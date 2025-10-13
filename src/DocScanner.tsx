import React, { ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { View, TouchableOpacity, StyleSheet } from 'react-native';
import { Camera, useCameraDevice, useCameraPermission, useFrameProcessor } from 'react-native-vision-camera';
import { useResizePlugin } from 'vision-camera-resize-plugin';
import { runOnJS } from 'react-native-reanimated';
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

  const frameProcessor = useFrameProcessor((frame) => {
    'worklet';

    const ratio = 480 / frame.width;
    const w = Math.floor(frame.width * ratio);
    const h = Math.floor(frame.height * ratio);
    const resized = resize(frame, {
      dataType: 'uint8',
      pixelFormat: 'bgr',
      scale: { width: w, height: h },
    });

    const mat = OpenCV.frameBufferToMat(h, w, 3, resized);

    OpenCV.invoke('cvtColor', mat, mat, ColorConversionCodes.COLOR_BGR2GRAY);

    const kernel = OpenCV.createObject(ObjectType.Size, 4, 4);
    const element = OpenCV.invoke('getStructuringElement', MorphShapes.MORPH_RECT, kernel);
    OpenCV.invoke('morphologyEx', mat, mat, MorphTypes.MORPH_OPEN, element);

    OpenCV.invoke('GaussianBlur', mat, mat, kernel, 0);
    OpenCV.invoke('Canny', mat, mat, 75, 100);

    const contours = OpenCV.createObject(ObjectType.PointVectorOfVectors);
    OpenCV.invoke('findContours', mat, contours, RetrievalModes.RETR_LIST, ContourApproximationModes.CHAIN_APPROX_SIMPLE);

    let best: Point[] | null = null;
    let maxArea = 0;

    const arr = OpenCV.toJSValue(contours).array;

    for (let i = 0; i < arr.length; i++) {
      const c = OpenCV.copyObjectFromVector(contours, i);
      const { value: area } = OpenCV.invoke('contourArea', c, false);

      if (area < w * h * 0.1) {
        continue;
      }

      const { value: peri } = OpenCV.invoke('arcLength', c, true);
      const approx = OpenCV.createObject(ObjectType.PointVector);
      OpenCV.invoke('approxPolyDP', c, approx, 0.02 * peri, true);
      const size = OpenCV.invokeWithOutParam('size', approx);
      if (size !== 4) {
        continue;
      }

      const pts: Point[] = [];
      for (let j = 0; j < 4; j++) {
        const p = OpenCV.invoke('atPoint', approx, j, 0);
        pts.push({ x: p.x / ratio, y: p.y / ratio });
      }

      if (!isConvexQuadrilateral(pts)) {
        continue;
      }

      if (area > maxArea) {
        best = pts;
        maxArea = area;
      }
    }

    OpenCV.clearBuffers();
    runOnJS(setQuad)(best);
  }, [resize]);

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
