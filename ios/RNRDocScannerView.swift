import AVFoundation
import CoreImage
import Foundation
import React
import UIKit
import Vision

@objc(RNRDocScannerView)
class RNRDocScannerView: UIView, AVCaptureVideoDataOutputSampleBufferDelegate, AVCapturePhotoCaptureDelegate {
  @objc var detectionCountBeforeCapture: NSNumber = 8
  @objc var autoCapture: Bool = true
  @objc var enableTorch: Bool = false {
    didSet {
      updateTorchMode()
    }
  }
  @objc var quality: NSNumber = 90
  @objc var useBase64: Bool = false

  @objc var onRectangleDetect: RCTDirectEventBlock?
  @objc var onPictureTaken: RCTDirectEventBlock?

  private let session = AVCaptureSession()
  private let sessionQueue = DispatchQueue(label: "com.reactnative.rectangledocscanner.session")
  private let analysisQueue = DispatchQueue(label: "com.reactnative.rectangledocscanner.analysis")
  private let ciContext = CIContext()

  private var previewLayer: AVCaptureVideoPreviewLayer?
  private let videoOutput = AVCaptureVideoDataOutput()
  private let photoOutput = AVCapturePhotoOutput()

  private var currentStableCounter: Int = 0
  private var isProcessingFrame = false
  private var isCaptureInFlight = false
  private var lastObservation: VNRectangleObservation?
  private var lastFrameSize: CGSize = .zero
  private var photoCaptureCompletion: ((Result<RNRDocScannerCaptureResult, Error>) -> Void)?

  override init(frame: CGRect) {
    super.init(frame: frame)
    commonInit()
  }

  required init?(coder: NSCoder) {
    super.init(coder: coder)
    commonInit()
  }

  private func commonInit() {
    backgroundColor = .black
    configurePreviewLayer()
    configureSession()
  }

  private func configurePreviewLayer() {
    let layer = AVCaptureVideoPreviewLayer(session: session)
    layer.videoGravity = .resizeAspectFill
    self.layer.insertSublayer(layer, at: 0)
    previewLayer = layer
  }

  private func configureSession() {
    sessionQueue.async { [weak self] in
      guard let self else { return }

      session.beginConfiguration()
      session.sessionPreset = .photo

      defer {
        session.commitConfiguration()
        if !session.isRunning {
          session.startRunning()
        }
      }

      guard
        let videoDevice = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
        let videoInput = try? AVCaptureDeviceInput(device: videoDevice),
        session.canAddInput(videoInput)
      else {
        NSLog("[RNRDocScanner] Unable to create AVCaptureDeviceInput")
        return
      }

      session.addInput(videoInput)

      if session.canAddOutput(photoOutput) {
        photoOutput.isHighResolutionCaptureEnabled = true
        session.addOutput(photoOutput)
      }

      videoOutput.videoSettings = [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
      ]
      videoOutput.alwaysDiscardsLateVideoFrames = true
      videoOutput.setSampleBufferDelegate(self, queue: analysisQueue)

      if session.canAddOutput(videoOutput) {
        session.addOutput(videoOutput)
      }
    }
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    previewLayer?.frame = bounds
  }

  private func updateTorchMode() {
    sessionQueue.async { [weak self] in
      guard
        let self,
        let device = self.videoDevice(for: .back),
        device.hasTorch
      else {
        return
      }

      do {
        try device.lockForConfiguration()
        device.torchMode = self.enableTorch ? .on : .off
        device.unlockForConfiguration()
      } catch {
        NSLog("[RNRDocScanner] Failed to update torch mode: \(error)")
      }
    }
  }

