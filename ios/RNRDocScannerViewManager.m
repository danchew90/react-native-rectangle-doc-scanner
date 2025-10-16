#import <React/RCTBridge.h>
#import <React/RCTUIManager.h>
#import <React/RCTViewManager.h>

#import "react-native-rectangle-doc-scanner-Swift.h"

@interface RCT_EXTERN_MODULE(RNRDocScannerViewManager, RCTViewManager)
RCT_EXPORT_VIEW_PROPERTY(detectionCountBeforeCapture, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(autoCapture, BOOL)
RCT_EXPORT_VIEW_PROPERTY(enableTorch, BOOL)
RCT_EXPORT_VIEW_PROPERTY(quality, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(useBase64, BOOL)
RCT_EXPORT_VIEW_PROPERTY(onRectangleDetect, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onPictureTaken, RCTDirectEventBlock)

RCT_EXTERN_METHOD(capture:(nonnull NSNumber *)reactTag
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(reset:(nonnull NSNumber *)reactTag)
@end
