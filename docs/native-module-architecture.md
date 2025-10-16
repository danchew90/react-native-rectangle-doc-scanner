# Native Module Architecture Plan

This document lays out the roadmap for migrating the library from its current
VisionCamera/OpenCV implementation to a fully native document scanner module
that mirrors the behaviour of `react-native-document-scanner-plugin` while
keeping support for custom React components and overlays.

## Goals

- Expose a `<DocScanner />` React view component that renders a native camera
  preview.
- Maintain the ability to layer custom React children (buttons, hints, headers)
  on top of the preview.
- Provide the detected polygon and stability information every frame, so the
  existing Skia overlay and auto-capture logic continue working.
- Support both automatic and manual capture flows; captures should resolve with
  both cropped and original image metadata.
- Keep the public TypeScript API as close as possible to the current version to
  minimise breaking changes for consuming apps.

## High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│ React Native (TypeScript)                                            │
│ ┌────────────────────────────┐   events / commands   ┌─────────────┐ │
│ │ DocScanner.tsx (wrapper)  ├───────────────────────▶│ Native View │ │
│ │ - Manages refs            │◀───────────────────────┤  Module     │ │
│ │ - Handles props           │   layout callbacks     └─────────────┘ │
│ │ - Renders Overlay + UI    │                                        │
│ └────────────────────────────┘                                        │
│                ▲                             ▲                       │
│                │onRectangleDetect            │onPictureTaken         │
└────────────────┴─────────────────────────────┴───────────────────────┘
                 │                             │
        ┌────────┴────────┐             ┌──────┴──────┐
        │ iOS (Swift)      │             │ Android     │
        │ DocScannerView   │             │ DocScannerView │
        │ - AVCapture      │             │ - CameraX       │
        │ - Vision / VNDetectRectangles │ │ - ML Kit / OpenCV│
        │ - VNDocumentCamera for capture ││ - On-device cropping │
        └──────────────────┘             └───────────────────────┘
```

### React Native Layer

- `DocScanner.tsx`
  - Forwards props to the native view via `requireNativeComponent`.
  - Holds a ref to call `capture()`, `setTorchEnabled()`, etc.
  - Converts native events into the existing `Point[]` / stability format.
  - Continues rendering the Skia-based `Overlay` and any child components.
- `index.ts`
  - Exports the wrapper and new types.
- TypeScript definitions for native events, capture result, and imperative
  methods (`DocScannerHandle`).

### iOS Implementation

- Create `ios/DocScannerView.swift`
  - Subclass of `UIView` hosting an `AVCaptureVideoPreviewLayer`.
  - Uses `AVCaptureSession` with `AVCaptureVideoDataOutput` to stream frames.
  - Runs `VNDetectRectanglesRequest` on a background queue for polygon
    detection.
  - Emits `onRectangleDetect` events (containing points, stability counter, and
    frame size) via `RCTDirectEventBlock`.
  - Implements `capture()` by calling `AVCapturePhotoOutput` and optionally
    running `CIFilter` warps for perspective correction.
  - Saves both original and cropped images, returning their URIs plus width /
    height.

- Create `ios/DocScannerViewManager.m`
  - Registers the component as `RNRDocScannerView` (name to be decided).
  - Exposes props: `overlayColor`, `detectionCountBeforeCapture`, `torchEnabled`,
    `useBase64`, `quality`, etc.
  - Exposes commands / methods: `capture`, `setTorchEnabled`, `reset`.

- Create backing Swift files:
  - `DocScannerFrameProcessor.swift` – handles rectangle detection and stability
    scoring.
  - `DocScannerCaptureCoordinator.swift` – manages photo output, perspective
    correction, optional VisionKit integration.
  - `DocScannerEventPayload.swift` – strongly typed event payloads.

### Android Implementation

- Create `android/src/main/java/.../DocScannerView.kt`
  - Extends `FrameLayout`, hosts a `PreviewView` from CameraX.
  - Sets up `ProcessCameraProvider` with `ImageAnalysis` for frame processing.
  - Runs ML Kit Document Scanner API (or OpenCV fallback) to detect quads.
  - Emits events through `@ReactProp` + `UIManagerModule` event dispatch.
  - Maintains stability counter similar to current JS implementation.

- Create `DocScannerViewManager.kt`
  - Registers the view name (e.g. `RNRDocScannerView`).
  - Maps props (`overlayColor`, `detectionCountBeforeCapture`, etc.).
  - Implements `receiveCommand` to handle `capture`, `torch`, `reset`.

- Create `DocScannerModule.kt`
  - Exposes imperative methods if needed for non-view functionality (e.g. file
    cleanup).

- Provide utility classes:
  - `ImageCropper.kt` – applies perspective transformations using OpenCV or
    RenderScript.
  - `PathUtils.kt` – file management for temporary images.
  - `RectangleEvent.kt` – serialisable event payload.

### Event Contract

`onRectangleDetect` event shape (identical for iOS & Android):

```ts
type RectangleEventPayload = {
  rectangleCoordinates: {
    topLeft: Point;
    topRight: Point;
    bottomRight: Point;
    bottomLeft: Point;
  } | null;
  stableCounter: number;
  frameWidth: number;
  frameHeight: number;
};
```

`onPictureTaken` event shape:

```ts
type PictureEvent = {
  croppedImage: string | null; // file:// or base64 depending on props
  initialImage: string;
  width: number;
  height: number;
};
```

### Props Mapping

| Prop                          | Description                                         | Native handling                       |
|-------------------------------|-----------------------------------------------------|---------------------------------------|
| `overlayColor`               | Stroke colour for overlay (forwarded to events)     | Only metadata – actual overlay is JS  |
| `detectionCountBeforeCapture`| Minimum stable frames before auto capture           | Native stability counter              |
| `autoCapture`                | If `true`, native triggers capture automatically    | Native triggers `capture()` internally|
| `enableTorch`                | Controls device torch                               | Maps to `AVCaptureDevice` / CameraX   |
| `quality`                    | JPEG quality (0–100)                                | Controls compression in native capture|
| `useBase64`                  | If `true`, return base64 strings instead of files   | Native encode                          |
| `showGrid`, `gridColor`...   | Handled in JS overlay; no native changes required   | N/A                                   |

### Migration Steps

1. **Scaffold native module directories**
   - Add `ios/` and `android/` folders with minimal React Native module wiring.

2. **Implement iOS detection & capture**
   - Set up camera session, rectangle detection, event dispatch.
   - Support auto capture and manual capture commands.

3. **Implement Android detection & capture**
   - Mirror iOS logic with CameraX + ML Kit/OpenCV.

4. **Update TypeScript wrapper**
   - Replace VisionCamera/OpenCV logic with `requireNativeComponent`.
   - Keep existing overlay and UI contract intact.

5. **Testing & validation**
   - Write example app update (outside this package) to verify events, overlay,
    auto-capture.
   - Ensure TypeScript typings align with native behaviour.

6. **Documentation**
   - Update README with new installation instructions (pods/gradle changes,
    permissions, peer deps).
   - Document new props & events, auto/manual capture behaviour.

This plan focuses on creating a native module while preserving the custom
overlay UX. Each platform will require significant native code, but the above
structure provides the blueprint for implementation.

