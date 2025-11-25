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

static inline void dispatch_async_main_queue(dispatch_block_t block)
{
    if (!block) {
        return;
    }

    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

@interface IPDFCameraViewController () <AVCaptureVideoDataOutputSampleBufferDelegate, AVCapturePhotoCaptureDelegate>

@property (nonatomic,strong) AVCaptureSession *captureSession;
@property (nonatomic,strong) AVCaptureDevice *captureDevice;
@property (nonatomic,strong) EAGLContext *context;

@property (nonatomic, strong) AVCaptureStillImageOutput* stillImageOutput; // Kept for backward compatibility

@property (nonatomic, assign) BOOL forceStop;
@property (nonatomic, assign) float lastDetectionRate;

@property (nonatomic, copy) void (^captureCompletionHandler)(UIImage *, UIImage *, CIRectangleFeature *);

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

- (void)completeCaptureWithCroppedImage:(UIImage *)croppedImage
                            initialImage:(UIImage *)initialImage
                               rectangle:(CIRectangleFeature *)rectangleFeature
                                   error:(NSError *)error
{
    void (^completionHandler)(UIImage *, UIImage *, CIRectangleFeature *) = self.captureCompletionHandler;

    dispatch_async_main_queue(^{
        if (error) {
            NSLog(@"[IPDFCameraViewController] Completing capture with error: %@", error.localizedDescription);
            if (completionHandler) {
                completionHandler(nil, nil, nil);
            }
        } else {
            UIImage *resolvedInitial = initialImage ?: croppedImage;
            UIImage *resolvedCropped = croppedImage ?: resolvedInitial;

            if (!resolvedInitial || !resolvedCropped) {
                NSLog(@"[IPDFCameraViewController] Missing images during completion, sending failure to JS");
                if (completionHandler) {
                    completionHandler(nil, nil, nil);
                }
            } else if (completionHandler) {
                completionHandler(resolvedCropped, resolvedInitial, rectangleFeature);
            }
        }

        self.captureCompletionHandler = nil;
        self->_isCapturing = NO;
        [self hideGLKView:NO completion:nil];
    });
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

    // If the session was stopped while the app was backgrounded, bring it back
    if (self.captureSession && !self.captureSession.isRunning) {
        NSLog(@"[IPDFCameraViewController] Foreground - restarting capture session");
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
            [self.captureSession startRunning];
        });
    }
}

- (void)dealloc
{
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

- (void)layoutSubviews
{
    [super layoutSubviews];

    // Update GLKView frame to match parent bounds
    if (_glkView) {
        _glkView.frame = self.bounds;
    }
}

- (void)createGLKView
{
    if (self.context) return;

    NSLog(@"[IPDFCamera] createGLKView - self.bounds: %@", NSStringFromCGRect(self.bounds));
    self.context = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES2];
    GLKView *view = [[GLKView alloc] initWithFrame:self.bounds];
    view.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    view.translatesAutoresizingMaskIntoConstraints = YES;
    view.context = self.context;
    view.contentScaleFactor = [UIScreen mainScreen].scale;
    view.drawableDepthFormat = GLKViewDrawableDepthFormat24;
    [self insertSubview:view atIndex:0];
    _glkView = view;
    NSLog(@"[IPDFCamera] createGLKView - created GLKView with frame: %@", NSStringFromCGRect(view.frame));
    glGenRenderbuffers(1, &_renderBuffer);
    glBindRenderbuffer(GL_RENDERBUFFER, _renderBuffer);
    _coreImageContext = [CIContext contextWithEAGLContext:self.context];
    [EAGLContext setCurrentContext:self.context];
}

