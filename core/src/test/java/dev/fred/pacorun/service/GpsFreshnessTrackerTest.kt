package dev.fred.pacorun.service

import dev.fred.pacorun.location.LocationSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsFreshnessTrackerTest {
    @Test
    fun alreadyOldCallbackDoesNotMakeGpsFresh() {
        val tracker = GpsFreshnessTracker()
        val staleSample = LocationSample(
            speedKmh = 0f,
            speedAccuracyKmh = null,
            reliable = false,
            elapsedRealtimeMillis = 6_999L,
        )

        tracker.record(staleSample)

        assertTrue(tracker.isOutage(10_000L))
    }

    @Test
    fun reliableSampleExpiresImmediatelyAfterMapperFreshnessLimit() {
        val tracker = GpsFreshnessTracker()
        val freshSample = LocationSample(
            speedKmh = 0f,
            speedAccuracyKmh = null,
            reliable = true,
            elapsedRealtimeMillis = 10_000L,
        )

        tracker.record(freshSample)

        assertFalse(tracker.isOutage(13_000L))
        assertTrue(tracker.isOutage(13_001L))
    }
}
