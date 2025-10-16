package com.reactnativerectangledocscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.Image
import android.util.AttributeSet
import android.util.Log
import android.util.Size as AndroidSize
import android.widget.FrameLayout
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.events.RCTEventEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size as MatSize
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

@androidx.camera.core.ExperimentalGetImage
class RNRDocScannerView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

  var detectionCountBeforeCapture: Int = 8
  var autoCapture: Boolean = true
  var enableTorch: Boolean = false
    set(value) {
      field = value
      updateTorchMode(value)
    }
  var quality: Int = 90
  var useBase64: Boolean = false

  private val previewView: PreviewView = PreviewView(context)
  private var cameraProvider: ProcessCameraProvider? = null
  private var camera: Camera? = null
  private var imageCapture: ImageCapture? = null
  private var imageAnalysis: ImageAnalysis? = null
  private var cameraExecutor: ExecutorService? = null
  private val scope = CoroutineScope(Dispatchers.Main + Job())

  private var currentStableCounter: Int = 0
  private var lastQuad: QuadPoints? = null
  private var lastFrameSize: AndroidSize? = null
  private var capturePromise: Promise? = null
  private var captureInFlight: Boolean = false

  init {
    setBackgroundColor(Color.BLACK)
    addView(
      previewView,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
    )

    if (!OpenCVLoader.initDebug()) {
      Log.w(TAG, "Failed to initialise OpenCV - detection will not run.")
    }

    initializeCamera()
  }

  private fun initializeCamera() {
    if (!hasCameraPermission()) {
      Log.w(TAG, "Camera permission missing. Detection will not start.")
      return
    }

    cameraExecutor = Executors.newSingleThreadExecutor()
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener(
      {
        scope.launch {
          try {
            cameraProvider = providerFuture.await()
            bindCameraUseCases()
          } catch (error: Exception) {
            Log.e(TAG, "Failed to initialise camera", error)
          }
        }
      },
      ContextCompat.getMainExecutor(context),
    )
  }

  private fun hasCameraPermission(): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
  }

  private fun bindCameraUseCases() {
    val provider = cameraProvider ?: return
    val lifecycleOwner = context as? LifecycleOwner
    if (lifecycleOwner == null) {
      Log.w(TAG, "Context is not a LifecycleOwner; cannot bind camera use cases.")
      return
    }
    provider.unbindAll()

    val preview = Preview.Builder()
      .setTargetAspectRatio(AspectRatio.RATIO_4_3)
      .setTargetRotation(previewView.display.rotation)
      .build()
      .also { it.setSurfaceProvider(previewView.surfaceProvider) }

    imageCapture = ImageCapture.Builder()
      .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
      .setTargetAspectRatio(AspectRatio.RATIO_4_3)
      .setTargetRotation(previewView.display.rotation)
      .build()

    imageAnalysis = ImageAnalysis.Builder()
      .setTargetAspectRatio(AspectRatio.RATIO_4_3)
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
      .build()
      .also { analysis ->
        analysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
          try {
            processFrame(imageProxy)
          } catch (error: Exception) {
            Log.e(TAG, "Frame processing error", error)
            imageProxy.close()
          }
        }
      }

    val selector = CameraSelector.Builder()
      .requireLensFacing(CameraSelector.LENS_FACING_BACK)
      .build()

    camera = provider.bindToLifecycle(
      lifecycleOwner,
      selector,
      preview,
      imageCapture,
      imageAnalysis,
    )

    updateTorchMode(enableTorch)
  }

  private fun updateTorchMode(enabled: Boolean) {
    camera?.cameraControl?.enableTorch(enabled)
  }

  private fun processFrame(imageProxy: ImageProxy) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
      imageProxy.close()
      return
    }

    val frameSize = AndroidSize(imageProxy.width, imageProxy.height)
    lastFrameSize = frameSize

    val mat = yuvToMat(mediaImage, imageProxy.imageInfo.rotationDegrees)
    val detectedQuad = detectDocument(mat, frameSize)

    imageProxy.close()

    scope.launch {
      emitDetectionResult(detectedQuad, frameSize)
      if (autoCapture && detectedQuad != null && currentStableCounter >= detectionCountBeforeCapture && !captureInFlight) {
        triggerAutoCapture()
      }
    }
  }

  private fun emitDetectionResult(quad: QuadPoints?, frameSize: AndroidSize) {
    val reactContext = context as? ReactContext ?: return
    val event: WritableMap = Arguments.createMap().apply {
      if (quad != null) {
        val quadMap = Arguments.createMap().apply {
          putMap("topLeft", quad.topLeft.toWritable())
          putMap("topRight", quad.topRight.toWritable())
          putMap("bottomRight", quad.bottomRight.toWritable())
          putMap("bottomLeft", quad.bottomLeft.toWritable())
        }
        putMap("rectangleCoordinates", quadMap)
        currentStableCounter = (currentStableCounter + 1).coerceAtMost(detectionCountBeforeCapture)
        lastQuad = quad
      } else {
        putNull("rectangleCoordinates")
        currentStableCounter = 0
        lastQuad = null
      }
      putInt("stableCounter", currentStableCounter)
      putDouble("frameWidth", frameSize.width.toDouble())
      putDouble("frameHeight", frameSize.height.toDouble())
    }

    reactContext
      .getJSModule(RCTEventEmitter::class.java)
      ?.receiveEvent(id, "onRectangleDetect", event)
  }

  private fun triggerAutoCapture() {
    startCapture(null)
  }

  fun capture(promise: Promise) {
    startCapture(promise)
  }

  private fun startCapture(promise: Promise?) {
    if (captureInFlight) {
      promise?.reject("capture_in_progress", "A capture request is already running.")
      return
    }

    val imageCapture = this.imageCapture
    if (imageCapture == null) {
      promise?.reject("capture_unavailable", "Image capture is not initialised yet.")
      return
    }

    val outputDir = context.cacheDir
    val photoFile = File(
      outputDir,
      "docscan-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.jpg",
    )

    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    captureInFlight = true
    pendingPromise = promise

    imageCapture.takePicture(
      outputOptions,
      cameraExecutor ?: Executors.newSingleThreadExecutor(),
      object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
          scope.launch {
            handleCaptureSuccess(photoFile)
          }
        }

        override fun onError(exception: ImageCaptureException) {
          scope.launch {
            handleCaptureFailure(exception)
          }
        }
      },
    )
  }

  private suspend fun handleCaptureSuccess(file: File) {
    withContext(Dispatchers.IO) {
      try {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        val width = bitmap.width
        val height = bitmap.height

        val frameSize = lastFrameSize
        val quadForCapture = if (lastQuad != null && frameSize != null) {
          val scaleX = width.toDouble() / frameSize.width.toDouble()
          val scaleY = height.toDouble() / frameSize.height.toDouble()
          lastQuad!!.scaled(scaleX, scaleY)
        } else {
          null
        }

        val croppedPath = if (quadForCapture != null) {
          cropAndSave(bitmap, quadForCapture, file.parentFile ?: context.cacheDir)
        } else {
          file.absolutePath
        }

        val event = Arguments.createMap().apply {
          putString("initialImage", "file://${file.absolutePath}")
          putString("croppedImage", "file://$croppedPath")
          putDouble("width", width.toDouble())
          putDouble("height", height.toDouble())
        }

       withContext(Dispatchers.Main) {
         emitPictureTaken(event)
         pendingPromise?.resolve(event)
         resetAfterCapture()
       }
      } catch (error: Exception) {
        bitmap.recycle()

        withContext(Dispatchers.Main) {
          handleCaptureFailure(error)
        }
      }
    }
  }

  private fun handleCaptureFailure(error: Exception) {
    pendingPromise?.reject(error)
    resetAfterCapture()
  }

  private fun resetAfterCapture() {
    captureInFlight = false
    pendingPromise = null
    currentStableCounter = 0
  }

  private fun emitPictureTaken(payload: WritableMap) {
    val reactContext = context as? ReactContext ?: return
    reactContext
      .getJSModule(RCTEventEmitter::class.java)
      ?.receiveEvent(id, "onPictureTaken", payload)
  }

  fun reset() {
    currentStableCounter = 0
    lastQuad = null
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    cameraExecutor?.shutdown()
    cameraExecutor = null
    cameraProvider?.unbindAll()
  }

  // region Detection helpers

  private fun yuvToMat(image: Image, rotationDegrees: Int): Mat {
    val bufferY = image.planes[0].buffer.toByteArray()
    val bufferU = image.planes[1].buffer.toByteArray()
    val bufferV = image.planes[2].buffer.toByteArray()

    val yuvBytes = ByteArray(bufferY.size + bufferU.size + bufferV.size)
    bufferY.copyInto(yuvBytes, 0)
    bufferV.copyInto(yuvBytes, bufferY.size)
    bufferU.copyInto(yuvBytes, bufferY.size + bufferV.size)

    val yuvMat = Mat(image.height + image.height / 2, image.width, CvType.CV_8UC1)
    yuvMat.put(0, 0, yuvBytes)

    val bgrMat = Mat()
    Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV2BGR_NV21, 3)
    yuvMat.release()

    val rotatedMat = Mat()
    when (rotationDegrees) {
      90 -> Core.rotate(bgrMat, rotatedMat, Core.ROTATE_90_CLOCKWISE)
      180 -> Core.rotate(bgrMat, rotatedMat, Core.ROTATE_180)
      270 -> Core.rotate(bgrMat, rotatedMat, Core.ROTATE_90_COUNTERCLOCKWISE)
      else -> bgrMat.copyTo(rotatedMat)
    }
    bgrMat.release()
    return rotatedMat
  }

  private fun detectDocument(mat: Mat, frameSize: AndroidSize): QuadPoints? {
    if (mat.empty()) {
      mat.release()
      return null
    }

    val gray = Mat()
    Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)

    // Improve contrast for low-light or glossy surfaces
    val clahe = Photo.createCLAHE(2.0, MatSize(8.0, 8.0))
    val enhanced = Mat()
    clahe.apply(gray, enhanced)
    clahe.collectGarbage()

    val blurred = Mat()
    Imgproc.GaussianBlur(enhanced, blurred, MatSize(5.0, 5.0), 0.0)

    val edges = Mat()
    Imgproc.Canny(blurred, edges, 40.0, 140.0)

    val morphKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, MatSize(5.0, 5.0))
    Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, morphKernel)

    val contours = ArrayList<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

    var bestQuad: QuadPoints? = null
    var maxArea = 0.0
    val frameArea = frameSize.width * frameSize.height.toDouble()

    val approxCurve = MatOfPoint2f()
    for (contour in contours) {
      val contour2f = MatOfPoint2f(*contour.toArray())
      val perimeter = Imgproc.arcLength(contour2f, true)
      Imgproc.approxPolyDP(contour2f, approxCurve, 0.02 * perimeter, true)

      val points = approxCurve.toArray()
      if (points.size != 4) {
        contour.release()
        contour2f.release()
        continue
      }

      val area = abs(Imgproc.contourArea(approxCurve))
      if (area < frameArea * 0.05 || area > frameArea * 0.98) {
        contour.release()
        contour2f.release()
        continue
      }

      if (area > maxArea && Imgproc.isContourConvex(approxCurve)) {
        val ordered = orderPoints(points)
        bestQuad = QuadPoints(
          topLeft = ordered[0],
          topRight = ordered[1],
          bottomRight = ordered[2],
          bottomLeft = ordered[3],
        )
        maxArea = area
      }

      contour.release()
      contour2f.release()
    }

    gray.release()
    enhanced.release()
    blurred.release()
    edges.release()
    morphKernel.release()
    hierarchy.release()
    approxCurve.release()
    mat.release()

    return bestQuad
  }

  private fun orderPoints(points: Array<Point>): Array<Point> {
    val sorted = points.sortedBy { it.x + it.y }
    val tl = sorted.first()
    val br = sorted.last()
    val remaining = points.filter { it != tl && it != br }
    val (tr, bl) =
      if (remaining[0].x > remaining[1].x) remaining[0] to remaining[1] else remaining[1] to remaining[0]
    return arrayOf(tl, tr, br, bl)
  }

  // endregion

  private fun cropAndSave(bitmap: Bitmap, quad: QuadPoints, outputDir: File): String {
    val srcMat = Mat()
    org.opencv.android.Utils.bitmapToMat(bitmap, srcMat)

    val ordered = quad.toArray()
    val widthA = hypot(ordered[2].x - ordered[3].x, ordered[2].y - ordered[3].y)
    val widthB = hypot(ordered[1].x - ordered[0].x, ordered[1].y - ordered[0].y)
    val heightA = hypot(ordered[1].x - ordered[2].x, ordered[1].y - ordered[2].y)
    val heightB = hypot(ordered[0].x - ordered[3].x, ordered[0].y - ordered[3].y)

    val maxWidth = max(widthA, widthB).toInt().coerceAtLeast(1)
    val maxHeight = max(heightA, heightB).toInt().coerceAtLeast(1)

    val srcPoints = MatOfPoint2f(*ordered)
    val dstPoints = MatOfPoint2f(
      Point(0.0, 0.0),
      Point(maxWidth - 1.0, 0.0),
      Point(maxWidth - 1.0, maxHeight - 1.0),
      Point(0.0, maxHeight - 1.0),
    )

    val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
    val warped = Mat(MatSize(maxWidth.toDouble(), maxHeight.toDouble()), srcMat.type())
    Imgproc.warpPerspective(srcMat, warped, transform, warped.size())

    val croppedBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
    org.opencv.android.Utils.matToBitmap(warped, croppedBitmap)

    val outputFile = File(
      outputDir,
      "docscan-cropped-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.jpg",
    )
    outputFile.outputStream().use { stream ->
      croppedBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), stream)
    }

    srcMat.release()
    warped.release()
    transform.release()
    srcPoints.release()
    dstPoints.release()

    return outputFile.absolutePath
  }

  private fun Point.toWritable(): WritableMap = Arguments.createMap().apply {
    putDouble("x", x)
    putDouble("y", y)
  }

  private fun ByteBuffer.toByteArray(): ByteArray {
    val bytes = ByteArray(remaining())
    get(bytes)
    rewind()
    return bytes
  }

  companion object {
    private const val TAG = "RNRDocScanner"
  }
}

data class QuadPoints(
  val topLeft: Point,
  val topRight: Point,
  val bottomRight: Point,
  val bottomLeft: Point,
) {
  fun toArray(): Array<Point> = arrayOf(topLeft, topRight, bottomRight, bottomLeft)
  fun scaled(scaleX: Double, scaleY: Double): QuadPoints = QuadPoints(
    topLeft = Point(topLeft.x * scaleX, topLeft.y * scaleY),
    topRight = Point(topRight.x * scaleX, topRight.y * scaleY),
    bottomRight = Point(bottomRight.x * scaleX, bottomRight.y * scaleY),
    bottomLeft = Point(bottomLeft.x * scaleX, bottomLeft.y * scaleY),
  )
}
