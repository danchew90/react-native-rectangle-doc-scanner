package com.reactnativerectangledocscanner

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Rectangle(
    val topLeft: Point,
    val topRight: Point,
    val bottomLeft: Point,
    val bottomRight: Point
) {
    fun toMap(): Map<String, Map<String, Double>> {
        return mapOf(
            "topLeft" to mapOf("x" to topLeft.x, "y" to topLeft.y),
            "topRight" to mapOf("x" to topRight.x, "y" to topRight.y),
            "bottomLeft" to mapOf("x" to bottomLeft.x, "y" to bottomLeft.y),
            "bottomRight" to mapOf("x" to bottomRight.x, "y" to bottomRight.y)
        )
    }
}

enum class RectangleQuality {
    GOOD,
    BAD_ANGLE,
    TOO_FAR
}

class DocumentDetector {
    companion object {
        private const val TAG = "DocumentDetector"

        init {
            try {
                System.loadLibrary("opencv_java4")
                Log.d(TAG, "OpenCV library loaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load OpenCV library", e)
            }
        }

        /**
         * Detect document rectangle in the bitmap
         * Returns the largest detected rectangle or null if none found
         */
        fun detectRectangle(bitmap: Bitmap): Rectangle? {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            val rectangle = detectRectangleInMat(mat)

            mat.release()

            return rectangle
        }

        /**
         * Detect document rectangle in YUV image format (from camera)
         */
        fun detectRectangleInYUV(
            yuvBytes: ByteArray,
            width: Int,
            height: Int,
            rotation: Int
        ): Rectangle? {
            // Create Mat from YUV data
            val yuvMat = Mat(height + height / 2, width, CvType.CV_8UC1)
            yuvMat.put(0, 0, yuvBytes)

            // Convert YUV to RGB
            val rgbMat = Mat()
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21)

            // Rotate if needed
            if (rotation != 0) {
                val rotationCode = when (rotation) {
                    90 -> Core.ROTATE_90_CLOCKWISE
                    180 -> Core.ROTATE_180
                    270 -> Core.ROTATE_90_COUNTERCLOCKWISE
                    else -> null
                }
                if (rotationCode != null) {
                    Core.rotate(rgbMat, rgbMat, rotationCode)
                }
            }

            val rectangle = detectRectangleInMat(rgbMat)

            yuvMat.release()
            rgbMat.release()

            return rectangle
        }

        /**
         * Core detection algorithm using OpenCV
         */
        private fun detectRectangleInMat(srcMat: Mat): Rectangle? {
            val grayMat = Mat()
            val blurredMat = Mat()
            val cannyMat = Mat()
            val morphMat = Mat()
            val threshMat = Mat()

            try {
                // Convert to grayscale
                if (srcMat.channels() > 1) {
                    Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGB2GRAY)
                } else {
                    srcMat.copyTo(grayMat)
                }

                // Apply a light blur to reduce noise without killing small edges.
                Imgproc.GaussianBlur(grayMat, blurredMat, Size(3.0, 3.0), 0.0)

                // Apply Canny edge detection with slightly lower thresholds for small documents.
                Imgproc.Canny(blurredMat, cannyMat, 50.0, 150.0)
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                Imgproc.morphologyEx(cannyMat, morphMat, Imgproc.MORPH_CLOSE, kernel)
                kernel.release()

                fun findLargestRectangle(source: Mat): Rectangle? {
                    val contours = mutableListOf<MatOfPoint>()
                    val hierarchy = Mat()
                    Imgproc.findContours(
                        source,
                        contours,
                        hierarchy,
                        Imgproc.RETR_EXTERNAL,
                        Imgproc.CHAIN_APPROX_SIMPLE
                    )

                    var largestRectangle: Rectangle? = null
                    var largestArea = 0.0
                    val minArea = max(500.0, (srcMat.rows() * srcMat.cols()) * 0.0008)

                    for (contour in contours) {
                        val contourArea = Imgproc.contourArea(contour)
                        if (contourArea < minArea) continue

                        val approx = MatOfPoint2f()
                        val contour2f = MatOfPoint2f(*contour.toArray())
                        val epsilon = 0.018 * Imgproc.arcLength(contour2f, true)
                        Imgproc.approxPolyDP(contour2f, approx, epsilon, true)

                        if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                            val points = approx.toArray()
                            if (contourArea > largestArea) {
                                largestArea = contourArea
                                largestRectangle = orderPoints(points)
                            }
                        }

                        approx.release()
                        contour2f.release()
                    }

                    hierarchy.release()
                    contours.forEach { it.release() }
                    return largestRectangle
                }

                // First pass: Canny-based edges (good for strong edges).
                var rectangle = findLargestRectangle(morphMat)

                // Fallback: adaptive threshold (better for low-contrast cards).
                if (rectangle == null) {
                    Imgproc.adaptiveThreshold(
                        blurredMat,
                        threshMat,
                        255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY,
                        15,
                        2.0
                    )
                    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                    Imgproc.morphologyEx(threshMat, morphMat, Imgproc.MORPH_CLOSE, kernel)
                    kernel.release()
                    rectangle = findLargestRectangle(morphMat)
                }

