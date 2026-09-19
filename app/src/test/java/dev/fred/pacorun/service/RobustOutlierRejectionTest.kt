package dev.fred.pacorun.service

import dev.fred.pacorun.eventlog.ProcessNoiseRegime
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class RobustOutlierRejectionTest {
    /** Shifts fix [index]'s true position by [offsetMeters] due north, keeping its declared
     * accuracy unchanged -- simulates a multipath spike that Android's own accuracy field never
     * flags, since accuracy metadata and actual position error are only "in the same file", not
     * causally linked in this synthetic injection (deliberately: that's the whole point of the gap
     * this test targets). */
    private fun withInjectedOutlier(fixes: List<SyntheticTrajectory.Fix>, index: Int, offsetMeters: Double): List<SyntheticTrajectory.Fix> {
        val target = fixes[index]
        val metersPerDegreeLatitude = 111_320.0
        val shiftedLatitude = target.position.latitude + offsetMeters / metersPerDegreeLatitude
        return fixes.toMutableList().also {
            it[index] = target.copy(
                position = target.position.copy(latitude = shiftedLatitude),
            )
        }
    }

    /** Builds a trajectory where fixes [degradedRange] have inflated declared accuracy (a
     * "degraded GPS" burst), everything else uses [normalAccuracyMeters]. */
    private fun withDegradedAccuracyBurst(
        fixes: List<SyntheticTrajectory.Fix>,
        degradedRange: IntRange,
        normalAccuracyMeters: Float,
        degradedAccuracyMeters: Float,
    ): List<SyntheticTrajectory.Fix> = fixes.mapIndexed { i, fix ->
        val accuracy = if (i in degradedRange) degradedAccuracyMeters else normalAccuracyMeters
        fix.copy(position = fix.position.copy(horizontalAccuracyMeters = accuracy))
    }

    @Test
    fun goodAccuracyButAnomalousPositionIsDampened() {
        val fixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(30.0 to 8.0),
            noiseStdMeters = 3.0,
            seed = 7L,
        )
        // A 25m northward jump on ONE fix, declared accuracy left at the trajectory's normal 3.0m
        // -- G7 (accuracy-metadata-based) has no way to catch this by construction; only a
        // residual-based test (G6) can.
        val withOutlier = withInjectedOutlier(fixes, index = 15, offsetMeters = 25.0)

        val estimator = SpeedEstimator()
        var speedAtOutlier = 0.0
        withOutlier.forEachIndexed { i, fix ->
            val speed = estimator.accept(fix.position, reliable = true)
            if (i == 15) speedAtOutlier = speed
        }

        // Without G6, a 25m single-fix jump at 1Hz implies ~90 km/h of instantaneous velocity
        // change fed straight into the filter. With G6's weight suppressing it, the speed at that
        // fix must stay close to the true 8 km/h -- not a rigorous bound, just "clearly dampened,
        // not blindly trusted".
        assertTrue(
            "expected outlier fix's speed to stay near 8 km/h (dampened), was $speedAtOutlier",
            abs(speedAtOutlier - 8.0) < 5.0,
        )
    }

    @Test
    fun robustWeightStaysNearOneOnOrdinaryNoise() {
        val fixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(60.0 to 10.0),
            noiseStdMeters = 3.5,
            seed = 42L,
        )
        val estimator = SpeedEstimator()
        val weights = fixes.drop(10).map { fix ->
            estimator.accept(fix.position, reliable = true)
            estimator.getLastRobustWeight()
        }
        val meanWeight = weights.average()
        assertTrue("expected mean robust weight close to 1.0 on ordinary noise, was $meanWeight", meanWeight > 0.9)
    }

    @Test
    fun regimeStaysQLowDuringDegradedBurstEvenWithG6Active() {
        val fixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(90.0 to 8.0),
            noiseStdMeters = 3.0,
            seed = 11L,
        )
        val withBurst = withDegradedAccuracyBurst(fixes, degradedRange = 20..40, normalAccuracyMeters = 3.0f, degradedAccuracyMeters = 25.0f)

        val estimator = SpeedEstimator()
        withBurst.take(20).forEach { estimator.accept(it.position, reliable = true) } // warm up before the burst
        val regimesDuringBurst = withBurst.subList(20, 41).map {
            estimator.accept(it.position, reliable = true)
            estimator.getLastProcessNoiseRegime()
        }

        assertTrue(
            "expected every fix during the degraded burst to stay in Q_LOW, saw: $regimesDuringBurst",
            regimesDuringBurst.all { it == ProcessNoiseRegime.Q_LOW },
        )
    }

    @Test
    fun emaNotContaminatedImmediatelyAfterBurstEnds() {
        val fixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(90.0 to 8.0),
            noiseStdMeters = 3.0,
            seed = 11L,
        )
        val withBurst = withDegradedAccuracyBurst(fixes, degradedRange = 20..40, normalAccuracyMeters = 3.0f, degradedAccuracyMeters = 25.0f)

        val estimator = SpeedEstimator()
        withBurst.take(41).forEach { estimator.accept(it.position, reliable = true) } // through the burst
        // Immediately after: a handful of clean, steady-state fixes at unchanged true speed.
        val regimesAfterBurst = withBurst.subList(41, 51).map {
            estimator.accept(it.position, reliable = true)
            estimator.getLastProcessNoiseRegime()
        }

        assertTrue(
            "expected the EMA to have been protected during the burst, so the first clean fixes " +
                "after it don't spuriously read as a real acceleration (Q_HIGH) at unchanged speed; saw: $regimesAfterBurst",
            regimesAfterBurst.count { it == ProcessNoiseRegime.Q_HIGH } == 0,
        )
    }
}
