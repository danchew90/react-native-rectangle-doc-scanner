#import "DocumentScannerView.h"
#import "IPDFCameraViewController.h"

@implementation DocumentScannerView {
    BOOL _hasSetupCamera;
    BOOL _cameraStarted;
    IPDFRectangeType _lastDetectionType;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        [self setEnableBorderDetection:YES];
        [self setDelegate: self];
        _hasSetupCamera = NO;
        _cameraStarted = NO;
        self.manualOnly = NO;  // Changed from YES to NO - allow manual capture to work
        self.detectionCountBeforeCapture = 99999;  // High threshold to prevent auto-capture
    }

    return self;
}

- (void)layoutSubviews {
    [super layoutSubviews];

    // Setup camera after layout is complete and bounds are valid
    if (!_hasSetupCamera && self.window && !CGRectIsEmpty(self.bounds)) {
        NSLog(@"[DocumentScanner] Setting up camera with bounds: %@", NSStringFromCGRect(self.bounds));
        [self setupCameraView];
        [self start];
        _hasSetupCamera = YES;
        _cameraStarted = YES;
    }
}

- (void)didMoveToWindow {
    [super didMoveToWindow];
    if (self.window && _hasSetupCamera && !_cameraStarted) {
        // Restart camera when view is added back to window (only if it was stopped)
        NSLog(@"[DocumentScanner] View added to window, restarting camera...");
        dispatch_async(dispatch_get_main_queue(), ^{
            [self start];
            self->_cameraStarted = YES;
        });
    } else if (!self.window && _hasSetupCamera && _cameraStarted) {
        // Stop camera when view is removed from window
        NSLog(@"[DocumentScanner] View removed from window, stopping camera");
        [self stop];
        _cameraStarted = NO;
    }
}


- (NSDictionary *)dictionaryForRectangleFeature:(CIRectangleFeature *)rectangleFeature
{
    if (!rectangleFeature) {
        return nil;
    }

    return @{
        @"topLeft": @{ @"y": @(rectangleFeature.bottomLeft.x + 30), @"x": @(rectangleFeature.bottomLeft.y)},
        @"topRight": @{ @"y": @(rectangleFeature.topLeft.x + 30), @"x": @(rectangleFeature.topLeft.y)},
        @"bottomLeft": @{ @"y": @(rectangleFeature.bottomRight.x), @"x": @(rectangleFeature.bottomRight.y)},
        @"bottomRight": @{ @"y": @(rectangleFeature.topRight.x), @"x": @(rectangleFeature.topRight.y)},
    };
}

- (void) didDetectRectangle:(CIRectangleFeature *)rectangle withType:(IPDFRectangeType)type {
    // Handle case where no rectangle is detected
    if (!rectangle) {
        NSLog(@"[DocumentScanner] Rectangle not found, resetting counter");
        self.stableCounter = 0;
        _lastDetectionType = type;
        return;
    }

    switch (type) {
        case IPDFRectangeTypeGood:
            self.stableCounter ++;
            NSLog(@"[DocumentScanner] Good rectangle detected, stableCounter: %ld/%ld", (long)self.stableCounter, (long)self.detectionCountBeforeCapture);
            break;
        case IPDFRectangeTypeBadAngle:
        case IPDFRectangeTypeTooFar:
            // For bad rectangles, reduce counter slowly instead of resetting
            if (self.stableCounter > 0) {
                self.stableCounter--;
            }
            NSLog(@"[DocumentScanner] Bad rectangle detected (type: %ld), stableCounter: %ld", (long)type, (long)self.stableCounter);
            break;
    }

    _lastDetectionType = type;

    NSLog(@"[DocumentScanner] manualOnly=%@ detectionCount=%ld stableCounter=%ld", self.manualOnly ? @"YES" : @"NO", (long)self.detectionCountBeforeCapture, (long)self.stableCounter);

    if (self.manualOnly) {
        return;
    }

    if (self.stableCounter >= self.detectionCountBeforeCapture){
        NSLog(@"[DocumentScanner] Auto-capture triggered! stableCounter: %ld >= threshold: %ld", (long)self.stableCounter, (long)self.detectionCountBeforeCapture);
        self.stableCounter = 0; // Reset to prevent multiple captures
        [self capture];
    }
}

- (void)cameraViewController:(IPDFCameraViewController *)controller
          didDetectRectangle:(CIRectangleFeature *)rectangle
                    withType:(IPDFRectangeType)type
             viewCoordinates:(NSDictionary *)viewCoordinates
                   imageSize:(CGSize)imageSize
{
    _lastDetectionType = type;

    if (!self.onRectangleDetect) {
        return;
    }

    NSDictionary *rectangleCoordinates = [self dictionaryForRectangleFeature:rectangle];
    NSMutableDictionary *payload = [@{
        @"stableCounter": @(self.stableCounter),
        @"lastDetectionType": @(_lastDetectionType),
        @"rectangleCoordinates": rectangleCoordinates ? rectangleCoordinates : [NSNull null],
        @"rectangleOnScreen": viewCoordinates ? viewCoordinates : [NSNull null],
        @"previewSize": @{
            @"width": @(self.bounds.size.width),
            @"height": @(self.bounds.size.height)
        },
        @"imageSize": @{
            @"width": @(imageSize.width),
            @"height": @(imageSize.height)
        }
    } mutableCopy];

    self.onRectangleDetect(payload);
}

