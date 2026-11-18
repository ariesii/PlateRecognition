package com.example.platerecognition.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.example.platerecognition.recognition.Point
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class LaneEstimate(val leftTop: Point, val leftBottom: Point,
                        val rightTop: Point, val rightBottom: Point,
                        val confidence: Float, val width: Int, val height: Int) {
    fun boundsAt(y: Float): Pair<Float, Float> {
        val t = ((y - leftTop.y) / (leftBottom.y - leftTop.y)).coerceIn(0f, 1f)
        return (leftTop.x + (leftBottom.x - leftTop.x) * t) to
            (rightTop.x + (rightBottom.x - rightTop.x) * t)
    }
    /** -1 / +1 are the left / right lane boundaries, 0 is the lane centre. */
    fun offsetAt(y: Float, cameraCentreRatio: Float): Float {
        val (left, right) = boundsAt(y)
        val halfWidth = (right - left) / 2f
        return if (halfWidth <= 0f) Float.NaN else
            (cameraCentreRatio * width - (left + right) / 2f) / halfWidth
    }
    fun contains(x: Float, y: Float): Boolean {
        val (left, right) = boundsAt(y)
        return x >= left && x <= right && y >= leftTop.y && y <= leftBottom.y
    }
}

/** Lightweight high-contrast lane candidate finder. It is NOT a learned lane model. */
class LaneAnalyzer {
    private data class PixelPoint(val x: Int, val y: Int)
    private data class Line(val slope: Float, val intercept: Float, val count: Int, val span: Int) {
        fun x(y: Float) = slope * y + intercept
    }
    private var previous: LaneEstimate? = null
    private var lastTime = 0L

    fun analyze(image: Bitmap, now: Long): LaneEstimate? {
        val sampleWidth = 320
        val sampleHeight = max(90, (image.height * 320f / image.width).toInt())
        val sample = Bitmap.createScaledBitmap(image, sampleWidth, sampleHeight, true)
        val pixels = IntArray(sampleWidth * sampleHeight)
        try { sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight) }
        finally { if (sample !== image) sample.recycle() }

        val lum = IntArray(pixels.size)
        val marks = BooleanArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val brightness = (r * 3 + g * 6 + b) / 10
            lum[i] = brightness
            marks[i] = (brightness >= 145 && max(r, max(g, b)) - min(r, min(g, b)) < 62) ||
                (r >= 135 && g >= 100 && b < 130 && r > b * 1.35)
        }
        val left = ArrayList<PixelPoint>()
        val right = ArrayList<PixelPoint>()
        val startY = (sampleHeight * 0.46f).toInt()
        val endY = (sampleHeight * 0.95f).toInt()
        for (y in startY until endY step 2) {
            for (x in 2 until sampleWidth - 2 step 2) {
                val i = y * sampleWidth + x
                if (!marks[i]) continue
                val contrast = abs(lum[i + 2] - lum[i - 2])
                if (contrast < 38) continue
                val point = PixelPoint(x, y)
                if (x < sampleWidth / 2) left += point else right += point
            }
        }
        val leftLine = fit(left, false, sampleWidth, startY, endY)
        val rightLine = fit(right, true, sampleWidth, startY, endY)
        if (leftLine == null || rightLine == null) {
            previous = null
            return null
        }
        val top = sampleHeight * 0.55f
        val bottom = sampleHeight * 0.94f
        val topWidth = rightLine.x(top) - leftLine.x(top)
        val bottomWidth = rightLine.x(bottom) - leftLine.x(bottom)
        if (topWidth !in sampleWidth * 0.02f..sampleWidth * 0.58f ||
            bottomWidth !in sampleWidth * 0.22f..sampleWidth * 0.90f ||
            bottomWidth < topWidth * 1.08f) {
            previous = null
            return null
        }
        val sx = image.width / sampleWidth.toFloat()
        val sy = image.height / sampleHeight.toFloat()
        val detected = LaneEstimate(
            Point(leftLine.x(top) * sx, top * sy), Point(leftLine.x(bottom) * sx, bottom * sy),
            Point(rightLine.x(top) * sx, top * sy), Point(rightLine.x(bottom) * sx, bottom * sy),
            min(1f, min(leftLine.count, rightLine.count) / 38f) *
                min(1f, min(leftLine.span, rightLine.span) / 55f), image.width, image.height)
        if (detected.confidence < 0.48f) { previous = null; return null }
        val old = previous
        val result = if (old != null && old.width == image.width && old.height == image.height &&
            now - lastTime in 0L..1500L) {
            fun blend(a: Point, b: Point) = Point(a.x * 0.55f + b.x * 0.45f, b.y)
            detected.copy(leftTop = blend(old.leftTop, detected.leftTop),
                leftBottom = blend(old.leftBottom, detected.leftBottom),
                rightTop = blend(old.rightTop, detected.rightTop),
                rightBottom = blend(old.rightBottom, detected.rightBottom))
        } else detected
        previous = result
        lastTime = now
        return result
    }

    private fun fit(points: List<PixelPoint>, right: Boolean, width: Int,
                    startY: Int, endY: Int): Line? {
        if (points.size < 24) return null
        var best: Line? = null
        val n = points.size
        // Fixed sampling makes failure reproducible and bounds work on slow phones.
        for (trial in 0 until 160) {
            val a = points[(trial * 313 + 17) % n]
            val b = points[(trial * 911 + n / 3) % n]
            if (abs(a.y - b.y) < (endY - startY) / 3) continue
            val slope = (b.x - a.x).toFloat() / (b.y - a.y)
            if (right && slope !in 0.12f..1.6f) continue
            if (!right && slope !in -1.6f..-0.12f) continue
            val intercept = a.x - slope * a.y
            val bottomX = slope * endY + intercept
            if (right && bottomX !in width * 0.53f..width * 0.99f) continue
            if (!right && bottomX !in width * 0.01f..width * 0.47f) continue
            var count = 0; var minY = endY; var maxY = startY
            points.forEach { p ->
                if (abs(p.x - (slope * p.y + intercept)) < 3.5f) {
                    count++
                    minY = min(minY, p.y); maxY = max(maxY, p.y)
                }
            }
            val span = maxY - minY
            if (span < (endY - startY) / 2 || count < 20) continue
            if (best == null || count * span > best.count * best.span) {
                best = Line(slope, intercept, count, span)
            }
        }
        return best
    }
}
