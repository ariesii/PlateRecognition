package com.example.platerecognition.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.platerecognition.recognition.PlateResult
import kotlin.math.max

/** Preview uses FILL_CENTER; analysis uses the same 16:9 target aspect ratio. */
class PlateOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var current = emptyList<PlateResult>()
    private var frameWidth = 1
    private var frameHeight = 1
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 4f * resources.displayMetrics.density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 20f * resources.displayMetrics.scaledDensity
    }
    private val background = Paint().apply { color = 0xCC101820.toInt() }

    fun show(results: List<PlateResult>, width: Int, height: Int) {
        current = results
        frameWidth = width
        frameHeight = height
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = max(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val offsetX = (width - frameWidth * scale) / 2f
        val offsetY = (height - frameHeight * scale) / 2f
        current.forEach { plate ->
            val box = plate.detection.box
            val left = box.left * scale + offsetX
            val top = box.top * scale + offsetY
            val right = box.right * scale + offsetX
            val bottom = box.bottom * scale + offsetY
            canvas.drawRect(left, top, right, bottom, outline)
            val labelWidth = text.measureText(plate.text) + 20f
            val labelTop = (top - text.textSize - 14f).coerceAtLeast(0f)
            canvas.drawRect(RectF(left, labelTop, left + labelWidth, labelTop + text.textSize + 12f), background)
            canvas.drawText(plate.text, left + 10f, labelTop + text.textSize + 2f, text)
        }
    }
}
