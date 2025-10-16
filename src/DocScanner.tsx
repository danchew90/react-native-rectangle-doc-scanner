import React, {
  ComponentType,
  ReactNode,
  forwardRef,
  useCallback,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  findNodeHandle,
  NativeModules,
  requireNativeComponent,
  StyleSheet,
  TouchableOpacity,
  View,
} from 'react-native';
import type { NativeSyntheticEvent } from 'react-native';
import { Overlay } from './utils/overlay';
import type { Point } from './types';

const MODULE_NAME = 'RNRDocScannerModule';
const VIEW_NAME = 'RNRDocScannerView';

const NativeDocScannerModule = NativeModules[MODULE_NAME];

if (!NativeDocScannerModule) {
  const fallbackMessage =
    `The native module '${MODULE_NAME}' is not linked. Make sure you have run pod install, ` +
    `synced Gradle, and rebuilt the app after installing 'react-native-rectangle-doc-scanner'.`;
  throw new Error(fallbackMessage);
}

type NativeRectangle = {
  topLeft: Point;
  topRight: Point;
  bottomRight: Point;
  bottomLeft: Point;
};

type RectangleEvent = {
  rectangleCoordinates: NativeRectangle | null;
  stableCounter: number;
  frameWidth: number;
  frameHeight: number;
};

type PictureEvent = {
  croppedImage?: string | null;
  initialImage?: string;
  width?: number;
  height?: number;
};

type NativeDocScannerProps = {
  style?: object;
  detectionCountBeforeCapture?: number;
  autoCapture?: boolean;
  enableTorch?: boolean;
  quality?: number;
  useBase64?: boolean;
  onRectangleDetect?: (event: NativeSyntheticEvent<RectangleEvent>) => void;
  onPictureTaken?: (event: NativeSyntheticEvent<PictureEvent>) => void;
};

type DocScannerHandle = {
  capture: () => Promise<PictureEvent>;
  reset: () => void;
};

const NativeDocScanner = requireNativeComponent<NativeDocScannerProps>(VIEW_NAME);

export interface DetectionConfig {
  processingWidth?: number;
  cannyLowThreshold?: number;
  cannyHighThreshold?: number;
  snapDistance?: number;
  maxAnchorMisses?: number;
  maxCenterDelta?: number;
}

interface Props {
  onCapture?: (photo: { path: string; quad: Point[] | null; width: number; height: number }) => void;
  overlayColor?: string;
  autoCapture?: boolean;
  minStableFrames?: number;
  enableTorch?: boolean;
  quality?: number;
  useBase64?: boolean;
  children?: ReactNode;
  showGrid?: boolean;
  gridColor?: string;
  gridLineWidth?: number;
  detectionConfig?: DetectionConfig;
}

const DEFAULT_OVERLAY_COLOR = '#e7a649';
const GRID_COLOR_FALLBACK = 'rgba(231, 166, 73, 0.35)';

