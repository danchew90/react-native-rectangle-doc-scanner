package com.reactnativerectangledocscanner

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var useFrontCamera = false
    private var torchEnabled = false

    var onFrameAnalyzed: ((Rectangle?) -> Unit)? = null

    companion object {
        private const val TAG = "CameraController"
    }

    /**
     * Start camera with preview and analysis
     */
    fun startCamera(
        useFrontCam: Boolean = false,
        enableDetection: Boolean = true
    ) {
        this.useFrontCamera = useFrontCam

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(enableDetection)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Stop camera and release resources
     */
    fun stopCamera() {
        cameraProvider?.unbindAll()
        camera = null
    }

    /**
     * Bind camera use cases (preview, capture, analysis)
     */
    private fun bindCameraUseCases(enableDetection: Boolean) {
        val cameraProvider = cameraProvider ?: return

        // Select camera
        val cameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Preview use case
        val preview = Preview.Builder()
            .setTargetResolution(Size(1080, 1920))
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // Image capture use case (high resolution for document scanning)
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetResolution(Size(1920, 2560))
            .build()

        // Image analysis use case for rectangle detection
        imageAnalysis = if (enableDetection) {
            ImageAnalysis.Builder()
                .setTargetResolution(Size(720, 1280))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzeFrame(imageProxy)
                    }
                }
        } else {
            null
        }

        try {
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()

            // Bind use cases to camera
            val useCases = mutableListOf<UseCase>(preview, imageCapture!!)
            if (imageAnalysis != null) {
                useCases.add(imageAnalysis!!)
            }

            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                *useCases.toTypedArray()
            )

            // Restore torch state if it was enabled
            if (torchEnabled) {
                setTorchEnabled(true)
            }

            Log.d(TAG, "Camera started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera use cases", e)
        }
    }

    /**
     * Analyze frame for rectangle detection
     */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // Note: Simplified - in production you'd convert ImageProxy to proper format
            // For now, we'll skip real-time detection in the analyzer and do it on capture
            onFrameAnalyzed?.invoke(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Capture photo
     */
    fun capturePhoto(
        outputDirectory: File,
        onImageCaptured: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val imageCapture = imageCapture ?: run {
            onError(Exception("Image capture not initialized"))
            return
        }

        val photoFile = File(
            outputDirectory,
            "doc_scan_${System.currentTimeMillis()}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo capture succeeded: ${photoFile.absolutePath}")
                    onImageCaptured(photoFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    onError(exception)
                }
            }
        )
    }

    /**
     * Enable or disable torch (flashlight)
     */
    fun setTorchEnabled(enabled: Boolean) {
        torchEnabled = enabled
        camera?.cameraControl?.enableTorch(enabled)
    }

    /**
     * Switch between front and back camera
     */
    fun switchCamera() {
        useFrontCamera = !useFrontCamera
        startCamera(useFrontCamera)
    }

    /**
     * Check if torch is available
     */
    fun isTorchAvailable(): Boolean {
        return camera?.cameraInfo?.hasFlashUnit() == true
    }

    /**
     * Focus at specific point
     */
    fun focusAt(x: Float, y: Float) {
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    /**
     * Cleanup resources
     */
    fun shutdown() {
        cameraExecutor.shutdown()
        stopCamera()
    }
}
