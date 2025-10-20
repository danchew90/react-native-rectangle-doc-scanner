// Main components
export { DocScanner } from './DocScanner';
export { CropEditor } from './CropEditor';
export { FullDocScanner } from './FullDocScanner';

export type {
  FullDocScannerResult,
  FullDocScannerProps,
  FullDocScannerStrings,
} from './FullDocScanner';

// Types
export type { Point, Quad, Rectangle, CapturedDocument } from './types';
export type {
  DetectionConfig,
  DocScannerHandle,
  DocScannerCapture,
  RectangleDetectEvent,
} from './DocScanner';

// Utilities
export {
  quadToRectangle,
  rectangleToQuad,
  scaleCoordinates,
  scaleRectangle,
  createFullImageRectangle,
} from './utils/coordinate';
