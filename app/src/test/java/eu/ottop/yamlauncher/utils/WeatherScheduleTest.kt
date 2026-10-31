package eu.ottop.yamlauncher.utils

import org.junit.Assert.*
import org.junit.Test

class WeatherScheduleTest {

    private val interval = 15 * 60_000L

    @Test
    fun delayUntilNextFetch_fetchesNowWithoutCache() {
        assertEquals(0L, WeatherSchedule.delayUntilNextFetch(null, null, 0, 1_000_000L, interval))
    }

    @Test
    fun delayUntilNextFetch_waitsRemainingInterval() {
        val now = 10_000_000L
        val lastSuccess = now - 5 * 60_000L
        assertEquals(10 * 60_000L, WeatherSchedule.delayUntilNextFetch(lastSuccess, null, 0, now, interval))
    }

    @Test
    fun delayUntilNextFetch_fetchesNowOnceIntervalElapsed() {
        val now = 10_000_000L
        assertEquals(0L, WeatherSchedule.delayUntilNextFetch(now - interval, null, 0, now, interval))
        assertEquals(0L, WeatherSchedule.delayUntilNextFetch(now - 2 * interval, null, 0, now, interval))
    }

    @Test
    fun delayUntilNextFetch_fetchesNowWhenClockMovedBackwards() {
        val now = 10_000_000L
        assertEquals(0L, WeatherSchedule.delayUntilNextFetch(now + 60_000L, null, 0, now, interval))
    }

    @Test
    fun delayUntilNextFetch_usesBackoffAfterFailure() {
        val now = 10_000_000L
        val lastSuccess = now - 2 * interval
        assertEquals(60_000L, WeatherSchedule.delayUntilNextFetch(lastSuccess, now, 1, now, interval))
        assertEquals(90_000L, WeatherSchedule.delayUntilNextFetch(lastSuccess, now - 30_000L, 2, now, interval))
        assertEquals(0L, WeatherSchedule.delayUntilNextFetch(lastSuccess, now - 120_000L, 2, now, interval))
    }

    @Test
    fun retryDelay_isCappedAtInterval() {
        assertEquals(60_000L, WeatherSchedule.retryDelay(1, interval))
        assertEquals(240_000L, WeatherSchedule.retryDelay(3, interval))
        assertEquals(interval, WeatherSchedule.retryDelay(10, interval))
        assertEquals(interval, WeatherSchedule.retryDelay(Int.MAX_VALUE, interval))
    }

    @Test
    fun cacheKey_ignoresGpsCoordinates() {
        assertEquals(
            WeatherCache.keyFor(true, "latitude=1.0&longitude=2.0", "celsius"),
            WeatherCache.keyFor(true, "latitude=3.0&longitude=4.0", "celsius")
        )
        assertNotEquals(
            WeatherCache.keyFor(false, "latitude=1.0&longitude=2.0", "celsius"),
            WeatherCache.keyFor(false, "latitude=1.0&longitude=2.0", "fahrenheit")
        )
    }
}
