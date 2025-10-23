# React Native Document Scanner Wrapper

React Native-friendly wrapper around [`react-native-document-scanner`](https://github.com/Michaelvilleneuve/react-native-document-scanner). It exposes a declarative `<DocScanner />` component that renders the native document scanner on both iOS and Android while keeping the surface area small enough to plug into custom UIs.

> The native implementation lives inside the upstream library (Objective‑C/OpenCV on iOS, Kotlin/OpenCV on Android). This package simply re-exports a type-safe wrapper, optional crop editor helpers, and a full-screen scanner flow.

## ✨ Professional Camera Quality (v3.2+)

**Major Update:** Upgraded to modern `AVCapturePhotoOutput` API for dramatically improved image quality!

### 🚀 What's New:
- **Modern Camera API** - Uses `AVCapturePhotoOutput` (iOS 10+) instead of deprecated `AVCaptureStillImageOutput`
- **iPhone Native Quality** - Same quality as the built-in Camera app
- **Computational Photography** - Automatic HDR, Deep Fusion, and Smart HDR support
- **12MP+ Resolution** - Full resolution capture on modern iPhones (up to 48MP on iPhone 14 Pro+)
- **Maximum Quality Priority** - iOS 13+ quality prioritization enabled
- **95%+ JPEG Quality** - Enforced minimum compression quality to prevent quality loss

### 🎯 Automatic Optimizations:
- **High-Resolution Capture** - Full sensor resolution enabled (`AVCaptureSessionPresetHigh`)
- **Minimum 95% JPEG** - Prevents quality degradation from compression
- **Advanced Features**:
  - Video stabilization for sharper images
  - Continuous autofocus for always-sharp captures
  - Auto exposure and white balance
  - Low-light boost in dark environments
- **Hardware-Accelerated** - CIContext for efficient processing

### ⚡ Fully Automatic Installation:
Just install with yarn/npm - **no manual configuration needed!**
- Postinstall script automatically patches camera quality
- Optimized iOS files copied during installation
- Works immediately after `pod install`

## Installation

```bash
yarn add react-native-rectangle-doc-scanner \
  github:Michaelvilleneuve/react-native-document-scanner \
  react-native-perspective-image-cropper

# iOS
cd ios && pod install
```

Android automatically links the native module. If you manage packages manually (legacy architecture), register `DocumentScannerPackage()` in your `MainApplication`.

## Usage

```tsx
import React, { useRef } from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { DocScanner, type DocScannerHandle } from 'react-native-rectangle-doc-scanner';

export const ScanScreen = () => {
  const scannerRef = useRef<DocScannerHandle>(null);

  return (
    <View style={styles.container}>
      <DocScanner
        ref={scannerRef}
        overlayColor="rgba(0, 126, 244, 0.35)"
        autoCapture
        minStableFrames={6}
        onCapture={(result) => {
          console.log('Captured document:', result.path);
        }}
      >
        <View style={styles.overlay} pointerEvents="none">
          <Text style={styles.hint}>Align the document inside the frame</Text>
        </View>
      </DocScanner>

      <TouchableOpacity
        style={styles.captureButton}
        onPress={() => scannerRef.current?.capture()}
      />
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
    paddingVertical: 10,
    borderRadius: 12,
    backgroundColor: 'rgba(0,0,0,0.5)',
  },
  hint: { color: '#fff', fontWeight: '600' },
  captureButton: {
    position: 'absolute',
    bottom: 40,
    alignSelf: 'center',
    width: 70,
    height: 70,
    borderRadius: 35,
    backgroundColor: '#fff',
  },
});
```

`<DocScanner />` passes through the important upstream props:

| Prop | Type | Default | Notes |
| --- | --- | --- | --- |
| `overlayColor` | `string` | `#0b7ef4` | Native overlay tint. |
| `autoCapture` | `boolean` | `true` | Maps to `manualOnly` internally. |
| `minStableFrames` | `number` | `8` | Detection count before auto capture. |
| `enableTorch` | `boolean` | `false` | Toggle device torch. |
| `quality` | `number` | `90` | 0–100 (converted for native). |
| `useBase64` | `boolean` | `false` | Return base64 payloads instead of file URIs. |
| `onCapture` | `(result) => void` | — | Receives `{ path, quad: null, width, height }`. |

Manual capture exposes an imperative `capture()` method via `ref`. Children render on top of the camera preview so you can build your own buttons, progress indicators, or onboarding tips.

## Convenience APIs

- `CropEditor` – wraps `react-native-perspective-image-cropper` for manual corner adjustment.
- `FullDocScanner` – puts the scanner and crop editor into a single modal-like flow. If the host app links either `expo-image-manipulator` or `react-native-image-rotate`, the confirmation screen exposes 90° rotation buttons; otherwise rotation controls remain hidden.

## License

MIT
