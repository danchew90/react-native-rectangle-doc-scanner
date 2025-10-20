import React, {
  ReactNode,
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Platform, StyleSheet, TouchableOpacity, View } from 'react-native';
import DocumentScanner from 'react-native-document-scanner';
import type { Rectangle as NativeRectangle, RectangleEventPayload } from 'react-native-document-scanner';
import { rectangleToQuad } from './utils/coordinate';
import type { Point, Rectangle } from './types';
import { ScannerOverlay } from './utils/overlay';

type PictureEvent = {
  croppedImage?: string | null;
  initialImage?: string | null;
  width?: number;
  height?: number;
  rectangleCoordinates?: NativeRectangle | null;
};

export type RectangleDetectEvent = Omit<RectangleEventPayload, 'rectangleCoordinates' | 'rectangleOnScreen'> & {
  rectangleCoordinates?: Rectangle | null;
  rectangleOnScreen?: Rectangle | null;
};

export type DocScannerCapture = {
  path: string;
  initialPath: string | null;
  croppedPath: string | null;
  quad: Point[] | null;
  rectangle: Rectangle | null;
  width: number;
  height: number;
  origin: 'auto' | 'manual';
};

const isFiniteNumber = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value);

const normalizePoint = (point?: { x?: number; y?: number } | null): Point | null => {
  if (!point || !isFiniteNumber(point.x) || !isFiniteNumber(point.y)) {
    return null;
  }
  return { x: point.x, y: point.y };
};

const normalizeRectangle = (rectangle?: NativeRectangle | null): Rectangle | null => {
  if (!rectangle) {
    return null;
  }

  const topLeft = normalizePoint(rectangle.topLeft);
  const topRight = normalizePoint(rectangle.topRight);
  const bottomRight = normalizePoint(rectangle.bottomRight);
  const bottomLeft = normalizePoint(rectangle.bottomLeft);

  if (!topLeft || !topRight || !bottomRight || !bottomLeft) {
    return null;
  }

  return {
    topLeft,
    topRight,
    bottomRight,
    bottomLeft,
  };
};

export interface DetectionConfig {
  processingWidth?: number;
  cannyLowThreshold?: number;
  cannyHighThreshold?: number;
  snapDistance?: number;
  maxAnchorMisses?: number;
  maxCenterDelta?: number;
}

interface Props {
  onCapture?: (photo: DocScannerCapture) => void;
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
  onRectangleDetect?: (event: RectangleDetectEvent) => void;
  showManualCaptureButton?: boolean;
}

export type DocScannerHandle = {
  capture: () => Promise<PictureEvent>;
  reset: () => void;
};

const DEFAULT_OVERLAY_COLOR = '#0b7ef4';

