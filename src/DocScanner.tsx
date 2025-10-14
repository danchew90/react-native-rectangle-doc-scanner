import React, { ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { View, TouchableOpacity, StyleSheet } from 'react-native';
import { Camera, useCameraDevice, useCameraPermission, useFrameProcessor } from 'react-native-vision-camera';
import { useResizePlugin } from 'vision-camera-resize-plugin';
import { useRunOnJS } from 'react-native-worklets-core';
import {
  OpenCV,
  ColorConversionCodes,
  MorphTypes,
  MorphShapes,
  RetrievalModes,
  ContourApproximationModes,
  ObjectType,
} from 'react-native-fast-opencv';
import { Overlay } from './utils/overlay';
import { checkStability } from './utils/stability';
import {
  blendQuads,
  isValidQuad,
  orderQuadPoints,
  quadArea,
  quadCenter,
  quadDistance,
  quadEdgeLengths,
  sanitizeQuad,
  weightedAverageQuad,
} from './utils/quad';
import type { Point } from './types';

const isConvexQuadrilateral = (points: Point[]) => {
  'worklet';
  try {
    if (points.length !== 4) {
      return false;
    }

    // Validate all points have valid x and y
    for (const p of points) {
      if (typeof p.x !== 'number' || typeof p.y !== 'number' ||
          !isFinite(p.x) || !isFinite(p.y)) {
        return false;
      }
    }

    let previous = 0;

    for (let i = 0; i < 4; i++) {
      const p0 = points[i];
      const p1 = points[(i + 1) % 4];
      const p2 = points[(i + 2) % 4];
      const cross = (p1.x - p0.x) * (p2.y - p1.y) - (p1.y - p0.y) * (p2.x - p1.x);

      // Relax the collinearity check - allow very small cross products
      if (Math.abs(cross) < 0.1) {
        return false;
      }

      if (i === 0) {
        previous = cross;
      } else if (previous * cross < 0) {
        return false;
      }
    }

    return true;
  } catch (err) {
    return false;
  }
};

type CameraRef = {
  takePhoto: (options: { qualityPrioritization: 'balanced' | 'quality' | 'speed' }) => Promise<{
    path: string;
  }>;
};

type CameraOverrides = Omit<React.ComponentProps<typeof Camera>, 'style' | 'ref' | 'frameProcessor'>;

/**
 * Configuration for detection quality and behavior
 */
export interface DetectionConfig {
  /** Processing resolution width (default: 1280) - higher = more accurate but slower */
  processingWidth?: number;
  /** Canny edge detection lower threshold (default: 40) */
  cannyLowThreshold?: number;
  /** Canny edge detection upper threshold (default: 120) */
  cannyHighThreshold?: number;
  /** Snap distance in pixels for corner locking (default: 8) */
  snapDistance?: number;
  /** Max frames to hold anchor when detection fails (default: 20) */
  maxAnchorMisses?: number;
  /** Maximum center movement allowed while maintaining lock (default: 200px) */
  maxCenterDelta?: number;
}

interface Props {
  onCapture?: (photo: { path: string; quad: Point[] | null; width: number; height: number }) => void;
  overlayColor?: string;
  autoCapture?: boolean;
  minStableFrames?: number;
  cameraProps?: CameraOverrides;
  children?: ReactNode;
  /** Advanced detection configuration */
  detectionConfig?: DetectionConfig;
}

export const DocScanner: React.FC<Props> = ({
  onCapture,
  overlayColor = '#e7a649',
  autoCapture = true,
  minStableFrames = 8,
  cameraProps,
  children,
  detectionConfig = {},
}) => {
  const device = useCameraDevice('back');
  const { hasPermission, requestPermission } = useCameraPermission();
  const { resize } = useResizePlugin();
  const camera = useRef<CameraRef | null>(null);
  const handleCameraRef = useCallback((ref: CameraRef | null) => {
    camera.current = ref;
  }, []);
  const [quad, setQuad] = useState<Point[] | null>(null);
  const [stable, setStable] = useState(0);

  useEffect(() => {
    requestPermission();
  }, [requestPermission]);

  const lastQuadRef = useRef<Point[] | null>(null);
  const smoothingBufferRef = useRef<Point[][]>([]);
  const anchorQuadRef = useRef<Point[] | null>(null);
  const anchorMissesRef = useRef(0);
  const anchorConfidenceRef = useRef(0);
  const lastMeasurementRef = useRef<Point[] | null>(null);
  const frameSizeRef = useRef<{ width: number; height: number } | null>(null);

  // Detection parameters - configurable via props with sensible defaults
  const PROCESSING_WIDTH = detectionConfig.processingWidth ?? 1280;
  const CANNY_LOW = detectionConfig.cannyLowThreshold ?? 40;
  const CANNY_HIGH = detectionConfig.cannyHighThreshold ?? 120;
  const SNAP_DISTANCE = detectionConfig.snapDistance ?? 8;
  const MAX_ANCHOR_MISSES = detectionConfig.maxAnchorMisses ?? 20;
  const REJECT_CENTER_DELTA = detectionConfig.maxCenterDelta ?? 200;

  // Fixed parameters for algorithm stability
  const MAX_HISTORY = 5;
  const SNAP_CENTER_DISTANCE = 18;
  const BLEND_DISTANCE = 80;
  const MAX_CENTER_DELTA = 120;
  const MAX_AREA_SHIFT = 0.55;
  const HISTORY_RESET_DISTANCE = 90;
  const MIN_AREA_RATIO = 0.0002;
  const MAX_AREA_RATIO = 0.9;
  const MIN_EDGE_RATIO = 0.015;
  const MIN_CONFIDENCE_TO_HOLD = 2;
  const MAX_ANCHOR_CONFIDENCE = 30;

  const updateQuad = useRunOnJS((value: Point[] | null) => {
    if (__DEV__) {
      console.log('[DocScanner] quad', value);
    }

    const fallbackToAnchor = (resetHistory: boolean) => {
      if (resetHistory) {
        smoothingBufferRef.current = [];
        lastMeasurementRef.current = null;
      }

      const anchor = anchorQuadRef.current;
      const anchorConfidence = anchorConfidenceRef.current;

      if (anchor && anchorConfidence >= MIN_CONFIDENCE_TO_HOLD) {
        anchorMissesRef.current += 1;

        if (anchorMissesRef.current <= MAX_ANCHOR_MISSES) {
          anchorConfidenceRef.current = Math.max(1, anchorConfidence - 1);
          lastQuadRef.current = anchor;
          setQuad(anchor);
          return true;
        }
      }

      anchorMissesRef.current = 0;
      anchorConfidenceRef.current = 0;
      anchorQuadRef.current = null;
      lastQuadRef.current = null;
      setQuad(null);
      return false;
    };

    if (!isValidQuad(value)) {
      const handled = fallbackToAnchor(false);
      if (handled) {
        return;
      }
      return;
    }

    anchorMissesRef.current = 0;

    const ordered = orderQuadPoints(value);
    const sanitized = sanitizeQuad(ordered);

    const frameSize = frameSizeRef.current;
    const frameArea = frameSize ? frameSize.width * frameSize.height : null;
    const area = quadArea(sanitized);
    const edges = quadEdgeLengths(sanitized);
    const minEdge = Math.min(...edges);
    const maxEdge = Math.max(...edges);
    const aspectRatio = maxEdge > 0 ? maxEdge / Math.max(minEdge, 1) : 0;

    const minEdgeThreshold = frameSize
      ? Math.max(14, Math.min(frameSize.width, frameSize.height) * MIN_EDGE_RATIO)
      : 14;

    const areaTooSmall = frameArea ? area < frameArea * MIN_AREA_RATIO : area === 0;
    const areaTooLarge = frameArea ? area > frameArea * MAX_AREA_RATIO : false;
    const edgesTooShort = minEdge < minEdgeThreshold;
    const aspectTooExtreme = aspectRatio > 7;

    if (areaTooSmall || areaTooLarge || edgesTooShort || aspectTooExtreme) {
      const handled = fallbackToAnchor(true);
      if (handled) {
        return;
      }
      return;
    }

    const lastMeasurement = lastMeasurementRef.current;
    const shouldResetHistory =
      lastMeasurement && quadDistance(lastMeasurement, sanitized) > HISTORY_RESET_DISTANCE;

    const existingHistory = shouldResetHistory ? [] : smoothingBufferRef.current;
    const nextHistory = existingHistory.length >= MAX_HISTORY
      ? [...existingHistory.slice(existingHistory.length - (MAX_HISTORY - 1)), sanitized]
      : [...existingHistory, sanitized];

    const hasHistory = nextHistory.length >= 2;
    let candidate = hasHistory ? weightedAverageQuad(nextHistory) : sanitized;

    const anchor = anchorQuadRef.current;
    if (anchor && isValidQuad(anchor)) {
      const delta = quadDistance(candidate, anchor);
      const anchorCenter = quadCenter(anchor);
      const candidateCenter = quadCenter(candidate);
      const anchorArea = quadArea(anchor);
      const candidateArea = quadArea(candidate);
      const centerDelta = Math.hypot(candidateCenter.x - anchorCenter.x, candidateCenter.y - anchorCenter.y);
      const areaShift = anchorArea > 0 ? Math.abs(anchorArea - candidateArea) / anchorArea : 0;

      if (centerDelta >= REJECT_CENTER_DELTA || areaShift > 1.2) {
        smoothingBufferRef.current = [sanitized];
        lastMeasurementRef.current = sanitized;
        anchorQuadRef.current = candidate;
        anchorConfidenceRef.current = 1;
        anchorMissesRef.current = 0;
        lastQuadRef.current = candidate;
        setQuad(candidate);
        return;
      }

      if (delta <= SNAP_DISTANCE && centerDelta <= SNAP_CENTER_DISTANCE && areaShift <= 0.08) {
        candidate = anchor;
        smoothingBufferRef.current = nextHistory;
        lastMeasurementRef.current = sanitized;
        anchorConfidenceRef.current = Math.min(anchorConfidenceRef.current + 1, MAX_ANCHOR_CONFIDENCE);
      } else if (delta <= BLEND_DISTANCE && centerDelta <= MAX_CENTER_DELTA && areaShift <= MAX_AREA_SHIFT) {
        const normalizedDelta = Math.min(1, delta / BLEND_DISTANCE);
        const adaptiveAlpha = 0.25 + normalizedDelta * 0.45; // 0.25..0.7 range
        candidate = blendQuads(anchor, candidate, adaptiveAlpha);
        smoothingBufferRef.current = nextHistory;
        lastMeasurementRef.current = sanitized;
        anchorConfidenceRef.current = Math.min(anchorConfidenceRef.current + 1, MAX_ANCHOR_CONFIDENCE);
      } else {
        const handled = fallbackToAnchor(true);
        if (handled) {
          return;
        }
        return;
      }
    } else {
      smoothingBufferRef.current = nextHistory;
      lastMeasurementRef.current = sanitized;
      anchorConfidenceRef.current = Math.min(anchorConfidenceRef.current + 1, MAX_ANCHOR_CONFIDENCE);
    }

    candidate = orderQuadPoints(candidate);
    anchorQuadRef.current = candidate;
    lastQuadRef.current = candidate;
    setQuad(candidate);
    anchorMissesRef.current = 0;
  }, []);

  const reportError = useRunOnJS((step: string, error: unknown) => {
    const message = error instanceof Error ? error.message : `${error}`;
    console.warn(`[DocScanner] frame error at ${step}: ${message}`);
  }, []);

  const reportStage = useRunOnJS((_stage: string) => {
    // Disabled for performance
  }, []);

  const [frameSize, setFrameSize] = useState<{ width: number; height: number } | null>(null);
  const updateFrameSize = useRunOnJS((width: number, height: number) => {
    frameSizeRef.current = { width, height };
    setFrameSize({ width, height });
  }, []);

  const frameProcessor = useFrameProcessor((frame) => {
    'worklet';

    let step = 'start';

    try {
      // Report frame size for coordinate transformation
      updateFrameSize(frame.width, frame.height);

      // Use configurable resolution for accuracy vs performance balance
      const ratio = PROCESSING_WIDTH / frame.width;
      const width = Math.floor(frame.width * ratio);
      const height = Math.floor(frame.height * ratio);
      step = 'resize';
      reportStage(step);
      const resized = resize(frame, {
        dataType: 'uint8',
        pixelFormat: 'bgr',
        scale: { width: width, height: height },
      });

      step = 'frameBufferToMat';
      reportStage(step);
      let mat = OpenCV.frameBufferToMat(height, width, 3, resized);

      step = 'cvtColor';
      reportStage(step);
      OpenCV.invoke('cvtColor', mat, mat, ColorConversionCodes.COLOR_BGR2GRAY);

      // Enhanced morphological operations for noise reduction
      const morphologyKernel = OpenCV.createObject(ObjectType.Size, 7, 7);
      step = 'getStructuringElement';
      reportStage(step);
      const element = OpenCV.invoke('getStructuringElement', MorphShapes.MORPH_RECT, morphologyKernel);
      step = 'morphologyEx';
      reportStage(step);
      // MORPH_CLOSE to fill small holes in edges
      OpenCV.invoke('morphologyEx', mat, mat, MorphTypes.MORPH_CLOSE, element);
      // MORPH_OPEN to remove small noise
      OpenCV.invoke('morphologyEx', mat, mat, MorphTypes.MORPH_OPEN, element);

      // Bilateral filter for edge-preserving smoothing (better quality than Gaussian)
      step = 'bilateralFilter';
      reportStage(step);
      try {
        const tempMat = OpenCV.createObject(ObjectType.Mat);
        OpenCV.invoke('bilateralFilter', mat, tempMat, 9, 75, 75);
        mat = tempMat;
      } catch (error) {
        if (__DEV__) {
          console.warn('[DocScanner] bilateralFilter unavailable, falling back to GaussianBlur', error);
        }
        step = 'gaussianBlurFallback';
        reportStage(step);
        const blurKernel = OpenCV.createObject(ObjectType.Size, 5, 5);
        OpenCV.invoke('GaussianBlur', mat, mat, blurKernel, 0);
      }

      step = 'Canny';
      reportStage(step);
      // Configurable Canny parameters for adaptive edge detection
      OpenCV.invoke('Canny', mat, mat, CANNY_LOW, CANNY_HIGH);

      step = 'createContours';
      reportStage(step);
      const contours = OpenCV.createObject(ObjectType.PointVectorOfVectors);
      OpenCV.invoke('findContours', mat, contours, RetrievalModes.RETR_EXTERNAL, ContourApproximationModes.CHAIN_APPROX_SIMPLE);

      let best: Point[] | null = null;
      let maxArea = 0;
      const frameArea = width * height;

      step = 'toJSValue';
      reportStage(step);
      const contourVector = OpenCV.toJSValue(contours);
      const contourArray = Array.isArray(contourVector?.array) ? contourVector.array : [];

      for (let i = 0; i < contourArray.length; i += 1) {
        step = `contour_${i}_copy`;
        reportStage(step);
        const contour = OpenCV.copyObjectFromVector(contours, i);

        // Compute absolute area first
        step = `contour_${i}_area_abs`;
        reportStage(step);
        const { value: area } = OpenCV.invoke('contourArea', contour, false);

        // Skip extremely small contours, but keep threshold very low to allow distant documents
        if (typeof area !== 'number' || !isFinite(area)) {
          continue;
        }

        if (area < 50) {
          continue;
        }

        step = `contour_${i}_area`; // ratio stage
        reportStage(step);
        const areaRatio = area / frameArea;

        if (__DEV__) {
          console.log('[DocScanner] area', area, 'ratio', areaRatio);
        }

        // Skip if area ratio is too small or too large
        if (areaRatio < 0.0002 || areaRatio > 0.99) {
          continue;
        }

        // Try to use convex hull for better corner detection
        let contourToUse = contour;
        try {
          step = `contour_${i}_convexHull`;
          reportStage(step);
          const hull = OpenCV.createObject(ObjectType.PointVector);
          OpenCV.invoke('convexHull', contour, hull, false, true);
          contourToUse = hull;
        } catch (err) {
          // If convexHull fails, use original contour
          if (__DEV__) {
            console.warn('[DocScanner] convexHull failed, using original contour');
          }
        }

        step = `contour_${i}_arcLength`;
        reportStage(step);
        const { value: perimeter } = OpenCV.invoke('arcLength', contourToUse, true);
        const approx = OpenCV.createObject(ObjectType.PointVector);

        let approxArray: Array<{ x: number; y: number }> = [];

        // Try more epsilon values from 0.1% to 10% for difficult shapes
        const epsilonValues = [
          0.001, 0.002, 0.003, 0.004, 0.005, 0.006, 0.007, 0.008, 0.009,
          0.01, 0.012, 0.015, 0.018, 0.02, 0.025, 0.03, 0.04, 0.05, 0.06, 0.07, 0.08, 0.09, 0.1
        ];

        for (let attempt = 0; attempt < epsilonValues.length; attempt += 1) {
          const epsilon = epsilonValues[attempt] * perimeter;
          step = `contour_${i}_approxPolyDP_attempt_${attempt}`;
          reportStage(step);
          OpenCV.invoke('approxPolyDP', contourToUse, approx, epsilon, true);

          step = `contour_${i}_toJS_attempt_${attempt}`;
          reportStage(step);
          const approxValue = OpenCV.toJSValue(approx);
          const candidate = Array.isArray(approxValue?.array) ? approxValue.array : [];

          if (__DEV__) {
            console.log('[DocScanner] approx length', candidate.length, 'epsilon', epsilon);
          }

          if (candidate.length === 4) {
            approxArray = candidate as Array<{ x: number; y: number }>;
            break;
          }
        }

        // Only proceed if we found exactly 4 corners
        if (approxArray.length !== 4) {
          continue;
        }

        step = `contour_${i}_convex`;
        reportStage(step);

        // Validate points before processing
        const isValidPoint = (pt: { x: number; y: number }) => {
          return typeof pt.x === 'number' && typeof pt.y === 'number' &&
                 !isNaN(pt.x) && !isNaN(pt.y) &&
                 isFinite(pt.x) && isFinite(pt.y);
        };

        if (!approxArray.every(isValidPoint)) {
          if (__DEV__) {
            console.warn('[DocScanner] invalid points in approxArray', approxArray);
          }
          continue;
        }

        const points: Point[] = approxArray.map((pt: { x: number; y: number }) => ({
          x: pt.x / ratio,
          y: pt.y / ratio,
        }));

        // Verify the quadrilateral is convex (valid document shape)
        try {
          if (!isConvexQuadrilateral(points)) {
            if (__DEV__) {
              console.log('[DocScanner] not convex, skipping:', points);
            }
            continue;
          }
        } catch (err) {
          if (__DEV__) {
            console.warn('[DocScanner] convex check error:', err, 'points:', points);
          }
          continue;
        }

        if (area > maxArea) {
          best = points;
          maxArea = area;
        }
      }

      step = 'clearBuffers';
      reportStage(step);
      OpenCV.clearBuffers();
      step = 'updateQuad';
      reportStage(step);
      updateQuad(best);
    } catch (error) {
      reportError(step, error);
    }
  }, [resize, reportError, updateQuad]);

  useEffect(() => {
    const s = checkStability(quad);
    setStable(s);
  }, [quad]);

  useEffect(() => {
    const capture = async () => {
      if (autoCapture && quad && stable >= minStableFrames && camera.current && frameSize) {
        const photo = await camera.current.takePhoto({ qualityPrioritization: 'quality' });
        onCapture?.({
          path: photo.path,
          quad,
          width: frameSize.width,
          height: frameSize.height,
        });
        setStable(0);
      }
    };

    capture();
  }, [autoCapture, minStableFrames, onCapture, quad, stable, frameSize]);

  const { device: overrideDevice, ...cameraRestProps } = cameraProps ?? {};
  const resolvedDevice = overrideDevice ?? device;

  if (!resolvedDevice || !hasPermission) {
    return null;
  }

  return (
    <View style={{ flex: 1 }}>
      <Camera
        ref={handleCameraRef}
        style={StyleSheet.absoluteFillObject}
        device={resolvedDevice}
        isActive
        photo
        frameProcessor={frameProcessor}
        frameProcessorFps={15}
        {...cameraRestProps}
      />
      <Overlay quad={quad} color={overlayColor} frameSize={frameSize} />
      {!autoCapture && (
        <TouchableOpacity
          style={styles.button}
          onPress={async () => {
            if (!camera.current || !frameSize) {
              return;
            }

            const photo = await camera.current.takePhoto({ qualityPrioritization: 'quality' });
            onCapture?.({
              path: photo.path,
              quad,
              width: frameSize.width,
              height: frameSize.height,
            });
          }}
        />
      )}
      {children}
    </View>
  );
};

const styles = StyleSheet.create({
  button: {
    position: 'absolute',
    bottom: 40,
    alignSelf: 'center',
    width: 70,
    height: 70,
    borderRadius: 35,
    backgroundColor: '#fff',
  },
});
