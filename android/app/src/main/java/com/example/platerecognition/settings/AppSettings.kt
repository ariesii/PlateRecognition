package com.example.platerecognition.settings

import android.content.Context
import com.example.platerecognition.recognition.PlateResult

data class PlateCandidate(val text: String, val widthRatio: Float, val confidence: Float,
                          val orientation: String, val observedAt: Long)
data class DistanceCalibration(val metresTimesRatio: Float, val orientation: String,
                               val referencePlate: String, val referenceMetres: Float)

/** A calibration is meaningful only for the same fixed camera pose and orientation. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("drive_settings", Context.MODE_PRIVATE)
    private var lastCandidateWrite = 0L

    var autoSave: Boolean
        get() = prefs.getBoolean("auto_save", true)
        set(value) { prefs.edit().putBoolean("auto_save", value).apply() }
    var adasEnabled: Boolean
        get() = prefs.getBoolean("adas", false) // Opt-in: never arm experimental warnings by default.
        set(value) { prefs.edit().putBoolean("adas", value).apply() }
    var recognitionSoundEnabled: Boolean
        get() = prefs.getBoolean("recognition_sound", true)
        set(value) { prefs.edit().putBoolean("recognition_sound", value).apply() }
    var soundEnabled: Boolean
        get() = prefs.getBoolean("sound", false)
        set(value) { prefs.edit().putBoolean("sound", value).apply() }
    var vibrationEnabled: Boolean
        get() = prefs.getBoolean("vibration", false)
        set(value) { prefs.edit().putBoolean("vibration", value).apply() }
    var closeMetres: Float
        get() = prefs.getFloat("close_metres", 12f).coerceIn(4f, 35f)
        set(value) { prefs.edit().putFloat("close_metres", value.coerceIn(4f, 35f)).apply() }
    var laneCenterRatio: Float
        get() = prefs.getFloat("lane_center", 0.5f).coerceIn(0.35f, 0.65f)
        set(value) { prefs.edit().putFloat("lane_center", value.coerceIn(0.35f, 0.65f)).apply() }

    fun orientation(width: Int, height: Int): String = if (width >= height) "landscape" else "portrait"

    /** Called only for recently observed high-confidence, fully visible plates. */
    fun recordCandidates(results: List<PlateResult>, width: Int, height: Int, time: Long) {
        val valid = results.filter { it.detection.confidence >= 0.65f &&
            it.detection.box.left > 4f && it.detection.box.right < width - 4f &&
            it.detection.box.width / width in 0.015f..0.45f
        }.take(8)
        if (valid.isEmpty() || time - lastCandidateWrite < 1_000L) return
        lastCandidateWrite = time
        val edit = prefs.edit().putInt("candidate_count", valid.size)
        valid.forEachIndexed { index, result ->
            edit.putString("candidate_${index}_text", result.text)
                .putFloat("candidate_${index}_ratio", result.detection.box.width / width)
                .putFloat("candidate_${index}_confidence", result.detection.confidence)
                .putString("candidate_${index}_orientation", orientation(width, height))
                .putLong("candidate_${index}_time", time)
        }
        edit.apply()
    }

    fun recentCandidates(now: Long = System.currentTimeMillis()): List<PlateCandidate> =
        (0 until prefs.getInt("candidate_count", 0)).mapNotNull { index ->
            val time = prefs.getLong("candidate_${index}_time", 0)
            if (time <= 0 || now - time !in 0L..300_000L) return@mapNotNull null
            val text = prefs.getString("candidate_${index}_text", null) ?: return@mapNotNull null
            PlateCandidate(text, prefs.getFloat("candidate_${index}_ratio", 0f),
                prefs.getFloat("candidate_${index}_confidence", 0f),
                prefs.getString("candidate_${index}_orientation", "") ?: "", time)
        }

    fun calibrate(candidate: PlateCandidate, knownMetres: Float, now: Long = System.currentTimeMillis()) {
        require(knownMetres in 2f..80f) { "實測距離須介於 2–80 公尺" }
        require(now - candidate.observedAt in 0L..300_000L && candidate.confidence >= 0.65f &&
            candidate.widthRatio in 0.015f..0.45f && candidate.orientation.isNotBlank()) {
            "校正目標已過期或不可信；請重新拍到清晰車牌"
        }
        prefs.edit().putFloat("calibration_factor", knownMetres * candidate.widthRatio)
            .putString("calibration_orientation", candidate.orientation)
            .putString("reference_plate", candidate.text)
            .putFloat("reference_metres", knownMetres)
            .putLong("calibrated_at", now).apply()
    }

    fun calibrationFor(width: Int, height: Int): DistanceCalibration? {
        val factor = prefs.getFloat("calibration_factor", 0f)
        val orientation = prefs.getString("calibration_orientation", "") ?: ""
        return if (factor > 0f && orientation == orientation(width, height)) {
            DistanceCalibration(factor, orientation,
                prefs.getString("reference_plate", "") ?: "", prefs.getFloat("reference_metres", 0f))
        } else null
    }

    fun clearCalibration() {
        prefs.edit().remove("calibration_factor").remove("calibration_orientation")
            .remove("reference_plate").remove("reference_metres").remove("calibrated_at").apply()
    }
}
