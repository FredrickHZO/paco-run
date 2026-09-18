package dev.fred.pacorun.service

import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceAccumulatorTest {
    @Test
    fun firstReliableFixCreatesAnchorWithoutDistance() {
        val accumulator = DistanceAccumulator()

        assertEquals(0.0, accumulator.accept(fix(45.4642, 9.1900, 0L), reliable = true), .001)
    }

    @Test
    fun consecutiveReliableFixesAddGeodesicDistance() {
        val accumulator = DistanceAccumulator()

        accumulator.accept(fix(0.0, 0.0, 0L), reliable = true)

        // 0.0001 degrees of longitude at the equator: a real, hand-computable geodesic
        // distance (~11.13m) comfortably clear of the jitter floor below, so this stays a
        // pure formula-correctness check unaffected by jitter suppression.
        assertEquals(11.13, accumulator.accept(fix(0.0, .0001, 1_000L), reliable = true), .02)
    }

    @Test
    fun subThresholdMovementIsTreatedAsJitterAndAnchorDoesNotMove() {
        val accumulator = DistanceAccumulator()

        accumulator.accept(fix(0.0, 0.0, 0L), reliable = true)
        // ~1.11m -- below the 2m jitter floor, must not count, and must not move the anchor.
        assertEquals(0.0, accumulator.accept(fix(0.0, .00001, 1_000L), reliable = true), .001)
        // Measured from the ORIGINAL anchor (not the jittered point), this is the same
        // ~11.13m real movement as the formula test above -- proving the anchor stayed put.
        assertEquals(11.13, accumulator.accept(fix(0.0, .0001, 2_000L), reliable = true), .02)
    }

    @Test
    fun jitterThresholdScalesWithReportedHorizontalAccuracy() {
        val accumulator = DistanceAccumulator()
        val poorAccuracy = 20f

        accumulator.accept(fix(0.0, 0.0, 0L, poorAccuracy), reliable = true)
        // ~11.13m real movement, but with 20m accuracy on both fixes the combined-accuracy
        // threshold (0.5 * (20+20) = 20m) is larger than the movement itself -- degraded
        // conditions must suppress a jump that would count fine with good accuracy.
        assertEquals(0.0, accumulator.accept(fix(0.0, .0001, 1_000L, poorAccuracy), reliable = true), .001)
    }

    @Test
    fun impossibleJumpBudgetStaysTightEvenWhileAnchorIsFrozenByJitter() {
        val accumulator = DistanceAccumulator(maxSpeedMetersPerSecond = 15.0)

        accumulator.accept(fix(0.0, 0.0, 0L), reliable = true)
        // Eight seconds of pure GPS noise at the same spot -- below the jitter floor, so the
        // anchor stays frozen at t=0 the whole time.
        repeat(8) { i -> accumulator.accept(fix(0.0, 0.0, (i + 1) * 1_000L), reliable = true) }
        // A single-fix glitch ~100m from the last fix in the one second since it (physically
        // impossible at 15 m/s): must be rejected even though it's "only" ~100m from the
        // long-frozen anchor, which an anchor-based budget (15 m/s * 9s = 135m) would wrongly allow.
        assertEquals(0.0, accumulator.accept(fix(0.0, .0009, 9_000L), reliable = true), .001)
    }

    @Test
    fun unreliableFixBreaksTheDistanceChain() {
        val accumulator = DistanceAccumulator()

        accumulator.accept(fix(0.0, 0.0, 0L), reliable = true)
        assertEquals(0.0, accumulator.accept(fix(0.0, .00001, 1_000L), reliable = false), .001)
        assertEquals(0.0, accumulator.accept(fix(0.0, .00002, 2_000L), reliable = true), .001)
    }

    @Test
    fun impossibleJumpIsRejectedAndNextFixStartsANewChain() {
        val accumulator = DistanceAccumulator(maxSpeedMetersPerSecond = 15.0)

        accumulator.accept(fix(0.0, 0.0, 0L), reliable = true)
        assertEquals(0.0, accumulator.accept(fix(0.0, .01, 1_000L), reliable = true), .001)
        assertEquals(0.0, accumulator.accept(fix(0.0, .00001, 2_000L), reliable = true), .001)
    }

    @Test
    fun unreliableOrInvalidFixEmitsDistanceChainBrokenEventBeforeResetting() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val accumulator = DistanceAccumulator(onEvent = events::add)

        accumulator.accept(fix(0.0, 0.0, 0L), reliable = false)

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.DistanceChainBrokenEvent(
                    0L,
                    dev.fred.pacorun.eventlog.DistanceChainBreakReason.UNRELIABLE_OR_INVALID_FIX,
                ),
            ),
            events,
        )
    }

    @Test
    fun implausibleJumpEmitsDistanceChainBrokenEventBeforeResetting() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val accumulator = DistanceAccumulator(maxSpeedMetersPerSecond = 15.0, onEvent = events::add)

        accumulator.accept(fix(0.0, 0.0, 0L), reliable = true)
        // ~1113m in one second -- far beyond the 15 m/s budget, must be rejected as a jump.
        accumulator.accept(fix(0.0, .01, 1_000L), reliable = true)

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.DistanceChainBrokenEvent(
                    1_000L,
                    dev.fred.pacorun.eventlog.DistanceChainBreakReason.IMPLAUSIBLE_JUMP,
                ),
            ),
            events,
        )
    }

    @Test
    fun reliableAcceptedFixesEmitNoEvent() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val accumulator = DistanceAccumulator(onEvent = events::add)

        accumulator.accept(fix(0.0, 0.0, 0L), reliable = true)
        accumulator.accept(fix(0.0, .0001, 1_000L), reliable = true)

        assertEquals(emptyList<dev.fred.pacorun.eventlog.SessionEvent>(), events)
    }

    private fun fix(latitude: Double, longitude: Double, elapsedMillis: Long, horizontalAccuracyMeters: Float? = null) =
        LocationPosition(latitude, longitude, elapsedMillis, horizontalAccuracyMeters)
}