  private func videoDevice(for position: AVCaptureDevice.Position) -> AVCaptureDevice? {
    if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) {
      return device
    }
    return AVCaptureDevice.devices(for: .video).first(where: { $0.position == position })
  }

  // MARK: - Detection

  func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
    if isProcessingFrame {
      return
    }
    guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
      return
    }

    isProcessingFrame = true
    CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
    let frameSize = CGSize(width: CVPixelBufferGetWidth(pixelBuffer), height: CVPixelBufferGetHeight(pixelBuffer))
    lastFrameSize = frameSize
    let orientation = currentExifOrientation()

    defer {
      CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly)
      isProcessingFrame = false
    }

    let request = VNDetectRectanglesRequest { [weak self] request, error in
      guard let self else { return }

      if let error {
        NSLog("[RNRDocScanner] detection error: \(error)")
        self.lastObservation = nil
        self.handleDetectedRectangle(nil, frameSize: frameSize)
        return
      }

      guard let observation = (request.results as? [VNRectangleObservation])?.first else {
        self.lastObservation = nil
        self.handleDetectedRectangle(nil, frameSize: frameSize)
        return
      }

      self.lastObservation = observation
      self.handleDetectedRectangle(observation, frameSize: frameSize)
    }

    request.maximumObservations = 1
    request.minimumConfidence = 0.6
    request.minimumAspectRatio = 0.3
    request.maximumAspectRatio = 1.0
    request.minimumSize = 0.15

    let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: orientation, options: [:])
    do {
      try handler.perform([request])
    } catch {
      NSLog("[RNRDocScanner] Failed to run Vision request: \(error)")
      lastObservation = nil
      handleDetectedRectangle(nil, frameSize: frameSize)
    }
  }

  func handleDetectedRectangle(_ rectangle: VNRectangleObservation?, frameSize: CGSize) {
    guard let onRectangleDetect else { return }

    let payload: [String: Any?]
    if let rectangle {
      let points = [
        pointForOverlay(from: rectangle.topLeft, frameSize: frameSize),
        pointForOverlay(from: rectangle.topRight, frameSize: frameSize),
        pointForOverlay(from: rectangle.bottomRight, frameSize: frameSize),
        pointForOverlay(from: rectangle.bottomLeft, frameSize: frameSize),
      ]

      currentStableCounter = min(currentStableCounter + 1, Int(truncating: detectionCountBeforeCapture))
      payload = [
        "rectangleCoordinates": [
          "topLeft": ["x": points[0].x, "y": points[0].y],
          "topRight": ["x": points[1].x, "y": points[1].y],
          "bottomRight": ["x": points[2].x, "y": points[2].y],
          "bottomLeft": ["x": points[3].x, "y": points[3].y],
        ],
        "stableCounter": currentStableCounter,
        "frameWidth": frameSize.width,
        "frameHeight": frameSize.height,
      ]
    } else {
      currentStableCounter = 0
      payload = [
        "rectangleCoordinates": NSNull(),
        "stableCounter": currentStableCounter,
        "frameWidth": frameSize.width,
        "frameHeight": frameSize.height,
      ]
    }

    DispatchQueue.main.async {
      onRectangleDetect(payload.compactMapValues { $0 })
    }
  }

  private func pointForOverlay(from normalizedPoint: CGPoint, frameSize: CGSize) -> CGPoint {
    CGPoint(x: normalizedPoint.x * frameSize.width, y: (1 - normalizedPoint.y) * frameSize.height)
  }

  // MARK: - Capture

  func capture(completion: @escaping (Result<RNRDocScannerCaptureResult, Error>) -> Void) {
    sessionQueue.async { [weak self] in
      guard let self else { return }

      if isCaptureInFlight {
        completion(.failure(RNRDocScannerError.captureInProgress))
        return
      }

      guard photoOutput.connection(with: .video) != nil else {
        completion(.failure(RNRDocScannerError.captureUnavailable))
        return
      }

      isCaptureInFlight = true
      photoCaptureCompletion = completion

      let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
      settings.isHighResolutionPhotoEnabled = photoOutput.isHighResolutionCaptureEnabled
      if photoOutput.supportedFlashModes.contains(.on) {
        settings.flashMode = enableTorch ? .on : .off
      }

      photoOutput.capturePhoto(with: settings, delegate: self)
    }
  }

  func resetStability() {
    currentStableCounter = 0
    lastObservation = nil
  }

  // MARK: - AVCapturePhotoCaptureDelegate

  func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
    guard let completion = photoCaptureCompletion else {
      isCaptureInFlight = false
      return
    }

    if let error {
      finishCapture(result: .failure(error))
      return
    }

    guard let data = photo.fileDataRepresentation() else {
      finishCapture(result: .failure(RNRDocScannerError.imageCreationFailed))
      return
    }

    let dimensions = photoDimensions(photo: photo)
    do {
      let original = try serializeImageData(data, suffix: "original")
      let croppedString: String?

      if let croppedData = generateCroppedImage(from: data) {
        croppedString = try serializeImageData(croppedData, suffix: "cropped").string
      } else {
        croppedString = original.string
      }

      let result = RNRDocScannerCaptureResult(
        croppedImage: croppedString,
        originalImage: original.string,
        width: dimensions.width,
        height: dimensions.height
      )

      finishCapture(result: .success(result))
    } catch {
      finishCapture(result: .failure(error))
    }
  }

  func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
    if let error, isCaptureInFlight {
      finishCapture(result: .failure(error))
    }
  }

  private func finishCapture(result: Result<RNRDocScannerCaptureResult, Error>) {
    let completion = photoCaptureCompletion
    photoCaptureCompletion = nil
    isCaptureInFlight = false

    DispatchQueue.main.async {
      switch result {
      case let .success(payload):
        completion?(.success(payload))
        self.emitPictureTaken(payload)
      case let .failure(error):
        completion?(.failure(error))
      }
    }
  }

  private func emitPictureTaken(_ result: RNRDocScannerCaptureResult) {
    guard let onPictureTaken else { return }
    let payload: [String: Any] = [
      "croppedImage": result.croppedImage ?? NSNull(),
      "initialImage": result.originalImage,
      "width": result.width,
      "height": result.height,
    ]
    onPictureTaken(payload)
  }

  // MARK: - Helpers

  private func currentExifOrientation() -> CGImagePropertyOrientation {
    switch UIDevice.current.orientation {
    case .landscapeLeft:
      return .up
    case .landscapeRight:
      return .down
    case .portraitUpsideDown:
      return .left
    default:
      return .right
    }
  }

  private func photoDimensions(photo: AVCapturePhoto) -> CGSize {
    if let pixelBuffer = photo.pixelBuffer {
      return CGSize(width: CVPixelBufferGetWidth(pixelBuffer), height: CVPixelBufferGetHeight(pixelBuffer))
    }

    let width = photo.metadata[kCGImagePropertyPixelWidth as String] as? Int ?? Int(lastFrameSize.width)
    let height = photo.metadata[kCGImagePropertyPixelHeight as String] as? Int ?? Int(lastFrameSize.height)
    return CGSize(width: CGFloat(width), height: CGFloat(height))
  }

  private func serializeImageData(_ data: Data, suffix: String) throws -> (string: String, url: URL?) {
    let filename = "docscan-\(UUID().uuidString)-\(suffix).jpg"
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
    do {
      try data.write(to: url, options: .atomic)
    } catch {
      throw RNRDocScannerError.fileWriteFailed
    }
    return (url.absoluteString, url)
  }

  private func generateCroppedImage(from data: Data) -> Data? {
    guard let ciImage = CIImage(data: data) else {
      return nil
    }

    var observation: VNRectangleObservation? = nil
    let request = VNDetectRectanglesRequest { request, _ in
      observation = (request.results as? [VNRectangleObservation])?.first
    }
    request.maximumObservations = 1
    request.minimumConfidence = 0.6

    let handler = VNImageRequestHandler(ciImage: ciImage, options: [:])
    try? handler.perform([request])

    guard let targetObservation = observation ?? lastObservation else {
      return nil
    }

    let size = ciImage.extent.size
    let topLeft = normalizedPoint(targetObservation.topLeft, in: size, flipY: false)
    let topRight = normalizedPoint(targetObservation.topRight, in: size, flipY: false)
    let bottomLeft = normalizedPoint(targetObservation.bottomLeft, in: size, flipY: false)
    let bottomRight = normalizedPoint(targetObservation.bottomRight, in: size, flipY: false)

    guard let filter = CIFilter(name: "CIPerspectiveCorrection") else {
      return nil
    }

    filter.setValue(ciImage, forKey: kCIInputImageKey)
    filter.setValue(CIVector(cgPoint: topLeft), forKey: "inputTopLeft")
    filter.setValue(CIVector(cgPoint: topRight), forKey: "inputTopRight")
    filter.setValue(CIVector(cgPoint: bottomLeft), forKey: "inputBottomLeft")
    filter.setValue(CIVector(cgPoint: bottomRight), forKey: "inputBottomRight")

    guard let corrected = filter.outputImage else {
      return nil
    }

    guard let cgImage = ciContext.createCGImage(corrected, from: corrected.extent) else {
      return nil
    }

    let cropped = UIImage(cgImage: cgImage)
    return cropped.jpegData(compressionQuality: CGFloat(max(0.05, min(1.0, quality.doubleValue / 100.0))))
  }

  private func normalizedPoint(_ point: CGPoint, in size: CGSize, flipY: Bool) -> CGPoint {
    let yValue = flipY ? (1 - point.y) : point.y
    return CGPoint(x: point.x * size.width, y: yValue * size.height)
  }
}

struct RNRDocScannerCaptureResult {
  let croppedImage: String?
  let originalImage: String
  let width: CGFloat
  let height: CGFloat
}

enum RNRDocScannerError: Error {
  case captureInProgress
  case captureUnavailable
  case imageCreationFailed
  case fileWriteFailed
  case viewNotFound

  var code: String {
    switch self {
    case .captureInProgress:
      return "capture_in_progress"
    case .captureUnavailable:
      return "capture_unavailable"
    case .imageCreationFailed:
      return "image_creation_failed"
    case .fileWriteFailed:
      return "file_write_failed"
    case .viewNotFound:
      return "view_not_found"
    }
  }

  var message: String {
    switch self {
    case .captureInProgress:
      return "A capture request is already in flight."
    case .captureUnavailable:
      return "Photo output is not configured yet."
    case .imageCreationFailed:
      return "Unable to create image data from capture."
    case .fileWriteFailed:
      return "Failed to persist captured image to disk."
    case .viewNotFound:
      return "Unable to locate the native DocScanner view."
    }
  }
}
