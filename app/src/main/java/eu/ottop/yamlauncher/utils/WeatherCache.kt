package eu.ottop.yamlauncher.utils

import android.content.Context
import androidx.core.content.edit

/**
 * Persists the last successful weather reading and when it was fetched.
 * Kept in its own preference file so writes neither reach the main settings
 * listener nor end up in settings backups.
 */
class WeatherCache(context: Context) {

    data class Entry(val text: String, val fetchedAtMs: Long)

    private val preferences = context.getSharedPreferences("weather_cache", Context.MODE_PRIVATE)

    /**
     * Returns the cached reading if it was fetched for the same inputs.
     */
    fun get(key: String): Entry? {
        if (preferences.getString("key", null) != key) return null
        val text = preferences.getString("text", null) ?: return null
        return Entry(text, preferences.getLong("fetchedAt", 0L))
    }

    fun put(key: String, text: String, fetchedAtMs: Long) {
        preferences.edit {
            putString("key", key)
            putString("text", text)
            putLong("fetchedAt", fetchedAtMs)
        }
    }

    companion object {
        /**
         * Builds the cache key from the inputs that affect the reading.
         * GPS coordinates are an output of the fetch, so GPS mode is keyed by mode only.
         */
        fun keyFor(isGps: Boolean, location: String, tempUnits: String): String {
            return "${if (isGps) "gps" else location}|$tempUnits"
        }
    }
}

/**
 * Decides when the next weather fetch is due.
 */
internal object WeatherSchedule {
    private const val BASE_RETRY_MS = 60_000L

    /**
     * Returns how long to wait before fetching, or 0 to fetch now.
     * After failures, waits a backoff from the last failure instead of the full interval.
     */
    fun delayUntilNextFetch(
        lastSuccessMs: Long?,
        lastFailureMs: Long?,
        failures: Int,
        nowMs: Long,
        intervalMs: Long
    ): Long {
        val (since, period) = if (failures > 0 && lastFailureMs != null) {
            lastFailureMs to retryDelay(failures, intervalMs)
        } else {
            lastSuccessMs to intervalMs
        }
        if (since == null) return 0L
        val elapsed = nowMs - since
        // A negative elapsed time means the wall clock moved backwards.
        if (elapsed < 0 || elapsed >= period) return 0L
        return period - elapsed
    }

    fun retryDelay(failures: Int, intervalMs: Long): Long {
        val shift = (failures - 1).coerceIn(0, 20)
        return (BASE_RETRY_MS shl shift).coerceAtMost(intervalMs)
    }
}
