package dev.fred.pacorun.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsSilentGapTrackerTest {
    @Test
    fun callbackWithinThresholdReportsNoGap() {
        val tracker = GpsSilentGapTracker(thresholdMillis = 5_000L)
        tracker.reset(0L)

        assertNull(tracker.recordCallback(4_999L))
    }

    @Test
    fun callbackAfterARealGapReportsItsActualDuration() {
        val tracker = GpsSilentGapTracker(thresholdMillis = 5_000L)
        tracker.reset(0L)
        tracker.recordCallback(1_000L)

        assertEquals(12_000L, tracker.recordCallback(13_000L))
    }

    @Test
    fun aShortCallbackAfterAReportedGapMeasuresOnlyTheNewInterval() {
        val tracker = GpsSilentGapTracker(thresholdMillis = 5_000L)
        tracker.reset(0L)
        assertEquals(9_000L, tracker.recordCallback(9_000L))

        assertNull(tracker.recordCallback(10_000L))
    }

    @Test
    fun resetEstablishesTheBaselineForAGapMeasuredFromSessionStart() {
        val tracker = GpsSilentGapTracker(thresholdMillis = 5_000L)

        tracker.reset(1_000L)

        assertEquals(6_000L, tracker.recordCallback(7_000L))
    }
}
