package com.example.platerecognition.recognition

import kotlin.math.max
import kotlin.math.min

data class Point(val x: Float, val y: Float)
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
data class PlateDetection(val box: Box, val points: List<Point>, val confidence: Float, val doubleRow: Boolean)
data class PlateResult(val detection: PlateDetection, val text: String)

/** Mirrors the upstream YOLOv5 [cx,cy,w,h,obj,8 landmarks,2 classes] contract. */
object DetectorMath {
    private const val ATTRS = 15
    private const val CONFIDENCE = 0.50f
    private const val IOU_THRESHOLD = 0.45f

    fun decode(output: FloatArray, shape: LongArray, sourceWidth: Int, sourceHeight: Int,
               inputWidth: Int = 640, inputHeight: Int = 640): List<PlateDetection> {
        require(shape.size == 3 && shape[0] == 1L && shape[2] == ATTRS.toLong() &&
                shape[1] > 0 && shape[1] <= 100_000 && shape[1] * ATTRS == output.size.toLong()) {
            "Unexpected detector output: ${shape.contentToString()}"
        }
        require(sourceWidth > 0 && sourceHeight > 0)
        val scale = min(inputWidth.toFloat() / sourceWidth, inputHeight.toFloat() / sourceHeight)
        val padX = (inputWidth - sourceWidth * scale) / 2f
        val padY = (inputHeight - sourceHeight * scale) / 2f
        val candidates = ArrayList<PlateDetection>()
        for (n in 0 until shape[1].toInt()) {
            val i = n * ATTRS
            val score0 = output[i + 4] * output[i + 13]
            val score1 = output[i + 4] * output[i + 14]
            val confidence = max(score0, score1)
            if (!confidence.isFinite() || confidence < CONFIDENCE) continue
            if (!(i until i + ATTRS).all { output[it].isFinite() }) continue
            val cx = output[i]
            val cy = output[i + 1]
            val w = output[i + 2]
            val h = output[i + 3]
            if (w <= 0f || h <= 0f) continue
            val box = Box(
                ((cx - w / 2f - padX) / scale).coerceIn(0f, sourceWidth.toFloat()),
                ((cy - h / 2f - padY) / scale).coerceIn(0f, sourceHeight.toFloat()),
                ((cx + w / 2f - padX) / scale).coerceIn(0f, sourceWidth.toFloat()),
                ((cy + h / 2f - padY) / scale).coerceIn(0f, sourceHeight.toFloat())
            )
            if (box.width < 3f || box.height < 3f) continue
            val points = (0..3).map { k ->
                Point(((output[i + 5 + k * 2] - padX) / scale).coerceIn(0f, sourceWidth - 1f),
                    ((output[i + 6 + k * 2] - padY) / scale).coerceIn(0f, sourceHeight - 1f))
            }
            if (points.distinct().size < 4) continue
            candidates.add(PlateDetection(box, points, confidence, score1 > score0))
        }
        val sorted = candidates.sortedByDescending { it.confidence }.take(300)
        val kept = ArrayList<PlateDetection>()
        for (candidate in sorted) {
            if (kept.none { it.doubleRow == candidate.doubleRow && iou(it.box, candidate.box) > IOU_THRESHOLD }) {
                kept.add(candidate)
                if (kept.size == 10) break
            }
        }
        return kept
    }

    fun iou(a: Box, b: Box): Float {
        val area = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
            max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val union = a.width * a.height + b.width * b.height - area
        return if (union <= 0f) 0f else area / union
    }
}
