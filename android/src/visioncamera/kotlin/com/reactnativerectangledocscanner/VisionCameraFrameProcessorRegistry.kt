package com.reactnativerectangledocscanner

import com.mrousavy.camera.frameprocessors.FrameProcessorPluginRegistry
import java.util.concurrent.atomic.AtomicBoolean

object VisionCameraFrameProcessorRegistry {
    private val registered = AtomicBoolean(false)

    @JvmStatic
    fun register() {
        if (!registered.compareAndSet(false, true)) {
            return
        }
        FrameProcessorPluginRegistry.addFrameProcessorPlugin("DocumentScanner") { _, _ ->
            DocumentScannerFrameProcessorPlugin()
        }
    }
}