                return rectangle
            } finally {
                grayMat.release()
                blurredMat.release()
                cannyMat.release()
                morphMat.release()
                threshMat.release()
            }
        }

        /**
         * Order points in consistent order: topLeft, topRight, bottomLeft, bottomRight
         */
        private fun orderPoints(points: Array<Point>): Rectangle {
            // Sort by y-coordinate
            val sorted = points.sortedBy { it.y }

            // Top two points
            val topPoints = sorted.take(2).sortedBy { it.x }
            val topLeft = topPoints[0]
            val topRight = topPoints[1]

            // Bottom two points
            val bottomPoints = sorted.takeLast(2).sortedBy { it.x }
            val bottomLeft = bottomPoints[0]
            val bottomRight = bottomPoints[1]

            return Rectangle(topLeft, topRight, bottomLeft, bottomRight)
        }

        /**
         * Evaluate rectangle quality (matching iOS logic)
         */
        fun evaluateRectangleQuality(
            rectangle: Rectangle,
            imageWidth: Int,
            imageHeight: Int
        ): RectangleQuality {
            // Check for bad angles
            val topYDiff = abs(rectangle.topRight.y - rectangle.topLeft.y)
            val bottomYDiff = abs(rectangle.bottomLeft.y - rectangle.bottomRight.y)
            val leftXDiff = abs(rectangle.topLeft.x - rectangle.bottomLeft.x)
            val rightXDiff = abs(rectangle.topRight.x - rectangle.bottomRight.x)

            if (topYDiff > 100 || bottomYDiff > 100 || leftXDiff > 100 || rightXDiff > 100) {
                return RectangleQuality.BAD_ANGLE
            }

            // Check if rectangle is too far from edges (too small)
            val margin = 150.0
            if (rectangle.topLeft.y > margin ||
                rectangle.topRight.y > margin ||
                rectangle.bottomLeft.y < (imageHeight - margin) ||
                rectangle.bottomRight.y < (imageHeight - margin)
            ) {
                return RectangleQuality.TOO_FAR
            }

            return RectangleQuality.GOOD
        }

        /**
         * Evaluate rectangle quality in view coordinates (closer to iOS behavior).
         */
        fun evaluateRectangleQualityInView(
            rectangle: Rectangle,
            viewWidth: Int,
            viewHeight: Int
        ): RectangleQuality {
            if (viewWidth == 0 || viewHeight == 0) {
                return RectangleQuality.TOO_FAR
            }

            val minDim = kotlin.math.min(viewWidth.toDouble(), viewHeight.toDouble())
            val angleThreshold = max(60.0, minDim * 0.08)

            val topYDiff = abs(rectangle.topRight.y - rectangle.topLeft.y)
            val bottomYDiff = abs(rectangle.bottomLeft.y - rectangle.bottomRight.y)
            val leftXDiff = abs(rectangle.topLeft.x - rectangle.bottomLeft.x)
            val rightXDiff = abs(rectangle.topRight.x - rectangle.bottomRight.x)

            if (topYDiff > angleThreshold || bottomYDiff > angleThreshold || leftXDiff > angleThreshold || rightXDiff > angleThreshold) {
                return RectangleQuality.BAD_ANGLE
            }

            val margin = max(120.0, minDim * 0.12)
            if (rectangle.topLeft.y > margin ||
                rectangle.topRight.y > margin ||
                rectangle.bottomLeft.y < (viewHeight - margin) ||
                rectangle.bottomRight.y < (viewHeight - margin)
            ) {
                return RectangleQuality.TOO_FAR
            }

            return RectangleQuality.GOOD
        }

        /**
         * Calculate perimeter of rectangle
         */
        fun calculatePerimeter(rectangle: Rectangle): Double {
            val width = distance(rectangle.topLeft, rectangle.topRight)
            val height = distance(rectangle.topLeft, rectangle.bottomLeft)
            return 2 * (width + height)
        }

        /**
         * Calculate distance between two points
         */
        private fun distance(p1: Point, p2: Point): Double {
            val dx = p1.x - p2.x
            val dy = p1.y - p2.y
            return sqrt(dx * dx + dy * dy)
        }

        /**
         * Transform rectangle coordinates from image space to view space
         */
        @Suppress("UNUSED_PARAMETER")
        fun transformRectangleToViewCoordinates(
            rectangle: Rectangle,
            imageWidth: Int,
            imageHeight: Int,
            viewWidth: Int,
            viewHeight: Int,
            rotationDegrees: Int = 0
        ): Rectangle {
            if (imageWidth == 0 || imageHeight == 0 || viewWidth == 0 || viewHeight == 0) {
                return rectangle
            }

            val scale = max(
                viewWidth.toDouble() / imageWidth.toDouble(),
                viewHeight.toDouble() / imageHeight.toDouble()
            )

            val scaledImageWidth = imageWidth * scale
            val scaledImageHeight = imageHeight * scale
            val offsetX = (scaledImageWidth - viewWidth) / 2.0
            val offsetY = (scaledImageHeight - viewHeight) / 2.0

            fun mapPoint(point: Point): Point {
                val x = (point.x * scale) - offsetX
                val y = (point.y * scale) - offsetY
                return Point(
                    x.coerceIn(0.0, viewWidth.toDouble()),
                    y.coerceIn(0.0, viewHeight.toDouble())
                )
            }

            return Rectangle(
                mapPoint(rectangle.topLeft),
                mapPoint(rectangle.topRight),
                mapPoint(rectangle.bottomLeft),
                mapPoint(rectangle.bottomRight)
            )
        }
    }
}
