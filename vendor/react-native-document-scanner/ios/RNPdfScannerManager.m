
#import "RNPdfScannerManager.h"
#import "DocumentScannerView.h"
#import <React/RCTUIManager.h>

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

        if ([reactTag isKindOfClass:[NSNull class]]) {
            reactTag = nil;
        }

        if (reactTag) {
            NSNumber *resolvedTag = (NSNumber *)reactTag;
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

@end
