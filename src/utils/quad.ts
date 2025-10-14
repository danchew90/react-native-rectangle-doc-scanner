import type { Point } from '../types';

const POINT_EPSILON = 1e-3;

const isFiniteNumber = (value: number): boolean => Number.isFinite(value) && !Number.isNaN(value);

export const isValidPoint = (point: Point | null | undefined): point is Point => {
  if (!point) {
    return false;
  }

  if (!isFiniteNumber(point.x) || !isFiniteNumber(point.y)) {
    return false;
  }

  if (Math.abs(point.x) > 1_000_000 || Math.abs(point.y) > 1_000_000) {
    return false;
  }

  return true;
};

export const isValidQuad = (quad: Point[] | null | undefined): quad is Point[] => {
  return Array.isArray(quad) && quad.length === 4 && quad.every(isValidPoint);
};

const clonePoint = (point: Point): Point => ({ x: point.x, y: point.y });

export const orderQuadPoints = (quad: Point[]): Point[] => {
  const centroid = quad.reduce(
    (acc, point) => ({ x: acc.x + point.x / quad.length, y: acc.y + point.y / quad.length }),
    { x: 0, y: 0 },
  );

  const sorted = quad
    .slice()
    .map(clonePoint)
    .sort((a, b) => {
      const angleA = Math.atan2(a.y - centroid.y, a.x - centroid.x);
      const angleB = Math.atan2(b.y - centroid.y, b.x - centroid.x);
      return angleA - angleB;
    });

  const topLeftIndex = sorted.reduce((selectedIndex, point, index) => {
    const currentScore = point.x + point.y;
    const selectedPoint = sorted[selectedIndex];
    const selectedScore = selectedPoint.x + selectedPoint.y;
    if (currentScore < selectedScore - POINT_EPSILON) {
      return index;
    }
    return selectedIndex;
  }, 0);

  return [...sorted.slice(topLeftIndex), ...sorted.slice(0, topLeftIndex)];
};

export const quadDistance = (a: Point[], b: Point[]): number => {
  if (!isValidQuad(a) || !isValidQuad(b)) {
    return Number.POSITIVE_INFINITY;
  }

  let total = 0;
  for (let i = 0; i < 4; i += 1) {
    total += Math.hypot(a[i].x - b[i].x, a[i].y - b[i].y);
  }

  return total / 4;
};

export const averageQuad = (quads: Point[][]): Point[] => {
  if (!Array.isArray(quads) || quads.length === 0) {
    throw new Error('Cannot average empty quad array');
  }

  const accum: Point[] = quads[0].map(() => ({ x: 0, y: 0 }));

  quads.forEach((quad) => {
    quad.forEach((point, index) => {
      accum[index].x += point.x;
      accum[index].y += point.y;
    });
  });

  return accum.map((point) => ({ x: point.x / quads.length, y: point.y / quads.length }));
};

export const blendQuads = (base: Point[], target: Point[], alpha: number): Point[] => {
  if (alpha <= 0) {
    return base.map(clonePoint);
  }

  if (alpha >= 1) {
    return target.map(clonePoint);
  }

  return base.map((point, index) => ({
    x: point.x * (1 - alpha) + target[index].x * alpha,
    y: point.y * (1 - alpha) + target[index].y * alpha,
  }));
};

export const sanitizeQuad = (quad: Point[]): Point[] => {
  if (!isValidQuad(quad)) {
    throw new Error('Cannot sanitise invalid quad');
  }

  return quad.map((point) => ({
    x: Number.isFinite(point.x) ? point.x : 0,
    y: Number.isFinite(point.y) ? point.y : 0,
  }));
};
