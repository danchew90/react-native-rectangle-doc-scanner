package com.reactnativerectangledocscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

object ImageProcessor {
    private const val TAG = "ImageProcessor"

    init {
        try {
            System.loadLibrary("opencv_java4")
            Log.d(TAG, "OpenCV library loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load OpenCV library", e)
        }
    }

    data class ProcessedImage(
        val croppedImage: Bitmap,
        val initialImage: Bitmap,
        val rectangle: Rectangle?
    )

    /**
     * Apply perspective correction to crop document from detected rectangle
     */
    fun cropAndCorrectPerspective(
        bitmap: Bitmap,
        rectangle: Rectangle
    ): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        // Convert to proper format if needed
        if (srcMat.channels() == 4) {
            Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGBA2RGB)
        }

        // Create source points from rectangle (match iOS order)
        val srcPoints = MatOfPoint2f(
            Point(rectangle.topLeft.x, rectangle.topLeft.y),
            Point(rectangle.topRight.x, rectangle.topRight.y),
            Point(rectangle.bottomLeft.x, rectangle.bottomLeft.y),
            Point(rectangle.bottomRight.x, rectangle.bottomRight.y)
        )

        // Calculate destination rectangle dimensions
        val widthTop = distance(rectangle.topLeft, rectangle.topRight)
        val widthBottom = distance(rectangle.bottomLeft, rectangle.bottomRight)
        val heightLeft = distance(rectangle.topLeft, rectangle.bottomLeft)
        val heightRight = distance(rectangle.topRight, rectangle.bottomRight)

        val maxWidth = max(widthTop, widthBottom).toInt()
        val maxHeight = max(heightLeft, heightRight).toInt()

        // Create destination points for a rectangle
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth.toDouble(), 0.0),
            Point(0.0, maxHeight.toDouble()),
            Point(maxWidth.toDouble(), maxHeight.toDouble())
        )

        // Get perspective transform matrix and apply it
        val transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val dstMat = Mat()
        Imgproc.warpPerspective(srcMat, dstMat, transformMatrix, Size(maxWidth.toDouble(), maxHeight.toDouble()))

        // Convert back to bitmap
        val croppedBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dstMat, croppedBitmap)

        // Cleanup
        srcMat.release()
        dstMat.release()
        transformMatrix.release()
        srcPoints.release()
        dstPoints.release()

        return croppedBitmap
    }

    /**
     * Apply color adjustments (brightness, contrast, saturation)
     */
    fun applyColorControls(
        bitmap: Bitmap,
        brightness: Float = 0f,
        contrast: Float = 1f,
        saturation: Float = 1f
    ): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        // Convert to HSV for saturation adjustment
        if (saturation != 1f) {
            val hsvMat = Mat()
            Imgproc.cvtColor(srcMat, hsvMat, Imgproc.COLOR_RGB2HSV)

            val channels = mutableListOf<Mat>()
            Core.split(hsvMat, channels)

            // Adjust saturation channel
            channels[1].convertTo(channels[1], -1, saturation.toDouble(), 0.0)

            Core.merge(channels, hsvMat)
            Imgproc.cvtColor(hsvMat, srcMat, Imgproc.COLOR_HSV2RGB)

            hsvMat.release()
            channels.forEach { it.release() }
        }

        // Apply brightness and contrast
        val dstMat = Mat()
        srcMat.convertTo(dstMat, -1, contrast.toDouble(), brightness * 255.0)

        val safeConfig = bitmap.config ?: Bitmap.Config.ARGB_8888
        val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, safeConfig)
        Utils.matToBitmap(dstMat, resultBitmap)

        srcMat.release()
        dstMat.release()

        return resultBitmap
    }

    /**
     * Save bitmap to file with specified quality
     */
    fun saveBitmapToFile(
        bitmap: Bitmap,
        directory: File,
        filename: String,
        quality: Float
    ): String {
        val qualityPercent = max(95f, min(100f, quality * 100f)).toInt()
        val file = File(directory, filename)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, qualityPercent, out)
        }

        return file.absolutePath
    }

    /**
     * Convert bitmap to Base64 string
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Float): String {
        val qualityPercent = max(95f, min(100f, quality * 100f)).toInt()
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, qualityPercent, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * Calculate Euclidean distance between two points
     */
    private fun distance(p1: Point, p2: Point): Double {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Rotate bitmap by specified degrees
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Process captured image with optional cropping and color adjustments
     */
    fun processImage(
        imagePath: String,
        rectangle: Rectangle?,
        brightness: Float = 0f,
        contrast: Float = 1f,
        saturation: Float = 1f,
        shouldCrop: Boolean = true
    ): ProcessedImage {
        var initialBitmap = BitmapFactory.decodeFile(imagePath)
        var croppedBitmap = initialBitmap

        // Apply perspective correction if rectangle detected and cropping enabled
        if (shouldCrop && rectangle != null) {
            try {
                croppedBitmap = cropAndCorrectPerspective(initialBitmap, rectangle)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to crop image, using original", e)
                croppedBitmap = initialBitmap
            }
        }

        // Apply color adjustments to cropped image
        if (brightness != 0f || contrast != 1f || saturation != 1f) {
            try {
                croppedBitmap = applyColorControls(croppedBitmap, brightness, contrast, saturation)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply color controls", e)
            }
        }

        return ProcessedImage(croppedBitmap, initialBitmap, rectangle)
    }
}
