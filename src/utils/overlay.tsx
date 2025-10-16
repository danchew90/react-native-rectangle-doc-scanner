import React, { useMemo } from 'react';
import { View, StyleSheet, useWindowDimensions } from 'react-native';
import { Canvas, Path, Skia } from '@shopify/react-native-skia';
import type { Point } from '../types';

const lerp = (start: Point, end: Point, t: number): Point => ({
  x: start.x + (end.x - start.x) * t,
  y: start.y + (end.y - start.y) * t,
});

type OverlayProps = {
  quad: Point[] | null;
  color?: string;
  frameSize: { width: number; height: number } | null;
  showGrid?: boolean;
  gridColor?: string;
  gridLineWidth?: number;
};

type OverlayGeometry = {
  outlinePath: ReturnType<typeof Skia.Path.Make> | null;
  gridPaths: ReturnType<typeof Skia.Path.Make>[];
};

export const Overlay: React.FC<OverlayProps> = ({
  quad,
  color = '#e7a649',
  frameSize,
  showGrid = true,
  gridColor = 'rgba(231, 166, 73, 0.35)',
  gridLineWidth = 2,
}) => {
  const { width: screenWidth, height: screenHeight } = useWindowDimensions();

  const { outlinePath, gridPaths }: OverlayGeometry = useMemo(() => {
    if (!quad || !frameSize) {
      if (__DEV__) {
        console.log('[Overlay] no quad or frameSize', { quad, frameSize });
      }
      return { outlinePath: null, gridPaths: [] };
    }

    if (__DEV__) {
      console.log('[Overlay] drawing quad:', quad);
      console.log('[Overlay] color:', color);
      console.log('[Overlay] screen dimensions:', screenWidth, 'x', screenHeight);
      console.log('[Overlay] frame dimensions:', frameSize.width, 'x', frameSize.height);
    }

    // Check if camera is in landscape mode (width > height) but screen is portrait (height > width)
    const isFrameLandscape = frameSize.width > frameSize.height;
    const isScreenPortrait = screenHeight > screenWidth;
    const needsRotation = isFrameLandscape && isScreenPortrait;

    if (__DEV__) {
      console.log('[Overlay] needs rotation:', needsRotation);
    }

    let transformedQuad: Point[];

    if (needsRotation) {
      // Camera is landscape, screen is portrait - need to rotate 90 degrees
      // Transform: rotate 90° clockwise and scale
      // New coordinates: x' = y * (screenWidth / frameHeight), y' = (frameWidth - x) * (screenHeight / frameWidth)
      const scaleX = screenWidth / frameSize.height;
      const scaleY = screenHeight / frameSize.width;

      if (__DEV__) {
        console.log('[Overlay] rotation scale factors:', scaleX, 'x', scaleY);
      }

      transformedQuad = quad.map((p) => ({
        x: p.y * scaleX,
        y: (frameSize.width - p.x) * scaleY,
      }));
    } else {
      // Same orientation - just scale
      const scaleX = screenWidth / frameSize.width;
      const scaleY = screenHeight / frameSize.height;

      if (__DEV__) {
        console.log('[Overlay] scale factors:', scaleX, 'x', scaleY);
      }

      transformedQuad = quad.map((p) => ({
        x: p.x * scaleX,
        y: p.y * scaleY,
      }));
    }

    if (__DEV__) {
      console.log('[Overlay] transformed quad:', transformedQuad);
    }

    const skPath = Skia.Path.Make();
    skPath.moveTo(transformedQuad[0].x, transformedQuad[0].y);
    transformedQuad.slice(1).forEach((p) => skPath.lineTo(p.x, p.y));
    skPath.close();
    const grid: ReturnType<typeof Skia.Path.Make>[] = [];

    if (showGrid) {
      const [topLeft, topRight, bottomRight, bottomLeft] = transformedQuad;
      const steps = [1 / 3, 2 / 3];

      steps.forEach((t) => {
        const start = lerp(topLeft, topRight, t);
        const end = lerp(bottomLeft, bottomRight, t);
        const verticalPath = Skia.Path.Make();
        verticalPath.moveTo(start.x, start.y);
        verticalPath.lineTo(end.x, end.y);
        grid.push(verticalPath);
      });

      steps.forEach((t) => {
        const start = lerp(topLeft, bottomLeft, t);
        const end = lerp(topRight, bottomRight, t);
        const horizontalPath = Skia.Path.Make();
        horizontalPath.moveTo(start.x, start.y);
        horizontalPath.lineTo(end.x, end.y);
        grid.push(horizontalPath);
      });
    }

    return { outlinePath: skPath, gridPaths: grid };
  }, [quad, screenWidth, screenHeight, frameSize, showGrid]);

  if (__DEV__) {
    console.log('[Overlay] rendering Canvas with dimensions:', screenWidth, 'x', screenHeight);
  }

  return (
    <View style={styles.container} pointerEvents="none">
      <Canvas style={{ width: screenWidth, height: screenHeight }}>
        {outlinePath && (
          <>
            <Path path={outlinePath} color={color} style="stroke" strokeWidth={8} />
            <Path path={outlinePath} color="rgba(231, 166, 73, 0.2)" style="fill" />
            {gridPaths.map((gridPath, index) => (
              <Path
                // eslint-disable-next-line react/no-array-index-key
                key={`grid-${index}`}
                path={gridPath}
                color={gridColor}
                style="stroke"
                strokeWidth={gridLineWidth}
              />
            ))}
          </>
        )}
      </Canvas>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
  },
});
