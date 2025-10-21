import React, { useCallback, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Image,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { launchImageLibrary } from 'react-native-image-picker';
import ImageCropPicker from 'react-native-image-crop-picker';
import { DocScanner } from './DocScanner';
import type { CapturedDocument } from './types';
import type { DetectionConfig, DocScannerHandle, DocScannerCapture } from './DocScanner';

const stripFileUri = (value: string) => value.replace(/^file:\/\//, '');

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
  manualCapture?: boolean;
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
  manualCapture = false,
  minStableFrames,
  onError,
  enableGallery = true,
  cropWidth = 1200,
  cropHeight = 1600,
}) => {
  const [processing, setProcessing] = useState(false);
  const [croppedImageData, setCroppedImageData] = useState<{path: string; base64?: string} | null>(null);
  const [isGalleryOpen, setIsGalleryOpen] = useState(false);
  const resolvedGridColor = gridColor ?? overlayColor;
  const docScannerRef = useRef<DocScannerHandle | null>(null);
  const manualCapturePending = useRef(false);

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
    async (imagePath: string) => {
      try {
        setProcessing(true);
        const croppedImage = await ImageCropPicker.openCropper({
          path: imagePath,
          mediaType: 'photo',
          width: cropWidth,
          height: cropHeight,
          cropping: true,
          cropperToolbarTitle: 'Crop Document',
          freeStyleCropEnabled: true,
          includeBase64: true,
          compressImageQuality: 0.9,
        });

        setProcessing(false);

        // Show check_DP confirmation screen
        setCroppedImageData({
          path: croppedImage.path,
          base64: croppedImage.data ?? undefined,
        });
      } catch (error) {
        setProcessing(false);
        if ((error as any)?.message !== 'User cancelled image selection') {
          emitError(
            error instanceof Error ? error : new Error(String(error)),
            'Failed to crop image.',
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
      });

      if (manualCapturePending.current) {
        manualCapturePending.current = false;
      }

      const normalizedDoc = normalizeCapturedDocument(document);

      // Open cropper with the captured image
      await openCropper(normalizedDoc.path);
    },
    [openCropper],
  );

  const triggerManualCapture = useCallback(() => {
    console.log('[FullDocScanner] triggerManualCapture called');
    if (processing) {
      console.log('[FullDocScanner] Already processing, skipping manual capture');
      return;
    }
    if (manualCapturePending.current) {
      console.log('[FullDocScanner] Manual capture already pending, skipping');
      return;
    }

    console.log('[FullDocScanner] Setting manualCapturePending to true');
    manualCapturePending.current = true;

    const capturePromise = docScannerRef.current?.capture();
    if (capturePromise && typeof capturePromise.then === 'function') {
      capturePromise
        .then(() => {
          console.log('[FullDocScanner] Capture success');
        })
        .catch((error: unknown) => {
          manualCapturePending.current = false;
          console.warn('[FullDocScanner] manual capture failed', error);
        });
    } else if (!capturePromise) {
      console.warn('[FullDocScanner] No capture promise returned');
      manualCapturePending.current = false;
    }
  }, [processing]);

  const handleGalleryPick = useCallback(async () => {
    console.log('[FullDocScanner] handleGalleryPick called');
    if (processing || isGalleryOpen) {
      return;
    }

    try {
      setIsGalleryOpen(true);
      const result = await launchImageLibrary({
        mediaType: 'photo',
        quality: 1,
        selectionLimit: 1,
      });

      setIsGalleryOpen(false);

      if (result.didCancel || !result.assets?.[0]?.uri) {
        console.log('[FullDocScanner] User cancelled gallery picker');
        return;
      }

      const imageUri = result.assets[0].uri;
      console.log('[FullDocScanner] Gallery image selected:', imageUri);

      // Open cropper with the selected image
      await openCropper(imageUri);
    } catch (error) {
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

  const handleConfirm = useCallback(() => {
    if (croppedImageData) {
      onResult({
        path: croppedImageData.path,
        base64: croppedImageData.base64,
      });
    }
  }, [croppedImageData, onResult]);

  const handleRetake = useCallback(() => {
    setCroppedImageData(null);
  }, []);

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
            autoCapture={!manualCapture && !isGalleryOpen}
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
            {enableGallery && (
              <TouchableOpacity
                style={[styles.galleryButton, processing && styles.buttonDisabled]}
                onPress={handleGalleryPick}
                disabled={processing}
                accessibilityLabel={mergedStrings.galleryButton}
                accessibilityRole="button"
              >
                <Text style={styles.galleryButtonText}>📁</Text>
              </TouchableOpacity>
            )}
            <TouchableOpacity
              style={[styles.shutterButton, processing && styles.buttonDisabled]}
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
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    gap: 24,
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
  galleryButton: {
    width: 60,
    height: 60,
    borderRadius: 30,
    borderWidth: 3,
    borderColor: '#fff',
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: 'rgba(255,255,255,0.1)',
  },
  galleryButtonText: {
    fontSize: 28,
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
