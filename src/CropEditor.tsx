import React, { useState, useCallback, useEffect } from 'react';
import { View, StyleSheet, Image, Dimensions, ActivityIndicator, Text, NativeModules } from 'react-native';
import CustomImageCropper from 'react-native-perspective-image-cropper';
import type { Rectangle as CropperRectangle } from 'react-native-perspective-image-cropper';
import type { Point, Rectangle, CapturedDocument } from './types';
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

interface CropEditorProps {
  document: CapturedDocument;
  overlayColor?: string;
  overlayStrokeColor?: string;
  handlerColor?: string;
  enablePanStrict?: boolean;
  onCropChange?: (rectangle: Rectangle) => void;
}

/**
 * CropEditor Component
 *
 * Displays a captured document image with adjustable corner handles.
 * Uses react-native-perspective-image-cropper for the cropping UI.
 *
 * @param document - The captured document with path and detected quad
 * @param overlayColor - Color of the overlay outside the crop area (default: 'rgba(0,0,0,0.5)')
 * @param overlayStrokeColor - Color of the crop boundary lines (default: '#e7a649')
 * @param handlerColor - Color of the corner handles (default: '#e7a649')
 * @param enablePanStrict - Enable strict panning behavior
 * @param onCropChange - Callback when user adjusts crop corners
 */
