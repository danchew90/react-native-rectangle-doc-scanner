import React, { useMemo } from 'react';
import { StyleSheet, View } from 'react-native';
import type { StyleProp, ViewStyle } from 'react-native';
import type { Rectangle } from '../types';

let SvgModule: typeof import('react-native-svg') | null = null;

try {
  // eslint-disable-next-line global-require, @typescript-eslint/no-var-requires
  SvgModule = require('react-native-svg');
} catch (error) {
  SvgModule = null;
}

const GRID_STEPS = [1 / 3, 2 / 3];

const createPointsString = (polygon: Rectangle): string =>
  [
    `${polygon.topLeft.x},${polygon.topLeft.y}`,
    `${polygon.topRight.x},${polygon.topRight.y}`,
    `${polygon.bottomRight.x},${polygon.bottomRight.y}`,
    `${polygon.bottomLeft.x},${polygon.bottomLeft.y}`,
  ].join(' ');

const interpolatePoint = (a: { x: number; y: number }, b: { x: number; y: number }, t: number) => ({
  x: a.x + (b.x - a.x) * t,
  y: a.y + (b.y - a.y) * t,
});

const createGridLines = (polygon: Rectangle) =>
  GRID_STEPS.flatMap((step) => {
    const horizontalStart = interpolatePoint(polygon.topLeft, polygon.bottomLeft, step);
    const horizontalEnd = interpolatePoint(polygon.topRight, polygon.bottomRight, step);
    const verticalStart = interpolatePoint(polygon.topLeft, polygon.topRight, step);
    const verticalEnd = interpolatePoint(polygon.bottomLeft, polygon.bottomRight, step);

    return [
      { x1: horizontalStart.x, y1: horizontalStart.y, x2: horizontalEnd.x, y2: horizontalEnd.y },
      { x1: verticalStart.x, y1: verticalStart.y, x2: verticalEnd.x, y2: verticalEnd.y },
    ];
  });

const getBounds = (polygon: Rectangle) => {
  const minX = Math.min(
    polygon.topLeft.x,
    polygon.bottomLeft.x,
    polygon.topRight.x,
    polygon.bottomRight.x,
  );
  const maxX = Math.max(
    polygon.topLeft.x,
    polygon.bottomLeft.x,
    polygon.topRight.x,
    polygon.bottomRight.x,
  );
  const minY = Math.min(
    polygon.topLeft.y,
    polygon.topRight.y,
    polygon.bottomLeft.y,
    polygon.bottomRight.y,
  );
  const maxY = Math.max(
    polygon.topLeft.y,
    polygon.topRight.y,
    polygon.bottomLeft.y,
    polygon.bottomRight.y,
  );

  return {
    minX,
    minY,
    width: maxX - minX,
    height: maxY - minY,
  };
};

export interface ScannerOverlayProps {
  active: boolean;
  color?: string;
  lineWidth?: number;
  polygon?: Rectangle | null;
  clipRect?: { left: number; top: number; width: number; height: number } | null;
}

export const ScannerOverlay: React.FC<ScannerOverlayProps> = ({
  active: _active, // kept for compatibility; no animation currently
  color = '#0b7ef4',
  lineWidth = StyleSheet.hairlineWidth,
  polygon,
  clipRect,
}) => {
  const offset = useMemo(
    () => (clipRect ? { x: -clipRect.left, y: -clipRect.top } : { x: 0, y: 0 }),
    [clipRect],
  );
  const shiftedPolygon = useMemo(() => {
    if (!polygon) return null;
    return {
      topLeft: { x: polygon.topLeft.x + offset.x, y: polygon.topLeft.y + offset.y },
      topRight: { x: polygon.topRight.x + offset.x, y: polygon.topRight.y + offset.y },
      bottomRight: { x: polygon.bottomRight.x + offset.x, y: polygon.bottomRight.y + offset.y },
      bottomLeft: { x: polygon.bottomLeft.x + offset.x, y: polygon.bottomLeft.y + offset.y },
    };
  }, [polygon, offset]);
  const points = useMemo(() => (shiftedPolygon ? createPointsString(shiftedPolygon) : null), [shiftedPolygon]);
  const gridLines = useMemo(() => (shiftedPolygon ? createGridLines(shiftedPolygon) : []), [shiftedPolygon]);
  const bounds = useMemo(() => (shiftedPolygon ? getBounds(shiftedPolygon) : null), [shiftedPolygon]);

  if (!shiftedPolygon || !points || !bounds) {
    return null;
  }

  const containerStyle: StyleProp<ViewStyle> = clipRect
    ? [
        StyleSheet.absoluteFill as ViewStyle,
        {
          left: clipRect.left,
          top: clipRect.top,
          width: clipRect.width,
          height: clipRect.height,
          overflow: 'hidden',
        },
      ]
    : (StyleSheet.absoluteFill as ViewStyle);

  if (SvgModule) {
    const { default: Svg, Polygon, Line } = SvgModule;

    return (
      <View pointerEvents="none" style={containerStyle}>
        <Svg style={StyleSheet.absoluteFill}>
          <Polygon points={points} fill={color} opacity={0.15} />
          {gridLines.map((line, index) => (
            <Line
              key={`grid-${index}`}
              x1={line.x1}
              y1={line.y1}
              x2={line.x2}
              y2={line.y2}
              stroke={color}
              strokeWidth={lineWidth}
              opacity={0.5}
            />
          ))}
          <Polygon points={points} stroke={color} strokeWidth={lineWidth} fill="none" />
        </Svg>
      </View>
    );
  }

  return (
    <View pointerEvents="none" style={containerStyle}>
      <View
        style={[
          styles.fallbackBox,
          {
            left: bounds.minX,
            top: bounds.minY,
            width: bounds.width,
            height: bounds.height,
            borderColor: color,
            borderWidth: lineWidth,
          },
        ]}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  fallbackBox: {
    position: 'absolute',
    backgroundColor: 'rgba(11, 126, 244, 0.1)',
  },
});
