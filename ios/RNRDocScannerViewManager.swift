import Foundation
import React

@objc(RNRDocScannerViewManager)
class RNRDocScannerViewManager: RCTViewManager {
  override static func requiresMainQueueSetup() -> Bool {
    true
  }

  override func view() -> UIView! {
    RNRDocScannerView()
  }

  @objc func capture(_ reactTag: NSNumber, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    bridge.uiManager.addUIBlock { _, viewRegistry in
      guard let view = viewRegistry?[reactTag] as? RNRDocScannerView else {
        reject(RNRDocScannerError.viewNotFound.code, RNRDocScannerError.viewNotFound.message, nil)
        return
      }

      view.capture { result in
        switch result {
        case let .success(payload):
          resolve([
            "croppedImage": payload.croppedImage ?? NSNull(),
            "initialImage": payload.originalImage,
            "width": payload.width,
            "height": payload.height,
          ])
        case let .failure(error as RNRDocScannerError):
          reject(error.code, error.message, error)
        case let .failure(error):
          reject("capture_failed", error.localizedDescription, error)
        }
      }
    }
  }

  @objc func reset(_ reactTag: NSNumber) {
    bridge.uiManager.addUIBlock { _, viewRegistry in
      guard let view = viewRegistry?[reactTag] as? RNRDocScannerView else {
        return
      }
      view.resetStability()
    }
  }
}
