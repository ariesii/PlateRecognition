package com.example.platerecognition.vision

import com.example.platerecognition.recognition.Box
import com.example.platerecognition.recognition.PlateResult
import com.example.platerecognition.recognition.StabilityGate
import com.example.platerecognition.settings.DistanceCalibration
import kotlin.math.hypot

data class DistanceReading(val text: String, val box: Box, val metres: Float,
                           val inOurLane: Boolean, val confidence: Float)

/** Visible plates only. A size-based monocular estimate is not a depth measurement. */
class DistanceEstimator {
    private data class Prior(val metres: Float, val timestamp: Long)
    private val prior = mutableMapOf<String, Prior>()

    fun estimate(results: List<PlateResult>, lane: LaneEstimate?, calibration: DistanceCalibration?,
                 width: Int, height: Int, now: Long): List<DistanceReading> {
        if (calibration == null || width <= 0 || height <= 0) {
            prior.clear()
            return emptyList()
        }
        val visible = mutableSetOf<String>()
        val readings = results.mapNotNull { result ->
            val key = StabilityGate.normalize(result.text) ?: return@mapNotNull null
            val d = result.detection
            val b = d.box
            val ratio = b.width / width
            if (d.confidence < 0.65f || ratio !in 0.015f..0.45f ||
                b.left <= 3f || b.right >= width - 3f || b.top <= 3f || b.bottom >= height - 3f) {
                return@mapNotNull null
            }
            // Strong perspective skew changes the apparent physical plate width.
            val top = hypot(d.points[1].x - d.points[0].x, d.points[1].y - d.points[0].y)
            val bottom = hypot(d.points[2].x - d.points[3].x, d.points[2].y - d.points[3].y)
            if (top < 4f || bottom < 4f || top / bottom !in 0.60f..1.65f) return@mapNotNull null
            val measured = calibration.metresTimesRatio / ratio
            if (measured !in 2f..100f) return@mapNotNull null
            visible += key
            val old = prior[key]
            val smoothed = if (old != null && now - old.timestamp in 100L..4000L)
                old.metres * 0.55f + measured * 0.45f else measured
            prior[key] = Prior(smoothed, now)
            val within = lane?.let {
                it.confidence >= 0.55f && it.contains((b.left + b.right) / 2f,
                    b.bottom.coerceAtMost(it.leftBottom.y))
            } ?: false
            DistanceReading(result.text, b, smoothed, within, d.confidence)
        }
        prior.keys.removeAll { it !in visible }
        return readings
    }

    fun reset() = prior.clear()
}
