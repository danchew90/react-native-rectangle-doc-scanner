export type Point = { x: number; y: number };

export type Quad = [Point, Point, Point, Point];

export type Rectangle = {
  topLeft: Point;
  topRight: Point;
  bottomRight: Point;
  bottomLeft: Point;
};

export type CapturedDocument = {
  path: string;
  quad: Point[] | null;
  width: number;
  height: number;
};
