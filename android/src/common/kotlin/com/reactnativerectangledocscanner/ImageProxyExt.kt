package com.reactnativerectangledocscanner

import androidx.camera.core.ImageProxy

/**
 * Convert [ImageProxy] in YUV_420_888 format to NV21 byte array.
 * This mirrors the logic used by the existing DocumentScannerView pipeline so CameraView can reuse
 * the same OpenCV detection utilities.
 */
fun ImageProxy.toNv21(): ByteArray {
    val width = width
    val height = height

    val ySize = width * height
    val uvSize = width * height / 2
    val nv21 = ByteArray(ySize + uvSize)

    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val yRowStride = planes[0].rowStride
    val yPixelStride = planes[0].pixelStride
    var outputOffset = 0
    for (row in 0 until height) {
        var inputOffset = row * yRowStride
        for (col in 0 until width) {
            nv21[outputOffset++] = yBuffer.get(inputOffset)
            inputOffset += yPixelStride
        }
    }

    val uvRowStride = planes[1].rowStride
    val uvPixelStride = planes[1].pixelStride
    val vRowStride = planes[2].rowStride
    val vPixelStride = planes[2].pixelStride
    val uvHeight = height / 2
    val uvWidth = width / 2
    for (row in 0 until uvHeight) {
        var uInputOffset = row * uvRowStride
        var vInputOffset = row * vRowStride
        for (col in 0 until uvWidth) {
            nv21[outputOffset++] = vBuffer.get(vInputOffset)
            nv21[outputOffset++] = uBuffer.get(uInputOffset)
            uInputOffset += uvPixelStride
            vInputOffset += vPixelStride
        }
    }

    return nv21
}
