//
//  IPDFCameraViewController.m
//  InstaPDF
//
//  Created by Maximilian Mackh on 06/01/15.
//  Copyright (c) 2015 mackh ag. All rights reserved.
//

#import "IPDFCameraViewController.h"

#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <CoreImage/CoreImage.h>
#import <ImageIO/ImageIO.h>
#import <GLKit/GLKit.h>

@interface IPDFCameraViewController () <AVCaptureVideoDataOutputSampleBufferDelegate, AVCapturePhotoCaptureDelegate>

@property (nonatomic,strong) AVCaptureSession *captureSession;
@property (nonatomic,strong) AVCaptureDevice *captureDevice;
@property (nonatomic,strong) EAGLContext *context;

@property (nonatomic, strong) AVCapturePhotoOutput* photoOutput;

@property (nonatomic, assign) BOOL forceStop;
@property (nonatomic, assign) float lastDetectionRate;

@property (nonatomic, copy) void (^photoCaptureCompletionHandler)(UIImage *croppedImage, UIImage *initialImage, CIRectangleFeature *rectangleFeature);

@end

@implementation IPDFCameraViewController
{
    CIContext *_coreImageContext;
    GLuint _renderBuffer;
    GLKView *_glkView;

    BOOL _isStopped;

    CGFloat _imageDedectionConfidence;
    NSTimer *_borderDetectTimeKeeper;
    BOOL _borderDetectFrame;
    CIRectangleFeature *_borderDetectLastRectangleFeature;

    BOOL _isCapturing;
}

- (void)awakeFromNib
{
    [super awakeFromNib];

    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(_backgroundMode) name:UIApplicationWillResignActiveNotification object:nil];

    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(_foregroundMode) name:UIApplicationDidBecomeActiveNotification object:nil];
}

- (void)_backgroundMode
{
    self.forceStop = YES;
}

- (void)_foregroundMode
{
    self.forceStop = NO;
}

- (void)dealloc
{
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

- (void)createGLKView
{
    if (self.context) return;

    self.context = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES2];
    GLKView *view = [[GLKView alloc] initWithFrame:self.bounds];
    view.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    view.translatesAutoresizingMaskIntoConstraints = YES;
    view.context = self.context;
    view.contentScaleFactor = [UIScreen mainScreen].scale;
    view.drawableDepthFormat = GLKViewDrawableDepthFormat24;
    [self insertSubview:view atIndex:0];
    _glkView = view;
    glGenRenderbuffers(1, &_renderBuffer);
    glBindRenderbuffer(GL_RENDERBUFFER, _renderBuffer);
    _coreImageContext = [CIContext contextWithEAGLContext:self.context];
    [EAGLContext setCurrentContext:self.context];
}

