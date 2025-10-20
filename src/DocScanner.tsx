import React, {
  ReactNode,
  forwardRef,
  useCallback,
  useImperativeHandle,
  useMemo,
  useRef,
} from 'react';
import { Platform, StyleSheet, TouchableOpacity, View } from 'react-native';
import DocumentScanner from 'react-native-document-scanner';

type PictureEvent = {
  croppedImage?: string | null;
  initialImage?: string | null;
  width?: number;
  height?: number;
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
  onCapture?: (photo: { path: string; quad: null; width: number; height: number }) => void;
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

type DocScannerHandle = {
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
    },
    ref,
  ) => {
    const scannerRef = useRef<any>(null);
    const captureResolvers = useRef<{
      resolve: (value: PictureEvent) => void;
      reject: (reason?: unknown) => void;
    } | null>(null);

    const normalizedQuality = useMemo(() => {
      if (Platform.OS === 'ios') {
        // iOS expects 0-1
        return Math.min(1, Math.max(0, quality / 100));
      }
      return Math.min(100, Math.max(0, quality));
    }, [quality]);

    const handlePictureTaken = useCallback(
      (event: PictureEvent) => {
        const path = event.croppedImage ?? event.initialImage;
        if (path) {
          onCapture?.({
            path,
            quad: null,
            width: event.width ?? 0,
            height: event.height ?? 0,
          });
        }

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
      const instance = scannerRef.current;
      if (!instance || typeof instance.capture !== 'function') {
        return Promise.reject(new Error('DocumentScanner native instance is not ready'));
      }
      if (captureResolvers.current) {
        return Promise.reject(new Error('capture_in_progress'));
      }

      const result = instance.capture();
      if (result && typeof result.then === 'function') {
        return result.then((payload: PictureEvent) => {
          handlePictureTaken(payload);
          return payload;
        });
      }

      return new Promise<PictureEvent>((resolve, reject) => {
        captureResolvers.current = { resolve, reject };
      });
    }, [handlePictureTaken]);

    const handleManualCapture = useCallback(() => {
      if (autoCapture) {
        return;
      }
      capture().catch((error) => {
        console.warn('[DocScanner] manual capture failed', error);
      });
    }, [autoCapture, capture]);

    useImperativeHandle(
      ref,
      () => ({
        capture,
        reset: () => {
          if (captureResolvers.current) {
            captureResolvers.current.reject(new Error('reset'));
            captureResolvers.current = null;
          }
        },
      }),
      [capture],
    );

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
          onPictureTaken={handlePictureTaken}
          onError={handleError}
        />
        {!autoCapture && <TouchableOpacity style={styles.button} onPress={handleManualCapture} />}
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

export type { DocScannerHandle };
