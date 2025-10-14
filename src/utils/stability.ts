import type { Point } from '../types';
import { isValidQuad, quadDistance } from './quad';

let last: Point[] | null = null;
let stable = 0;

const STABILITY_DISTANCE = 8;

export function checkStability(current: Point[] | null): number {
  if (!isValidQuad(current)) {
    stable = 0;
    last = null;
    return 0;
  }

  if (!last) {
    last = current;
    stable = 1;
    return stable;
  }

  const diff = quadDistance(current, last);

  if (diff < STABILITY_DISTANCE) {
    stable++;
  } else {
    stable = 0;
  }

  last = current;
  return stable;
}
