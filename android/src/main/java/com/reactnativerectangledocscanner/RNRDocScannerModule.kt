package com.reactnativerectangledocscanner

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.EventDispatcher

class RNRDocScannerModule(
  private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

  override fun getName() = "RNRDocScannerModule"

  @ReactMethod
  fun capture(viewTag: Int, promise: Promise) {
    UiThreadUtil.runOnUiThread {
      val view = UIManagerHelper.getView(reactContext, viewTag) as? RNRDocScannerView
      if (view == null) {
        promise.reject("view_not_found", "Unable to locate DocScanner view.")
        return@runOnUiThread
      }
      view.capture(promise)
    }
  }

  @ReactMethod
  fun reset(viewTag: Int) {
    UiThreadUtil.runOnUiThread {
      val view = UIManagerHelper.getView(reactContext, viewTag) as? RNRDocScannerView
      view?.reset()
    }
  }
}
