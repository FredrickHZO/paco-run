package dev.fred.pacorun.service

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEstimatorTest {
    /**
     * Minimal noisy-trajectory helper for this task's own tests: constant-velocity ground
     * truth with real Gaussian position noise (std = accuracy) injected into each fix, same
     * shape the round-2 review used to catch the false-triggering bug. Not the full
     * SyntheticTrajectory class from a later task -- scoped only to what's needed here.
     */
    private class NoisyConstantVelocityTrajectory(
        private val accuracyMeters: Float,
        seed: Long,
        private var latitude: Double = 45.0,
        private val longitude: Double = 9.0,
    ) {
        private val random = Random(seed)
        private var elapsedMillis = 0L
        private val metersPerDegreeLatitude = 111_320.0

        fun firstFix(): LocationPosition = LocationPosition(latitude, longitude, elapsedMillis, accuracyMeters)

        /** Advances the true position by [speedKmh] for one second and returns a noisy fix. */
        fun nextFix(speedKmh: Double): LocationPosition {
            latitude += (speedKmh / 3.6) / metersPerDegreeLatitude
            elapsedMillis += 1_000L
            val metersPerDegreeLongitude = metersPerDegreeLatitude * cos(Math.toRadians(latitude))
            val noisyLatitude = latitude + (random.nextGaussian() * accuracyMeters) / metersPerDegreeLatitude
            val noisyLongitude = longitude + (random.nextGaussian() * accuracyMeters) / metersPerDegreeLongitude
            return LocationPosition(noisyLatitude, noisyLongitude, elapsedMillis, accuracyMeters)
        }
    }

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
    fun nanAccuracyDoesNotPermanentlyPoisonFilter() {
        val estimator = SpeedEstimator()
        val metersPerSecond = 10.0 / 3.6
        val degreesPerSecond = metersPerSecond / 111_320.0
        var latitude = 45.0
        var elapsedMillis = 0L

        estimator.accept(LocationPosition(latitude, 9.0, elapsedMillis, 3f), reliable = true)
        repeat(5) {
            latitude += degreesPerSecond
            elapsedMillis += 1_000L
            estimator.accept(LocationPosition(latitude, 9.0, elapsedMillis, 3f), reliable = true)
        }

        // A NaN horizontalAccuracyMeters must not poison the filter with NaN state -- the
        // guarded return should break the chain and return a finite value (0.0), not NaN.
        latitude += degreesPerSecond
        elapsedMillis += 1_000L
        val nanAccuracySpeed = estimator.accept(
            LocationPosition(latitude, 9.0, elapsedMillis, Float.NaN),
            reliable = true,
        )
        assertTrue("NaN accuracy fix must not produce a non-finite speed", nanAccuracySpeed.isFinite())
        assertEquals(0.0, nanAccuracySpeed, .001)

        // Recovery: breakChain() means the very next fix re-anchors (0.0 again, like a fresh
        // first fix), then subsequent good fixes should converge normally -- proving the filter
        // actually self-recovers, not just that the one bad output got suppressed.
        latitude += degreesPerSecond
        elapsedMillis += 1_000L
        val reanchorSpeed = estimator.accept(LocationPosition(latitude, 9.0, elapsedMillis, 3f), reliable = true)
        assertEquals(0.0, reanchorSpeed, .001)

        var recoveredSpeed = 0.0
        repeat(15) {
            latitude += degreesPerSecond
            elapsedMillis += 1_000L
            recoveredSpeed = estimator.accept(LocationPosition(latitude, 9.0, elapsedMillis, 3f), reliable = true)
        }
        assertTrue("filter must recover to a finite speed after a NaN accuracy fix", recoveredSpeed.isFinite())
        assertEquals(10.0, recoveredSpeed, 0.2)
    }

    @Test
    fun sustainedAccelerationTriggersFastSettlingViaAdaptiveProcessNoise() {
        // Deterministic, noise-free step change: 5 -> 12 km/h. Exact numbers are meaningful
        // here because there's no randomness -- this is the same scenario as task-3-brief.md's
        // original test.
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
        // settle measurably faster than with fixed qLow alone. (7 fixes, not 6: the round-2
        // retuned threshold of 0.60 -- needed to keep steady-state noise from spuriously
        // triggering qHigh, see steadyStateDoesNotFalselyTriggerAdaptiveQ below -- takes one
        // extra fix to cross versus the original round-1 threshold of 0.30.)
        var qHighEngaged = false
        var fixedQSettlingSpeed = 0.0

        repeat(7) {
            val (adaptive, fixed) = step(12.0)
            adaptiveSpeed = adaptive
            fixedQSettlingSpeed = fixed

            // qHigh (0.75) is well above qLow (0.10); anything past the midpoint means it engaged.
            if (adaptiveEstimator.getLastProcessNoise() > 0.4) {
                qHighEngaged = true
            }
        }

        assertEquals("qHigh should engage during acceleration", true, qHighEngaged)
        assertEquals("Adaptive should settle close to 12 km/h", 12.0, adaptiveSpeed, 0.5)
        val adaptiveError = abs(adaptiveSpeed - 12.0)
        val fixedError = abs(fixedQSettlingSpeed - 12.0)
        assertTrue(
            "Adaptive should be closer to target than fixed qLow " +
            "(adaptive: ${"%.2f".format(adaptiveSpeed)}, fixed: ${"%.2f".format(fixedQSettlingSpeed)})",
            adaptiveError < fixedError
        )
    }

    @Test
    fun sustainedAccelerationSettlesFasterThanFixedQWithRealNoise() {
        // Round-2 evidence: the noise-free test above proves the switch *can* engage, but says
        // nothing about real GPS noise. Re-run the same 5 -> 12 km/h step across several seeds
        // and accuracy levels (3-6.6m, the design spec's measured range) with real Gaussian
        // position noise, and confirm the adaptive filter is *statistically*, not just once,
        // closer to the true speed than a fixed-qLow control fed the exact same noisy fixes.
        val accuracies = listOf(3f, 4.5f, 6.6f)
        var adaptiveBetterCount = 0
        var totalRuns = 0
        val adaptiveErrors = mutableListOf<Double>()
        val fixedErrors = mutableListOf<Double>()

        for (accuracy in accuracies) {
            for (seed in 1L..6L) {
                val trajectory = NoisyConstantVelocityTrajectory(accuracy, seed = seed * 1000 + accuracy.toLong())
                val adaptive = SpeedEstimator()
                val fixed = SpeedEstimator(qLow = 0.10, qHigh = 0.10)

                val firstFix = trajectory.firstFix()
                adaptive.accept(firstFix, reliable = true)
                fixed.accept(firstFix, reliable = true)
                repeat(15) {
                    val noisyFix = trajectory.nextFix(5.0)
                    adaptive.accept(noisyFix, reliable = true)
                    fixed.accept(noisyFix, reliable = true)
                }

                var adaptiveSpeed = 0.0
                var fixedSpeed = 0.0
                repeat(6) {
                    val noisyFix = trajectory.nextFix(12.0)
                    adaptiveSpeed = adaptive.accept(noisyFix, reliable = true)
                    fixedSpeed = fixed.accept(noisyFix, reliable = true)
                }

                val adaptiveError = abs(adaptiveSpeed - 12.0)
                val fixedError = abs(fixedSpeed - 12.0)
                adaptiveErrors.add(adaptiveError)
                fixedErrors.add(fixedError)
                if (adaptiveError < fixedError) adaptiveBetterCount++
                totalRuns++
            }
        }

        val medianAdaptiveError = adaptiveErrors.sorted()[adaptiveErrors.size / 2]
        val medianFixedError = fixedErrors.sorted()[fixedErrors.size / 2]

        // Reliable, not lucky: adaptive beats the fixed-qLow control on a clear majority of
        // these 18 seeded noisy runs, and its typical (median) error is meaningfully lower.
        assertTrue(
            "Adaptive should beat fixed qLow on a majority of noisy runs " +
                "(won $adaptiveBetterCount/$totalRuns)",
            adaptiveBetterCount >= (totalRuns * 0.7).toInt()
        )
        assertTrue(
            "Adaptive median error ($medianAdaptiveError) should be clearly below fixed median error ($medianFixedError)",
            medianAdaptiveError < medianFixedError * 0.9
        )
    }

    @Test
    fun steadyStateDoesNotFalselyTriggerAdaptiveQ() {
        // Round-2 evidence: with the old *unsigned*-magnitude EMA, real GPS noise alone (no
        // acceleration at all) crossed the threshold on ~96% of fixes. Verify the signed-EMA
        // switch does NOT do this: inject real Gaussian noise (std = accuracy, 3-6.6m per the
        // design spec) into an otherwise-constant-velocity trajectory and confirm qHigh spends
        // only a small fraction of fixes engaged -- occasional brief spikes are fine, spending
        // most of the time in qHigh is not.
        val accuracies = listOf(3f, 4.5f, 6.6f)

        for (accuracy in accuracies) {
            var highCount = 0
            val fixes = 150
            val trajectory = NoisyConstantVelocityTrajectory(accuracy, seed = 42L + accuracy.toInt())
            val estimator = SpeedEstimator()
            var speedKmh = 0.0

            estimator.accept(trajectory.firstFix(), reliable = true)
            repeat(fixes) {
                speedKmh = estimator.accept(trajectory.nextFix(5.0), reliable = true)
                if (estimator.getLastProcessNoise() > 0.4) highCount++
            }

            assertEquals("accuracy=$accuracy should still converge near true speed", 5.0, speedKmh, 1.0)
            val highFraction = highCount.toDouble() / fixes
            assertTrue(
                "accuracy=$accuracy: qHigh engaged $highCount/$fixes fixes (${"%.1f".format(highFraction * 100)}%) " +
                    "during pure constant-velocity noise -- should be rare, not routine",
                highFraction < 0.15
            )
        }
    }

    @Test
    fun accuracyDegradationForcesQLowEvenWhenInnovationEmaWouldTriggerQHigh() {
        // Converge at 5 km/h, then a sustained real jump to 12 km/h (mirrors
        // sustainedAccelerationTriggersFastSettlingViaAdaptiveProcessNoise) so the innovation EMA
        // climbs past innovationThreshold on its own -- this reproduces the "innovation alone
        // would otherwise cross innovationThreshold" precondition from the task brief. Once qHigh
        // has engaged from that real acceleration, feed one more fix whose accuracy suddenly
        // collapses (the round-2 review's Task 8 finding: a severe accuracy degradation with no
        // real motion change looks just like more real acceleration to the innovation detector).
        // The accuracy gate must force qLow for that fix regardless of the already-elevated EMA.
        val estimator = SpeedEstimator()
        val metersPerDegree = 111_320.0
        var latitude = 45.0
        var elapsedMillis = 0L

        fun step(speedKmh: Double, accuracy: Float): Double {
            latitude += (speedKmh / 3.6) / metersPerDegree
            elapsedMillis += 1_000L
            return estimator.accept(LocationPosition(latitude, 9.0, elapsedMillis, accuracy), reliable = true)
        }

        estimator.accept(LocationPosition(latitude, 9.0, 0L, 3f), reliable = true)
        repeat(15) { step(5.0, 3f) }

        // 6 fixes at the new speed leaves the innovation EMA still above innovationThreshold
        // (0.60) going into the next fix -- i.e. without the gate, the next fix's process-noise
        // decision (which is made from the EMA accumulated by *prior* fixes, not its own
        // measurement) would still resolve to qHigh.
        var qHighEngaged = false
        repeat(6) {
            step(12.0, 3f)
            if (estimator.getLastProcessNoise() > 0.4) qHighEngaged = true
        }
        assertTrue("precondition: qHigh should have engaged from the real acceleration", qHighEngaged)
        assertTrue(
            "precondition: innovation EMA must still be above threshold going into the degraded fix",
            estimator.getLastInnovationEma() > 0.60,
        )

        // One more fix, same continuing true motion, but this fix's accuracy collapses to 30 m
        // (10x the converged 3 m baseline) -- no new real motion change, just degraded accuracy.
        step(12.0, 30f)

        assertEquals(
            "a severely degraded-accuracy fix must force qLow, even though the innovation EMA " +
                "(from the preceding real acceleration) would otherwise say qHigh",
            0.10,
            estimator.getLastProcessNoise(),
            0.001,
        )
    }

    @Test
    fun accuracyGatedFixDoesNotPoisonInnovationEma() {
        // A gated fix's Kalman update() still runs (that math is untouched and correctly
        // down-weights the noisy point), but its necessarily-large innovation must NOT be folded
        // into eastInnovationEma/northInnovationEma -- otherwise this fix's own degraded-accuracy
        // noise would poison the *next* fix's switch decision, even though nothing about it
        // reflects a real acceleration. Verify the EMA is unchanged by the gated fix.
        val estimator = SpeedEstimator()
        val metersPerDegree = 111_320.0
        var latitude = 45.0
        var elapsedMillis = 0L

        estimator.accept(LocationPosition(latitude, 9.0, 0L, 3f), reliable = true)
        repeat(15) {
            latitude += (5.0 / 3.6) / metersPerDegree
            elapsedMillis += 1_000L
            estimator.accept(LocationPosition(latitude, 9.0, elapsedMillis, 3f), reliable = true)
        }
        val emaBeforeGatedFix = estimator.getLastInnovationEma()

        // A huge position jump (a real acceleration would produce a similarly large innovation)
        // combined with severely degraded accuracy (30 m vs. the converged 3 m baseline) --
        // engages the gate.
        latitude += 50.0 / metersPerDegree
        elapsedMillis += 1_000L
        estimator.accept(LocationPosition(latitude, 9.0, elapsedMillis, 30f), reliable = true)

        assertTrue("precondition: the gate should have engaged on this fix", estimator.getLastAccuracyGateEngaged())
        assertEquals(
            "a gated fix's innovation must not change eastInnovationEma/northInnovationEma",
            emaBeforeGatedFix,
            estimator.getLastInnovationEma(),
            1e-9,
        )
    }

    @Test
    fun accuracyGateProtectsWholeDegradedBurstUnlikeANaiveLastFixBaseline() {
        // Task 8 finding (prior plan's final review): the false transient peaks partway through
        // a multi-fix degraded-accuracy burst (fix 4-6 of 8), not on the very first degraded fix.
        // A single-previous-fix baseline would only see a large ratio on the very first degraded
        // fix (compared against the last *good* fix); every fix after that compares one degraded
        // fix's accuracy against the previous fix's *already-degraded* accuracy, so the ratio
        // collapses back near 1.0 and it silently stops protecting right where the transient
        // peaks. accuracyBaselineAlpha=1.0 makes the baseline fully replace itself with the
        // current fix's own accuracy every time -- exactly a "compare against the last fix" rule
        // -- so it stands in here for that explicitly-rejected naive approach. This test proves
        // the default EMA baseline (real memory, alpha=0.05) keeps firing across the *whole*
        // burst, while the naive alpha=1.0 baseline stops after fix 1.
        val emaGatedEstimator = SpeedEstimator()
        val naiveLastFixEstimator = SpeedEstimator(accuracyBaselineAlpha = 1.0)
        val metersPerDegree = 111_320.0
        var latitude = 45.0
        var elapsedMillis = 0L

        fun step(accuracy: Float) {
            latitude += (8.0 / 3.6) / metersPerDegree
            elapsedMillis += 1_000L
            val pos = LocationPosition(latitude, 9.0, elapsedMillis, accuracy)
            emaGatedEstimator.accept(pos, reliable = true)
            naiveLastFixEstimator.accept(pos, reliable = true)
        }

        emaGatedEstimator.accept(LocationPosition(latitude, 9.0, 0L, 3.5f), reliable = true)
        naiveLastFixEstimator.accept(LocationPosition(latitude, 9.0, 0L, 3.5f), reliable = true)
        repeat(20) { step(3.5f) }

        val emaGateEngaged = mutableListOf<Boolean>()
        val naiveGateEngaged = mutableListOf<Boolean>()
        repeat(8) {
            step(20f)
            emaGateEngaged += emaGatedEstimator.getLastAccuracyGateEngaged()
            naiveGateEngaged += naiveLastFixEstimator.getLastAccuracyGateEngaged()
        }

        assertEquals(
            "the EMA-baseline gate (default alpha) must protect every fix of the 8-fix degraded burst",
            List(8) { true },
            emaGateEngaged,
        )
        assertEquals(
            "a naive last-fix baseline (alpha=1.0) only catches the first fix of the burst, then " +
                "silently stops protecting -- exactly the failure mode this design rejects",
            listOf(true, false, false, false, false, false, false, false),
            naiveGateEngaged,
        )
    }

    @Test
    fun unreliableFixEmitsSpeedChainBrokenEventBeforeResetting() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val estimator = SpeedEstimator(onEvent = events::add)

        estimator.accept(fix(0.0, 0.0, 0L), reliable = true)
        estimator.accept(fix(0.0, .0001, 1_000L), reliable = false)

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.SpeedChainBrokenEvent(
                    1_000L,
                    dev.fred.pacorun.eventlog.SpeedChainBreakReason.UNRELIABLE_OR_INVALID_FIX,
                ),
            ),
            events,
        )
    }

    @Test
    fun nonPositiveTimeDeltaEmitsSpeedChainBrokenEventBeforeResetting() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val estimator = SpeedEstimator(onEvent = events::add)

        estimator.accept(fix(0.0, 0.0, 1_000L), reliable = true)
        estimator.accept(fix(0.0, .0001, 1_000L), reliable = true)

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.SpeedChainBrokenEvent(
                    1_000L,
                    dev.fred.pacorun.eventlog.SpeedChainBreakReason.NON_POSITIVE_TIME_DELTA,
                ),
            ),
            events,
        )
    }

    @Test
    fun nonFiniteResultEmitsSpeedChainBrokenEventBeforeResetting() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val estimator = SpeedEstimator(onEvent = events::add)
        val metersPerSecond = 10.0 / 3.6
        val degreesPerSecond = metersPerSecond / 111_320.0
        var latitude = 45.0
        var elapsedMillis = 0L

        estimator.accept(LocationPosition(latitude, 9.0, elapsedMillis, 3f), reliable = true)
        repeat(5) {
            latitude += degreesPerSecond
            elapsedMillis += 1_000L
            estimator.accept(LocationPosition(latitude, 9.0, elapsedMillis, 3f), reliable = true)
        }
        // Same NaN-accuracy trigger as SpeedEstimatorTest's own nanAccuracyDoesNotPermanentlyPoisonFilter:
        // a NaN horizontalAccuracyMeters poisons the Kalman update into a non-finite speed.
        latitude += degreesPerSecond
        elapsedMillis += 1_000L
        estimator.accept(LocationPosition(latitude, 9.0, elapsedMillis, Float.NaN), reliable = true)

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.SpeedChainBrokenEvent(
                    elapsedMillis,
                    dev.fred.pacorun.eventlog.SpeedChainBreakReason.NON_FINITE_RESULT,
                ),
            ),
            events,
        )
    }

    @Test
    fun normalConvergingFixesEmitNoEvent() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val estimator = SpeedEstimator(onEvent = events::add)

        estimator.accept(fix(45.4642, 9.1900, 0L), reliable = true)
        estimator.accept(fix(45.4643, 9.1900, 1_000L), reliable = true)

        assertEquals(emptyList<dev.fred.pacorun.eventlog.SessionEvent>(), events)
    }

    @Test
    fun lastAcceptWasMeasurementIsTrueOnlyForFixesThatRanARealKalmanUpdate() {
        val estimator = SpeedEstimator()

        assertEquals(false, estimator.getLastAcceptWasMeasurement()) // before any accept()

        estimator.accept(fix(45.4642, 9.1900, 0L), reliable = true)
        assertEquals(false, estimator.getLastAcceptWasMeasurement()) // init branch, no update yet

        estimator.accept(fix(45.4643, 9.1900, 1_000L), reliable = true)
        assertEquals(true, estimator.getLastAcceptWasMeasurement())

        // Non-positive dt breaks the chain without a measurement -- must not keep reporting true.
        estimator.accept(fix(45.4644, 9.1900, 1_000L), reliable = true)
        assertEquals(false, estimator.getLastAcceptWasMeasurement())
    }

    private fun fix(latitude: Double, longitude: Double, elapsedMillis: Long, horizontalAccuracyMeters: Float? = null) =
        LocationPosition(latitude, longitude, elapsedMillis, horizontalAccuracyMeters)
}
