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

        if (area < width * height * 0.02) {
          continue;
        }

        step = `contour_${i}_arcLength`;
        reportStage(step);
        const { value: perimeter } = OpenCV.invoke('arcLength', contour, true);
        const approx = OpenCV.createObject(ObjectType.PointVector);

        step = `contour_${i}_approxPolyDP`;
        reportStage(step);
        OpenCV.invoke('approxPolyDP', contour, approx, 0.012 * perimeter, true);

        step = `contour_${i}_toJS`;
        reportStage(step);
        const approxValue = OpenCV.toJSValue(approx);
        const approxArray = Array.isArray(approxValue?.array) ? approxValue.array : [];

        if (__DEV__) {
          reportStage(`${step}_length_${approxArray.length}`);
        }

        if (approxArray.length !== 4) {
          continue;
        }

        step = `contour_${i}_convex`;
        reportStage(step);
        const points: Point[] = approxArray.map((pt: { x: number; y: number }) => ({
          x: pt.x / ratio,
          y: pt.y / ratio,
        }));

        if (!isConvexQuadrilateral(points)) {
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
