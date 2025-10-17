# Changelog

All notable changes to this project will be documented in this file.

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
