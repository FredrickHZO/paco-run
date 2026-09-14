package dev.fred.pacorun.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsSilentGapTrackerTest {
    @Test
    fun noGapBeforeThresholdReturnsNull() {
        val tracker = GpsSilentGapTracker(thresholdMillis = 5_000L)
        tracker.reset(0L)

        assertNull(tracker.checkGap(4_999L))
    }

    @Test
    fun gapAtOrAboveThresholdReturnsItOnce() {
        val tracker = GpsSilentGapTracker(thresholdMillis = 5_000L)
        tracker.reset(0L)

        assertEquals(5_000L, tracker.checkGap(5_000L))
        assertNull(tracker.checkGap(6_000L)) // same silent episode -- already reported
    }

    @Test
    fun aNewCallbackAllowsANewGapToBeReportedAgain() {
        val tracker = GpsSilentGapTracker(thresholdMillis = 5_000L)
        tracker.reset(0L)
        tracker.checkGap(5_000L)

        tracker.recordCallback(6_000L)

        assertNull(tracker.checkGap(10_999L))
        assertEquals(5_000L, tracker.checkGap(11_000L))
    }

    @Test
    fun resetEstablishesTheBaselineForAPreFirstFixGap() {
        val tracker = GpsSilentGapTracker(thresholdMillis = 5_000L)

        tracker.reset(1_000L)

        assertNull(tracker.checkGap(5_999L))
        assertEquals(5_000L, tracker.checkGap(6_000L))
    }
}
