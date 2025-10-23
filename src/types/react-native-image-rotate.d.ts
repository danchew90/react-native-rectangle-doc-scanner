declare module 'react-native-image-rotate' {
  export default class ImageRotate {
    static rotateImage(
      imagePath: string,
      degrees: number,
      successCallback: (rotatedImagePath: string) => void,
      errorCallback: (error: Error) => void,
    ): void;
  }
}
