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
import type { Point } from './types';

const isConvexQuadrilateral = (points: Point[]) => {
  if (points.length !== 4) {
    return false;
  }

  let previous = 0;

  for (let i = 0; i < 4; i++) {
    const p0 = points[i];
    const p1 = points[(i + 1) % 4];
    const p2 = points[(i + 2) % 4];
    const cross = (p1.x - p0.x) * (p2.y - p1.y) - (p1.y - p0.y) * (p2.x - p1.x);

    if (Math.abs(cross) < 1e-3) {
      return false;
    }

    if (i === 0) {
      previous = cross;
    } else if (previous * cross < 0) {
      return false;
    }
  }

  return true;
};

type CameraRef = {
  takePhoto: (options: { qualityPrioritization: 'balanced' | 'quality' | 'speed' }) => Promise<{
    path: string;
  }>;
};

type CameraOverrides = Omit<React.ComponentProps<typeof Camera>, 'style' | 'ref' | 'frameProcessor'>;

interface Props {
  onCapture?: (photo: { path: string; quad: Point[] | null }) => void;
  overlayColor?: string;
  autoCapture?: boolean;
  minStableFrames?: number;
  cameraProps?: CameraOverrides;
  children?: ReactNode;
}

export const DocScanner: React.FC<Props> = ({
  onCapture,
  overlayColor = '#e7a649',
  autoCapture = true,
  minStableFrames = 8,
  cameraProps,
  children,
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

  const updateQuad = useRunOnJS((value: Point[] | null) => {
    if (__DEV__) {
      console.log('[DocScanner] quad', value);
    }
    setQuad(value);
  }, []);

  const reportError = useRunOnJS((step: string, error: unknown) => {
    const message = error instanceof Error ? error.message : `${error}`;
    console.warn(`[DocScanner] frame error at ${step}: ${message}`);
  }, []);

  const reportStage = useRunOnJS((stage: string) => {
    if (__DEV__) {
      console.log('[DocScanner] stage', stage);
    }
  }, []);

  const frameProcessor = useFrameProcessor((frame) => {
    'worklet';

    let step = 'start';

    try {
      const ratio = 480 / frame.width;
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
      const mat = OpenCV.frameBufferToMat(height, width, 3, resized);

      step = 'cvtColor';
      reportStage(step);
      OpenCV.invoke('cvtColor', mat, mat, ColorConversionCodes.COLOR_BGR2GRAY);

      const morphologyKernel = OpenCV.createObject(ObjectType.Size, 5, 5);
      step = 'getStructuringElement';
      reportStage(step);
      const element = OpenCV.invoke('getStructuringElement', MorphShapes.MORPH_RECT, morphologyKernel);
      step = 'morphologyEx';
      reportStage(step);
      OpenCV.invoke('morphologyEx', mat, mat, MorphTypes.MORPH_OPEN, element);

      const gaussianKernel = OpenCV.createObject(ObjectType.Size, 5, 5);
      step = 'GaussianBlur';
      reportStage(step);
      OpenCV.invoke('GaussianBlur', mat, mat, gaussianKernel, 0);
      step = 'Canny';
      reportStage(step);
      OpenCV.invoke('Canny', mat, mat, 75, 100);

      step = 'createContours';
      reportStage(step);
      const contours = OpenCV.createObject(ObjectType.PointVectorOfVectors);
      OpenCV.invoke('findContours', mat, contours, RetrievalModes.RETR_LIST, ContourApproximationModes.CHAIN_APPROX_SIMPLE);

      let best: Point[] | null = null;
      let maxArea = 0;

      step = 'toJSValue';
      reportStage(step);
      const contourVector = OpenCV.toJSValue(contours);
      const contourArray = Array.isArray(contourVector?.array) ? contourVector.array : [];

      for (let i = 0; i < contourArray.length; i += 1) {
        step = `contour_${i}_copy`;
        reportStage(step);
        const contour = OpenCV.copyObjectFromVector(contours, i);

        step = `contour_${i}_area`;
        reportStage(step);
        const { value: area } = OpenCV.invoke('contourArea', contour, false);

        if (__DEV__) {
          console.log('[DocScanner] area ratio', area / (width * height));
        }

        // Lower threshold to detect smaller documents
        if (area < width * height * 0.0001) {
          continue;
        }

        step = `contour_${i}_arcLength`;
        reportStage(step);
        const { value: perimeter } = OpenCV.invoke('arcLength', contour, true);
        const approx = OpenCV.createObject(ObjectType.PointVector);

        let approxArray: Array<{ x: number; y: number }> = [];
        let usedBoundingRect = false;
        let epsilonBase = 0.006 * perimeter;

        for (let attempt = 0; attempt < 10; attempt += 1) {
          const epsilon = epsilonBase * (1 + attempt);
          step = `contour_${i}_approxPolyDP_attempt_${attempt}`;
          reportStage(step);
          OpenCV.invoke('approxPolyDP', contour, approx, epsilon, true);

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

          if (approxArray.length === 0 || Math.abs(candidate.length - 4) < Math.abs(approxArray.length - 4)) {
            approxArray = candidate as Array<{ x: number; y: number }>;
          }
        }

        if (approxArray.length !== 4) {
          // fallback: boundingRect (axis-aligned) so we always have 4 points
          try {
            const rect = OpenCV.invoke('boundingRect', contour);
            // Convert the rect object to JS value to get actual coordinates
            const rectJS = OpenCV.toJSValue(rect);
            const rectValue = rectJS?.value ?? rectJS;

            const rectX = rectValue?.x ?? 0;
            const rectY = rectValue?.y ?? 0;
            const rectW = rectValue?.width ?? 0;
            const rectH = rectValue?.height ?? 0;

            // Validate that we have a valid rectangle
            if (rectW > 0 && rectH > 0) {
              approxArray = [
                { x: rectX, y: rectY },
                { x: rectX + rectW, y: rectY },
                { x: rectX + rectW, y: rectY + rectH },
                { x: rectX, y: rectY + rectH },
              ];
              usedBoundingRect = true;

              if (__DEV__) {
                console.log('[DocScanner] using boundingRect fallback:', approxArray);
              }
            }
          } catch (err) {
            if (__DEV__) {
              console.warn('[DocScanner] boundingRect fallback failed:', err);
            }
          }
        }

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

        // Skip convexity check for boundingRect (always forms a valid rectangle)
        if (!usedBoundingRect) {
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
      if (autoCapture && quad && stable >= minStableFrames && camera.current) {
        const photo = await camera.current.takePhoto({ qualityPrioritization: 'quality' });
        onCapture?.({ path: photo.path, quad });
        setStable(0);
      }
    };

    capture();
  }, [autoCapture, minStableFrames, onCapture, quad, stable]);

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
      <Overlay quad={quad} color={overlayColor} />
      {!autoCapture && (
        <TouchableOpacity
          style={styles.button}
          onPress={async () => {
            if (!camera.current) {
              return;
            }

            const photo = await camera.current.takePhoto({ qualityPrioritization: 'quality' });
            onCapture?.({ path: photo.path, quad });
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
