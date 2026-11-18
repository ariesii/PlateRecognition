package com.example.platerecognition.camera

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.example.platerecognition.vision.DriveScene
import com.example.platerecognition.vision.ScenePainter

/** Draws only observations actually produced by the current camera frame. */
class PlateOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var scene: DriveScene? = null

    fun show(value: DriveScene?) {
        scene = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scene?.let { ScenePainter.draw(canvas, it, width, height) }
    }
}