- (void)setupCameraView
{
    [self createGLKView];

    AVCaptureDevice *device = nil;
    NSArray *devices = [AVCaptureDevice devicesWithMediaType:AVMediaTypeVideo];
    for (AVCaptureDevice *possibleDevice in devices) {
        if (self.useFrontCam) {
            if ([possibleDevice position] == AVCaptureDevicePositionFront) {
                device = possibleDevice;
            }
        } else {
            if ([possibleDevice position] != AVCaptureDevicePositionFront) {
                device = possibleDevice;
            }
        }
    }
    if (!device) return;

    _imageDedectionConfidence = 0.0;

    AVCaptureSession *session = [[AVCaptureSession alloc] init];
    self.captureSession = session;
    [session beginConfiguration];
    self.captureDevice = device;

    NSError *error = nil;
    AVCaptureDeviceInput* input = [AVCaptureDeviceInput deviceInputWithDevice:device error:&error];

    // Set session preset to highest quality
    if ([session canSetSessionPreset:AVCaptureSessionPresetHigh]) {
        session.sessionPreset = AVCaptureSessionPresetHigh;
    } else {
        session.sessionPreset = AVCaptureSessionPresetPhoto;
    }

    [session addInput:input];

    AVCaptureVideoDataOutput *dataOutput = [[AVCaptureVideoDataOutput alloc] init];
    [dataOutput setAlwaysDiscardsLateVideoFrames:YES];
    [dataOutput setVideoSettings:@{(id)kCVPixelBufferPixelFormatTypeKey:@(kCVPixelFormatType_32BGRA)}];
    [dataOutput setSampleBufferDelegate:self queue:dispatch_get_main_queue()];
    [session addOutput:dataOutput];

    // Use modern AVCapturePhotoOutput for best quality
    self.photoOutput = [[AVCapturePhotoOutput alloc] init];

    if ([session canAddOutput:self.photoOutput]) {
        [session addOutput:self.photoOutput];

        // Enable high quality photo capture
        if (@available(iOS 13.0, *)) {
            self.photoOutput.maxPhotoQualityPrioritization = AVCapturePhotoQualityPrioritizationQuality;
            // maxPhotoDimensions defaults to the highest supported resolution automatically
        }
    }

    AVCaptureConnection *connection = [dataOutput.connections firstObject];
    [connection setVideoOrientation:AVCaptureVideoOrientationPortrait];

    // Enable video stabilization for better quality
    if ([connection isVideoStabilizationSupported]) {
        [connection setPreferredVideoStabilizationMode:AVCaptureVideoStabilizationModeAuto];
    }

    // Configure device for best quality
    if ([device lockForConfiguration:nil])
    {
        // Disable flash for better natural lighting
        if (device.isFlashAvailable) {
            [device setFlashMode:AVCaptureFlashModeOff];
        }

        // Enable continuous autofocus for sharp images
        if ([device isFocusModeSupported:AVCaptureFocusModeContinuousAutoFocus]) {
            [device setFocusMode:AVCaptureFocusModeContinuousAutoFocus];
        }

        // Enable continuous auto exposure
        if ([device isExposureModeSupported:AVCaptureExposureModeContinuousAutoExposure]) {
            [device setExposureMode:AVCaptureExposureModeContinuousAutoExposure];
        }

        // Enable auto white balance
        if ([device isWhiteBalanceModeSupported:AVCaptureWhiteBalanceModeContinuousAutoWhiteBalance]) {
            [device setWhiteBalanceMode:AVCaptureWhiteBalanceModeContinuousAutoWhiteBalance];
        }

        // Enable low light boost if available
        if (device.isLowLightBoostSupported) {
            [device setAutomaticallyEnablesLowLightBoostWhenAvailable:YES];
        }

        // Set active video format to highest resolution
        if (@available(iOS 13.0, *)) {
            AVCaptureDeviceFormat *bestFormat = nil;
            AVFrameRateRange *bestFrameRateRange = nil;
            for (AVCaptureDeviceFormat *format in [device formats]) {
                CMVideoDimensions dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription);
                // Prefer 4K resolution (3840x2160)
                if (dimensions.width == 3840 && dimensions.height == 2160) {
                    for (AVFrameRateRange *range in format.videoSupportedFrameRateRanges) {
                        if (bestFormat == nil || range.maxFrameRate > bestFrameRateRange.maxFrameRate) {
                            bestFormat = format;
                            bestFrameRateRange = range;
                        }
                    }
                }
            }
            if (bestFormat) {
                [device setActiveFormat:bestFormat];
            }
        }

        [device unlockForConfiguration];
    }

    [session commitConfiguration];
}

- (void)setCameraViewType:(IPDFCameraViewType)cameraViewType
{
    UIBlurEffect * effect = [UIBlurEffect effectWithStyle:UIBlurEffectStyleDark];
    UIVisualEffectView *viewWithBlurredBackground =[[UIVisualEffectView alloc] initWithEffect:effect];
    viewWithBlurredBackground.frame = self.bounds;
    [self insertSubview:viewWithBlurredBackground aboveSubview:_glkView];

    _cameraViewType = cameraViewType;


    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.3 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^
    {
        [viewWithBlurredBackground removeFromSuperview];
    });
}

