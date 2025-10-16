import AVFoundation
import Foundation
import React
import Vision

@objc(RNRDocScannerView)
class RNRDocScannerView: UIView {
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
  private var previewLayer: AVCaptureVideoPreviewLayer?
  private var photoOutput = AVCapturePhotoOutput()

  private var currentStableCounter: Int = 0
  private var isCaptureInFlight = false

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
        session.addOutput(photoOutput)
      }

      // TODO: Wire up AVCaptureVideoDataOutput + rectangle detection pipeline.
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

  func handleDetectedRectangle(_ rectangle: VNRectangleObservation?, frameSize: CGSize) {
    guard let onRectangleDetect else { return }

    let payload: [String: Any?]
    if let rectangle {
      let points = [
        point(from: rectangle.topLeft, frameSize: frameSize),
        point(from: rectangle.topRight, frameSize: frameSize),
        point(from: rectangle.bottomRight, frameSize: frameSize),
        point(from: rectangle.bottomLeft, frameSize: frameSize),
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

  private func point(from normalizedPoint: CGPoint, frameSize: CGSize) -> CGPoint {
    CGPoint(x: normalizedPoint.x * frameSize.width, y: (1 - normalizedPoint.y) * frameSize.height)
  }

  func capture(completion: @escaping (Result<RNRDocScannerCaptureResult, Error>) -> Void) {
    sessionQueue.async { [weak self] in
      guard let self else { return }

      if isCaptureInFlight {
        completion(.failure(RNRDocScannerError.captureInProgress))
        return
      }

      guard photoOutput.connections.isEmpty == false else {
        completion(.failure(RNRDocScannerError.captureUnavailable))
        return
      }

      isCaptureInFlight = true

      // TODO: Implement real capture logic; emit stub callback for now.
      DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
        self.isCaptureInFlight = false
        completion(.failure(RNRDocScannerError.notImplemented))
      }
    }
  }

  func resetStability() {
    currentStableCounter = 0
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
  case notImplemented
  case viewNotFound

  var code: String {
    switch self {
    case .captureInProgress:
      return "capture_in_progress"
    case .captureUnavailable:
      return "capture_unavailable"
    case .notImplemented:
      return "not_implemented"
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
    case .notImplemented:
      return "Native capture is not implemented yet."
    case .viewNotFound:
      return "Unable to locate the native DocScanner view."
    }
  }
}