// Helper method to process captured images and prepare response data
- (NSDictionary *)processAndPrepareImageData:(UIImage *)croppedImage
                                 initialImage:(UIImage *)initialImage
                             rectangleFeature:(CIRectangleFeature *)rectangleFeature {
    CGFloat imageQuality = MAX(self.quality, 0.95);
    NSData *croppedImageData = UIImageJPEGRepresentation(croppedImage, imageQuality);

    if (initialImage.imageOrientation != UIImageOrientationUp) {
        UIGraphicsBeginImageContextWithOptions(initialImage.size, false, initialImage.scale);
        [initialImage drawInRect:CGRectMake(0, 0, initialImage.size.width, initialImage.size.height)];
        initialImage = UIGraphicsGetImageFromCurrentImageContext();
        UIGraphicsEndImageContext();
    }
    NSData *initialImageData = UIImageJPEGRepresentation(initialImage, imageQuality);

    NSDictionary *rectangleCoordinatesDict = [self dictionaryForRectangleFeature:rectangleFeature];
    id rectangleCoordinates = rectangleCoordinatesDict ? rectangleCoordinatesDict : [NSNull null];

    if (self.useBase64) {
        return @{
            @"croppedImage": [croppedImageData base64EncodedStringWithOptions:NSDataBase64Encoding64CharacterLineLength],
            @"initialImage": [initialImageData base64EncodedStringWithOptions:NSDataBase64Encoding64CharacterLineLength],
            @"rectangleCoordinates": rectangleCoordinates
        };
    } else {
        NSString *dir = NSTemporaryDirectory();
        if (self.saveInAppDocument) {
            dir = [NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) firstObject];
        }
        NSString *croppedFilePath = [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"cropped_img_%i.jpeg",(int)[NSDate date].timeIntervalSince1970]];
        NSString *initialFilePath = [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"initial_img_%i.jpeg",(int)[NSDate date].timeIntervalSince1970]];

        [croppedImageData writeToFile:croppedFilePath atomically:YES];
        [initialImageData writeToFile:initialFilePath atomically:YES];

        return @{
            @"croppedImage": croppedFilePath,
            @"initialImage": initialFilePath,
            @"rectangleCoordinates": rectangleCoordinates
        };
    }
}

// Promise-based capture method - NEW
- (void)captureWithResolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject {
    NSLog(@"[DocumentScanner] captureWithResolver called");

    [self captureImageWithCompletionHander:^(UIImage *croppedImage, UIImage *initialImage, CIRectangleFeature *rectangleFeature) {
        NSLog(@"[DocumentScanner] captureImageWithCompletionHander callback - croppedImage: %@, initialImage: %@", croppedImage ? @"YES" : @"NO", initialImage ? @"YES" : @"NO");

        if (!croppedImage && initialImage) {
            croppedImage = initialImage;
        } else if (!initialImage && croppedImage) {
            initialImage = croppedImage;
        }

        if (!croppedImage || !initialImage) {
            NSLog(@"[DocumentScanner] capture failed - missing image data");
            reject(@"CAPTURE_FAILED", @"Failed to capture image", nil);

            if (!self.captureMultiple) {
                [self stop];
            }
            return;
        }

        NSLog(@"[DocumentScanner] Processing captured images");
        NSDictionary *result = [self processAndPrepareImageData:croppedImage
                                                    initialImage:initialImage
                                                rectangleFeature:rectangleFeature];

        NSLog(@"[DocumentScanner] Resolving promise with result");
        resolve(result);

        if (!self.captureMultiple) {
            [self stop];
        }
    }];
}

// Event-based capture method - LEGACY (for backwards compatibility)
- (void) capture {
    NSLog(@"[DocumentScanner] capture called");
    [self captureImageWithCompletionHander:^(UIImage *croppedImage, UIImage *initialImage, CIRectangleFeature *rectangleFeature) {
    NSLog(@"[DocumentScanner] captureImageWithCompletionHander callback - croppedImage: %@, initialImage: %@", croppedImage ? @"YES" : @"NO", initialImage ? @"YES" : @"NO");

      if (!croppedImage && initialImage) {
        // Use initial image when cropping is not available
        croppedImage = initialImage;
      } else if (!initialImage && croppedImage) {
        // Mirror cropped image so downstream logic continues to work
        initialImage = croppedImage;
      }

      if (!croppedImage || !initialImage) {
        NSLog(@"[DocumentScanner] capture failed - missing image data");
        if (self.onPictureTaken) {
          self.onPictureTaken(@{ @"error": @"capture_failed" });
        }

        if (!self.captureMultiple) {
          [self stop];
        }
        return;
      }

      if (self.onPictureTaken) {
            NSLog(@"[DocumentScanner] Calling onPictureTaken");
            NSDictionary *result = [self processAndPrepareImageData:croppedImage
                                                        initialImage:initialImage
                                                    rectangleFeature:rectangleFeature];
            self.onPictureTaken(result);
        }

        if (!self.captureMultiple) {
          [self stop];
        }
    }];

}


@end
