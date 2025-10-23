declare module 'react-native-fast-opencv' {
  export enum RotateFlags {
    ROTATE_90_CLOCKWISE = 0,
    ROTATE_180 = 1,
    ROTATE_90_COUNTERCLOCKWISE = 2
  }

  export class Mat {
    delete(): void;
  }

  export interface OpenCVModel {
    Mat: typeof Mat;
    invoke(name: 'imread', path: string): Promise<Mat>;
    invoke(name: 'imwrite', path: string, mat: Mat): Promise<void>;
    invoke(name: 'rotate', src: Mat, dst: Mat, code: RotateFlags): void;
  }

  export const OpenCV: OpenCVModel;
}
