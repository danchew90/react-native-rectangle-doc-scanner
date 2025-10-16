package com.reactnativerectangledocscanner

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.uimanager.ViewManager

class RNRDocScannerPackage : ReactPackage {
  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
    return listOf(RNRDocScannerModule(reactContext))
  }

  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
    return listOf(RNRDocScannerViewManager(reactContext))
  }
}
