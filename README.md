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
- `react-native-perspective-image-cropper`
- `react-native-reanimated` + `react-native-worklets-core`
- `@shopify/react-native-skia`
- `react`, `react-native`

## Installation

```sh
yarn add react-native-rectangle-doc-scanner \
  react-native-vision-camera \
  vision-camera-resize-plugin \
  react-native-fast-opencv \
  react-native-perspective-image-cropper \
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

### Basic Document Scanning

```tsx
import React, { useState } from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { DocScanner, CropEditor, type CapturedDocument } from 'react-native-rectangle-doc-scanner';

export const ScanScreen = () => {
  const [capturedDoc, setCapturedDoc] = useState<CapturedDocument | null>(null);

  if (capturedDoc) {
    // Show crop editor after capture
    return (
      <CropEditor
        document={capturedDoc}
        overlayColor="rgba(0,0,0,0.5)"
        overlayStrokeColor="#e7a649"
        handlerColor="#e7a649"
        onCropChange={(rectangle) => {
          console.log('User adjusted corners:', rectangle);
          // Process the adjusted corners
        }}
      />
    );
  }

  return (
    <View style={styles.container}>
      <DocScanner
        onCapture={(doc) => {
          console.log('Document captured:', doc);
          setCapturedDoc(doc);
        }}
        overlayColor="#e7a649"
        autoCapture
        minStableFrames={8}
        cameraProps={{ enableZoomGesture: true }}
      >
        <View style={styles.overlayControls}>
          <Text style={styles.hint}>Position document in frame</Text>
        </View>
      </DocScanner>
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1 },
  overlayControls: {
    position: 'absolute',
    top: 60,
    alignSelf: 'center',
  },
  hint: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
    textShadowColor: 'rgba(0,0,0,0.75)',
    textShadowOffset: { width: 0, height: 1 },
    textShadowRadius: 3,
  },
});
```

### Advanced Configuration

```tsx
import { DocScanner, type DetectionConfig } from 'react-native-rectangle-doc-scanner';

const detectionConfig: DetectionConfig = {
  processingWidth: 1280,      // Higher = more accurate but slower
  cannyLowThreshold: 40,      // Lower = detect more edges
  cannyHighThreshold: 120,    // Edge strength threshold
  snapDistance: 8,            // Corner lock sensitivity
  maxAnchorMisses: 20,        // Frames to hold anchor when detection fails
  maxCenterDelta: 200,        // Max camera movement while maintaining lock
};

<DocScanner
  detectionConfig={detectionConfig}
  onCapture={(doc) => {
    // doc includes: path, quad, width, height
    console.log('Captured with size:', doc.width, 'x', doc.height);
  }}
/>
```

Passing `children` lets you render any UI on top of the camera preview, so you can freely add buttons, tutorials, or progress indicators without modifying the package.

## API Reference

### DocScanner Props

- `onCapture({ path, quad, width, height })` — called when a photo is taken
  - `path`: file path to the captured image
  - `quad`: detected corner coordinates (or `null` if none found)
  - `width`, `height`: original frame dimensions for coordinate scaling
- `overlayColor` (default `#e7a649`) — stroke color for the contour overlay
- `autoCapture` (default `true`) — auto-captures after stability is reached
- `minStableFrames` (default `8`) — consecutive stable frames required before auto capture
- `cameraProps` — forwarded to underlying `Camera` (zoom, HDR, torch, etc.)
- `children` — custom UI rendered over the camera preview
- `detectionConfig` — advanced detection configuration (see below)

### DetectionConfig

Fine-tune the detection algorithm for your specific use case:

```typescript
interface DetectionConfig {
  processingWidth?: number;      // Default: 1280 (higher = more accurate but slower)
  cannyLowThreshold?: number;    // Default: 40 (lower = detect more edges)
  cannyHighThreshold?: number;   // Default: 120 (edge strength threshold)
  snapDistance?: number;         // Default: 8 (corner lock sensitivity in pixels)
  maxAnchorMisses?: number;      // Default: 20 (frames to hold anchor when detection fails)
  maxCenterDelta?: number;       // Default: 200 (max camera movement while maintaining lock)
}
```

### CropEditor Props

- `document` — `CapturedDocument` object from `onCapture` callback
- `overlayColor` (default `rgba(0,0,0,0.5)`) — color of overlay outside crop area
- `overlayStrokeColor` (default `#e7a649`) — color of crop boundary lines
- `handlerColor` (default `#e7a649`) — color of corner drag handles
- `enablePanStrict` (default `false`) — enable strict panning behavior
- `onCropChange(rectangle)` — callback when user adjusts corners

### Notes on camera behaviour

- If you disable `autoCapture`, the built-in shutter button appears; you can still provide your own UI as `children` to replace or augment it.
- The internal frame processor handles document detection; do not override `frameProcessor` in `cameraProps`.
- Adjust `minStableFrames` or tweak lighting conditions if auto capture is too sensitive or too slow.

## Detection Algorithm

The scanner uses a sophisticated multi-stage pipeline optimized for quality and stability:

### 1. Pre-processing (Configurable Resolution)
- Resizes frame to `processingWidth` (default 1280p) for optimal accuracy
- Converts to grayscale
- **Enhanced morphological operations**:
  - MORPH_CLOSE to fill small holes in edges (7x7 kernel)
  - MORPH_OPEN to remove small noise
- **Bilateral filter** for edge-preserving smoothing (better than Gaussian)
- **Adaptive Canny edge detection** with configurable thresholds (default 40/120)

### 2. Contour Detection
- Finds external contours using CHAIN_APPROX_SIMPLE
- Applies convex hull for improved corner accuracy
- Tests **23 epsilon values** (0.1%-10%) for approxPolyDP to find exact 4 corners
- Validates quadrilaterals for convexity and valid coordinates

### 3. Advanced Anchor Locking System
Once corners are detected, the system maintains stability through:
- **Snap locking**: Corners lock to positions when movement is minimal
- **Camera movement tolerance**: Maintains lock during movement (up to 200px center delta)
- **Persistence**: Holds anchor for up to 20 consecutive failed detections
- **Adaptive blending**: Smoothly transitions between old and new positions
- **Confidence building**: Increases lock strength over time (max 30 frames)
- **Intelligent reset**: Only resets when document clearly changes

### 4. Quad Validation
- Area ratio filtering (0.02%-90% of frame)
- Minimum edge length validation
- Aspect ratio constraints (max 7:1)
- Convexity checks to filter invalid shapes

### 5. Post-Capture Editing
After capture, users can manually adjust corners using the `CropEditor` component:
- Grid-based interface with perspective view
- Draggable corner handles
- Real-time preview of adjusted crop area
- Exports adjusted coordinates for final processing

This multi-layered approach ensures high-quality detection with maximum flexibility for various document types and lighting conditions.

## Build
```sh
yarn build
```
Generates the `dist/` output via TypeScript.

## License
MIT
