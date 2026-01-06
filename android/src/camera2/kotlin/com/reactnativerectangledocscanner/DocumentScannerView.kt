package com.reactnativerectangledocscanner

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import org.opencv.core.Point
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.min
import kotlin.math.max

class DocumentScannerView(context: ThemedReactContext) : FrameLayout(context), LifecycleOwner {
    private val themedContext = context
    private val previewView: PreviewView
    private val overlayView: OverlayView
    private val useNativeOverlay = false
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
    private var lastDetectedRectangle: Rectangle? = null
    private var lastDetectedImageWidth = 0
    private var lastDetectedImageHeight = 0
    private var lastRectangleOnScreen: Rectangle? = null

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "DocumentScannerView"
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    init {
        Log.d(TAG, "╔════════════════════════════════════════╗")
        Log.d(TAG, "║  DocumentScannerView INIT START        ║")
        Log.d(TAG, "╚════════════════════════════════════════╝")
        Log.d(TAG, "[INIT] Context: $context")
        Log.d(TAG, "[INIT] Context class: ${context.javaClass.name}")

        // Initialize lifecycle FIRST
        Log.d(TAG, "[INIT] Setting lifecycle to INITIALIZED...")
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        Log.d(TAG, "[INIT] Lifecycle state: ${lifecycleRegistry.currentState}")

        // Create preview view (CameraX PreviewView)
        Log.d(TAG, "[INIT] Creating PreviewView...")
        previewView = PreviewView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            visibility = View.VISIBLE
            keepScreenOn = true
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE  // Use SurfaceView internally
            scaleType = PreviewView.ScaleType.FILL_CENTER  // Fill and center the preview
        }
        Log.d(TAG, "[INIT] PreviewView created: $previewView")
        Log.d(TAG, "[INIT] PreviewView visibility: ${previewView.visibility}")

        Log.d(TAG, "[INIT] Adding PreviewView to parent...")
        addView(previewView)
        Log.d(TAG, "[INIT] PreviewView added, childCount: $childCount")

