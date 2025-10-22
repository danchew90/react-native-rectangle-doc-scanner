#import "IPDFCameraViewController.h"
#import <React/RCTViewManager.h>
#import <React/RCTBridgeModule.h>

@interface DocumentScannerView : IPDFCameraViewController <IPDFCameraViewControllerDelegate>

@property (nonatomic, copy) RCTDirectEventBlock onPictureTaken;
@property (nonatomic, copy) RCTDirectEventBlock onRectangleDetect;
@property (nonatomic, assign) NSInteger detectionCountBeforeCapture;
@property (assign, nonatomic) NSInteger stableCounter;
@property (nonatomic, assign) float quality;
@property (nonatomic, assign) BOOL useBase64;
@property (nonatomic, assign) BOOL captureMultiple;
@property (nonatomic, assign) BOOL saveInAppDocument;
@property (nonatomic, assign) BOOL manualOnly;

- (void) capture;
- (void) captureWithResolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject;

@end
