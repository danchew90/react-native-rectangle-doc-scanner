import React, {
  ComponentType,
  ReactNode,
  useCallback,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  LayoutChangeEvent,
  StyleSheet,
  TouchableOpacity,
  View,
} from 'react-native';
import DocumentScanner from 'react-native-document-scanner-plugin';
import { Overlay } from './utils/overlay';
import type { Point } from './types';

type NativeRectangle = {
  topLeft: Point;
  topRight: Point;
  bottomRight: Point;
  bottomLeft: Point;
};

type NativeRectangleEvent = {
  rectangleCoordinates?: NativeRectangle | null;
  stableCounter?: number;
};

type NativeCaptureResult = {
  croppedImage?: string;
  initialImage?: string;
  width?: number;
  height?: number;
};

type DocumentScannerHandle = {
  capture: () => Promise<NativeCaptureResult>;
};

type NativeDocumentScannerProps = {
  style?: object;
  overlayColor?: string;
  detectionCountBeforeCapture?: number;
  enableTorch?: boolean;
  hideControls?: boolean;
  useBase64?: boolean;
  quality?: number;
  onRectangleDetect?: (event: NativeRectangleEvent) => void;
  onPictureTaken?: (event: NativeCaptureResult) => void;
};

const NativeDocumentScanner = DocumentScanner as unknown as ComponentType<
  NativeDocumentScannerProps & { ref?: React.Ref<DocumentScannerHandle> }
>;

/**
 * Detection configuration is no longer used now that the native
 * implementation handles edge detection. Keeping it for backwards
 * compatibility with existing consumer code.
 */
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

export const DocScanner: React.FC<Props> = ({
  onCapture,
  overlayColor = DEFAULT_OVERLAY_COLOR,
  autoCapture = true,
  minStableFrames = 8,
  enableTorch = false,
  quality,
  useBase64 = false,
  children,
  showGrid = true,
  gridColor,
  gridLineWidth = 2,
}) => {
  const scannerRef = useRef<DocumentScannerHandle | null>(null);
  const capturingRef = useRef(false);
  const [quad, setQuad] = useState<Point[] | null>(null);
  const [frameSize, setFrameSize] = useState<{ width: number; height: number } | null>(null);

  const effectiveGridColor = useMemo(
    () => gridColor ?? GRID_COLOR_FALLBACK,
    [gridColor],
  );

  const handleLayout = useCallback((event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout;
    if (width > 0 && height > 0) {
      setFrameSize({ width, height });
    }
  }, []);

  const handleRectangleDetect = useCallback((event: NativeRectangleEvent) => {
    const coordinates = event?.rectangleCoordinates;

    if (!coordinates) {
      setQuad(null);
      return;
    }

    const nextQuad: Point[] = [
      coordinates.topLeft,
      coordinates.topRight,
      coordinates.bottomRight,
      coordinates.bottomLeft,
    ];

    setQuad(nextQuad);
  }, []);

  const handlePictureTaken = useCallback(
    (event: NativeCaptureResult) => {
      capturingRef.current = false;

      const path = event?.croppedImage ?? event?.initialImage;
      if (!path) {
        return;
      }

      const width = event?.width ?? frameSize?.width ?? 0;
      const height = event?.height ?? frameSize?.height ?? 0;

      onCapture?.({
        path,
        quad,
        width,
        height,
      });
    },
    [frameSize, onCapture, quad],
  );

  const handleManualCapture = useCallback(() => {
    if (autoCapture || capturingRef.current || !scannerRef.current) {
      return;
    }

    capturingRef.current = true;
    scannerRef.current
      .capture()
      .catch((error) => {
        console.warn('[DocScanner] manual capture failed', error);
        capturingRef.current = false;
      });
  }, [autoCapture]);

  return (
    <View style={styles.container} onLayout={handleLayout}>
      <NativeDocumentScanner
        ref={(instance) => {
          scannerRef.current = instance as DocumentScannerHandle | null;
        }}
        style={StyleSheet.absoluteFill}
        overlayColor="transparent"
        detectionCountBeforeCapture={autoCapture ? minStableFrames : 10000}
        enableTorch={enableTorch}
        hideControls
        useBase64={useBase64}
        quality={quality}
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
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
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
