package com.reactnativerectangledocscanner

import android.util.Log
import com.mrousavy.camera.frameprocessors.Frame
import com.mrousavy.camera.frameprocessors.FrameProcessorPlugin

class DocumentScannerFrameProcessorPlugin : FrameProcessorPlugin() {
    override fun callback(frame: Frame, params: Map<String, Any>?): Any? {
        return try {
            val imageProxy = frame.imageProxy
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

            val nv21 = imageProxy.toNv21()
            val rectangle = DocumentDetector.detectRectangleInYUV(
                nv21,
                imageProxy.width,
                imageProxy.height,
                rotationDegrees
            )

            val result = HashMap<String, Any?>()
            result["rectangle"] = rectangle?.toMap()
            result["imageWidth"] = frameWidth
            result["imageHeight"] = frameHeight
            result["rotation"] = rotationDegrees
            result["isMirrored"] = runCatching { frame.isMirrored }.getOrDefault(false)

            result
        } catch (e: Throwable) {
            Log.e("DocScannerVC", "Frame processor failed", e)
            null
        }
    }
}
