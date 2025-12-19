package com.reactnativerectangledocscanner

import android.content.Context
import android.graphics.ImageFormat
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
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

    var onFrameAnalyzed: ((Rectangle?, Int, Int) -> Unit)? = null

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
        Log.d(TAG, "========================================")
        Log.d(TAG, "[CAMERA_CONTROLLER] startCamera called")
        Log.d(TAG, "[CAMERA_CONTROLLER] useFrontCam: $useFrontCam")
        Log.d(TAG, "[CAMERA_CONTROLLER] enableDetection: $enableDetection")
        Log.d(TAG, "[CAMERA_CONTROLLER] lifecycleOwner: $lifecycleOwner")
        Log.d(TAG, "[CAMERA_CONTROLLER] lifecycleOwner.lifecycle.currentState: ${lifecycleOwner.lifecycle.currentState}")
        Log.d(TAG, "========================================")

        this.useFrontCamera = useFrontCam

        Log.d(TAG, "[CAMERA_CONTROLLER] Getting ProcessCameraProvider instance...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                Log.d(TAG, "[CAMERA_CONTROLLER] ProcessCameraProvider future resolved")
                cameraProvider = cameraProviderFuture.get()
                Log.d(TAG, "[CAMERA_CONTROLLER] Got cameraProvider: $cameraProvider")
                Log.d(TAG, "[CAMERA_CONTROLLER] Calling bindCameraUseCases...")
                bindCameraUseCases(enableDetection)
            } catch (e: Exception) {
                Log.e(TAG, "[CAMERA_CONTROLLER] Failed to start camera", e)
                e.printStackTrace()
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
        Log.d(TAG, "[BIND] bindCameraUseCases called")
        Log.d(TAG, "[BIND] enableDetection: $enableDetection")

        val cameraProvider = cameraProvider
        if (cameraProvider == null) {
            Log.e(TAG, "[BIND] cameraProvider is null, returning")
            return
        }

        // Check lifecycle state
        val lifecycle = lifecycleOwner.lifecycle
        Log.d(TAG, "[BIND] Lifecycle current state: ${lifecycle.currentState}")
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
            Log.e(TAG, "[BIND] Cannot bind camera - lifecycle is destroyed")
            return
        }

        // Select camera
        val cameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        Log.d(TAG, "[BIND] Camera selector: ${if (useFrontCamera) "FRONT" else "BACK"}")

        // Preview use case
        Log.d(TAG, "[BIND] Creating Preview use case...")
        val preview = Preview.Builder()
            // Use aspect ratio to avoid unsupported size combinations on some devices.
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
        Log.d(TAG, "[BIND] Preview created: $preview")

        // Image capture use case (high resolution for document scanning)
        Log.d(TAG, "[BIND] Creating ImageCapture use case...")
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            // Use aspect ratio to avoid unsupported size combinations on some devices.
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()
        Log.d(TAG, "[BIND] ImageCapture created: $imageCapture")

        // Image analysis use case for rectangle detection
        imageAnalysis = if (enableDetection) {
            Log.d(TAG, "[BIND] Creating ImageAnalysis use case...")
            ImageAnalysis.Builder()
                // Use aspect ratio to avoid unsupported size combinations on some devices.
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzeFrame(imageProxy)
                    }
                    Log.d(TAG, "[BIND] ImageAnalysis created and analyzer set: $analysis")
                }
        } else {
            Log.d(TAG, "[BIND] ImageAnalysis disabled")
            null
        }

        try {
            Log.d(TAG, "[BIND] PreviewView: $previewView")
            Log.d(TAG, "[BIND] PreviewView.surfaceProvider: ${previewView.surfaceProvider}")
            Log.d(TAG, "[BIND] PreviewView attached to window: ${previewView.isAttachedToWindow}")
            Log.d(TAG, "[BIND] PreviewView size: ${previewView.width}x${previewView.height}")
            Log.d(TAG, "[BIND] PreviewView implementationMode: ${previewView.implementationMode}")

            // Set surface provider FIRST, before binding - this is critical
            Log.d(TAG, "[BIND] Setting surface provider BEFORE binding...")
            preview.setSurfaceProvider(previewView.surfaceProvider)
            Log.d(TAG, "[BIND] Surface provider set successfully")

            // Unbind all use cases before rebinding
            Log.d(TAG, "[BIND] Unbinding all existing use cases...")
            cameraProvider.unbindAll()

            // Bind use cases to camera
            val useCases = mutableListOf<UseCase>(preview, imageCapture!!)
            if (imageAnalysis != null) {
                useCases.add(imageAnalysis!!)
            }
            Log.d(TAG, "[BIND] Total use cases to bind: ${useCases.size}")

            Log.d(TAG, "[BIND] Binding to lifecycle...")
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                *useCases.toTypedArray()
            )
            Log.d(TAG, "[BIND] Bound to lifecycle successfully, camera: $camera")

            // Restore torch state if it was enabled
            if (torchEnabled) {
                Log.d(TAG, "[BIND] Restoring torch state...")
                setTorchEnabled(true)
            }

            Log.d(TAG, "[BIND] ========================================")
            Log.d(TAG, "[BIND] Camera started successfully!")
            Log.d(TAG, "[BIND] hasFlashUnit: ${camera?.cameraInfo?.hasFlashUnit()}")
            Log.d(TAG, "[BIND] ========================================")
        } catch (e: Exception) {
            Log.e(TAG, "[BIND] Failed to bind camera use cases", e)
            e.printStackTrace()
        }
    }

    /**
     * Analyze frame for rectangle detection
     */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
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

            if (imageProxy.format != ImageFormat.YUV_420_888 || imageProxy.planes.size < 3) {
                onFrameAnalyzed?.invoke(null, frameWidth, frameHeight)
                return
            }

            val nv21 = imageProxyToNV21(imageProxy)
            val rectangle = DocumentDetector.detectRectangleInYUV(
                nv21,
                imageProxy.width,
                imageProxy.height,
                rotationDegrees
            )

            onFrameAnalyzed?.invoke(rectangle, frameWidth, frameHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame", e)
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
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
            onFrameAnalyzed?.invoke(null, frameWidth, frameHeight)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Convert ImageProxy (YUV_420_888) to NV21 byte array
     */
    private fun imageProxyToNV21(image: ImageProxy): ByteArray {
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
                    if (exception.imageCaptureError == ImageCapture.ERROR_CAMERA_CLOSED) {
                        Log.w(TAG, "Camera was closed during capture, attempting restart")
                        stopCamera()
                        startCamera(useFrontCamera, true)
                    }
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
