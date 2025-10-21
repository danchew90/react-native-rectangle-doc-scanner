#import "DocumentScannerView.h"
#import "IPDFCameraViewController.h"

@implementation DocumentScannerView {
    BOOL _hasSetupCamera;
    IPDFRectangeType _lastDetectionType;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        [self setEnableBorderDetection:YES];
        [self setDelegate: self];
        _hasSetupCamera = NO;
        self.manualOnly = YES;
        self.detectionCountBeforeCapture = NSIntegerMax;
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
    }
}

- (void)didMoveToWindow {
    [super didMoveToWindow];
    if (!self.window && _hasSetupCamera) {
        // Stop camera when view is removed from window
        [self stop];
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

- (void) capture {
    NSLog(@"[DocumentScanner] capture called");
    [self captureImageWithCompletionHander:^(UIImage *croppedImage, UIImage *initialImage, CIRectangleFeature *rectangleFeature) {
      NSLog(@"[DocumentScanner] captureImageWithCompletionHander callback - croppedImage: %@, initialImage: %@", croppedImage ? @"YES" : @"NO", initialImage ? @"YES" : @"NO");
      if (self.onPictureTaken) {
            NSLog(@"[DocumentScanner] Calling onPictureTaken");
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
            NSDictionary *rectangleCoordinatesDict = [self dictionaryForRectangleFeature:rectangleFeature];
            id rectangleCoordinates = rectangleCoordinatesDict ? rectangleCoordinatesDict : [NSNull null];
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
