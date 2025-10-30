package com.reactnativerectangledocscanner

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter

/**
 * ViewManager for CameraView
 * Bridges native CameraView to React Native
 */
class CameraViewManager : SimpleViewManager<CameraView>() {
    private val TAG = "CameraViewManager"

    companion object {
        const val REACT_CLASS = "RNDocScannerCamera"
    }

    override fun getName(): String = REACT_CLASS

    override fun createViewInstance(reactContext: ThemedReactContext): CameraView {
        Log.d(TAG, "Creating CameraView instance")

        val cameraView = CameraView(reactContext)

        // Set up rectangle detection callback
        cameraView.onRectangleDetected = { rectangle ->
            sendRectangleDetectedEvent(reactContext, cameraView, rectangle)
        }

        return cameraView
    }

    override fun onAfterUpdateTransaction(view: CameraView) {
        super.onAfterUpdateTransaction(view)
        // Start camera after view is ready
        view.post {
            view.startCamera()
        }
    }

    override fun onDropViewInstance(view: CameraView) {
        super.onDropViewInstance(view)
        view.stopCamera()
    }

    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> {
        return MapBuilder.of(
            "onRectangleDetected",
            MapBuilder.of("registrationName", "onRectangleDetected")
        )
    }

    /**
     * Send rectangle detected event to React Native
     */
    private fun sendRectangleDetectedEvent(
        reactContext: ReactContext,
        view: CameraView,
        rectangle: Rectangle?
    ) {
        val event: WritableMap = Arguments.createMap()

        if (rectangle != null) {
            val topLeft = Arguments.createMap().apply {
                putDouble("x", rectangle.topLeft.x.toDouble())
                putDouble("y", rectangle.topLeft.y.toDouble())
            }
            val topRight = Arguments.createMap().apply {
                putDouble("x", rectangle.topRight.x.toDouble())
                putDouble("y", rectangle.topRight.y.toDouble())
            }
            val bottomRight = Arguments.createMap().apply {
                putDouble("x", rectangle.bottomRight.x.toDouble())
                putDouble("y", rectangle.bottomRight.y.toDouble())
            }
            val bottomLeft = Arguments.createMap().apply {
                putDouble("x", rectangle.bottomLeft.x.toDouble())
                putDouble("y", rectangle.bottomLeft.y.toDouble())
            }

            event.putMap("topLeft", topLeft)
            event.putMap("topRight", topRight)
            event.putMap("bottomRight", bottomRight)
            event.putMap("bottomLeft", bottomLeft)
        } else {
            event.putNull("rectangle")
        }

        reactContext
            .getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(view.id, "onRectangleDetected", event)
    }
}
