package com.reactnativerectangledocscanner

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

class RNRDocScannerViewManager(
  private val reactContext: ReactApplicationContext,
) : SimpleViewManager<RNRDocScannerView>() {

  override fun getName() = "RNRDocScannerView"

  override fun createViewInstance(reactContext: ThemedReactContext): RNRDocScannerView {
    return RNRDocScannerView(reactContext)
  }

  override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> {
    return mutableMapOf(
      "onRectangleDetect" to mapOf("registrationName" to "onRectangleDetect"),
      "onPictureTaken" to mapOf("registrationName" to "onPictureTaken"),
    )
  }

  @ReactProp(name = "detectionCountBeforeCapture", defaultInt = 8)
  fun setDetectionCountBeforeCapture(view: RNRDocScannerView, value: Int) {
    view.detectionCountBeforeCapture = value
  }

  @ReactProp(name = "autoCapture", defaultBoolean = true)
  fun setAutoCapture(view: RNRDocScannerView, value: Boolean) {
    view.autoCapture = value
  }

  @ReactProp(name = "enableTorch", defaultBoolean = false)
  fun setEnableTorch(view: RNRDocScannerView, value: Boolean) {
    view.enableTorch = value
  }

  @ReactProp(name = "quality", defaultInt = 90)
  fun setQuality(view: RNRDocScannerView, value: Int) {
    view.quality = value
  }

  @ReactProp(name = "useBase64", defaultBoolean = false)
  fun setUseBase64(view: RNRDocScannerView, value: Boolean) {
    view.useBase64 = value
  }
}