export const CropEditor: React.FC<CropEditorProps> = ({
  document,
  overlayColor = 'rgba(0,0,0,0.5)',
  overlayStrokeColor = '#e7a649',
  handlerColor = '#e7a649',
  enablePanStrict = false,
  onCropChange,
}) => {
  const [imageSize, setImageSize] = useState<{ width: number; height: number } | null>(null);
  const [displaySize, setDisplaySize] = useState<{ width: number; height: number }>({
    width: Dimensions.get('window').width,
    height: Dimensions.get('window').height,
  });
  const [isImageLoading, setIsImageLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [croppedImageUri, setCroppedImageUri] = useState<string | null>(null);

  useEffect(() => {
    console.log('[CropEditor] Document path:', document.path);
    console.log('[CropEditor] Document dimensions:', document.width, 'x', document.height);
    console.log('[CropEditor] Document quad:', document.quad);
    console.log('[CropEditor] Document rectangle:', document.rectangle);

    // Load image size using Image.getSize
    const imageUri = document.path.startsWith('file://') ? document.path : `file://${document.path}`;
    Image.getSize(
      imageUri,
      (width, height) => {
        console.log('[CropEditor] Image.getSize success:', { width, height });
        setImageSize({ width, height });

        // If we have a rectangle (from auto-capture), crop the image
        if (document.rectangle || document.quad) {
          cropImageToRectangle(imageUri, width, height);
        } else {
          setIsImageLoading(false);
          setLoadError(null);
        }
      },
      (error) => {
        console.error('[CropEditor] Image.getSize error:', error);
        // Fallback to document dimensions
        console.log('[CropEditor] Using fallback dimensions:', document.width, 'x', document.height);
        setImageSize({ width: document.width, height: document.height });
        setIsImageLoading(false);
      }
    );
  }, [document]);

  const cropImageToRectangle = useCallback((imageUri: string, width: number, height: number) => {
    const cropManager = NativeModules.CustomCropManager as CustomCropManagerType | undefined;

    if (!cropManager?.crop) {
      console.warn('[CropEditor] CustomCropManager not available, showing original image');
      setIsImageLoading(false);
      return;
    }

    const baseWidth = document.width > 0 ? document.width : width;
    const baseHeight = document.height > 0 ? document.height : height;

    let rectangle: Rectangle | null = document.rectangle ?? null;
    if (!rectangle && document.quad && document.quad.length === 4) {
      rectangle = quadToRectangle(document.quad);
    }

    if (!rectangle) {
      console.warn('[CropEditor] No rectangle found, showing original image');
      setIsImageLoading(false);
      return;
    }

    // Scale rectangle to actual image size
    const scaledRect = scaleRectangle(rectangle, baseWidth, baseHeight, width, height);

    console.log('[CropEditor] Cropping image with rectangle:', scaledRect);

    cropManager.crop(
      {
        topLeft: scaledRect.topLeft,
        topRight: scaledRect.topRight,
        bottomRight: scaledRect.bottomRight,
        bottomLeft: scaledRect.bottomLeft,
        width,
        height,
      },
      imageUri,
      (error: unknown, result: { image: string }) => {
        if (error) {
          console.error('[CropEditor] Crop error:', error);
          setIsImageLoading(false);
          return;
        }
        console.log('[CropEditor] Crop success, base64 length:', result.image?.length);
        // Convert base64 to data URI
        const croppedUri = `data:image/jpeg;base64,${result.image}`;
        setCroppedImageUri(croppedUri);
        setIsImageLoading(false);
        setLoadError(null);
      }
    );
  }, [document]);

  // Get initial rectangle from detected quad or use default
  const getInitialRectangle = useCallback((): CropperRectangle | undefined => {
    if (!imageSize) {
      return undefined;
    }

    const baseWidth = document.width > 0 ? document.width : imageSize.width;
    const baseHeight = document.height > 0 ? document.height : imageSize.height;

    const sourceRectangle = document.rectangle
      ? document.rectangle
      : document.quad && document.quad.length === 4
      ? quadToRectangle(document.quad)
      : createFullImageRectangle(baseWidth, baseHeight);

    const scaled = scaleRectangle(
      sourceRectangle ?? createFullImageRectangle(baseWidth, baseHeight),
      baseWidth,
      baseHeight,
      imageSize.width,
      imageSize.height
    );

    return scaled as CropperRectangle;
  }, [document.rectangle, document.quad, document.width, document.height, imageSize]);

  const handleImageLoad = useCallback((event: any) => {
    // This is just for debugging - actual size is loaded via Image.getSize in useEffect
    console.log('[CropEditor] Image onLoad event triggered');
  }, []);

  const handleLayout = useCallback((event: any) => {
    const { width, height } = event.nativeEvent.layout;
    setDisplaySize({ width, height });
  }, []);

  const handleDragEnd = useCallback((coordinates: CropperRectangle) => {
    if (!imageSize) {
      return;
    }

    // Convert back to Rectangle type
    const rect: Rectangle = {
      topLeft: coordinates.topLeft,
      topRight: coordinates.topRight,
      bottomRight: coordinates.bottomRight,
      bottomLeft: coordinates.bottomLeft,
    };

    onCropChange?.(rect);
  }, [imageSize, onCropChange]);

  // Ensure proper file URI format
  const imageUri = document.path.startsWith('file://')
    ? document.path
    : `file://${document.path}`;

  const initialRect = getInitialRectangle();

  console.log('[CropEditor] Rendering with:', {
    imageUri,
    imageSize,
    displaySize,
    initialRect,
    isLoading: isImageLoading,
    hasError: !!loadError,
  });

  return (
    <View style={styles.container} onLayout={handleLayout}>
      {/* Show loading, error, or cropper */}
      {loadError ? (
        <View style={styles.errorContainer}>
          <Text style={styles.errorText}>Failed to load image</Text>
          <Text style={styles.errorPath}>{imageUri}</Text>
        </View>
      ) : !imageSize || isImageLoading ? (
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color={handlerColor} />
          <Text style={styles.loadingText}>Loading image...</Text>
        </View>
      ) : (
        <>
          {/* Show cropped image if available, otherwise show original */}
          <Image
            source={{ uri: croppedImageUri || imageUri }}
            style={styles.fullImage}
            resizeMode="contain"
            onLoad={() => console.log('[CropEditor] Image loaded successfully', croppedImageUri ? 'cropped' : 'original')}
            onError={(e) => console.error('[CropEditor] Image load error:', e.nativeEvent.error)}
          />
          {/* Temporarily disabled CustomImageCropper - showing image only */}
          {/* <CustomImageCropper
            height={displaySize.height}
            width={displaySize.width}
            image={imageUri}
            rectangleCoordinates={initialRect}
            overlayColor={overlayColor}
            overlayStrokeColor={overlayStrokeColor}
            handlerColor={handlerColor}
            enablePanStrict={enablePanStrict}
            onDragEnd={handleDragEnd}
          /> */}
        </>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    color: '#fff',
    marginTop: 16,
    fontSize: 16,
  },
  errorContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  errorText: {
    color: '#ff4444',
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 10,
  },
  errorPath: {
    color: '#999',
    fontSize: 12,
    textAlign: 'center',
  },
  fullImage: {
    width: '100%',
    height: '100%',
  },
});
