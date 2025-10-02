# React Native Rectangle Doc Scanner

VisionCamera + Fast-OpenCV powered document scanner template built for React Native. You can install it as a reusable module, extend the detection pipeline, and publish to npm out of the box.

## Features
- Real-time quad detection using `react-native-fast-opencv`
- Frame processor worklet executed on the UI thread via `react-native-vision-camera`
- Resize plugin to keep frame processing fast on lower-end devices
- Skia overlay for visualizing detected document contours
- Stability tracker for auto-capture once the document is steady

## Installation
Install the template as a package and make sure the peer dependencies already exist in your app:

```sh
yarn add react-native-rectangle-doc-scanner
```

## Usage
```tsx
import React from 'react';
import { View } from 'react-native';
import { DocScanner } from 'react-native-rectangle-doc-scanner';

export const ScanScreen = () => (
  <View style={{ flex: 1 }}>
    <DocScanner
      onCapture={({ path, quad }) => {
        console.log('Document captured at', path, quad);
      }}
      overlayColor="#ff8800"
      autoCapture
      minStableFrames={8}
    />
  </View>
);
```

## Build
```sh
yarn build
```
Generates the `dist/` output via TypeScript.

## License
MIT
