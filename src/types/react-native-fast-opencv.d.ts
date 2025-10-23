declare module 'react-native-fast-opencv' {
  export class Cv2 {
    static rotate(imagePath: string, rotateCode: number): Promise<string>;
  }
}
