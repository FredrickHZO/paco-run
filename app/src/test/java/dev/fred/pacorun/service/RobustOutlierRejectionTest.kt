package dev.fred.pacorun.service

import dev.fred.pacorun.eventlog.ProcessNoiseRegime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class RobustOutlierRejectionTest {
    private fun fix(latitude: Double, longitude: Double, elapsedMillis: Long, accuracyMeters: Float = 3.0f) =
        LocationPosition(latitude, longitude, elapsedMillis, accuracyMeters)

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

    @Test
    fun normalAccuracyButAnomalousResidualStillExcludesFromEma() {
        val fixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(30.0 to 8.0),
            noiseStdMeters = 3.0,
            seed = 7L,
        )
        // A 25m northward jump on ONE fix, with NORMAL/GOOD declared accuracy (same shape as
        // goodAccuracyButAnomalousPositionIsDampened). This triggers the second OR disjunct
        // (jointRobustWeight < emaExclusionWeightThreshold), not the first (accuracyDegraded).
        // The EMA must be bit-for-bit unchanged because the entire `if (!excludedFromEma)` block
        // is skipped; any tolerance-based check would miss the real mistake (treating || as &&
        // or deleting the second criterion entirely).
        val withOutlier = withInjectedOutlier(fixes, index = 15, offsetMeters = 25.0)

        val estimator = SpeedEstimator()
        // Feed up to (not including) the outlier
        withOutlier.take(15).forEach { estimator.accept(it.position, reliable = true) }
        val emaBeforeOutlier = estimator.getLastInnovationEma()

        // Feed the outlier fix and check it was excluded via the second criterion, not the first
        estimator.accept(withOutlier[15].position, reliable = true)
        val accuracyGateEngaged = estimator.getLastAccuracyGateEngaged()
        val emaAfterOutlier = estimator.getLastInnovationEma()

        assertTrue(
            "accuracy gate (G7) should NOT have been engaged for normal-accuracy outlier, was: $accuracyGateEngaged",
            !accuracyGateEngaged,
        )
        assertTrue(
            "EMA must be unchanged when outlier is excluded via G6 (second OR criterion); " +
                "before=$emaBeforeOutlier, after=$emaAfterOutlier",
            emaBeforeOutlier == emaAfterOutlier,
        )
    }

    @Test
    fun firstFixAfterBurstIsExemptFromRobustWeightSuppression() {
        // CORRECTED (found by the Task 4 implementer, not by the design doc's five review
        // rounds): the original degradedRange (5..19, 15 fixes) was too long. accuracyBaselineMeters
        // is a plain EMA that updates unconditionally even on degraded fixes (see the existing
        // "always update the baseline...so it tracks reality" comment in SpeedEstimator.kt) -- it
        // chases the degraded value and, with these exact pre-existing G7 parameters
        // (accuracyDegradationRatio=2.0, accuracyBaselineAlpha=0.05, normal=3.0m, degraded=25.0m),
        // crosses the ratio threshold and self-disengages accuracyDegraded after exactly 12
        // degraded fixes -- verified by direct simulation, deterministic regardless of seed
        // (accuracy values don't depend on position noise). A 15-fix declared range silently
        // stopped being "degraded" 3 fixes before the test's own indices assumed it still was.
        // 12 fixes is the longest range that stays degraded for its ENTIRE declared length given
        // these exact parameters -- do not lengthen it without re-deriving this number.
        //
        // Also verified by the same simulation: with qLow's deliberately conservative process
        // noise, 12 fixes of suppressed reaction do NOT build up enough position uncertainty for
        // even a large speed jump (checked up to 60 km/h) to cross G6's own dampening threshold —
        // this specific scenario's catch-up residual would be ~1.0 weight even WITHOUT the
        // exemption. So this test verifies the exemption fires at the structurally correct fix
        // (the assertion that matters, and where the critical read-after-overwrite trap would
        // hide), not that it prevents a dramatic real-world consequence here -- that demonstration
        // is Task 5's costOfPostBurstExemptionOnAnIndependentAnomalyIsCharacterized test, which
        // uses a deliberately large, independent anomaly instead of relying on catch-up magnitude.
        val fixes = SyntheticTrajectory.generate(
            // Speed changes at index 5, exactly where the burst starts (not mid-burst as before) --
            // aligns the "real motion during suppression" scenario with the burst window itself.
            speedProfileKmh = listOf(5.0 to 8.0, 30.0 to 16.0),
            noiseStdMeters = 3.0,
            seed = 5L,
        )
        val withBurst = withDegradedAccuracyBurst(fixes, degradedRange = 5..16, normalAccuracyMeters = 3.0f, degradedAccuracyMeters = 25.0f)

        val estimator = SpeedEstimator()
        withBurst.take(17).forEach { estimator.accept(it.position, reliable = true) } // through the burst (indices 0-16)
        estimator.accept(withBurst[17].position, reliable = true) // FIRST recovery fix, right after the true 8->16 jump

        assertTrue(
            "expected the first post-burst fix to be exempt from G6's weight test",
            estimator.getLastPostBurstExemptionApplied(),
        )
        assertEquals(1.0, estimator.getLastRobustWeight(), 1e-9)
    }

    @Test
    fun secondFixOfNewChainAfterBreakChainIsNotWronglyExempted() {
        // Regression test for the bug found by self-check while writing the design doc:
        // breakChain() must reset lastAccuracyGateEngaged, or a fresh chain after a genuine GPS
        // outage inherits stale "just recovered from a burst" state from an unrelated prior chain.
        val estimator = SpeedEstimator()
        // Old chain: enter a degraded-accuracy state, then break the chain WHILE still degraded.
        estimator.accept(fix(45.0, 9.0, 0L, accuracyMeters = 3.0f), reliable = true)
        estimator.accept(fix(45.00003, 9.0, 1_000L, accuracyMeters = 30.0f), reliable = true) // accuracyDegraded == true here
        estimator.breakChain()

        // New, unrelated chain, with perfectly ordinary accuracy throughout.
        estimator.accept(fix(46.0, 10.0, 10_000L, accuracyMeters = 3.0f), reliable = true) // 1st fix of new chain: early return
        estimator.accept(fix(46.00003, 10.0, 11_000L, accuracyMeters = 3.0f), reliable = true) // 2nd fix: must NOT be exempted

        assertFalse(
            "a fresh chain's second fix must not inherit exemption state from an unrelated prior chain",
            estimator.getLastPostBurstExemptionApplied(),
        )
    }
}