export const DocScanner = forwardRef<DocScannerHandle, Props>(({
  onCapture,
  overlayColor = DEFAULT_OVERLAY_COLOR,
  autoCapture = true,
  minStableFrames = 8,
  enableTorch = false,
  quality = 90,
  useBase64 = false,
  children,
  showGrid = true,
  gridColor,
  gridLineWidth = 2,
}, ref) => {
  const viewRef = useRef<any>(null);
  const capturingRef = useRef(false);
  const [quad, setQuad] = useState<Point[] | null>(null);
  const [stable, setStable] = useState(0);
  const [frameSize, setFrameSize] = useState<{ width: number; height: number } | null>(null);

  const effectiveGridColor = useMemo(
    () => gridColor ?? GRID_COLOR_FALLBACK,
    [gridColor],
  );

  const ensureViewHandle = useCallback(() => {
    const nodeHandle = findNodeHandle(viewRef.current);
    if (!nodeHandle) {
      throw new Error('Unable to obtain native view handle for DocScanner.');
    }
    return nodeHandle;
  }, []);

  const resetNativeStability = useCallback(() => {
    try {
      const handle = ensureViewHandle();
      NativeDocScannerModule.reset(handle);
    } catch (error) {
      console.warn('[DocScanner] unable to reset native stability', error);
    }
  }, [ensureViewHandle]);

  const emitCaptureResult = useCallback(
    (payload: PictureEvent) => {
      capturingRef.current = false;

      const path = payload.croppedImage ?? payload.initialImage;
      if (!path) {
        return;
      }

      const width = payload.width ?? frameSize?.width ?? 0;
      const height = payload.height ?? frameSize?.height ?? 0;
      onCapture?.({
        path,
        quad,
        width,
        height,
      });
      setStable(0);
      resetNativeStability();
    },
    [frameSize, onCapture, quad, resetNativeStability],
  );

  const handleRectangleDetect = useCallback(
    (event: NativeSyntheticEvent<RectangleEvent>) => {
      const { rectangleCoordinates, stableCounter, frameWidth, frameHeight } = event.nativeEvent;
      setStable(stableCounter);
      setFrameSize({ width: frameWidth, height: frameHeight });

      if (!rectangleCoordinates) {
        setQuad(null);
        return;
      }

      setQuad([
        rectangleCoordinates.topLeft,
        rectangleCoordinates.topRight,
        rectangleCoordinates.bottomRight,
        rectangleCoordinates.bottomLeft,
      ]);

      if (autoCapture && stableCounter >= minStableFrames) {
        triggerCapture();
      }
    },
    [autoCapture, minStableFrames],
  );

  const handlePictureTaken = useCallback(
    (event: NativeSyntheticEvent<PictureEvent>) => {
      emitCaptureResult(event.nativeEvent);
    },
    [emitCaptureResult],
  );

  const captureNative = useCallback((): Promise<PictureEvent> => {
    if (capturingRef.current) {
      return Promise.reject(new Error('capture_in_progress'));
    }

    try {
      const handle = ensureViewHandle();
      capturingRef.current = true;
      return NativeDocScannerModule.capture(handle)
        .then((result: PictureEvent) => {
          emitCaptureResult(result);
          return result;
        })
        .catch((error: Error) => {
          capturingRef.current = false;
          throw error;
        });
    } catch (error) {
      capturingRef.current = false;
      return Promise.reject(error);
    }
  }, [emitCaptureResult, ensureViewHandle]);

  const triggerCapture = useCallback(() => {
    if (capturingRef.current) {
      return;
    }

    captureNative().catch((error: Error) => {
      console.warn('[DocScanner] capture failed', error);
    });
  }, [captureNative]);

  const handleManualCapture = useCallback(() => {
    if (autoCapture) {
      return;
    }
    captureNative().catch((error: Error) => {
      console.warn('[DocScanner] manual capture failed', error);
    });
  }, [autoCapture, captureNative]);

  useImperativeHandle(
    ref,
    () => ({
      capture: captureNative,
      reset: () => {
        setStable(0);
        resetNativeStability();
      },
    }),
    [captureNative, resetNativeStability],
  );

  return (
    <View style={styles.container}>
      <NativeDocScanner
        ref={viewRef as React.Ref<ComponentType<NativeDocScannerProps>>}
        style={StyleSheet.absoluteFill}
        detectionCountBeforeCapture={minStableFrames}
        autoCapture={autoCapture}
        enableTorch={enableTorch}
        quality={quality}
        useBase64={useBase64}
        onRectangleDetect={handleRectangleDetect}
        onPictureTaken={handlePictureTaken}
      />
      <Overlay
        quad={quad}
        color={overlayColor}
        frameSize={frameSize}
        showGrid={showGrid}
        gridColor={effectiveGridColor}
        gridLineWidth={gridLineWidth}
      />
      {!autoCapture && (
        <TouchableOpacity style={styles.button} onPress={handleManualCapture} />
      )}
      {children}
    </View>
  );
});

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
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

export type { DocScannerHandle };
