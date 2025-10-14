// Main components
export { DocScanner } from './DocScanner';
export { CropEditor } from './CropEditor';

// Types
export type { Point, Quad, Rectangle, CapturedDocument } from './types';
export type { DetectionConfig } from './DocScanner';

// Utilities
export {
  quadToRectangle,
  rectangleToQuad,
  scaleCoordinates,
  scaleRectangle,
} from './utils/coordinate';