-(void)captureOutput:(AVCaptureOutput *)captureOutput didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer fromConnection:(AVCaptureConnection *)connection
{
    if (self.forceStop) return;
    if (_isStopped || _isCapturing || !CMSampleBufferIsValid(sampleBuffer)) return;

    CVPixelBufferRef pixelBuffer = (CVPixelBufferRef)CMSampleBufferGetImageBuffer(sampleBuffer);

    CIImage *image = [CIImage imageWithCVPixelBuffer:pixelBuffer];

    if (self.cameraViewType != IPDFCameraViewTypeNormal)
    {
        image = [self filteredImageUsingEnhanceFilterOnImage:image];
    }
    else
    {
        image = [self filteredImageUsingContrastFilterOnImage:image];
    }

    if (self.isBorderDetectionEnabled)
    {
        if (_borderDetectFrame)
        {
            _borderDetectLastRectangleFeature = [self biggestRectangleInRectangles:[[self highAccuracyRectangleDetector] featuresInImage:image]];
            _borderDetectFrame = NO;
        }

        if (_borderDetectLastRectangleFeature)
        {
            _imageDedectionConfidence += .5;

            image = [self drawHighlightOverlayForPoints:image topLeft:_borderDetectLastRectangleFeature.topLeft topRight:_borderDetectLastRectangleFeature.topRight bottomLeft:_borderDetectLastRectangleFeature.bottomLeft bottomRight:_borderDetectLastRectangleFeature.bottomRight];
        }
        else
        {
            _imageDedectionConfidence = 0.0f;
        }
    }

    if (self.context && _coreImageContext)
    {
        // Calculate the rect to draw the image with aspect fill
        CGRect drawRect = self.bounds;
        CGRect imageExtent = image.extent;

        // Calculate aspect ratios
        CGFloat imageAspect = imageExtent.size.width / imageExtent.size.height;
        CGFloat viewAspect = drawRect.size.width / drawRect.size.height;

        CGRect fromRect = imageExtent;

        // Aspect fill: crop the image to fill the view
        if (imageAspect > viewAspect) {
            // Image is wider, crop width
            CGFloat newWidth = imageExtent.size.height * viewAspect;
            CGFloat xOffset = (imageExtent.size.width - newWidth) / 2.0;
            fromRect = CGRectMake(xOffset, 0, newWidth, imageExtent.size.height);
        } else {
            // Image is taller, crop height
            CGFloat newHeight = imageExtent.size.width / viewAspect;
            CGFloat yOffset = (imageExtent.size.height - newHeight) / 2.0;
            fromRect = CGRectMake(0, yOffset, imageExtent.size.width, newHeight);
        }

        [_coreImageContext drawImage:image inRect:drawRect fromRect:fromRect];
        [self.context presentRenderbuffer:GL_RENDERBUFFER];

        [_glkView setNeedsDisplay];
    }
}

- (void)enableBorderDetectFrame
{
    _borderDetectFrame = YES;
}

- (CIImage *)drawHighlightOverlayForPoints:(CIImage *)image topLeft:(CGPoint)topLeft topRight:(CGPoint)topRight bottomLeft:(CGPoint)bottomLeft bottomRight:(CGPoint)bottomRight
{
    CIImage *overlay = [CIImage imageWithColor:[[CIColor alloc] initWithColor:self.overlayColor]];
    overlay = [overlay imageByCroppingToRect:image.extent];
    overlay = [overlay imageByApplyingFilter:@"CIPerspectiveTransformWithExtent" withInputParameters:@{@"inputExtent":[CIVector vectorWithCGRect:image.extent],@"inputTopLeft":[CIVector vectorWithCGPoint:topLeft],@"inputTopRight":[CIVector vectorWithCGPoint:topRight],@"inputBottomLeft":[CIVector vectorWithCGPoint:bottomLeft],@"inputBottomRight":[CIVector vectorWithCGPoint:bottomRight]}];

    return [overlay imageByCompositingOverImage:image];
}

- (void)start
{
    _isStopped = NO;

    // Start camera session on background thread to avoid UI blocking
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        [self.captureSession startRunning];
    });

    float detectionRefreshRate = _detectionRefreshRateInMS;
    CGFloat detectionRefreshRateInSec = detectionRefreshRate/100;

    if (_lastDetectionRate != _detectionRefreshRateInMS) {
        if (_borderDetectTimeKeeper) {
            [_borderDetectTimeKeeper invalidate];
        }
        _borderDetectTimeKeeper = [NSTimer scheduledTimerWithTimeInterval:detectionRefreshRateInSec target:self selector:@selector(enableBorderDetectFrame) userInfo:nil repeats:YES];
    }

    [self hideGLKView:NO completion:nil];

    _lastDetectionRate = _detectionRefreshRateInMS;
}

- (void)stop
{
    _isStopped = YES;

    [self.captureSession stopRunning];

    [_borderDetectTimeKeeper invalidate];

    [self hideGLKView:YES completion:nil];
}

