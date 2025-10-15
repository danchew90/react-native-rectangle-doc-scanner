import {
  OpenCV,
  ColorConversionCodes,
  MorphShapes,
  MorphTypes,
  RetrievalModes,
  ContourApproximationModes,
} from 'react-native-fast-opencv';
import type { Point } from '../types';

type MatLike = { release?: () => void } | null | undefined;

type Size = { width: number; height: number };

type Quad = [Point, Point, Point, Point];

const OUTPUT_SIZE: Size = { width: 800, height: 600 };
const MIN_AREA = 1000;
const GAUSSIAN_KERNEL: Size = { width: 5, height: 5 };
const MORPH_KERNEL: Size = { width: 3, height: 3 };
const ADAPTIVE_THRESH_GAUSSIAN_C = 1;
const THRESH_BINARY = 0;

const safeRelease = (mat: MatLike) => {
  if (mat && typeof mat.release === 'function') {
    mat.release();
  }
};

const normalizePoint = (value: unknown): Point | null => {
  if (!value) {
    return null;
  }

  if (Array.isArray(value) && value.length >= 2) {
    const [x, y] = value;
    const px = Number(x);
    const py = Number(y);
    return Number.isFinite(px) && Number.isFinite(py) ? { x: px, y: py } : null;
  }

  if (typeof value === 'object') {
    const maybePoint = value as { x?: unknown; y?: unknown };
    const px = Number(maybePoint.x);
    const py = Number(maybePoint.y);
    return Number.isFinite(px) && Number.isFinite(py) ? { x: px, y: py } : null;
  }

  return null;
};

const toPointArray = (value: unknown): Point[] | null => {
  if (!value) {
    return null;
  }

  if (Array.isArray(value)) {
    const points = value.map(normalizePoint).filter((point): point is Point => point !== null);
    return points.length === value.length ? points : null;
  }

  if (typeof value === 'object') {
    const mat = value as { data32F?: number[]; data64F?: number[]; data32S?: number[] };
    const data = mat.data32F ?? mat.data64F ?? mat.data32S;
    if (!data || data.length < 8) {
      return null;
    }

    const points: Point[] = [];
    for (let i = 0; i + 1 < data.length; i += 2) {
      const x = data[i];
      const y = data[i + 1];
      if (Number.isFinite(x) && Number.isFinite(y)) {
        points.push({ x, y });
      }
    }

    return points.length >= 4 ? points.slice(0, 4) : null;
  }

  return null;
};

const ensureQuad = (points: Point[] | null): Quad | null => {
  if (!points || points.length < 4) {
    return null;
  }

  const quad: Quad = [points[0], points[1], points[2], points[3]];
  for (const point of quad) {
    if (typeof point.x !== 'number' || typeof point.y !== 'number') {
      return null;
    }
  }

  return quad;
};

/**
 * Provides document detection utilities using react-native-fast-opencv.
 */
export class DocumentDetector {
  private static initialized = false;

  /** Initialize OpenCV runtime once */
  static async initialize(): Promise<void> {
    if (!DocumentDetector.initialized) {
      await OpenCV.initialize();
      DocumentDetector.initialized = true;
    }
  }

