import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Image,
  InteractionManager,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { launchImageLibrary } from 'react-native-image-picker';
import ImageCropPicker from 'react-native-image-crop-picker';
import { DocScanner } from './DocScanner';
import type { CapturedDocument } from './types';
import type {
  DetectionConfig,
  DocScannerHandle,
  DocScannerCapture,
  RectangleDetectEvent,
} from './DocScanner';

const stripFileUri = (value: string) => value.replace(/^file:\/\//, '');

const CROPPER_TIMEOUT_MS = 8000;
const CROPPER_TIMEOUT_CODE = 'cropper_timeout';

const delay = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

const runAfterInteractions = () =>
  new Promise<void>((resolve) => InteractionManager.runAfterInteractions(() => resolve()));

// Allow native pickers to finish their dismissal animations before presenting the cropper.
const waitForModalDismissal = async () => {
  await delay(50);
  await runAfterInteractions();
  if (typeof requestAnimationFrame === 'function') {
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
  }
  await delay(180);
  await runAfterInteractions();
};

// Guard the native cropper promise so we can recover if it never resolves.
async function withTimeout<T>(factory: () => Promise<T>): Promise<T> {
  let timeoutId: ReturnType<typeof setTimeout> | undefined;
  let finished = false;

  const promise = factory()
    .then((value) => {
      finished = true;
      return value;
    })
    .catch((error) => {
      finished = true;
      throw error;
    });

  const timeoutPromise = new Promise<T>((_, reject) => {
    timeoutId = setTimeout(() => {
      if (!finished) {
        const timeoutError = new Error(CROPPER_TIMEOUT_CODE);
        (timeoutError as any).code = CROPPER_TIMEOUT_CODE;
        reject(timeoutError);
      }
    }, CROPPER_TIMEOUT_MS);
  });

  try {
    return await Promise.race([promise, timeoutPromise]);
  } finally {
    if (timeoutId) {
      clearTimeout(timeoutId);
    }
    if (!finished) {
      promise.catch(() => {
        console.warn('[FullDocScanner] Cropper promise settled after timeout');
      });
    }
  }
}

type OpenCropperOptions = {
  waitForPickerDismissal?: boolean;
};

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
  /** File path to the cropped image */
  path: string;
  /** Base64-encoded image string (optional) */
  base64?: string;
  /** Original captured document info */
  original?: CapturedDocument;
}

export interface FullDocScannerStrings {
  captureHint?: string;
  manualHint?: string;
  cancel?: string;
  processing?: string;
  galleryButton?: string;
  retake?: string;
  confirm?: string;
}

export interface FullDocScannerProps {
  onResult: (result: FullDocScannerResult) => void;
  onClose?: () => void;
  detectionConfig?: DetectionConfig;
  overlayColor?: string;
  gridColor?: string;
  gridLineWidth?: number;
  showGrid?: boolean;
  strings?: FullDocScannerStrings;
  minStableFrames?: number;
  onError?: (error: Error) => void;
  enableGallery?: boolean;
  cropWidth?: number;
  cropHeight?: number;
}

