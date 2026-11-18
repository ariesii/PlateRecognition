package com.example.platerecognition.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/** CameraX RGBA_8888 plane bytes are A,R,G,B, with padded row strides. */
object CameraFrame {
    fun copyRotated(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val bytes = plane.buffer.duplicate().apply { rewind() }
        val stride = plane.rowStride
        val pixelStride = plane.pixelStride
        require(pixelStride >= 4 && stride >= image.width * pixelStride)
        val colors = IntArray(image.width * image.height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val offset = y * stride + x * pixelStride
                val red = bytes.get(offset + 1).toInt() and 255
                val green = bytes.get(offset + 2).toInt() and 255
                val blue = bytes.get(offset + 3).toInt() and 255
                colors[y * image.width + x] = Color.rgb(red, green, blue)
            }
        }
        val raw = Bitmap.createBitmap(colors, image.width, image.height, Bitmap.Config.ARGB_8888)
        val degrees = image.imageInfo.rotationDegrees
        if (degrees == 0) return raw
        return try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } finally {
            raw.recycle()
        }
    }
}
