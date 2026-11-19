package com.example.platerecognition.recognition

/** One confirmation tone per visible encounter, independent of daily photo dedup. */
class RecognitionCue(private val separationMillis: Long = 2_000L,
                     private val minToneGapMillis: Long = 650L) {
    private val lastSeen = mutableMapOf<String, Long>()
    private val sounded = mutableSetOf<String>()
    private var lastTone = Long.MIN_VALUE / 2

    fun observe(currentTexts: List<String>, newlyConfirmed: List<String>, now: Long): Boolean {
        val current = currentTexts.mapNotNull { StabilityGate.normalize(it) }.toSet()
        lastSeen.keys.removeAll { key ->
            if (now - (lastSeen[key] ?: 0L) > separationMillis) {
                sounded.remove(key)
                true
            } else false
        }
        current.forEach { lastSeen[it] = now }
        val fresh = newlyConfirmed.mapNotNull { StabilityGate.normalize(it) }
            .any { it in current && it !in sounded }
        if (fresh) newlyConfirmed.mapNotNull { StabilityGate.normalize(it) }.forEach(sounded::add)
        if (!fresh || now - lastTone < minToneGapMillis) return false
        lastTone = now
        return true
    }

    fun reset() { lastSeen.clear(); sounded.clear() }
}
