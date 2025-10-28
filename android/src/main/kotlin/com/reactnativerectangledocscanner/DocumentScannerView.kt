package com.reactnativerectangledocscanner

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.max

class DocumentScannerView(context: ThemedReactContext) : FrameLayout(context) {
    private val themedContext = context
    private val previewView: PreviewView
    private val overlayView: OverlayView
    private var cameraController: CameraController? = null

    // Props (matching iOS)
    var overlayColor: Int = Color.parseColor("#80FFFFFF")
    var enableTorch: Boolean = false
    var useFrontCam: Boolean = false
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
    private var lastDetectedRectangle: Rectangle? = null
    private var lastDetectionQuality: RectangleQuality = RectangleQuality.TOO_FAR
    private val detectionHandler = Handler(Looper.getMainLooper())
    private var detectionRunnable: Runnable? = null
    private var isCapturing = false

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "DocumentScannerView"
    }

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
    }

    private fun setupCamera() {
        try {
            val lifecycleOwner = context as? LifecycleOwner ?: run {
                Log.e(TAG, "Context is not a LifecycleOwner")
                return
            }

            cameraController = CameraController(context, lifecycleOwner, previewView)
            cameraController?.startCamera(useFrontCam, !manualOnly)

            // Start detection loop
            startDetectionLoop()

            Log.d(TAG, "Camera setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup camera", e)
        }
    }

    private fun startDetectionLoop() {
        detectionRunnable?.let { detectionHandler.removeCallbacks(it) }

        detectionRunnable = object : Runnable {
            override fun run() {
                // Perform detection
                performDetection()

                // Schedule next detection
                detectionHandler.postDelayed(this, detectionRefreshRateInMS.toLong())
            }
        }

        detectionHandler.post(detectionRunnable!!)
    }

    private fun performDetection() {
        // In a real implementation, we'd analyze the camera frames
        // For now, we'll simulate detection based on capture
        // The actual detection happens during capture in this simplified version
    }

    private fun onRectangleDetected(rectangle: Rectangle?, quality: RectangleQuality) {
        lastDetectedRectangle = rectangle
        lastDetectionQuality = quality

        // Update overlay
        overlayView.setRectangle(rectangle, overlayColor)

        // Update stable counter based on quality
        when (quality) {
            RectangleQuality.GOOD -> {
                if (rectangle != null) {
                    stableCounter++
                    Log.d(TAG, "Good rectangle detected, stableCounter: $stableCounter/$detectionCountBeforeCapture")
                }
            }
            RectangleQuality.BAD_ANGLE, RectangleQuality.TOO_FAR -> {
                if (stableCounter > 0) {
                    stableCounter--
                }
                Log.d(TAG, "Bad rectangle detected (type: $quality), stableCounter: $stableCounter")
            }
        }

        // Send event to JavaScript
        sendRectangleDetectEvent(rectangle, quality)

        // Auto-capture if threshold reached
        if (!manualOnly && stableCounter >= detectionCountBeforeCapture && rectangle != null) {
            Log.d(TAG, "Auto-capture triggered! stableCounter: $stableCounter >= threshold: $detectionCountBeforeCapture")
            stableCounter = 0
            capture()
        }
    }

    fun capture() {
        if (isCapturing) {
            Log.d(TAG, "Already capturing, ignoring request")
            return
        }

        isCapturing = true
        Log.d(TAG, "Capture initiated")

        val outputDir = if (saveInAppDocument) {
            context.filesDir
        } else {
            context.cacheDir
        }

        cameraController?.capturePhoto(
            outputDirectory = outputDir,
            onImageCaptured = { file ->
                scope.launch {
                    processAndEmitImage(file)
                }
            },
            onError = { exception ->
                Log.e(TAG, "Capture failed", exception)
                isCapturing = false
                sendErrorEvent("capture_failed")
            }
        )
    }

    private suspend fun processAndEmitImage(imageFile: File) = withContext(Dispatchers.IO) {
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
                sendPictureTakenEvent(result)
                isCapturing = false

                if (!captureMultiple) {
                    stopCamera()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image", e)
            withContext(Dispatchers.Main) {
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

    private fun sendRectangleDetectEvent(rectangle: Rectangle?, quality: RectangleQuality) {
        val event = Arguments.createMap().apply {
            putInt("stableCounter", stableCounter)
            putInt("lastDetectionType", quality.ordinal)
            putMap("rectangleCoordinates", rectangle?.toMap()?.toWritableMap())
            putMap("previewSize", Arguments.createMap().apply {
                putInt("width", width)
                putInt("height", height)
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
        this.enableTorch = enabled
        cameraController?.setTorchEnabled(enabled)
    }

    fun setUseFrontCam(enabled: Boolean) {
        if (this.useFrontCam != enabled) {
            this.useFrontCam = enabled
            cameraController?.stopCamera()
            setupCamera()
        }
    }

    fun startCamera() {
        cameraController?.startCamera(useFrontCam, !manualOnly)
        startDetectionLoop()
    }

    fun stopCamera() {
        detectionRunnable?.let { detectionHandler.removeCallbacks(it) }
        cameraController?.stopCamera()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopCamera()
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
