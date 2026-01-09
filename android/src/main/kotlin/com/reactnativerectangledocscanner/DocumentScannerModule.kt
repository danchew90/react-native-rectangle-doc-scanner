package com.reactnativerectangledocscanner

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.uimanager.UIManagerModule
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.*
import org.opencv.core.Point
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class DocumentScannerModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), ActivityEventListener {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pendingScanPromise: Promise? = null

    private val scanRequestCode = 39201

    init {
        reactContext.addActivityEventListener(this)
    }

    companion object {
        const val NAME = "RNPdfScannerManager"
        private const val TAG = "DocumentScannerModule"
    }

    override fun getName() = NAME

    @ReactMethod
    fun startDocumentScanner(options: ReadableMap?, promise: Promise) {
        val activity = currentActivity
        if (activity == null) {
            promise.reject("NO_ACTIVITY", "Activity doesn't exist")
            return
        }

        if (pendingScanPromise != null) {
            promise.reject("SCAN_IN_PROGRESS", "Document scanner already in progress")
            return
        }

        val pageLimit = options?.takeIf { it.hasKey("pageLimit") }?.getInt("pageLimit") ?: 2

        val scannerOptions = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setPageLimit(pageLimit.coerceAtMost(2))
            .setGalleryImportAllowed(false)
            .build()

        val scanner = GmsDocumentScanning.getClient(scannerOptions)
        pendingScanPromise = promise

        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                try {
                    activity.startIntentSenderForResult(
                        intentSender,
                        scanRequestCode,
                        null,
                        0,
                        0,
                        0
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch document scanner", e)
                    pendingScanPromise = null
                    promise.reject("SCAN_LAUNCH_FAILED", "Failed to launch scanner: ${e.message}", e)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to get document scanner intent", e)
                pendingScanPromise = null
                promise.reject("SCAN_INIT_FAILED", "Failed to initialize scanner: ${e.message}", e)
            }
    }

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

    /**
     * Process a captured image from JS (VisionCamera path).
     * Options map keys:
     * - imagePath: String (required)
     * - rectangleCoordinates: Map (optional)
     * - rectangleWidth: Int (optional)
     * - rectangleHeight: Int (optional)
     * - useBase64: Boolean (optional)
     * - quality: Double (optional)
     * - brightness: Double (optional)
     * - contrast: Double (optional)
     * - saturation: Double (optional)
     * - saveInAppDocument: Boolean (optional)
     */
    @ReactMethod
    fun processImage(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val imagePath = options.getString("imagePath")
                    ?: throw IllegalArgumentException("imagePath is required")
                val rectangleMap = if (options.hasKey("rectangleCoordinates")) {
                    options.getMap("rectangleCoordinates")
                } else {
                    null
                }
                val rectangleWidth = if (options.hasKey("rectangleWidth")) {
                    options.getInt("rectangleWidth")
                } else {
                    0
                }
                val rectangleHeight = if (options.hasKey("rectangleHeight")) {
                    options.getInt("rectangleHeight")
                } else {
                    0
                }
                val useBase64 = options.hasKey("useBase64") && options.getBoolean("useBase64")
                val quality = if (options.hasKey("quality")) {
                    options.getDouble("quality").toFloat()
                } else {
                    0.95f
                }
                val brightness = if (options.hasKey("brightness")) {
                    options.getDouble("brightness").toFloat()
                } else {
                    0f
                }
                val contrast = if (options.hasKey("contrast")) {
                    options.getDouble("contrast").toFloat()
                } else {
                    1f
                }
                val saturation = if (options.hasKey("saturation")) {
                    options.getDouble("saturation").toFloat()
                } else {
                    1f
                }
                val saveInAppDocument = options.hasKey("saveInAppDocument") && options.getBoolean("saveInAppDocument")

                withContext(Dispatchers.IO) {
                    val bitmap = BitmapFactory.decodeFile(imagePath)
                        ?: throw IllegalStateException("decode_failed")
                    val rectangle = rectangleMap?.let { mapToRectangle(it) }
                    val scaledRectangle = if (rectangle != null && rectangleWidth > 0 && rectangleHeight > 0) {
                        scaleRectangleToBitmap(rectangle, rectangleWidth, rectangleHeight, bitmap.width, bitmap.height)
                    } else {
                        rectangle
                    }

                    val processed = ImageProcessor.processImage(
                        imagePath = imagePath,
                        rectangle = scaledRectangle,
                        brightness = brightness,
                        contrast = contrast,
                        saturation = saturation,
                        shouldCrop = scaledRectangle != null
                    )

                    val outputDir = if (saveInAppDocument) {
                        reactApplicationContext.filesDir
                    } else {
                        reactApplicationContext.cacheDir
                    }

                    val timestamp = System.currentTimeMillis()

                    val result = Arguments.createMap()
                    if (useBase64) {
                        val croppedBase64 = ImageProcessor.bitmapToBase64(processed.croppedImage, quality)
                        val initialBase64 = ImageProcessor.bitmapToBase64(processed.initialImage, quality)
                        result.putString("croppedImage", croppedBase64)
                        result.putString("initialImage", initialBase64)
                    } else {
                        val croppedPath = ImageProcessor.saveBitmapToFile(
                            processed.croppedImage,
                            outputDir,
                            "cropped_img_$timestamp.jpeg",
                            quality
                        )
                        val initialPath = ImageProcessor.saveBitmapToFile(
                            processed.initialImage,
                            outputDir,
                            "initial_img_$timestamp.jpeg",
                            quality
                        )
                        result.putString("croppedImage", croppedPath)
                        result.putString("initialImage", initialPath)
                    }

                    result.putMap("rectangleCoordinates", scaledRectangle?.let { rectangleToWritableMap(it) })
                    result.putInt("width", processed.croppedImage.width)
                    result.putInt("height", processed.croppedImage.height)

                    withContext(Dispatchers.Main) {
                        promise.resolve(result)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process image", e)
                promise.reject("PROCESSING_FAILED", "Failed to process image: ${e.message}", e)
            }
        }
    }

    override fun onActivityResult(activity: Activity?, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != scanRequestCode) {
            return
        }

        val promise = pendingScanPromise
        pendingScanPromise = null

        if (promise == null) {
            return
        }

        if (resultCode != Activity.RESULT_OK) {
            promise.reject("SCAN_CANCELLED", "Document scan cancelled")
            return
        }

        val result = GmsDocumentScanningResult.fromActivityResultIntent(data)
        if (result == null) {
            promise.reject("SCAN_NO_RESULT", "No scan result returned")
            return
        }

        val pages = result.pages
        if (pages.isNullOrEmpty()) {
            promise.reject("SCAN_NO_PAGES", "No pages returned from scanner")
            return
        }

        try {
            val outputPages = Arguments.createArray()
            var firstPath: String? = null
            var firstWidth = 0
            var firstHeight = 0

            pages.forEachIndexed { index, page ->
                val imageUri = page.imageUri
                val outputFile = copyUriToCache(imageUri, index)
                val (width, height) = readImageSize(outputFile.absolutePath)

                if (index == 0) {
                    firstPath = outputFile.absolutePath
                    firstWidth = width
                    firstHeight = height
                }

                val pageMap = Arguments.createMap().apply {
                    putString("path", outputFile.absolutePath)
                    putInt("width", width)
                    putInt("height", height)
                }
                outputPages.pushMap(pageMap)
            }

            val response = Arguments.createMap().apply {
                putString("croppedImage", firstPath)
                putString("initialImage", firstPath)
                putInt("width", firstWidth)
                putInt("height", firstHeight)
                putArray("pages", outputPages)
            }

            promise.resolve(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle scan result", e)
            promise.reject("SCAN_PROCESS_FAILED", "Failed to handle scan result: ${e.message}", e)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        // No-op
    }

    private fun copyUriToCache(uri: Uri, index: Int): File {
        val filename = "docscanner_page_${System.currentTimeMillis()}_$index.jpg"
        val outputFile = File(reactApplicationContext.cacheDir, filename)
        val resolver = reactApplicationContext.contentResolver
        val inputStream: InputStream = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Failed to open input stream for $uri")

        inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }

        return outputFile
    }

    private fun readImageSize(path: String): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        return options.outWidth to options.outHeight
    }

    override fun onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy()
        scope.cancel()
    }

    private fun mapToRectangle(map: ReadableMap): Rectangle? {
        fun toPoint(pointMap: ReadableMap?): Point? {
            if (pointMap == null) return null
            if (!pointMap.hasKey("x") || !pointMap.hasKey("y")) return null
            return Point(pointMap.getDouble("x"), pointMap.getDouble("y"))
        }

        val topLeft = toPoint(map.getMap("topLeft"))
        val topRight = toPoint(map.getMap("topRight"))
        val bottomLeft = toPoint(map.getMap("bottomLeft"))
        val bottomRight = toPoint(map.getMap("bottomRight"))

        if (topLeft == null || topRight == null || bottomLeft == null || bottomRight == null) {
            return null
        }

        return Rectangle(topLeft, topRight, bottomLeft, bottomRight)
    }

    private fun rectangleToWritableMap(rectangle: Rectangle): WritableMap {
        val map = Arguments.createMap()
        fun putPoint(key: String, point: Point) {
            val pointMap = Arguments.createMap()
            pointMap.putDouble("x", point.x)
            pointMap.putDouble("y", point.y)
            map.putMap(key, pointMap)
        }
        putPoint("topLeft", rectangle.topLeft)
        putPoint("topRight", rectangle.topRight)
        putPoint("bottomLeft", rectangle.bottomLeft)
        putPoint("bottomRight", rectangle.bottomRight)
        return map
    }

    private fun scaleRectangleToBitmap(
        rectangle: Rectangle,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int
    ): Rectangle {
        if (srcWidth == 0 || srcHeight == 0) return rectangle
        val scaleX = dstWidth.toDouble() / srcWidth.toDouble()
        val scaleY = dstHeight.toDouble() / srcHeight.toDouble()

        fun mapPoint(point: Point): Point {
            return Point(point.x * scaleX, point.y * scaleY)
        }

        return Rectangle(
            mapPoint(rectangle.topLeft),
            mapPoint(rectangle.topRight),
            mapPoint(rectangle.bottomLeft),
            mapPoint(rectangle.bottomRight)
        )
    }
}
