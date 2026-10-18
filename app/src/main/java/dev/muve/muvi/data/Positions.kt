package dev.muve.muvi.data

import android.content.Context

/**
 * Dónde se quedó cada video, para seguir donde ibas. Se guarda por ruta en el teléfono.
 * Solo cuenta si viste más de [MIN_MS] y faltan más de [END_MS] para el final.
 */
class Positions(context: Context) {
    private val prefs = context.getSharedPreferences("muvi_positions", Context.MODE_PRIVATE)

    fun get(path: String): Long = prefs.getLong(path, 0L)

    fun save(path: String, positionMs: Long, durationMs: Long) {
        val keep = shouldKeep(positionMs, durationMs)
        prefs.edit().apply { if (keep) putLong(path, positionMs) else remove(path) }.apply()
    }

    fun clear(path: String) = prefs.edit().remove(path).apply()

    companion object {
        const val MIN_MS = 10_000L
        const val END_MS = 15_000L

        fun shouldKeep(positionMs: Long, durationMs: Long): Boolean =
            positionMs >= MIN_MS && (durationMs <= 0 || durationMs - positionMs > END_MS)
    }
}
