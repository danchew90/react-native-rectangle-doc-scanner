package com.reactnativerectangledocscanner

import android.util.Log
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

class DocumentScannerPackage : ReactPackage {
    companion object {
        private const val TAG = "DocumentScannerPackage"
        private const val VISION_CAMERA_REGISTRY =
            "com.reactnativerectangledocscanner.VisionCameraFrameProcessorRegistry"
    }

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        registerVisionCameraPlugin()
        return listOf(DocumentScannerModule(reactContext))
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
        // Only register Camera2-based view managers if VisionCamera is not available
        return try {
            Class.forName(VISION_CAMERA_REGISTRY)
            // VisionCamera is available, no need to register Camera2 view managers
            emptyList()
        } catch (e: ClassNotFoundException) {
            // VisionCamera not available, register Camera2 view managers using reflection
            try {
                val viewManagers = mutableListOf<ViewManager<*, *>>()

                val docScannerViewManagerClass = Class.forName("com.reactnativerectangledocscanner.DocumentScannerViewManager")
                val cameraViewManagerClass = Class.forName("com.reactnativerectangledocscanner.CameraViewManager")

                viewManagers.add(docScannerViewManagerClass.newInstance() as ViewManager<*, *>)
                viewManagers.add(cameraViewManagerClass.newInstance() as ViewManager<*, *>)

                viewManagers
            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate Camera2 view managers", e)
                emptyList()
            }
        }
    }

    private fun registerVisionCameraPlugin() {
        try {
            val registryClass = Class.forName(VISION_CAMERA_REGISTRY)
            val registerMethod = registryClass.getMethod("register")
            registerMethod.invoke(null)
        } catch (e: ClassNotFoundException) {
            // VisionCamera not installed in the host app; skip registration.
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register VisionCamera frame processor", e)
        }
    }
}