- (void)setupCameraView
{
    [self createGLKView];

    // Explicitly set GLKView frame to match current bounds
    if (_glkView) {
        NSLog(@"[IPDFCamera] setupCameraView - setting _glkView.frame to self.bounds: %@", NSStringFromCGRect(self.bounds));
        _glkView.frame = self.bounds;
        NSLog(@"[IPDFCamera] setupCameraView - _glkView.frame is now: %@", NSStringFromCGRect(_glkView.frame));
    }

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
    session.sessionPreset = AVCaptureSessionPresetPhoto;
    [session addInput:input];

    AVCaptureVideoDataOutput *dataOutput = [[AVCaptureVideoDataOutput alloc] init];
    [dataOutput setAlwaysDiscardsLateVideoFrames:YES];
    [dataOutput setVideoSettings:@{(id)kCVPixelBufferPixelFormatTypeKey:@(kCVPixelFormatType_32BGRA)}];
    [dataOutput setSampleBufferDelegate:self queue:dispatch_get_main_queue()];
    [session addOutput:dataOutput];

    // Use legacy AVCaptureStillImageOutput for reliable manual captures on all supported iOS versions
    self.stillImageOutput = [[AVCaptureStillImageOutput alloc] init];
    if ([session canAddOutput:self.stillImageOutput]) {
        [session addOutput:self.stillImageOutput];
        NSLog(@"[IPDFCamera] Using AVCaptureStillImageOutput (manual capture)");
    } else {
        NSLog(@"[IPDFCamera] CRITICAL ERROR: Cannot add AVCaptureStillImageOutput to session");
        self.stillImageOutput = nil;
    }

    AVCaptureConnection *connection = [dataOutput.connections firstObject];
    [connection setVideoOrientation:AVCaptureVideoOrientationPortrait];

    // Restart capture session automatically after media services/interruption events
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(handleSessionRuntimeError:)
                                                 name:AVCaptureSessionRuntimeErrorNotification
                                               object:session];

    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(handleSessionInterruptionEnded:)
                                                 name:AVCaptureSessionInterruptionEndedNotification
                                               object:session];

    if (device.isFlashAvailable)
    {
        [device lockForConfiguration:nil];
        [device setFlashMode:AVCaptureFlashModeOff];
        [device unlockForConfiguration];

        if ([device isFocusModeSupported:AVCaptureFocusModeContinuousAutoFocus])
        {
            [device lockForConfiguration:nil];
            [device setFocusMode:AVCaptureFocusModeContinuousAutoFocus];
            [device unlockForConfiguration];
        }
    }

    [session commitConfiguration];
}

- (void)handleSessionRuntimeError:(NSNotification *)notification
{
    NSError *error = notification.userInfo[AVCaptureSessionErrorKey];
    NSLog(@"[IPDFCameraViewController] Session runtime error: %@", error);

    // Common case: AVErrorMediaServicesWereReset requires restarting the session
    if (error.code == AVErrorMediaServicesWereReset) {
        [self restartCaptureSession:@"Media services were reset"];
    }
}

- (void)handleSessionInterruptionEnded:(NSNotification *)notification
{
    [self restartCaptureSession:@"Interruption ended"];
}

