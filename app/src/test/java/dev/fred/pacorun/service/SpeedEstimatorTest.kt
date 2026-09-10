package dev.fred.pacorun.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedEstimatorTest {
    @Test
    fun firstReliableFixProducesNoSpeedYet() {
        val estimator = SpeedEstimator()

        assertEquals(0.0, estimator.accept(fix(45.4642, 9.1900, 0L), reliable = true), .001)
    }

    @Test
    fun unreliableFixResetsStateAndReturnsZero() {
        val estimator = SpeedEstimator()

        estimator.accept(fix(0.0, 0.0, 0L), reliable = true)
        assertEquals(0.0, estimator.accept(fix(0.0, .0001, 1_000L), reliable = false), .001)
    }

    @Test
    fun invalidPositionResetsStateAndReturnsZero() {
        val estimator = SpeedEstimator()

        estimator.accept(fix(0.0, 0.0, 0L), reliable = true)
        assertEquals(0.0, estimator.accept(fix(Double.NaN, 0.0, 1_000L), reliable = true), .001)
    }

    @Test
    fun nonPositiveElapsedTimeResetsStateAndReturnsZero() {
        val estimator = SpeedEstimator()

        estimator.accept(fix(0.0, 0.0, 1_000L), reliable = true)
        // A fix with a timestamp at or before the previous one can't produce a valid dt.
        assertEquals(0.0, estimator.accept(fix(0.0, .0001, 1_000L), reliable = true), .001)
    }

    @Test
    fun explicitBreakChainResetsState() {
        val estimator = SpeedEstimator()

        estimator.accept(fix(0.0, 0.0, 0L), reliable = true)
        assertEquals(0.0, estimator.breakChain(), .001)
        // After the reset, this behaves like a fresh first fix -- no speed yet, not a huge
        // jump computed against the pre-reset state.
        assertEquals(0.0, estimator.accept(fix(0.0, .0001, 1_000L), reliable = true), .001)
    }

    @Test
    fun constantVelocityNoNoiseConvergesToTrueSpeed() {
        val estimator = SpeedEstimator()
        // 10 km/h north, no measurement noise: 1 degree latitude ~= 111_320m, so each 1s
        // step advances latitude by (10/3.6) / 111_320 degrees.
        val metersPerSecond = 10.0 / 3.6
        val degreesPerSecond = metersPerSecond / 111_320.0
        var latitude = 45.0
        var speedKmh = 0.0

        estimator.accept(LocationPosition(latitude, 9.0, 0L, 3f), reliable = true)
        repeat(15) { i ->
            latitude += degreesPerSecond
            speedKmh = estimator.accept(LocationPosition(latitude, 9.0, (i + 1) * 1_000L, 3f), reliable = true)
        }

        // 15 fixes of noise-free constant motion is comfortably enough to converge.
        assertEquals(10.0, speedKmh, 0.2)
    }

    @Test
    fun sustainedAccelerationTriggersFastSettlingViaAdaptiveProcessNoise() {
        val estimator = SpeedEstimator()
        val metersPerDegree = 111_320.0
        var latitude = 45.0
        var elapsedMillis = 0L
        var speedKmh = 0.0

        fun step(speedKmhSegment: Double): Double {
            latitude += (speedKmhSegment / 3.6) / metersPerDegree
            elapsedMillis += 1_000L
            return estimator.accept(LocationPosition(latitude, 9.0, elapsedMillis, 3f), reliable = true)
        }

        estimator.accept(LocationPosition(latitude, 9.0, 0L, 3f), reliable = true)
        // Settle at 5 km/h first.
        repeat(15) { speedKmh = step(5.0) }
        assertEquals(5.0, speedKmh, 0.2)

        // Sustained jump to 12 km/h -- give it 8 fixes to catch up (adaptive q kicks in
        // around fix 5, giving time for the filter to track the new speed).
        repeat(8) { speedKmh = step(12.0) }
        assertEquals(12.0, speedKmh, 1.0)
    }

    private fun fix(latitude: Double, longitude: Double, elapsedMillis: Long, horizontalAccuracyMeters: Float? = null) =
        LocationPosition(latitude, longitude, elapsedMillis, horizontalAccuracyMeters)
}
