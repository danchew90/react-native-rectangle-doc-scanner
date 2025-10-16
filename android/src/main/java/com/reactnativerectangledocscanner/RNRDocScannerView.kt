package com.reactnativerectangledocscanner

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.widget.FrameLayout
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.events.RCTEventEmitter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
  private var imageCapture: ImageCapture? = null
  private var imageAnalysis: ImageAnalysis? = null
  private var cameraExecutor: ExecutorService? = null
  private var currentStableCounter: Int = 0
  private var captureInFlight: Boolean = false

  init {
    setBackgroundColor(Color.BLACK)
    addView(
      previewView,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
    )
    initializeCamera()
  }

  private fun initializeCamera() {
    cameraExecutor = Executors.newSingleThreadExecutor()
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener(
      {
        cameraProvider = providerFuture.get()
        // TODO: Configure Preview + ImageAnalysis + ML Kit processing.
      },
      ContextCompat.getMainExecutor(context),
    )
  }

  fun emitRectangle(rectangle: WritableMap?) {
    val event: WritableMap = Arguments.createMap().apply {
      if (rectangle != null) {
        putMap("rectangleCoordinates", rectangle)
        currentStableCounter = (currentStableCounter + 1).coerceAtMost(detectionCountBeforeCapture)
      } else {
        putNull("rectangleCoordinates")
        currentStableCounter = 0
      }
      putInt("stableCounter", currentStableCounter)
      // Frame size placeholders until analysis is wired.
      putDouble("frameWidth", width.toDouble())
      putDouble("frameHeight", height.toDouble())
    }

    (context as? ReactContext)
      ?.getJSModule(RCTEventEmitter::class.java)
      ?.receiveEvent(id, "onRectangleDetect", event)
  }

  fun emitPictureTaken(payload: WritableMap) {
    (context as? ReactContext)
      ?.getJSModule(RCTEventEmitter::class.java)
      ?.receiveEvent(id, "onPictureTaken", payload)
  }

  fun capture(promise: Promise) {
    if (captureInFlight) {
      promise.reject("capture_in_progress", "A capture request is already running.")
      return
    }

    val imageCapture = this.imageCapture
    if (imageCapture == null) {
      promise.reject("capture_unavailable", "Image capture is not initialised yet.")
      return
    }

    captureInFlight = true
    // TODO: Hook into ImageCapture#takePicture and ML Kit cropping.
    postDelayed(
      {
        captureInFlight = false
        promise.reject("not_implemented", "Native capture pipeline has not been implemented.")
      },
      100,
    )
  }

  fun reset() {
    currentStableCounter = 0
  }

  private fun updateTorchMode(enabled: Boolean) {
    // TODO: Toggle torch once camera is integrated.
    Log.d("RNRDocScanner", "Torch set to $enabled (not yet wired).")
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    cameraExecutor?.shutdown()
    cameraExecutor = null
    cameraProvider?.unbindAll()
  }
}
