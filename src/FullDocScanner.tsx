import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Image,
  NativeModules,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { DocScanner } from './DocScanner';
import { CropEditor } from './CropEditor';
import type { CapturedDocument, Point, Quad, Rectangle } from './types';
import type { DetectionConfig } from './DocScanner';
import { quadToRectangle, scaleRectangle } from './utils/coordinate';

type CustomCropManagerType = {
  crop: (
    points: {
      topLeft: Point;
      topRight: Point;
      bottomRight: Point;
      bottomLeft: Point;
      width: number;
      height: number;
    },
    imageUri: string,
    callback: (error: unknown, result: { image: string }) => void,
  ) => void;
};

const stripFileUri = (value: string) => value.replace(/^file:\/\//, '');

const ensureFileUri = (value: string) => (value.startsWith('file://') ? value : `file://${value}`);

export interface FullDocScannerResult {
  original: CapturedDocument;
  rectangle: Rectangle | null;
  /** Base64-encoded JPEG string returned by CustomCropManager */
  base64: string;
}

export interface FullDocScannerStrings {
  captureHint?: string;
  manualHint?: string;
  cancel?: string;
  confirm?: string;
  retake?: string;
  cropTitle?: string;
  processing?: string;
}

export interface FullDocScannerProps {
  onResult: (result: FullDocScannerResult) => void;
  onClose?: () => void;
  detectionConfig?: DetectionConfig;
  overlayColor?: string;
  gridColor?: string;
  gridLineWidth?: number;
  showGrid?: boolean;
  overlayStrokeColor?: string;
  handlerColor?: string;
  strings?: FullDocScannerStrings;
  manualCapture?: boolean;
  minStableFrames?: number;
  onError?: (error: Error) => void;
}

type ScreenState = 'scanner' | 'crop';

export const FullDocScanner: React.FC<FullDocScannerProps> = ({
  onResult,
  onClose,
  detectionConfig,
  overlayColor = '#3170f3',
  gridColor,
  gridLineWidth,
  showGrid,
  overlayStrokeColor = '#3170f3',
  handlerColor = '#3170f3',
  strings,
  manualCapture = false,
  minStableFrames,
  onError,
}) => {
  const [screen, setScreen] = useState<ScreenState>('scanner');
  const [capturedDoc, setCapturedDoc] = useState<CapturedDocument | null>(null);
  const [cropRectangle, setCropRectangle] = useState<Rectangle | null>(null);
  const [imageSize, setImageSize] = useState<{ width: number; height: number } | null>(null);
  const [processing, setProcessing] = useState(false);
  const resolvedGridColor = gridColor ?? overlayColor;

  const mergedStrings = useMemo<Required<FullDocScannerStrings>>(
    () => ({
      captureHint: strings?.captureHint ?? 'Align the document within the frame.',
      manualHint: strings?.manualHint ?? 'Tap the button below to capture.',
      cancel: strings?.cancel ?? 'Cancel',
      confirm: strings?.confirm ?? 'Use photo',
      retake: strings?.retake ?? 'Retake',
      cropTitle: strings?.cropTitle ?? 'Adjust the corners',
      processing: strings?.processing ?? 'Processing…',
    }),
    [strings],
  );

  useEffect(() => {
    if (!capturedDoc) {
      return;
    }

    Image.getSize(
      ensureFileUri(capturedDoc.path),
      (width, height) => setImageSize({ width, height }),
      () => setImageSize({ width: capturedDoc.width, height: capturedDoc.height }),
    );
  }, [capturedDoc]);

  const resetState = useCallback(() => {
    setScreen('scanner');
    setCapturedDoc(null);
    setCropRectangle(null);
    setImageSize(null);
    setProcessing(false);
  }, []);

  const handleCapture = useCallback(
    (document: CapturedDocument) => {
      const normalizedPath = stripFileUri(document.path);
      const nextQuad = document.quad && document.quad.length === 4 ? (document.quad as Quad) : null;

      setCapturedDoc({
        ...document,
        path: normalizedPath,
        quad: nextQuad,
      });
      setCropRectangle(nextQuad ? quadToRectangle(nextQuad) : null);
      setScreen('crop');
    },
    [],
  );

  const handleCropChange = useCallback((rectangle: Rectangle) => {
    setCropRectangle(rectangle);
  }, []);

  const emitError = useCallback(
    (error: Error, fallbackMessage?: string) => {
      console.error('[FullDocScanner] error', error);
      onError?.(error);
      if (!onError && fallbackMessage) {
        Alert.alert('Document Scanner', fallbackMessage);
      }
    },
    [onError],
  );

  const performCrop = useCallback(async (): Promise<string> => {
    if (!capturedDoc) {
      throw new Error('No captured document to crop');
    }

    const size = imageSize ?? { width: capturedDoc.width, height: capturedDoc.height };
    const cropManager = NativeModules.CustomCropManager as CustomCropManagerType | undefined;

    if (!cropManager?.crop) {
      throw new Error('CustomCropManager.crop is not available');
    }

    const fallbackRectangle =
      capturedDoc.quad && capturedDoc.quad.length === 4
        ? quadToRectangle(capturedDoc.quad as Quad)
        : null;

    const scaledFallback = fallbackRectangle
      ? scaleRectangle(
          fallbackRectangle,
          capturedDoc.width,
          capturedDoc.height,
          size.width,
          size.height,
        )
      : null;

    const rectangle = cropRectangle ?? scaledFallback;

    const base64 = await new Promise<string>((resolve, reject) => {
      cropManager.crop(
        {
          topLeft: rectangle?.topLeft ?? { x: 0, y: 0 },
          topRight: rectangle?.topRight ?? { x: size.width, y: 0 },
          bottomRight: rectangle?.bottomRight ?? { x: size.width, y: size.height },
          bottomLeft: rectangle?.bottomLeft ?? { x: 0, y: size.height },
          width: size.width,
          height: size.height,
        },
        ensureFileUri(capturedDoc.path),
        (error: unknown, result: { image: string }) => {
          if (error) {
            reject(error instanceof Error ? error : new Error('Crop failed'));
            return;
          }

          resolve(result.image);
        },
      );
    });

    return base64;
  }, [capturedDoc, cropRectangle, imageSize]);

  const handleConfirm = useCallback(async () => {
    if (!capturedDoc) {
      return;
    }

    try {
      setProcessing(true);
      const base64 = await performCrop();
      setProcessing(false);
      onResult({
        original: capturedDoc,
        rectangle: cropRectangle,
        base64,
      });
      resetState();
    } catch (error) {
      setProcessing(false);
      emitError(error instanceof Error ? error : new Error(String(error)), 'Failed to process document.');
    }
  }, [capturedDoc, cropRectangle, emitError, onResult, performCrop, resetState]);

  const handleRetake = useCallback(() => {
    resetState();
  }, [resetState]);

  const handleClose = useCallback(() => {
    resetState();
    onClose?.();
  }, [onClose, resetState]);

  return (
    <View style={styles.container}>
      {screen === 'scanner' && (
        <View style={styles.flex}>
          <DocScanner
            autoCapture={!manualCapture}
            overlayColor={overlayColor}
            showGrid={showGrid}
            gridColor={resolvedGridColor}
            gridLineWidth={gridLineWidth}
            minStableFrames={minStableFrames ?? 6}
            detectionConfig={detectionConfig}
            onCapture={handleCapture}
          >
            <View style={styles.overlay} pointerEvents="box-none">
              <TouchableOpacity
                style={styles.closeButton}
                onPress={handleClose}
                accessibilityLabel={mergedStrings.cancel}
                accessibilityRole="button"
              >
                <Text style={styles.closeButtonLabel}>×</Text>
              </TouchableOpacity>
              <View style={styles.instructions} pointerEvents="none">
                <Text style={styles.captureText}>{mergedStrings.captureHint}</Text>
                {manualCapture && <Text style={styles.captureText}>{mergedStrings.manualHint}</Text>}
              </View>
            </View>
          </DocScanner>
        </View>
      )}

      {screen === 'crop' && capturedDoc && (
        <View style={styles.flex}>
          <CropEditor
            document={capturedDoc}
            overlayColor="rgba(0,0,0,0.6)"
            overlayStrokeColor={overlayStrokeColor}
            handlerColor={handlerColor}
            onCropChange={handleCropChange}
          />
          <View style={styles.cropFooter}>
            <TouchableOpacity style={[styles.actionButton, styles.secondaryButton]} onPress={handleRetake}>
              <Text style={styles.buttonText}>{mergedStrings.retake}</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.actionButton, styles.primaryButton]} onPress={handleConfirm}>
              <Text style={styles.buttonText}>{mergedStrings.confirm}</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}

      {processing && (
        <View style={styles.processingOverlay}>
          <ActivityIndicator size="large" color={overlayStrokeColor} />
          <Text style={styles.processingText}>{mergedStrings.processing}</Text>
        </View>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  flex: {
    flex: 1,
  },
  overlay: {
    ...StyleSheet.absoluteFillObject,
    justifyContent: 'space-between',
    paddingTop: 48,
    paddingBottom: 64,
    paddingHorizontal: 24,
  },
  closeButton: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    alignItems: 'center',
    alignSelf: 'flex-end',
  },
  closeButtonLabel: {
    color: '#fff',
    fontSize: 28,
    lineHeight: 32,
    marginTop: -3,
  },
  instructions: {
    alignSelf: 'center',
    backgroundColor: 'rgba(0,0,0,0.55)',
    borderRadius: 16,
    paddingHorizontal: 20,
    paddingVertical: 16,
  },
  captureText: {
    color: '#fff',
    fontSize: 15,
    textAlign: 'center',
  },
  cropFooter: {
    position: 'absolute',
    bottom: 40,
    left: 20,
    right: 20,
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  actionButton: {
    flex: 1,
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: 'center',
    marginHorizontal: 6,
  },
  secondaryButton: {
    backgroundColor: 'rgba(255,255,255,0.2)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.35)',
  },
  primaryButton: {
    backgroundColor: '#3170f3',
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  processingOverlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0,0,0,0.65)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  processingText: {
    marginTop: 12,
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
});
