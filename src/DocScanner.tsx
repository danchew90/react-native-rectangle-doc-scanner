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

type DetectionCandidate = {
  quad: Point[];
  area: number;
  label: string;
};

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
      OpenCV.invoke('morphologyEx', mat, mat, MorphTypes.MORPH_CLOSE, element);
      OpenCV.invoke('morphologyEx', mat, mat, MorphTypes.MORPH_OPEN, element);

      const ADAPTIVE_THRESH_GAUSSIAN_C = 1;
      const THRESH_BINARY = 0;
      const THRESH_OTSU = 8;

      // Bilateral filter for edge-preserving smoothing (better quality than Gaussian)
      step = 'bilateralFilter';
      reportStage(step);
      let processed = mat;
      try {
        const tempMat = OpenCV.createObject(ObjectType.Mat);
        OpenCV.invoke('bilateralFilter', mat, tempMat, 9, 75, 75);
        processed = tempMat;
      } catch (error) {
        if (__DEV__) {
          console.warn('[DocScanner] bilateralFilter unavailable, falling back to GaussianBlur', error);
        }
        const blurKernel = OpenCV.createObject(ObjectType.Size, 5, 5);
        OpenCV.invoke('GaussianBlur', mat, mat, blurKernel, 0);
        processed = mat;
      }

      // Additional blur and close pass to smooth jagged edges
      step = 'gaussianBlur';
      reportStage(step);
      const gaussianKernel = OpenCV.createObject(ObjectType.Size, 5, 5);
      OpenCV.invoke('GaussianBlur', processed, processed, gaussianKernel, 0);
      OpenCV.invoke('morphologyEx', processed, processed, MorphTypes.MORPH_CLOSE, element);

      const baseMat = OpenCV.invoke('clone', processed);
      const frameArea = width * height;
      const originalArea = frame.width * frame.height;
      const minEdgeThreshold = Math.max(14, Math.min(frame.width, frame.height) * MIN_EDGE_RATIO);
      const epsilonValues = [
        0.001, 0.002, 0.003, 0.004, 0.005, 0.006, 0.007, 0.008, 0.009,
        0.01, 0.012, 0.015, 0.018, 0.02, 0.025, 0.03, 0.035, 0.04, 0.05,
        0.06, 0.07, 0.08, 0.09, 0.1, 0.12,
      ];

      let bestCandidate: DetectionCandidate | null = null;

      const considerCandidate = (candidate: DetectionCandidate | null) => {
        'worklet';
        if (!candidate) {
          return;
        }
        if (!bestCandidate || candidate.area > bestCandidate.area) {
          bestCandidate = candidate;
        }
      };

      const evaluateContours = (inputMat: unknown, attemptLabel: string): DetectionCandidate | null => {
        'worklet';

        step = `findContours_${attemptLabel}`;
        reportStage(step);
        const contours = OpenCV.createObject(ObjectType.PointVectorOfVectors);
        OpenCV.invoke('findContours', inputMat, contours, RetrievalModes.RETR_EXTERNAL, ContourApproximationModes.CHAIN_APPROX_SIMPLE);

        const contourVector = OpenCV.toJSValue(contours);
        const contourArray = Array.isArray(contourVector?.array) ? contourVector.array : [];

        let bestLocal: DetectionCandidate | null = null;

        for (let i = 0; i < contourArray.length; i += 1) {
          step = `${attemptLabel}_contour_${i}_copy`;
          reportStage(step);
          const contour = OpenCV.copyObjectFromVector(contours, i);

          step = `${attemptLabel}_contour_${i}_area`;
          reportStage(step);
          const { value: area } = OpenCV.invoke('contourArea', contour, false);
          if (typeof area !== 'number' || !isFinite(area) || area < 60) {
            continue;
          }

          const resizedRatio = area / frameArea;
          if (resizedRatio < 0.00012 || resizedRatio > 0.98) {
            continue;
          }

          let contourToUse = contour;
          try {
            const hull = OpenCV.createObject(ObjectType.PointVector);
            OpenCV.invoke('convexHull', contour, hull, false, true);
            contourToUse = hull;
          } catch (err) {
            if (__DEV__) {
              console.warn('[DocScanner] convexHull failed, using original contour');
            }
          }

          const { value: perimeter } = OpenCV.invoke('arcLength', contourToUse, true);
          if (typeof perimeter !== 'number' || !isFinite(perimeter) || perimeter < 80) {
            continue;
          }

          const approx = OpenCV.createObject(ObjectType.PointVector);
          let approxArray: Array<{ x: number; y: number }> = [];

          for (let attempt = 0; attempt < epsilonValues.length; attempt += 1) {
            const epsilon = epsilonValues[attempt] * perimeter;
            step = `${attemptLabel}_contour_${i}_approx_${attempt}`;
            reportStage(step);
            OpenCV.invoke('approxPolyDP', contourToUse, approx, epsilon, true);

            const approxValue = OpenCV.toJSValue(approx);
            const candidate = Array.isArray(approxValue?.array) ? approxValue.array : [];
            if (candidate.length === 4) {
              approxArray = candidate as Array<{ x: number; y: number }>;
              break;
            }
          }

          if (approxArray.length !== 4) {
            continue;
          }

          const isValidPoint = (pt: { x: number; y: number }) =>
            typeof pt.x === 'number' && typeof pt.y === 'number' && isFinite(pt.x) && isFinite(pt.y);

          if (!approxArray.every(isValidPoint)) {
            continue;
          }

          const normalizedPoints: Point[] = approxArray.map((pt) => ({
            x: pt.x / ratio,
            y: pt.y / ratio,
          }));

          if (!isConvexQuadrilateral(normalizedPoints)) {
            continue;
          }

          const sanitized = sanitizeQuad(orderQuadPoints(normalizedPoints));
          if (!isValidQuad(sanitized)) {
            continue;
          }

          const edges = quadEdgeLengths(sanitized);
          const minEdge = Math.min(...edges);
          const maxEdge = Math.max(...edges);
          if (!Number.isFinite(minEdge) || minEdge < minEdgeThreshold) {
            continue;
          }
          const aspectRatio = maxEdge / Math.max(minEdge, 1);
          if (!Number.isFinite(aspectRatio) || aspectRatio > 8.5) {
            continue;
          }

          const quadAreaValue = quadArea(sanitized);
          const originalRatio = originalArea > 0 ? quadAreaValue / originalArea : 0;
          if (originalRatio < 0.00012 || originalRatio > 0.92) {
            continue;
          }

          const candidate: DetectionCandidate = {
            quad: sanitized,
            area: quadAreaValue,
            label: attemptLabel,
          };

          if (!bestLocal || candidate.area > bestLocal.area) {
            bestLocal = candidate;
          }
        }

        return bestLocal;
      };

      const runCanny = (label: string, low: number, high: number) => {
        'worklet';
        const working = OpenCV.invoke('clone', baseMat);
        step = `${label}_canny`;
        reportStage(step);
        OpenCV.invoke('Canny', working, working, low, high);
        OpenCV.invoke('morphologyEx', working, working, MorphTypes.MORPH_CLOSE, element);
        considerCandidate(evaluateContours(working, label));
      };

      const runAdaptive = (label: string, blockSize: number, c: number) => {
        'worklet';
        const working = OpenCV.invoke('clone', baseMat);
        step = `${label}_adaptive`;
        reportStage(step);
        OpenCV.invoke('adaptiveThreshold', working, working, 255, ADAPTIVE_THRESH_GAUSSIAN_C, THRESH_BINARY, blockSize, c);
        OpenCV.invoke('morphologyEx', working, working, MorphTypes.MORPH_CLOSE, element);
        considerCandidate(evaluateContours(working, label));
      };

      const runOtsu = () => {
        'worklet';
        const working = OpenCV.invoke('clone', baseMat);
        step = 'otsu_threshold';
        reportStage(step);
        OpenCV.invoke('threshold', working, working, 0, 255, THRESH_BINARY | THRESH_OTSU);
        OpenCV.invoke('morphologyEx', working, working, MorphTypes.MORPH_CLOSE, element);
        considerCandidate(evaluateContours(working, 'otsu'));
      };

      runCanny('canny_primary', CANNY_LOW, CANNY_HIGH);
      runCanny('canny_soft', Math.max(6, CANNY_LOW * 0.6), Math.max(CANNY_LOW * 1.2, CANNY_HIGH * 0.75));
      runCanny('canny_hard', Math.max(12, CANNY_LOW * 1.1), CANNY_HIGH * 1.25);

      runAdaptive('adaptive_19', 19, 7);
      runAdaptive('adaptive_23', 23, 5);
      runOtsu();

      step = 'clearBuffers';
      reportStage(step);
      OpenCV.clearBuffers();
      step = 'updateQuad';
      reportStage(step);
      if (bestCandidate) {
        updateQuad(bestCandidate.quad);
      } else {
        updateQuad(null);
      }
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
