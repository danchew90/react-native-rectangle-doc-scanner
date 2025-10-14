import React, { useState, useCallback } from 'react';
import { View, StyleSheet, Image, Dimensions } from 'react-native';
import { CustomImageCropper } from 'react-native-perspective-image-cropper';
import type { Rectangle as CropperRectangle } from 'react-native-perspective-image-cropper';
import type { Point, Rectangle, CapturedDocument } from './types';
import { quadToRectangle, scaleRectangle } from './utils/coordinate';

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

  // Get initial rectangle from detected quad or use default
  const getInitialRectangle = useCallback((): CropperRectangle | undefined => {
    if (!document.quad || !imageSize) {
      return undefined;
    }

    const rect = quadToRectangle(document.quad);
    if (!rect) {
      return undefined;
    }

    // Scale from original detection coordinates to image coordinates
    const scaled = scaleRectangle(
      rect,
      document.width,
      document.height,
      imageSize.width,
      imageSize.height
    );

    return scaled as CropperRectangle;
  }, [document.quad, document.width, document.height, imageSize]);

  const handleImageLoad = useCallback((event: any) => {
    const { width, height } = event.nativeEvent.source;
    setImageSize({ width, height });
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

  // Wait for image to load to get dimensions
  if (!imageSize) {
    return (
      <View style={styles.container} onLayout={handleLayout}>
        <Image
          source={{ uri: `file://${document.path}` }}
          style={styles.hiddenImage}
          onLoad={handleImageLoad}
          resizeMode="contain"
        />
      </View>
    );
  }

  return (
    <View style={styles.container} onLayout={handleLayout}>
      <CustomImageCropper
        height={displaySize.height}
        width={displaySize.width}
        image={`file://${document.path}`}
        rectangleCoordinates={getInitialRectangle()}
        overlayColor={overlayColor}
        overlayStrokeColor={overlayStrokeColor}
        handlerColor={handlerColor}
        enablePanStrict={enablePanStrict}
        onDragEnd={handleDragEnd}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  hiddenImage: {
    width: 1,
    height: 1,
    opacity: 0,
  },
});