- (void)setEnableTorch:(BOOL)enableTorch
{
    _enableTorch = enableTorch;

    AVCaptureDevice *device = self.captureDevice;
    if ([device hasTorch] && [device hasFlash])
    {
        [device lockForConfiguration:nil];
        if (enableTorch)
        {
            [device setTorchMode:AVCaptureTorchModeOn];
        }
        else
        {
            [device setTorchMode:AVCaptureTorchModeOff];
        }
        [device unlockForConfiguration];
    }
}

- (void)setUseFrontCam:(BOOL)useFrontCam
{
    _useFrontCam = useFrontCam;
    [self stop];
    [self setupCameraView];
    [self start];
}


- (void)setContrast:(float)contrast
{

    _contrast = contrast;
}

- (void)setSaturation:(float)saturation
{
    _saturation = saturation;
}

- (void)setBrightness:(float)brightness
{
    _brightness = brightness;
}

- (void)setDetectionRefreshRateInMS:(NSInteger)detectionRefreshRateInMS
{
    _detectionRefreshRateInMS = detectionRefreshRateInMS;
}


- (void)focusAtPoint:(CGPoint)point completionHandler:(void(^)())completionHandler
{
    AVCaptureDevice *device = self.captureDevice;
    CGPoint pointOfInterest = CGPointZero;
    CGSize frameSize = self.bounds.size;
    pointOfInterest = CGPointMake(point.y / frameSize.height, 1.f - (point.x / frameSize.width));

    if ([device isFocusPointOfInterestSupported] && [device isFocusModeSupported:AVCaptureFocusModeAutoFocus])
    {
        NSError *error;
        if ([device lockForConfiguration:&error])
        {
            if ([device isFocusModeSupported:AVCaptureFocusModeContinuousAutoFocus])
            {
                [device setFocusMode:AVCaptureFocusModeContinuousAutoFocus];
                [device setFocusPointOfInterest:pointOfInterest];
            }

            if([device isExposurePointOfInterestSupported] && [device isExposureModeSupported:AVCaptureExposureModeContinuousAutoExposure])
            {
                [device setExposurePointOfInterest:pointOfInterest];
                [device setExposureMode:AVCaptureExposureModeContinuousAutoExposure];
                completionHandler();
            }

            [device unlockForConfiguration];
        }
    }
    else
    {
        completionHandler();
    }
}

- (void)captureImageWithCompletionHander:(void(^)(id data, id initialData, CIRectangleFeature *rectangleFeature))completionHandler
{
    if (_isCapturing) return;

    // Check if photoOutput is available
    if (!self.photoOutput) {
        NSLog(@"Error: photoOutput is nil");
        if (completionHandler) {
            completionHandler(nil, nil, nil);
        }
        return;
    }

    __weak typeof(self) weakSelf = self;

    [weakSelf hideGLKView:YES completion:^
    {
        [weakSelf hideGLKView:NO completion:^
        {
            [weakSelf hideGLKView:YES completion:nil];
        }];
    }];

    _isCapturing = YES;

    // Store completion handler for delegate callback
    self.photoCaptureCompletionHandler = completionHandler;

    // Create photo settings with maximum quality - use JPEG for compatibility
    AVCapturePhotoSettings *photoSettings = [AVCapturePhotoSettings photoSettings];

    // Enable high resolution photo capture
    photoSettings.highResolutionPhotoEnabled = YES;

    // Set maximum quality prioritization (iOS 13+)
    if (@available(iOS 13.0, *)) {
        photoSettings.photoQualityPrioritization = AVCapturePhotoQualityPrioritizationQuality;
    }

    // Enable auto flash
    if (self.photoOutput.supportedFlashModes && [self.photoOutput.supportedFlashModes containsObject:@(AVCaptureFlashModeAuto)]) {
        photoSettings.flashMode = AVCaptureFlashModeAuto;
    }

    // Capture photo - delegate will be called
    [self.photoOutput capturePhotoWithSettings:photoSettings delegate:self];
}

- (void)hideGLKView:(BOOL)hidden completion:(void(^)())completion
{
    [UIView animateWithDuration:0.1 animations:^
    {
        _glkView.alpha = (hidden) ? 0.0 : 1.0;
    }
    completion:^(BOOL finished)
    {
        if (!completion) return;
        completion();
    }];
}

