package com.reactnativerectangledocscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.AspectRatio
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
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    private var useFrontCamera = false
    private var detectionEnabled = true

    var onFrameAnalyzed: ((Rectangle?, Int, Int) -> Unit)? = null

    companion object {
        private const val TAG = "CameraController"
        private const val ANALYSIS_WIDTH = 1280
        private const val ANALYSIS_HEIGHT = 720
    }

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
    }

    fun capturePhoto(
        outputDirectory: File,
        onImageCaptured: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val capture = imageCapture
        if (capture == null) {
            onError(IllegalStateException("ImageCapture not initialized"))
            return
        }

        val photoFile = File(outputDirectory, "doc_scan_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "[CAMERAX] Photo capture succeeded: ${photoFile.absolutePath}")
                    onImageCaptured(photoFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "[CAMERAX] Photo capture failed", exception)
                    onError(exception)
                }
            }
        )
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

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT))
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, DocumentAnalyzer())
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()

        val cameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis,
                imageCapture
            )
            Log.d(TAG, "[CAMERAX] Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERAX] Failed to bind camera", e)
        }
    }

    private inner class DocumentAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
            try {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val nv21 = imageProxy.toNv21()

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

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
}