export const FullDocScanner: React.FC<FullDocScannerProps> = ({
  onResult,
  onClose,
  detectionConfig,
  overlayColor = '#3170f3',
  gridColor,
  gridLineWidth,
  showGrid,
  strings,
  minStableFrames,
  onError,
  enableGallery = true,
  cropWidth = 1200,
  cropHeight = 1600,
}) => {
  const [processing, setProcessing] = useState(false);
  const [croppedImageData, setCroppedImageData] = useState<{path: string; base64?: string} | null>(null);
  const [isGalleryOpen, setIsGalleryOpen] = useState(false);
  const [rectangleDetected, setRectangleDetected] = useState(false);
  const [rectangleHint, setRectangleHint] = useState(false);
  const [flashEnabled, setFlashEnabled] = useState(false);
  const resolvedGridColor = gridColor ?? overlayColor;
  const docScannerRef = useRef<DocScannerHandle | null>(null);
  const captureModeRef = useRef<'grid' | 'no-grid' | null>(null);
  const captureInProgressRef = useRef(false);
  const rectangleCaptureTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const rectangleHintTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const mergedStrings = useMemo(
    () => ({
      captureHint: strings?.captureHint,
      manualHint: strings?.manualHint,
      cancel: strings?.cancel,
      processing: strings?.processing,
      galleryButton: strings?.galleryButton,
      retake: strings?.retake ?? 'Retake',
      confirm: strings?.confirm ?? 'Confirm',
    }),
    [strings],
  );

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

  const openCropper = useCallback(
    async (imagePath: string, options?: OpenCropperOptions) => {
      try {
        console.log('[FullDocScanner] openCropper called with path:', imagePath);
        setProcessing(true);

        // Clean path - remove file:// prefix for react-native-image-crop-picker
        // The library handles the prefix internally and double prefixing causes issues
        let cleanPath = imagePath;
        if (cleanPath.startsWith('file://')) {
          cleanPath = cleanPath.replace('file://', '');
        }
        console.log('[FullDocScanner] Clean path for cropper:', cleanPath);

        const shouldWaitForPickerDismissal = options?.waitForPickerDismissal ?? true;

        if (shouldWaitForPickerDismissal) {
          await waitForModalDismissal();
        }

        const croppedImage = await withTimeout(() =>
          ImageCropPicker.openCropper({
            path: cleanPath,
            mediaType: 'photo',
            width: cropWidth,
            height: cropHeight,
            cropping: true,
            cropperToolbarTitle: 'Crop Document',
            freeStyleCropEnabled: true,
            includeBase64: true,
            compressImageQuality: 0.9,
          }),
        );

        console.log('[FullDocScanner] Cropper returned:', {
          path: croppedImage.path,
          hasBase64: !!croppedImage.data,
        });

        setProcessing(false);

        // Show confirmation screen
        setCroppedImageData({
          path: croppedImage.path,
          base64: croppedImage.data ?? undefined,
        });
      } catch (error) {
        console.error('[FullDocScanner] openCropper error:', error);
        setProcessing(false);

        // Reset capture state when cropper fails or is cancelled
        captureInProgressRef.current = false;
        captureModeRef.current = null;

        const errorCode = (error as any)?.code;
        const errorMessage = (error as any)?.message || String(error);

        if (errorCode === CROPPER_TIMEOUT_CODE || errorMessage === CROPPER_TIMEOUT_CODE) {
          console.error('[FullDocScanner] Cropper timed out waiting for presentation');
          emitError(
            error instanceof Error ? error : new Error('Cropper timed out'),
            'Failed to open crop editor. Please try again.',
          );
        } else if (
          errorCode === 'E_PICKER_CANCELLED' ||
          errorMessage === 'User cancelled image selection' ||
          errorMessage.includes('cancelled') ||
          errorMessage.includes('cancel')
        ) {
          console.log('[FullDocScanner] User cancelled cropper');
        } else {
          emitError(
            error instanceof Error ? error : new Error(errorMessage),
            'Failed to crop image. Please try again.',
          );
        }
      }
    },
    [cropWidth, cropHeight, emitError],
  );

  const handleCapture = useCallback(
    async (document: DocScannerCapture) => {
      console.log('[FullDocScanner] handleCapture called:', {
        origin: document.origin,
        path: document.path,
        croppedPath: document.croppedPath,
        initialPath: document.initialPath,
        captureMode: captureModeRef.current,
        captureInProgress: captureInProgressRef.current,
      });

      const captureMode = captureModeRef.current;

      // Reset capture state
      captureInProgressRef.current = false;
      captureModeRef.current = null;

      if (!captureMode) {
        console.warn('[FullDocScanner] Missing capture mode for capture result, ignoring');
        return;
      }

      const normalizedDoc = normalizeCapturedDocument(document);

      if (captureMode === 'no-grid') {
        console.log('[FullDocScanner] No grid at capture button press: opening cropper for manual selection');
        await openCropper(normalizedDoc.path, { waitForPickerDismissal: false });
        return;
      }

      if (normalizedDoc.croppedPath) {
        console.log('[FullDocScanner] Grid detected: using pre-cropped image', normalizedDoc.croppedPath);
        setCroppedImageData({
          path: normalizedDoc.croppedPath,
        });
        return;
      }

      console.log('[FullDocScanner] Fallback to manual crop (no croppedPath available)');
      await openCropper(normalizedDoc.path, { waitForPickerDismissal: false });
    },
    [openCropper],
  );

  const triggerManualCapture = useCallback(() => {
    const scannerInstance = docScannerRef.current;
    const hasScanner = !!scannerInstance;
    console.log('[FullDocScanner] triggerManualCapture called', {
      processing,
      hasRef: hasScanner,
      rectangleDetected,
      rectangleHint,
      currentCaptureMode: captureModeRef.current,
      captureInProgress: captureInProgressRef.current,
    });

    if (processing) {
      console.log('[FullDocScanner] Already processing, skipping manual capture');
      return;
    }

    if (captureInProgressRef.current) {
      console.log('[FullDocScanner] Capture already in progress, skipping');
      return;
    }

    if (!hasScanner) {
      console.error('[FullDocScanner] DocScanner ref not available');
      return;
    }

    console.log('[FullDocScanner] Starting manual capture, grid detected:', rectangleDetected);

    const captureMode = rectangleDetected ? 'grid' : 'no-grid';
    captureModeRef.current = captureMode;
    captureInProgressRef.current = true;

    // Add timeout to reset state if capture hangs
    const captureTimeout = setTimeout(() => {
      if (captureInProgressRef.current) {
        console.error('[FullDocScanner] Capture timeout - resetting state');
        captureModeRef.current = null;
        captureInProgressRef.current = false;
        emitError(
          new Error('Capture timeout'),
          'Capture timed out. Please try again.',
        );
      }
    }, 10000);

    scannerInstance.capture()
      .then((result) => {
        clearTimeout(captureTimeout);
        console.log('[FullDocScanner] Manual capture promise resolved:', {
          hasResult: !!result,
          croppedImage: result?.croppedImage,
          initialImage: result?.initialImage,
        });
        // Note: captureInProgressRef is reset in handleCapture
      })
      .catch((error: unknown) => {
        clearTimeout(captureTimeout);
        const errorMessage = error instanceof Error ? error.message : String(error);
        console.error('[FullDocScanner] Manual capture failed:', errorMessage, error);
        captureModeRef.current = null;
        captureInProgressRef.current = false;

        if (error instanceof Error && error.message !== 'capture_in_progress') {
          emitError(
            error,
            'Failed to capture image. Please try again.',
          );
        }
      });
  }, [processing, rectangleDetected, rectangleHint, emitError]);

  const handleGalleryPick = useCallback(async () => {
    console.log('[FullDocScanner] handleGalleryPick called');
    if (processing || isGalleryOpen) {
      console.log('[FullDocScanner] Skipping gallery pick - already processing:', { processing, isGalleryOpen });
      return;
    }

    try {
      setIsGalleryOpen(true);
      console.log('[FullDocScanner] Launching image library...');

      const result = await launchImageLibrary({
        mediaType: 'photo',
        quality: 1,
        selectionLimit: 1,
      });

      console.log('[FullDocScanner] Image library result:', {
        didCancel: result.didCancel,
        hasAssets: !!result.assets,
        assetsLength: result.assets?.length,
      });

      setIsGalleryOpen(false);

      if (result.didCancel || !result.assets?.[0]?.uri) {
        console.log('[FullDocScanner] User cancelled gallery picker or no image selected');
        return;
      }

      const imageUri = result.assets[0].uri;
      console.log('[FullDocScanner] Gallery image selected:', imageUri);

      // Skip waitForModalDismissal for gallery - go directly to cropper
      await openCropper(imageUri, { waitForPickerDismissal: false });
    } catch (error) {
      console.error('[FullDocScanner] Gallery pick error:', error);
      setIsGalleryOpen(false);
      emitError(
        error instanceof Error ? error : new Error(String(error)),
        'Failed to pick image from gallery.',
      );
    }
  }, [processing, isGalleryOpen, openCropper, emitError]);

  const handleClose = useCallback(() => {
    onClose?.();
  }, [onClose]);

  const handleFlashToggle = useCallback(() => {
    setFlashEnabled(prev => !prev);
  }, []);

  const handleConfirm = useCallback(() => {
    if (croppedImageData) {
      onResult({
        path: croppedImageData.path,
        base64: croppedImageData.base64,
      });
    }
  }, [croppedImageData, onResult]);

  const handleRetake = useCallback(() => {
    console.log('[FullDocScanner] Retake - clearing cropped image and resetting scanner');
    setCroppedImageData(null);
    setProcessing(false);
    setRectangleDetected(false);
    setRectangleHint(false);
    captureModeRef.current = null;
    captureInProgressRef.current = false;
    if (rectangleCaptureTimeoutRef.current) {
      clearTimeout(rectangleCaptureTimeoutRef.current);
      rectangleCaptureTimeoutRef.current = null;
    }
    if (rectangleHintTimeoutRef.current) {
      clearTimeout(rectangleHintTimeoutRef.current);
      rectangleHintTimeoutRef.current = null;
    }
    // Reset DocScanner state
    if (docScannerRef.current?.reset) {
      docScannerRef.current.reset();
    }
  }, []);

  const handleRectangleDetect = useCallback((event: RectangleDetectEvent) => {
    const stableCounter = event.stableCounter ?? 0;
    const rectangleCoordinates = event.rectangleOnScreen ?? event.rectangleCoordinates;
    const hasRectangle = Boolean(rectangleCoordinates);
    const captureReady = hasRectangle && event.lastDetectionType === 0 && stableCounter >= 1;

    const scheduleClear = (
      ref: React.MutableRefObject<ReturnType<typeof setTimeout> | null>,
      clearFn: () => void,
    ) => {
      if (ref.current) {
        clearTimeout(ref.current);
      }
      ref.current = setTimeout(() => {
        ref.current = null;
        clearFn();
      }, 350);
    };

    if (hasRectangle) {
      scheduleClear(rectangleHintTimeoutRef, () => setRectangleHint(false));
      setRectangleHint(true);
    } else {
      if (rectangleHintTimeoutRef.current) {
        clearTimeout(rectangleHintTimeoutRef.current);
        rectangleHintTimeoutRef.current = null;
      }
      setRectangleHint(false);
    }

    if (captureReady) {
      scheduleClear(rectangleCaptureTimeoutRef, () => {
        console.log('[FullDocScanner] Rectangle timeout - clearing detection');
        setRectangleDetected(false);
      });
      setRectangleDetected(true);
    } else if (!hasRectangle) {
      if (rectangleCaptureTimeoutRef.current) {
        clearTimeout(rectangleCaptureTimeoutRef.current);
        rectangleCaptureTimeoutRef.current = null;
      }
      setRectangleDetected(false);
    } else if (rectangleDetected) {
      scheduleClear(rectangleCaptureTimeoutRef, () => {
        console.log('[FullDocScanner] Rectangle timeout - clearing detection');
        setRectangleDetected(false);
      });
    }

    console.log('[FullDocScanner] Rectangle detection update', {
      lastDetectionType: event.lastDetectionType,
      stableCounter,
      hasRectangle,
      captureReady,
    });
  }, [rectangleDetected]);

  useEffect(
    () => () => {
      if (rectangleCaptureTimeoutRef.current) {
        clearTimeout(rectangleCaptureTimeoutRef.current);
      }
      if (rectangleHintTimeoutRef.current) {
        clearTimeout(rectangleHintTimeoutRef.current);
      }
    },
    [],
  );

  return (
    <View style={styles.container}>
      {croppedImageData ? (
        // check_DP: Show confirmation screen
        <View style={styles.confirmationContainer}>
          <Image
            source={{ uri: croppedImageData.path }}
            style={styles.previewImage}
            resizeMode="contain"
          />
          <View style={styles.confirmationButtons}>
            <TouchableOpacity
              style={[styles.confirmButton, styles.retakeButton]}
              onPress={handleRetake}
              accessibilityLabel={mergedStrings.retake}
              accessibilityRole="button"
            >
              <Text style={styles.confirmButtonText}>{mergedStrings.retake}</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.confirmButton, styles.confirmButtonPrimary]}
              onPress={handleConfirm}
              accessibilityLabel={mergedStrings.confirm}
              accessibilityRole="button"
            >
              <Text style={styles.confirmButtonText}>{mergedStrings.confirm}</Text>
            </TouchableOpacity>
          </View>
        </View>
      ) : (
        <View style={styles.flex}>
          <DocScanner
            ref={docScannerRef}
            autoCapture={false}
            overlayColor={overlayColor}
            showGrid={showGrid}
            gridColor={resolvedGridColor}
            gridLineWidth={gridLineWidth}
            minStableFrames={minStableFrames ?? 6}
            detectionConfig={detectionConfig}
            onCapture={handleCapture}
            onRectangleDetect={handleRectangleDetect}
            showManualCaptureButton={false}
            enableTorch={flashEnabled}
          >
          <View style={styles.overlayTop} pointerEvents="box-none">
            <TouchableOpacity
              style={[
                styles.iconButton,
                processing && styles.buttonDisabled,
                flashEnabled && styles.flashButtonActive
              ]}
              onPress={handleFlashToggle}
              disabled={processing}
              accessibilityLabel="Toggle flash"
              accessibilityRole="button"
            >
              <View style={styles.iconContainer}>
                <Text style={styles.iconText}>⚡️</Text>
              </View>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.iconButton}
              onPress={handleClose}
              accessibilityLabel={mergedStrings.cancel}
              accessibilityRole="button"
            >
              <View style={styles.iconContainer}>
                <Text style={styles.closeIconText}>×</Text>
              </View>
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
            {enableGallery && (
              <TouchableOpacity
                style={[styles.iconButton, processing && styles.buttonDisabled]}
                onPress={handleGalleryPick}
                disabled={processing}
                accessibilityLabel={mergedStrings.galleryButton}
                accessibilityRole="button"
              >
                <View style={styles.iconContainer}>
                  <Text style={styles.iconText}>🖼️</Text>
                </View>
              </TouchableOpacity>
            )}
            <TouchableOpacity
              style={[styles.shutterButton, processing && styles.buttonDisabled]}
              onPress={triggerManualCapture}
              disabled={processing}
              accessibilityLabel={mergedStrings.manualHint}
              accessibilityRole="button"
            >
              <View style={[
                styles.shutterInner,
                rectangleHint && { backgroundColor: overlayColor }
              ]} />
            </TouchableOpacity>
            <View style={styles.rightButtonsPlaceholder} />
          </View>
        </DocScanner>
        </View>
      )}

      {processing && (
        <View style={styles.processingOverlay}>
          <ActivityIndicator size="large" color={overlayColor} />
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
    left: 24,
    right: 24,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
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
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 40,
    zIndex: 10,
  },
  rightButtonsPlaceholder: {
    width: 56,
  },
  iconButton: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: 'rgba(50,50,50,0.8)',
    justifyContent: 'center',
    alignItems: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.3)',
  },
  iconContainer: {
    justifyContent: 'center',
    alignItems: 'center',
  },
  iconText: {
    fontSize: 22,
  },
  closeIconText: {
    fontSize: 32,
    fontWeight: '300',
    color: '#fff',
  },
  flashButtonActive: {
    backgroundColor: 'rgba(255,215,0,0.5)',
    borderColor: '#FFD700',
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
  buttonDisabled: {
    opacity: 0.4,
  },
  shutterInner: {
    width: 60,
    height: 60,
    borderRadius: 30,
    backgroundColor: '#fff',
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
  confirmationContainer: {
    flex: 1,
    backgroundColor: '#000',
    justifyContent: 'center',
    alignItems: 'center',
  },
  previewImage: {
    width: '100%',
    height: '80%',
  },
  confirmationButtons: {
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    gap: 24,
    paddingVertical: 32,
  },
  confirmButton: {
    paddingHorizontal: 40,
    paddingVertical: 16,
    borderRadius: 12,
    minWidth: 140,
    alignItems: 'center',
    justifyContent: 'center',
  },
  retakeButton: {
    backgroundColor: 'rgba(255,255,255,0.2)',
    borderWidth: 2,
    borderColor: '#fff',
  },
  confirmButtonPrimary: {
    backgroundColor: '#3170f3',
  },
  confirmButtonText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: '600',
  },
});
