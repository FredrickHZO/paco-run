# Fase 1 — Robust Outlier Rejection (G6) + Satellite Geometry Logging (G9) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a joint (2-axis), mathematically self-consistent robust reweighting mechanism to `SpeedEstimator`'s Kalman update, so a GPS fix with *declared-good* accuracy but an *actually anomalous* position (multipath, reflection) gets down-weighted instead of blindly trusted — without touching or replacing any part of the existing accuracy-crash gate, which stays fully intact. Also adds per-satellite (not aggregated) elevation/azimuth logging, unrelated to the algorithm change, bundled here purely for dispatch convenience.

**Architecture:** `SpeedEstimator.kt`'s private `AxisKalman.update()` splits into a `peek` phase (raw normalized innovation, no state mutation) and an `apply` phase (real update, using a variance inflated by a jointly-computed robust weight). `SpeedEstimator.accept()` orchestrates: peek both axes → compute one shared weight from the joint (chi-squared-2-consistent) statistic → apply both axes with that weight. A structural exemption (not a tuned threshold) forces the weight to 1.0 for the fix immediately following a resolved accuracy-degradation burst, using state (`lastAccuracyGateEngaged`) the filter already tracks. `RunSessionService.kt`'s existing `GnssStatus.Callback` is extended, independently, to log a genuine per-satellite list instead of aggregated stats.

**Tech Stack:** Kotlin, JUnit 4, the project's existing hand-written JSON event log (`SessionEventWriter.kt`), no new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-19-fase1-robust-outlier-rejection-design.md` — read it in full before starting; this plan assumes it, and repeats only what each task needs verbatim. That spec itself went through five rounds of adversarial review; several of its details (the post-burst exemption, the `breakChain()` fix, the exact EMA-exclusion OR logic) exist *because* of specific bugs those rounds found — the "why" comments in the code below reference them so a reader six months from now doesn't accidentally undo a fix by "simplifying."

## Branch

