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

    private var cameraId: String? = null
    private var sensorOrientation: Int = 0
    private var previewSize: Size? = null
    private var analysisSize: Size? = null
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
        private const val MAX_PREVIEW_WIDTH = 1280
        private const val MAX_PREVIEW_HEIGHT = 720
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

        if (previewView.isAvailable) {
            openCamera()
        } else {
            previewView.surfaceTextureListener = textureListener
        }
    }

    fun stopCamera() {
        Log.d(TAG, "[CAMERA2] stopCamera called")
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
        val previewChoices = streamConfig?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        val analysisChoices = streamConfig?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()

        previewSize = chooseSize(previewChoices, MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT)
        analysisSize = chooseSize(analysisChoices, MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT)
        Log.d(TAG, "[CAMERA2] Selected sizes - preview: $previewSize, analysis: $analysisSize")
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
        val previewSize = previewSize ?: return
        val analysisSize = analysisSize ?: previewSize

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

    private fun updatePreviewTransform() {
        val previewSize = previewSize ?: return
        val viewWidth = previewView.width
        val viewHeight = previewView.height
        if (viewWidth == 0 || viewHeight == 0) {
            return
        }

        val rotationDegrees = getRotationDegrees()
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = if (rotationDegrees == 90 || rotationDegrees == 270) {
            RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        } else {
            RectF(0f, 0f, previewSize.width.toFloat(), previewSize.height.toFloat())
        }
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        // Fill the view while preserving aspect ratio (center-crop).
        matrix.setRectToRect(bufferRect, viewRect, Matrix.ScaleToFit.FILL)

        when (rotationDegrees) {
            90 -> matrix.postRotate(90f, centerX, centerY)
            180 -> matrix.postRotate(180f, centerX, centerY)
            270 -> matrix.postRotate(270f, centerX, centerY)
        }

        previewView.setTransform(matrix)
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

    private fun chooseSize(choices: Array<Size>, maxWidth: Int, maxHeight: Int): Size? {
        if (choices.isEmpty()) {
            return null
        }
        val filtered = choices.filter { it.width <= maxWidth && it.height <= maxHeight }
        val candidates = if (filtered.isNotEmpty()) filtered else choices.toList()
        return candidates.sortedBy { it.width * it.height }.last()
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
