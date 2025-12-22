package com.reactnativerectangledocscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
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
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: TextureView
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var previewLayoutListener: android.view.View.OnLayoutChangeListener? = null

    private var cameraId: String? = null
    private var sensorOrientation: Int = 0
    private var previewSize: Size? = null
    private var analysisSize: Size? = null
    private var previewChoices: Array<Size> = emptyArray()
    private var analysisChoices: Array<Size> = emptyArray()
    private var useFrontCamera = false
    private var torchEnabled = false
    private var detectionEnabled = true
    private var hasStarted = false

    private val isOpening = AtomicBoolean(false)
    private val lastFrameLock = Any()
    private var lastFrame: LastFrame? = null

    var onFrameAnalyzed: ((Rectangle?, Int, Int) -> Unit)? = null

    companion object {
        private const val TAG = "CameraController"
        private const val MAX_ANALYSIS_WIDTH = 1280
        private const val MAX_ANALYSIS_HEIGHT = 720
    }

    private data class LastFrame(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val isFront: Boolean
    )

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "[CAMERA2] Texture available: ${width}x${height}")
            createPreviewSession()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "[CAMERA2] Texture size changed: ${width}x${height}")
            updatePreviewTransform()
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            Log.d(TAG, "[CAMERA2] Texture destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    fun startCamera(
        useFrontCam: Boolean = false,
        enableDetection: Boolean = true
    ) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "[CAMERA2] startCamera called")
        Log.d(TAG, "[CAMERA2] useFrontCam: $useFrontCam")
        Log.d(TAG, "[CAMERA2] enableDetection: $enableDetection")
        Log.d(TAG, "[CAMERA2] lifecycleOwner: $lifecycleOwner")
        Log.d(TAG, "========================================")

        this.useFrontCamera = useFrontCam
        this.detectionEnabled = enableDetection

        if (hasStarted) {
            Log.d(TAG, "[CAMERA2] Already started, skipping")
            return
        }
        hasStarted = true

        if (!hasCameraPermission()) {
            Log.e(TAG, "[CAMERA2] Camera permission not granted")
            return
        }

        startBackgroundThread()
        chooseCamera()

        if (previewLayoutListener == null) {
            previewLayoutListener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updatePreviewTransform()
            }
            previewView.addOnLayoutChangeListener(previewLayoutListener)
        }

        if (previewView.isAvailable) {
            openCamera()
        } else {
            previewView.surfaceTextureListener = textureListener
        }
    }

    fun stopCamera() {
        Log.d(TAG, "[CAMERA2] stopCamera called")
        previewLayoutListener?.let { listener ->
            previewView.removeOnLayoutChangeListener(listener)
        }
        previewLayoutListener = null
        try {
            captureSession?.close()
            captureSession = null
        } catch (e: Exception) {
            Log.w(TAG, "[CAMERA2] Failed to close session", e)
        }
        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.w(TAG, "[CAMERA2] Failed to close camera device", e)
        }
        imageReader?.close()
        imageReader = null
        stopBackgroundThread()
        hasStarted = false
    }

    fun capturePhoto(
        outputDirectory: File,
        onImageCaptured: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val frame = synchronized(lastFrameLock) { lastFrame }
        if (frame == null) {
            onError(Exception("No frame available for capture"))
            return
        }

        backgroundHandler?.post {
            try {
                val photoFile = File(
                    outputDirectory,
                    "doc_scan_${System.currentTimeMillis()}.jpg"
                )

                val jpegBytes = nv21ToJpeg(frame.nv21, frame.width, frame.height, 95)
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    ?: throw IllegalStateException("Failed to decode JPEG")

                val rotated = rotateAndMirror(bitmap, frame.rotationDegrees, frame.isFront)
                FileOutputStream(photoFile).use { out ->
                    rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                if (rotated != bitmap) {
                    rotated.recycle()
                }
                bitmap.recycle()

                Log.d(TAG, "[CAMERA2] Photo capture succeeded: ${photoFile.absolutePath}")
                onImageCaptured(photoFile)
            } catch (e: Exception) {
                Log.e(TAG, "[CAMERA2] Photo capture failed", e)
                onError(e)
            }
        }
    }

    fun setTorchEnabled(enabled: Boolean) {
        torchEnabled = enabled
        val builder = previewRequestBuilder ?: return
        builder.set(CaptureRequest.FLASH_MODE, if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        try {
            captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.w(TAG, "[CAMERA2] Failed to update torch", e)
        }
    }

    fun switchCamera() {
        useFrontCamera = !useFrontCamera
        stopCamera()
        startCamera(useFrontCamera, detectionEnabled)
    }

    fun isTorchAvailable(): Boolean {
        val id = cameraId ?: return false
        val characteristics = cameraManager.getCameraCharacteristics(id)
        return characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }

    fun focusAt(x: Float, y: Float) {
        // No-op for now. Camera2 focus metering can be added if needed.
    }

    fun shutdown() {
        stopCamera()
    }

    private fun chooseCamera() {
        val lensFacing = if (useFrontCamera) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        val ids = cameraManager.cameraIdList
        val selected = ids.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) == lensFacing
        } ?: ids.firstOrNull()

        if (selected == null) {
            Log.e(TAG, "[CAMERA2] No camera available")
            return
        }

        cameraId = selected
        val characteristics = cameraManager.getCameraCharacteristics(selected)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val streamConfig = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        previewChoices = streamConfig?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        analysisChoices = streamConfig?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()

        val viewWidth = if (previewView.width > 0) previewView.width else context.resources.displayMetrics.widthPixels
        val viewHeight = if (previewView.height > 0) previewView.height else context.resources.displayMetrics.heightPixels
        val targetRatio = if (viewWidth > 0 && viewHeight > 0) {
            viewWidth.toFloat() / viewHeight.toFloat()
        } else {
            null
        }

        logSizeCandidates("preview", previewChoices, targetRatio)
        logSizeCandidates("analysis", analysisChoices, targetRatio)

        previewSize = choosePreviewSize(previewChoices, targetRatio)
        analysisSize = chooseAnalysisSize(analysisChoices, targetRatio)
        Log.d(
            TAG,
            "[CAMERA2] chooseCamera view=${viewWidth}x${viewHeight} ratio=$targetRatio " +
                "sensorOrientation=$sensorOrientation preview=$previewSize analysis=$analysisSize"
        )
    }

    private fun openCamera() {
        val id = cameraId ?: run {
            Log.e(TAG, "[CAMERA2] Camera id not set")
            return
        }
        if (isOpening.getAndSet(true)) {
            return
        }

        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    Log.d(TAG, "[CAMERA2] Camera opened")
                    isOpening.set(false)
                    cameraDevice = device
                    createPreviewSession()
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "[CAMERA2] Camera disconnected")
                    isOpening.set(false)
                    device.close()
                    cameraDevice = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "[CAMERA2] Camera error: $error")
                    isOpening.set(false)
                    device.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            isOpening.set(false)
            Log.e(TAG, "[CAMERA2] Camera permission missing", e)
        } catch (e: Exception) {
            isOpening.set(false)
            Log.e(TAG, "[CAMERA2] Failed to open camera", e)
        }
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        val texture = previewView.surfaceTexture ?: return
        val sizes = ensurePreviewSizes()
        val previewSize = sizes.first ?: return
        val analysisSize = sizes.second ?: previewSize

        Log.d(
            TAG,
            "[CAMERA2] createPreviewSession view=${previewView.width}x${previewView.height} " +
                "preview=${previewSize.width}x${previewSize.height} analysis=${analysisSize.width}x${analysisSize.height}"
        )

        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)

        imageReader?.close()
        imageReader = ImageReader.newInstance(
            analysisSize.width,
            analysisSize.height,
            ImageFormat.YUV_420_888,
            2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                handleImage(image)
            }, backgroundHandler)
        }

        val surfaces = listOf(previewSurface, imageReader!!.surface)
        try {
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) {
                            return
                        }
                        captureSession = session
                        previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            addTarget(imageReader!!.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                        }
                        try {
                            session.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
                            Log.d(TAG, "[CAMERA2] Preview session started")
                            updatePreviewTransform()
                        } catch (e: Exception) {
                            Log.e(TAG, "[CAMERA2] Failed to start preview", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "[CAMERA2] Preview session configure failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERA2] Failed to create preview session", e)
        }
    }

    private fun ensurePreviewSizes(): Pair<Size?, Size?> {
        if (previewChoices.isEmpty()) {
            return Pair(previewSize, analysisSize)
        }

        val viewWidth = if (previewView.width > 0) previewView.width else context.resources.displayMetrics.widthPixels
        val viewHeight = if (previewView.height > 0) previewView.height else context.resources.displayMetrics.heightPixels
        val targetRatio = if (viewWidth > 0 && viewHeight > 0) {
            viewWidth.toFloat() / viewHeight.toFloat()
        } else {
            null
        }

        val newPreview = choosePreviewSize(previewChoices, targetRatio)
        val newAnalysis = chooseAnalysisSize(analysisChoices, targetRatio)

        if (newPreview != null && newPreview != previewSize) {
            previewSize = newPreview
        }
        if (newAnalysis != null && newAnalysis != analysisSize) {
            analysisSize = newAnalysis
        }

        Log.d(
            TAG,
            "[CAMERA2] ensurePreviewSizes view=${viewWidth}x${viewHeight} ratio=$targetRatio " +
                "preview=${previewSize?.width}x${previewSize?.height} analysis=${analysisSize?.width}x${analysisSize?.height}"
        )
        return Pair(previewSize, analysisSize)
    }

    private fun updatePreviewTransform() {
        val previewSize = previewSize ?: return
        ensureMatchParent()

        val viewWidth = previewView.width
        val viewHeight = previewView.height
        if (viewWidth == 0 || viewHeight == 0) {
            return
        }

        val rotationDegrees = getRotationDegrees()
        val bufferWidth = previewSize.width.toFloat()
        val bufferHeight = previewSize.height.toFloat()
        val rotatedBufferWidth = if (rotationDegrees == 90 || rotationDegrees == 270) {
            bufferHeight
        } else {
            bufferWidth
        }
        val rotatedBufferHeight = if (rotationDegrees == 90 || rotationDegrees == 270) {
            bufferWidth
        } else {
            bufferHeight
        }

        // Fill the view like the default camera preview (center-crop).
        val scale = kotlin.math.max(
            viewWidth.toFloat() / rotatedBufferWidth,
            viewHeight.toFloat() / rotatedBufferHeight
        )

        val matrix = Matrix()
        // Center buffer at origin, rotate, scale to fit, then move to view center.
        matrix.postTranslate(-bufferWidth / 2f, -bufferHeight / 2f)
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }
        matrix.postScale(scale, scale)
        matrix.postTranslate(viewWidth / 2f, viewHeight / 2f)

        previewView.setTransform(matrix)
        Log.d(
            TAG,
            "[CAMERA2] transform view=${viewWidth}x${viewHeight} buffer=${bufferWidth}x${bufferHeight} " +
                "rotated=${rotatedBufferWidth}x${rotatedBufferHeight} rotation=$rotationDegrees scale=$scale"
        )
    }

    private fun ensureMatchParent() {
        val parentView = previewView.parent as? android.view.View ?: return
        val parentWidth = parentView.width
        val parentHeight = parentView.height
        if (parentWidth == 0 || parentHeight == 0) {
            return
        }

        val layoutParams = (previewView.layoutParams as? android.widget.FrameLayout.LayoutParams)
            ?: android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        if (layoutParams.width != android.widget.FrameLayout.LayoutParams.MATCH_PARENT ||
            layoutParams.height != android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ) {
            layoutParams.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            layoutParams.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            layoutParams.gravity = Gravity.CENTER
            previewView.layoutParams = layoutParams
        }
        Log.d(TAG, "[CAMERA2] parent=${parentWidth}x${parentHeight} previewView=${previewView.width}x${previewView.height}")
    }

    private fun handleImage(image: Image) {
        try {
            val rotationDegrees = getRotationDegrees()
            val width = image.width
            val height = image.height
            val nv21 = imageToNV21(image)

            val frameWidth = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
            val frameHeight = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

            synchronized(lastFrameLock) {
                lastFrame = LastFrame(nv21, width, height, rotationDegrees, useFrontCamera)
            }

            if (detectionEnabled) {
                val rectangle = DocumentDetector.detectRectangleInYUV(
                    nv21,
                    width,
                    height,
                    rotationDegrees
                )
                onFrameAnalyzed?.invoke(rectangle, frameWidth, frameHeight)
            } else {
                onFrameAnalyzed?.invoke(null, frameWidth, frameHeight)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERA2] Error analyzing frame", e)
        } finally {
            image.close()
        }
    }

    private fun imageToNV21(image: Image): ByteArray {
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

    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)
        return out.toByteArray()
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

    private fun getRotationDegrees(): Int {
        val displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        return if (useFrontCamera) {
            (sensorOrientation + displayDegrees) % 360
        } else {
            (sensorOrientation - displayDegrees + 360) % 360
        }
    }

    private fun choosePreviewSize(
        choices: Array<Size>,
        targetRatio: Float?
    ): Size? {
        if (choices.isEmpty()) {
            return null
        }
        val candidates = choices.toList()

        if (targetRatio == null) {
            return candidates.maxByOrNull { it.width * it.height }
        }

        val normalizedTarget = targetRatio
        val sorted = candidates.sortedWith(
            compareBy<Size> { size ->
                val ratio = if (normalizedTarget < 1f) {
                    size.height.toFloat() / size.width.toFloat()
                } else {
                    size.width.toFloat() / size.height.toFloat()
                }
                kotlin.math.abs(ratio - normalizedTarget)
            }.thenByDescending { size ->
                size.width * size.height
            }
        )
        return sorted.first()
    }

    private fun chooseAnalysisSize(
        choices: Array<Size>,
        targetRatio: Float?
    ): Size? {
        if (choices.isEmpty()) {
            return null
        }

        val capped = choices.filter { it.width <= MAX_ANALYSIS_WIDTH && it.height <= MAX_ANALYSIS_HEIGHT }
        val candidates = if (capped.isNotEmpty()) capped else choices.toList()

        if (targetRatio == null) {
            return candidates.maxByOrNull { it.width * it.height }
        }

        val normalizedTarget = targetRatio
        val sorted = candidates.sortedWith(
            compareBy<Size> { size ->
                val ratio = if (normalizedTarget < 1f) {
                    size.height.toFloat() / size.width.toFloat()
                } else {
                    size.width.toFloat() / size.height.toFloat()
                }
                kotlin.math.abs(ratio - normalizedTarget)
            }.thenByDescending { size ->
                size.width * size.height
            }
        )
        return sorted.first()
    }

    private fun logSizeCandidates(
        label: String,
        choices: Array<Size>,
        targetRatio: Float?
    ) {
        if (choices.isEmpty()) {
            Log.d(TAG, "[CAMERA2] $label sizes: none")
            return
        }

        if (targetRatio == null) {
            Log.d(TAG, "[CAMERA2] $label sizes: ${choices.size}, targetRatio=null")
            return
        }

        val normalizedTarget = targetRatio
        val sorted = choices.sortedWith(
            compareBy<Size> { size ->
                val ratio = if (normalizedTarget < 1f) {
                    size.height.toFloat() / size.width.toFloat()
                } else {
                    size.width.toFloat() / size.height.toFloat()
                }
                kotlin.math.abs(ratio - normalizedTarget)
            }.thenByDescending { size ->
                size.width * size.height
            }
        )

        val top = sorted.take(5).joinToString { size ->
            val ratio = if (normalizedTarget < 1f) {
                size.height.toFloat() / size.width.toFloat()
            } else {
                size.width.toFloat() / size.height.toFloat()
            }
            val diff = kotlin.math.abs(ratio - normalizedTarget)
            "${size.width}x${size.height}(r=${"%.3f".format(ratio)},d=${"%.3f".format(diff)})"
        }

        Log.d(TAG, "[CAMERA2] $label sizes: ${choices.size}, target=${"%.3f".format(normalizedTarget)} top=$top")
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) {
            return
        }
        backgroundThread = HandlerThread("Camera2Background").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.w(TAG, "[CAMERA2] Background thread shutdown interrupted", e)
        } finally {
            backgroundThread = null
            backgroundHandler = null
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
}
