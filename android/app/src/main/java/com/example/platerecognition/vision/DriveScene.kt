package com.example.platerecognition.vision

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.example.platerecognition.recognition.PlateDetection
import com.example.platerecognition.recognition.PlateResult

/** All coordinates are in the rotated analysis frame, never in the PreviewView. */
data class DriveScene(val plates: List<PlateResult>, val lane: LaneEstimate?,
                      val distances: List<DistanceReading>, val width: Int, val height: Int,
                      val warning: DriveWarning? = null,
                      val provisional: List<PlateDetection> = emptyList())

/** Shared renderer for the live camera HUD and saved full-frame evidence. */
object ScenePainter {
    fun draw(canvas: Canvas, scene: DriveScene, targetWidth: Int, targetHeight: Int) {
        if (scene.width <= 0 || scene.height <= 0) return
        val scale = maxOf(targetWidth.toFloat() / scene.width, targetHeight.toFloat() / scene.height)
        val ox = (targetWidth - scene.width * scale) / 2f
        val oy = (targetHeight - scene.height * scale) / 2f
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(scale, scale)
        val stroke = maxOf(2f, scene.width / 400f)
        val lane = scene.lane
        if (lane != null && lane.confidence >= 0.48f) {
            val path = Path().apply {
                moveTo(lane.leftTop.x, lane.leftTop.y)
                lineTo(lane.rightTop.x, lane.rightTop.y)
                lineTo(lane.rightBottom.x, lane.rightBottom.y)
                lineTo(lane.leftBottom.x, lane.leftBottom.y)
                close()
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(64, 46, 171, 179) })
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF2EABB3.toInt(); style = Paint.Style.STROKE; strokeWidth = stroke * 1.7f
            }
            canvas.drawLine(lane.leftTop.x, lane.leftTop.y, lane.leftBottom.x, lane.leftBottom.y, edge)
            canvas.drawLine(lane.rightTop.x, lane.rightTop.y, lane.rightBottom.x, lane.rightBottom.y, edge)
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2EABB3.toInt(); style = Paint.Style.STROKE; strokeWidth = stroke
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF4F2EB.toInt(); textSize = (scene.width / 48f).coerceIn(15f, 32f)
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        val metric = Paint(text).apply { typeface = Typeface.MONOSPACE }
        val background = Paint().apply { color = 0xDD14232C.toInt() }
        val provisionalOutline = Paint(outline).apply {
            color = 0xFF9AB0B8.toInt()
            pathEffect = DashPathEffect(floatArrayOf(stroke * 3f, stroke * 2f), 0f)
        }
        scene.provisional.forEach { candidate ->
            val b = candidate.box
            canvas.drawRect(b.left, b.top, b.right, b.bottom, provisionalOutline)
        }
        scene.plates.forEach { plate ->
            val box = plate.detection.box
            val distance = scene.distances.firstOrNull { it.text == plate.text }
            val label = plate.text + (distance?.let { "  ~%.1f m".format(java.util.Locale.ROOT, it.metres) } ?: "")
            outline.color = if (distance?.inOurLane == true && distance.metres < 12f)
                0xFFE9B65E.toInt() else 0xFF2EABB3.toInt()
            canvas.drawRect(box.left, box.top, box.right, box.bottom, outline)
            val labelWidth = text.measureText(label) + 20f
            val labelTop = (box.top - text.textSize - 16f).coerceAtLeast(0f)
            canvas.drawRoundRect(RectF(box.left, labelTop,
                (box.left + labelWidth).coerceAtMost(scene.width.toFloat()), labelTop + text.textSize + 12f),
                5f, 5f, background)
            canvas.drawText(label, box.left + 9f, labelTop + text.textSize + 2f, text)
        }
        scene.warning?.let { warning ->
            val label = warning.text
            background.color = if (warning.kind == WarningKind.CLOSING) 0xE0D85B51.toInt() else 0xE0A8722B.toInt()
            val width = metric.measureText(label) + 28f
            val bottom = scene.height - 48f
            canvas.drawRoundRect(RectF(14f, bottom - metric.textSize - 16f, 14f + width,
                bottom + 10f), 7f, 7f, background)
            canvas.drawText(label, 28f, bottom, metric)
        }
        canvas.restore()
    }
}