        // Create overlay view for drawing rectangle
        Log.d(TAG, "[INIT] Creating OverlayView...")
        overlayView = OverlayView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            // Ensure overlay is drawn on top
            bringToFront()
        }
        Log.d(TAG, "[INIT] OverlayView created: $overlayView")

        Log.d(TAG, "[INIT] Adding OverlayView to parent...")
        addView(overlayView)
        Log.d(TAG, "[INIT] OverlayView added, childCount: $childCount")

        Log.d(TAG, "╔════════════════════════════════════════╗")
        Log.d(TAG, "║  DocumentScannerView INIT COMPLETE     ║")
        Log.d(TAG, "╚════════════════════════════════════════╝")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "========================================")
        Log.d(TAG, "onAttachedToWindow called")
        Log.d(TAG, "Current lifecycle state: ${lifecycleRegistry.currentState}")
        Log.d(TAG, "PreviewView: width=${previewView.width}, height=${previewView.height}")
        Log.d(TAG, "This view: width=$width, height=$height")
        Log.d(TAG, "========================================")

        // Update lifecycle
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Initialize camera immediately or on next layout
        initializeCameraWhenReady()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            Log.d(TAG, "[LAYOUT] View size: ${right - left}x${bottom - top}, PreviewView: ${previewView.width}x${previewView.height}")
            cameraController?.refreshTransform()
        }
    }

    private fun initializeCameraWhenReady() {
        // If view is already laid out, start camera immediately
        if (width > 0 && height > 0) {
            Log.d(TAG, "[INIT] View already laid out, starting camera immediately")
            Log.d(TAG, "[INIT] View: width=$width, height=$height")
            Log.d(TAG, "[INIT] PreviewView: width=${previewView.width}, height=${previewView.height}")
            setupCamera()
            startCamera()
        } else {
            // Otherwise, wait for layout
            Log.d(TAG, "[INIT] View not laid out yet, waiting for layout")
            post {
                if (width > 0 && height > 0) {
                    Log.d(TAG, "[INIT] View laid out after post: width=$width, height=$height")
                    setupCamera()
                    startCamera()
                } else {
                    // Fallback: use ViewTreeObserver if still not ready
                    Log.d(TAG, "[INIT] Still not laid out, using ViewTreeObserver")
                    viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            if (width > 0 && height > 0) {
                                viewTreeObserver.removeOnGlobalLayoutListener(this)
                                Log.d(TAG, "[INIT] View laid out via observer: width=$width, height=$height")
                                setupCamera()
                                startCamera()
                            }
                        }
                    })
                }
            }
        }
    }

    private fun setupCamera() {
        try {
            Log.d(TAG, "[SETUP] Creating CameraController...")
            Log.d(TAG, "[SETUP] Context: $context")
            Log.d(TAG, "[SETUP] LifecycleOwner: $this")
            Log.d(TAG, "[SETUP] PreviewView: $previewView")

            cameraController = CameraController(context, this, previewView)

            Log.d(TAG, "[SETUP] CameraController created: $cameraController")

            cameraController?.onFrameAnalyzed = { rectangle, imageWidth, imageHeight ->
                handleDetectionResult(rectangle, imageWidth, imageHeight)
            }
            lastDetectionTimestamp = 0L

            Log.d(TAG, "[SETUP] Camera setup completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "[SETUP] Failed to setup camera", e)
            e.printStackTrace()
        }
    }

    private fun handleDetectionResult(rectangle: Rectangle?, imageWidth: Int, imageHeight: Int) {
        val now = System.currentTimeMillis()
        if (now - lastDetectionTimestamp < detectionRefreshRateInMS) {
            return
        }
        lastDetectionTimestamp = now

        if (rectangle != null && imageWidth > 0 && imageHeight > 0) {
            lastDetectedRectangle = rectangle
            lastDetectedImageWidth = imageWidth
            lastDetectedImageHeight = imageHeight
        }

        val rectangleOnScreen = if (rectangle != null && width > 0 && height > 0) {
            cameraController?.mapRectangleToView(rectangle, imageWidth, imageHeight)
                ?: DocumentDetector.transformRectangleToViewCoordinates(rectangle, imageWidth, imageHeight, width, height)
        } else {
            null
        }
        lastRectangleOnScreen = rectangleOnScreen
        val quality = when {
            rectangleOnScreen != null && width > 0 && height > 0 ->
                DocumentDetector.evaluateRectangleQualityInView(rectangleOnScreen, width, height)
            rectangle != null -> DocumentDetector.evaluateRectangleQuality(rectangle, imageWidth, imageHeight)
            else -> RectangleQuality.TOO_FAR
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
        // Update native overlay only when explicitly enabled to avoid double-rendering with JS overlay
        if (useNativeOverlay) {
            overlayView.setRectangle(rectangleOnScreen, overlayColor)
        }

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

        // Ensure lifecycle is active before attempting capture to avoid camera closed errors
        if (lifecycleRegistry.currentState < Lifecycle.State.STARTED) {
            Log.d(TAG, "Lifecycle not STARTED, current state: ${lifecycleRegistry.currentState}")
            promise?.reject("LIFECYCLE_INACTIVE", "Camera preview not ready")
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
                ?: throw IllegalStateException("decode_failed")
            var detectedRectangle: Rectangle? = null
            val rectangleFromView = if (lastDetectedImageWidth > 0 && lastDetectedImageHeight > 0) {
                lastRectangleOnScreen?.let {
                    viewToImageRectangle(
                        it,
                        width,
                        height,
                        lastDetectedImageWidth,
                        lastDetectedImageHeight
                    )
                }
            } else {
                null
            }
            if (rectangleFromView != null) {
                detectedRectangle = scaleRectangleToBitmap(
                    rectangleFromView,
                    lastDetectedImageWidth,
                    lastDetectedImageHeight,
                    bitmap.width,
                    bitmap.height
                )
            } else {
                detectedRectangle = try {
                    DocumentDetector.detectRectangle(bitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "Rectangle detection failed, using original image", e)
                    null
                }
                if (detectedRectangle == null && lastDetectedImageWidth > 0 && lastDetectedImageHeight > 0) {
                    val fallbackRect = lastDetectedRectangle
                    if (fallbackRect != null) {
                        detectedRectangle = scaleRectangleToBitmap(
                            fallbackRect,
                            lastDetectedImageWidth,
                            lastDetectedImageHeight,
                            bitmap.width,
                            bitmap.height
                        )
                    }
                }
            }

            // Process image with detected rectangle
            val shouldCrop = detectedRectangle != null
            val processed = ImageProcessor.processImage(
                imagePath = imageFile.absolutePath,
                rectangle = detectedRectangle,
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                shouldCrop = shouldCrop
            )

            fun buildResult(
                croppedPath: String,
                initialPath: String,
                rectangle: Rectangle?
            ): WritableMap {
                return Arguments.createMap().apply {
                    putString("croppedImage", croppedPath)
                    putString("initialImage", initialPath)
                    putMap("rectangleCoordinates", rectangle?.toMap()?.toWritableMap())
                }
            }

            val (resultForPromise, resultForEvent) = if (useBase64) {
                val croppedBase64 = ImageProcessor.bitmapToBase64(processed.croppedImage, quality)
                val initialBase64 = ImageProcessor.bitmapToBase64(processed.initialImage, quality)
                buildResult(croppedBase64, initialBase64, detectedRectangle) to
                    buildResult(croppedBase64, initialBase64, detectedRectangle)
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
                buildResult(croppedPath, initialPath, detectedRectangle) to
                    buildResult(croppedPath, initialPath, detectedRectangle)
            }

            withContext(Dispatchers.Main) {
                Log.d(TAG, "Processing completed, resolving promise: ${promise != null}")

                // Resolve promise first (if provided) - matches iOS behavior
                promise?.resolve(resultForPromise)

                // Then send event for backwards compatibility
                sendPictureTakenEvent(resultForEvent)

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
        themedContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, "onPictureTaken", data)
    }

    private fun sendRectangleDetectEvent(
        rectangleOnScreen: Rectangle?,
        rectangleCoordinates: Rectangle?,
        quality: RectangleQuality,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val previewViewport = cameraController?.getPreviewViewport()
        val event = Arguments.createMap().apply {
            putInt("stableCounter", stableCounter)
            putInt("lastDetectionType", quality.ordinal)
            putMap("rectangleCoordinates", rectangleCoordinates?.toMap()?.toWritableMap())
            putMap("rectangleOnScreen", rectangleOnScreen?.toMap()?.toWritableMap()?.apply {
                putMap("topLeft", mapPointToDp(getMap("topLeft"), density))
                putMap("topRight", mapPointToDp(getMap("topRight"), density))
                putMap("bottomLeft", mapPointToDp(getMap("bottomLeft"), density))
                putMap("bottomRight", mapPointToDp(getMap("bottomRight"), density))
            })
            previewViewport?.let {
                putMap("previewViewport", Arguments.createMap().apply {
                    putDouble("left", (it.left / density).toDouble())
                    putDouble("top", (it.top / density).toDouble())
                    putDouble("width", (it.width() / density).toDouble())
                    putDouble("height", (it.height() / density).toDouble())
                })
            }
            putMap("previewSize", Arguments.createMap().apply {
                putInt("width", (width / density).toInt())
                putInt("height", (height / density).toInt())
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
        Log.d(TAG, "[START_CAMERA] Starting camera...")
        Log.d(TAG, "[START_CAMERA] Current lifecycle state: ${lifecycleRegistry.currentState}")
        Log.d(TAG, "[START_CAMERA] isUsingFrontCamera: $isUsingFrontCamera")
        Log.d(TAG, "[START_CAMERA] isTorchEnabled: $isTorchEnabled")
        Log.d(TAG, "[START_CAMERA] CameraController: $cameraController")

        // Force PreviewView visibility and layout
        previewView.post {
            Log.d(TAG, "[START_CAMERA] Forcing PreviewView visibility and layout...")
            previewView.visibility = View.VISIBLE
            previewView.alpha = 1.0f
            previewView.requestLayout()
            previewView.invalidate()
            Log.d(TAG, "[START_CAMERA] PreviewView state - visible: ${previewView.visibility == View.VISIBLE}, alpha: ${previewView.alpha}")
            Log.d(TAG, "[START_CAMERA] PreviewView state - width: ${previewView.width}, height: ${previewView.height}")
            Log.d(TAG, "[START_CAMERA] PreviewView state - hasWindowFocus: ${previewView.hasWindowFocus()}")
        }

        lastDetectionTimestamp = 0L
        cameraController?.onFrameAnalyzed = { rectangle, imageWidth, imageHeight ->
            handleDetectionResult(rectangle, imageWidth, imageHeight)
        }

        // Transition lifecycle states properly
        Log.d(TAG, "[START_CAMERA] Ensuring lifecycle is RESUMED")
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        Log.d(TAG, "[START_CAMERA] Calling CameraController.startCamera()...")
        try {
            cameraController?.startCamera(isUsingFrontCamera, true)
            Log.d(TAG, "[START_CAMERA] CameraController.startCamera() completed")
        } catch (e: Exception) {
            Log.e(TAG, "[START_CAMERA] Failed to start camera", e)
            e.printStackTrace()
        }

        if (isTorchEnabled) {
            Log.d(TAG, "[START_CAMERA] Enabling torch...")
            cameraController?.setTorchEnabled(true)
        }

        Log.d(TAG, "[START_CAMERA] Camera start completed")
    }

    fun stopCamera() {
        if (!isCapturing) {
            cameraController?.stopCamera()
            overlayView.setRectangle(null, overlayColor)
            stableCounter = 0

            // Transition lifecycle back
            when (lifecycleRegistry.currentState) {
                Lifecycle.State.RESUMED -> {
                    lifecycleRegistry.currentState = Lifecycle.State.STARTED
                    lifecycleRegistry.currentState = Lifecycle.State.CREATED
                }
                Lifecycle.State.STARTED -> {
                    lifecycleRegistry.currentState = Lifecycle.State.CREATED
                }
                else -> {
                    // Already CREATED or destroyed
                }
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

    private fun mapPointToDp(point: ReadableMap?, density: Float): WritableMap? {
        if (point == null) return null
        val map = Arguments.createMap()
        val x = if (point.hasKey("x")) point.getDouble("x") else 0.0
        val y = if (point.hasKey("y")) point.getDouble("y") else 0.0
        map.putDouble("x", x / density)
        map.putDouble("y", y / density)
        return map
    }

    private fun viewToImageRectangle(
        rectangle: Rectangle,
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Rectangle {
        if (viewWidth == 0 || viewHeight == 0 || imageWidth == 0 || imageHeight == 0) {
            return rectangle
        }
        val scale = max(
            viewWidth.toDouble() / imageWidth.toDouble(),
            viewHeight.toDouble() / imageHeight.toDouble()
        )
        val scaledImageWidth = imageWidth.toDouble() * scale
        val scaledImageHeight = imageHeight.toDouble() * scale
        val offsetX = (scaledImageWidth - viewWidth) / 2.0
        val offsetY = (scaledImageHeight - viewHeight) / 2.0

        fun mapPoint(point: Point): Point {
            val x = (point.x + offsetX) / scale
            val y = (point.y + offsetY) / scale
            return Point(
                x.coerceIn(0.0, imageWidth.toDouble()),
                y.coerceIn(0.0, imageHeight.toDouble())
            )
        }

        return Rectangle(
            mapPoint(rectangle.topLeft),
            mapPoint(rectangle.topRight),
            mapPoint(rectangle.bottomLeft),
            mapPoint(rectangle.bottomRight)
        )
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
