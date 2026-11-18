package com.example.platerecognition.vision

import com.example.platerecognition.recognition.StabilityGate
import kotlin.math.abs

enum class WarningKind { LANE, FOLLOWING, CLOSING }
data class DriveWarning(val kind: WarningKind, val text: String)

/** Conservative, rate-limited prompts only; this is not a safety-rated ADAS. */
class WarningEngine {
    private data class Track(val distance: Float, val time: Long, val closingSpeed: Float)
    private val tracks = mutableMapOf<String, Track>()
    private val lastAlert = mutableMapOf<WarningKind, Long>()
    private var laneHits = 0
    private var closeHits = 0
    private var closingHits = 0

    fun observe(lane: LaneEstimate?, centreRatio: Float, readings: List<DistanceReading>,
                calibrated: Boolean, closeMetres: Float, now: Long): DriveWarning? {
        if (!calibrated || lane == null || lane.confidence < 0.55f) {
            resetTracks()
            return null
        }
        val offset = lane.offsetAt(lane.height * 0.88f, centreRatio)
        laneHits = if (offset.isFinite() && abs(offset) > 0.78f) laneHits + 1 else 0
        val front = readings.filter { it.inOurLane }.minByOrNull { it.metres }
        val visible = mutableSetOf<String>()
        var ttc: Float? = null
        if (front != null) {
            val key = StabilityGate.normalize(front.text)
            if (key != null) {
                visible += key
                val old = tracks[key]
                val elapsed = if (old != null) (now - old.time) / 1000f else 0f
                if (old != null && elapsed in 0.15f..4f) {
                    val rawClosing = (old.distance - front.metres) / elapsed
                    val smoothClosing = (old.closingSpeed * 0.6f + rawClosing * 0.4f).coerceAtLeast(0f)
                    if (smoothClosing > 1.0f) ttc = front.metres / smoothClosing
                    tracks[key] = Track(front.metres, now, smoothClosing)
                } else tracks[key] = Track(front.metres, now, 0f)
            }
        }
        tracks.keys.removeAll { it !in visible }
        closeHits = if (front != null && front.metres < closeMetres) closeHits + 1 else 0
        closingHits = if (front != null && front.metres < 35f && ttc != null && ttc < 3f)
            closingHits + 1 else 0
        val candidate = when {
            closingHits >= 3 -> DriveWarning(WarningKind.CLOSING, "估計前方接近過快 · 留意碰撞風險")
            closeHits >= 2 -> DriveWarning(WarningKind.FOLLOWING, "估計跟車距離偏近")
            laneHits >= 3 -> DriveWarning(WarningKind.LANE, "車道位置偏移，請留意路況")
            else -> null
        } ?: return null
        if (now - (lastAlert[candidate.kind] ?: 0L) < 8_000L) return null
        lastAlert[candidate.kind] = now
        return candidate
    }

    fun resetTracks() {
        tracks.clear()
        laneHits = 0; closeHits = 0; closingHits = 0
    }
}
