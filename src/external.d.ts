declare module '@shopify/react-native-skia' {
  import type { ComponentType, ReactNode } from 'react';
  import type { ViewStyle } from 'react-native';

  export type SkPath = {
    moveTo: (x: number, y: number) => void;
    lineTo: (x: number, y: number) => void;
    close: () => void;
  };

  export const Skia: {
    Path: {
      Make: () => SkPath;
    };
  };

  export type CanvasProps = {
    style?: ViewStyle;
    children?: ReactNode;
  };

  export const Canvas: ComponentType<CanvasProps>;

  export type PathProps = {
    path: SkPath;
    style?: 'stroke' | 'fill';
    strokeWidth?: number;
    color?: string;
  };

  export const Path: ComponentType<PathProps>;
}

declare module 'react-native-perspective-image-cropper' {
  import type { ComponentType } from 'react';

  export type Rectangle = {
    topLeft: { x: number; y: number };
    topRight: { x: number; y: number };
    bottomLeft: { x: number; y: number };
    bottomRight: { x: number; y: number };
  };

  export type CustomImageCropperProps = {
    height: number;
    width: number;
    image: string;
    rectangleCoordinates?: Rectangle;
    overlayColor?: string;
    overlayStrokeColor?: string;
    handlerColor?: string;
    enablePanStrict?: boolean;
    onDragEnd?: (coordinates: Rectangle) => void;
  };

  export const CustomImageCropper: ComponentType<CustomImageCropperProps>;
  const CustomImageCropperDefault: ComponentType<CustomImageCropperProps>;
  export default CustomImageCropperDefault;
}

declare module 'react-native-document-scanner' {
  import type { Component } from 'react';
  import type { ViewStyle } from 'react-native';

  export type DocumentScannerResult = {
    croppedImage?: string | null;
    initialImage?: string | null;
    width?: number;
    height?: number;
  };

  export interface DocumentScannerProps {
    style?: ViewStyle;
    detectionCountBeforeCapture?: number;
    overlayColor?: string;
    enableTorch?: boolean;
    useBase64?: boolean;
    quality?: number;
    manualOnly?: boolean;
    onPictureTaken?: (event: DocumentScannerResult) => void;
    onError?: (error: Error) => void;
  }

  export default class DocumentScanner extends Component<DocumentScannerProps> {
    capture(): Promise<DocumentScannerResult>;
  }
}
