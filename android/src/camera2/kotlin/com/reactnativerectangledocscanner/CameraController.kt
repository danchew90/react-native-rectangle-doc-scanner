package com.reactnativerectangledocscanner

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX-based camera controller for document scanning
 * Handles Preview, ImageAnalysis (ML Kit + OpenCV), and ImageCapture
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var useFrontCamera = false
    private var detectionEnabled = true

    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build()
    )

    var onFrameAnalyzed: ((Rectangle?, Int, Int) -> Unit)? = null

    private var pendingCapture: PendingCapture? = null

    companion object {
        private const val TAG = "CameraController"
        private const val ANALYSIS_TARGET_RESOLUTION = 1280 // Max dimension for analysis
    }

    private data class PendingCapture(
        val outputDirectory: File,
        val onImageCaptured: (File) -> Unit,
        val onError: (Exception) -> Unit
    )

    fun startCamera(useFront: Boolean = false, enableDetection: Boolean = true) {
        Log.d(TAG, "[CAMERAX] startCamera called: useFront=$useFront, enableDetection=$enableDetection")

        useFrontCamera = useFront
        detectionEnabled = enableDetection

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "[CAMERAX] Failed to start camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: run {
            Log.e(TAG, "[CAMERAX] CameraProvider is null")
            return
        }

        // Check lifecycle state
        Log.d(TAG, "[CAMERAX] Current lifecycle state: ${lifecycleOwner.lifecycle.currentState}")
        if (lifecycleOwner.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED) {
            Log.w(TAG, "[CAMERAX] Lifecycle is not RESUMED, camera stream may not start")
        }

        // Select camera
        val cameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Preview UseCase
        Log.d(TAG, "[CAMERAX] PreviewView size: ${previewView.width}x${previewView.height}")
        Log.d(TAG, "[CAMERAX] PreviewView visibility: ${previewView.visibility}")
        Log.d(TAG, "[CAMERAX] PreviewView scaleType: ${previewView.scaleType}")
        Log.d(TAG, "[CAMERAX] PreviewView implementationMode: ${previewView.implementationMode}")
        preview = Preview.Builder()
            .setTargetResolution(android.util.Size(1920, 1080))  // 16:9 resolution
            .build()
            .also { previewUseCase ->
                Log.d(TAG, "[CAMERAX] Setting SurfaceProvider...")

                // Set surface provider with custom executor to see when surface is requested
                previewUseCase.setSurfaceProvider(ContextCompat.getMainExecutor(context)) { request ->
                    Log.d(TAG, "[CAMERAX] ===== SURFACE REQUESTED =====")
                    Log.d(TAG, "[CAMERAX] Surface resolution: ${request.resolution}")
                    Log.d(TAG, "[CAMERAX] Surface camera: ${request.camera}")

                    // Get the surface from PreviewView and provide it to the request
                    val surfaceTexture = (previewView.getChildAt(0) as? android.view.TextureView)?.surfaceTexture
                    if (surfaceTexture != null) {
                        Log.d(TAG, "[CAMERAX] Got SurfaceTexture from PreviewView")
                        surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                        val surface = android.view.Surface(surfaceTexture)
                        request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { result ->
                            Log.d(TAG, "[CAMERAX] Surface result: ${result.resultCode}")
                            surface.release()
                        }
                    } else {
                        Log.e(TAG, "[CAMERAX] Failed to get SurfaceTexture - using default provider")
                        // Fallback to default behavior
                        previewView.surfaceProvider.onSurfaceRequested(request)
                    }
                }

                Log.d(TAG, "[CAMERAX] SurfaceProvider set successfully")
            }

        // ImageAnalysis UseCase for document detection
        imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(1280, 960))  // Limit resolution for analysis
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (detectionEnabled) {
                        analyzeImage(imageProxy)
                    } else {
                        imageProxy.close()
                    }
                }
            }

        // ImageCapture UseCase
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        try {
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()

            // Bind use cases to camera - start with Preview and ImageCapture only
            Log.d(TAG, "[CAMERAX] Binding Preview and ImageCapture first...")
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )

            Log.d(TAG, "[CAMERAX] Preview and ImageCapture bound successfully")

            // Add ImageAnalysis after a short delay to avoid timeout
            if (detectionEnabled) {
                previewView.post {
                    try {
                        Log.d(TAG, "[CAMERAX] Adding ImageAnalysis...")
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture,
                            imageAnalyzer
                        )
                        Log.d(TAG, "[CAMERAX] ImageAnalysis added successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "[CAMERAX] Failed to add ImageAnalysis", e)
                    }
                }
            }

            Log.d(TAG, "[CAMERAX] Camera bound successfully")

            // Monitor preview stream state
            previewView.previewStreamState.observe(lifecycleOwner) { state ->
                Log.d(TAG, "[CAMERAX] PreviewStreamState changed: $state")
                when (state) {
                    androidx.camera.view.PreviewView.StreamState.IDLE ->
                        Log.w(TAG, "[CAMERAX] Preview stream is IDLE")
                    androidx.camera.view.PreviewView.StreamState.STREAMING ->
                        Log.d(TAG, "[CAMERAX] Preview stream is STREAMING ✓")
                    else ->
                        Log.d(TAG, "[CAMERAX] Preview stream state: $state")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "[CAMERAX] Use case binding failed", e)
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyzeImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val imageWidth = imageProxy.width
        val imageHeight = imageProxy.height

        // Try ML Kit first
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        objectDetector.process(inputImage)
            .addOnSuccessListener { objects ->
                if (objects.isEmpty()) {
                    // No objects detected, fallback to OpenCV
                    fallbackToOpenCV(imageProxy, rotationDegrees)
                    return@addOnSuccessListener
                }

                // Find largest object
                val best = objects.maxByOrNull { obj ->
                    val box = obj.boundingBox
                    box.width() * box.height()
                }
                val mlBox = best?.boundingBox

                // Refine with OpenCV
                val nv21 = imageProxyToNV21(imageProxy)
                val rectangle = if (nv21 != null) {
                    try {
                        refineWithOpenCv(nv21, imageWidth, imageHeight, rotationDegrees, mlBox)
                    } catch (e: Exception) {
                        Log.w(TAG, "[CAMERAX] OpenCV refinement failed", e)
                        null
                    }
                } else {
                    null
                }

                val frameWidth = if (rotationDegrees == 90 || rotationDegrees == 270) imageHeight else imageWidth
                val frameHeight = if (rotationDegrees == 90 || rotationDegrees == 270) imageWidth else imageHeight

                onFrameAnalyzed?.invoke(rectangle, frameWidth, frameHeight)
                imageProxy.close()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "[CAMERAX] ML Kit detection failed, using OpenCV", e)
                fallbackToOpenCV(imageProxy, rotationDegrees)
            }
    }

    private fun fallbackToOpenCV(imageProxy: ImageProxy, rotationDegrees: Int) {
        val nv21 = imageProxyToNV21(imageProxy)
        val rectangle = if (nv21 != null) {
            try {
                DocumentDetector.detectRectangleInYUV(
                    nv21,
                    imageProxy.width,
                    imageProxy.height,
                    rotationDegrees
                )
            } catch (e: Exception) {
                Log.w(TAG, "[CAMERAX] OpenCV fallback failed", e)
                null
            }
        } else {
            null
        }

        val frameWidth = if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.height else imageProxy.width
        val frameHeight = if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.width else imageProxy.height

        onFrameAnalyzed?.invoke(rectangle, frameWidth, frameHeight)
        imageProxy.close()
    }

    private fun imageProxyToNV21(imageProxy: ImageProxy): ByteArray? {
        return try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            nv21
        } catch (e: Exception) {
            Log.e(TAG, "[CAMERAX] Failed to convert ImageProxy to NV21", e)
            null
        }
    }

    private fun refineWithOpenCv(
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
        mlBox: android.graphics.Rect?
    ): Rectangle? {
        return try {
            DocumentDetector.detectRectangleInYUV(nv21, width, height, rotation)
        } catch (e: Exception) {
            Log.w(TAG, "[CAMERAX] OpenCV detection failed", e)
            null
        }
    }

    fun capturePhoto(
        outputDirectory: File,
        onImageCaptured: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val imageCapture = imageCapture ?: run {
            onError(IllegalStateException("ImageCapture not initialized"))
            return
        }

        pendingCapture = PendingCapture(outputDirectory, onImageCaptured, onError)

        val photoFile = File(outputDirectory, "doc_scan_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "[CAMERAX] Image saved: ${photoFile.absolutePath}")
                    onImageCaptured(photoFile)
                    pendingCapture = null
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "[CAMERAX] Image capture failed", exception)
                    onError(exception)
                    pendingCapture = null
                }
            }
        )
    }

    fun setTorchEnabled(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    fun stopCamera() {
        Log.d(TAG, "[CAMERAX] stopCamera called")
        cameraProvider?.unbindAll()
        camera = null
    }

    fun shutdown() {
        stopCamera()
        objectDetector.close()
        cameraExecutor.shutdown()
    }

    fun refreshTransform() {
        // CameraX handles transform automatically via PreviewView
        // No manual matrix calculation needed!
        Log.d(TAG, "[CAMERAX] Transform refresh requested - handled automatically by PreviewView")
    }

    // Simplified coordinate mapping - PreviewView handles most of the work
    fun mapRectangleToView(rectangle: Rectangle?, imageWidth: Int, imageHeight: Int): Rectangle? {
        if (rectangle == null || imageWidth <= 0 || imageHeight <= 0) return null

        // CameraX PreviewView with FILL_CENTER handles scaling and centering
        // We just need to scale the coordinates proportionally
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()

        if (viewWidth <= 0 || viewHeight <= 0) return null

        // Simple proportional scaling
        val scaleX = viewWidth / imageWidth.toFloat()
        val scaleY = viewHeight / imageHeight.toFloat()

        fun scalePoint(point: org.opencv.core.Point): org.opencv.core.Point {
            return org.opencv.core.Point(
                point.x * scaleX,
                point.y * scaleY
            )
        }

        return Rectangle(
            scalePoint(rectangle.topLeft),
            scalePoint(rectangle.topRight),
            scalePoint(rectangle.bottomLeft),
            scalePoint(rectangle.bottomRight)
        )
    }

    fun getPreviewViewport(): android.graphics.RectF? {
        // With CameraX PreviewView, the viewport is simply the view bounds
        val width = previewView.width.toFloat()
        val height = previewView.height.toFloat()

        if (width <= 0 || height <= 0) return null

        return android.graphics.RectF(0f, 0f, width, height)
    }
}
