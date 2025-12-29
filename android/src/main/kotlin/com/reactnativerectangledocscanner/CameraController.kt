package com.reactnativerectangledocscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import org.opencv.core.Point
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CameraController(
    private val context: Context,
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    private val previewView: TextureView
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var previewSize: Size? = null
    private var analysisSize: Size? = null
    private var captureSize: Size? = null
    private var sensorOrientation: Int = 0

    private var yuvReader: ImageReader? = null
    private var jpegReader: ImageReader? = null

    private val cameraThread = HandlerThread("Camera2Thread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val analysisThread = HandlerThread("Camera2Analysis").apply { start() }
    private val analysisHandler = Handler(analysisThread.looper)

    private var useFrontCamera = false
    private var detectionEnabled = true
    private var torchEnabled = false

    private val pendingCapture = AtomicReference<PendingCapture?>()
    private val analysisInFlight = AtomicBoolean(false)
    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build()
    )
    private var lastRectangle: Rectangle? = null
    private var lastRectangleTimestamp = 0L

    var onFrameAnalyzed: ((Rectangle?, Int, Int) -> Unit)? = null

    companion object {
        private const val TAG = "CameraController"
        private const val ANALYSIS_ASPECT_TOLERANCE = 0.15
    }

    private data class PendingCapture(
        val outputDirectory: File,
        val onImageCaptured: (File) -> Unit,
        val onError: (Exception) -> Unit
    )

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform()
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            // no-op
        }
    }

    fun startCamera(
        useFrontCam: Boolean = false,
        enableDetection: Boolean = true
    ) {
        Log.d(TAG, "[CAMERA2] startCamera called")
        this.useFrontCamera = useFrontCam
        this.detectionEnabled = enableDetection

        if (!hasCameraPermission()) {
            Log.e(TAG, "[CAMERA2] Camera permission not granted")
            return
        }

        if (previewView.isAvailable) {
            openCamera()
        } else {
            previewView.surfaceTextureListener = textureListener
        }
    }

    fun stopCamera() {
        Log.d(TAG, "[CAMERA2] stopCamera called")
        previewView.surfaceTextureListener = null
        closeSession()
    }

    fun capturePhoto(
        outputDirectory: File,
        onImageCaptured: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val device = cameraDevice
        val session = captureSession
        val reader = jpegReader
        if (device == null || session == null || reader == null) {
            onError(IllegalStateException("Camera not ready for capture"))
            return
        }

        if (!pendingCapture.compareAndSet(null, PendingCapture(outputDirectory, onImageCaptured, onError))) {
            onError(IllegalStateException("Capture already in progress"))
            return
        }

        try {
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (torchEnabled) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }

            session.capture(requestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {}, cameraHandler)
        } catch (e: Exception) {
            pendingCapture.getAndSet(null)?.onError?.invoke(e)
        }
    }

    fun setTorchEnabled(enabled: Boolean) {
        torchEnabled = enabled
        updateRepeatingRequest()
    }

    fun switchCamera() {
        useFrontCamera = !useFrontCamera
        closeSession()
        openCamera()
    }

    fun isTorchAvailable(): Boolean {
        return try {
            val cameraId = selectCameraId() ?: return false
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } catch (e: Exception) {
            false
        }
    }

    fun focusAt(x: Float, y: Float) {
        // Optional: implement touch-to-focus if needed.
    }

    fun shutdown() {
        stopCamera()
        objectDetector.close()
        cameraThread.quitSafely()
        analysisThread.quitSafely()
    }

    private fun openCamera() {
        if (cameraDevice != null) {
            return
        }
        val cameraId = selectCameraId() ?: return
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            // Calculate view aspect ratio considering sensor orientation
            // For portrait mode with 90/270 degree sensor, we need to swap width/height
            val displayRotation = displayRotationDegrees()
            val totalRotation = if (useFrontCamera) {
                (sensorOrientation + displayRotation) % 360
            } else {
                (sensorOrientation - displayRotation + 360) % 360
            }

            val viewWidth = previewView.width.takeIf { it > 0 } ?: 1200
            val viewHeight = previewView.height.takeIf { it > 0 } ?: 1928

            // If total rotation is 90 or 270, the sensor output is rotated, so we need to match against swapped aspect
            val viewAspect = if (totalRotation == 90 || totalRotation == 270) {
                // Sensor outputs landscape (e.g., 1920x1080), but we display portrait
                // So we want to find sensor size with aspect ~= viewHeight/viewWidth
                viewHeight.toDouble() / viewWidth.toDouble()
            } else {
                viewWidth.toDouble() / viewHeight.toDouble()
            }

            Log.d(TAG, "[CAMERA2] sensorOrientation=$sensorOrientation displayRotation=$displayRotation totalRotation=$totalRotation")
            Log.d(TAG, "[CAMERA2] viewAspect=$viewAspect (view: ${viewWidth}x${viewHeight})")

            val previewSizes = streamConfigMap.getOutputSizes(SurfaceTexture::class.java)
            Log.d(TAG, "[CAMERA2] Available preview sizes: ${previewSizes?.take(10)?.joinToString { "${it.width}x${it.height}" }}")

            previewSize = chooseBestSize(previewSizes, viewAspect, null, preferClosestAspect = true)
            Log.d(TAG, "[CAMERA2] Selected preview size: ${previewSize?.width}x${previewSize?.height}")

            val previewAspect = previewSize?.let { it.width.toDouble() / it.height.toDouble() } ?: viewAspect
            val analysisSizes = streamConfigMap.getOutputSizes(ImageFormat.YUV_420_888)
            analysisSize = chooseBestSize(analysisSizes, previewAspect, null, preferClosestAspect = true)

            val captureSizes = streamConfigMap.getOutputSizes(ImageFormat.JPEG)
            captureSize = chooseBestSize(captureSizes, previewAspect, null, preferClosestAspect = true)
                ?: captureSizes?.maxByOrNull { it.width * it.height }

            setupImageReaders()
            Log.d(
                TAG,
                "[CAMERA2] view=${previewView.width}x${previewView.height} " +
                    "preview=${previewSize?.width}x${previewSize?.height} " +
                    "analysis=${analysisSize?.width}x${analysisSize?.height} " +
                    "capture=${captureSize?.width}x${captureSize?.height}"
            )

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "[CAMERA2] Camera permission not granted")
                return
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "[CAMERA2] CameraDevice error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERA2] Failed to open camera", e)
        }
    }

    private fun setupImageReaders() {
        val analysis = analysisSize
        val capture = captureSize

        yuvReader?.close()
        jpegReader?.close()

        if (analysis != null) {
            yuvReader = ImageReader.newInstance(analysis.width, analysis.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    if (!detectionEnabled || onFrameAnalyzed == null) {
                        try {
                            reader.acquireLatestImage()?.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "[CAMERA2] Failed to drain analysis image", e)
                        }
                        return@setOnImageAvailableListener
                    }
                    if (!analysisInFlight.compareAndSet(false, true)) {
                        try {
                            reader.acquireLatestImage()?.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "[CAMERA2] Failed to drop analysis image", e)
                        }
                        return@setOnImageAvailableListener
                    }
                    val image = try {
                        reader.acquireLatestImage()
                    } catch (e: Exception) {
                        analysisInFlight.set(false)
                        Log.w(TAG, "[CAMERA2] acquireLatestImage failed", e)
                        null
                    }
                    if (image == null) {
                        analysisInFlight.set(false)
                        return@setOnImageAvailableListener
                    }
                    analysisHandler.post { analyzeImage(image) }
                }, cameraHandler)
            }
        }

        if (capture != null) {
            jpegReader = ImageReader.newInstance(capture.width, capture.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    val pending = pendingCapture.getAndSet(null)
                    if (pending == null) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    analysisHandler.post { processCapture(image, pending) }
                }, cameraHandler)
            }
        }
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val surfaceTexture = previewView.surfaceTexture ?: return
        val preview = previewSize ?: return

        surfaceTexture.setDefaultBufferSize(preview.width, preview.height)
        val previewSurface = Surface(surfaceTexture)

        val targets = mutableListOf(previewSurface)
        yuvReader?.surface?.let { targets.add(it) }
        jpegReader?.surface?.let { targets.add(it) }

        try {
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    configureTransform()
                    startRepeating(previewSurface)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "[CAMERA2] Failed to configure capture session")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERA2] Failed to create capture session", e)
        }
    }

    private fun startRepeating(previewSurface: Surface) {
        val device = cameraDevice ?: return
        try {
            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                yuvReader?.surface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (torchEnabled) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            }
            captureSession?.setRepeatingRequest(previewRequestBuilder?.build() ?: return, null, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERA2] Failed to start repeating request", e)
        }
    }

    private fun updateRepeatingRequest() {
        val builder = previewRequestBuilder ?: return
        builder.set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        try {
            captureSession?.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERA2] Failed to update torch state", e)
        }
    }

    private fun analyzeImage(image: Image) {
        val rotationDegrees = computeRotationDegrees()
        val imageWidth = image.width
        val imageHeight = image.height
        val nv21 = try {
            imageToNv21(image)
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERA2] Failed to read image buffer", e)
            try {
                image.close()
            } catch (closeError: Exception) {
                Log.w(TAG, "[CAMERA2] Failed to close image", closeError)
            }
            analysisInFlight.set(false)
            return
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
                Log.w(TAG, "[CAMERA2] Failed to close image", e)
            }
        }

        val inputImage = try {
            InputImage.fromByteArray(
                nv21,
                imageWidth,
                imageHeight,
                rotationDegrees,
                InputImage.IMAGE_FORMAT_NV21
            )
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERA2] Failed to create InputImage", e)
            analysisInFlight.set(false)
            return
        }

        objectDetector.process(inputImage)
            .addOnSuccessListener { objects ->
                val best = objects.maxByOrNull { obj ->
                    val box = obj.boundingBox
                    box.width() * box.height()
                }
                val mlBox = best?.boundingBox
                val rectangle = refineWithOpenCv(nv21, imageWidth, imageHeight, rotationDegrees, mlBox)

                val frameWidth = if (rotationDegrees == 90 || rotationDegrees == 270) imageHeight else imageWidth
                val frameHeight = if (rotationDegrees == 90 || rotationDegrees == 270) imageWidth else imageHeight
                onFrameAnalyzed?.invoke(smoothRectangle(rectangle), frameWidth, frameHeight)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "[CAMERA2] ML Kit detection failed", e)
                val rectangle = try {
                    DocumentDetector.detectRectangleInYUV(nv21, imageWidth, imageHeight, rotationDegrees)
                } catch (detectError: Exception) {
                    Log.w(TAG, "[CAMERA2] OpenCV fallback failed", detectError)
                    null
                }
                val frameWidth = if (rotationDegrees == 90 || rotationDegrees == 270) imageHeight else imageWidth
                val frameHeight = if (rotationDegrees == 90 || rotationDegrees == 270) imageWidth else imageHeight
                onFrameAnalyzed?.invoke(smoothRectangle(rectangle), frameWidth, frameHeight)
            }
            .addOnCompleteListener {
                analysisInFlight.set(false)
            }
    }

    private fun processCapture(image: Image, pending: PendingCapture) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Failed to decode JPEG")

            val rotated = rotateAndMirror(bitmap, computeRotationDegrees(), useFrontCamera)
            val photoFile = File(pending.outputDirectory, "doc_scan_${System.currentTimeMillis()}.jpg")
            FileOutputStream(photoFile).use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            if (rotated != bitmap) {
                rotated.recycle()
            }
            bitmap.recycle()

            pending.onImageCaptured(photoFile)
        } catch (e: Exception) {
            pending.onError(e)
        } finally {
            image.close()
        }
    }

    private fun closeSession() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERA2] Error closing camera", e)
        } finally {
            yuvReader?.close()
            jpegReader?.close()
            yuvReader = null
            jpegReader = null
            previewRequestBuilder = null
        }
    }

    private fun computeRotationDegrees(): Int {
        val displayRotation = displayRotationDegrees()
        return if (useFrontCamera) {
            (sensorOrientation + displayRotation) % 360
        } else {
            (sensorOrientation - displayRotation + 360) % 360
        }
    }

    private fun displayRotationDegrees(): Int {
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        return when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun configureTransform() {
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()
        val preview = previewSize ?: return
        if (viewWidth == 0f || viewHeight == 0f) return

        val rotation = computeRotationDegrees()
        val bufferWidth = if (rotation == 90 || rotation == 270) preview.height.toFloat() else preview.width.toFloat()
        val bufferHeight = if (rotation == 90 || rotation == 270) preview.width.toFloat() else preview.height.toFloat()

        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        // Rotate to match display orientation.
        val matrix = Matrix()
        if (rotation != 0) {
            matrix.postRotate(rotation.toFloat(), centerX, centerY)
        }
        previewView.setTransform(matrix)

        // Center-crop to fill the view.
        val viewAspect = viewWidth / viewHeight
        val bufferAspect = bufferWidth / bufferHeight
        previewView.pivotX = centerX
        previewView.pivotY = centerY
        if (bufferAspect > viewAspect) {
            previewView.scaleX = bufferAspect / viewAspect
            previewView.scaleY = 1f
        } else {
            previewView.scaleX = 1f
            previewView.scaleY = viewAspect / bufferAspect
        }
    }

    private fun chooseBestSize(
        sizes: Array<Size>?,
        targetAspect: Double,
        maxArea: Int?,
        preferClosestAspect: Boolean = false
    ): Size? {
        if (sizes == null || sizes.isEmpty()) return null
        val sorted = sizes.sortedByDescending { it.width * it.height }

        val capped = if (maxArea != null) {
            sorted.filter { it.width * it.height <= maxArea }
        } else {
            sorted
        }

        if (capped.isEmpty()) {
            return sorted.first()
        }

        fun aspectDiff(size: Size): Double {
            val w = size.width.toDouble()
            val h = size.height.toDouble()
            val direct = abs(w / h - targetAspect)
            val inverted = abs(h / w - targetAspect)
            return min(direct, inverted)
        }

        if (preferClosestAspect) {
            // Prefer high-resolution sizes: minimum 1080p (1920x1080 or 1080x1920)
            val minAcceptableArea = 1920 * 1080
            val highResSizes = capped.filter { it.width * it.height >= minAcceptableArea }
            val pool = if (highResSizes.isNotEmpty()) highResSizes else capped

            // Debug: log aspect ratio diff for each size
            pool.forEach { size ->
                val diff = aspectDiff(size)
                Log.d(TAG, "[SIZE_SELECTION] ${size.width}x${size.height} aspect=${size.width.toDouble()/size.height} diff=$diff")
            }

            val bestDiff = pool.minOf { aspectDiff(it) }
            // Use very tight tolerance (0.001) to get only the best aspect ratio matches
            val close = pool.filter { aspectDiff(it) <= bestDiff + 0.001 }

            // Among best aspect ratio matches, prefer higher resolution
            val selected = close.maxByOrNull { it.width * it.height } ?: pool.maxByOrNull { it.width * it.height }
            Log.d(TAG, "[SIZE_SELECTION] Best aspect diff: $bestDiff, candidates: ${close.size}, selected: ${selected?.width}x${selected?.height}")
            return selected
        }

        val matching = capped.filter { aspectDiff(it) <= ANALYSIS_ASPECT_TOLERANCE }

        return matching.firstOrNull() ?: capped.first()
    }

    private fun rotateAndMirror(bitmap: Bitmap, rotationDegrees: Int, mirror: Boolean): Bitmap {
        if (rotationDegrees == 0 && !mirror) {
            return bitmap
        }
        val matrix = Matrix()
        if (mirror) {
            matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        }
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat(), bitmap.width / 2f, bitmap.height / 2f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun refineWithOpenCv(
        nv21: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        mlBox: Rect?
    ): Rectangle? {
        return try {
            val uprightWidth = if (rotationDegrees == 90 || rotationDegrees == 270) imageHeight else imageWidth
            val uprightHeight = if (rotationDegrees == 90 || rotationDegrees == 270) imageWidth else imageHeight
            val openCvRect = if (mlBox != null) {
                val expanded = expandRect(mlBox, uprightWidth, uprightHeight, 0.25f)
                DocumentDetector.detectRectangleInYUVWithRoi(
                    nv21,
                    imageWidth,
                    imageHeight,
                    rotationDegrees,
                    expanded
                )
            } else {
                DocumentDetector.detectRectangleInYUV(nv21, imageWidth, imageHeight, rotationDegrees)
            }
            if (openCvRect == null) {
                mlBox?.let { boxToRectangle(insetBox(it, 0.9f)) }
            } else {
                openCvRect
            }
        } catch (e: Exception) {
            Log.w(TAG, "[CAMERA2] OpenCV refine failed", e)
            null
        }
    }

    private fun boxToRectangle(box: Rect): Rectangle {
        return Rectangle(
            Point(box.left.toDouble(), box.top.toDouble()),
            Point(box.right.toDouble(), box.top.toDouble()),
            Point(box.left.toDouble(), box.bottom.toDouble()),
            Point(box.right.toDouble(), box.bottom.toDouble())
        )
    }

    private fun expandRect(box: Rect, maxWidth: Int, maxHeight: Int, ratio: Float): Rect {
        val padX = (box.width() * ratio).toInt()
        val padY = (box.height() * ratio).toInt()
        val left = (box.left - padX).coerceAtLeast(0)
        val top = (box.top - padY).coerceAtLeast(0)
        val right = (box.right + padX).coerceAtMost(maxWidth)
        val bottom = (box.bottom + padY).coerceAtMost(maxHeight)
        return Rect(left, top, right, bottom)
    }

    private fun insetBox(box: Rect, ratio: Float): Rect {
        if (ratio >= 1f) return box
        val insetX = ((1f - ratio) * box.width() / 2f).toInt()
        val insetY = ((1f - ratio) * box.height() / 2f).toInt()
        return Rect(
            box.left + insetX,
            box.top + insetY,
            box.right - insetX,
            box.bottom - insetY
        )
    }

    private fun smoothRectangle(current: Rectangle?): Rectangle? {
        val now = System.currentTimeMillis()
        val last = lastRectangle
        if (current == null) {
            if (last != null && now - lastRectangleTimestamp < 150) {
                return last
            }
            lastRectangle = null
            return null
        }

        lastRectangle = current
        lastRectangleTimestamp = now
        return current
    }

    private fun rectangleBounds(rectangle: Rectangle): Rect {
        val left = listOf(rectangle.topLeft.x, rectangle.bottomLeft.x, rectangle.topRight.x, rectangle.bottomRight.x).minOrNull() ?: 0.0
        val right = listOf(rectangle.topLeft.x, rectangle.bottomLeft.x, rectangle.topRight.x, rectangle.bottomRight.x).maxOrNull() ?: 0.0
        val top = listOf(rectangle.topLeft.y, rectangle.bottomLeft.y, rectangle.topRight.y, rectangle.bottomRight.y).minOrNull() ?: 0.0
        val bottom = listOf(rectangle.topLeft.y, rectangle.bottomLeft.y, rectangle.topRight.y, rectangle.bottomRight.y).maxOrNull() ?: 0.0
        return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    private fun imageToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val yRowStride = image.planes[0].rowStride
        val yPixelStride = image.planes[0].pixelStride
        var outputOffset = 0
        for (row in 0 until height) {
            var inputOffset = row * yRowStride
            for (col in 0 until width) {
                nv21[outputOffset++] = yBuffer.get(inputOffset)
                inputOffset += yPixelStride
            }
        }

        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride
        val vRowStride = image.planes[2].rowStride
        val vPixelStride = image.planes[2].pixelStride
        val uvHeight = height / 2
        val uvWidth = width / 2
        for (row in 0 until uvHeight) {
            var uInputOffset = row * uvRowStride
            var vInputOffset = row * vRowStride
            for (col in 0 until uvWidth) {
                nv21[outputOffset++] = vBuffer.get(vInputOffset)
                nv21[outputOffset++] = uBuffer.get(uInputOffset)
                uInputOffset += uvPixelStride
                vInputOffset += vPixelStride
            }
        }

        return nv21
    }
    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun selectCameraId(): String? {
        return try {
            val desiredFacing = if (useFrontCamera) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == desiredFacing
            } ?: cameraManager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERA2] Failed to select camera", e)
            null
        }
    }
}
