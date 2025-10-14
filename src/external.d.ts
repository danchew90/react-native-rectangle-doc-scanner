declare module 'react-native-vision-camera' {
  import type { ComponentType } from 'react';
  import type { ViewStyle } from 'react-native';

  export type CameraDevice = {
    id: string;
    name: string;
  } | null;

  export type Frame = {
    width: number;
    height: number;
  };

  export type TakePhotoOptions = {
    qualityPrioritization?: 'balanced' | 'quality' | 'speed';
  };

  export type CameraRef = {
    takePhoto: (options?: TakePhotoOptions) => Promise<{
      path: string;
    }>;
  };

  export type CameraProps = {
    ref?: (value: CameraRef | null) => void;
    style?: ViewStyle;
    device: CameraDevice;
    isActive?: boolean;
    photo?: boolean;
    frameProcessor?: (frame: Frame) => void;
    frameProcessorFps?: number;
  };

  export const Camera: ComponentType<CameraProps>;
  export function useCameraDevice(position?: 'back' | 'front'): CameraDevice;
  export function useCameraPermission(): {
    hasPermission: boolean;
    requestPermission: () => Promise<void>;
  };
  export function useFrameProcessor(
    processor: (frame: Frame) => void,
    deps?: ReadonlyArray<unknown>
  ): (frame: Frame) => void;
}

declare module 'react-native-reanimated' {
  export function runOnJS<T extends (...args: any[]) => any>(fn: T): T;
}

declare module 'vision-camera-resize-plugin' {
  import type { Frame } from 'react-native-vision-camera';

  type ResizeOptions = {
    dataType: 'uint8';
    pixelFormat: 'bgr';
    scale: {
      width: number;
      height: number;
    };
  };

  export function useResizePlugin(): {
    resize: (frame: Frame, options: ResizeOptions) => ArrayBuffer;
  };
}

declare module 'react-native-fast-opencv' {
  export const OpenCV: any;
  export const ColorConversionCodes: any;
  export const MorphTypes: any;
  export const MorphShapes: any;
  export const RetrievalModes: any;
  export const ContourApproximationModes: any;
  export const ObjectType: any;
}

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
}
