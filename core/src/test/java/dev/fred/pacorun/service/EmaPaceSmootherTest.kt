package dev.fred.pacorun.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmaPaceSmootherTest {
    @Test
    fun firstAcceptReturnsTheRawValueUnchanged() {
        val smoother = EmaPaceSmoother(timeConstantSeconds = 6.0)

        assertEquals(9.4f, smoother.accept(9.4f, 12_345L), 0f)
    }

    @Test
    fun accumulatesTowardRawWithATimeNormalizedAlphaEqualToOneMinusExpMinusDtOverTau() {
        val smallDtSmoother = EmaPaceSmoother(timeConstantSeconds = 6.0)
        smallDtSmoother.accept(8f, 0L)
        val afterOneSecond = smallDtSmoother.accept(10f, 1_000L)

        val largeDtSmoother = EmaPaceSmoother(timeConstantSeconds = 6.0)
        largeDtSmoother.accept(8f, 0L)
        val afterSixSeconds = largeDtSmoother.accept(10f, 6_000L)

        // alpha = 1 - exp(-dt/tau): a 1s gap barely moves off the previous value (~8.307), a 6s
        // gap (one full tau) moves ~63% of the way to the new raw reading (~9.264) -- Decision 3's
        // time normalization, distinct from SpeedEstimator's fixed-alpha-per-sample EMA.
        assertEquals(8.30704f, afterOneSecond, 0.0005f)
        assertEquals(9.26424f, afterSixSeconds, 0.0005f)
        assertTrue(afterOneSecond < afterSixSeconds)
    }

    @Test
    fun purgeResetsToRawPassthroughOnTheNextAccept() {
        val smoother = EmaPaceSmoother(timeConstantSeconds = 6.0)
        smoother.accept(8f, 0L)
        smoother.accept(10f, 6_000L) // now holds smoothed memory around 9.26

        smoother.purge()
        val afterPurge = smoother.accept(12f, 6_001L)

        assertEquals(12f, afterPurge, 0f) // first sample after purge: raw passthrough, zero smoothing
    }

    @Test
    fun nonPositiveDtKeepsThePreviousSmoothedValueUnchanged() {
        val smoother = EmaPaceSmoother(timeConstantSeconds = 6.0)
        smoother.accept(8f, 1_000L)
        val duplicateTick = smoother.accept(20f, 1_000L) // same elapsed timestamp -- dt=0, stale/duplicate tick

        assertEquals(8f, duplicateTick, 0f)
    }
}