  /** Find document contours and return the largest quadrilateral */
  static async findDocumentContours(imagePath: string): Promise<Quad | null> {
    await DocumentDetector.initialize();

    let image: MatLike;
    let gray: MatLike;
    let blurred: MatLike;
    let thresh: MatLike;
    let morphed: MatLike;
    let kernel: MatLike;

    try {
      image = OpenCV.imread(imagePath);
      gray = OpenCV.cvtColor(image, ColorConversionCodes.COLOR_BGR2GRAY);
      blurred = OpenCV.GaussianBlur(gray, GAUSSIAN_KERNEL, 0);

      thresh = OpenCV.adaptiveThreshold(
        blurred,
        255,
        ADAPTIVE_THRESH_GAUSSIAN_C,
        THRESH_BINARY,
        11,
        2,
      );

      kernel = OpenCV.getStructuringElement(MorphShapes.MORPH_RECT, MORPH_KERNEL);
      morphed = OpenCV.morphologyEx(thresh, MorphTypes.MORPH_CLOSE, kernel);

      const contours = OpenCV.findContours(
        morphed,
        RetrievalModes.RETR_EXTERNAL,
        ContourApproximationModes.CHAIN_APPROX_SIMPLE,
      );

      let largestQuad: Quad | null = null;
      let maxArea = 0;

      for (const contour of contours) {
        const area = OpenCV.contourArea(contour);
        if (area <= maxArea || area <= MIN_AREA) {
          continue;
        }

        const perimeter = OpenCV.arcLength(contour, true);
        const approx = OpenCV.approxPolyDP(contour, 0.02 * perimeter, true);
        const points = ensureQuad(toPointArray(approx));

        safeRelease(approx as MatLike);

        if (!points) {
          continue;
        }

        maxArea = area;
        largestQuad = points;
      }

      return largestQuad;
    } catch (error) {
      if (__DEV__) {
        console.error('[DocumentDetector] findDocumentContours error', error);
      }
      return null;
    } finally {
      safeRelease(kernel);
      safeRelease(morphed);
      safeRelease(thresh);
      safeRelease(blurred);
      safeRelease(gray);
      safeRelease(image);
    }
  }

  /** Apply a perspective transform using detected corners */
  static async perspectiveTransform(
    imagePath: string,
    corners: Quad,
    outputSize: Size = OUTPUT_SIZE,
  ): Promise<string | null> {
    await DocumentDetector.initialize();

    let image: MatLike;
    let srcPoints: MatLike;
    let dstPoints: MatLike;
    let transformMatrix: MatLike;
    let warped: MatLike;

    try {
      image = OpenCV.imread(imagePath);

      srcPoints = OpenCV.matFromArray(4, 1, OpenCV.CV_32FC2, [
        corners[0].x,
        corners[0].y,
        corners[1].x,
        corners[1].y,
        corners[2].x,
        corners[2].y,
        corners[3].x,
        corners[3].y,
      ]);

      dstPoints = OpenCV.matFromArray(4, 1, OpenCV.CV_32FC2, [
        0,
        0,
        outputSize.width,
        0,
        outputSize.width,
        outputSize.height,
        0,
        outputSize.height,
      ]);

      transformMatrix = OpenCV.getPerspectiveTransform(srcPoints, dstPoints);

      warped = OpenCV.warpPerspective(image, transformMatrix, outputSize);

      const outputPath = imagePath.replace(/\.jpg$/i, '_normalized.jpg');
      OpenCV.imwrite(outputPath, warped);

      return outputPath;
    } catch (error) {
      if (__DEV__) {
        console.error('[DocumentDetector] perspectiveTransform error', error);
      }
      return null;
    } finally {
      safeRelease(warped);
      safeRelease(transformMatrix);
      safeRelease(dstPoints);
      safeRelease(srcPoints);
      safeRelease(image);
    }
  }

  /** Detect document and apply normalization */
  static async detectAndNormalize(imagePath: string, outputSize?: Size): Promise<string | null> {
    try {
      const corners = await DocumentDetector.findDocumentContours(imagePath);
      if (!corners) {
        if (__DEV__) {
          console.log('[DocumentDetector] No document detected');
        }
        return null;
      }

      return DocumentDetector.perspectiveTransform(imagePath, corners, outputSize ?? OUTPUT_SIZE);
    } catch (error) {
      if (__DEV__) {
        console.error('[DocumentDetector] detectAndNormalize error', error);
      }
      return null;
    }
  }

  /** Only detect document corners without transforming */
  static async getDocumentBounds(imagePath: string): Promise<Quad | null> {
    try {
      return DocumentDetector.findDocumentContours(imagePath);
    } catch (error) {
      if (__DEV__) {
        console.error('[DocumentDetector] getDocumentBounds error', error);
      }
      return null;
    }
  }
}
