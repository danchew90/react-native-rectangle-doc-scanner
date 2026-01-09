import type { Component } from 'react';
import type { ViewStyle } from 'react-native';

export type RectanglePoint = {
  x: number;
  y: number;
};

export type Rectangle = {
  topLeft: RectanglePoint;
  topRight: RectanglePoint;
  bottomLeft: RectanglePoint;
  bottomRight: RectanglePoint;
};

export type RectangleEventPayload = {
  stableCounter: number;
  lastDetectionType: number;
  rectangleCoordinates?: Rectangle | null;
  rectangleOnScreen?: Rectangle | null;
  previewSize?: { width: number; height: number };
  imageSize?: { width: number; height: number };
};

export type DocumentScannerResult = {
  croppedImage?: string | null;
  initialImage?: string | null;
  width?: number;
  height?: number;
  rectangleCoordinates?: Rectangle | null;
};

export interface DocumentScannerProps {
  style?: ViewStyle;
  detectionCountBeforeCapture?: number;
  overlayColor?: string;
  enableTorch?: boolean;
  useBase64?: boolean;
  quality?: number;
  manualOnly?: boolean;
  detectionConfig?: {
    processingWidth?: number;
    cannyLowThreshold?: number;
    cannyHighThreshold?: number;
    snapDistance?: number;
    maxAnchorMisses?: number;
    maxCenterDelta?: number;
  };
  useExternalScanner?: boolean;
  onPictureTaken?: (event: DocumentScannerResult) => void;
  onError?: (error: Error) => void;
  onRectangleDetect?: (event: RectangleEventPayload) => void;
}

export default class DocumentScanner extends Component<DocumentScannerProps> {
  capture(): Promise<DocumentScannerResult>;
}
