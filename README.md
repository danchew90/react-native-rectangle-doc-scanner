# React Native Rectangle Doc Scanner

VisionCamera + Fast-OpenCV powered document scanner template built for React Native. You can install it as a reusable module, extend the detection pipeline, and publish to npm out of the box.

## Features
- Real-time quad detection using `react-native-fast-opencv`
- Frame processor worklet executed on the UI thread via `react-native-vision-camera`
- High-resolution processing (1280p) for accurate corner detection
- Advanced anchor locking system maintains corner positions during camera movement
- Intelligent edge detection with optimized Canny parameters (50/150 thresholds)
- Adaptive smoothing with weighted averaging across multiple frames
- Resize plugin keeps frame processing fast on lower-end devices
- Skia overlay visualises detected document contours
- Stability tracker enables auto-capture once the document is steady

## Requirements
Install the module alongside these peer dependencies (your host app should already include them or install them now):

- `react-native-vision-camera` (v3+) with frame processors enabled
- `vision-camera-resize-plugin`
- `react-native-fast-opencv`
- `react-native-reanimated` + `react-native-worklets-core`
- `@shopify/react-native-skia`
- `react`, `react-native`

## Installation

```sh
yarn add react-native-rectangle-doc-scanner \
  react-native-vision-camera \
  vision-camera-resize-plugin \
  react-native-fast-opencv \
  react-native-reanimated \
  react-native-worklets-core \
  @shopify/react-native-skia
```

Follow each dependency’s native installation guide:

- Run `npx pod-install` after adding iOS dependencies.
- For `react-native-reanimated`, add the Babel plugin, enable the JSI runtime, and ensure Reanimated is the first import in `index.js`.
- Configure `react-native-fast-opencv` according to its README (adds native OpenCV binaries on both platforms).
- For `react-native-vision-camera`, enable frame processors by adding the new architecture build and the proxy registration they describe. You must also request camera permissions at runtime.
- Register the resize plugin once in native code (for example inside your `VisionCameraProxy` setup):

  ```ts
  import { VisionCameraProxy } from 'react-native-vision-camera';
  import { ResizePlugin } from 'vision-camera-resize-plugin';

  VisionCameraProxy.installFrameProcessorPlugin('resize', ResizePlugin);
  ```

## Usage

```tsx
import React from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { DocScanner } from 'react-native-rectangle-doc-scanner';

export const ScanScreen = () => (
  <View style={styles.container}>
    <DocScanner
      onCapture={({ path, quad }) => {
        console.log('Document captured at', path, quad);
      }}
      overlayColor="#ff8800"
      autoCapture
      minStableFrames={8}
      cameraProps={{ enableZoomGesture: true }}
    >
      <View style={styles.overlayControls}>
        <TouchableOpacity style={styles.button}>
          <Text style={styles.label}>Manual Capture</Text>
        </TouchableOpacity>
      </View>
    </DocScanner>
  </View>
);

const styles = StyleSheet.create({
  container: { flex: 1 },
  overlayControls: {
    position: 'absolute',
    bottom: 32,
    alignSelf: 'center',
  },
  button: {
    paddingHorizontal: 24,
    paddingVertical: 12,
    borderRadius: 999,
    backgroundColor: 'rgba(0,0,0,0.7)',
  },
  label: { color: '#fff', fontWeight: '600' },
});
```

Passing `children` lets you render any UI on top of the camera preview, so you can freely add buttons, tutorials, or progress indicators without modifying the package.

### Props

- `onCapture({ path, quad })` — called when a photo is taken; `quad` contains the detected corner coordinates (or `null` if none were found).
- `overlayColor` (default `#e7a649`) — stroke colour for the contour overlay.
- `autoCapture` (default `true`) — when `true`, captures automatically after stability is reached; set to `false` to show the built-in shutter button.
- `minStableFrames` (default `8`) — number of consecutive stable frames required before auto capture triggers.
- `cameraProps` — forwarded to the underlying `Camera` (except for `frameProcessor`), enabling features such as zoom gestures, HDR, torch control, device selection, etc.
- `children` — rendered over the camera/overlay for fully custom controls.

### Notes on camera behaviour

- If you disable `autoCapture`, the built-in shutter button appears; you can still provide your own UI as `children` to replace or augment it.
- The internal frame processor handles document detection; do not override `frameProcessor` in `cameraProps`.
- Adjust `minStableFrames` or tweak lighting conditions if auto capture is too sensitive or too slow.

## Detection Algorithm

The scanner uses a sophisticated multi-stage pipeline:

1. **Pre-processing** (1280p resolution for accuracy)
   - Converts frame to grayscale
   - Applies morphological opening to reduce noise
   - Gaussian blur for smoother edges
   - Canny edge detection with 50/150 thresholds

2. **Contour Detection**
   - Finds external contours using CHAIN_APPROX_SIMPLE
   - Applies convex hull for better corner detection
   - Tests multiple epsilon values (0.1%-10%) for approxPolyDP
   - Validates quadrilaterals for convexity

3. **Anchor Locking System**
   - Once corners are detected, they "snap" to stable positions
   - Maintains lock even when camera moves (up to 200px center delta)
   - Holds anchor for up to 20 missed detections
   - Adaptive blending between old and new positions for smooth transitions
   - Builds confidence over time (max 30 frames) for stronger locking

4. **Quad Validation**
   - Checks area ratio (0.02%-90% of frame)
   - Validates minimum edge lengths
   - Ensures reasonable aspect ratios
   - Filters out non-convex shapes

This approach ensures corners remain locked once detected, allowing you to move the camera slightly without losing the document boundary.

## Build
```sh
yarn build
```
Generates the `dist/` output via TypeScript.

## License
MIT
