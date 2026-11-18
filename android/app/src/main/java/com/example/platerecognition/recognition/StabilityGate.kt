package com.example.platerecognition.recognition

import java.util.Locale

/** Applies a per-local-day consecutive-frame gate before a persistent reservation. */
class StabilityGate(private val requiredHits: Int = 2, private val maxGapMillis: Long = 4_000) {
    private data class Hit(var count: Int, var lastSeen: Long, var emitted: Boolean)
    private val hits = mutableMapOf<String, Hit>()

    fun observe(results: List<PlateResult>, day: String, nowMillis: Long): List<PlateResult> {
        require(day.isNotEmpty())
        val byKey = results.mapNotNull { result ->
            val key = normalize(result.text) ?: return@mapNotNull null
            "$day:$key" to result
        }.distinctBy { it.first }
        val visible = byKey.map { it.first }.toSet()
        hits.keys.removeAll { key ->
            key !in visible || nowMillis - (hits[key]?.lastSeen ?: 0) > maxGapMillis
        }
        return byKey.mapNotNull { (key, result) ->
            val hit = hits[key]
            if (hit == null) {
                hits[key] = Hit(1, nowMillis, false)
                null
            } else {
                hit.count++
                hit.lastSeen = nowMillis
                if (hit.count >= requiredHits && !hit.emitted) {
                    hit.emitted = true
                    result
                } else null
            }
        }
    }

    fun forget(text: String, day: String) {
        normalize(text)?.let { hits.remove("$day:$it") }
    }

    companion object {
        fun normalize(text: String): String? {
            val key = text.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
            val length = key.codePointCount(0, key.length)
            // The bundled model emits Chinese province characters + ASCII letters/digits.
            return key.takeIf { length in 5..10 && it.any(Char::isDigit) && it.any { c -> c in 'A'..'Z' } }
        }
    }
}
