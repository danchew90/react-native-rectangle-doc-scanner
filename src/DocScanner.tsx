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
import {
  Platform,
  PixelRatio,
  StyleSheet,
  TouchableOpacity,
  View,
  NativeModules,
  findNodeHandle,
} from 'react-native';
import DocumentScanner from '../vendor/react-native-document-scanner';
import type { Rectangle as NativeRectangle, RectangleEventPayload } from '../vendor/react-native-document-scanner';
import { rectangleToQuad } from './utils/coordinate';
import type { Point, Rectangle } from './types';
import { ScannerOverlay } from './utils/overlay';

type PictureEvent = {
  croppedImage?: string | null;
  initialImage?: string | null;
  width?: number;
  height?: number;
  rectangleCoordinates?: NativeRectangle | null;
  rectangleOnScreen?: NativeRectangle | null;
};

export type RectangleDetectEvent = Omit<RectangleEventPayload, 'rectangleCoordinates' | 'rectangleOnScreen'> & {
  rectangleCoordinates?: Rectangle | null;
  rectangleOnScreen?: Rectangle | null;
  previewViewport?: { left: number; top: number; width: number; height: number };
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

const { RNPdfScannerManager } = NativeModules;

const safeRequire = (moduleName: string) => {
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    return require(moduleName);
  } catch {
    return null;
  }
};

const visionCameraModule = Platform.OS === 'android' ? safeRequire('react-native-vision-camera') : null;
const reanimatedModule = Platform.OS === 'android' ? safeRequire('react-native-reanimated') : null;
const hasVisionCamera =
  Platform.OS === 'android' &&
  Boolean(visionCameraModule?.Camera && visionCameraModule?.useCameraDevice && visionCameraModule?.useFrameProcessor);

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

const RectangleQuality = {
  GOOD: 0,
  BAD_ANGLE: 1,
  TOO_FAR: 2,
} as const;

const evaluateRectangleQualityInView = (rectangle: Rectangle, viewWidth: number, viewHeight: number) => {
  if (!viewWidth || !viewHeight) {
    return RectangleQuality.TOO_FAR;
  }

  const minDim = Math.min(viewWidth, viewHeight);
  const angleThreshold = Math.max(60, minDim * 0.08);
  const topYDiff = Math.abs(rectangle.topRight.y - rectangle.topLeft.y);
  const bottomYDiff = Math.abs(rectangle.bottomLeft.y - rectangle.bottomRight.y);
  const leftXDiff = Math.abs(rectangle.topLeft.x - rectangle.bottomLeft.x);
  const rightXDiff = Math.abs(rectangle.topRight.x - rectangle.bottomRight.x);

  if (
    topYDiff > angleThreshold ||
    bottomYDiff > angleThreshold ||
    leftXDiff > angleThreshold ||
    rightXDiff > angleThreshold
  ) {
    return RectangleQuality.BAD_ANGLE;
  }

  const margin = Math.max(120, minDim * 0.12);
  if (
    rectangle.topLeft.y > margin ||
    rectangle.topRight.y > margin ||
    rectangle.bottomLeft.y < viewHeight - margin ||
    rectangle.bottomRight.y < viewHeight - margin
  ) {
    return RectangleQuality.TOO_FAR;
  }

  return RectangleQuality.GOOD;
};

const mirrorRectangleHorizontally = (rectangle: Rectangle, imageWidth: number): Rectangle => ({
  topLeft: { x: imageWidth - rectangle.topRight.x, y: rectangle.topRight.y },
  topRight: { x: imageWidth - rectangle.topLeft.x, y: rectangle.topLeft.y },
  bottomLeft: { x: imageWidth - rectangle.bottomRight.x, y: rectangle.bottomRight.y },
  bottomRight: { x: imageWidth - rectangle.bottomLeft.x, y: rectangle.bottomLeft.y },
});

