import React, { useMemo } from 'react';
import { View, StyleSheet, useWindowDimensions } from 'react-native';
import { Canvas, Path, Skia } from '@shopify/react-native-skia';
import type { Point } from '../types';

type OverlayProps = {
  quad: Point[] | null;
  color?: string;
};

export const Overlay: React.FC<OverlayProps> = ({ quad, color = '#e7a649' }) => {
  const { width: screenWidth, height: screenHeight } = useWindowDimensions();

  const path = useMemo(() => {
    if (!quad) {
      if (__DEV__) {
        console.log('[Overlay] no quad');
      }
      return null;
    }

    if (__DEV__) {
      console.log('[Overlay] drawing quad:', quad);
      console.log('[Overlay] color:', color);
      console.log('[Overlay] screen dimensions:', screenWidth, 'x', screenHeight);
    }

    const skPath = Skia.Path.Make();
    skPath.moveTo(quad[0].x, quad[0].y);
    quad.slice(1).forEach((p) => skPath.lineTo(p.x, p.y));
    skPath.close();
    return skPath;
  }, [quad, color, screenWidth, screenHeight]);

  // Test path - always visible for debugging
  const testPath = useMemo(() => {
    const tp = Skia.Path.Make();
    tp.moveTo(100, 100);
    tp.lineTo(300, 100);
    tp.lineTo(300, 300);
    tp.lineTo(100, 300);
    tp.close();
    return tp;
  }, []);

  if (__DEV__) {
    console.log('[Overlay] rendering Canvas with dimensions:', screenWidth, 'x', screenHeight);
  }

  return (
    <View style={styles.container} pointerEvents="none">
      <Canvas style={{ width: screenWidth, height: screenHeight }}>
        {/* Debug: always show a test rectangle */}
        <Path path={testPath} color="red" style="stroke" strokeWidth={4} />

        {/* Actual quad overlay */}
        {path && (
          <>
            <Path path={path} color={color} style="stroke" strokeWidth={8} />
            <Path path={path} color="rgba(231, 166, 73, 0.2)" style="fill" />
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