`feature/gps-outlier-rejection`, forked from `main` @ `5998715` (this plan's own commit), worktree `.worktrees/gps-outlier-rejection` — does not exist yet, must be created before Task 1. Named for the whole G6/G9 workstream, not per-task, per this project's own past naming-mistake lesson (`feature/paco-run-m3e` absorbing unrelated work). Merges back into `main` when Task 8 is done and the owner gives explicit go-ahead — never merged automatically.

## Global Constraints

- **Hard rule 12 (`CLAUDE.md`):** any core-engine change needs a first-principles self-consistency check before being considered done, not just a plausibility check — Task 5 makes this an explicit, written deliverable, not an implicit code-review nicety.
- **G6 never replaces any part of G7.** `accuracyDegraded`'s two existing jobs (forcing `qLow`, unconditionally excluding a fix from the innovation EMA) stay byte-for-byte behaviorally unchanged. G6 only *adds*: a weight applied to the position/velocity update, and a second, OR-combined (never substituted) EMA-exclusion trigger.
- **No change to `breakChain()`'s existing reset targets** (`eastAxis`, `northAxis`, `lastElapsedRealtimeMillis`, both innovation EMAs, `accuracyBaselineMeters`) beyond adding exactly one new line (`lastAccuracyGateEngaged = false`) in Task 4. Re-convergence cost/speed after a genuine GPS outage is unaffected — G14's permanent constraint (synthetic test required before loosening a reset) does not apply here, since nothing about *when* or *how* a chain breaks changes.
- **Numeric thresholds are provisional until Task 6.** Every new tunable constant introduced in Tasks 2–4 ships with a literature-motivated starting value and an inline comment saying so — Task 6 is where real tuning happens, exactly like `qHigh`/`accuracyDegradationRatio` were tuned in this same file previously (see the existing "Round-2 fix" comments for precedent).
- **Part B (G9) has zero dependency on Part A (G6).** Task 7 can be implemented, tested, and reviewed independently of Tasks 1–6.

---

## File Structure

| File | Role | Task |
|---|---|---|
| `app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt` | Modify: `AxisKalman` peek/apply split, joint weight, EMA OR-exclusion, post-burst exemption, `breakChain()` fix | 1, 2, 3, 4 |
| `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt` | Modify: add `breakChain()` reset assertion | 4 |
| `app/src/test/java/dev/fred/pacorun/service/RobustOutlierRejectionTest.kt` | Create: all new G6-specific scenario tests | 2, 3, 4, 5 |
| `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorRealRunTest.kt` | Modify: add the Task 6 real-run comparison | 6 |
| `docs/superpowers/plans/task-6-threshold-sweep-report.md` | Create: empirical tuning report (mirrors `task-1-report.md`/`task-3-report.md` precedent) | 6 |
| `app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt` | Modify: `SpeedEstimatorDiagnostics` gains 2 fields (Task 2, 4); new `SatelliteGeometry` + `LocationFixEvent` field (Task 7) | 2, 4, 7 |
| `app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt` | Modify: serialize the new diagnostics fields (Task 2, 4); new `objectArray` builder + satellite geometry serialization (Task 7) | 2, 4, 7 |
| `app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt` | Modify: cover all of the above | 2, 4, 7 |
| `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt` | Modify: `GnssSnapshot`/`registerGnssStatusCallback()`/`onLocation()` build and pass the per-satellite list | 7 |

---

### Task 1: `AxisKalman` peek/apply split — behavior-preserving refactor

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt:53-99` (the `AxisKalman` class), `:183-192` (the two `update()` call sites in `accept()`)

**Interfaces:**
- Consumes: nothing new.
- Produces: `AxisKalman.peekSignedNormalizedInnovation(measurement, measurementVariance): Double` and `AxisKalman.apply(measurement, effectiveMeasurementVariance, rawSignedNormalizedInnovation): Unit`, replacing the old single-shot `update()`. Task 2 is the first task that actually uses the split (computing a real weight); this task wires `accept()` to call peek-then-apply with `effectiveMeasurementVariance == measurementVariance` (i.e. weight forced to 1.0), producing **identical** output to today.

**Why split now, with no new behavior yet:** isolates "did the refactor itself introduce a regression" from "is the new weight math correct" — two independently reviewable claims, not one conflated change. `AxisKalman` is a private nested class with no external callers beyond `SpeedEstimator` itself, so this is safe to restructure freely.

- [ ] **Step 1: Write a regression-lock test on a fixed 5-fix sequence**

This test exists to catch any accidental divergence *during this specific refactor* — the full existing suite (`SpeedEstimatorTest`, `SpeedEstimatorScenarioTest`, `SpeedEstimatorRealRunTest`) is the real regression net and must also stay green, but a single deterministic fixed-point check is faster to debug against if something breaks.

Add to `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt` (add near the other `@Test` methods, using the file's existing `fix(...)` helper):

```kotlin
    @Test
    fun fiveFixSequenceProducesKnownSpeedSequence() {
        // Locked-in output of the CURRENT (pre-refactor) update() on this exact input -- computed
        // by hand-simulating AxisKalman's exact recursion (predict + update, default constructor
        // constants, DEFAULT_HORIZONTAL_ACCURACY_METERS=10f since no accuracy is given) outside
        // this test, not invented. If Task 1's refactor changes this, the refactor changed
        // behavior -- stop and find out why before continuing.
        val estimator = SpeedEstimator()
        val speeds = listOf(
            estimator.accept(fix(45.0, 9.0, 0L), reliable = true),
            estimator.accept(fix(45.0001, 9.0, 1_000L), reliable = true),
            estimator.accept(fix(45.0002, 9.0, 2_000L), reliable = true),
            estimator.accept(fix(45.0003, 9.0, 3_000L), reliable = true),
            estimator.accept(fix(45.0004, 9.0, 4_000L), reliable = true),
        )
        assertEquals(0.0, speeds[0], 1e-9)
        assertEquals(4.461044704636316, speeds[1], 1e-6)
        assertEquals(13.399918853039797, speeds[2], 1e-6)
        assertEquals(22.346734105469604, speeds[3], 1e-6)
        assertEquals(28.806939552343792, speeds[4], 1e-6)
    }
```

- [ ] **Step 2: Run it against the CURRENT, unmodified code to confirm the numbers**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorTest.fiveFixSequenceProducesKnownSpeedSequence"`

Expected: PASS immediately — the values above are already the real output of today's code, not placeholders to fill in. If it fails, do not adjust the asserted numbers to match — that would defeat the point of a regression lock; instead stop and figure out why today's actual behavior differs from this independently-computed expectation before touching anything else.

- [ ] **Step 3: Refactor `AxisKalman`**

Current (`SpeedEstimator.kt:53-93`):

```kotlin
    private class AxisKalman(initialPosition: Double) {
        var position: Double = initialPosition
        var velocity: Double = 0.0
        /** Signed normalized innovation from the most recent [update] call -- see class-level comment. */
        var lastSignedNormalizedInnovation: Double = 0.0
            private set
        private var p00: Double = INITIAL_POSITION_VARIANCE
        private var p01: Double = 0.0
        private var p10: Double = 0.0
        private var p11: Double = INITIAL_VELOCITY_VARIANCE

        fun predict(dtSeconds: Double, processNoise: Double) {
            position += velocity * dtSeconds
            val q11 = processNoise * dtSeconds.pow(3) / 3.0
            val q12 = processNoise * dtSeconds.pow(2) / 2.0
            val q22 = processNoise * dtSeconds
            val newP00 = p00 + dtSeconds * (p10 + p01) + dtSeconds * dtSeconds * p11 + q11
            val newP01 = p01 + dtSeconds * p11 + q12
            val newP10 = p10 + dtSeconds * p11 + q12
            val newP11 = p11 + q22
            p00 = newP00; p01 = newP01; p10 = newP10; p11 = newP11
        }

        /** Returns the normalized innovation |residual| / sqrt(innovation variance), used to detect real accelerations. */
        fun update(measurement: Double, measurementVariance: Double): Double {
            val innovation = measurement - position
            val innovationVariance = p00 + measurementVariance
            val signedNormalizedInnovation = innovation / sqrt(innovationVariance)
            lastSignedNormalizedInnovation = signedNormalizedInnovation
            val normalizedInnovation = kotlin.math.abs(signedNormalizedInnovation)
            val k0 = p00 / innovationVariance
            val k1 = p10 / innovationVariance
            position += k0 * innovation
            velocity += k1 * innovation
            val newP00 = (1 - k0) * p00
            val newP01 = (1 - k0) * p01
            val newP10 = p10 - k1 * p00
            val newP11 = p11 - k1 * p01
            p00 = newP00; p01 = newP01; p10 = newP10; p11 = newP11
            return normalizedInnovation
        }

        private companion object {
            const val INITIAL_POSITION_VARIANCE = 100.0 // (10m std)^2 -- first fix could be anywhere nearby
            const val INITIAL_VELOCITY_VARIANCE = 25.0   // (5 m/s std)^2 -- no prior on starting speed
        }
    }
```

New:

```kotlin
    private class AxisKalman(initialPosition: Double) {
        var position: Double = initialPosition
        var velocity: Double = 0.0
        /** Signed normalized innovation from the most recent [apply] call, computed from the RAW
         * (un-inflated) measurement variance by [peekSignedNormalizedInnovation] -- see the G6
         * design doc, "Cosa succede all'EMA di innovazione". This is deliberately NOT recomputed
         * with the weight-inflated variance: the regime-switch EMA that consumes this value must
         * see the real observed signal, not a version already discounted by G6's own robustness
         * adjustment, or the two mechanisms would be entangled instead of independent. */
        var lastSignedNormalizedInnovation: Double = 0.0
            private set
        private var p00: Double = INITIAL_POSITION_VARIANCE
        private var p01: Double = 0.0
        private var p10: Double = 0.0
        private var p11: Double = INITIAL_VELOCITY_VARIANCE

        fun predict(dtSeconds: Double, processNoise: Double) {
            position += velocity * dtSeconds
            val q11 = processNoise * dtSeconds.pow(3) / 3.0
            val q12 = processNoise * dtSeconds.pow(2) / 2.0
            val q22 = processNoise * dtSeconds
            val newP00 = p00 + dtSeconds * (p10 + p01) + dtSeconds * dtSeconds * p11 + q11
            val newP01 = p01 + dtSeconds * p11 + q12
            val newP10 = p10 + dtSeconds * p11 + q12
            val newP11 = p11 + q22
            p00 = newP00; p01 = newP01; p10 = newP10; p11 = newP11
        }

        /** Raw signed normalized innovation using the given (un-inflated) [measurementVariance],
         * without mutating any state. Must be called on BOTH axes, using each axis's own current
         * [p00], before either axis's [apply] runs -- G6's joint weight needs both raw residuals
         * to compute its shared statistic. */
        fun peekSignedNormalizedInnovation(measurement: Double, measurementVariance: Double): Double {
            val innovation = measurement - position
            val innovationVariance = p00 + measurementVariance
            return innovation / sqrt(innovationVariance)
        }

        /** Applies the real Kalman update using [effectiveMeasurementVariance] -- which may already
         * be inflated by 1/weight upstream in [SpeedEstimator.accept] -- for BOTH the gain and the
         * covariance update, so the filter's own confidence always matches what it actually did
         * (hard rule 12: never scale only the state correction and leave the covariance as if the
         * fix had been fully trusted). Records [rawSignedNormalizedInnovation] (from the peek
         * step, NOT recomputed here) as [lastSignedNormalizedInnovation]. */
        fun apply(measurement: Double, effectiveMeasurementVariance: Double, rawSignedNormalizedInnovation: Double) {
            val innovation = measurement - position
            val innovationVariance = p00 + effectiveMeasurementVariance
            val k0 = p00 / innovationVariance
            val k1 = p10 / innovationVariance
            position += k0 * innovation
            velocity += k1 * innovation
            val newP00 = (1 - k0) * p00
            val newP01 = (1 - k0) * p01
            val newP10 = p10 - k1 * p00
            val newP11 = p11 - k1 * p01
            p00 = newP00; p01 = newP01; p10 = newP10; p11 = newP11
            lastSignedNormalizedInnovation = rawSignedNormalizedInnovation
        }

        private companion object {
            const val INITIAL_POSITION_VARIANCE = 100.0 // (10m std)^2 -- first fix could be anywhere nearby
            const val INITIAL_VELOCITY_VARIANCE = 25.0   // (5 m/s std)^2 -- no prior on starting speed
        }
    }
```

- [ ] **Step 4: Update the two call sites in `accept()`**

Current (`SpeedEstimator.kt:186-192`):

```kotlin
        val measurementVariance = accuracyMeters.pow(2)
        east.update(x, measurementVariance)
        north.update(y, measurementVariance)
        if (!accuracyDegraded) {
            eastInnovationEma = innovationEmaAlpha * east.lastSignedNormalizedInnovation + (1 - innovationEmaAlpha) * eastInnovationEma
            northInnovationEma = innovationEmaAlpha * north.lastSignedNormalizedInnovation + (1 - innovationEmaAlpha) * northInnovationEma
        }
```

New (weight hardcoded to 1.0 for now -- Task 2 replaces this with the real computation):

```kotlin
        val measurementVariance = accuracyMeters.pow(2)
        val eastRawInnovation = east.peekSignedNormalizedInnovation(x, measurementVariance)
        val northRawInnovation = north.peekSignedNormalizedInnovation(y, measurementVariance)
        // TODO(Task 2): replace this hardcoded 1.0 with the real joint robust weight.
        val effectiveMeasurementVariance = measurementVariance / 1.0
        east.apply(x, effectiveMeasurementVariance, eastRawInnovation)
        north.apply(y, effectiveMeasurementVariance, northRawInnovation)
        if (!accuracyDegraded) {
            eastInnovationEma = innovationEmaAlpha * east.lastSignedNormalizedInnovation + (1 - innovationEmaAlpha) * eastInnovationEma
            northInnovationEma = innovationEmaAlpha * north.lastSignedNormalizedInnovation + (1 - innovationEmaAlpha) * northInnovationEma
        }
```

- [ ] **Step 5: Run the regression-lock test and the full existing SpeedEstimator suite**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorTest" --tests "dev.fred.pacorun.service.SpeedEstimatorScenarioTest" --tests "dev.fred.pacorun.service.SpeedEstimatorRealRunTest" --tests "dev.fred.pacorun.service.SessionCoordinatorRealRunTest"`

Expected: PASS, every test, including the one from Step 1/2. Any failure means the refactor changed behavior — do not proceed to Task 2 until this is 100% green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt
git commit -m "refactor: split AxisKalman.update into peek/apply, no behavior change"
```

---

### Task 2: joint robust weight — detect and dampen an anomalous fix

**Files:**
- Modify: `SpeedEstimator.kt` (constructor params, `accept()`, new private `robustWeight` function, new diagnostic fields/getters)
- Modify: `app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt` (`SpeedEstimatorDiagnostics` gains `robustWeight: Double`)
- Modify: `app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt` (serialize the new field)
- Modify: `app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt`
- Create: `app/src/test/java/dev/fred/pacorun/service/RobustOutlierRejectionTest.kt`

**Interfaces:**
- Consumes: `AxisKalman.peekSignedNormalizedInnovation`/`apply` from Task 1.
- Produces: `SpeedEstimator.getLastRobustWeight(): Double`, used by Task 3 (EMA exclusion) and exposed in `SpeedEstimatorDiagnostics.robustWeight` for future log analysis (same rationale as the existing `accuracyGateEngaged` diagnostic).

This is validation item 1 (the specific gap: good declared accuracy, anomalous position) and item 2 (non-regression on normal noise) from the spec.

- [ ] **Step 1: Write the failing test — the specific gap G6 closes**

Create `app/src/test/java/dev/fred/pacorun/service/RobustOutlierRejectionTest.kt`:

```kotlin
package dev.fred.pacorun.service

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
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.RobustOutlierRejectionTest"`
Expected: FAIL — `getLastRobustWeight()` doesn't exist yet, compile error.

- [ ] **Step 3: Add the constructor parameters and the weight function**

In `SpeedEstimator.kt`, add to the primary constructor (after `accuracyBaselineAlpha`, before `onEvent`):

```kotlin
    // G6 (robust outlier rejection): joint (2-axis, chi-squared-2-consistent) weight thresholds
    // on the Mahalanobis distance d = sqrt(eastNormInnovation^2 + northNormInnovation^2).
    // PROVISIONAL values, not yet empirically tuned -- see Task 6's sweep report. Starting points
    // are literature chi-squared(2 dof) critical values: sqrt(5.99) (95th pct, "clearly normal
    // below this") and sqrt(13.8) (99.9th pct, "fully reject above this").
    private val robustWeightLowThresholdSigma: Double = 2.45,
    private val robustWeightHighThresholdSigma: Double = 3.71,
    // Provisional -- Task 6 tunes this alongside the two thresholds above. A fix is excluded from
    // the innovation EMA if accuracyDegraded OR its robust weight falls below this (see Task 3).
    private val emaExclusionWeightThreshold: Double = 0.5,
```

Add near the other `internal fun getLast...()` accessors:

```kotlin
    private var lastRobustWeight: Double = 1.0
    internal fun getLastRobustWeight(): Double = lastRobustWeight
```

Add a private function (near the bottom of the class, before the `private companion object`):

```kotlin
    /** Redescending robust weight on the joint (2-axis) squared Mahalanobis distance [jointD2]:
     * 1.0 below [robustWeightLowThresholdSigma], linearly down to 0.0 at
     * [robustWeightHighThresholdSigma], 0.0 beyond it. Piecewise-linear rather than a classic
     * Huber (k/d) specifically so a genuinely extreme outlier gets FULLY rejected, not just
     * asymptotically down-weighted -- Huber's weight is never exactly zero for any finite d,
     * which the spec's design explicitly flagged as insufficient protection against a large
     * multipath spike. */
    private fun robustWeight(jointD2: Double): Double {
        val d = sqrt(jointD2)
        return when {
            d <= robustWeightLowThresholdSigma -> 1.0
            d >= robustWeightHighThresholdSigma -> 0.0
            else -> (robustWeightHighThresholdSigma - d) / (robustWeightHighThresholdSigma - robustWeightLowThresholdSigma)
        }
    }
```

- [ ] **Step 4: Wire it into `accept()`**

Current (from Task 1's Step 4):

```kotlin
        val measurementVariance = accuracyMeters.pow(2)
        val eastRawInnovation = east.peekSignedNormalizedInnovation(x, measurementVariance)
        val northRawInnovation = north.peekSignedNormalizedInnovation(y, measurementVariance)
        // TODO(Task 2): replace this hardcoded 1.0 with the real joint robust weight.
        val effectiveMeasurementVariance = measurementVariance / 1.0
        east.apply(x, effectiveMeasurementVariance, eastRawInnovation)
        north.apply(y, effectiveMeasurementVariance, northRawInnovation)
```

New:

```kotlin
        val measurementVariance = accuracyMeters.pow(2)
        val eastRawInnovation = east.peekSignedNormalizedInnovation(x, measurementVariance)
        val northRawInnovation = north.peekSignedNormalizedInnovation(y, measurementVariance)
        val jointD2 = eastRawInnovation.pow(2) + northRawInnovation.pow(2)
        // Named jointRobustWeight, not robustWeight, specifically to avoid shadowing the
        // robustWeight(jointD2) FUNCTION with a local val of the same simple name -- confusing to
        // read even where Kotlin would resolve it unambiguously.
        val jointRobustWeight = robustWeight(jointD2)
        lastRobustWeight = jointRobustWeight
        val effectiveMeasurementVariance = measurementVariance / jointRobustWeight
        east.apply(x, effectiveMeasurementVariance, eastRawInnovation)
        north.apply(y, effectiveMeasurementVariance, northRawInnovation)
```

(Task 3 will change the `if (!accuracyDegraded)` line right after this; leave it untouched for now.)

- [ ] **Step 5: Run the new tests**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.RobustOutlierRejectionTest"`
Expected: PASS, both tests.

- [ ] **Step 6: Run the full regression suite again**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorTest" --tests "dev.fred.pacorun.service.SpeedEstimatorScenarioTest" --tests "dev.fred.pacorun.service.SpeedEstimatorRealRunTest" --tests "dev.fred.pacorun.service.SessionCoordinatorRealRunTest"`
Expected: PASS. The steady-state scenario test in particular must still clear its existing 90% bar — ordinary noise should almost never trip the weight below 1.0 given the provisional thresholds (roughly the 95th percentile of normal-fix residuals).

- [ ] **Step 7: Expose the new diagnostic in the event log**

`SessionEvent.kt` — modify `SpeedEstimatorDiagnostics` (append last, with a default, so no existing call site breaks):

Current:

```kotlin
data class SpeedEstimatorDiagnostics(
    val processNoiseRegime: ProcessNoiseRegime,
    val accuracyGateEngaged: Boolean,
    val innovationEmaMagnitude: Double,
)
```

New:

```kotlin
data class SpeedEstimatorDiagnostics(
    val processNoiseRegime: ProcessNoiseRegime,
    val accuracyGateEngaged: Boolean,
    val innovationEmaMagnitude: Double,
    // Appended LAST with a default, same reasoning as PaceStateConfirmedEvent.rawSpeedKmh: keeps
    // every existing positional/named call site compiling without a shift.
    val robustWeight: Double = 1.0,
)
```

`SessionEventWriter.kt` — modify `speedEstimatorDiagnostics`:

Current:

```kotlin
    private fun speedEstimatorDiagnostics(diagnostics: SpeedEstimatorDiagnostics) = json {
        enum("processNoiseRegime", diagnostics.processNoiseRegime)
        boolean("accuracyGateEngaged", diagnostics.accuracyGateEngaged)
        double("innovationEmaMagnitude", diagnostics.innovationEmaMagnitude)
    }
```

New:

```kotlin
    private fun speedEstimatorDiagnostics(diagnostics: SpeedEstimatorDiagnostics) = json {
        enum("processNoiseRegime", diagnostics.processNoiseRegime)
        boolean("accuracyGateEngaged", diagnostics.accuracyGateEngaged)
        double("innovationEmaMagnitude", diagnostics.innovationEmaMagnitude)
        double("robustWeight", diagnostics.robustWeight)
    }
```

`RunSessionService.kt` — modify the `SpeedEstimatorDiagnostics(...)` construction inside `onLocation()` (around line 357):

Current:

```kotlin
                    SpeedEstimatorDiagnostics(
                        processNoiseRegime = estimator.getLastProcessNoiseRegime(),
                        accuracyGateEngaged = estimator.getLastAccuracyGateEngaged(),
                        innovationEmaMagnitude = estimator.getLastInnovationEma(),
                    )
```

New:

```kotlin
                    SpeedEstimatorDiagnostics(
                        processNoiseRegime = estimator.getLastProcessNoiseRegime(),
                        accuracyGateEngaged = estimator.getLastAccuracyGateEngaged(),
                        innovationEmaMagnitude = estimator.getLastInnovationEma(),
                        robustWeight = estimator.getLastRobustWeight(),
                    )
```

- [ ] **Step 8: Add a writer test**

Add to `SessionEventWriterTest.kt` (following the file's existing `locationFixEventSerializes...` pattern — use named args, copy the existing nested-diagnostics test's surrounding fields and add `robustWeight`):

```kotlin
    @Test
    fun speedEstimatorDiagnosticsSerializesRobustWeight() {
        val event = LocationFixEvent(
            elapsedRealtimeMillis = 5_000L,
            sequenceNumber = 6,
            elapsedSinceStartMillis = 5_000L,
            provider = "gps",
            reliable = true,
            rejectionReasons = emptyList(),
            satellitesUsedInFix = null,
            satelliteCn0MinDbHz = null,
            satelliteCn0AvgDbHz = null,
            satelliteCn0MaxDbHz = null,
            qualityTier = QualityTier.BUONA,
            speedEstimatorDiagnostics = SpeedEstimatorDiagnostics(
                processNoiseRegime = ProcessNoiseRegime.Q_LOW,
                accuracyGateEngaged = false,
                innovationEmaMagnitude = 0.1,
                robustWeight = 0.42,
            ),
        )

        assertTrue(SessionEventWriter.toJsonLine(event).contains("\"robustWeight\":0.42"))
    }
```

(Add `import org.junit.Assert.assertTrue` at the top of the file if not already present — check first.)

- [ ] **Step 9: Run all affected tests**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.eventlog.SessionEventWriterTest" --tests "dev.fred.pacorun.service.RobustOutlierRejectionTest"`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt \
  app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt \
  app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt \
  app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt \
  app/src/test/java/dev/fred/pacorun/service/RobustOutlierRejectionTest.kt \
  app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt
git commit -m "feat: add G6 joint robust weight on Kalman update (provisional thresholds)"
```

---

### Task 3: G7 stays intact — OR-combined EMA exclusion, not a replacement

**Files:**
- Modify: `SpeedEstimator.kt` (the EMA-update conditional)
- Modify: `RobustOutlierRejectionTest.kt`

**Interfaces:**
- Consumes: `jointRobustWeight`/`accuracyDegraded` from `accept()`'s existing scope, `emaExclusionWeightThreshold` from Task 2.
- Produces: nothing new consumed elsewhere; this task only changes an internal decision inside `accept()`.

This is validation items 3 (non-regression during a degraded burst) and 4 (non-contamination after a burst ends) from the spec — the exact class of mistake found and fixed twice during the spec's own review, so the tests here specifically target "did G6 accidentally weaken G7," not just "does G6 work."

- [ ] **Step 1: Write the failing tests**

Add to `RobustOutlierRejectionTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run to verify current (pre-fix) behavior**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.RobustOutlierRejectionTest"`

Expected: `regimeStaysQLowDuringDegradedBurstEvenWithG6Active` should already PASS (Task 2 didn't touch the `processNoise` computation at all). `emaNotContaminatedImmediatelyAfterBurstEnds` should also already PASS at this point, since Task 2 didn't touch the EMA-exclusion line either (`if (!accuracyDegraded)` is unchanged from before this plan started) — this step exists to confirm the baseline is already correct before Task 3's change, so Step 5 below is a true non-regression check, not a first-time pass.

- [ ] **Step 3: Add the OR-combined exclusion**

Current (`SpeedEstimator.kt`, right after Task 2's Step 4 edit):

```kotlin
        if (!accuracyDegraded) {
            eastInnovationEma = innovationEmaAlpha * east.lastSignedNormalizedInnovation + (1 - innovationEmaAlpha) * eastInnovationEma
            northInnovationEma = innovationEmaAlpha * north.lastSignedNormalizedInnovation + (1 - innovationEmaAlpha) * northInnovationEma
        }
```

New:

```kotlin
        // G6 adds a SECOND, OR-combined exclusion trigger -- it never replaces accuracyDegraded's
        // own unconditional exclusion. Two rounds of adversarial review on the design doc found
        // that treating this as a substitution (either "G6 replaces G7 entirely" or "G6's weight
        // replaces just this exclusion") would stop excluding exactly the degraded-but-not-
        // residual-anomalous fixes this unconditional check exists to catch. Both criteria must
        // stay independently sufficient to trigger exclusion.
        val excludedFromEma = accuracyDegraded || jointRobustWeight < emaExclusionWeightThreshold
        if (!excludedFromEma) {
            eastInnovationEma = innovationEmaAlpha * east.lastSignedNormalizedInnovation + (1 - innovationEmaAlpha) * eastInnovationEma
            northInnovationEma = innovationEmaAlpha * north.lastSignedNormalizedInnovation + (1 - innovationEmaAlpha) * northInnovationEma
        }
```

- [ ] **Step 4: Run the tests again**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.RobustOutlierRejectionTest"`
Expected: PASS, both new tests, plus the two from Task 2.

- [ ] **Step 5: Run the full regression suite**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorTest" --tests "dev.fred.pacorun.service.SpeedEstimatorScenarioTest" --tests "dev.fred.pacorun.service.SpeedEstimatorRealRunTest" --tests "dev.fred.pacorun.service.SessionCoordinatorRealRunTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt app/src/test/java/dev/fred/pacorun/service/RobustOutlierRejectionTest.kt
git commit -m "feat: OR-combine G6's EMA exclusion with G7's, never replace it"
```

---

### Task 4: post-burst exemption rule + `breakChain()` fix

**Files:**
- Modify: `SpeedEstimator.kt` (capture the previous gate state, exemption logic, `breakChain()`, new diagnostic)
- Modify: `SessionEvent.kt`, `SessionEventWriter.kt`, `RunSessionService.kt` (expose `postBurstExemptionApplied`)
- Modify: `RobustOutlierRejectionTest.kt`, `SpeedEstimatorTest.kt`, `SessionEventWriterTest.kt`

**Interfaces:**
- Consumes: `lastAccuracyGateEngaged` (existing field), `accuracyDegraded` (existing local in `accept()`).
- Produces: `SpeedEstimator.getLastPostBurstExemptionApplied(): Boolean`.

This implements the structural fix for validation item 7 (legitimate catch-up after a burst must not be treated as an anomaly) and closes the specific implementation trap found in the spec's fourth adversarial round: read `lastAccuracyGateEngaged`'s *previous* value into a local **before** the line that overwrites it, or the exemption silently never fires.

- [ ] **Step 1: Write the failing tests**

Add to `RobustOutlierRejectionTest.kt`:

```kotlin
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
```

`SpeedEstimatorTest.kt` already has a `fix(latitude, longitude, elapsedMillis, horizontalAccuracyMeters)` helper, but it's a `private` member of that class — not visible from `RobustOutlierRejectionTest.kt`. Add an equivalent local helper to `RobustOutlierRejectionTest.kt` instead of changing the other file's visibility (near the top of the class, alongside `withInjectedOutlier`/`withDegradedAccuracyBurst`):

```kotlin
    private fun fix(latitude: Double, longitude: Double, elapsedMillis: Long, accuracyMeters: Float = 3.0f) =
        LocationPosition(latitude, longitude, elapsedMillis, accuracyMeters)
```

Add `import org.junit.Assert.assertFalse` to `RobustOutlierRejectionTest.kt` if not already present.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.RobustOutlierRejectionTest"`
Expected: FAIL — `getLastPostBurstExemptionApplied()` doesn't exist yet, compile error.

- [ ] **Step 3: Add the capture, the exemption, and the new diagnostic field**

Current (`SpeedEstimator.kt`, the `accuracyDegraded`/`lastAccuracyGateEngaged` lines):

```kotlin
        val accuracyBaseline = accuracyBaselineMeters ?: accuracyMeters
        val accuracyDegraded = accuracyMeters > accuracyBaseline * accuracyDegradationRatio
        lastAccuracyGateEngaged = accuracyDegraded
```

New — the capture MUST happen between computing `accuracyDegraded` and overwriting `lastAccuracyGateEngaged`, not anywhere later in the function (the residual/weight isn't even computed yet at this point, but that's fine: `isFirstFixAfterBurst` only needs the two booleans, nothing else):

```kotlin
        val accuracyBaseline = accuracyBaselineMeters ?: accuracyMeters
        val accuracyDegraded = accuracyMeters > accuracyBaseline * accuracyDegradationRatio
        // G6 post-burst exemption: MUST capture the PREVIOUS fix's value before the next line
        // overwrites it. Reading lastAccuracyGateEngaged any later in this function (e.g. near
        // where the robust weight is computed) would read the value THIS line already set to the
        // CURRENT fix's accuracyDegraded -- making the exemption check permanently
        // `accuracyDegraded && !accuracyDegraded`, always false, a silent no-op. Found in the
        // design doc's fourth adversarial review round.
        val previousAccuracyGateEngaged = lastAccuracyGateEngaged
        lastAccuracyGateEngaged = accuracyDegraded
        val isFirstFixAfterBurst = previousAccuracyGateEngaged && !accuracyDegraded
```

Current (from Task 2's Step 4):

```kotlin
        val jointD2 = eastRawInnovation.pow(2) + northRawInnovation.pow(2)
        val jointRobustWeight = robustWeight(jointD2)
        lastRobustWeight = jointRobustWeight
```

New:

```kotlin
        val jointD2 = eastRawInnovation.pow(2) + northRawInnovation.pow(2)
        // Structural exemption, not a tuned threshold: the fix right after a resolved degraded-
        // accuracy burst can show a large, LEGITIMATE catch-up residual (the filter under-reacted
        // on purpose during the burst) that is indistinguishable from a genuine spatial outlier by
        // magnitude alone. We know deterministically when this is the case, so we skip the
        // magnitude test entirely for this one fix rather than guessing a wider threshold.
        val jointRobustWeight = if (isFirstFixAfterBurst) 1.0 else robustWeight(jointD2)
        lastRobustWeight = jointRobustWeight
        lastPostBurstExemptionApplied = isFirstFixAfterBurst
```

Add the new field/getter near the others:

```kotlin
    private var lastPostBurstExemptionApplied: Boolean = false
    internal fun getLastPostBurstExemptionApplied(): Boolean = lastPostBurstExemptionApplied
```

- [ ] **Step 4: Fix `breakChain()`**

Current:

```kotlin
    fun breakChain(): Double {
        eastAxis = null
        northAxis = null
        lastElapsedRealtimeMillis = null
        eastInnovationEma = 0.0
        northInnovationEma = 0.0
        accuracyBaselineMeters = null
        lastAcceptWasMeasurement = false
        return 0.0
    }
```

New:

```kotlin
    fun breakChain(): Double {
        eastAxis = null
        northAxis = null
        lastElapsedRealtimeMillis = null
        eastInnovationEma = 0.0
        northInnovationEma = 0.0
        accuracyBaselineMeters = null
        // A fresh chain must never inherit "just recovered from a burst" state from an unrelated
        // prior chain -- see the post-burst exemption's comment above accept(). This field was
        // previously write-only (only read by the internal test/diagnostics accessor), so this
        // reset was never needed before G6 added an actual internal read of it.
        lastAccuracyGateEngaged = false
        lastAcceptWasMeasurement = false
        return 0.0
    }
```

- [ ] **Step 5: Expose the new diagnostic in the event log**

`SessionEvent.kt` — append to `SpeedEstimatorDiagnostics` again (same pattern as Task 2's `robustWeight`):

```kotlin
data class SpeedEstimatorDiagnostics(
    val processNoiseRegime: ProcessNoiseRegime,
    val accuracyGateEngaged: Boolean,
    val innovationEmaMagnitude: Double,
    val robustWeight: Double = 1.0,
    val postBurstExemptionApplied: Boolean = false,
)
```

`SessionEventWriter.kt`:

```kotlin
    private fun speedEstimatorDiagnostics(diagnostics: SpeedEstimatorDiagnostics) = json {
        enum("processNoiseRegime", diagnostics.processNoiseRegime)
        boolean("accuracyGateEngaged", diagnostics.accuracyGateEngaged)
        double("innovationEmaMagnitude", diagnostics.innovationEmaMagnitude)
        double("robustWeight", diagnostics.robustWeight)
        boolean("postBurstExemptionApplied", diagnostics.postBurstExemptionApplied)
    }
```

`RunSessionService.kt`, the same construction site as Task 2's Step 7:

```kotlin
                    SpeedEstimatorDiagnostics(
                        processNoiseRegime = estimator.getLastProcessNoiseRegime(),
                        accuracyGateEngaged = estimator.getLastAccuracyGateEngaged(),
                        innovationEmaMagnitude = estimator.getLastInnovationEma(),
                        robustWeight = estimator.getLastRobustWeight(),
                        postBurstExemptionApplied = estimator.getLastPostBurstExemptionApplied(),
                    )
```

- [ ] **Step 6: Add a `breakChain()` reset test**

Add to `SpeedEstimatorTest.kt` (near the other `breakChain`/reset tests):

```kotlin
    @Test
    fun breakChainResetsAccuracyGateEngagedFlag() {
        val estimator = SpeedEstimator()
        estimator.accept(fix(45.0, 9.0, 0L), reliable = true)
        estimator.accept(fix(0.0, 0.0, 1_000L), reliable = false) // forces breakChain() via the unreliable path
        assertFalse(estimator.getLastAccuracyGateEngaged())
    }
```

(Add `import org.junit.Assert.assertFalse` if not already present in this file.)

- [ ] **Step 7: Run the new and existing tests**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.RobustOutlierRejectionTest" --tests "dev.fred.pacorun.service.SpeedEstimatorTest" --tests "dev.fred.pacorun.eventlog.SessionEventWriterTest"`
Expected: PASS, everything, including both new tests from Step 1 — `secondFixOfNewChainAfterBreakChainIsNotWronglyExempted` in particular is the direct regression test for the bug this task fixes.

- [ ] **Step 8: Run the full regression suite**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorScenarioTest" --tests "dev.fred.pacorun.service.SpeedEstimatorRealRunTest" --tests "dev.fred.pacorun.service.SessionCoordinatorRealRunTest"`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt \
  app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt \
  app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt \
  app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt \
  app/src/test/java/dev/fred/pacorun/service/RobustOutlierRejectionTest.kt \
  app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt
git commit -m "feat: exempt the first post-burst fix from G6, fix breakChain() gate reset"
```

---

### Task 5: written self-consistency verification + two characterization tests

**Files:**
- Create: `docs/superpowers/plans/task-5-self-consistency-report.md`
- Modify: `RobustOutlierRejectionTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1-4.
- Produces: a written artifact satisfying hard rule 12's "confirm in writing" requirement, plus two tests that measure (don't gate on) two accepted trade-offs the design doc names explicitly.

This is validation items 6, 8, and 9 from the spec.

- [ ] **Step 1: Write the self-consistency report**

Create `docs/superpowers/plans/task-5-self-consistency-report.md`:

```markdown
# Task 5 — G6 self-consistency verification (hard rule 12)

Confirms, by direct inspection of the implemented code (not by argument alone), that
`AxisKalman.apply()` never lets the filter's covariance state imply more confidence than
the actual state correction justifies.

## The invariant

For every call to `apply(measurement, effectiveMeasurementVariance, rawSignedNormalizedInnovation)`:

1. `innovationVariance = p00 + effectiveMeasurementVariance` — the SAME `effectiveMeasurementVariance`
   passed in by the caller, never a different value recomputed partway through.
2. `k0 = p00 / innovationVariance`, `k1 = p10 / innovationVariance` — both gains derived from that
   one `innovationVariance`.
3. `position`/`velocity` are corrected using `k0`/`k1`.
4. `p00`/`p01`/`p10`/`p11` are updated using the SAME `k0`/`k1` from step 2 — not a second,
   independently-computed pair.

There is exactly one place in `apply()` where `effectiveMeasurementVariance` is consumed (the
`innovationVariance` line), and every one of `k0`, `k1`, and the four covariance updates derives
from that single `innovationVariance` value. No code path scales `position += k0 * innovation` /
`velocity += k1 * innovation` by anything other than the `k0`/`k1` also used for the covariance
update in the same call. Confirmed by reading `SpeedEstimator.kt`'s `AxisKalman.apply()` top to
bottom, current as of this task's commit — see the exact line range in the file's own history for
this task.

## What this rules out

The specific failure mode hard rule 12 exists to prevent: scaling `k0 * innovation` /
`k1 * innovation` by a weight directly, while leaving the covariance update computed from the
UN-weighted `k0`/`k1` — which would let the filter believe it had fully trusted a fix it had
actually mostly ignored. That pattern does not appear anywhere in this implementation.

## What this does NOT verify

This confirms internal self-consistency (the covariance always matches what the correction actually
did), not correctness of the weight VALUES themselves (that's Task 6's empirical tuning) or of the
post-burst exemption's own correctness (verified separately in Task 4's tests, specifically the
regression test for the read-after-overwrite trap).
```

- [ ] **Step 2: Write the oscillating-accuracy characterization test**

Add to `RobustOutlierRejectionTest.kt`:

```kotlin
    @Test
    fun oscillatingAccuracyExemptionFrequencyIsCharacterized() {
        // Period anchored to the baseline EMA's own time constant (accuracyBaselineAlpha = 0.05,
        // i.e. ~20 fixes) -- not fix-per-fix noise, which the baseline design already filters out
        // by construction. This test does not assert a pass/fail bound (the spec explicitly frames
        // this as characterization, not validation) -- it prints the real measured frequency so a
        // human can judge whether it's acceptable, and future runs can compare against it.
        val fixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(200.0 to 8.0),
            noiseStdMeters = 3.0,
            seed = 3L,
        )
        val oscillatingAccuracy = fixes.mapIndexed { i, fix ->
            val accuracy = if ((i / 20) % 2 == 0) 3.0f else 25.0f
            fix.copy(position = fix.position.copy(horizontalAccuracyMeters = accuracy))
        }

        val estimator = SpeedEstimator()
        var exemptionCount = 0
        oscillatingAccuracy.forEach {
            estimator.accept(it.position, reliable = true)
            if (estimator.getLastPostBurstExemptionApplied()) exemptionCount++
        }

        println(
            "oscillatingAccuracyExemptionFrequencyIsCharacterized: $exemptionCount exemptions " +
                "out of ${oscillatingAccuracy.size} fixes (period ~20 fixes, matching the baseline " +
                "EMA's own time constant)",
        )
        // No assertion on the count itself -- see comment above. The test still needs one
        // assertion to be a real test, not just a print statement: confirm it ran to completion
        // without throwing (e.g. a division or state issue under rapid oscillation).
        assertTrue(exemptionCount >= 0)
    }

    @Test
    fun costOfPostBurstExemptionOnAnIndependentAnomalyIsCharacterized() {
        // Injects a genuine, burst-unrelated spatial outlier exactly at the fix the exemption rule
        // would otherwise protect -- measuring (not fixing) the accepted trade-off named in the
        // design doc: this fix gets zero G6 scrutiny even if it happens to independently be a real
        // outlier, because degraded-accuracy conditions correlate with genuine multipath risk.
        val fixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(30.0 to 8.0),
            noiseStdMeters = 3.0,
            seed = 9L,
        )
        val withBurst = withDegradedAccuracyBurst(fixes, degradedRange = 10..19, normalAccuracyMeters = 3.0f, degradedAccuracyMeters = 25.0f)
        // Independent 20m outlier exactly at index 20, the first (exempt) post-burst fix.
        val withBurstAndOutlier = withInjectedOutlier(withBurst, index = 20, offsetMeters = 20.0)

        val estimator = SpeedEstimator()
        var speedAtExemptOutlier = 0.0
        withBurstAndOutlier.forEachIndexed { i, fix ->
            val speed = estimator.accept(fix.position, reliable = true)
            if (i == 20) speedAtExemptOutlier = speed
        }

        println(
            "costOfPostBurstExemptionOnAnIndependentAnomalyIsCharacterized: speed at the exempt " +
                "fix with an independent 20m outlier injected was $speedAtExemptOutlier km/h " +
                "(true speed 8 km/h) -- exemption applied: ${estimator.getLastPostBurstExemptionApplied()}",
        )
        assertTrue(estimator.getLastPostBurstExemptionApplied())
    }
```

- [ ] **Step 3: Run the new tests, read the printed numbers**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.RobustOutlierRejectionTest" --info`

Expected: PASS. Read the two `println` outputs in the test log — copy both numbers into `task-5-self-consistency-report.md` under a new "## Characterization results" section before committing, so the report captures the real measured behavior, not just the test code that produced it.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/plans/task-5-self-consistency-report.md app/src/test/java/dev/fred/pacorun/service/RobustOutlierRejectionTest.kt
git commit -m "docs: self-consistency verification + oscillation/independent-outlier characterization"
```

---

### Task 6: empirical threshold tuning + real-run comparison

**Files:**
- Modify: `SpeedEstimator.kt` (replace the three provisional constants with tuned values)
- Modify: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorRealRunTest.kt`
- Create: `docs/superpowers/plans/task-6-threshold-sweep-report.md`

**Interfaces:**
- Consumes: `RobustOutlierRejectionTest`'s scenarios from Tasks 2-5 as the sweep's test bed.
- Produces: final values for `robustWeightLowThresholdSigma`, `robustWeightHighThresholdSigma`, `emaExclusionWeightThreshold`.

This is validation item 5 from the spec (the real-run comparison), plus finalizing the thresholds left provisional since Task 2. Follow the same empirical discipline already used for `qHigh`/`accuracyDegradationRatio` in this file (see the existing "Round-2 fix" comments): sweep candidate values against synthetic scenarios that specifically target the trade-off, pick the value with the best margin, document why.

- [ ] **Step 1: Sweep the two weight thresholds against the existing scenarios**

Write a short, throwaway local script or a temporary parameterized test (not committed — this step is investigation, its OUTPUT is what gets committed) that runs `goodAccuracyButAnomalousPositionIsDampened` and `robustWeightStaysNearOneOnOrdinaryNoise` from Task 2, across a grid of `robustWeightLowThresholdSigma` ∈ {2.0, 2.45, 3.0} × `robustWeightHighThresholdSigma` ∈ {3.0, 3.71, 4.5}, for each combination checking: (a) does the injected 25m outlier's speed stay within the same 5 km/h band, and by how much margin; (b) does the mean weight on ordinary noise stay above 0.9, and by how much margin. Record the numbers for all 9 combinations.

- [ ] **Step 2: Sweep the EMA-exclusion threshold against the burst scenarios**

Same approach against `regimeStaysQLowDuringDegradedBurstEvenWithG6Active` and `emaNotContaminatedImmediatelyAfterBurstEnds` from Task 3, across `emaExclusionWeightThreshold` ∈ {0.3, 0.5, 0.7}.

- [ ] **Step 3: Pick final values and update the constants**

In `SpeedEstimator.kt`, replace the provisional values and comments with the tuned ones and the reasoning, following the exact style of the existing `qHigh`/`innovationThreshold` comments (empirical sweep description, what was tried and rejected, why the chosen value has real margin, reference to the report file):

```kotlin
    // Empirically tuned (see task-6-threshold-sweep-report.md for the full grid): [FINAL VALUE]
    // gives [X]km/h of margin on the injected-outlier test while keeping ordinary-noise mean
    // weight at [Y] (vs. requiring >0.9) -- the widest combination tested that still fully
    // protects every seed of the injected-outlier scenario.
    private val robustWeightLowThresholdSigma: Double = /* real value from Step 1 */,
    private val robustWeightHighThresholdSigma: Double = /* real value from Step 1 */,
    private val emaExclusionWeightThreshold: Double = /* real value from Step 2 */,
```

- [ ] **Step 4: Run every test touched by this plan so far**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.*" --tests "dev.fred.pacorun.eventlog.SessionEventWriterTest"`
Expected: PASS, all of it, with the final tuned constants.

- [ ] **Step 5: Real-run comparison**

**Correction to this plan's own earlier draft, found while writing this task (not caught by the design doc's five review rounds, which never checked this specific citation):** the spec's 0.242 km/h MAE baseline belongs to `paco-run-2026-09-10-1122.gpx` (5195 fixes) — the SAME fixture `SpeedEstimatorRealRunTest.kt` already loads via its existing `loadPacoRunGpxFixture(): List<Pair<LocationPosition, Boolean>>` helper — not `paco-run-2026-09-15-110804-events.jsonl` (a different, later run used for unrelated P1/P2 validation). Also: that 0.242 km/h number was originally computed by an external, never-committed Python script comparing against a Strava `.fit` file — there is no Strava-comparison harness in this Kotlin test suite to reuse, and building one is out of scope for this task. What IS achievable and sufficient here: replay the SAME existing fixture through the G6-equipped estimator, and separately through an estimator with G6 structurally disabled (via extreme constructor thresholds, not a new code path), and confirm the two speed sequences are nearly identical — this run has no known G6-relevant anomaly, so a large divergence would indicate an unintended regression, while near-identical output is exactly the honest, expected result.

Add to `SpeedEstimatorRealRunTest.kt`:

```kotlin
    @Test
    fun g6ProducesNoRegressionOnTheRecordedRealRun() {
        val fixes = loadPacoRunGpxFixture()
        assertTrue("fixture failed to load any fixes", fixes.isNotEmpty())

        val withG6 = SpeedEstimator()
        val speedsWithG6 = fixes.map { (position, reliable) -> withG6.accept(position, reliable) }

        // G6 structurally disabled: a low threshold this high means every fix's joint distance is
        // always <= it, so robustWeight(jointD2) always returns 1.0 -- equivalent to this run
        // never having G6 at all, without a separate code path to maintain.
        val withoutG6 = SpeedEstimator(robustWeightLowThresholdSigma = Double.MAX_VALUE)
        val speedsWithoutG6 = fixes.map { (position, reliable) -> withoutG6.accept(position, reliable) }

        val maxAbsoluteDifferenceKmh = speedsWithG6.zip(speedsWithoutG6)
            .maxOf { (withWeight, withoutWeight) -> abs(withWeight - withoutWeight) }
        println(
            "g6ProducesNoRegressionOnTheRecordedRealRun: max per-fix divergence between G6-on and " +
                "G6-off replays of the same real run = $maxAbsoluteDifferenceKmh km/h " +
                "(original Strava-validated baseline for this run, established separately and " +
                "unaffected by this comparison: MAE 0.242 km/h)",
        )
        // This run has no known G6-relevant anomaly (see the design doc's own caveat) -- expect
        // near-zero divergence, not a specific small-but-nonzero number invented here.
        assertTrue(
            "expected G6 to have negligible effect on this anomaly-free run, max divergence was $maxAbsoluteDifferenceKmh km/h",
            maxAbsoluteDifferenceKmh < 0.5,
        )
    }
```

Add `import kotlin.math.abs` to `SpeedEstimatorRealRunTest.kt` if not already present.

- [ ] **Step 6: Run it and record the honest result**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorRealRunTest"`

Expected: PASS, all tests in the file, including the two pre-existing ones. Copy the printed max-divergence value into `task-6-threshold-sweep-report.md`'s final section, alongside a note that the original 0.242 km/h Strava-comparison baseline for this run was established separately (external script, 2026-09-10) and is not being recomputed here — this task only confirms G6 doesn't disturb that already-validated behavior.

- [ ] **Step 7: Write the sweep report**

Create `docs/superpowers/plans/task-6-threshold-sweep-report.md` with the full grid from Steps 1-2, the chosen values and why, and the real-run MAE result from Step 6.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt \
  app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorRealRunTest.kt \
  docs/superpowers/plans/task-6-threshold-sweep-report.md
git commit -m "tune: finalize G6 thresholds from empirical sweep + real-run comparison"
```

---

### Task 7: G9 — per-satellite geometry logging (independent of Tasks 1-6)

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt` (new `SatelliteGeometry`, `LocationFixEvent` field)
- Modify: `app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt` (new `objectArray` builder, serialization)
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt` (`GnssSnapshot`, `registerGnssStatusCallback()`, `onLocation()`)
- Modify: `app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt`

**Interfaces:**
- Consumes: nothing from Tasks 1-6.
- Produces: `LocationFixEvent.satelliteGeometry: List<SatelliteGeometry>`, a genuine per-satellite record (not an aggregate) — the only form usable for a future DOP computation, per the design doc's own correction on this exact point.

- [ ] **Step 1: Add the data class and extend `LocationFixEvent`**

`SessionEvent.kt` — add near `SpeedEstimatorDiagnostics`:

```kotlin
data class SatelliteGeometry(
    val svid: Int,
    val constellationType: Int,
    val elevationDegrees: Float,
    val azimuthDegrees: Float,
    val usedInFix: Boolean,
)
```

Modify `LocationFixEvent` (append last, with a default, same reasoning as every other field appended this way in this file):

```kotlin
data class LocationFixEvent(
    override val elapsedRealtimeMillis: Long,
    val sequenceNumber: Int,
    val elapsedSinceStartMillis: Long,
    val provider: String?,
    val reliable: Boolean,
    val rejectionReasons: List<RejectionReason>,
    val satellitesUsedInFix: Int?,
    val satelliteCn0MinDbHz: Float?,
    val satelliteCn0AvgDbHz: Float?,
    val satelliteCn0MaxDbHz: Float?,
    val qualityTier: QualityTier,
    val speedEstimatorDiagnostics: SpeedEstimatorDiagnostics?,
    val satelliteGeometry: List<SatelliteGeometry> = emptyList(),
) : SessionEvent
```

- [ ] **Step 2: Add the `objectArray` builder and serialize the new field**

`SessionEventWriter.kt` — add to `JsonObjectBuilder` (near `stringArray`):

```kotlin
    fun <T> objectArray(name: String, values: List<T>, toJson: (T) -> String) =
        raw(name, values.joinToString(",", "[", "]", transform = toJson))
```

Add a serializer function (near `speedEstimatorDiagnostics`):

```kotlin
    private fun satelliteGeometry(satellite: SatelliteGeometry) = json {
        int("svid", satellite.svid)
        int("constellationType", satellite.constellationType)
        // floatOrNull (not double + .toDouble()) deliberately -- a Float->Double cast carries the
        // source Float's own binary rounding artifact into the JSON (see runPreferences() above
        // in this same file for the identical, already-documented reasoning: 0.6f becomes
        // 0.6000000238418579 if cast to Double first). floatOrNull accepts these non-null Floats
        // fine (Float is a subtype of Float?) and calls .toString() directly on the Float, which
        // prints the clean decimal the value actually displays.
        floatOrNull("elevationDegrees", satellite.elevationDegrees)
        floatOrNull("azimuthDegrees", satellite.azimuthDegrees)
        boolean("usedInFix", satellite.usedInFix)
    }
```

Modify `locationFixEvent`:

Current:

```kotlin
    private fun locationFixEvent(event: LocationFixEvent) = json {
        string("type", "LocationFixEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        int("sequenceNumber", event.sequenceNumber)
        long("elapsedSinceStartMillis", event.elapsedSinceStartMillis)
        stringOrNull("provider", event.provider)
        boolean("reliable", event.reliable)
        stringArray("rejectionReasons", event.rejectionReasons.map { it.name })
        intOrNull("satellitesUsedInFix", event.satellitesUsedInFix)
        floatOrNull("satelliteCn0MinDbHz", event.satelliteCn0MinDbHz)
        floatOrNull("satelliteCn0AvgDbHz", event.satelliteCn0AvgDbHz)
        floatOrNull("satelliteCn0MaxDbHz", event.satelliteCn0MaxDbHz)
        enum("qualityTier", event.qualityTier)
        raw("speedEstimatorDiagnostics", event.speedEstimatorDiagnostics?.let(::speedEstimatorDiagnostics))
    }
```

New:

```kotlin
    private fun locationFixEvent(event: LocationFixEvent) = json {
        string("type", "LocationFixEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        int("sequenceNumber", event.sequenceNumber)
        long("elapsedSinceStartMillis", event.elapsedSinceStartMillis)
        stringOrNull("provider", event.provider)
        boolean("reliable", event.reliable)
        stringArray("rejectionReasons", event.rejectionReasons.map { it.name })
        intOrNull("satellitesUsedInFix", event.satellitesUsedInFix)
        floatOrNull("satelliteCn0MinDbHz", event.satelliteCn0MinDbHz)
        floatOrNull("satelliteCn0AvgDbHz", event.satelliteCn0AvgDbHz)
        floatOrNull("satelliteCn0MaxDbHz", event.satelliteCn0MaxDbHz)
        enum("qualityTier", event.qualityTier)
        raw("speedEstimatorDiagnostics", event.speedEstimatorDiagnostics?.let(::speedEstimatorDiagnostics))
        objectArray("satelliteGeometry", event.satelliteGeometry, ::satelliteGeometry)
    }
```

- [ ] **Step 3: Write the writer test first (TDD for this task's own new behavior)**

Add to `SessionEventWriterTest.kt`:

```kotlin
    @Test
    fun locationFixEventSerializesPerSatelliteGeometryAsAGenuineList() {
        val event = LocationFixEvent(
            elapsedRealtimeMillis = 6_000L,
            sequenceNumber = 7,
            elapsedSinceStartMillis = 6_000L,
            provider = "gps",
            reliable = true,
            rejectionReasons = emptyList(),
            satellitesUsedInFix = 2,
            satelliteCn0MinDbHz = null,
            satelliteCn0AvgDbHz = null,
            satelliteCn0MaxDbHz = null,
            qualityTier = QualityTier.OTTIMA,
            speedEstimatorDiagnostics = null,
            satelliteGeometry = listOf(
                SatelliteGeometry(svid = 12, constellationType = 1, elevationDegrees = 45.5f, azimuthDegrees = 180.0f, usedInFix = true),
                SatelliteGeometry(svid = 5, constellationType = 3, elevationDegrees = 10.2f, azimuthDegrees = 270.0f, usedInFix = false),
            ),
        )

        val json = SessionEventWriter.toJsonLine(event)
        assertTrue(json.contains("\"satelliteGeometry\":[" +
            "{\"svid\":12,\"constellationType\":1,\"elevationDegrees\":45.5,\"azimuthDegrees\":180.0,\"usedInFix\":true}," +
            "{\"svid\":5,\"constellationType\":3,\"elevationDegrees\":10.2,\"azimuthDegrees\":270.0,\"usedInFix\":false}]"))
    }

    @Test
    fun locationFixEventDefaultsSatelliteGeometryToEmptyList() {
        val event = LocationFixEvent(
            elapsedRealtimeMillis = 7_000L,
            sequenceNumber = 8,
            elapsedSinceStartMillis = 7_000L,
            provider = "gps",
            reliable = true,
            rejectionReasons = emptyList(),
            satellitesUsedInFix = null,
            satelliteCn0MinDbHz = null,
            satelliteCn0AvgDbHz = null,
            satelliteCn0MaxDbHz = null,
            qualityTier = QualityTier.OTTIMA,
            speedEstimatorDiagnostics = null,
        )

        assertTrue(SessionEventWriter.toJsonLine(event).contains("\"satelliteGeometry\":[]"))
    }
```

- [ ] **Step 4: Run to verify they fail, then confirm they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.eventlog.SessionEventWriterTest"`
Expected: FAIL first (compile error, `SatelliteGeometry`/the new field don't exist) until Steps 1-2 are done, then PASS.

- [ ] **Step 5: Wire real per-satellite data in `RunSessionService.kt`**

Current `GnssSnapshot` and callback (`RunSessionService.kt:103-271`, keeping the existing aggregate CN0 fields — nothing here says to remove them, only to add the per-satellite list alongside):

```kotlin
    private data class GnssSnapshot(
        val satellitesUsedInFix: Int,
        val cn0MinDbHz: Float,
        val cn0AvgDbHz: Float,
        val cn0MaxDbHz: Float,
    )
```

New:

```kotlin
    private data class GnssSnapshot(
        val satellitesUsedInFix: Int,
        val cn0MinDbHz: Float,
        val cn0AvgDbHz: Float,
        val cn0MaxDbHz: Float,
        val geometry: List<SatelliteGeometry>,
    )
```

Current `registerGnssStatusCallback()`:

```kotlin
    private fun registerGnssStatusCallback() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val locationManager = getSystemService(LocationManager::class.java) ?: return
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                var min = Float.MAX_VALUE
                var max = -Float.MAX_VALUE
                var sum = 0f
                for (i in 0 until status.satelliteCount) {
                    if (!status.usedInFix(i)) continue
                    used++
                    val cn0 = status.getCn0DbHz(i)
                    if (cn0 < min) min = cn0
                    if (cn0 > max) max = cn0
                    sum += cn0
                }
                lastGnssSnapshot = if (used > 0) GnssSnapshot(used, min, sum / used, max) else null
            }
        }
        try {
            locationManager.registerGnssStatusCallback(callback, handler)
            gnssStatusCallback = callback
        } catch (_: SecurityException) {
        }
    }
```

New:

```kotlin
    private fun registerGnssStatusCallback() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val locationManager = getSystemService(LocationManager::class.java) ?: return
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                var min = Float.MAX_VALUE
                var max = -Float.MAX_VALUE
                var sum = 0f
                val geometry = mutableListOf<SatelliteGeometry>()
                for (i in 0 until status.satelliteCount) {
                    // Per-satellite geometry is recorded for EVERY satellite Android reports, not
                    // only ones used in the fix -- a future DOP computation needs the full visible
                    // constellation's geometry, not just the subset the fix happened to use.
                    geometry += SatelliteGeometry(
                        svid = status.getSvid(i),
                        constellationType = status.getConstellationType(i),
                        elevationDegrees = status.getElevationDegrees(i),
                        azimuthDegrees = status.getAzimuthDegrees(i),
                        usedInFix = status.usedInFix(i),
                    )
                    if (!status.usedInFix(i)) continue
                    used++
                    val cn0 = status.getCn0DbHz(i)
                    if (cn0 < min) min = cn0
                    if (cn0 > max) max = cn0
                    sum += cn0
                }
                lastGnssSnapshot = if (used > 0) GnssSnapshot(used, min, sum / used, max, geometry) else null
            }
        }
        try {
            locationManager.registerGnssStatusCallback(callback, handler)
            gnssStatusCallback = callback
        } catch (_: SecurityException) {
        }
    }
```

Current `LocationFixEvent(...)` construction in `onLocation()`:

```kotlin
        onEvent(
            LocationFixEvent(
                elapsedRealtimeMillis = now,
                sequenceNumber = locationSequenceNumber++,
                elapsedSinceStartMillis = now - sessionStartedAtElapsedMillis,
                provider = location.provider,
                reliable = reliability.reliable,
                rejectionReasons = reliability.reasons,
                satellitesUsedInFix = snapshot?.satellitesUsedInFix,
                satelliteCn0MinDbHz = snapshot?.cn0MinDbHz,
                satelliteCn0AvgDbHz = snapshot?.cn0AvgDbHz,
                satelliteCn0MaxDbHz = snapshot?.cn0MaxDbHz,
                qualityTier = mapper.qualityTier(reliability, speedAccuracyMps, horizontalAccuracyMeters),
                speedEstimatorDiagnostics = if (reliability.reliable && estimator != null && estimator.getLastAcceptWasMeasurement()) {
                    SpeedEstimatorDiagnostics(
                        processNoiseRegime = estimator.getLastProcessNoiseRegime(),
                        accuracyGateEngaged = estimator.getLastAccuracyGateEngaged(),
                        innovationEmaMagnitude = estimator.getLastInnovationEma(),
                    )
                } else {
                    null
                },
            ),
        )
```

New (add `satelliteGeometry = snapshot?.geometry ?: emptyList()` — keep whatever `SpeedEstimatorDiagnostics(...)` fields Tasks 2/4 already added here; this shows only the line this task adds):

```kotlin
        onEvent(
            LocationFixEvent(
                elapsedRealtimeMillis = now,
                sequenceNumber = locationSequenceNumber++,
                elapsedSinceStartMillis = now - sessionStartedAtElapsedMillis,
                provider = location.provider,
                reliable = reliability.reliable,
                rejectionReasons = reliability.reasons,
                satellitesUsedInFix = snapshot?.satellitesUsedInFix,
                satelliteCn0MinDbHz = snapshot?.cn0MinDbHz,
                satelliteCn0AvgDbHz = snapshot?.cn0AvgDbHz,
                satelliteCn0MaxDbHz = snapshot?.cn0MaxDbHz,
                qualityTier = mapper.qualityTier(reliability, speedAccuracyMps, horizontalAccuracyMeters),
                speedEstimatorDiagnostics = if (reliability.reliable && estimator != null && estimator.getLastAcceptWasMeasurement()) {
                    SpeedEstimatorDiagnostics(
                        processNoiseRegime = estimator.getLastProcessNoiseRegime(),
                        accuracyGateEngaged = estimator.getLastAccuracyGateEngaged(),
                        innovationEmaMagnitude = estimator.getLastInnovationEma(),
                        robustWeight = estimator.getLastRobustWeight(),
                        postBurstExemptionApplied = estimator.getLastPostBurstExemptionApplied(),
                    )
                } else {
                    null
                },
                satelliteGeometry = snapshot?.geometry ?: emptyList(),
            ),
        )
```

- [ ] **Step 6: Build and run the full test suite**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: BUILD SUCCESSFUL, all tests pass, no lint errors. (`RunSessionService.kt`'s `GnssStatus.Callback` isn't unit-testable in isolation — it's a real Android callback — this is verified indirectly via the writer tests plus the app's own instrumentation, not a new unit test here.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt \
  app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt \
  app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt \
  app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt
git commit -m "feat: log genuine per-satellite geometry (elevation/azimuth), not aggregates"
```

---

### Task 8: final verification, dual review, STOP

**Files:** none — checkpoint, no code changes.

- [ ] **Step 1: Run everything one more time, clean**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: BUILD SUCCESSFUL, zero failures, zero lint issues.

- [ ] **Step 2: Self-check against the spec's 9 validation items**

Go through `docs/superpowers/specs/2026-09-19-fase1-robust-outlier-rejection-design.md`'s validation section one item at a time and confirm, in writing (a short note per item is enough), which task and which test covers it. Any item with no matching test is a gap to fix before moving on — do not defer this to the reviewer.

- [ ] **Step 3: Independent adversarial review**

Dispatch a fresh reviewer (no context from this implementation) to check: (a) the self-consistency claim in Task 5's report against the actual final code, not just the report's prose; (b) that G7's two original jobs are still byte-for-byte unchanged by diffing `SpeedEstimator.kt` against `main`'s pre-Task-1 version for anything outside the documented additions; (c) the same categories of question the design doc's own five review rounds asked, now against real code instead of a design doc.

- [ ] **Step 4: Report and STOP**

Report to the project owner: all tests passing, the two report files (`task-5-self-consistency-report.md`, `task-6-threshold-sweep-report.md`) and their real numbers, the independent review's findings and how each was resolved. **Do not merge this branch, delete it, or start any further work** (the G14-adjacent reset work, P6's hysteresis brainstorm, anything else in the roadmap) — all pending a separate, explicit go-ahead from the owner.
