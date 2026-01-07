package com.reactnativerectangledocscanner

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
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
 * Handles Preview (via TextureView), ImageAnalysis (ML Kit + OpenCV), and ImageCapture
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val textureView: TextureView
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

        // Preview UseCase with TextureView
        Log.d(TAG, "[CAMERAX] TextureView size: ${textureView.width}x${textureView.height}")
        Log.d(TAG, "[CAMERAX] TextureView visibility: ${textureView.visibility}")
        Log.d(TAG, "[CAMERAX] TextureView isAvailable: ${textureView.isAvailable}")

        // Force portrait orientation (app is portrait-only)
        val targetRotation = android.view.Surface.ROTATION_0
        Log.d(TAG, "[CAMERAX] Setting target rotation to ROTATION_0 (portrait-only app)")

        preview = Preview.Builder()
            .setTargetRotation(targetRotation)  // Force portrait
            .build()
            .also { previewUseCase ->
                Log.d(TAG, "[CAMERAX] Setting SurfaceProvider for TextureView...")

                // Set custom SurfaceProvider for TextureView
                previewUseCase.setSurfaceProvider(ContextCompat.getMainExecutor(context)) { request ->
                    Log.d(TAG, "[CAMERAX] Surface requested - resolution: ${request.resolution}")

                    val surfaceTexture = textureView.surfaceTexture
                    if (surfaceTexture != null) {
                        Log.d(TAG, "[CAMERAX] SurfaceTexture available, providing surface")
                        surfaceTexture.setDefaultBufferSize(
                            request.resolution.width,
                            request.resolution.height
                        )
                        val surface = Surface(surfaceTexture)

                        // Apply transform BEFORE providing surface
                        updateTextureViewTransform(
                            request.resolution.width,
                            request.resolution.height
                        )

                        request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { result ->
                            Log.d(TAG, "[CAMERAX] Surface provided - result: ${result.resultCode}")
                            // Don't release surface - let CameraX manage it
                        }
                    } else {
                        Log.e(TAG, "[CAMERAX] SurfaceTexture is null! Waiting for TextureView to be ready...")
                        // Set listener for when SurfaceTexture becomes available
                        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                Log.d(TAG, "[CAMERAX] SurfaceTexture now available ($width x $height)")
                                st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                                val surface = Surface(st)

                                // Apply transform BEFORE providing surface
                                updateTextureViewTransform(
                                    request.resolution.width,
                                    request.resolution.height
                                )

                                request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { result ->
                                    Log.d(TAG, "[CAMERAX] Surface provided (delayed) - result: ${result.resultCode}")
                                    // Don't release surface - let CameraX manage it
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                        }
                    }
                }

                Log.d(TAG, "[CAMERAX] SurfaceProvider set successfully")
            }

        // ImageAnalysis UseCase for document detection
        imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(1280, 960))  // Limit resolution for analysis
            .setTargetRotation(targetRotation)  // Match preview rotation
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
            .setTargetRotation(targetRotation)  // Match preview rotation
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
                textureView.post {
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
        // CameraX with TextureView - no manual transform needed
        Log.d(TAG, "[CAMERAX] Transform refresh requested - handled automatically")
    }

    // Simplified coordinate mapping for TextureView
    fun mapRectangleToView(rectangle: Rectangle?, imageWidth: Int, imageHeight: Int): Rectangle? {
        if (rectangle == null || imageWidth <= 0 || imageHeight <= 0) return null

        // Simple proportional scaling for TextureView
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()

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
        // With TextureView, the viewport is simply the view bounds
        val width = textureView.width.toFloat()
        val height = textureView.height.toFloat()

        if (width <= 0 || height <= 0) return null

        return android.graphics.RectF(0f, 0f, width, height)
    }

    private fun updateTextureViewTransform(bufferWidth: Int, bufferHeight: Int) {
        val viewWidth = textureView.width
        val viewHeight = textureView.height

        if (viewWidth == 0 || viewHeight == 0) {
            Log.w(TAG, "[TRANSFORM] View size is 0, skipping transform")
            return
        }

        Log.d(TAG, "[TRANSFORM] View: ${viewWidth}x${viewHeight}, Buffer: ${bufferWidth}x${bufferHeight}")

        val matrix = android.graphics.Matrix()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        // Camera sensor is landscape (1440x1088), but we want portrait display
        // Rotate 90 degrees clockwise to make it portrait
        matrix.postRotate(90f, centerX, centerY)

        // After rotation, the buffer dimensions are swapped
        val rotatedBufferWidth = bufferHeight  // 1088
        val rotatedBufferHeight = bufferWidth  // 1440

        // Scale to fill the view while maintaining aspect ratio
        val scaleX = viewWidth.toFloat() / rotatedBufferWidth.toFloat()
        val scaleY = viewHeight.toFloat() / rotatedBufferHeight.toFloat()
        val scale = scaleX.coerceAtLeast(scaleY)  // Use max to fill

        Log.d(TAG, "[TRANSFORM] ScaleX: $scaleX, ScaleY: $scaleY, Using: $scale")

        matrix.postScale(scale, scale, centerX, centerY)

        textureView.setTransform(matrix)
        Log.d(TAG, "[TRANSFORM] Transform applied successfully")
    }
}