const mapRectangleToView = (
  rectangle: Rectangle,
  imageWidth: number,
  imageHeight: number,
  viewWidth: number,
  viewHeight: number,
  density: number,
): Rectangle => {
  const viewWidthPx = viewWidth * density;
  const viewHeightPx = viewHeight * density;
  const scale =
    Platform.OS === 'ios'
      ? Math.max(viewWidthPx / imageWidth, viewHeightPx / imageHeight)
      : Math.min(viewWidthPx / imageWidth, viewHeightPx / imageHeight);
  const scaledImageWidth = imageWidth * scale;
  const scaledImageHeight = imageHeight * scale;
  const offsetX =
    Platform.OS === 'ios'
      ? (scaledImageWidth - viewWidthPx) / 2
      : (viewWidthPx - scaledImageWidth) / 2;
  const offsetY =
    Platform.OS === 'ios'
      ? (scaledImageHeight - viewHeightPx) / 2
      : (viewHeightPx - scaledImageHeight) / 2;

  const mapPoint = (point: Point): Point => ({
    x:
      Platform.OS === 'ios'
        ? (point.x * scale - offsetX) / density
        : (point.x * scale + offsetX) / density,
    y:
      Platform.OS === 'ios'
        ? (point.y * scale - offsetY) / density
        : (point.y * scale + offsetY) / density,
  });

  return {
    topLeft: mapPoint(rectangle.topLeft),
    topRight: mapPoint(rectangle.topRight),
    bottomRight: mapPoint(rectangle.bottomRight),
    bottomLeft: mapPoint(rectangle.bottomLeft),
  };
};

