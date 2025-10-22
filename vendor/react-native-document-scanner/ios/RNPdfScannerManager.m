
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

// Main capture method - uses the last created scanner view
RCT_EXPORT_METHOD(capture) {
    NSLog(@"[RNPdfScannerManager] capture called, scannerView: %@", _scannerView ? @"YES" : @"NO");
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!self->_scannerView) {
            NSLog(@"[RNPdfScannerManager] ERROR: No scanner view available");
            return;
        }
        NSLog(@"[RNPdfScannerManager] Calling capture on view: %@", self->_scannerView);
        [self->_scannerView capture];
    });
}

// Alternative method that takes reactTag - for future use
RCT_EXPORT_METHOD(captureWithTag:(nonnull NSNumber *)reactTag) {
    NSLog(@"[RNPdfScannerManager] captureWithTag called with reactTag: %@", reactTag);
    dispatch_async(dispatch_get_main_queue(), ^{
        UIView *view = [self.bridge.uiManager viewForReactTag:reactTag];
        if (!view || ![view isKindOfClass:[DocumentScannerView class]]) {
            NSLog(@"[RNPdfScannerManager] Cannot find DocumentScannerView with tag #%@", reactTag);
            return;
        }
        DocumentScannerView *scannerView = (DocumentScannerView *)view;
        NSLog(@"[RNPdfScannerManager] Calling capture on view: %@", scannerView);
        [scannerView capture];
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
