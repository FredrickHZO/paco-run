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

        assertEquals(1.11, accumulator.accept(fix(0.0, .00001, 1_000L), reliable = true), .02)
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

    private fun fix(latitude: Double, longitude: Double, elapsedMillis: Long) =
        LocationPosition(latitude, longitude, elapsedMillis)
}