- (CIImage *)filteredImageUsingEnhanceFilterOnImage:(CIImage *)image
{
    return [CIFilter filterWithName:@"CIColorControls" keysAndValues:kCIInputImageKey, image, @"inputBrightness", @(self.brightness), @"inputContrast", @(self.contrast), @"inputSaturation", @(self.saturation), nil].outputImage;
}

- (CIImage *)filteredImageUsingContrastFilterOnImage:(CIImage *)image
{
    return [CIFilter filterWithName:@"CIColorControls" withInputParameters:@{@"inputContrast":@(1.0),kCIInputImageKey:image}].outputImage;
}

- (CIImage *)correctPerspectiveForImage:(CIImage *)image withFeatures:(CIRectangleFeature *)rectangleFeature
{
  NSMutableDictionary *rectangleCoordinates = [NSMutableDictionary new];
  CGPoint newLeft = CGPointMake(rectangleFeature.topLeft.x + 30, rectangleFeature.topLeft.y);
  CGPoint newRight = CGPointMake(rectangleFeature.topRight.x, rectangleFeature.topRight.y);
  CGPoint newBottomLeft = CGPointMake(rectangleFeature.bottomLeft.x + 30, rectangleFeature.bottomLeft.y);
  CGPoint newBottomRight = CGPointMake(rectangleFeature.bottomRight.x, rectangleFeature.bottomRight.y);


  rectangleCoordinates[@"inputTopLeft"] = [CIVector vectorWithCGPoint:newLeft];
  rectangleCoordinates[@"inputTopRight"] = [CIVector vectorWithCGPoint:newRight];
  rectangleCoordinates[@"inputBottomLeft"] = [CIVector vectorWithCGPoint:newBottomLeft];
  rectangleCoordinates[@"inputBottomRight"] = [CIVector vectorWithCGPoint:newBottomRight];
  return [image imageByApplyingFilter:@"CIPerspectiveCorrection" withInputParameters:rectangleCoordinates];
}

- (CIDetector *)rectangleDetetor
{
    static CIDetector *detector = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^
    {
          detector = [CIDetector detectorOfType:CIDetectorTypeRectangle context:nil options:@{CIDetectorAccuracy : CIDetectorAccuracyLow,CIDetectorTracking : @(YES)}];
    });
    return detector;
}

- (CIDetector *)highAccuracyRectangleDetector
{
    static CIDetector *detector = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^
    {
        detector = [CIDetector detectorOfType:CIDetectorTypeRectangle context:nil options:@{CIDetectorAccuracy : CIDetectorAccuracyHigh, CIDetectorReturnSubFeatures: @(YES) }];
    });
    return detector;
}

- (CIRectangleFeature *)biggestRectangleInRectangles:(NSArray *)rectangles
{
    if (![rectangles count]) return nil;

    float halfPerimiterValue = 0;

    CIRectangleFeature *biggestRectangle = [rectangles firstObject];

    for (CIRectangleFeature *rect in rectangles)
    {
        CGPoint p1 = rect.topLeft;
        CGPoint p2 = rect.topRight;
        CGFloat width = hypotf(p1.x - p2.x, p1.y - p2.y);

        CGPoint p3 = rect.topLeft;
        CGPoint p4 = rect.bottomLeft;
        CGFloat height = hypotf(p3.x - p4.x, p3.y - p4.y);

        CGFloat currentHalfPerimiterValue = height + width;

        if (halfPerimiterValue < currentHalfPerimiterValue)
        {
            halfPerimiterValue = currentHalfPerimiterValue;
            biggestRectangle = rect;
        }
    }

    if (self.delegate) {
        [self.delegate didDetectRectangle:biggestRectangle withType:[self typeForRectangle:biggestRectangle]];
    }

    return biggestRectangle;
}

- (IPDFRectangeType) typeForRectangle: (CIRectangleFeature*) rectangle {
    if (fabs(rectangle.topRight.y - rectangle.topLeft.y) > 100 ||
        fabs(rectangle.topRight.x - rectangle.bottomRight.x) > 100 ||
        fabs(rectangle.topLeft.x - rectangle.bottomLeft.x) > 100 ||
        fabs(rectangle.bottomLeft.y - rectangle.bottomRight.y) > 100) {
        return IPDFRectangeTypeBadAngle;
    } else if ((_glkView.frame.origin.y + _glkView.frame.size.height) - rectangle.topLeft.y > 150 ||
               (_glkView.frame.origin.y + _glkView.frame.size.height) - rectangle.topRight.y > 150 ||
               _glkView.frame.origin.y - rectangle.bottomLeft.y > 150 ||
               _glkView.frame.origin.y - rectangle.bottomRight.y > 150) {
        return IPDFRectangeTypeTooFar;
    }
    return IPDFRectangeTypeGood;
}