- (void)restartCaptureSession:(NSString *)reason
{
    if (!self.captureSession || self.captureSession.isRunning) {
        return;
    }

    NSLog(@"[IPDFCameraViewController] Restarting capture session (%@)", reason);
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        [self.captureSession startRunning];
    });
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

    if (self.context && _coreImageContext && _glkView)
    {
        // Calculate the rect to draw the image with aspect fill
        CGRect viewBounds = _glkView.bounds;
        CGRect imageExtent = image.extent;

        // Calculate aspect ratios
        CGFloat imageAspect = imageExtent.size.width / imageExtent.size.height;
        CGFloat viewAspect = viewBounds.size.width / viewBounds.size.height;

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

        // GLKView renderbuffer expects pixel dimensions, not point-based bounds
        CGRect drawRect = CGRectMake(0,
                                     0,
                                     (CGFloat)_glkView.drawableWidth,
                                     (CGFloat)_glkView.drawableHeight);

        if (self.delegate && _borderDetectLastRectangleFeature) {
            IPDFRectangeType detectionType = [self typeForRectangle:_borderDetectLastRectangleFeature];

            if ([self.delegate respondsToSelector:@selector(didDetectRectangle:withType:)]) {
                [self.delegate didDetectRectangle:_borderDetectLastRectangleFeature withType:detectionType];
            }

            if ([self.delegate respondsToSelector:@selector(cameraViewController:didDetectRectangle:withType:viewCoordinates:imageSize:)]) {
                NSDictionary *viewCoordinates = [self viewCoordinateDictionaryForRectangleFeature:_borderDetectLastRectangleFeature
                                                                                          fromRect:fromRect
                                                                                          drawRect:drawRect];
                [self.delegate cameraViewController:self
                               didDetectRectangle:_borderDetectLastRectangleFeature
                                         withType:detectionType
                                  viewCoordinates:viewCoordinates
                                        imageSize:imageExtent.size];
            }
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
    NSLog(@"[IPDFCameraViewController] captureImageWithCompletionHander called, _isCapturing=%@", _isCapturing ? @"YES" : @"NO");

    if (_isCapturing) {
        NSLog(@"[IPDFCameraViewController] Already capturing, ignoring request");
        return;
    }

    if (!completionHandler) {
        NSLog(@"[IPDFCameraViewController] ERROR: No completion handler provided");
        return;
    }

    if (!self.captureSession || !self.captureSession.isRunning) {
        NSLog(@"[IPDFCameraViewController] ERROR: captureSession is not running");
        NSError *error = [NSError errorWithDomain:@"IPDFCameraViewController"
                                             code:-200
                                         userInfo:@{ NSLocalizedDescriptionKey: @"capture_session_not_running" }];
        [self completeCaptureWithCroppedImage:nil initialImage:nil rectangle:nil error:error];
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
    self.captureCompletionHandler = completionHandler;

    if (!self.stillImageOutput) {
        NSLog(@"[IPDFCameraViewController] ERROR: stillImageOutput is nil");
        NSError *error = [NSError errorWithDomain:@"IPDFCameraViewController"
                                             code:-201
                                         userInfo:@{ NSLocalizedDescriptionKey: @"missing_still_image_output" }];
        [self completeCaptureWithCroppedImage:nil initialImage:nil rectangle:nil error:error];
        return;
    }

    AVCaptureConnection *videoConnection = nil;
    for (AVCaptureConnection *connection in self.stillImageOutput.connections)
    {
        for (AVCaptureInputPort *port in [connection inputPorts])
        {
            if ([[port mediaType] isEqual:AVMediaTypeVideo] )
            {
                videoConnection = connection;
                break;
            }
        }
        if (videoConnection) break;
    }

    if (!videoConnection) {
        NSLog(@"[IPDFCameraViewController] ERROR: No video connection found");
        NSError *error = [NSError errorWithDomain:@"IPDFCameraViewController"
                                             code:-202
                                         userInfo:@{ NSLocalizedDescriptionKey: @"no_video_connection" }];
        [self completeCaptureWithCroppedImage:nil initialImage:nil rectangle:nil error:error];
        return;
    }

    if (videoConnection.isVideoOrientationSupported) {
        videoConnection.videoOrientation = AVCaptureVideoOrientationPortrait;
    }

    NSLog(@"[IPDFCameraViewController] Capturing image via AVCaptureStillImageOutput");
    [self.stillImageOutput captureStillImageAsynchronouslyFromConnection:videoConnection completionHandler:^(CMSampleBufferRef imageSampleBuffer, NSError *error) {
        [weakSelf handleCapturedImageData:imageSampleBuffer error:error];
    }];
}

// Helper method for legacy AVCaptureStillImageOutput
- (void)handleCapturedImageData:(CMSampleBufferRef)sampleBuffer error:(NSError *)error {
    NSLog(@"[IPDFCameraViewController] handleCapturedImageData called (legacy), error=%@, buffer=%@", error, sampleBuffer ? @"YES" : @"NO");

    if (error) {
        NSLog(@"[IPDFCameraViewController] ERROR capturing image: %@", error);
        [self completeCaptureWithCroppedImage:nil initialImage:nil rectangle:nil error:error];
        return;
    }

    if (!sampleBuffer) {
        NSLog(@"[IPDFCameraViewController] ERROR: sampleBuffer is nil");
        NSError *bufferError = [NSError errorWithDomain:@"IPDFCameraViewController"
                                                  code:-206
                                              userInfo:@{ NSLocalizedDescriptionKey: @"sample_buffer_nil" }];
        [self completeCaptureWithCroppedImage:nil initialImage:nil rectangle:nil error:bufferError];
        return;
    }

    // iOS < 10: Use AVCaptureStillImageOutput's method
    NSData *imageData = [AVCaptureStillImageOutput jpegStillImageNSDataRepresentation:sampleBuffer];

    if (!imageData) {
        NSLog(@"[IPDFCameraViewController] ERROR: Failed to create image data from sample buffer (legacy)");
        NSError *dataError = [NSError errorWithDomain:@"IPDFCameraViewController"
                                                code:-207
                                            userInfo:@{ NSLocalizedDescriptionKey: @"legacy_sample_conversion_failed" }];
        [self completeCaptureWithCroppedImage:nil initialImage:nil rectangle:nil error:dataError];
        return;
    }

    NSLog(@"[IPDFCameraViewController] Got image data from still image output (legacy), size: %lu bytes", (unsigned long)imageData.length);
    [self processImageData:imageData];
}

- (void)processImageData:(NSData *)imageData {
    NSLog(@"[IPDFCameraViewController] processImageData called, imageData size: %lu bytes", (unsigned long)imageData.length);

    if (!imageData || imageData.length == 0) {
        NSError *dataError = [NSError errorWithDomain:@"IPDFCameraViewController"
                                                code:-208
                                            userInfo:@{ NSLocalizedDescriptionKey: @"empty_image_data" }];
        [self completeCaptureWithCroppedImage:nil initialImage:nil rectangle:nil error:dataError];
        return;
    }

    UIImage *initialImage = [UIImage imageWithData:imageData];
    if (!initialImage) {
        NSError *conversionError = [NSError errorWithDomain:@"IPDFCameraViewController"
                                                       code:-209
                                                   userInfo:@{ NSLocalizedDescriptionKey: @"initial_image_conversion_failed" }];
        [self completeCaptureWithCroppedImage:nil initialImage:nil rectangle:nil error:conversionError];
        return;
    }

    UIImage *croppedImage = initialImage;
    CIRectangleFeature *rectangleFeature = nil;

    BOOL shouldEnhance = (self.cameraViewType == IPDFCameraViewTypeBlackAndWhite) || self.isBorderDetectionEnabled;
    if (shouldEnhance) {
        CIImage *processedImage = [CIImage imageWithData:imageData];
        if (!processedImage) {
            NSLog(@"[IPDFCameraViewController] Unable to create CIImage from data, returning original image");
        } else {
            if (self.cameraViewType == IPDFCameraViewTypeBlackAndWhite) {
                processedImage = [self filteredImageUsingEnhanceFilterOnImage:processedImage];
            } else {
                processedImage = [self filteredImageUsingContrastFilterOnImage:processedImage];
            }

            if (self.isBorderDetectionEnabled && rectangleDetectionConfidenceHighEnough(_imageDedectionConfidence)) {
                CIRectangleFeature *detectedRectangle = [self biggestRectangleInRectangles:[[self highAccuracyRectangleDetector] featuresInImage:processedImage]];

                if (detectedRectangle) {
                    rectangleFeature = detectedRectangle;
                    CIImage *correctedImage = [self correctPerspectiveForImage:processedImage withFeatures:detectedRectangle];

                    UIGraphicsBeginImageContext(CGSizeMake(correctedImage.extent.size.height, correctedImage.extent.size.width));
                    [[UIImage imageWithCIImage:correctedImage scale:1.0 orientation:UIImageOrientationRight] drawInRect:CGRectMake(0, 0, correctedImage.extent.size.height, correctedImage.extent.size.width)];
                    UIImage *perspectiveCorrectedImage = UIGraphicsGetImageFromCurrentImageContext();
                    UIGraphicsEndImageContext();

                    if (perspectiveCorrectedImage) {
                        croppedImage = perspectiveCorrectedImage;
                    } else {
                        NSLog(@"[IPDFCameraViewController] Failed to create perspective corrected image, using original");
                    }
                } else {
                    NSLog(@"[IPDFCameraViewController] No rectangle detected during manual capture, returning original image");
                }
            }
        }
    }

    [self completeCaptureWithCroppedImage:croppedImage initialImage:initialImage rectangle:rectangleFeature error:nil];
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

- (NSDictionary *)viewCoordinateDictionaryForRectangleFeature:(CIRectangleFeature *)rectangle
                                                     fromRect:(CGRect)fromRect
                                                     drawRect:(CGRect)drawRect
{
    if (!rectangle || !_glkView) {
        return nil;
    }

    if (CGRectIsEmpty(fromRect) || CGRectIsEmpty(drawRect)) {
        return nil;
    }

    CGFloat scaleFactor = _glkView.contentScaleFactor;
    if (scaleFactor <= 0.0) {
        scaleFactor = [UIScreen mainScreen].scale;
    }
    if (scaleFactor <= 0.0) {
        scaleFactor = 1.0;
    }

    CGFloat scaleX = drawRect.size.width / fromRect.size.width;
    CGFloat scaleY = drawRect.size.height / fromRect.size.height;

    CGPoint (^convertPoint)(CGPoint) = ^CGPoint(CGPoint point) {
        CGFloat translatedX = (point.x - fromRect.origin.x) * scaleX;
        CGFloat translatedY = (point.y - fromRect.origin.y) * scaleY;
        CGFloat flippedY = drawRect.size.height - translatedY;

        return CGPointMake(translatedX / scaleFactor, flippedY / scaleFactor);
    };

    CGPoint topLeft = convertPoint(rectangle.topLeft);
    CGPoint topRight = convertPoint(rectangle.topRight);
    CGPoint bottomLeft = convertPoint(rectangle.bottomLeft);
    CGPoint bottomRight = convertPoint(rectangle.bottomRight);

    return @{
        @"topLeft": @{@"x": @(topLeft.x), @"y": @(topLeft.y)},
        @"topRight": @{@"x": @(topRight.x), @"y": @(topRight.y)},
        @"bottomLeft": @{@"x": @(bottomLeft.x), @"y": @(bottomLeft.y)},
        @"bottomRight": @{@"x": @(bottomRight.x), @"y": @(bottomRight.y)}
    };
}

BOOL rectangleDetectionConfidenceHighEnough(float confidence)
{
    return (confidence > 1.0);
}

@end
