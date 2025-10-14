import React, { useMemo } from 'react';
import { Canvas, Path, Skia } from '@shopify/react-native-skia';
import type { Point } from '../types';

type OverlayProps = {
  quad: Point[] | null;
  color?: string;
};

export const Overlay: React.FC<OverlayProps> = ({ quad, color = '#e7a649' }) => {
  const path = useMemo(() => {
    if (!quad) {
      if (__DEV__) {
        console.log('[Overlay] no quad');
      }
      return null;
    }

    if (__DEV__) {
      console.log('[Overlay] drawing quad:', quad);
    }

    const skPath = Skia.Path.Make();
    skPath.moveTo(quad[0].x, quad[0].y);
    quad.slice(1).forEach((p) => skPath.lineTo(p.x, p.y));
    skPath.close();
    return skPath;
  }, [quad]);

  return (
    <Canvas style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}>
      {path && <Path path={path} color={color} style="stroke" strokeWidth={4} />}
    </Canvas>
  );
};
