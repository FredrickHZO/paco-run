package dev.fred.pacorun.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val adaptiveEstimator = SpeedEstimator()
        val fixedQEstimator = SpeedEstimator(qLow = 0.10, qHigh = 0.10) // Fixed qLow for control
        val metersPerDegree = 111_320.0
        var latitude = 45.0
        var elapsedMillis = 0L
        var adaptiveSpeed = 0.0
        var fixedSpeed = 0.0

        fun step(speedKmhSegment: Double): Pair<Double, Double> {
            latitude += (speedKmhSegment / 3.6) / metersPerDegree
            elapsedMillis += 1_000L
            val pos = LocationPosition(latitude, 9.0, elapsedMillis, 3f)
            return Pair(
                adaptiveEstimator.accept(pos, reliable = true),
                fixedQEstimator.accept(pos, reliable = true)
            )
        }

        adaptiveEstimator.accept(LocationPosition(latitude, 9.0, 0L, 3f), reliable = true)
        fixedQEstimator.accept(LocationPosition(latitude, 9.0, 0L, 3f), reliable = true)

        // Settle at 5 km/h first (both should converge similarly at constant speed).
        repeat(15) {
            val (adaptive, fixed) = step(5.0)
            adaptiveSpeed = adaptive
            fixedSpeed = fixed
        }
        assertEquals(5.0, adaptiveSpeed, 0.2)
        assertEquals(5.0, fixedSpeed, 0.2)

        // Sustained jump to 12 km/h. With adaptive q, the filter should engage qHigh and
        // settle measurably faster than with fixed qLow alone.
        // Innovation EMA should cross threshold (~0.65) around fixes 3-4, engaging qHigh.
        var qHighEngaged = false
        var maxInnovationEma = 0.0
        var fixedQSettlingSpeed = 0.0

        repeat(8) { fixIndex ->
            val (adaptive, fixed) = step(12.0)
            adaptiveSpeed = adaptive
            fixedSpeed = fixed
            fixedQSettlingSpeed = fixed

            val currentInnovationEma = adaptiveEstimator.getLastInnovationEma()
            val currentProcessNoise = adaptiveEstimator.getLastProcessNoise()
            maxInnovationEma = kotlin.math.max(maxInnovationEma, currentInnovationEma)

            // Check if qHigh (25.0) was engaged
            if (currentProcessNoise > 1.0) {
                qHighEngaged = true
            }
        }

        // Verify that adaptive approach with qHigh enabled settled measurably faster than fixed qLow.
        // Fixed qLow settles slowly; adaptive with qHigh should be noticeably closer to target.
        assertEquals("qHigh should engage during acceleration", true, qHighEngaged)
        assertEquals("Adaptive should settle close to 12 km/h", 12.0, adaptiveSpeed, 0.5)
        // Fixed qLow alone (with qHigh = qLow) should lag significantly behind.
        val adaptiveError = kotlin.math.abs(adaptiveSpeed - 12.0)
        val fixedError = kotlin.math.abs(fixedQSettlingSpeed - 12.0)
        assertTrue(
            "Adaptive should be closer to target than fixed qLow " +
            "(adaptive: ${"%.2f".format(adaptiveSpeed)}, fixed: ${"%.2f".format(fixedQSettlingSpeed)})",
            adaptiveError < fixedError
        )
    }

    @Test
    fun steadyStateDoesNotFalselyTriggerAdaptiveQ() {
        // Verify that at steady 5 km/h with realistic 3m accuracy, noise alone doesn't
        // cause qHigh to engage spuriously.
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
        var qHighEverEngaged = false

        // Steady 5 km/h for 20 fixes (more than enough to show if spurious engagement happens).
        repeat(20) {
            speedKmh = step(5.0)
            if (estimator.getLastProcessNoise() > 1.0) {
                qHighEverEngaged = true
            }
        }

        assertEquals(5.0, speedKmh, 0.2)
        assertEquals(
            "Steady-state constant speed should NOT trigger qHigh (no acceleration)",
            false,
            qHighEverEngaged
        )
    }

    private fun fix(latitude: Double, longitude: Double, elapsedMillis: Long, horizontalAccuracyMeters: Float? = null) =
        LocationPosition(latitude, longitude, elapsedMillis, horizontalAccuracyMeters)
}
