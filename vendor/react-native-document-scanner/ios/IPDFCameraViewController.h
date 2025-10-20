//
//  IPDFCameraViewController.h
//  InstaPDF
//
//  Created by Maximilian Mackh on 06/01/15.
//  Copyright (c) 2015 mackh ag. All rights reserved.
//

#import <UIKit/UIKit.h>

@class CIRectangleFeature;

typedef NS_ENUM(NSInteger, IPDFCameraViewType)
{
    IPDFCameraViewTypeBlackAndWhite,
    IPDFCameraViewTypeNormal
};

typedef NS_ENUM(NSInteger, IPDFRectangeType)
{
    IPDFRectangeTypeGood,
    IPDFRectangeTypeBadAngle,
    IPDFRectangeTypeTooFar
};

@protocol IPDFCameraViewControllerDelegate <NSObject>

- (void)didDetectRectangle:(CIRectangleFeature *)rectangle withType:(IPDFRectangeType)type;

@optional
- (void)cameraViewController:(id)controller
         didDetectRectangle:(CIRectangleFeature *)rectangle
                   withType:(IPDFRectangeType)type
            viewCoordinates:(NSDictionary *)viewCoordinates
                  imageSize:(CGSize)imageSize;

@end

@interface IPDFCameraViewController : UIView

- (void)setupCameraView;

- (void)start;
- (void)stop;

@property (nonatomic, assign, getter=isBorderDetectionEnabled) BOOL enableBorderDetection;
@property (nonatomic, assign, getter=isTorchEnabled) BOOL enableTorch;
@property (nonatomic, assign, getter=isFrontCam) BOOL useFrontCam;

@property (weak, nonatomic) id<IPDFCameraViewControllerDelegate> delegate;

@property (nonatomic, assign) IPDFCameraViewType cameraViewType;

- (void)focusAtPoint:(CGPoint)point completionHandler:(void(^)(void))completionHandler;

- (void)captureImageWithCompletionHander:(void(^)(UIImage *data, UIImage *initialData, CIRectangleFeature *rectangleFeature))completionHandler;

@property (nonatomic, strong) UIColor *overlayColor;
@property (nonatomic, assign) float saturation;
@property (nonatomic, assign) float contrast;
@property (nonatomic, assign) float brightness;
@property (nonatomic, assign) NSInteger detectionRefreshRateInMS;

@end
