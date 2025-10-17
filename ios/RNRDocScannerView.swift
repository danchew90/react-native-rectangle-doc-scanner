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
  private var smoothedOverlayPoints: [CGPoint]?
  private let outlineLayer = CAShapeLayer()
  private let gridLayer = CAShapeLayer()

  private var currentStableCounter: Int = 0
  private var isProcessingFrame = false
  private var isCaptureInFlight = false
  private var lastObservation: VNRectangleObservation?
  private var missedDetectionFrames: Int = 0
  private let maxMissedDetections = 1
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
    configureOverlayLayers()
    configureSession()
  }

  private func configurePreviewLayer() {
    let layer = AVCaptureVideoPreviewLayer(session: session)
    layer.videoGravity = .resizeAspectFill
    self.layer.insertSublayer(layer, at: 0)
    previewLayer = layer
  }

  private func configureOverlayLayers() {
    outlineLayer.strokeColor = UIColor(red: 0.18, green: 0.6, blue: 0.95, alpha: 1.0).cgColor
    outlineLayer.fillColor = UIColor(red: 0.18, green: 0.6, blue: 0.95, alpha: 0.2).cgColor
    outlineLayer.lineWidth = 4
    outlineLayer.lineJoin = .round
    outlineLayer.isHidden = true
    layer.addSublayer(outlineLayer)

    gridLayer.strokeColor = UIColor(red: 0.18, green: 0.6, blue: 0.95, alpha: 0.35).cgColor
    gridLayer.fillColor = UIColor.clear.cgColor
    gridLayer.lineWidth = 1.5
    gridLayer.lineJoin = .round
    gridLayer.isHidden = true
    gridLayer.zPosition = outlineLayer.zPosition + 1
    layer.addSublayer(gridLayer)
  }

  private func configureSession() {
    sessionQueue.async { [weak self] in
      guard let self else { return }

      session.beginConfiguration()
      session.sessionPreset = .high

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
        if let connection = photoOutput.connection(with: .video), connection.isVideoOrientationSupported {
          connection.videoOrientation = .portrait
        }
      }

      videoOutput.videoSettings = [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
      ]
      videoOutput.alwaysDiscardsLateVideoFrames = true
      videoOutput.setSampleBufferDelegate(self, queue: analysisQueue)

      if session.canAddOutput(videoOutput) {
        session.addOutput(videoOutput)
        if let connection = videoOutput.connection(with: .video), connection.isVideoOrientationSupported {
          connection.videoOrientation = .portrait
        }
      }
    }
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    previewLayer?.frame = bounds
    if let connection = previewLayer?.connection, connection.isVideoOrientationSupported {
      connection.videoOrientation = .portrait
    }
    outlineLayer.frame = bounds
    gridLayer.frame = bounds
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

    let requestHandler: VNRequestCompletionHandler = { [weak self] request, error in
      guard let self = self else { return }

      if let error = error {
        NSLog("[RNRDocScanner] detection error: \(error)")
        self.lastObservation = nil
        self.handleDetectedRectangle(nil, frameSize: frameSize)
        return
      }

      guard let observations = request.results as? [VNRectangleObservation], !observations.isEmpty else {
        self.lastObservation = nil
        self.handleDetectedRectangle(nil, frameSize: frameSize)
        return
      }

      let filtered = observations.filter { $0.confidence >= 0.55 }
      let candidates = filtered.isEmpty ? observations : filtered
      let weighted: [VNRectangleObservation] = candidates.sorted { (lhs: VNRectangleObservation, rhs: VNRectangleObservation) -> Bool in
        let lhsScore: CGFloat = CGFloat(lhs.confidence) * lhs.boundingBox.area
        let rhsScore: CGFloat = CGFloat(rhs.confidence) * rhs.boundingBox.area
        return lhsScore > rhsScore
      }

      guard let best = weighted.first else {
        self.lastObservation = nil
        self.handleDetectedRectangle(nil, frameSize: frameSize)
        return
      }

      self.lastObservation = best
      self.missedDetectionFrames = 0
      self.handleDetectedRectangle(best, frameSize: frameSize)
    }

    let request = VNDetectRectanglesRequest(completionHandler: requestHandler)

      request.maximumObservations = 3
      request.minimumConfidence = 0.65
      request.minimumAspectRatio = 0.12
      request.maximumAspectRatio = 1.9
      request.minimumSize = 0.05
      if #available(iOS 13.0, *) {
        request.quadratureTolerance = 18
      }

    var processedImage = CIImage(cvPixelBuffer: pixelBuffer)
    processedImage = processedImage.applyingFilter("CIColorControls", parameters: [
      kCIInputContrastKey: 1.35,
      kCIInputBrightnessKey: 0.02,
      kCIInputSaturationKey: 1.05,
    ])
    processedImage = processedImage.applyingFilter("CISharpenLuminance", parameters: [kCIInputSharpnessKey: 0.5])

    let handler = VNImageRequestHandler(ciImage: processedImage, orientation: orientation, options: [:])
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

    let effectiveObservation: VNRectangleObservation?
    if let rect = rectangle {
      effectiveObservation = rect
      lastObservation = rect
      missedDetectionFrames = 0
    } else if missedDetectionFrames < maxMissedDetections, let cached = lastObservation {
      missedDetectionFrames += 1
      effectiveObservation = cached
    } else {
      lastObservation = nil
      missedDetectionFrames = 0
      smoothedOverlayPoints = nil
      effectiveObservation = nil
    }

    let overlayStableThreshold = max(2, Int(truncating: detectionCountBeforeCapture) / 2)
    let payload: [String: Any?]

    if let observation = effectiveObservation {
      let points = [
        pointForOverlay(from: observation.topLeft, frameSize: frameSize),
        pointForOverlay(from: observation.topRight, frameSize: frameSize),
        pointForOverlay(from: observation.bottomRight, frameSize: frameSize),
        pointForOverlay(from: observation.bottomLeft, frameSize: frameSize),
      ]

      currentStableCounter = min(currentStableCounter + 1, Int(truncating: detectionCountBeforeCapture))

      let normalizedArea = observation.boundingBox.width * observation.boundingBox.height
      let meetsArea = normalizedArea >= 0.06 && normalizedArea <= 0.95
      let meetsConfidence = observation.confidence >= 0.65
      let shouldDisplayOverlay = currentStableCounter >= overlayStableThreshold && meetsArea && meetsConfidence
      updateNativeOverlay(with: shouldDisplayOverlay ? observation : nil)

      payload = [
        "rectangleCoordinates": shouldDisplayOverlay ? [
          "topLeft": ["x": points[0].x, "y": points[0].y],
          "topRight": ["x": points[1].x, "y": points[1].y],
          "bottomRight": ["x": points[2].x, "y": points[2].y],
          "bottomLeft": ["x": points[3].x, "y": points[3].y],
        ] : NSNull(),
        "stableCounter": currentStableCounter,
        "frameWidth": frameSize.width,
        "frameHeight": frameSize.height,
      ]
    } else {
      currentStableCounter = 0
      updateNativeOverlay(with: nil)
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

  private func updateNativeOverlay(with observation: VNRectangleObservation?) {
    DispatchQueue.main.async {
      guard let observation else {
        self.outlineLayer.path = nil
        self.gridLayer.path = nil
        self.outlineLayer.isHidden = true
        self.gridLayer.isHidden = true
        self.smoothedOverlayPoints = nil
        return
      }

      guard let previewLayer = self.previewLayer else {
        return
      }

      let rawPoints = [
        self.convertToLayerPoint(observation.topLeft, previewLayer: previewLayer),
        self.convertToLayerPoint(observation.topRight, previewLayer: previewLayer),
        self.convertToLayerPoint(observation.bottomRight, previewLayer: previewLayer),
        self.convertToLayerPoint(observation.bottomLeft, previewLayer: previewLayer),
      ]

      let orderedPoints = self.orderPoints(rawPoints)

      let points: [CGPoint]
      if let previous = self.smoothedOverlayPoints, previous.count == 4 {
        points = zip(previous, orderedPoints).map { prev, next in
          CGPoint(x: prev.x * 0.7 + next.x * 0.3, y: prev.y * 0.7 + next.y * 0.3)
        }
      } else {
        points = orderedPoints
      }

      self.smoothedOverlayPoints = points

      let outline = UIBezierPath()
      outline.move(to: points[0])
      outline.addLine(to: points[1])
      outline.addLine(to: points[2])
      outline.addLine(to: points[3])
      outline.close()

      self.outlineLayer.path = outline.cgPath
      self.outlineLayer.isHidden = false

      let gridPath = UIBezierPath()
      let steps: [CGFloat] = [1.0 / 3.0, 2.0 / 3.0]

      for step in steps {
        let startVertical = self.interpolate(points[0], points[1], t: step)
        let endVertical = self.interpolate(points[3], points[2], t: step)
        gridPath.move(to: startVertical)
        gridPath.addLine(to: endVertical)

        let startHorizontal = self.interpolate(points[0], points[3], t: step)
        let endHorizontal = self.interpolate(points[1], points[2], t: step)
        gridPath.move(to: startHorizontal)
        gridPath.addLine(to: endHorizontal)
      }

      self.gridLayer.path = gridPath.cgPath
      self.gridLayer.isHidden = false
    }
  }

  private func convertToLayerPoint(_ normalizedPoint: CGPoint, previewLayer: AVCaptureVideoPreviewLayer) -> CGPoint {
    let devicePoint = CGPoint(x: normalizedPoint.x, y: 1 - normalizedPoint.y)
    return previewLayer.layerPointConverted(fromCaptureDevicePoint: devicePoint)
  }

  private func interpolate(_ start: CGPoint, _ end: CGPoint, t: CGFloat) -> CGPoint {
    CGPoint(x: start.x + (end.x - start.x) * t, y: start.y + (end.y - start.y) * t)
  }

  private func orderPoints(_ points: [CGPoint]) -> [CGPoint] {
    guard points.count == 4 else { return points }

    var topLeft = points[0]
    var topRight = points[0]
    var bottomRight = points[0]
    var bottomLeft = points[0]

    var minSum = CGFloat.greatestFiniteMagnitude
    var maxSum = -CGFloat.greatestFiniteMagnitude
    var minDiff = CGFloat.greatestFiniteMagnitude
    var maxDiff = -CGFloat.greatestFiniteMagnitude

    for point in points {
      let sum = point.x + point.y
      if sum < minSum {
        minSum = sum
        topLeft = point
      }
      if sum > maxSum {
        maxSum = sum
        bottomRight = point
      }

      let diff = point.x - point.y
      if diff < minDiff {
        minDiff = diff
        bottomLeft = point
      }
      if diff > maxDiff {
        maxDiff = diff
        topRight = point
      }
    }

    var ordered = [topLeft, topRight, bottomRight, bottomLeft]
    if cross(ordered[0], ordered[1], ordered[2]) < 0 {
      ordered = [topLeft, bottomLeft, bottomRight, topRight]
    }
    return ordered
  }

  private func cross(_ a: CGPoint, _ b: CGPoint, _ c: CGPoint) -> CGFloat {
    let abx = b.x - a.x
    let aby = b.y - a.y
    let acx = c.x - a.x
    let acy = c.y - a.y
    return abx * acy - aby * acx
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

private extension CGRect {
  var area: CGFloat {
    width * height
  }
}