BOOL rectangleDetectionConfidenceHighEnough(float confidence)
{
    return (confidence > 1.0);
}

#pragma mark - AVCapturePhotoCaptureDelegate

- (void)captureOutput:(AVCapturePhotoOutput *)output didFinishProcessingPhoto:(AVCapturePhoto *)photo error:(NSError *)error {
    __weak typeof(self) weakSelf = self;

    if (error) {
        NSLog(@"Error capturing photo: %@", error);
        _isCapturing = NO;
        [weakSelf hideGLKView:NO completion:nil];
        if (self.photoCaptureCompletionHandler) {
            self.photoCaptureCompletionHandler(nil, nil, nil);
            self.photoCaptureCompletionHandler = nil;
        }
        return;
    }

    // Get high quality image data
    NSData *imageData = [photo fileDataRepresentation];

    if (!imageData) {
        NSLog(@"Failed to get image data from photo");
        _isCapturing = NO;
        [weakSelf hideGLKView:NO completion:nil];
        if (self.photoCaptureCompletionHandler) {
            self.photoCaptureCompletionHandler(nil, nil, nil);
            self.photoCaptureCompletionHandler = nil;
        }
        return;
    }

    // Process image
    if (weakSelf.cameraViewType == IPDFCameraViewTypeBlackAndWhite || weakSelf.isBorderDetectionEnabled)
    {
        CIImage *enhancedImage = [CIImage imageWithData:imageData];

        if (weakSelf.cameraViewType == IPDFCameraViewTypeBlackAndWhite)
        {
            enhancedImage = [self filteredImageUsingEnhanceFilterOnImage:enhancedImage];
        }
        else
        {
            enhancedImage = [self filteredImageUsingContrastFilterOnImage:enhancedImage];
        }

        if (weakSelf.isBorderDetectionEnabled && rectangleDetectionConfidenceHighEnough(_imageDedectionConfidence))
        {
            CIRectangleFeature *rectangleFeature = [self biggestRectangleInRectangles:[[self highAccuracyRectangleDetector] featuresInImage:enhancedImage]];

            if (rectangleFeature)
            {
                enhancedImage = [self correctPerspectiveForImage:enhancedImage withFeatures:rectangleFeature];

                // Convert CIImage to UIImage with high quality using CIContext
                CIContext *ciContext = [CIContext contextWithOptions:@{kCIContextUseSoftwareRenderer: @(NO)}];

                // Apply rotation to match device orientation
                CGAffineTransform transform = CGAffineTransformMakeRotation(-M_PI_2);
                enhancedImage = [enhancedImage imageByApplyingTransform:transform];

                // Convert to CGImage first for better quality
                CGImageRef cgImage = [ciContext createCGImage:enhancedImage fromRect:enhancedImage.extent];
                UIImage *image = [UIImage imageWithCGImage:cgImage scale:1.0 orientation:UIImageOrientationUp];
                CGImageRelease(cgImage);

                UIImage *initialImage = [UIImage imageWithData:imageData];

                [weakSelf hideGLKView:NO completion:nil];
                _isCapturing = NO;

                if (self.photoCaptureCompletionHandler) {
                    self.photoCaptureCompletionHandler(image, initialImage, rectangleFeature);
                    self.photoCaptureCompletionHandler = nil;
                }
            }
        } else {
            [weakSelf hideGLKView:NO completion:nil];
            _isCapturing = NO;
            UIImage *initialImage = [UIImage imageWithData:imageData];

            if (self.photoCaptureCompletionHandler) {
                self.photoCaptureCompletionHandler(initialImage, initialImage, nil);
                self.photoCaptureCompletionHandler = nil;
            }
        }

    }
    else
    {
        [weakSelf hideGLKView:NO completion:nil];
        _isCapturing = NO;
        UIImage *initialImage = [UIImage imageWithData:imageData];

        if (self.photoCaptureCompletionHandler) {
            self.photoCaptureCompletionHandler(initialImage, initialImage, nil);
            self.photoCaptureCompletionHandler = nil;
        }
    }
}

@end