export const DocScanner = forwardRef<DocScannerHandle, Props>(
  (
    {
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
      gridLineWidth,
      detectionConfig,
      onRectangleDetect,
      showManualCaptureButton = false,
    },
    ref,
  ) => {
    const scannerRef = useRef<any>(null);
    const captureResolvers = useRef<{
      resolve: (value: PictureEvent) => void;
      reject: (reason?: unknown) => void;
    } | null>(null);
    const [isAutoCapturing, setIsAutoCapturing] = useState(false);
    const [detectedRectangle, setDetectedRectangle] = useState<RectangleDetectEvent | null>(null);
    const lastRectangleRef = useRef<Rectangle | null>(null);
    const captureOriginRef = useRef<'auto' | 'manual'>('auto');

    useEffect(() => {
      if (!autoCapture) {
        setIsAutoCapturing(false);
      }
    }, [autoCapture]);

    const normalizedQuality = useMemo(() => {
      if (Platform.OS === 'ios') {
        // iOS expects 0-1
        return Math.min(1, Math.max(0, quality / 100));
      }
      return Math.min(100, Math.max(0, quality));
    }, [quality]);

    const handlePictureTaken = useCallback(
      (event: PictureEvent) => {
        setIsAutoCapturing(false);

        const normalizedRectangle =
          normalizeRectangle(event.rectangleCoordinates ?? null) ?? lastRectangleRef.current;
        const quad = normalizedRectangle ? rectangleToQuad(normalizedRectangle) : null;
        const origin = captureOriginRef.current;
        captureOriginRef.current = 'auto';

        const initialPath = event.initialImage ?? null;
        const croppedPath = event.croppedImage ?? null;
        const editablePath = initialPath ?? croppedPath;

        if (editablePath) {
          onCapture?.({
            path: editablePath,
            initialPath,
            croppedPath,
            quad,
            rectangle: normalizedRectangle,
            width: event.width ?? 0,
            height: event.height ?? 0,
            origin,
          });
        }

        setDetectedRectangle(null);

        if (captureResolvers.current) {
          captureResolvers.current.resolve(event);
          captureResolvers.current = null;
        }
      },
      [onCapture],
    );

    const handleError = useCallback((error: Error) => {
      if (captureResolvers.current) {
        captureResolvers.current.reject(error);
        captureResolvers.current = null;
      }
    }, []);

    const capture = useCallback((): Promise<PictureEvent> => {
      captureOriginRef.current = 'manual';
      const instance = scannerRef.current;
      if (!instance || typeof instance.capture !== 'function') {
        captureOriginRef.current = 'auto';
        return Promise.reject(new Error('DocumentScanner native instance is not ready'));
      }
      if (captureResolvers.current) {
        captureOriginRef.current = 'auto';
        return Promise.reject(new Error('capture_in_progress'));
      }

      let result: any;
      try {
        result = instance.capture();
      } catch (error) {
        captureOriginRef.current = 'auto';
        return Promise.reject(error);
      }
      if (result && typeof result.then === 'function') {
        return result
          .then((payload: PictureEvent) => {
            handlePictureTaken(payload);
            return payload;
          })
          .catch((error: unknown) => {
            captureOriginRef.current = 'auto';
            throw error;
          });
      }

      return new Promise<PictureEvent>((resolve, reject) => {
        captureResolvers.current = {
          resolve: (value) => {
            captureOriginRef.current = 'auto';
            resolve(value);
          },
          reject: (reason) => {
            captureOriginRef.current = 'auto';
            reject(reason);
          },
        };
      });
    }, [handlePictureTaken]);

    const handleManualCapture = useCallback(() => {
      captureOriginRef.current = 'manual';
      capture().catch((error) => {
        captureOriginRef.current = 'auto';
        console.warn('[DocScanner] manual capture failed', error);
      });
    }, [capture]);

    const handleRectangleDetect = useCallback(
      (event: RectangleEventPayload) => {
        const rectangleCoordinates = normalizeRectangle(event.rectangleCoordinates ?? null);
        const rectangleOnScreen = normalizeRectangle(event.rectangleOnScreen ?? null);

        const payload: RectangleDetectEvent = {
          ...event,
          rectangleCoordinates,
          rectangleOnScreen,
        };

        if (autoCapture) {
        if (payload.stableCounter >= Math.max(minStableFrames - 1, 0)) {
            setIsAutoCapturing(true);
          } else if (payload.stableCounter === 0) {
            setIsAutoCapturing(false);
          }
        }

        if (payload.rectangleCoordinates) {
          lastRectangleRef.current = payload.rectangleCoordinates;
        }

        const isGoodRectangle = payload.lastDetectionType === 0;
        setDetectedRectangle(isGoodRectangle && rectangleOnScreen ? payload : null);
        onRectangleDetect?.(payload);
      },
      [autoCapture, minStableFrames, onRectangleDetect],
    );

    useImperativeHandle(
      ref,
      () => ({
        capture,
        reset: () => {
          if (captureResolvers.current) {
            captureResolvers.current.reject(new Error('reset'));
            captureResolvers.current = null;
          }
          captureOriginRef.current = 'auto';
        },
      }),
      [capture],
    );

    const overlayPolygon = detectedRectangle?.rectangleOnScreen ?? detectedRectangle?.rectangleCoordinates ?? null;
    const overlayIsActive = autoCapture ? isAutoCapturing : (detectedRectangle?.stableCounter ?? 0) > 0;

    return (
      <View style={styles.container}>
        <DocumentScanner
          ref={scannerRef}
          style={styles.scanner}
          detectionCountBeforeCapture={minStableFrames}
          overlayColor={overlayColor}
          enableTorch={enableTorch}
          quality={normalizedQuality}
          useBase64={useBase64}
          manualOnly={!autoCapture}
          detectionConfig={detectionConfig}
          onPictureTaken={handlePictureTaken}
          onError={handleError}
          onRectangleDetect={handleRectangleDetect}
        />
        {showGrid && overlayPolygon && (
          <ScannerOverlay
            active={overlayIsActive}
            color={gridColor ?? overlayColor}
            lineWidth={gridLineWidth}
            polygon={overlayPolygon}
          />
        )}
        {(showManualCaptureButton || !autoCapture) && (
          <TouchableOpacity style={styles.button} onPress={handleManualCapture} />
        )}
        {children}
      </View>
    );
  },
);

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  scanner: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    width: '100%',
    height: '100%',
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
