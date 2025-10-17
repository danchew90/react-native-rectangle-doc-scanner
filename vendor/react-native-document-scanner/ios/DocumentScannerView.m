#import "DocumentScannerView.h"
#import "IPDFCameraViewController.h"

@implementation DocumentScannerView {
    BOOL _hasSetupCamera;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        [self setEnableBorderDetection:YES];
        [self setDelegate: self];
        _hasSetupCamera = NO;
    }

    return self;
}

- (void)didMoveToWindow {
    [super didMoveToWindow];
    if (self.window && !_hasSetupCamera) {
        // Only setup camera once when view is added to window
        [self setupCameraView];
        [self start];
        _hasSetupCamera = YES;
    } else if (!self.window && _hasSetupCamera) {
        // Stop camera when view is removed from window
        [self stop];
    }
}


- (void) didDetectRectangle:(CIRectangleFeature *)rectangle withType:(IPDFRectangeType)type {
    switch (type) {
        case IPDFRectangeTypeGood:
            self.stableCounter ++;
            break;
        default:
            self.stableCounter = 0;
            break;
    }
    if (self.onRectangleDetect) {
        self.onRectangleDetect(@{@"stableCounter": @(self.stableCounter), @"lastDetectionType": @(type)});
    }

    if (self.stableCounter > self.detectionCountBeforeCapture){
        [self capture];
    }
}

- (void) capture {
    [self captureImageWithCompletionHander:^(UIImage *croppedImage, UIImage *initialImage, CIRectangleFeature *rectangleFeature) {
      if (self.onPictureTaken) {
            // Use maximum JPEG quality (1.0) or user's quality setting, whichever is higher
            // This ensures no quality loss during compression
            CGFloat imageQuality = MAX(self.quality, 0.95);
            NSData *croppedImageData = UIImageJPEGRepresentation(croppedImage, imageQuality);

            if (initialImage.imageOrientation != UIImageOrientationUp) {
                UIGraphicsBeginImageContextWithOptions(initialImage.size, false, initialImage.scale);
                [initialImage drawInRect:CGRectMake(0, 0, initialImage.size.width
                                                    , initialImage.size.height)];
                initialImage = UIGraphicsGetImageFromCurrentImageContext();
                UIGraphicsEndImageContext();
            }
            NSData *initialImageData = UIImageJPEGRepresentation(initialImage, imageQuality);

            /*
             RectangleCoordinates expects a rectanle viewed from portrait,
             while rectangleFeature returns a rectangle viewed from landscape, which explains the nonsense of the mapping below.
             Sorry about that.
             */
            NSDictionary *rectangleCoordinates = rectangleFeature ? @{
                                     @"topLeft": @{ @"y": @(rectangleFeature.bottomLeft.x + 30), @"x": @(rectangleFeature.bottomLeft.y)},
                                     @"topRight": @{ @"y": @(rectangleFeature.topLeft.x + 30), @"x": @(rectangleFeature.topLeft.y)},
                                     @"bottomLeft": @{ @"y": @(rectangleFeature.bottomRight.x), @"x": @(rectangleFeature.bottomRight.y)},
                                     @"bottomRight": @{ @"y": @(rectangleFeature.topRight.x), @"x": @(rectangleFeature.topRight.y)},
                                     } : [NSNull null];
            if (self.useBase64) {
              self.onPictureTaken(@{
                                    @"croppedImage": [croppedImageData base64EncodedStringWithOptions:NSDataBase64Encoding64CharacterLineLength],
                                    @"initialImage": [initialImageData base64EncodedStringWithOptions:NSDataBase64Encoding64CharacterLineLength],
                                    @"rectangleCoordinates": rectangleCoordinates });
            }
            else {
                NSString *dir = NSTemporaryDirectory();
                if (self.saveInAppDocument) {
                    dir = [NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) firstObject];
                }
               NSString *croppedFilePath = [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"cropped_img_%i.jpeg",(int)[NSDate date].timeIntervalSince1970]];
               NSString *initialFilePath = [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"initial_img_%i.jpeg",(int)[NSDate date].timeIntervalSince1970]];

              [croppedImageData writeToFile:croppedFilePath atomically:YES];
              [initialImageData writeToFile:initialFilePath atomically:YES];

               self.onPictureTaken(@{
                                     @"croppedImage": croppedFilePath,
                                     @"initialImage": initialFilePath,
                                     @"rectangleCoordinates": rectangleCoordinates });
            }
        }

        if (!self.captureMultiple) {
          [self stop];
        }
    }];

}


@end
