import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
import type { DetectionConfig, DocScannerHandle, DocScannerCapture } from './DocScanner';
import { createFullImageRectangle, quadToRectangle, scaleRectangle } from './utils/coordinate';

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

const resolveImageSize = (
  path: string,
  fallbackWidth: number,
  fallbackHeight: number,
): Promise<{ width: number; height: number }> =>
  new Promise((resolve) => {
    Image.getSize(
      ensureFileUri(path),
      (width, height) => resolve({ width, height }),
      () => resolve({
        width: fallbackWidth > 0 ? fallbackWidth : 0,
        height: fallbackHeight > 0 ? fallbackHeight : 0,
      }),
    );
  });

const normalizeCapturedDocument = (document: DocScannerCapture): CapturedDocument => {
  const { origin: _origin, ...rest } = document;
  const normalizedPath = stripFileUri(document.initialPath ?? document.path);
  return {
    ...rest,
    path: normalizedPath,
    initialPath: document.initialPath ? stripFileUri(document.initialPath) : normalizedPath,
    croppedPath: document.croppedPath ? stripFileUri(document.croppedPath) : null,
  };
};

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
  const docScannerRef = useRef<DocScannerHandle | null>(null);
  const manualCapturePending = useRef(false);
  const processingCaptureRef = useRef(false);
  const cropInitializedRef = useRef(false);

  const mergedStrings = useMemo(
    () => ({
      captureHint: strings?.captureHint ?? '',
      manualHint: strings?.manualHint ?? '',
      cancel: strings?.cancel ?? '',
      confirm: strings?.confirm ?? '',
      retake: strings?.retake ?? '',
      cropTitle: strings?.cropTitle ?? '',
      processing: strings?.processing ?? '',
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

  useEffect(() => {
    if (!capturedDoc || !imageSize || cropInitializedRef.current) {
      return;
    }

    const baseWidth = capturedDoc.width > 0 ? capturedDoc.width : imageSize.width;
    const baseHeight = capturedDoc.height > 0 ? capturedDoc.height : imageSize.height;

    let initialRectangle: Rectangle | null = null;

    if (capturedDoc.rectangle) {
      initialRectangle = scaleRectangle(
        capturedDoc.rectangle,
        baseWidth,
        baseHeight,
        imageSize.width,
        imageSize.height,
      );
    } else if (capturedDoc.quad && capturedDoc.quad.length === 4) {
      const quadRectangle = quadToRectangle(capturedDoc.quad as Quad);
      if (quadRectangle) {
        initialRectangle = scaleRectangle(
          quadRectangle,
          baseWidth,
          baseHeight,
          imageSize.width,
          imageSize.height,
        );
      }
    }

    cropInitializedRef.current = true;
    setCropRectangle(
      initialRectangle ?? createFullImageRectangle(imageSize.width || 1, imageSize.height || 1),
    );
  }, [capturedDoc, imageSize]);

  const resetState = useCallback(() => {
    setScreen('scanner');
    setCapturedDoc(null);
    setCropRectangle(null);
    setImageSize(null);
    setProcessing(false);
    manualCapturePending.current = false;
    processingCaptureRef.current = false;
    cropInitializedRef.current = false;
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

  const processAutoCapture = useCallback(
    async (document: DocScannerCapture) => {
      console.log('[FullDocScanner] processAutoCapture started');
      manualCapturePending.current = false;
      const normalizedDoc = normalizeCapturedDocument(document);
      const cropManager = NativeModules.CustomCropManager as CustomCropManagerType | undefined;

      if (!cropManager?.crop) {
        console.error('[FullDocScanner] CustomCropManager.crop is not available');
        emitError(new Error('CustomCropManager.crop is not available'));
        return;
      }

      console.log('[FullDocScanner] Setting processing to true');
      setProcessing(true);

      try {
        const size = await resolveImageSize(
          normalizedDoc.path,
          normalizedDoc.width,
          normalizedDoc.height,
        );

        const targetWidthRaw = size.width > 0 ? size.width : normalizedDoc.width;
        const targetHeightRaw = size.height > 0 ? size.height : normalizedDoc.height;
        const baseWidth = normalizedDoc.width > 0 ? normalizedDoc.width : targetWidthRaw;
        const baseHeight = normalizedDoc.height > 0 ? normalizedDoc.height : targetHeightRaw;
        const targetWidth = targetWidthRaw > 0 ? targetWidthRaw : baseWidth || 1;
        const targetHeight = targetHeightRaw > 0 ? targetHeightRaw : baseHeight || 1;

        let rectangleBase: Rectangle | null = normalizedDoc.rectangle ?? null;
        if (!rectangleBase && normalizedDoc.quad && normalizedDoc.quad.length === 4) {
          rectangleBase = quadToRectangle(normalizedDoc.quad as Quad);
        }

        const scaledRectangle = rectangleBase
          ? scaleRectangle(
              rectangleBase,
              baseWidth || targetWidth,
              baseHeight || targetHeight,
              targetWidth,
              targetHeight,
            )
          : null;

        const rectangleToUse = scaledRectangle ?? createFullImageRectangle(targetWidth, targetHeight);

        console.log('[FullDocScanner] Calling CustomCropManager.crop with:', {
          rectangle: rectangleToUse,
          imageUri: ensureFileUri(normalizedDoc.path),
          targetSize: { width: targetWidth, height: targetHeight },
        });

        const base64 = await new Promise<string>((resolve, reject) => {
          cropManager.crop(
            {
              topLeft: rectangleToUse.topLeft,
              topRight: rectangleToUse.topRight,
              bottomRight: rectangleToUse.bottomRight,
              bottomLeft: rectangleToUse.bottomLeft,
              width: targetWidth,
              height: targetHeight,
            },
            ensureFileUri(normalizedDoc.path),
            (error: unknown, result: { image: string }) => {
              if (error) {
                console.error('[FullDocScanner] CustomCropManager.crop error:', error);
                reject(error instanceof Error ? error : new Error('Crop failed'));
                return;
              }
              console.log('[FullDocScanner] CustomCropManager.crop success, base64 length:', result.image?.length);
              resolve(result.image);
            },
          );
        });

        const finalDoc: CapturedDocument = {
          ...normalizedDoc,
          rectangle: rectangleToUse,
        };

        console.log('[FullDocScanner] Calling onResult with base64 length:', base64?.length);
        onResult({
          original: finalDoc,
          rectangle: rectangleToUse,
          base64,
        });

        console.log('[FullDocScanner] Resetting state');
        resetState();
      } catch (error) {
        setProcessing(false);
        emitError(error instanceof Error ? error : new Error(String(error)), 'Failed to process document.');
      } finally {
        processingCaptureRef.current = false;
      }
    },
    [emitError, onResult, resetState],
  );

  const handleCapture = useCallback(
    (document: DocScannerCapture) => {
      console.log('[FullDocScanner] handleCapture called:', {
        origin: document.origin,
        path: document.path,
        width: document.width,
        height: document.height,
        hasQuad: !!document.quad,
        hasRectangle: !!document.rectangle,
      });

      if (processingCaptureRef.current) {
        console.log('[FullDocScanner] Already processing, skipping');
        return;
      }

      const normalizedDoc = normalizeCapturedDocument(document);

      // 자동 촬영이든 수동 촬영이든 모두 crop 화면으로 이동
      console.log('[FullDocScanner] Moving to crop/preview screen');
      manualCapturePending.current = false;
      processingCaptureRef.current = false;
      cropInitializedRef.current = false;
      setCapturedDoc(normalizedDoc);
      setImageSize(null);
      setCropRectangle(null);
      setScreen('crop');
    },
    [],
  );

  const handleCropChange = useCallback((rectangle: Rectangle) => {
    setCropRectangle(rectangle);
  }, []);

  const triggerManualCapture = useCallback(() => {
    console.log('[FullDocScanner] triggerManualCapture called');
    if (processingCaptureRef.current) {
      console.log('[FullDocScanner] Already processing, skipping manual capture');
      return;
    }

    // Reset DocScanner state before capturing
    docScannerRef.current?.reset();

    console.log('[FullDocScanner] Setting manualCapturePending to true');
    manualCapturePending.current = true;

    // Small delay to ensure reset completes
    setTimeout(() => {
      const capturePromise = docScannerRef.current?.capture();
      console.log('[FullDocScanner] capturePromise:', !!capturePromise);
      if (capturePromise && typeof capturePromise.catch === 'function') {
        capturePromise.catch((error: unknown) => {
          manualCapturePending.current = false;
          console.warn('[FullDocScanner] manual capture failed', error);
        });
      } else if (!capturePromise) {
        console.warn('[FullDocScanner] No capture promise returned');
        manualCapturePending.current = false;
      }
    }, 100);
  }, []);

  const performCrop = useCallback(async (): Promise<{ base64: string; rectangle: Rectangle }> => {
    if (!capturedDoc) {
      throw new Error('No captured document to crop');
    }

    const size = imageSize ?? { width: capturedDoc.width, height: capturedDoc.height };
    const cropManager = NativeModules.CustomCropManager as CustomCropManagerType | undefined;

    if (!cropManager?.crop) {
      throw new Error('CustomCropManager.crop is not available');
    }

    const baseWidth = capturedDoc.width > 0 ? capturedDoc.width : size.width;
    const baseHeight = capturedDoc.height > 0 ? capturedDoc.height : size.height;
    const targetWidth = size.width > 0 ? size.width : baseWidth || 1;
    const targetHeight = size.height > 0 ? size.height : baseHeight || 1;

    let fallbackRectangle: Rectangle | null = null;

    if (capturedDoc.rectangle) {
      fallbackRectangle = scaleRectangle(
        capturedDoc.rectangle,
        baseWidth || targetWidth,
        baseHeight || targetHeight,
        targetWidth,
        targetHeight,
      );
    } else if (capturedDoc.quad && capturedDoc.quad.length === 4) {
      const quadRectangle = quadToRectangle(capturedDoc.quad as Quad);
      if (quadRectangle) {
        fallbackRectangle = scaleRectangle(
          quadRectangle,
          baseWidth || targetWidth,
          baseHeight || targetHeight,
          targetWidth,
          targetHeight,
        );
      }
    }

    const rectangleToUse = cropRectangle ?? fallbackRectangle ?? createFullImageRectangle(targetWidth, targetHeight);

    const base64 = await new Promise<string>((resolve, reject) => {
      cropManager.crop(
        {
          topLeft: rectangleToUse.topLeft,
          topRight: rectangleToUse.topRight,
          bottomRight: rectangleToUse.bottomRight,
          bottomLeft: rectangleToUse.bottomLeft,
          width: targetWidth,
          height: targetHeight,
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

    return { base64, rectangle: rectangleToUse };
  }, [capturedDoc, cropRectangle, imageSize]);

  const handleConfirm = useCallback(async () => {
    if (!capturedDoc) {
      return;
    }

    try {
      setProcessing(true);
      const { base64, rectangle } = await performCrop();
      setProcessing(false);
      const finalDoc: CapturedDocument = {
        ...capturedDoc,
        rectangle,
      };
      onResult({
        original: finalDoc,
        rectangle,
        base64,
      });
      resetState();
    } catch (error) {
      setProcessing(false);
      emitError(error instanceof Error ? error : new Error(String(error)), 'Failed to process document.');
    }
  }, [capturedDoc, emitError, onResult, performCrop, resetState]);

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
            ref={docScannerRef}
            autoCapture={!manualCapture}
            overlayColor={overlayColor}
            showGrid={showGrid}
            gridColor={resolvedGridColor}
            gridLineWidth={gridLineWidth}
            minStableFrames={minStableFrames ?? 6}
            detectionConfig={detectionConfig}
            onCapture={handleCapture}
            showManualCaptureButton={false}
          >
            <View style={styles.overlayTop} pointerEvents="box-none">
              <TouchableOpacity
                style={styles.closeButton}
                onPress={handleClose}
                accessibilityLabel={mergedStrings.cancel}
                accessibilityRole="button"
              >
                <Text style={styles.closeButtonLabel}>×</Text>
              </TouchableOpacity>
            </View>
            {(mergedStrings.captureHint || mergedStrings.manualHint) && (
              <View style={styles.instructionsContainer} pointerEvents="none">
                <View style={styles.instructions}>
                  {mergedStrings.captureHint && (
                    <Text style={styles.captureText}>{mergedStrings.captureHint}</Text>
                  )}
                  {mergedStrings.manualHint && (
                    <Text style={styles.captureText}>{mergedStrings.manualHint}</Text>
                  )}
                </View>
              </View>
            )}
            <View style={styles.shutterContainer} pointerEvents="box-none">
              <TouchableOpacity
                style={[styles.shutterButton, processing && styles.shutterButtonDisabled]}
                onPress={triggerManualCapture}
                disabled={processing}
                accessibilityLabel={mergedStrings.manualHint}
                accessibilityRole="button"
              >
                <View style={styles.shutterInner} />
              </TouchableOpacity>
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
              {mergedStrings.retake && <Text style={styles.buttonText}>{mergedStrings.retake}</Text>}
            </TouchableOpacity>
            <TouchableOpacity style={[styles.actionButton, styles.primaryButton]} onPress={handleConfirm}>
              {mergedStrings.confirm && <Text style={styles.buttonText}>{mergedStrings.confirm}</Text>}
            </TouchableOpacity>
          </View>
        </View>
      )}

      {processing && (
        <View style={styles.processingOverlay}>
          <ActivityIndicator size="large" color={overlayStrokeColor} />
          {mergedStrings.processing && (
            <Text style={styles.processingText}>{mergedStrings.processing}</Text>
          )}
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
  overlayTop: {
    position: 'absolute',
    top: 48,
    right: 24,
    zIndex: 10,
  },
  instructionsContainer: {
    position: 'absolute',
    top: '40%',
    left: 0,
    right: 0,
    alignItems: 'center',
    zIndex: 5,
  },
  shutterContainer: {
    position: 'absolute',
    bottom: 64,
    left: 0,
    right: 0,
    alignItems: 'center',
    zIndex: 10,
  },
  closeButton: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  closeButtonLabel: {
    color: '#fff',
    fontSize: 28,
    lineHeight: 32,
    marginTop: -3,
  },
  instructions: {
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
  shutterButton: {
    width: 80,
    height: 80,
    borderRadius: 40,
    borderWidth: 4,
    borderColor: '#fff',
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: 'rgba(255,255,255,0.1)',
  },
  shutterButtonDisabled: {
    opacity: 0.4,
  },
  shutterInner: {
    width: 60,
    height: 60,
    borderRadius: 30,
    backgroundColor: '#fff',
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
