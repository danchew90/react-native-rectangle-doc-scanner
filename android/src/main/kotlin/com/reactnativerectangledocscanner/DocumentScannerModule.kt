package com.reactnativerectangledocscanner

import android.graphics.BitmapFactory
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.uimanager.UIManagerModule
import kotlinx.coroutines.*

class DocumentScannerModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val NAME = "RNPdfScannerManager"
        private const val TAG = "DocumentScannerModule"
    }

    override fun getName() = NAME

    /**
     * Capture image from the document scanner view
     * Matches iOS signature: capture(reactTag, resolver, rejecter)
     */
    @ReactMethod
    fun capture(reactTag: Double?, promise: Promise) {
        Log.d(TAG, "capture called with reactTag: $reactTag")

        try {
            val tag = reactTag?.toInt() ?: run {
                promise.reject("NO_TAG", "React tag is required")
                return
            }

            val uiManager = reactApplicationContext.getNativeModule(UIManagerModule::class.java)
                ?: run {
                    promise.reject("NO_UI_MANAGER", "UIManager not available")
                    return
                }

            UiThreadUtil.runOnUiThread {
                try {
                    val view = uiManager.resolveView(tag)

                    if (view is DocumentScannerView) {
                        Log.d(TAG, "Found DocumentScannerView, triggering capture with promise")

                        // Pass promise to view so it can be resolved when capture completes
                        // This matches iOS behavior where promise is resolved with actual image data
                        view.captureWithPromise(promise)
                    } else {
                        Log.e(TAG, "View with tag $tag is not DocumentScannerView: ${view?.javaClass?.simpleName}")
                        promise.reject("INVALID_VIEW", "View is not a DocumentScannerView")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error resolving view", e)
                    promise.reject("VIEW_ERROR", "Failed to resolve view: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in capture method", e)
            promise.reject("CAPTURE_ERROR", "Failed to capture: ${e.message}", e)
        }
    }

    /**
     * Apply color controls to an image
     * Matches iOS: applyColorControls(imagePath, brightness, contrast, saturation, resolver, rejecter)
     */
    @ReactMethod
    fun applyColorControls(
        imagePath: String,
        brightness: Double,
        contrast: Double,
        saturation: Double,
        promise: Promise
    ) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bitmap = BitmapFactory.decodeFile(imagePath)
                        ?: throw Exception("Failed to load image from path: $imagePath")

                    val processedBitmap = ImageProcessor.applyColorControls(
                        bitmap = bitmap,
                        brightness = brightness.toFloat(),
                        contrast = contrast.toFloat(),
                        saturation = saturation.toFloat()
                    )

                    val outputDir = reactApplicationContext.cacheDir
                    val timestamp = System.currentTimeMillis()
                    val outputPath = ImageProcessor.saveBitmapToFile(
                        bitmap = processedBitmap,
                        directory = outputDir,
                        filename = "docscanner_enhanced_$timestamp.jpg",
                        quality = 0.98f
                    )

                    // Cleanup
                    bitmap.recycle()
                    if (processedBitmap != bitmap) {
                        processedBitmap.recycle()
                    }

                    withContext(Dispatchers.Main) {
                        promise.resolve(outputPath)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply color controls", e)
                promise.reject("COLOR_CONTROLS_ERROR", "Failed to apply color controls: ${e.message}", e)
            }
        }
    }

    override fun onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy()
        scope.cancel()
    }
}
