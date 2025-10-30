package com.reactnativerectangledocscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraView with real-time document detection, grid overlay, and rectangle overlay
 * Matches iOS implementation behavior
 */
class CameraView(context: Context) : FrameLayout(context) {
    private val TAG = "CameraView"

    private val previewView: PreviewView
    private val overlayView: OverlayView

    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Callback for detected rectangles
    var onRectangleDetected: ((Rectangle?) -> Unit)? = null

    init {
        // Create preview view
        previewView = PreviewView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        addView(previewView)

        // Create overlay view for grid and rectangle
        overlayView = OverlayView(context)
        addView(overlayView)

        Log.d(TAG, "CameraView initialized")
    }

    /**
     * Start camera and document detection
     */
    fun startCamera() {
        if (!hasPermissions()) {
            Log.e(TAG, "Camera permissions not granted")
            return
        }

        cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture?.addListener({
            try {
                cameraProvider = cameraProviderFuture?.get()
                bindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Stop camera and release resources
     */
    fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }

    /**
     * Bind camera use cases
     */
    private fun bindCamera() {
        val lifecycleOwner = context as? LifecycleOwner
        if (lifecycleOwner == null) {
            Log.e(TAG, "Context is not a LifecycleOwner")
            return
        }

        val provider = cameraProvider ?: return

        // Unbind all before rebinding
        provider.unbindAll()

        // Preview use case
        preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // Image analysis use case for document detection
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, DocumentAnalyzer())
            }

        // Select back camera
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            // Bind use cases to camera
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )

            Log.d(TAG, "Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }

    /**
     * Check if camera permissions are granted
     */
    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Image analyzer for document detection
     */
    private inner class DocumentAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            try {
                val nv21 = imageProxy.toNv21()
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

                val rectangle = DocumentDetector.detectRectangleInYUV(
                    nv21,
                    imageProxy.width,
                    imageProxy.height,
                    rotationDegrees
                )

                val transformedRectangle = rectangle?.let {
                    val viewWidth = if (overlayView.width > 0) overlayView.width else width
                    val viewHeight = if (overlayView.height > 0) overlayView.height else height

                    DocumentDetector.transformRectangleToViewCoordinates(
                        it,
                        frameWidth,
                        frameHeight,
                        viewWidth,
                        viewHeight
                    )
                }

                post {
                    overlayView.setDetectedRectangle(transformedRectangle)
                    onRectangleDetected?.invoke(transformedRectangle)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to analyze frame", e)
                post {
                    overlayView.setDetectedRectangle(null)
                    onRectangleDetected?.invoke(null)
                }
            } finally {
                imageProxy.close()
            }
        }
    }

    /**
     * Overlay view for grid and rectangle
     */
    private class OverlayView(context: Context) : View(context) {
        private var detectedRectangle: Rectangle? = null

        private val gridPaint = Paint().apply {
            color = Color.parseColor("#80FFFFFF")  // 50% white
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        private val rectanglePaint = Paint().apply {
            color = Color.parseColor("#00FF00")  // Green
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }

        private val rectangleFillPaint = Paint().apply {
            color = Color.parseColor("#2000FF00")  // 12% green
            style = Paint.Style.FILL
        }

        init {
            setWillNotDraw(false)
        }

        fun setDetectedRectangle(rectangle: Rectangle?) {
            detectedRectangle = rectangle
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw 3x3 grid
            drawGrid(canvas)

            // Draw detected rectangle if available
            detectedRectangle?.let { rect ->
                drawRectangle(canvas, rect)
            }
        }

        private fun drawGrid(canvas: Canvas) {
            val width = width.toFloat()
            val height = height.toFloat()

            // Draw vertical lines (2 lines for 3x3 grid)
            canvas.drawLine(width / 3f, 0f, width / 3f, height, gridPaint)
            canvas.drawLine(2f * width / 3f, 0f, 2f * width / 3f, height, gridPaint)

            // Draw horizontal lines (2 lines for 3x3 grid)
            canvas.drawLine(0f, height / 3f, width, height / 3f, gridPaint)
            canvas.drawLine(0f, 2f * height / 3f, width, 2f * height / 3f, gridPaint)
        }

        private fun drawRectangle(canvas: Canvas, rect: Rectangle) {
            val path = Path().apply {
                moveTo(rect.topLeft.x, rect.topLeft.y)
                lineTo(rect.topRight.x, rect.topRight.y)
                lineTo(rect.bottomRight.x, rect.bottomRight.y)
                lineTo(rect.bottomLeft.x, rect.bottomLeft.y)
                close()
            }

            // Draw filled rectangle
            canvas.drawPath(path, rectangleFillPaint)

            // Draw rectangle outline
            canvas.drawPath(path, rectanglePaint)
        }
    }
}