const VisionCameraScanner = forwardRef<DocScannerHandle, Props>(
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
    const cameraRef = useRef<any>(null);
    const captureResolvers = useRef<{
      resolve: (value: PictureEvent) => void;
      reject: (reason?: unknown) => void;
    } | null>(null);
    const captureOriginRef = useRef<'auto' | 'manual'>('auto');
    const captureInProgressRef = useRef(false);
    const stableCounterRef = useRef(0);
    const lastDetectionTimestampRef = useRef(0);
    const lastRectangleRef = useRef<Rectangle | null>(null);
    const lastImageSizeRef = useRef<{ width: number; height: number } | null>(null);
    const rectangleClearTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    const [isAutoCapturing, setIsAutoCapturing] = useState(false);
    const [detectedRectangle, setDetectedRectangle] = useState<RectangleDetectEvent | null>(null);
    const [viewSize, setViewSize] = useState({ width: 0, height: 0 });
    const [hasPermission, setHasPermission] = useState(false);

    const normalizedQuality = useMemo(() => Math.min(100, Math.max(0, quality)), [quality]);
    const density = PixelRatio.get() || 1;

    const CameraComponent = visionCameraModule?.Camera;
    const runOnJS = reanimatedModule?.runOnJS;

    const device = visionCameraModule.useCameraDevice('back');

    useEffect(() => {
      let mounted = true;

      const requestPermission = async () => {
        try {
          if (!CameraComponent?.requestCameraPermission) {
            if (mounted) {
              setHasPermission(true);
            }
            return;
          }

          const status = await CameraComponent.requestCameraPermission();
          if (mounted) {
            setHasPermission(status === 'authorized');
          }
        } catch (error) {
          if (mounted) {
            setHasPermission(false);
          }
        }
      };

      requestPermission();

      return () => {
        mounted = false;
      };
    }, [CameraComponent]);

    useEffect(() => {
      if (!autoCapture) {
        setIsAutoCapturing(false);
      }
    }, [autoCapture]);

    useEffect(() => {
      return () => {
        if (rectangleClearTimeoutRef.current) {
          clearTimeout(rectangleClearTimeoutRef.current);
        }
      };
    }, []);

    const handlePictureTaken = useCallback(
      (event: PictureEvent) => {
        const captureError = (event as any)?.error;
        if (captureError) {
          console.error('[DocScanner] Native capture error received:', captureError);
          captureOriginRef.current = 'auto';
          setIsAutoCapturing(false);
          setDetectedRectangle(null);

          if (captureResolvers.current) {
            captureResolvers.current.reject(new Error(String(captureError)));
            captureResolvers.current = null;
          }

          return;
        }

        setIsAutoCapturing(false);

        const normalizedRectangle =
          normalizeRectangle(event.rectangleCoordinates ?? null) ??
          normalizeRectangle(event.rectangleOnScreen ?? null) ??
          lastRectangleRef.current;
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

    const applyRectangleEvent = useCallback(
      (payload: RectangleDetectEvent) => {
        if (autoCapture) {
          if (payload.stableCounter >= Math.max(minStableFrames - 1, 0)) {
            setIsAutoCapturing(true);
          } else if (payload.stableCounter === 0) {
            setIsAutoCapturing(false);
          }
        }

        const hasAnyRectangle = Boolean(payload.rectangleOnScreen || payload.rectangleCoordinates);

        if (hasAnyRectangle) {
          if (rectangleClearTimeoutRef.current) {
            clearTimeout(rectangleClearTimeoutRef.current);
          }
          setDetectedRectangle(payload);
          rectangleClearTimeoutRef.current = setTimeout(() => {
            setDetectedRectangle(null);
            rectangleClearTimeoutRef.current = null;
          }, 500);
        } else {
          if (rectangleClearTimeoutRef.current) {
            clearTimeout(rectangleClearTimeoutRef.current);
            rectangleClearTimeoutRef.current = null;
          }
          setDetectedRectangle(null);
        }

        onRectangleDetect?.(payload);
      },
      [autoCapture, minStableFrames, onRectangleDetect],
    );

    const captureVision = useCallback(
      async (origin: 'auto' | 'manual') => {
        if (captureInProgressRef.current) {
          throw new Error('capture_in_progress');
        }

        if (!cameraRef.current?.takePhoto) {
          throw new Error('capture_not_supported');
        }

        if (!RNPdfScannerManager?.processImage) {
          throw new Error('process_image_not_supported');
        }

        captureInProgressRef.current = true;
        captureOriginRef.current = origin;

        try {
          const photo = await cameraRef.current.takePhoto({ flash: 'off' });
          const imageSize = lastImageSizeRef.current;
          const payload = await RNPdfScannerManager.processImage({
            imagePath: photo.path,
            rectangleCoordinates: lastRectangleRef.current,
            rectangleWidth: imageSize?.width ?? 0,
            rectangleHeight: imageSize?.height ?? 0,
            useBase64,
            quality: normalizedQuality,
            brightness: 0,
            contrast: 1,
            saturation: 1,
            saveInAppDocument: false,
          });
          handlePictureTaken(payload);
          return payload;
        } catch (error) {
          handleError(error as Error);
          throw error;
        } finally {
          captureInProgressRef.current = false;
        }
      },
      [handleError, handlePictureTaken, normalizedQuality, useBase64],
    );

    const capture = useCallback((): Promise<PictureEvent> => {
      captureOriginRef.current = 'manual';

      if (captureResolvers.current) {
        captureOriginRef.current = 'auto';
        return Promise.reject(new Error('capture_in_progress'));
      }

      return new Promise<PictureEvent>((resolve, reject) => {
        captureResolvers.current = { resolve, reject };
        captureVision('manual').catch((error) => {
          captureResolvers.current = null;
          captureOriginRef.current = 'auto';
          reject(error);
        });
      });
    }, [captureVision]);

    useImperativeHandle(
      ref,
      () => ({
        capture,
        reset: () => {
          if (captureResolvers.current) {
            captureResolvers.current.reject(new Error('reset'));
            captureResolvers.current = null;
          }

          if (rectangleClearTimeoutRef.current) {
            clearTimeout(rectangleClearTimeoutRef.current);
            rectangleClearTimeoutRef.current = null;
          }

          lastRectangleRef.current = null;
          lastImageSizeRef.current = null;
          stableCounterRef.current = 0;
          setDetectedRectangle(null);
          setIsAutoCapturing(false);
          captureOriginRef.current = 'auto';
        },
      }),
      [capture],
    );

    const handleVisionResult = useCallback(
      (result: any) => {
        const now = Date.now();
        if (now - lastDetectionTimestampRef.current < 100) {
          return;
        }
        lastDetectionTimestampRef.current = now;

        const imageWidth = Number(result?.imageWidth) || 0;
        const imageHeight = Number(result?.imageHeight) || 0;
        const isMirrored = Boolean(result?.isMirrored);

        let rectangleCoordinates = normalizeRectangle(result?.rectangle ?? null);
        if (rectangleCoordinates && isMirrored && imageWidth) {
          rectangleCoordinates = mirrorRectangleHorizontally(rectangleCoordinates, imageWidth);
        }

        const rectangleOnScreen =
          rectangleCoordinates && viewSize.width && viewSize.height && imageWidth && imageHeight
            ? mapRectangleToView(
                rectangleCoordinates,
                imageWidth,
                imageHeight,
                viewSize.width,
                viewSize.height,
                density,
              )
            : null;

        const quality = rectangleOnScreen
          ? evaluateRectangleQualityInView(rectangleOnScreen, viewSize.width, viewSize.height)
          : RectangleQuality.TOO_FAR;

        if (!rectangleCoordinates) {
          stableCounterRef.current = 0;
        } else if (quality === RectangleQuality.GOOD) {
          const cap = autoCapture ? minStableFrames : 99999;
          stableCounterRef.current = Math.min(stableCounterRef.current + 1, cap);
        } else if (stableCounterRef.current > 0) {
          stableCounterRef.current -= 1;
        }

        if (rectangleCoordinates) {
          lastRectangleRef.current = rectangleCoordinates;
          if (imageWidth && imageHeight) {
            lastImageSizeRef.current = { width: imageWidth, height: imageHeight };
          }
        }

        const payload: RectangleDetectEvent = {
          stableCounter: stableCounterRef.current,
          lastDetectionType: quality,
          rectangleCoordinates,
          rectangleOnScreen,
          previewSize: viewSize.width && viewSize.height ? { width: viewSize.width, height: viewSize.height } : undefined,
          imageSize: imageWidth && imageHeight ? { width: imageWidth, height: imageHeight } : undefined,
        };

        applyRectangleEvent(payload);

        if (
          autoCapture &&
          rectangleCoordinates &&
          stableCounterRef.current >= minStableFrames &&
          !captureInProgressRef.current
        ) {
          stableCounterRef.current = 0;
          captureVision('auto').catch(() => {});
        }
      },
      [applyRectangleEvent, autoCapture, captureVision, density, minStableFrames, viewSize],
    );

    const plugin = useMemo(() => {
      if (!visionCameraModule?.VisionCameraProxy?.initFrameProcessorPlugin) {
        return null;
      }
      return visionCameraModule.VisionCameraProxy.initFrameProcessorPlugin('DocumentScanner', detectionConfig ?? {});
    }, [detectionConfig]);

    const frameProcessor = visionCameraModule.useFrameProcessor((frame: any) => {
      'worklet';
      if (!plugin || !runOnJS) {
        return;
      }
      const output = plugin.call(frame, null);
      runOnJS(handleVisionResult)(output ?? null);
    }, [plugin, runOnJS, handleVisionResult]);

    const handleLayout = useCallback((event: any) => {
      const { width, height } = event.nativeEvent.layout;
      if (width && height) {
        setViewSize({ width, height });
      }
    }, []);

    const overlayPolygon = detectedRectangle?.rectangleOnScreen ?? null;
    const overlayIsActive = autoCapture ? isAutoCapturing : (detectedRectangle?.stableCounter ?? 0) > 0;

    return (
      <View style={styles.container} onLayout={handleLayout}>
        {CameraComponent && device && hasPermission ? (
          <CameraComponent
            ref={cameraRef}
            style={styles.scanner}
            device={device}
            isActive={true}
            photo={true}
            torch={enableTorch ? 'on' : 'off'}
            frameProcessor={frameProcessor}
            frameProcessorFps={10}
          />
        ) : (
          <View style={styles.scanner} />
        )}
        {showGrid && overlayPolygon && (
          <ScannerOverlay
            active={overlayIsActive}
            color={gridColor ?? overlayColor}
            lineWidth={gridLineWidth}
            polygon={overlayPolygon}
            clipRect={Platform.OS === 'android' ? null : (detectedRectangle?.previewViewport ?? null)}
          />
        )}
        {showManualCaptureButton && (
          <TouchableOpacity style={styles.button} onPress={() => captureVision('manual')} />
        )}
        {children}
      </View>
    );
  },
);

const NativeScanner = forwardRef<DocScannerHandle, Props>(
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
    const rectangleClearTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    useEffect(() => {
      if (!autoCapture) {
        setIsAutoCapturing(false);
      }
    }, [autoCapture]);

    // Cleanup timeout on unmount
    useEffect(() => {
      return () => {
        if (rectangleClearTimeoutRef.current) {
          clearTimeout(rectangleClearTimeoutRef.current);
        }
      };
    }, []);

    const normalizedQuality = useMemo(() => {
      if (Platform.OS === 'ios') {
        // iOS expects 0-1
        return Math.min(1, Math.max(0, quality / 100));
      }
      return Math.min(100, Math.max(0, quality));
    }, [quality]);

    const handlePictureTaken = useCallback(
      (event: PictureEvent) => {
        const captureError = (event as any)?.error;
        if (captureError) {
          console.error('[DocScanner] Native capture error received:', captureError);
          captureOriginRef.current = 'auto';
          setIsAutoCapturing(false);
          setDetectedRectangle(null);

          if (captureResolvers.current) {
            captureResolvers.current.reject(new Error(String(captureError)));
            captureResolvers.current = null;
          }

          return;
        }

        console.log('[DocScanner] handlePictureTaken called with event:', {
          hasInitialImage: !!event.initialImage,
          hasCroppedImage: !!event.croppedImage,
          hasRectangleCoordinates: !!event.rectangleCoordinates,
          width: event.width,
          height: event.height,
        });

        setIsAutoCapturing(false);

        const normalizedRectangle =
          normalizeRectangle(event.rectangleCoordinates ?? null) ??
          normalizeRectangle(event.rectangleOnScreen ?? null) ??
          lastRectangleRef.current;
        const quad = normalizedRectangle ? rectangleToQuad(normalizedRectangle) : null;
        const origin = captureOriginRef.current;
        captureOriginRef.current = 'auto';

        const initialPath = event.initialImage ?? null;
        const croppedPath = event.croppedImage ?? null;
        const editablePath = initialPath ?? croppedPath;

        console.log('[DocScanner] Processing captured image:', {
          origin,
          initialPath,
          croppedPath,
          editablePath,
          hasQuad: !!quad,
        });

        if (editablePath) {
          console.log('[DocScanner] Calling onCapture callback');
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
        } else {
          console.warn('[DocScanner] No editable path available, skipping onCapture');
        }

        setDetectedRectangle(null);

        if (captureResolvers.current) {
          console.log('[DocScanner] Resolving capture promise');
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
      console.log('[DocScanner] capture() called');
      captureOriginRef.current = 'manual';
      const instance = scannerRef.current;

      if (!instance || (typeof instance.capture !== 'function' && Platform.OS !== 'ios')) {
        console.error('[DocScanner] Native instance not ready:', {
          hasInstance: !!instance,
          hasCaptureMethod: instance ? typeof (instance as any).capture : 'no instance',
        });
        captureOriginRef.current = 'auto';
        return Promise.reject(new Error('DocumentScanner native instance is not ready'));
      }

      if (captureResolvers.current) {
        console.warn('[DocScanner] Capture already in progress');
        captureOriginRef.current = 'auto';
        return Promise.reject(new Error('capture_in_progress'));
      }

      const attemptNativeManagerCapture = () => {
        if (Platform.OS !== 'ios') {
          return null;
        }

        if (!RNPdfScannerManager || typeof RNPdfScannerManager.capture !== 'function') {
          console.warn('[DocScanner] RNPdfScannerManager.capture not available, falling back to instance method');
          return null;
        }

        const nodeHandle = findNodeHandle(instance);

        if (!nodeHandle) {
          console.error('[DocScanner] Unable to resolve native tag for scanner view');
          return Promise.reject(new Error('scanner_view_not_ready'));
        }

        console.log('[DocScanner] Calling RNPdfScannerManager.capture with tag:', nodeHandle);

        const managerResult = RNPdfScannerManager.capture(nodeHandle);

        if (!managerResult || typeof managerResult.then !== 'function') {
          console.warn('[DocScanner] RNPdfScannerManager.capture did not return a promise, falling back to instance method');
          return null;
        }

        return managerResult
          .then((payload: PictureEvent) => {
            console.log('[DocScanner] RNPdfScannerManager promise resolved');
            handlePictureTaken(payload);
            return payload;
          })
          .catch((error: unknown) => {
            console.error('[DocScanner] RNPdfScannerManager promise rejected:', error);
            captureOriginRef.current = 'auto';
            throw error;
          });
      };

      const managerPromise = attemptNativeManagerCapture();
      if (managerPromise) {
        return managerPromise;
      }

      if (!instance || typeof instance.capture !== 'function') {
        console.error('[DocScanner] capture() fallback not available on instance');
        captureOriginRef.current = 'auto';
        return Promise.reject(new Error('capture_not_supported'));
      }

      console.log('[DocScanner] Calling native capture method (legacy/event-based)...');
      try {
        const result = instance.capture();
        console.log('[DocScanner] Native capture method called, result type:', typeof result, 'isPromise:', !!(result && typeof result.then === 'function'));

        if (result && typeof result.then === 'function') {
          console.log('[DocScanner] Native returned a promise, waiting for resolution...');
          return result
            .then((payload: PictureEvent) => {
              console.log('[DocScanner] Native promise resolved with payload:', {
                hasCroppedImage: !!payload.croppedImage,
                hasInitialImage: !!payload.initialImage,
              });
              handlePictureTaken(payload);
              return payload;
            })
            .catch((error: unknown) => {
              console.error('[DocScanner] Native promise rejected:', error);
              captureOriginRef.current = 'auto';
              throw error;
            });
        }

        // Fallback for legacy event-based approach
        console.warn('[DocScanner] Native did not return a promise, using callback-based approach (legacy)');
        return new Promise<PictureEvent>((resolve, reject) => {
          captureResolvers.current = {
            resolve: (value) => {
              console.log('[DocScanner] Callback resolver called with:', value);
              captureOriginRef.current = 'auto';
              resolve(value);
            },
            reject: (reason) => {
              console.error('[DocScanner] Callback rejector called with:', reason);
              captureOriginRef.current = 'auto';
              reject(reason);
            },
          };
        });
      } catch (error) {
        console.error('[DocScanner] Native capture threw error:', error);
        captureOriginRef.current = 'auto';
        return Promise.reject(error);
      }
    }, [handlePictureTaken]);

    const handleManualCapture = useCallback(() => {
      captureOriginRef.current = 'manual';
      capture().catch((error) => {
        captureOriginRef.current = 'auto';
        console.error('[DocScanner] manual capture failed', error);
        throw error;
      });
    }, [capture]);

    const handleRectangleDetect = useCallback(
      (event: RectangleEventPayload) => {
        const rectangleCoordinates = normalizeRectangle(event.rectangleCoordinates ?? null);
        let rectangleOnScreen = normalizeRectangle(event.rectangleOnScreen ?? null);
        const density = PixelRatio.get();

        if (
          Platform.OS === 'android' &&
          !rectangleOnScreen &&
          rectangleCoordinates &&
          event.imageSize &&
          event.previewSize &&
          event.imageSize.width &&
          event.imageSize.height &&
          event.previewSize.width &&
          event.previewSize.height
        ) {
          rectangleOnScreen = mapRectangleToView(
            rectangleCoordinates,
            event.imageSize.width,
            event.imageSize.height,
            event.previewSize.width,
            event.previewSize.height,
            density,
          );
        }

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

        if (payload.rectangleCoordinates || payload.rectangleOnScreen) {
          lastRectangleRef.current = payload.rectangleCoordinates ?? payload.rectangleOnScreen ?? null;
        }

        const hasAnyRectangle =
          Platform.OS === 'android'
            ? Boolean(rectangleOnScreen || payload.rectangleCoordinates)
            : payload.lastDetectionType === 0 && Boolean(rectangleOnScreen);

        // 그리드를 표시할 조건: 사각형이 잡히면 품질과 무관하게 표시
        if (hasAnyRectangle) {
          // 기존 타임아웃 클리어
          if (rectangleClearTimeoutRef.current) {
            clearTimeout(rectangleClearTimeoutRef.current);
          }
          setDetectedRectangle(payload);
          // 500ms 후에 그리드 자동 클리어 (새로운 이벤트가 없으면)
          rectangleClearTimeoutRef.current = setTimeout(() => {
            setDetectedRectangle(null);
            rectangleClearTimeoutRef.current = null;
          }, 500);
        } else {
          // 즉시 클리어
          if (rectangleClearTimeoutRef.current) {
            clearTimeout(rectangleClearTimeoutRef.current);
            rectangleClearTimeoutRef.current = null;
          }
          setDetectedRectangle(null);
        }

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

          if (rectangleClearTimeoutRef.current) {
            clearTimeout(rectangleClearTimeoutRef.current);
            rectangleClearTimeoutRef.current = null;
          }

          lastRectangleRef.current = null;
          setDetectedRectangle(null);
          setIsAutoCapturing(false);
          captureOriginRef.current = 'auto';
        },
      }),
      [capture],
    );

    const overlayPolygon =
      Platform.OS === 'android'
        ? detectedRectangle?.rectangleOnScreen ?? null
        : detectedRectangle?.rectangleOnScreen ?? detectedRectangle?.rectangleCoordinates ?? null;
    const overlayIsActive = autoCapture ? isAutoCapturing : (detectedRectangle?.stableCounter ?? 0) > 0;

  const detectionThreshold = autoCapture ? minStableFrames : 99999;

  return (
      <View style={styles.container}>
        <DocumentScanner
          ref={scannerRef}
          style={styles.scanner}
          detectionCountBeforeCapture={detectionThreshold}
          overlayColor={overlayColor}
          enableTorch={enableTorch}
          quality={normalizedQuality}
          useBase64={useBase64}
          manualOnly={false}
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
            clipRect={Platform.OS === 'android' ? null : (detectedRectangle?.previewViewport ?? null)}
          />
        )}
        {showManualCaptureButton && (
          <TouchableOpacity style={styles.button} onPress={handleManualCapture} />
        )}
        {children}
      </View>
    );
  },
);

export const DocScanner = forwardRef<DocScannerHandle, Props>((props, ref) => {
  useEffect(() => {
    if (Platform.OS !== 'android') {
      return;
    }
    if (hasVisionCamera) {
      console.log('[DocScanner] Using VisionCamera pipeline');
    } else {
      console.warn('[DocScanner] VisionCamera pipeline unavailable, falling back to native view.', {
        hasVisionCameraModule: Boolean(visionCameraModule),
        hasReanimated: Boolean(reanimatedModule),
      });
    }
  }, []);

  if (hasVisionCamera) {
    return <VisionCameraScanner ref={ref} {...props} />;
  }

  return <NativeScanner ref={ref} {...props} />;
});

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
