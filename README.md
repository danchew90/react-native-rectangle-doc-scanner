# React Native Rectangle Doc Scanner

> ⚠️ **Native module migration in progress**
>
> A native VisionKit (iOS) + CameraX/ML Kit (Android) implementation is being scaffolded to replace the previous VisionCamera/OpenCV pipeline. The JavaScript API is already aligned with the native contract; the detection/capture engines will be filled in next. See [`docs/native-module-architecture.md`](docs/native-module-architecture.md) for the roadmap.

Native-ready document scanner for React Native that keeps your overlay completely customisable. The library renders a native camera preview, streams polygon detections back to JavaScript, and exposes an imperative `capture()` method so you can build the exact UX you need.

## Features

- Native camera preview surfaces on iOS/Android with React overlay support
- Polygon detection events (with stability counter) delivered every frame
- Skia-powered outline + optional 3×3 grid overlay
- Auto-capture and manual capture flows using the same API
- Optional `CropEditor` powered by `react-native-perspective-image-cropper`

## Requirements

- React Native 0.70+
- iOS 13+ (VisionKit availability) / Android 7.0+ (API 24)
- Camera permission strings in your host app (`NSCameraUsageDescription`, Android runtime permission handling)
- Peer dependencies:
  - `@shopify/react-native-skia`
  - `react-native-perspective-image-cropper`
  - `react`
  - `react-native`

## Installation

```sh
yarn add react-native-rectangle-doc-scanner \
  @shopify/react-native-skia \
  react-native-perspective-image-cropper

# iOS
cd ios && pod install
```

Android will automatically pick up the included Gradle configuration. If you use a custom package list (old architecture), register `new RNRDocScannerPackage()` manually.

## Usage

```tsx
import React, { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  DocScanner,
  CropEditor,
  type CapturedDocument,
} from 'react-native-rectangle-doc-scanner';

export const ScanScreen: React.FC = () => {
  const [capturedDoc, setCapturedDoc] = useState<CapturedDocument | null>(null);

  if (capturedDoc) {
    return (
      <CropEditor
        document={capturedDoc}
        overlayColor="rgba(0,0,0,0.6)"
        overlayStrokeColor="#e7a649"
        handlerColor="#e7a649"
        onCropChange={(rectangle) => {
          console.log('Adjusted corners:', rectangle);
        }}
      />
    );
  }

  return (
    <View style={styles.container}>
      <DocScanner
        overlayColor="#e7a649"
        minStableFrames={8}
        autoCapture
        onCapture={(doc) => {
          console.log('Captured document:', doc);
          setCapturedDoc(doc);
        }}
      >
        <View style={styles.overlay}>
          <Text style={styles.hint}>Align the document with the frame</Text>
        </View>
      </DocScanner>
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#000' },
  overlay: {
    position: 'absolute',
    top: 60,
    alignSelf: 'center',
    paddingHorizontal: 20,
    paddingVertical: 8,
    borderRadius: 12,
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  hint: { color: '#fff', fontSize: 15, fontWeight: '600' },
});
```

The native view renders underneath; anything you pass as `children` sits on top, so you can add custom buttons, headers, progress indicators, etc.

## API

### `<DocScanner />`

| Prop | Type | Default | Description |
| --- | --- | --- | --- |
| `onCapture` | `(result) => void` | – | Fired when a capture resolves. Returns `path`, `width`, `height`, `quad`. |
| `overlayColor` | `string` | `#e7a649` | Stroke colour for the overlay outline. |
| `autoCapture` | `boolean` | `true` | When `true`, capture is triggered automatically once stability is reached. |
| `minStableFrames` | `number` | `8` | Number of stable frames before auto capture fires. |
| `enableTorch` | `boolean` | `false` | Toggles device torch (if supported). |
| `quality` | `number` | `90` | JPEG quality (0–100). |
| `useBase64` | `boolean` | `false` | Return base64 strings instead of file URIs. |
| `showGrid` | `boolean` | `true` | Show the 3×3 helper grid inside the overlay. |
| `gridColor` | `string` | `rgba(231,166,73,0.35)` | Colour of grid lines. |
| `gridLineWidth` | `number` | `2` | Width of grid lines. |

Imperative helpers are exposed via `DocScannerHandle`:

```ts
import { DocScanner, type DocScannerHandle } from 'react-native-rectangle-doc-scanner';

const ref = useRef<DocScannerHandle>(null);

const fireCapture = () => {
  ref.current?.capture().then((result) => {
    console.log(result);
  });
};
```

> The native module currently returns a `"not_implemented"` error from `capture()` until the VisionKit / ML Kit integration is finished. The JS surface is ready for when the native pipeline lands.

### `<CropEditor />`

| Prop | Type | Default | Description |
| --- | --- | --- | --- |
| `document` | `CapturedDocument` | – | Document produced by `onCapture`. |
| `overlayColor` | `string` | `rgba(0,0,0,0.5)` | Tint outside the crop area. |
| `overlayStrokeColor` | `string` | `#e7a649` | Boundary colour. |
| `handlerColor` | `string` | `#e7a649` | Corner handle colour. |
| `enablePanStrict` | `boolean` | `false` | Enable strict panning behaviour. |
| `onCropChange` | `(rectangle) => void` | – | Fires when the user drags handles. |

## Native scaffolding status

- ✅ TypeScript wrapper + overlay grid
- ✅ iOS view manager / module skeleton (Swift)
- ✅ Android view manager / module skeleton (Kotlin)
- ☐ VisionKit rectangle detection & capture pipeline
- ☐ CameraX + ML Kit rectangle detection & capture pipeline
- ☐ Base64 / file output parity tests

Contributions to the native pipeline are welcome! Start by reading [`docs/native-module-architecture.md`](docs/native-module-architecture.md) for the current plan.

## Build

```sh
yarn build
```

Generates the `dist/` output via TypeScript.

## License

MIT
