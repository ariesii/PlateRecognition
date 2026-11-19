package com.example.platerecognition.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.View
import com.example.platerecognition.vision.DriveScene
import com.example.platerecognition.vision.ScenePainter

/** CameraX image-to-preview mapping; hides stale overlays when the transform is unavailable. */
class PlateOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var scene: DriveScene? = null
    private var imageToPreview: Matrix? = null

    fun show(value: DriveScene?, mapping: Matrix? = null) {
        scene = if (mapping == null) null else value
        imageToPreview = mapping?.let(::Matrix)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val item = scene ?: return
        val mapping = imageToPreview ?: return
        canvas.save()
        canvas.concat(mapping)
        ScenePainter.draw(canvas, item, item.width, item.height)
        canvas.restore()
    }
}
