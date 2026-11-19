package com.example.platerecognition.camera

import com.example.platerecognition.recognition.Box
import com.example.platerecognition.recognition.DetectorMath
import com.example.platerecognition.recognition.PlateDetection
import com.example.platerecognition.recognition.PlateResult
import com.example.platerecognition.recognition.Point
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Short-lived visual tracking on camera frames. Must only be used by the camera executor. */
class PlateTracker {
    data class Known(val detection: PlateDetection, val text: String, val verifiedAt: Long,
                     val confirmed: Boolean)
    data class Snapshot(val confirmed: List<PlateResult>, val pending: List<PlateDetection>)
    private data class Track(var detection: PlateDetection, var text: String?, var verifiedAt: Long,
                             var lastMatched: Long, var lastDetected: Long, var confirmed: Boolean,
                             var origin: PlateDetection, var originAt: Long)
    private val tracks = mutableListOf<Track>()
    private var previous: GrayFrame? = null
    private val maxTrackAgeMs = 1_800L

    fun reset() { tracks.clear(); previous = null }

    fun known(): List<Known> = tracks.mapNotNull { track ->
        track.text?.let { Known(track.detection, it, track.verifiedAt, track.confirmed) }
    }

    fun update(frame: GrayFrame): Snapshot {
        val before = previous
        if (before != null && !compatible(before, frame)) reset()
        if (before != null && compatible(before, frame)) {
            tracks.removeAll { track ->
                val offset = if (frame.observedAt - track.lastDetected > 3_000L) null
                    else match(before, frame, track.detection.box, 16)
                if (offset == null || frame.observedAt - track.lastMatched > maxTrackAgeMs) true
                else {
                    track.detection = translate(track.detection,
                        offset.first * frame.imageWidth / frame.width.toFloat(),
                        offset.second * frame.imageHeight / frame.height.toFloat(),
                        frame.imageWidth, frame.imageHeight)
                    track.lastMatched = frame.observedAt
                    false
                }
            }
        }
        previous = frame
        return snapshot(frame.observedAt)
    }

    /** Align a slow model result from its snapshot frame to the latest camera frame. */
    fun reconcile(snapshotFrame: GrayFrame, detections: List<PlateDetection>,
                  recognized: List<PlateResult>, now: Long,
                  confirmedTexts: Set<String> = emptySet()): Snapshot {
        val current = previous ?: return Snapshot(emptyList(), emptyList())
        if (!compatible(snapshotFrame, current) || current.observedAt - snapshotFrame.observedAt > 2_000L) {
            return snapshot(current.observedAt)
        }
        val used = mutableSetOf<Track>()
        detections.forEach { detection ->
            val shift = if (current.observedAt == snapshotFrame.observedAt) 0 to 0
                else match(snapshotFrame, current, detection.box, 32)
            if (shift == null) return@forEach
            val aligned = translate(detection,
                shift.first * current.imageWidth / current.width.toFloat(),
                shift.second * current.imageHeight / current.height.toFloat(),
                current.imageWidth, current.imageHeight)
            val read = recognized.firstOrNull { it.detection == detection }
            val existing = tracks.filter { it !in used && it.detection.doubleRow == aligned.doubleRow }
                .maxByOrNull { DetectorMath.iou(it.detection.box, aligned.box) }
                ?.takeIf { DetectorMath.iou(it.detection.box, aligned.box) >= 0.35f }
            if (existing != null) {
                existing.detection = aligned
                existing.lastMatched = current.observedAt
                existing.lastDetected = current.observedAt
                existing.origin = detection
                existing.originAt = snapshotFrame.observedAt
                if (read != null) {
                    if (existing.text != read.text) existing.confirmed = false
                    existing.text = read.text; existing.verifiedAt = now
                    if (read.text in confirmedTexts) existing.confirmed = true
                }
                used += existing
            } else {
                val added = Track(aligned, read?.text, if (read == null) 0L else now,
                    current.observedAt, current.observedAt, read?.text in confirmedTexts,
                    detection, snapshotFrame.observedAt)
                tracks += added
                used += added
            }
        }
        tracks.removeAll { it.lastMatched < current.observedAt - maxTrackAgeMs ||
            it.lastDetected < current.observedAt - 3_000L }
        return snapshot(current.observedAt)
    }

