# Changelog

All notable changes to this project will be documented in this file.

## [3.2.1] - 2025-10-17

### 🔧 Auto-Install Improvements

- Updated postinstall script to copy both camera files automatically
- Now copies `IPDFCameraViewController.m` and `DocumentScannerView.m`
- Complete automation - no manual steps required

## [3.2.0] - 2025-10-17

### 🔧 Camera Quality Fixes

Fixed critical quality issues and app crashes:

#### Fixed Issues
- **Fixed app crash** from HEVC format compatibility issues
- **Fixed JPEG compression quality** - now enforces minimum 95% quality
  - Previously used user's quality setting which could be very low
  - Now uses `MAX(self.quality, 0.95)` to prevent quality loss
- **Improved session preset** - uses `AVCaptureSessionPresetHigh` for better quality
- **Added nil checks** to prevent crashes when photoOutput is unavailable

#### Quality Improvements
- Minimum 95% JPEG quality ensures no visible compression artifacts
- Simplified capture to use reliable JPEG format (removed HEVC compatibility issues)
- Better error handling for edge cases

## [3.0.0] - 2025-10-17

### 🚀 BREAKING CHANGE - Modern Camera API

Complete camera system overhaul for professional-grade image quality!

#### Replaced Camera Engine
- **Migrated from deprecated `AVCaptureStillImageOutput` to modern `AVCapturePhotoOutput`**
- This API was deprecated in iOS 10 (2016) and severely limited image quality
- New API provides iPhone Camera app quality

#### New Features
- **Computational Photography Support**
  - Automatic HDR, Deep Fusion, Smart HDR
  - These features were impossible with the old API
- **Full Resolution Capture**
  - 12MP+ on modern iPhones (up to 48MP on iPhone 14 Pro+)
  - Old API was limited to lower resolutions
- **Quality Prioritization** (iOS 13+)
  - `AVCapturePhotoQualityPrioritizationQuality` enabled
  - Tells iOS to prioritize quality over speed

#### Technical Improvements
- Delegate-based capture instead of callback-based
- Better error handling and edge cases
- Reduced memory usage
- Faster capture times

#### Breaking Changes
- **iOS 10+** now required (was iOS 8+)
- Camera quality dramatically improved (expected behavior change)
- First capture may be slightly slower (computational photography warm-up)

#### Migration
No code changes required! The API remains the same:
```tsx
<DocScanner quality={100} onCapture={handleCapture} />
```

**Result:** Image quality is now comparable to or better than `react-native-document-scanner-plugin`!

## [2.1.0] - 2025-10-17

### ✨ Enhanced - Camera Quality Optimizations

Significant improvements to image capture quality with automatic optimizations:

#### Camera Resolution
- Added automatic 4K resolution support (3840x2160)
- Falls back to Full HD (1920x1080) or Photo preset based on device capability
- Enabled `highResolutionStillImageOutputEnabled` for maximum capture quality

#### Display & Preview
- Fixed Retina display scaling (2x, 3x) for crisp camera preview
- Improved preview rendering quality with proper `contentScaleFactor`

#### Camera Features
- Enabled video stabilization for sharper images
- Configured continuous autofocus for always-sharp captures
- Added continuous auto exposure for optimal brightness
- Enabled continuous auto white balance for natural colors
- Enabled low-light boost for better performance in dark environments
- Automatic 4K format selection on iOS 13+ devices

#### Image Processing
- Direct pixel buffer access (`CVImageBuffer`) instead of JPEG re-compression
- Improved JPEG quality from default to 95% (near-lossless)
- Hardware-accelerated image conversion using `CIContext`
- Removed intermediate quality loss from UIGraphics rendering
- Source image used directly for processing (no decode/encode cycle)

### 📝 Technical Details

All optimizations are applied automatically through patches to the underlying `react-native-document-scanner` package. No configuration or code changes required in your app.

**Before**: Standard photo quality with basic camera settings
**After**: 4K capture with professional camera features and near-lossless image processing

## [2.0.0] - Previous Release

Initial release with TypeScript wrapper and basic functionality.
