package com.reactnativerectangledocscanner

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.min

class DocumentScannerView(context: ThemedReactContext) : FrameLayout(context), LifecycleOwner {
    private val themedContext = context
    private val previewView: PreviewView
    private val overlayView: OverlayView
    private var cameraController: CameraController? = null
    private val lifecycleRegistry = LifecycleRegistry(this)

    // Props (matching iOS)
    var overlayColor: Int = Color.parseColor("#80FFFFFF")
    private var isTorchEnabled: Boolean = false
    private var isUsingFrontCamera: Boolean = false
    var useBase64: Boolean = false
    var saveInAppDocument: Boolean = false
    var captureMultiple: Boolean = false
    var manualOnly: Boolean = false
    var detectionCountBeforeCapture: Int = 15
    var detectionRefreshRateInMS: Int = 100
    var quality: Float = 0.95f
    var brightness: Float = 0f
    var contrast: Float = 1f
    var saturation: Float = 1f

    // State
    private var stableCounter = 0
    private var lastDetectionTimestamp: Long = 0L
    private var isCapturing = false
    private var isDetaching = false

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "DocumentScannerView"
    }

    override fun getLifecycle(): Lifecycle = lifecycleRegistry

    init {
        // Create preview view
        previewView = PreviewView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        addView(previewView)

        // Create overlay view for drawing rectangle
        overlayView = OverlayView(context)
        addView(overlayView)

        // Setup camera
        post {
            setupCamera()
        }

        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    private fun setupCamera() {
        try {
            // Move to STARTED state first
            if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
                lifecycleRegistry.currentState = Lifecycle.State.STARTED
            }

            cameraController = CameraController(context, this, previewView)
            cameraController?.onFrameAnalyzed = { rectangle, imageWidth, imageHeight ->
                handleDetectionResult(rectangle, imageWidth, imageHeight)
            }
            lastDetectionTimestamp = 0L

            // Move to RESUMED state before starting camera
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            }

            cameraController?.startCamera(isUsingFrontCamera, true)
            if (isTorchEnabled) {
                cameraController?.setTorchEnabled(true)
            }

            Log.d(TAG, "Camera setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup camera", e)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    private fun handleDetectionResult(rectangle: Rectangle?, imageWidth: Int, imageHeight: Int) {
        val now = System.currentTimeMillis()
        if (now - lastDetectionTimestamp < detectionRefreshRateInMS) {
            return
        }
        lastDetectionTimestamp = now

        val quality = if (rectangle != null) {
            DocumentDetector.evaluateRectangleQuality(rectangle, imageWidth, imageHeight)
        } else {
            RectangleQuality.TOO_FAR
        }

        val rectangleOnScreen = if (rectangle != null && width > 0 && height > 0) {
            DocumentDetector.transformRectangleToViewCoordinates(rectangle, imageWidth, imageHeight, width, height)
        } else {
            null
        }

        post {
            onRectangleDetected(rectangleOnScreen, rectangle, quality, imageWidth, imageHeight)
        }
    }

    private fun onRectangleDetected(
        rectangleOnScreen: Rectangle?,
        rectangleCoordinates: Rectangle?,
        quality: RectangleQuality,
        imageWidth: Int,
        imageHeight: Int
    ) {
        // Update overlay
        overlayView.setRectangle(rectangleOnScreen, overlayColor)

        // Update stable counter based on quality
        if (rectangleCoordinates == null) {
            if (stableCounter != 0) {
                Log.d(TAG, "Rectangle lost, resetting stableCounter")
            }
            stableCounter = 0
        } else {
            when (quality) {
                RectangleQuality.GOOD -> {
                    stableCounter = min(stableCounter + 1, detectionCountBeforeCapture)
                    Log.d(TAG, "Good rectangle detected, stableCounter: $stableCounter/$detectionCountBeforeCapture")
                }
                RectangleQuality.BAD_ANGLE, RectangleQuality.TOO_FAR -> {
                    if (stableCounter > 0) {
                        stableCounter--
                    }
                    Log.d(TAG, "Bad rectangle detected (type: $quality), stableCounter: $stableCounter")
                }
            }
        }

        // Send event to JavaScript
        sendRectangleDetectEvent(rectangleOnScreen, rectangleCoordinates, quality, imageWidth, imageHeight)

        // Auto-capture if threshold reached
        if (!manualOnly && rectangleCoordinates != null && stableCounter >= detectionCountBeforeCapture) {
            Log.d(TAG, "Auto-capture triggered! stableCounter: $stableCounter >= threshold: $detectionCountBeforeCapture")
            stableCounter = 0
            capture()
        }
    }

    fun capture() {
        captureWithPromise(null)
    }

    /**
     * Capture image with promise support (matches iOS captureWithResolver:rejecter:)
     * @param promise Optional promise to resolve with capture result
     */
    fun captureWithPromise(promise: com.facebook.react.bridge.Promise?) {
        if (isCapturing) {
            Log.d(TAG, "Already capturing, ignoring request")
            promise?.reject("CAPTURE_IN_PROGRESS", "Capture already in progress")
            return
        }

        if (isDetaching) {
            Log.d(TAG, "View is detaching, cannot capture")
            promise?.reject("VIEW_DETACHING", "View is being removed")
            return
        }

        isCapturing = true
        Log.d(TAG, "Capture initiated with promise: ${promise != null}")

        val outputDir = if (saveInAppDocument) {
            context.filesDir
        } else {
            context.cacheDir
        }

        cameraController?.capturePhoto(
            outputDirectory = outputDir,
            onImageCaptured = { file ->
                if (!isDetaching) {
                    scope.launch {
                        processAndEmitImage(file, promise)
                    }
                } else {
                    Log.d(TAG, "View detaching, skipping image processing")
                    isCapturing = false
                    promise?.reject("VIEW_DETACHING", "View was removed during capture")
                }
            },
            onError = { exception ->
                Log.e(TAG, "Capture failed", exception)
                isCapturing = false

                // Reject promise if provided
                promise?.reject("CAPTURE_FAILED", "Failed to capture image", exception)

                // Also send event for backwards compatibility
                sendErrorEvent("capture_failed")
            }
        )
    }

    private suspend fun processAndEmitImage(imageFile: File, promise: com.facebook.react.bridge.Promise? = null) = withContext(Dispatchers.IO) {
        try {
            // Detect rectangle in captured image
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            val detectedRectangle = DocumentDetector.detectRectangle(bitmap)

            // Process image with detected rectangle
            val shouldCrop = detectedRectangle != null && stableCounter > 0
            val processed = ImageProcessor.processImage(
                imagePath = imageFile.absolutePath,
                rectangle = detectedRectangle,
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                shouldCrop = shouldCrop
            )

            // Save or encode images
            val result = if (useBase64) {
                Arguments.createMap().apply {
                    putString("croppedImage", ImageProcessor.bitmapToBase64(processed.croppedImage, quality))
                    putString("initialImage", ImageProcessor.bitmapToBase64(processed.initialImage, quality))
                    putMap("rectangleCoordinates", detectedRectangle?.toMap()?.toWritableMap())
                }
            } else {
                val timestamp = System.currentTimeMillis()
                val croppedPath = ImageProcessor.saveBitmapToFile(
                    processed.croppedImage,
                    if (saveInAppDocument) context.filesDir else context.cacheDir,
                    "cropped_img_$timestamp.jpeg",
                    quality
                )
                val initialPath = ImageProcessor.saveBitmapToFile(
                    processed.initialImage,
                    if (saveInAppDocument) context.filesDir else context.cacheDir,
                    "initial_img_$timestamp.jpeg",
                    quality
                )

                Arguments.createMap().apply {
                    putString("croppedImage", croppedPath)
                    putString("initialImage", initialPath)
                    putMap("rectangleCoordinates", detectedRectangle?.toMap()?.toWritableMap())
                }
            }

            withContext(Dispatchers.Main) {
                Log.d(TAG, "Processing completed, resolving promise: ${promise != null}")

                // Resolve promise first (if provided) - matches iOS behavior
                promise?.resolve(result)

                // Then send event for backwards compatibility
                sendPictureTakenEvent(result)

                isCapturing = false

                if (!captureMultiple) {
                    stopCamera()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image", e)
            withContext(Dispatchers.Main) {
                // Reject promise if provided
                promise?.reject("PROCESSING_FAILED", "Failed to process image: ${e.message}", e)

                // Also send error event for backwards compatibility
                sendErrorEvent("processing_failed")
                isCapturing = false
            }
        }
    }

    private fun sendPictureTakenEvent(data: WritableMap) {
        val event = Arguments.createMap().apply {
            merge(data)
        }
        themedContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, "onPictureTaken", event)
    }

    private fun sendRectangleDetectEvent(
        rectangleOnScreen: Rectangle?,
        rectangleCoordinates: Rectangle?,
        quality: RectangleQuality,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val event = Arguments.createMap().apply {
            putInt("stableCounter", stableCounter)
            putInt("lastDetectionType", quality.ordinal)
            putMap("rectangleCoordinates", rectangleCoordinates?.toMap()?.toWritableMap())
            putMap("rectangleOnScreen", rectangleOnScreen?.toMap()?.toWritableMap())
            putMap("previewSize", Arguments.createMap().apply {
                putInt("width", width)
                putInt("height", height)
            })
            putMap("imageSize", Arguments.createMap().apply {
                putInt("width", imageWidth)
                putInt("height", imageHeight)
            })
        }
        themedContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, "onRectangleDetect", event)
    }

    private fun sendErrorEvent(error: String) {
        val event = Arguments.createMap().apply {
            putString("error", error)
        }
        themedContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, "onPictureTaken", event)
    }

    fun setEnableTorch(enabled: Boolean) {
        isTorchEnabled = enabled
        cameraController?.setTorchEnabled(enabled)
    }

    fun setUseFrontCam(enabled: Boolean) {
        if (isUsingFrontCamera != enabled) {
            isUsingFrontCamera = enabled
            cameraController?.stopCamera()
            setupCamera()
        }
    }

    fun startCamera() {
        lastDetectionTimestamp = 0L
        cameraController?.onFrameAnalyzed = { rectangle, imageWidth, imageHeight ->
            handleDetectionResult(rectangle, imageWidth, imageHeight)
        }

        // Ensure proper lifecycle state before starting camera
        if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        cameraController?.startCamera(isUsingFrontCamera, true)
        if (isTorchEnabled) {
            cameraController?.setTorchEnabled(true)
        }
    }

    fun stopCamera() {
        if (!isCapturing) {
            cameraController?.stopCamera()
            overlayView.setRectangle(null, overlayColor)
            stableCounter = 0
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.currentState = Lifecycle.State.CREATED
            }
        } else {
            Log.d(TAG, "Cannot stop camera while capturing")
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "onDetachedFromWindow called, isCapturing: $isCapturing")

        // Mark as detaching to prevent new captures
        isDetaching = true

        // Wait for any ongoing capture to complete before cleaning up
        if (isCapturing) {
            Log.d(TAG, "Waiting for capture to complete before cleanup...")
            // Use a coroutine to wait briefly for capture to complete
            scope.launch {
                var waitCount = 0
                while (isCapturing && waitCount < 20) { // Wait up to 2 seconds
                    delay(100)
                    waitCount++
                }
                performCleanup()
            }
        } else {
            performCleanup()
        }
    }

    private fun performCleanup() {
        Log.d(TAG, "Performing cleanup")
        cameraController?.stopCamera()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraController?.shutdown()
        scope.cancel()
    }

    /**
     * Overlay view for drawing detected rectangle
     */
    private class OverlayView(context: Context) : View(context) {
        private var rectangle: Rectangle? = null
        private var overlayColor: Int = Color.parseColor("#80FFFFFF")
        private val paint = Paint().apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        }

        fun setRectangle(rect: Rectangle?, color: Int) {
            this.rectangle = rect
            this.overlayColor = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            rectangle?.let { rect ->
                paint.color = overlayColor

                // Draw the rectangle overlay (simplified - just a filled polygon)
                val path = android.graphics.Path().apply {
                    moveTo(rect.topLeft.x.toFloat(), rect.topLeft.y.toFloat())
                    lineTo(rect.topRight.x.toFloat(), rect.topRight.y.toFloat())
                    lineTo(rect.bottomRight.x.toFloat(), rect.bottomRight.y.toFloat())
                    lineTo(rect.bottomLeft.x.toFloat(), rect.bottomLeft.y.toFloat())
                    close()
                }

                canvas.drawPath(path, paint)
            }
        }
    }
}

/**
 * Extension function to convert Map to WritableMap
 */
private fun Map<String, Any?>.toWritableMap(): WritableMap {
    val map = Arguments.createMap()
    forEach { (key, value) ->
        when (value) {
            null -> map.putNull(key)
            is Boolean -> map.putBoolean(key, value)
            is Double -> map.putDouble(key, value)
            is Int -> map.putInt(key, value)
            is String -> map.putString(key, value)
            is Map<*, *> -> map.putMap(key, (value as Map<String, Any?>).toWritableMap())
            else -> Log.w("DocumentScannerView", "Unknown type for key $key: ${value::class.java}")
        }
    }
    return map
}
