package com.example.platerecognition.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.roundToInt

/** Rotated, view-port-cropped luminance sample; coordinates match copyRotated(). */
data class GrayFrame(val width: Int, val height: Int, val imageWidth: Int, val imageHeight: Int,
                     val pixels: ByteArray, val laneMarks: ByteArray, val observedAt: Long) {
    fun luma(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 255
}

/** CameraX RGBA_8888 bytes are A,R,G,B, with independent row and pixel strides. */
object CameraFrame {
    fun copyRotated(image: ImageProxy): Bitmap {
        val crop = image.cropRect
        val plane = image.planes[0]
        val bytes = plane.buffer.duplicate().apply { rewind() }
        val stride = plane.rowStride
        val pixelStride = plane.pixelStride
        require(pixelStride >= 4 && stride >= image.width * pixelStride)
        val width = crop.width()
        val height = crop.height()
        val colors = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = (crop.top + y) * stride + (crop.left + x) * pixelStride
                val red = bytes.get(offset + 1).toInt() and 255
                val green = bytes.get(offset + 2).toInt() and 255
                val blue = bytes.get(offset + 3).toInt() and 255
                colors[y * width + x] = Color.rgb(red, green, blue)
            }
        }
        val raw = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
        val degrees = image.imageInfo.rotationDegrees
        if (degrees == 0) return raw
        return try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } finally { raw.recycle() }
    }

    /** Avoids a full Bitmap on camera-only frames. Rotate sampling coordinates instead. */
    fun sampleGray(image: ImageProxy, now: Long, maxSide: Int = 320): GrayFrame {
        val crop = image.cropRect
        val rawWidth = crop.width()
        val rawHeight = crop.height()
        val degrees = image.imageInfo.rotationDegrees
        require(degrees == 0 || degrees == 90 || degrees == 180 || degrees == 270)
        val rotatedWidth = if (degrees % 180 == 0) rawWidth else rawHeight
        val rotatedHeight = if (degrees % 180 == 0) rawHeight else rawWidth
        val scale = maxSide.toFloat() / max(rotatedWidth, rotatedHeight)
        val width = max(1, (rotatedWidth * scale).roundToInt())
        val height = max(1, (rotatedHeight * scale).roundToInt())
        val values = ByteArray(width * height)
        val laneMarks = ByteArray(width * height)
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate().apply { rewind() }
        require(plane.pixelStride >= 4)
        for (y in 0 until height) {
            val orientedY = ((y + 0.5f) * rotatedHeight / height).toInt().coerceIn(0, rotatedHeight - 1)
            for (x in 0 until width) {
                val orientedX = ((x + 0.5f) * rotatedWidth / width).toInt().coerceIn(0, rotatedWidth - 1)
                val rawX: Int
                val rawY: Int
                when (degrees) {
                    90 -> { rawX = orientedY; rawY = rawHeight - 1 - orientedX }
                    180 -> { rawX = rawWidth - 1 - orientedX; rawY = rawHeight - 1 - orientedY }
                    270 -> { rawX = rawWidth - 1 - orientedY; rawY = orientedX }
                    else -> { rawX = orientedX; rawY = orientedY }
                }
                val offset = (crop.top + rawY) * plane.rowStride + (crop.left + rawX) * plane.pixelStride
                val r = buffer.get(offset + 1).toInt() and 255
                val g = buffer.get(offset + 2).toInt() and 255
                val b = buffer.get(offset + 3).toInt() and 255
                val index = y * width + x
                val brightness = (r * 3 + g * 6 + b) / 10
                values[index] = brightness.toByte()
                val white = brightness >= 145 && max(r, max(g, b)) - minOf(r, g, b) < 62
                val yellow = r >= 135 && g >= 100 && b < 130 && r > b * 1.35f
                laneMarks[index] = if (white || yellow) 1 else 0
            }
        }
        return GrayFrame(width, height, rotatedWidth, rotatedHeight, values, laneMarks, now)
    }
}
