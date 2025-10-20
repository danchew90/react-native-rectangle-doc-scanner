import React, { useEffect, useMemo } from 'react';
import { processColor, StyleSheet } from 'react-native';
import {
  Canvas,
  LinearGradient,
  Path,
  Skia,
  SkPath,
  useValue,
  vec,
} from '@shopify/react-native-skia';
import type { Point, Rectangle } from '../types';

export interface ScannerOverlayProps {
  /** 자동 캡처 중임을 표시할 때 true로 설정합니다. */
  active: boolean;
  color?: string;
  lineWidth?: number;
  polygon?: Rectangle | null;
}

const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));

const withAlpha = (inputColor: string, alpha: number): string => {
  const parsed = processColor(inputColor);
  const normalized =
    typeof parsed === 'number'
      ? parsed >>> 0
      : parsed && typeof parsed === 'object' && 'argb' in parsed && typeof parsed.argb === 'number'
      ? parsed.argb >>> 0
      : null;

  if (normalized == null) {
    return inputColor;
  }

  const r = (normalized >> 16) & 0xff;
  const g = (normalized >> 8) & 0xff;
  const b = normalized & 0xff;
  const clampedAlpha = clamp(alpha, 0, 1);

  return `rgba(${r}, ${g}, ${b}, ${clampedAlpha})`;
};

const createPolygonPath = (polygon: Rectangle | null): SkPath | null => {
  if (!polygon) {
    return null;
  }

  const path = Skia.Path.Make();
  path.moveTo(polygon.topLeft.x, polygon.topLeft.y);
  path.lineTo(polygon.topRight.x, polygon.topRight.y);
  path.lineTo(polygon.bottomRight.x, polygon.bottomRight.y);
  path.lineTo(polygon.bottomLeft.x, polygon.bottomLeft.y);
  path.close();
  return path;
};

const interpolate = (a: Point, b: Point, t: number): Point => ({
  x: a.x + (b.x - a.x) * t,
  y: a.y + (b.y - a.y) * t,
});

const createLinePath = (start: Point, end: Point): SkPath => {
  const path = Skia.Path.Make();
  path.moveTo(start.x, start.y);
  path.lineTo(end.x, end.y);
  return path;
};

const createGridPaths = (polygon: Rectangle | null): SkPath[] => {
  if (!polygon) {
    return [];
  }

  const lines: SkPath[] = [];
  const steps = [1 / 3, 2 / 3];

  steps.forEach((t) => {
    const horizontalStart = interpolate(polygon.topLeft, polygon.bottomLeft, t);
    const horizontalEnd = interpolate(polygon.topRight, polygon.bottomRight, t);
    lines.push(createLinePath(horizontalStart, horizontalEnd));

    const verticalStart = interpolate(polygon.topLeft, polygon.topRight, t);
    const verticalEnd = interpolate(polygon.bottomLeft, polygon.bottomRight, t);
    lines.push(createLinePath(verticalStart, verticalEnd));
  });

  return lines;
};

const getPolygonMetrics = (polygon: Rectangle) => {
  const minX = Math.min(polygon.topLeft.x, polygon.bottomLeft.x, polygon.topRight.x, polygon.bottomRight.x);
  const maxX = Math.max(polygon.topLeft.x, polygon.bottomLeft.x, polygon.topRight.x, polygon.bottomRight.x);
  const minY = Math.min(polygon.topLeft.y, polygon.topRight.y, polygon.bottomLeft.y, polygon.bottomRight.y);
  const maxY = Math.max(polygon.topLeft.y, polygon.topRight.y, polygon.bottomLeft.y, polygon.bottomRight.y);

  return {
    minX,
    maxX,
    minY,
    maxY,
    width: maxX - minX,
    height: maxY - minY,
    centerX: minX + (maxX - minX) / 2,
  };
};

const SCAN_DURATION_MS = 2200;

export const ScannerOverlay: React.FC<ScannerOverlayProps> = ({
  active,
  color = '#0b7ef4',
  lineWidth = StyleSheet.hairlineWidth,
  polygon,
}) => {
  const path = useMemo(() => createPolygonPath(polygon ?? null), [polygon]);
  const gridPaths = useMemo(() => createGridPaths(polygon ?? null), [polygon]);
  const metrics = useMemo(() => (polygon ? getPolygonMetrics(polygon) : null), [polygon]);

  const gradientStart = useValue(vec(0, 0));
  const gradientEnd = useValue(vec(0, 0));
  const gradientColors = useValue<number[]>([
    Skia.Color(withAlpha(color, 0)),
    Skia.Color(withAlpha(color, 0.85)),
    Skia.Color(withAlpha(color, 0)),
  ]);
  const gradientPositions = useValue<number[]>([0, 0.5, 1]);

  useEffect(() => {
    if (!metrics) {
      return;
    }

    let frame: number | null = null;
    const transparentColor = Skia.Color(withAlpha(color, 0));
    const highlightColor = Skia.Color(withAlpha(color, 0.9));
    const bandSize = Math.max(metrics.height * 0.25, 20);

    const animate = () => {
      const now = Date.now() % SCAN_DURATION_MS;
      const progress = now / SCAN_DURATION_MS;
      const travel = metrics.height + bandSize * 2;
      const start = metrics.minY - bandSize + travel * progress;
      const end = start + bandSize;

      const clampedStart = clamp(start, metrics.minY, metrics.maxY);
      const clampedEnd = clamp(end, metrics.minY, metrics.maxY);

      gradientStart.current = vec(metrics.centerX, clampedStart);
      gradientEnd.current = vec(
        metrics.centerX,
        clampedEnd <= clampedStart ? clampedStart + 1 : clampedEnd,
      );
      gradientColors.current = [transparentColor, highlightColor, transparentColor];

      frame = requestAnimationFrame(animate);
    };

    gradientStart.current = vec(metrics.centerX, metrics.minY);
    gradientEnd.current = vec(metrics.centerX, metrics.maxY);

    if (active) {
      animate();
    } else {
      gradientColors.current = [transparentColor, transparentColor, transparentColor];
    }

    return () => {
      if (frame !== null) {
        cancelAnimationFrame(frame);
      }
    };
  }, [active, color, gradientColors, gradientEnd, gradientStart, metrics]);

  if (!polygon || !path || !metrics) {
    return null;
  }

  const strokeColor = withAlpha(color, 0.9);
  const fillColor = withAlpha(color, 0.18);
  const gridColor = withAlpha(color, 0.35);

  return (
    <Canvas style={StyleSheet.absoluteFill} pointerEvents="none">
      <Path path={path} color={fillColor} style="fill" />
      {gridPaths.map((gridPath, index) => (
        <Path key={`grid-${index}`} path={gridPath} color={gridColor} style="stroke" strokeWidth={lineWidth} />
      ))}
      <Path path={path} color={strokeColor} style="stroke" strokeWidth={lineWidth} />
      <Path path={path}>
        <LinearGradient
          start={gradientStart}
          end={gradientEnd}
          colors={gradientColors}
          positions={gradientPositions}
        />
      </Path>
    </Canvas>
  );
};
