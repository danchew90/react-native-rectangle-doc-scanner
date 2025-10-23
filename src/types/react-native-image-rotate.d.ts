declare module 'react-native-image-rotate' {
  export default class ImageRotate {
    static rotateImage(
      imagePath: string,
      degrees: number,
    ): Promise<string>;
  }
}
