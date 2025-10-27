
#import "RNPdfScannerManager.h"
#import "DocumentScannerView.h"
#import <React/RCTUIManager.h>
#import <UIKit/UIKit.h>
#import <CoreImage/CoreImage.h>

@interface RNPdfScannerManager()
@property (strong, nonatomic) DocumentScannerView *scannerView;
@end

@implementation RNPdfScannerManager

- (dispatch_queue_t)methodQueue
{
    return dispatch_get_main_queue();
}

RCT_EXPORT_MODULE()

RCT_EXPORT_VIEW_PROPERTY(onPictureTaken, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onRectangleDetect, RCTBubblingEventBlock)


RCT_EXPORT_VIEW_PROPERTY(overlayColor, UIColor)
RCT_EXPORT_VIEW_PROPERTY(enableTorch, BOOL)
RCT_EXPORT_VIEW_PROPERTY(useFrontCam, BOOL)
RCT_EXPORT_VIEW_PROPERTY(useBase64, BOOL)
RCT_EXPORT_VIEW_PROPERTY(saveInAppDocument, BOOL)
RCT_EXPORT_VIEW_PROPERTY(captureMultiple, BOOL)
RCT_EXPORT_VIEW_PROPERTY(manualOnly, BOOL)
RCT_EXPORT_VIEW_PROPERTY(detectionCountBeforeCapture, NSInteger)
RCT_EXPORT_VIEW_PROPERTY(detectionRefreshRateInMS, NSInteger)
RCT_EXPORT_VIEW_PROPERTY(saturation, float)
RCT_EXPORT_VIEW_PROPERTY(quality, float)
RCT_EXPORT_VIEW_PROPERTY(brightness, float)
RCT_EXPORT_VIEW_PROPERTY(contrast, float)

// Main capture method - accept reactTag when available (falls back to cached view)
RCT_EXPORT_METHOD(capture:(NSNumber * _Nullable)reactTag
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    NSLog(@"[RNPdfScannerManager] capture called with reactTag: %@", reactTag);
    dispatch_async(dispatch_get_main_queue(), ^{
        DocumentScannerView *targetView = nil;
        NSNumber *resolvedTag = reactTag;

        if ([resolvedTag isKindOfClass:[NSNull class]]) {
            resolvedTag = nil;
        }

        if (resolvedTag) {
            UIView *view = [self.bridge.uiManager viewForReactTag:resolvedTag];
            if ([view isKindOfClass:[DocumentScannerView class]]) {
                targetView = (DocumentScannerView *)view;
                self->_scannerView = targetView;
            } else if (view) {
                NSLog(@"[RNPdfScannerManager] View for tag %@ is not DocumentScannerView: %@", resolvedTag, NSStringFromClass(view.class));
            } else {
                NSLog(@"[RNPdfScannerManager] No view found for tag %@", resolvedTag);
            }
        }

        if (!targetView && self->_scannerView) {
            NSLog(@"[RNPdfScannerManager] Falling back to last known scanner view: %@", self->_scannerView);
            targetView = self->_scannerView;
        } else if (!targetView) {
            NSLog(@"[RNPdfScannerManager] No cached scanner view available");
        }

        if (!targetView) {
            NSLog(@"[RNPdfScannerManager] ERROR: Scanner view not yet ready for capture");
            if (reject) {
                reject(@"NO_VIEW", @"Document scanner view is not ready", nil);
            }
            return;
        }

        NSLog(@"[RNPdfScannerManager] Calling capture on view: %@", targetView);
        [targetView captureWithResolver:resolve rejecter:reject];
    });
}

- (UIView*) view {
    _scannerView = [[DocumentScannerView alloc] init];
    // Force layout update
    _scannerView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    NSLog(@"[RNPdfScannerManager] Created view with frame: %@", NSStringFromCGRect(_scannerView.frame));
    return _scannerView;
}

RCT_EXPORT_METHOD(applyColorControls:(NSString *)imagePath
                  brightness:(nonnull NSNumber *)brightness
                  contrast:(nonnull NSNumber *)contrast
                  saturation:(nonnull NSNumber *)saturation
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        @autoreleasepool {
            NSString *resolvedPath = imagePath ?: @"";
            if (![resolvedPath length]) {
                if (reject) {
                    reject(@"INVALID_PATH", @"Image path is empty", nil);
                }
                return;
            }

            NSString *fileUri = resolvedPath;
            if (![fileUri hasPrefix:@"file://"]) {
                fileUri = [NSString stringWithFormat:@"file://%@", resolvedPath];
            }

            NSURL *imageURL = [NSURL URLWithString:fileUri];
            if (!imageURL) {
                if (reject) {
                    reject(@"INVALID_URI", @"Unable to create URL for image path", nil);
                }
                return;
            }

            CIImage *inputImage = [CIImage imageWithContentsOfURL:imageURL];
            if (!inputImage) {
                if (reject) {
                    reject(@"LOAD_FAILED", @"Failed to load image for color adjustment", nil);
                }
                return;
            }

            CIFilter *colorControls = [CIFilter filterWithName:@"CIColorControls"];
            if (!colorControls) {
                if (reject) {
                    reject(@"FILTER_MISSING", @"CIColorControls filter is unavailable", nil);
                }
                return;
            }

            [colorControls setValue:inputImage forKey:kCIInputImageKey];
            [colorControls setValue:brightness forKey:@"inputBrightness"];
            [colorControls setValue:contrast forKey:@"inputContrast"];
            [colorControls setValue:saturation forKey:@"inputSaturation"];

            CIImage *outputImage = colorControls.outputImage;
            if (!outputImage) {
                if (reject) {
                    reject(@"FILTER_FAILED", @"Failed to apply color controls", nil);
                }
                return;
            }

            CIContext *context = [CIContext contextWithOptions:nil];
            CGRect extent = outputImage.extent;
            CGImageRef cgImage = [context createCGImage:outputImage fromRect:extent];

            if (!cgImage) {
                if (reject) {
                    reject(@"RENDER_FAILED", @"Failed to render filtered image", nil);
                }
                return;
            }

            UIImage *resultImage = [UIImage imageWithCGImage:cgImage];
            CGImageRelease(cgImage);

            if (!resultImage) {
                if (reject) {
                    reject(@"IMAGE_CREATION_FAILED", @"Failed to create UIImage from filtered output", nil);
                }
                return;
            }

            NSData *jpegData = UIImageJPEGRepresentation(resultImage, 0.98f);
            if (!jpegData) {
                if (reject) {
                    reject(@"ENCODE_FAILED", @"Failed to encode filtered image to JPEG", nil);
                }
                return;
            }

            NSString *tempDirectory = NSTemporaryDirectory();
            NSString *fileName = [NSString stringWithFormat:@"docscanner_enhanced_%@.jpg", [[NSUUID UUID] UUIDString]];
            NSString *outputPath = [tempDirectory stringByAppendingPathComponent:fileName];

            NSError *writeError = nil;
            BOOL success = [jpegData writeToFile:outputPath options:NSDataWritingAtomic error:&writeError];
            if (!success) {
                if (reject) {
                    reject(@"WRITE_FAILED", @"Failed to write filtered image to disk", writeError);
                }
                return;
            }

            if (resolve) {
                resolve(outputPath);
            }
        }
    });
}

@end
