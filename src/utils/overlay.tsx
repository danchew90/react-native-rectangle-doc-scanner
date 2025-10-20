import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Animated, Easing, StyleSheet, View } from 'react-native';
import type { Rectangle } from '../types';

let SvgModule: typeof import('react-native-svg') | null = null;

try {
  // eslint-disable-next-line global-require, @typescript-eslint/no-var-requires
  SvgModule = require('react-native-svg');
} catch (error) {
  SvgModule = null;
}

const SCAN_DURATION_MS = 2200;
const GRID_STEPS = [1 / 3, 2 / 3];

type PolygonMetrics = {
  minX: number;
  maxX: number;
  minY: number;
  maxY: number;
  width: number;
  height: number;
};

const calculateMetrics = (polygon: Rectangle): PolygonMetrics => {
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
    maxX,
    minY,
    maxY,
    width: maxX - minX,
    height: maxY - minY,
  };
};

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

export interface ScannerOverlayProps {
  active: boolean;
  color?: string;
  lineWidth?: number;
  polygon?: Rectangle | null;
}

export const ScannerOverlay: React.FC<ScannerOverlayProps> = ({
  active,
  color = '#0b7ef4',
  lineWidth = StyleSheet.hairlineWidth,
  polygon,
}) => {
  const scanProgress = useRef(new Animated.Value(0)).current;
  const fallbackBase = useRef(new Animated.Value(0)).current;
  const [scanY, setScanY] = useState<number | null>(null);

  const metrics = useMemo(() => (polygon ? calculateMetrics(polygon) : null), [polygon]);

  const scanBarHeight = useMemo(() => {
    if (!metrics) return 0;
    return Math.max(metrics.height * 0.2, 16);
  }, [metrics]);

  const travelDistance = useMemo(() => {
    if (!metrics) {
      return 0;
    }
    return Math.max(metrics.height - scanBarHeight, 0);
  }, [metrics, scanBarHeight]);

  useEffect(() => {
    scanProgress.stopAnimation();
    scanProgress.setValue(0);
    setScanY(null);

    if (!active || !metrics || travelDistance <= 0) {
      return undefined;
    }

    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(scanProgress, {
          toValue: 1,
          duration: SCAN_DURATION_MS,
          easing: Easing.inOut(Easing.quad),
          useNativeDriver: false,
        }),
        Animated.timing(scanProgress, {
          toValue: 0,
          duration: SCAN_DURATION_MS,
          easing: Easing.inOut(Easing.quad),
          useNativeDriver: false,
        }),
      ]),
    );

    loop.start();
    return () => {
      loop.stop();
      scanProgress.stopAnimation();
    };
  }, [active, metrics, scanProgress, travelDistance]);

  useEffect(() => {
    if (!metrics || travelDistance <= 0) {
      setScanY(null);
      return undefined;
    }

    const listenerId = scanProgress.addListener(({ value }) => {
      const nextValue = metrics.minY + travelDistance * value;
      if (Number.isFinite(nextValue)) {
        setScanY(nextValue);
      }
    });

    return () => {
      scanProgress.removeListener(listenerId);
    };
  }, [metrics, scanProgress, travelDistance]);

  if (!polygon || !metrics || metrics.width <= 0 || metrics.height <= 0) {
    return null;
  }

  if (SvgModule) {
    const { default: Svg, Polygon, Line, Defs, LinearGradient, Stop, Rect } = SvgModule;
    const gridLines = createGridLines(polygon);
    const points = createPointsString(polygon);
    const scanRectY = scanY ?? metrics.minY;

    return (
      <View pointerEvents="none" style={StyleSheet.absoluteFill}>
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
          <Defs>
            <LinearGradient id="scanGradient" x1="0" y1="0" x2="0" y2="1">
              <Stop offset="0%" stopColor="rgba(255,255,255,0)" />
              <Stop offset="50%" stopColor={color} stopOpacity={0.8} />
              <Stop offset="100%" stopColor="rgba(255,255,255,0)" />
            </LinearGradient>
          </Defs>
          {active && travelDistance > 0 && Number.isFinite(scanRectY) && (
            <Rect
              x={metrics.minX}
              width={metrics.width}
              height={scanBarHeight}
              fill="url(#scanGradient)"
              y={scanRectY}
            />
          )}
        </Svg>
      </View>
    );
  }

  const relativeTranslate =
    metrics && travelDistance > 0
      ? Animated.multiply(scanProgress, travelDistance)
      : fallbackBase;

  return (
    <View pointerEvents="none" style={StyleSheet.absoluteFill}>
      <View
        style={[
          styles.fallbackBox,
          {
            left: metrics.minX,
            top: metrics.minY,
            width: metrics.width,
            height: metrics.height,
            borderColor: color,
            borderWidth: lineWidth,
          },
        ]}
      >
        {active && travelDistance > 0 && (
          <Animated.View
            style={[
              styles.fallbackScanBar,
              {
                backgroundColor: color,
                height: scanBarHeight,
                transform: [{ translateY: relativeTranslate }],
              },
            ]}
          />
        )}
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  fallbackBox: {
    position: 'absolute',
    backgroundColor: 'rgba(11, 126, 244, 0.1)',
    overflow: 'hidden',
  },
  fallbackScanBar: {
    width: '100%',
    opacity: 0.4,
  },
});
