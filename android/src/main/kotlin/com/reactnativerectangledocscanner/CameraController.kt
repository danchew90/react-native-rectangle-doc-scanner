package com.reactnativerectangledocscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lastFrame = AtomicReference<LastFrame?>()
    private var analysisBound = false
    private var pendingBindAttempts = 0

    private var useFrontCamera = false
    private var detectionEnabled = true

    // For periodic frame capture
    private var isAnalysisActive = false
    private val analysisHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val analysisRunnable = object : Runnable {
        override fun run() {
            if (isAnalysisActive && onFrameAnalyzed != null) {
                captureFrameForAnalysis()
                analysisHandler.postDelayed(this, 200) // Capture every 200ms
            }
        }
    }

    var onFrameAnalyzed: ((Rectangle?, Int, Int) -> Unit)? = null

    companion object {
        private const val TAG = "CameraController"
    }

    private data class LastFrame(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val isFront: Boolean
    )

    fun startCamera(
        useFrontCam: Boolean = false,
        enableDetection: Boolean = true
    ) {
        Log.d(TAG, "[CAMERAX-V6] startCamera called")
        this.useFrontCamera = useFrontCam
        this.detectionEnabled = enableDetection

        if (!hasCameraPermission()) {
            Log.e(TAG, "[CAMERAX-V6] Camera permission not granted")
            return
        }

        if (cameraProviderFuture == null) {
            cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        }

        cameraProviderFuture?.addListener({
            try {
                cameraProvider = cameraProviderFuture?.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "[CAMERAX-V6] Failed to get camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        Log.d(TAG, "[CAMERAX-V6] stopCamera called")
        isAnalysisActive = false
        analysisHandler.removeCallbacks(analysisRunnable)
        cameraProvider?.unbindAll()
        analysisBound = false
    }

    fun capturePhoto(
        outputDirectory: File,
        onImageCaptured: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val frame = lastFrame.get()
        if (frame == null) {
            onError(IllegalStateException("No frame available for capture"))
            return
        }

        cameraExecutor.execute {
            try {
                val photoFile = File(outputDirectory, "doc_scan_${System.currentTimeMillis()}.jpg")
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

                Log.d(TAG, "[CAMERAX-V6] Photo capture succeeded: ${photoFile.absolutePath}")
                onImageCaptured(photoFile)
            } catch (e: Exception) {
                Log.e(TAG, "[CAMERAX-V6] Photo capture failed", e)
                onError(e)
            }
        }
    }

    fun setTorchEnabled(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    fun switchCamera() {
        useFrontCamera = !useFrontCamera
        bindCameraUseCases()
    }

    fun isTorchAvailable(): Boolean {
        return camera?.cameraInfo?.hasFlashUnit() == true
    }

    fun focusAt(x: Float, y: Float) {
        // No-op for now.
    }

    fun shutdown() {
        stopCamera()
        cameraExecutor.shutdown()
    }

    private fun bindCameraUseCases() {
        if (!previewView.isAttachedToWindow || previewView.width == 0 || previewView.height == 0) {
            if (pendingBindAttempts < 5) {
                pendingBindAttempts++
                Log.d(TAG, "[CAMERAX-V9] PreviewView not ready (attached=${previewView.isAttachedToWindow}, w=${previewView.width}, h=${previewView.height}), retrying...")
                previewView.post { bindCameraUseCases() }
            } else {
                Log.w(TAG, "[CAMERAX-V9] PreviewView still not ready after retries, aborting bind")
            }
            return
        }
        pendingBindAttempts = 0

        val provider = cameraProvider ?: return
        provider.unbindAll()
        analysisBound = false
        isAnalysisActive = false

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

        // Build Preview without a fixed size to avoid unsupported stream configs.
        preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also {
                // IMPORTANT: Set surface provider BEFORE binding
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        val cameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Bind Preview ONLY first
        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview
            )

            Log.d(TAG, "[CAMERAX-V9] Preview bound, waiting for capture session to configure...")

            // Log session state after some time
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "[CAMERAX-V9] Camera state check - preview should be working now")
            }, 6000)

        } catch (e: Exception) {
            Log.e(TAG, "[CAMERAX-V8] Failed to bind preview", e)
        }
    }

    // Function removed - this device cannot handle ImageCapture + Preview simultaneously

    private fun captureFrameForAnalysis() {
        val capture = imageCapture ?: return

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                try {
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    val nv21 = image.toNv21()

                    lastFrame.set(
                        LastFrame(
                            nv21,
                            image.width,
                            image.height,
                            rotationDegrees,
                            useFrontCamera
                        )
                    )

                    val frameWidth = if (rotationDegrees == 90 || rotationDegrees == 270) {
                        image.height
                    } else {
                        image.width
                    }

                    val frameHeight = if (rotationDegrees == 90 || rotationDegrees == 270) {
                        image.width
                    } else {
                        image.height
                    }

                    val rectangle = DocumentDetector.detectRectangleInYUV(
                        nv21,
                        image.width,
                        image.height,
                        rotationDegrees
                    )
                    onFrameAnalyzed?.invoke(rectangle, frameWidth, frameHeight)
                } catch (e: Exception) {
                    Log.e(TAG, "[CAMERAX-V6] Error analyzing frame", e)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "[CAMERAX-V6] Frame capture for analysis failed", exception)
            }
        })
    }

    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
        val yuv = YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
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

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
}
