import type { Point, Rectangle } from '../types';

/**
 * Convert quad points array to Rectangle format for perspective cropper
 * Assumes quad points are ordered: [topLeft, topRight, bottomRight, bottomLeft]
 */
export const quadToRectangle = (quad: Point[]): Rectangle | null => {
  if (!quad || quad.length !== 4) {
    return null;
  }

  return {
    topLeft: quad[0],
    topRight: quad[1],
    bottomRight: quad[2],
    bottomLeft: quad[3],
  };
};

/**
 * Convert Rectangle format back to quad points array
 */
export const rectangleToQuad = (rect: Rectangle): Point[] => {
  return [
    rect.topLeft,
    rect.topRight,
    rect.bottomRight,
    rect.bottomLeft,
  ];
};

/**
 * Scale coordinates from one dimension to another
 * Useful when image dimensions differ from display dimensions
 */
export const scaleCoordinates = (
  points: Point[],
  fromWidth: number,
  fromHeight: number,
  toWidth: number,
  toHeight: number
): Point[] => {
  const scaleX = toWidth / fromWidth;
  const scaleY = toHeight / fromHeight;

  return points.map(p => ({
    x: p.x * scaleX,
    y: p.y * scaleY,
  }));
};

/**
 * Scale a rectangle
 */
export const scaleRectangle = (
  rect: Rectangle,
  fromWidth: number,
  fromHeight: number,
  toWidth: number,
  toHeight: number
): Rectangle => {
  const scaleX = toWidth / fromWidth;
  const scaleY = toHeight / fromHeight;

  return {
    topLeft: { x: rect.topLeft.x * scaleX, y: rect.topLeft.y * scaleY },
    topRight: { x: rect.topRight.x * scaleX, y: rect.topRight.y * scaleY },
    bottomRight: { x: rect.bottomRight.x * scaleX, y: rect.bottomRight.y * scaleY },
    bottomLeft: { x: rect.bottomLeft.x * scaleX, y: rect.bottomLeft.y * scaleY },
  };
};
