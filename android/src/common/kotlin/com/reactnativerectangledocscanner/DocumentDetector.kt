package com.reactnativerectangledocscanner

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.reactnativerectangledocscanner.BuildConfig
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
        private var debugFrameCounter = 0

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
         * Detect rectangle within a region-of-interest (ROI) in YUV image.
         * The ROI is specified in the rotated image coordinate space.
         */
        fun detectRectangleInYUVWithRoi(
            yuvBytes: ByteArray,
            width: Int,
            height: Int,
            rotation: Int,
            roi: Rect
        ): Rectangle? {
            val yuvMat = Mat(height + height / 2, width, CvType.CV_8UC1)
            yuvMat.put(0, 0, yuvBytes)

            val rgbMat = Mat()
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21)

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

            val x = roi.left.coerceIn(0, rgbMat.cols() - 1)
            val y = roi.top.coerceIn(0, rgbMat.rows() - 1)
            val right = roi.right.coerceIn(x + 1, rgbMat.cols())
            val bottom = roi.bottom.coerceIn(y + 1, rgbMat.rows())
            val w = right - x
            val h = bottom - y
            val roiRect = org.opencv.core.Rect(x, y, w, h)

            val roiMat = Mat(rgbMat, roiRect)
            val rectangle = detectRectangleInMat(roiMat)
            roiMat.release()
            yuvMat.release()
            rgbMat.release()

            return rectangle?.let {
                Rectangle(
                    Point(it.topLeft.x + x, it.topLeft.y + y),
                    Point(it.topRight.x + x, it.topRight.y + y),
                    Point(it.bottomLeft.x + x, it.bottomLeft.y + y),
                    Point(it.bottomRight.x + x, it.bottomRight.y + y)
                )
            }
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
            val debugStats = DebugStats()

            try {
                // Convert to grayscale
                if (srcMat.channels() > 1) {
                    Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGB2GRAY)
                } else {
                    srcMat.copyTo(grayMat)
                }

                // Boost local contrast to improve low-contrast edges (e.g., business cards).
                val clahe = Imgproc.createCLAHE()
                clahe.clipLimit = 2.5
                clahe.apply(grayMat, grayMat)

                // Apply a light blur to reduce noise without killing small edges.
                Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 0.0)

                fun computeMedian(mat: Mat): Double {
                    val hist = Mat()
                    return try {
                        Imgproc.calcHist(
                            listOf(mat),
                            MatOfInt(0),
                            Mat(),
                            hist,
                            MatOfInt(256),
                            MatOfFloat(0f, 256f)
                        )
                        val total = mat.total().toDouble()
                        var cumulative = 0.0
                        var median = 0.0
                        for (i in 0 until 256) {
                            cumulative += hist.get(i, 0)[0]
                            if (cumulative >= total * 0.5) {
                                median = i.toDouble()
                                break
                            }
                        }
                        median
                    } finally {
                        hist.release()
                    }
                }

                val median = computeMedian(blurredMat)
                val sigma = 0.33
                // Balanced thresholds for high-res (1280x960)
                // Lower than before to detect document edges, but higher than default to reduce noise
                val cannyLow = max(50.0, (1.0 - sigma) * median).coerceAtLeast(60.0)
                val cannyHigh = max(150.0, (1.0 + sigma) * median).coerceAtLeast(120.0)

                // Apply Canny edge detection with adaptive thresholds for better corner detection.
                Imgproc.Canny(blurredMat, cannyMat, cannyLow, cannyHigh)
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                Imgproc.morphologyEx(cannyMat, morphMat, Imgproc.MORPH_CLOSE, kernel)
                kernel.release()

                fun refineRectangle(gray: Mat, rectangle: Rectangle): Rectangle {
                    val maxX = (gray.cols() - 1).toDouble().coerceAtLeast(1.0)
                    val maxY = (gray.rows() - 1).toDouble().coerceAtLeast(1.0)
                    val points = MatOfPoint2f(
                        Point(rectangle.topLeft.x.coerceIn(0.0, maxX), rectangle.topLeft.y.coerceIn(0.0, maxY)),
                        Point(rectangle.topRight.x.coerceIn(0.0, maxX), rectangle.topRight.y.coerceIn(0.0, maxY)),
                        Point(rectangle.bottomLeft.x.coerceIn(0.0, maxX), rectangle.bottomLeft.y.coerceIn(0.0, maxY)),
                        Point(rectangle.bottomRight.x.coerceIn(0.0, maxX), rectangle.bottomRight.y.coerceIn(0.0, maxY))
                    )
                    // Use larger window for better sub-pixel corner refinement (matching iOS high accuracy)
                    val criteria = TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 40, 0.001)
                    return try {
                        Imgproc.cornerSubPix(
                            gray,
                            points,
                            Size(11.0, 11.0),  // Increased from 5x5 to 11x11 for better accuracy
                            Size(-1.0, -1.0),
                            criteria
                        )
                        orderPoints(points.toArray())
                    } catch (e: Exception) {
                        rectangle
                    } finally {
                        points.release()
                    }
                }

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
                    var bestScore = 0.0
                    val imageArea = (srcMat.rows() * srcMat.cols()).toDouble()
                    // Min: 2% of image (documents should be visible)
                    // Max: 85% of image (avoid detecting screen borders)
                    val minArea = max(350.0, imageArea * 0.02)
                    val maxArea = imageArea * 0.85

                    debugStats.contours = contours.size

                    for (contour in contours) {
                        val contourArea = Imgproc.contourArea(contour)
                        if (contourArea < minArea || contourArea > maxArea) continue

                        val approx = MatOfPoint2f()
                        val contour2f = MatOfPoint2f(*contour.toArray())
                        val arcLength = Imgproc.arcLength(contour2f, true)
                        // Reduced epsilon for more accurate corner detection (matching iOS high accuracy)
                        val epsilon = 0.01 * arcLength
                        Imgproc.approxPolyDP(contour2f, approx, epsilon, true)
                        val relaxed = if (approx.total() != 4L) {
                            MatOfPoint2f().apply {
                                // Reduced fallback epsilon for better corner accuracy
                                Imgproc.approxPolyDP(contour2f, this, 0.02 * arcLength, true)
                            }
                        } else {
                            null
                        }
                        val quad = if (relaxed?.total() == 4L) relaxed else approx

                        if (quad.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*quad.toArray()))) {
                            val points = quad.toArray()
                            val ordered = orderPoints(points)
                            val rect = Imgproc.minAreaRect(MatOfPoint2f(*points))
                            val rectArea = rect.size.area()
                            val rectangularity = if (rectArea > 1.0) contourArea / rectArea else 0.0
                            // Stricter rectangularity check (0.7 = more rectangular shapes only)
                            if (rectangularity >= 0.7 && isCandidateValid(ordered, srcMat)) {
                                debugStats.candidates += 1
                                val score = contourArea * rectangularity
                                if (score > bestScore) {
                                    val width = distance(ordered.topLeft, ordered.topRight)
                                    val height = distance(ordered.topLeft, ordered.bottomLeft)
                                    val aspect = if (height > 0.0) width / height else 0.0
                                    bestScore = score
                                    debugStats.bestArea = contourArea
                                    debugStats.bestRectangularity = rectangularity
                                    debugStats.bestWidth = width
                                    debugStats.bestHeight = height
                                    debugStats.bestAspect = aspect
                                    largestRectangle = refineRectangle(grayMat, ordered)
                                }
                            }
                        } else {
                            // Fallback: use rotated bounding box when contour is near-rectangular.
                            val contour2fForRect = MatOfPoint2f(*contour.toArray())
                            val rotated = Imgproc.minAreaRect(contour2fForRect)
                            contour2fForRect.release()
                            val rectArea = rotated.size.area()
                            if (rectArea > 1.0) {
                                val rectangularity = contourArea / rectArea
                                if (rectangularity >= 0.5) {
                                    debugStats.candidates += 1
                                    val boxPoints = Array(4) { Point() }
                                    rotated.points(boxPoints)
                                    val ordered = orderPoints(boxPoints)
                                    if (!isCandidateValid(ordered, srcMat)) {
                                        continue
                                    }
                                    val score = contourArea * rectangularity
                                    if (score > bestScore) {
                                        val width = distance(ordered.topLeft, ordered.topRight)
                                        val height = distance(ordered.topLeft, ordered.bottomLeft)
                                        val aspect = if (height > 0.0) width / height else 0.0
                                        bestScore = score
                                        debugStats.bestArea = contourArea
                                        debugStats.bestRectangularity = rectangularity
                                        debugStats.bestWidth = width
                                        debugStats.bestHeight = height
                                        debugStats.bestAspect = aspect
                                        largestRectangle = refineRectangle(grayMat, ordered)
                                    }
                                }
                            }
                        }

                        approx.release()
                        relaxed?.release()
                        contour2f.release()
                    }

                    hierarchy.release()
                    contours.forEach { it.release() }
                    debugStats.bestScore = bestScore
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

                if (BuildConfig.DEBUG) {
                    debugFrameCounter = (debugFrameCounter + 1) % 15
                    if (debugFrameCounter == 0) {
                        Log.d(
                            TAG,
                            "[DEBUG] cannyLow=$cannyLow cannyHigh=$cannyHigh " +
                                "contours=${debugStats.contours} candidates=${debugStats.candidates} " +
                                "bestScore=${String.format("%.1f", debugStats.bestScore)} " +
                                "bestArea=${String.format("%.1f", debugStats.bestArea)} " +
                                "bestRect=${String.format("%.2f", debugStats.bestRectangularity)} " +
                                "bestW=${String.format("%.1f", debugStats.bestWidth)} " +
                                "bestH=${String.format("%.1f", debugStats.bestHeight)} " +
                                "bestAspect=${String.format("%.2f", debugStats.bestAspect)} " +
                                "hasRect=${rectangle != null}"
                        )
                    }
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

        private data class DebugStats(
            var contours: Int = 0,
            var candidates: Int = 0,
            var bestScore: Double = 0.0,
            var bestArea: Double = 0.0,
            var bestRectangularity: Double = 0.0,
            var bestWidth: Double = 0.0,
            var bestHeight: Double = 0.0,
            var bestAspect: Double = 0.0
        )

        /**
         * Order points in consistent order: topLeft, topRight, bottomLeft, bottomRight
         */
        private fun orderPoints(points: Array<Point>): Rectangle {
            // Use sum/diff ordering for robustness under rotation.
            val sortedBySum = points.sortedBy { it.x + it.y }
            val sortedByDiff = points.sortedBy { it.x - it.y }

            val topLeft = sortedBySum.first()
            val bottomRight = sortedBySum.last()
            val topRight = sortedByDiff.first()
            val bottomLeft = sortedByDiff.last()

            return Rectangle(topLeft, topRight, bottomLeft, bottomRight)
        }

        private fun isCandidateValid(rectangle: Rectangle, srcMat: Mat): Boolean {
            val width = distance(rectangle.topLeft, rectangle.topRight)
            val height = distance(rectangle.topLeft, rectangle.bottomLeft)
            val minDim = min(srcMat.cols(), srcMat.rows()).toDouble()
            val minEdge = max(60.0, minDim * 0.08)
            if (width < minEdge || height < minEdge) {
                return false
            }
            val aspect = if (height > 0) width / height else 0.0
            if (aspect < 0.45 || aspect > 2.8) {
                return false
            }

            return true
        }

        /**
         * Check if all corners have angles close to 90° (within 60°-120° range)
         */
        private fun hasValidCornerAngles(rectangle: Rectangle): Boolean {
            fun angleAt(p1: Point, vertex: Point, p2: Point): Double {
                val v1x = p1.x - vertex.x
                val v1y = p1.y - vertex.y
                val v2x = p2.x - vertex.x
                val v2y = p2.y - vertex.y

                val dot = v1x * v2x + v1y * v2y
                val len1 = sqrt(v1x * v1x + v1y * v1y)
                val len2 = sqrt(v2x * v2x + v2y * v2y)

                if (len1 < 1.0 || len2 < 1.0) return 90.0

                val cosAngle = (dot / (len1 * len2)).coerceIn(-1.0, 1.0)
                return Math.toDegrees(kotlin.math.acos(cosAngle))
            }

            val angleTL = angleAt(rectangle.topRight, rectangle.topLeft, rectangle.bottomLeft)
            val angleTR = angleAt(rectangle.topLeft, rectangle.topRight, rectangle.bottomRight)
            val angleBL = angleAt(rectangle.topLeft, rectangle.bottomLeft, rectangle.bottomRight)
            val angleBR = angleAt(rectangle.topRight, rectangle.bottomRight, rectangle.bottomLeft)

            // All angles should be within 50°-130° (allow ±40° from 90°, more lenient)
            return angleTL in 50.0..130.0 && angleTR in 50.0..130.0 &&
                   angleBL in 50.0..130.0 && angleBR in 50.0..130.0
        }

        /**
         * Check if edges are sufficiently straight (low deviation from fitted line)
         */
        private fun hasValidEdgeStraightness(rectangle: Rectangle, srcMat: Mat): Boolean {
            val minDim = min(srcMat.cols(), srcMat.rows()).toDouble()
            val maxDeviation = max(10.0, minDim * 0.02) // Allow 2% deviation

            fun checkEdgeStraightness(p1: Point, p2: Point, p3: Point, p4: Point): Boolean {
                // Check if points p1-p2 and p3-p4 form roughly parallel lines
                val dx1 = p2.x - p1.x
                val dy1 = p2.y - p1.y
                val dx2 = p4.x - p3.x
                val dy2 = p4.y - p3.y

                val len1 = sqrt(dx1 * dx1 + dy1 * dy1)
                val len2 = sqrt(dx2 * dx2 + dy2 * dy2)

                if (len1 < 1.0 || len2 < 1.0) return true

                // Normalize and compute angle difference
                val dot = (dx1 * dx2 + dy1 * dy2) / (len1 * len2)
                val angleDiff = Math.toDegrees(kotlin.math.acos(dot.coerceIn(-1.0, 1.0)))

                // Parallel lines should have angle diff close to 0° or 180° (more lenient: 20° tolerance)
                return angleDiff < 20.0 || angleDiff > 160.0
            }

            // Check top vs bottom edges
            val topBottomOk = checkEdgeStraightness(
                rectangle.topLeft, rectangle.topRight,
                rectangle.bottomLeft, rectangle.bottomRight
            )

            // Check left vs right edges
            val leftRightOk = checkEdgeStraightness(
                rectangle.topLeft, rectangle.bottomLeft,
                rectangle.topRight, rectangle.bottomRight
            )

            return topBottomOk && leftRightOk
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
         * Evaluate rectangle quality in view coordinates (iOS-like behavior).
         * Focuses on rectangle size and angle quality.
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
            val viewArea = viewWidth.toDouble() * viewHeight.toDouble()

            // Calculate actual edge lengths for better angle evaluation
            val topEdgeLength = distance(rectangle.topLeft, rectangle.topRight)
            val bottomEdgeLength = distance(rectangle.bottomLeft, rectangle.bottomRight)
            val leftEdgeLength = distance(rectangle.topLeft, rectangle.bottomLeft)
            val rightEdgeLength = distance(rectangle.topRight, rectangle.bottomRight)

            // Calculate rectangle area (approximate using bounding box)
            val rectWidth = max(topEdgeLength, bottomEdgeLength)
            val rectHeight = max(leftEdgeLength, rightEdgeLength)
            val rectArea = rectWidth * rectHeight

            // Check if rectangle is too small (less than 6% of view area)
            // or too large (more than 95% - likely detecting screen instead of document)
            val areaRatio = rectArea / viewArea
            if (areaRatio < 0.06) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "[QUALITY] TOO_FAR (small): area=${String.format("%.1f", rectArea)}, ratio=${String.format("%.2f", areaRatio)}")
                }
                return RectangleQuality.TOO_FAR
            }
            if (areaRatio > 0.95) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "[QUALITY] TOO_FAR (large): area=${String.format("%.1f", rectArea)}, ratio=${String.format("%.2f", areaRatio)} - likely detecting screen")
                }
                return RectangleQuality.TOO_FAR
            }

            // Check angle quality using angle ratio instead of absolute difference
            // Edges should be roughly horizontal/vertical (not too skewed)
            val topAngleRatio = if (topEdgeLength > 0) abs(rectangle.topRight.y - rectangle.topLeft.y) / topEdgeLength else 0.0
            val bottomAngleRatio = if (bottomEdgeLength > 0) abs(rectangle.bottomLeft.y - rectangle.bottomRight.y) / bottomEdgeLength else 0.0
            val leftAngleRatio = if (leftEdgeLength > 0) abs(rectangle.topLeft.x - rectangle.bottomLeft.x) / leftEdgeLength else 0.0
            val rightAngleRatio = if (rightEdgeLength > 0) abs(rectangle.topRight.x - rectangle.bottomRight.x) / rightEdgeLength else 0.0

            // Allow up to 30% skew (sin(~17°) ≈ 0.3)
            val maxSkewRatio = 0.3

            if (topAngleRatio > maxSkewRatio ||
                bottomAngleRatio > maxSkewRatio ||
                leftAngleRatio > maxSkewRatio ||
                rightAngleRatio > maxSkewRatio) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "[QUALITY] BAD_ANGLE (skew): top=${String.format("%.2f", topAngleRatio)}, " +
                        "bottom=${String.format("%.2f", bottomAngleRatio)}, " +
                        "left=${String.format("%.2f", leftAngleRatio)}, " +
                        "right=${String.format("%.2f", rightAngleRatio)} > $maxSkewRatio")
                }
                return RectangleQuality.BAD_ANGLE
            }

            // Check opposite edge ratio (perspective check)
            // More lenient: allow 3x difference
            val topBottomRatio = if (bottomEdgeLength > 0) topEdgeLength / bottomEdgeLength else 0.0
            val leftRightRatio = if (rightEdgeLength > 0) leftEdgeLength / rightEdgeLength else 0.0
            if (topBottomRatio < 0.33 || topBottomRatio > 3.0 ||
                leftRightRatio < 0.33 || leftRightRatio > 3.0) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "[QUALITY] BAD_ANGLE (ratio): topBottom=${String.format("%.2f", topBottomRatio)}, " +
                        "leftRight=${String.format("%.2f", leftRightRatio)}")
                }
                return RectangleQuality.BAD_ANGLE
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "[QUALITY] GOOD: area=${String.format("%.1f", rectArea)}, ratio=${String.format("%.2f", areaRatio)}")
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

            val scale = min(
                viewWidth.toDouble() / imageWidth.toDouble(),
                viewHeight.toDouble() / imageHeight.toDouble()
            )

            val scaledImageWidth = imageWidth * scale
            val scaledImageHeight = imageHeight * scale
            val offsetX = (viewWidth - scaledImageWidth) / 2.0
            val offsetY = (viewHeight - scaledImageHeight) / 2.0

            fun mapPoint(point: Point): Point {
                val x = (point.x * scale) + offsetX
                val y = (point.y * scale) + offsetY
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
