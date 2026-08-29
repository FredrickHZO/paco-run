package dev.fred.pacorun.service

import dev.fred.pacorun.location.LocationSampleMapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsFreshnessTrackerTest {
    @Test
    fun alreadyOldCallbackDoesNotMakeGpsFresh() {
        val tracker = GpsFreshnessTracker()
        val staleSample = LocationSampleMapper().map(
            speedMps = 2.5f,
            accuracyMps = .1f,
            ageMillis = 3_001L,
            elapsedRealtimeMillis = 6_999L,
        )

        tracker.record(staleSample)

        assertTrue(tracker.isOutage(10_000L))
    }

    @Test
    fun reliableSampleExpiresImmediatelyAfterMapperFreshnessLimit() {
        val tracker = GpsFreshnessTracker()
        val freshSample = LocationSampleMapper().map(
            speedMps = 2.5f,
            accuracyMps = .1f,
            ageMillis = 0L,
            elapsedRealtimeMillis = 10_000L,
        )

        tracker.record(freshSample)

        assertFalse(tracker.isOutage(13_000L))
        assertTrue(tracker.isOutage(13_001L))
    }
}
