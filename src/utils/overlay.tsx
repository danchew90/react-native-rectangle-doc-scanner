import React, { useMemo } from 'react';
import { View, StyleSheet, useWindowDimensions } from 'react-native';
import { Canvas, Path, Skia } from '@shopify/react-native-skia';
import type { Point } from '../types';

const lerp = (start: Point, end: Point, t: number): Point => ({
  x: start.x + (end.x - start.x) * t,
  y: start.y + (end.y - start.y) * t,
});

const withAlpha = (value: string, alpha: number): string => {
  const hexMatch = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(value.trim());
  if (!hexMatch) {
    return `rgba(231, 166, 73, ${alpha})`;
  }

  const hex = hexMatch[1];
  const normalize = hex.length === 3
    ? hex.split('').map((ch) => ch + ch).join('')
    : hex;

  const r = parseInt(normalize.slice(0, 2), 16);
  const g = parseInt(normalize.slice(2, 4), 16);
  const b = parseInt(normalize.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};

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

const buildPath = (points: Point[]) => {
  const path = Skia.Path.Make();
  path.moveTo(points[0].x, points[0].y);
  points.slice(1).forEach((p) => path.lineTo(p.x, p.y));
  path.close();
  return path;
};

const orderQuad = (points: Point[]): Point[] => {
  if (points.length !== 4) {
    return points;
  }

  const sum = (p: Point) => p.x + p.y;
  const diff = (p: Point) => p.x - p.y;

  const topLeft = points.reduce((prev, curr) => (sum(curr) < sum(prev) ? curr : prev));
  const bottomRight = points.reduce((prev, curr) => (sum(curr) > sum(prev) ? curr : prev));

  const remaining = points.filter((p) => p !== topLeft && p !== bottomRight);
  if (remaining.length !== 2) {
    return [topLeft, bottomRight, ...remaining];
  }

  const [candidate1, candidate2] = remaining;
  const topRight = diff(candidate1) > diff(candidate2) ? candidate1 : candidate2;
  const bottomLeft = topRight === candidate1 ? candidate2 : candidate1;

  return [topLeft, topRight, bottomRight, bottomLeft];
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
  const fillColor = useMemo(() => withAlpha(color, 0.2), [color]);

  const { outlinePath, gridPaths }: OverlayGeometry = useMemo(() => {
    let transformedQuad: Point[] | null = null;
    let sourceQuad: Point[] | null = null;
    let sourceFrameSize = frameSize;

    if (quad && frameSize) {
      sourceQuad = quad;
    } else {
      // No detection yet – skip drawing
      return { outlinePath: null, gridPaths: [] };
    }

    if (sourceQuad && sourceFrameSize) {
      if (__DEV__) {
        console.log('[Overlay] drawing quad:', sourceQuad);
        console.log('[Overlay] color:', color);
        console.log('[Overlay] screen dimensions:', screenWidth, 'x', screenHeight);
        console.log('[Overlay] frame dimensions:', sourceFrameSize.width, 'x', sourceFrameSize.height);
      }

      const isFrameLandscape = sourceFrameSize.width > sourceFrameSize.height;
      const isScreenPortrait = screenHeight > screenWidth;
      const needsRotation = isFrameLandscape && isScreenPortrait;

      if (needsRotation) {
        const scaleX = screenWidth / sourceFrameSize.height;
        const scaleY = screenHeight / sourceFrameSize.width;

        transformedQuad = sourceQuad.map((p) => ({
          x: p.y * scaleX,
          y: (sourceFrameSize.width - p.x) * scaleY,
        }));
      } else {
        const scaleX = screenWidth / sourceFrameSize.width;
        const scaleY = screenHeight / sourceFrameSize.height;

        transformedQuad = sourceQuad.map((p) => ({
          x: p.x * scaleX,
          y: p.y * scaleY,
        }));
      }
    }

    if (!transformedQuad) {
      return { outlinePath: null, gridPaths: [] };
    }

    const normalizedQuad = orderQuad(transformedQuad);
    const skPath = buildPath(normalizedQuad);
    const grid: ReturnType<typeof Skia.Path.Make>[] = [];

    if (showGrid) {
      const [topLeft, topRight, bottomRight, bottomLeft] = normalizedQuad;
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
  }, [quad, screenWidth, screenHeight, frameSize, showGrid, color]);

  if (__DEV__) {
    console.log('[Overlay] rendering Canvas with dimensions:', screenWidth, 'x', screenHeight);
  }

  return (
    <View style={styles.container} pointerEvents="none">
      <Canvas style={{ width: screenWidth, height: screenHeight }}>
        {outlinePath && (
          <>
            <Path path={outlinePath} color={color} style="stroke" strokeWidth={8} />
            <Path path={outlinePath} color={fillColor} style="fill" />
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