    /** Attach delayed OCR only to the exact detection still being visually tracked. */
    fun applyRecognitions(originAt: Long, results: List<PlateResult>, now: Long,
                          confirmedTexts: Set<String>): Snapshot {
        results.forEach { result ->
            val track = tracks.firstOrNull { it.originAt == originAt && it.origin == result.detection }
                ?: return@forEach
            if (track.text != result.text) track.confirmed = false
            track.text = result.text
            track.verifiedAt = now
            if (result.text in confirmedTexts) track.confirmed = true
        }
        return snapshot(previous?.observedAt ?: now)
    }

    private fun snapshot(now: Long): Snapshot {
        val active = tracks.filter { now - it.lastMatched in 0L..maxTrackAgeMs &&
            now - it.lastDetected in 0L..3_000L }
        return Snapshot(active.mapNotNull { t -> t.text?.let { PlateResult(t.detection, it) } },
            active.filter { it.text == null }.map { it.detection })
    }

    private fun compatible(a: GrayFrame, b: GrayFrame) = a.width == b.width && a.height == b.height &&
        a.imageWidth == b.imageWidth && a.imageHeight == b.imageHeight &&
        b.observedAt - a.observedAt in 0L..2_000L

    /** Template SAD over plate glyph texture; no match means hide rather than ghost a box. */
    private fun match(from: GrayFrame, to: GrayFrame, box: Box, radius: Int): Pair<Int, Int>? {
        val sx = from.width / from.imageWidth.toFloat()
        val sy = from.height / from.imageHeight.toFloat()
        val cx = ((box.left + box.right) * 0.5f * sx).toInt()
        val cy = ((box.top + box.bottom) * 0.5f * sy).toInt()
        val halfW = (box.width * sx * 0.35f).toInt().coerceIn(4, 16)
        val halfH = (box.height * sy * 0.35f).toInt().coerceIn(3, 10)
        val probes = ArrayList<Triple<Int, Int, Int>>()
        var low = 255; var high = 0
        for (py in -halfH..halfH step max(1, halfH / 3)) {
            for (px in -halfW..halfW step max(1, halfW / 4)) {
                val x = cx + px; val y = cy + py
                if (x !in 1 until from.width - 1 || y !in 1 until from.height - 1) continue
                val luma = from.luma(x, y)
                low = min(low, luma); high = max(high, luma)
                probes += Triple(px, py, luma)
            }
        }
        if (probes.size < 12 || high - low < 25) return null
        var bestScore = Float.MAX_VALUE
        var best: Pair<Int, Int>? = null
        for (dy in -radius..radius step 2) {
            for (dx in -radius..radius step 2) {
                if (cx + dx - halfW < 1 || cx + dx + halfW >= to.width - 1 ||
                    cy + dy - halfH < 1 || cy + dy + halfH >= to.height - 1) continue
                var score = 0
                probes.forEach { (px, py, luma) -> score += abs(to.luma(cx + dx + px, cy + dy + py) - luma) }
                val mean = score.toFloat() / probes.size + (abs(dx) + abs(dy)) * 0.12f
                if (mean < bestScore) { bestScore = mean; best = dx to dy }
            }
        }
        return best.takeIf { bestScore < 31f }
    }

    private fun translate(d: PlateDetection, dx: Float, dy: Float, width: Int, height: Int): PlateDetection {
        val b = d.box
        val left = (b.left + dx).coerceIn(0f, width.toFloat())
        val top = (b.top + dy).coerceIn(0f, height.toFloat())
        val right = (b.right + dx).coerceIn(0f, width.toFloat())
        val bottom = (b.bottom + dy).coerceIn(0f, height.toFloat())
        return d.copy(box = Box(left, top, right, bottom),
            points = d.points.map { p -> Point((p.x + dx).coerceIn(0f, width.toFloat()),
                (p.y + dy).coerceIn(0f, height.toFloat())) })
    }
}
