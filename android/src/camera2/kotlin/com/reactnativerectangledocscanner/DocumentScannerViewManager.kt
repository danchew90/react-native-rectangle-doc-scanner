package com.reactnativerectangledocscanner

import android.graphics.Color
import android.util.Log
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

class DocumentScannerViewManager : SimpleViewManager<DocumentScannerView>() {

    companion object {
        const val REACT_CLASS = "RNPdfScanner"
        private const val TAG = "DocScannerViewManager"
    }

    override fun getName() = REACT_CLASS

    override fun createViewInstance(reactContext: ThemedReactContext): DocumentScannerView {
        Log.d(TAG, "╔════════════════════════════════════════╗")
        Log.d(TAG, "║  createViewInstance CALLED             ║")
        Log.d(TAG, "╚════════════════════════════════════════╝")
        Log.d(TAG, "[CREATE] reactContext: $reactContext")
        val view = DocumentScannerView(reactContext)
        Log.d(TAG, "[CREATE] DocumentScannerView created: $view")
        return view
    }

    @ReactProp(name = "overlayColor", customType = "Color")
    fun setOverlayColor(view: DocumentScannerView, color: Int?) {
        color?.let {
            view.overlayColor = it
        }
    }

    @ReactProp(name = "enableTorch")
    fun setEnableTorch(view: DocumentScannerView, enabled: Boolean) {
        view.setEnableTorch(enabled)
    }

    @ReactProp(name = "useFrontCam")
    fun setUseFrontCam(view: DocumentScannerView, enabled: Boolean) {
        view.setUseFrontCam(enabled)
    }

    @ReactProp(name = "useBase64")
    fun setUseBase64(view: DocumentScannerView, enabled: Boolean) {
        view.useBase64 = enabled
    }

    @ReactProp(name = "saveInAppDocument")
    fun setSaveInAppDocument(view: DocumentScannerView, enabled: Boolean) {
        view.saveInAppDocument = enabled
    }

    @ReactProp(name = "captureMultiple")
    fun setCaptureMultiple(view: DocumentScannerView, enabled: Boolean) {
        view.captureMultiple = enabled
    }

    @ReactProp(name = "manualOnly")
    fun setManualOnly(view: DocumentScannerView, enabled: Boolean) {
        view.manualOnly = enabled
    }

    @ReactProp(name = "detectionCountBeforeCapture")
    fun setDetectionCountBeforeCapture(view: DocumentScannerView, count: Int) {
        view.detectionCountBeforeCapture = count
    }

    @ReactProp(name = "detectionRefreshRateInMS")
    fun setDetectionRefreshRateInMS(view: DocumentScannerView, rate: Int) {
        view.detectionRefreshRateInMS = rate
    }

    @ReactProp(name = "quality")
    fun setQuality(view: DocumentScannerView, quality: Float) {
        view.quality = quality
    }

    @ReactProp(name = "brightness")
    fun setBrightness(view: DocumentScannerView, brightness: Float) {
        view.brightness = brightness
    }

    @ReactProp(name = "contrast")
    fun setContrast(view: DocumentScannerView, contrast: Float) {
        view.contrast = contrast
    }

    @ReactProp(name = "saturation")
    fun setSaturation(view: DocumentScannerView, saturation: Float) {
        view.saturation = saturation
    }

    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> {
        return MapBuilder.of(
            "onPictureTaken",
            MapBuilder.of("registrationName", "onPictureTaken"),
            "onRectangleDetect",
            MapBuilder.of("registrationName", "onRectangleDetect")
        )
    }

    override fun onDropViewInstance(view: DocumentScannerView) {
        super.onDropViewInstance(view)
        view.stopCamera()
    }
}
