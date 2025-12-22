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
import android.util.Size
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
    private var camera: Camera? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lastFrame = AtomicReference<LastFrame?>()
    private var analysisBound = false

    private var useFrontCamera = false
    private var detectionEnabled = true

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
        Log.d(TAG, "[CAMERAX] startCamera called")
        this.useFrontCamera = useFrontCam
        this.detectionEnabled = enableDetection

        if (!hasCameraPermission()) {
            Log.e(TAG, "[CAMERAX] Camera permission not granted")
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
                Log.e(TAG, "[CAMERAX] Failed to get camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        Log.d(TAG, "[CAMERAX] stopCamera called")
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

                Log.d(TAG, "[CAMERAX] Photo capture succeeded: ${photoFile.absolutePath}")
                onImageCaptured(photoFile)
            } catch (e: Exception) {
                Log.e(TAG, "[CAMERAX] Photo capture failed", e)
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
        val provider = cameraProvider ?: return
        provider.unbindAll()
        analysisBound = false

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

        // Build Preview ONLY first
        preview = Preview.Builder()
            .setTargetResolution(Size(1280, 720))
            .setTargetRotation(rotation)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        val cameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Step 1: Bind Preview ONLY first
        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview
            )
            Log.d(TAG, "[CAMERAX-FIX-V4] Preview bound, waiting before adding analysis...")

            // Step 2: Add ImageAnalysis after a delay to let Preview session stabilize
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                bindImageAnalysis(provider, cameraSelector, rotation)
            }, 500)

        } catch (e: Exception) {
            Log.e(TAG, "[CAMERAX-FIX-V4] Failed to bind preview", e)
            analysisBound = false
        }
    }

    private fun bindImageAnalysis(provider: ProcessCameraProvider, cameraSelector: CameraSelector, rotation: Int) {
        if (analysisBound) return

        try {
            // Build ImageAnalysis
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
                .setTargetResolution(Size(640, 480))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, DocumentAnalyzer())
                }

            // Rebind with both Preview and ImageAnalysis
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            analysisBound = true
            Log.d(TAG, "[CAMERAX-FIX-V4] ImageAnalysis added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERAX-FIX-V4] Failed to add ImageAnalysis", e)
            analysisBound = false
        }
    }

    private inner class DocumentAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
            try {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val nv21 = imageProxy.toNv21()
                lastFrame.set(
                    LastFrame(
                        nv21,
                        imageProxy.width,
                        imageProxy.height,
                        rotationDegrees,
                        useFrontCamera
                    )
                )

                val frameWidth = if (rotationDegrees == 90 || rotationDegrees == 270) {
                    imageProxy.height
                } else {
                    imageProxy.width
                }

                val frameHeight = if (rotationDegrees == 90 || rotationDegrees == 270) {
                    imageProxy.width
                } else {
                    imageProxy.height
                }

                if (detectionEnabled) {
                    val rectangle = DocumentDetector.detectRectangleInYUV(
                        nv21,
                        imageProxy.width,
                        imageProxy.height,
                        rotationDegrees
                    )
                    onFrameAnalyzed?.invoke(rectangle, frameWidth, frameHeight)
                } else {
                    onFrameAnalyzed?.invoke(null, frameWidth, frameHeight)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[CAMERAX] Error analyzing frame", e)
            } finally {
                imageProxy.close()
            }
        }
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
