import type { Point } from '../types';

let last: Point[] | null = null;
let stable = 0;

export function checkStability(current: Point[] | null): number {
  if (!current) {
    stable = 0;
    last = null;
    return 0;
  }

  if (!last) {
    last = current;
    stable = 1;
    return stable;
  }

  const diff = Math.hypot(
    avg(current.map((p) => p.x)) - avg(last.map((p) => p.x)),
    avg(current.map((p) => p.y)) - avg(last.map((p) => p.y))
  );

  if (diff < 10) {
    stable++;
  } else {
    stable = 0;
  }

  last = current;
  return stable;
}

const avg = (arr: number[]) => arr.reduce((a, b) => a + b, 0) / arr.length;
