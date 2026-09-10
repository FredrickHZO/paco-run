# SpeedEstimator (Kalman adattivo) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and validate `SpeedEstimator`, a two-axis adaptive Kalman filter that estimates instantaneous speed from raw GPS position fixes alone (never from `location.speed`), as a new component isolated from the existing distance-calculation code.

**Architecture:** Two independent 1D Kalman filters (state `[position, velocity]`, constant-velocity process model), one for the local east axis and one for the local north axis, in a small local tangent-plane projection around the session's first fix. Each `accept()` call predicts forward by the elapsed time, then corrects using the new fix weighted by its own `horizontalAccuracyMeters`. The process noise switches between a low value (steady state) and a high value (detected acceleration) based on an exponentially-smoothed normalized innovation — this is what lets the filter be both stable at constant pace and reactive to real accelerations, without trusting `location.speed` at all. Speed = `hypot(eastVelocity, northVelocity)`.

**Tech Stack:** Kotlin, JUnit4 (`org.junit.Test` / `org.junit.Assert.assertEquals`, no mocking library — matches the rest of `app/src/test`), Gradle (`./gradlew testDebugUnitTest`).

**Spec:** `docs/superpowers/specs/2026-09-10-speed-accuracy-design.md` — read it first. It has the full investigation (why `location.speed` is wrong, why every alternative except this one was rejected, and where every constant below comes from). This plan implements only "Approccio A" from that spec.

## Global Constraints

- **Never use `location.speed` / `LocationSample.speedKmh` inside `SpeedEstimator`.** The spec (section 6.4) shows fusing it — at any trust level — makes the estimate worse on every axis. This is not a missed optimization to revisit; it was tested and rejected.
- **Do not modify `DistanceAccumulator` or its call sites.** Distance stays exactly as it is today (spec section 6.5 — the two are deliberately decoupled). `SpeedEstimator` must be addable and removable without touching `RunSessionStore.kt`'s existing classes.
- **This plan does NOT wire `SpeedEstimator` into `PaceEvaluator` / `SessionCoordinator` / production.** The spec's validation sequence (section 10.4) requires a second real run against Strava before that wiring happens, and that can't be done inside a coding session. This plan stops at "built, unit-tested, sanity-checked against the one real run we have" — wiring it into the live pace-feedback loop is a follow-up plan, written after that second run.
- **GPS-critical code — standing project instruction (from `docs/handoff-2026-09-08.md`):** always TDD, always a dual review (self-review + an independently-dispatched fresh subagent with no prior context) before calling this done. Task 12 below is that checkpoint — do not skip it.
- Starting parameters (all overridable constructor defaults, not hardcoded): `qLow = 0.10`, `qHigh` in `15.0..40.0` (spec found this plateau — use `25.0`), `innovationThreshold` in `1.5..2.0` (use `1.75`), `innovationEmaAlpha = 0.3`. These came from a Python simulation on one real run, **not from running this exact Kotlin code** — if a test's tolerance doesn't hold by a small margin, widening the tolerance or the settling-time budget is fine; don't chase exact numbers from the spec as if they were guaranteed to reproduce bit-for-bit in a different implementation language.

---

## File Structure

| File | Role | Task |
|---|---|---|
| `app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt` | The estimator itself (public class) + its private `AxisKalman` helper | 1, 2, 3 |
| `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt` | Deterministic (noise-free) unit tests | 1, 2, 3 |
| `app/src/test/java/dev/fred/pacorun/service/SyntheticTrajectory.kt` | Test-only helper: generates noisy fix sequences from a known speed profile | 4 |
| `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt` | The 9 scenarios from spec section 10.2, using `SyntheticTrajectory` | 5, 6, 7, 8, 9, 10 |
| `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorRealRunTest.kt` | Regression test replaying the real GPX fixture from the 2026-09-10 run | 11 |
| `app/src/test/resources/paco-run-2026-09-10-1122.gpx` | Fixture copied from `~/Downloads` | 11 |

No existing file is modified by this plan (Global Constraints above).

---

### Task 1: `SpeedEstimator` skeleton — initialization and edge cases

**Files:**
- Create: `app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt`
- Test: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt`

**Interfaces:**
- Consumes: `LocationPosition` (already defined in `RunSessionStore.kt` — `latitude: Double, longitude: Double, elapsedRealtimeMillis: Long, horizontalAccuracyMeters: Float?`).
- Produces: `class SpeedEstimator(qLow: Double = 0.10, qHigh: Double = 25.0, innovationThreshold: Double = 1.75, innovationEmaAlpha: Double = 0.3)` with `fun accept(position: LocationPosition, reliable: Boolean): Double` (speed in km/h) and `fun breakChain(): Double` (always returns `0.0`, resets internal state) — same shape as `DistanceAccumulator.accept`/`breakChain` for consistency.

- [ ] **Step 1: Write the failing tests**

```kotlin
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

    private fun fix(latitude: Double, longitude: Double, elapsedMillis: Long, horizontalAccuracyMeters: Float? = null) =
        LocationPosition(latitude, longitude, elapsedMillis, horizontalAccuracyMeters)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorTest"`
Expected: compilation failure (`SpeedEstimator` doesn't exist yet).

- [ ] **Step 3: Write the skeleton implementation**

```kotlin
package dev.fred.pacorun.service

class SpeedEstimator(
    private val qLow: Double = 0.10,
    private val qHigh: Double = 25.0,
    private val innovationThreshold: Double = 1.75,
    private val innovationEmaAlpha: Double = 0.3,
) {
    private var lastElapsedRealtimeMillis: Long? = null
    private var innovationEma: Double = 0.0

    fun accept(position: LocationPosition, reliable: Boolean): Double {
        if (!reliable || !position.isValid()) return breakChain()

        val previousElapsed = lastElapsedRealtimeMillis
        if (previousElapsed == null) {
            lastElapsedRealtimeMillis = position.elapsedRealtimeMillis
            return 0.0
        }

        val dtSeconds = (position.elapsedRealtimeMillis - previousElapsed) / 1_000.0
        if (dtSeconds <= 0.0) return breakChain()

        lastElapsedRealtimeMillis = position.elapsedRealtimeMillis
        return 0.0 // real Kalman math lands in Task 2
    }

    fun breakChain(): Double {
        lastElapsedRealtimeMillis = null
        innovationEma = 0.0
        return 0.0
    }

    private fun LocationPosition.isValid() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorTest"`
Expected: PASS (all 5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt
git commit -m "feat: add SpeedEstimator skeleton with reset/edge-case handling"
```

---

### Task 2: Two-axis Kalman core (fixed process noise) — deterministic convergence

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `SpeedEstimator.accept()` now returns a real speed estimate in km/h once at least 2 fixes have been accepted. Adds a private nested `AxisKalman` class (state `position: Double`, `velocity: Double`, 2x2 covariance `p00/p01/p10/p11`, methods `predict(dtSeconds: Double, processNoise: Double)` and `update(measurement: Double, measurementVariance: Double): Double` returning the normalized innovation) — later tasks depend on this exact shape.

This task deliberately ignores the adaptive-q switching (always uses `qLow`) so the convergence test is fully deterministic and hand-verifiable — Task 3 adds the switching.

- [ ] **Step 1: Write the failing test**

Noise-free straight-line motion at a known constant speed. With zero measurement noise, a Kalman filter converges to the true velocity within a handful of fixes regardless of `q` (there's no noise to fight), so this test is a pure correctness check of the predict/update math, not of the tuning.

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorTest.constantVelocityNoNoiseConvergesToTrueSpeed"`
Expected: FAIL (`speedKmh` stays `0.0`, since `accept()` still returns `0.0` unconditionally after the first fix).

- [ ] **Step 3: Implement the Kalman core**

Replace the `return 0.0 // real Kalman math lands in Task 2` line and add the projection + `AxisKalman` class:

```kotlin
package dev.fred.pacorun.service

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

class SpeedEstimator(
    private val qLow: Double = 0.10,
    private val qHigh: Double = 25.0,
    private val innovationThreshold: Double = 1.75,
    private val innovationEmaAlpha: Double = 0.3,
) {
    private class AxisKalman(initialPosition: Double) {
        var position: Double = initialPosition
        var velocity: Double = 0.0
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
            val normalizedInnovation = kotlin.math.abs(innovation) / sqrt(innovationVariance)
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

    private var eastAxis: AxisKalman? = null
    private var northAxis: AxisKalman? = null
    private var originLatitude: Double? = null
    private var originLongitude: Double? = null
    private var lastElapsedRealtimeMillis: Long? = null
    private var innovationEma: Double = 0.0

    fun accept(position: LocationPosition, reliable: Boolean): Double {
        if (!reliable || !position.isValid()) return breakChain()

        val lat0 = originLatitude ?: position.latitude.also { originLatitude = it }
        val lon0 = originLongitude ?: position.longitude.also { originLongitude = it }
        val metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(lat0))
        val x = (position.longitude - lon0) * metersPerDegreeLongitude
        val y = (position.latitude - lat0) * METERS_PER_DEGREE_LATITUDE

        val east = eastAxis
        val north = northAxis
        val previousElapsed = lastElapsedRealtimeMillis

        if (east == null || north == null || previousElapsed == null) {
            eastAxis = AxisKalman(x)
            northAxis = AxisKalman(y)
            lastElapsedRealtimeMillis = position.elapsedRealtimeMillis
            return 0.0
        }

        val dtSeconds = (position.elapsedRealtimeMillis - previousElapsed) / 1_000.0
        if (dtSeconds <= 0.0) return breakChain()

        east.predict(dtSeconds, qLow)
        north.predict(dtSeconds, qLow)

        val accuracyMeters = (position.horizontalAccuracyMeters ?: DEFAULT_HORIZONTAL_ACCURACY_METERS).coerceAtLeast(1f)
        val measurementVariance = accuracyMeters.toDouble().pow(2)
        east.update(x, measurementVariance)
        north.update(y, measurementVariance)

        lastElapsedRealtimeMillis = position.elapsedRealtimeMillis
        return hypot(east.velocity, north.velocity) * METERS_PER_SECOND_TO_KMH
    }

    fun breakChain(): Double {
        eastAxis = null
        northAxis = null
        lastElapsedRealtimeMillis = null
        innovationEma = 0.0
        return 0.0
    }

    private fun LocationPosition.isValid() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

    private companion object {
        const val METERS_PER_DEGREE_LATITUDE = 111_320.0
        const val DEFAULT_HORIZONTAL_ACCURACY_METERS = 10f
        const val METERS_PER_SECOND_TO_KMH = 3.6
    }
}
```

Note: `originLatitude`/`originLongitude` are set once on the session's first fix and never reset by `breakChain()` — they're just the reference point for the flat-earth projection, not part of the filter's belief state, so a GPS outage shouldn't move the origin (that would silently shift every subsequent axis reading).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorTest"`
Expected: PASS (all 6 tests, including the 5 from Task 1 — they must still pass since resets are unaffected).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt
git commit -m "feat: implement two-axis Kalman core for SpeedEstimator"
```

---

### Task 3: Adaptive process noise (innovation-gated q switch)

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt`

**Interfaces:**
- Consumes: `AxisKalman.update()`'s returned normalized innovation (already produced by Task 2, currently discarded).
- Produces: same public `SpeedEstimator` interface; internal behavior only.

- [ ] **Step 1: Write the failing test**

Noise-free step change (so the test is deterministic): constant 5 km/h, then an instant jump to 12 km/h, sustained. With `qLow` alone the filter would lag for many seconds (Task 2's filter has no way to know an acceleration is real vs noise); with adaptive `q` it should catch up quickly once the sustained innovation triggers `qHigh`.

```kotlin
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

        // Sustained jump to 12 km/h -- give it 6 fixes (roughly matching the real run's
        // observed settling time, spec section 10.2 scenario 3) to catch up.
        repeat(6) { speedKmh = step(12.0) }
        assertEquals(12.0, speedKmh, 0.5)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorTest.sustainedAccelerationTriggersFastSettlingViaAdaptiveProcessNoise"`
Expected: FAIL (fixed `qLow` alone won't settle to within 0.5 km/h of 12.0 in only 6 fixes — verify this is genuinely the failure, not a typo, by reading the assertion failure's actual value).

- [ ] **Step 3: Wire in the adaptive switch**

In `accept()`, replace the two fixed-`qLow` `predict()` calls and add the ema update after both `update()` calls:

```kotlin
        val processNoise = if (innovationEma > innovationThreshold) qHigh else qLow
        east.predict(dtSeconds, processNoise)
        north.predict(dtSeconds, processNoise)

        val accuracyMeters = (position.horizontalAccuracyMeters ?: DEFAULT_HORIZONTAL_ACCURACY_METERS).coerceAtLeast(1f)
        val measurementVariance = accuracyMeters.toDouble().pow(2)
        val eastInnovation = east.update(x, measurementVariance)
        val northInnovation = north.update(y, measurementVariance)
        innovationEma = innovationEmaAlpha * maxOf(eastInnovation, northInnovation) + (1 - innovationEmaAlpha) * innovationEma
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorTest"`
Expected: PASS (all 7 tests). If the step-change test still doesn't settle within tolerance, widen the tolerance or the fix budget slightly rather than changing the constants — this specific test's job is "adaptive q clearly helps vs Task 2's fixed q", not "hit these exact numbers".

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt
git commit -m "feat: add innovation-gated adaptive process noise to SpeedEstimator"
```

---

### Task 4: Synthetic trajectory generator (test-only noise model)

**Files:**
- Create: `app/src/test/java/dev/fred/pacorun/service/SyntheticTrajectory.kt`

**Interfaces:**
- Consumes: `LocationPosition`.
- Produces:
  ```kotlin
  object SyntheticTrajectory {
      data class Fix(val position: LocationPosition, val trueSpeedKmh: Double)

      fun generate(
          speedProfileKmh: List<Pair<Double, Double>>, // (durationSeconds, speedKmh) segments, run back to back
          noiseStdMeters: Double,
          sampleIntervalSeconds: Double = 1.0,
          accuracyMeters: Float = noiseStdMeters.toFloat(),
          headingDegrees: Double = 0.0, // 0 = north, 90 = east
          startLatitude: Double = 45.0,
          startLongitude: Double = 9.0,
          startElapsedMillis: Long = 0L,
          seed: Long = 42L,
      ): List<Fix>
  }
  ```
  Later tasks (scenarios) consume `SyntheticTrajectory.generate(...)` and feed the resulting `Fix.position` into `SpeedEstimator.accept()`, comparing against `Fix.trueSpeedKmh`.

This is a test utility with no behavior of its own to TDD against production code, but its own correctness matters (a buggy noise generator invalidates every scenario test), so step 1 is a statistical sanity check of the generator itself before it's trusted.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/fred/pacorun/service/SyntheticTrajectoryTest.kt`:

```kotlin
package dev.fred.pacorun.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SyntheticTrajectoryTest {
    @Test
    fun generatedFixesAverageToTheRequestedSpeedOverAConstantSegment() {
        val fixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(60.0 to 10.0),
            noiseStdMeters = 3.0,
        )

        assertEquals(60, fixes.size)
        assertTrue(fixes.all { it.trueSpeedKmh == 10.0 })

        // The average of many noisy fixes' position deltas should land close to the true
        // 10 km/h even though individual fix-to-fix deltas are noisy.
        var totalDistance = 0.0
        for (i in 1 until fixes.size) {
            val a = fixes[i - 1].position
            val b = fixes[i].position
            totalDistance += haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        val totalSeconds = (fixes.last().position.elapsedRealtimeMillis - fixes.first().position.elapsedRealtimeMillis) / 1_000.0
        val averageSpeedKmh = (totalDistance / totalSeconds) * 3.6
        assertEquals(10.0, averageSpeedKmh, 1.0)
    }

    @Test
    fun noiseStandardDeviationMatchesRequestedMagnitude() {
        // Zero true motion, so every fix-to-fix displacement IS the noise, letting us check
        // the generator's actual injected noise std against what was requested.
        val fixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(200.0 to 0.0),
            noiseStdMeters = 5.0,
        )

        val deltas = (1 until fixes.size).map { i ->
            haversineMeters(
                fixes[i - 1].position.latitude, fixes[i - 1].position.longitude,
                fixes[i].position.latitude, fixes[i].position.longitude,
            )
        }
        // Each delta is the difference of two independent 2D Gaussian noise samples (std
        // noiseStdMeters each), so its expected magnitude is on the order of noiseStdMeters --
        // loose bounds here since this is a statistical property of a small sample, not exact.
        val meanDelta = deltas.average()
        assertTrue("mean delta $meanDelta should be roughly noiseStdMeters-scaled", meanDelta in 1.0..15.0)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1); val dLambda = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dPhi / 2).let { it * it } +
            kotlin.math.cos(p1) * kotlin.math.cos(p2) * kotlin.math.sin(dLambda / 2).let { it * it }
        return 2 * r * kotlin.math.asin(sqrt(a))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SyntheticTrajectoryTest"`
Expected: compilation failure (`SyntheticTrajectory` doesn't exist).

- [ ] **Step 3: Implement the generator**

```kotlin
package dev.fred.pacorun.service

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object SyntheticTrajectory {
    data class Fix(val position: LocationPosition, val trueSpeedKmh: Double)

    private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    private const val KMH_TO_METERS_PER_SECOND = 1.0 / 3.6

    fun generate(
        speedProfileKmh: List<Pair<Double, Double>>,
        noiseStdMeters: Double,
        sampleIntervalSeconds: Double = 1.0,
        accuracyMeters: Float = noiseStdMeters.toFloat(),
        headingDegrees: Double = 0.0,
        startLatitude: Double = 45.0,
        startLongitude: Double = 9.0,
        startElapsedMillis: Long = 0L,
        seed: Long = 42L,
    ): List<Fix> {
        val random = Random(seed)
        val headingRadians = Math.toRadians(headingDegrees)
        val fixes = mutableListOf<Fix>()

        var trueLatitude = startLatitude
        var trueLongitude = startLongitude
        var elapsedMillis = startElapsedMillis
        var timeInSegment = 0.0
        var segmentIndex = 0

        while (segmentIndex < speedProfileKmh.size) {
            val (durationSeconds, speedKmh) = speedProfileKmh[segmentIndex]
            val distanceMeters = (speedKmh * KMH_TO_METERS_PER_SECOND) * sampleIntervalSeconds
            val metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(trueLatitude))
            trueLatitude += (distanceMeters * cos(headingRadians)) / METERS_PER_DEGREE_LATITUDE
            trueLongitude += (distanceMeters * sin(headingRadians)) / metersPerDegreeLongitude
            elapsedMillis += (sampleIntervalSeconds * 1_000).toLong()

            val (noisyLat, noisyLon) = addGaussianNoiseMeters(trueLatitude, trueLongitude, noiseStdMeters, random)
            fixes += Fix(LocationPosition(noisyLat, noisyLon, elapsedMillis, accuracyMeters), speedKmh)

            timeInSegment += sampleIntervalSeconds
            if (timeInSegment + 1e-9 >= durationSeconds) {
                timeInSegment = 0.0
                segmentIndex++
            }
        }
        return fixes
    }

    private fun addGaussianNoiseMeters(latitude: Double, longitude: Double, stdMeters: Double, random: Random): Pair<Double, Double> {
        val metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(latitude))
        val noisyLat = latitude + (gaussian(random) * stdMeters) / METERS_PER_DEGREE_LATITUDE
        val noisyLon = longitude + (gaussian(random) * stdMeters) / metersPerDegreeLongitude
        return noisyLat to noisyLon
    }

    // Box-Muller transform: a standard normal sample from two uniform ones, no extra dependency needed.
    private fun gaussian(random: Random): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SyntheticTrajectoryTest"`
Expected: PASS. If `noiseStandardDeviationMatchesRequestedMagnitude` is flaky, widen its bounds (it's a loose statistical sanity check, not a precise one) rather than reworking the generator.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/dev/fred/pacorun/service/SyntheticTrajectory.kt app/src/test/java/dev/fred/pacorun/service/SyntheticTrajectoryTest.kt
git commit -m "test: add synthetic GPS trajectory generator with calibrated noise"
```

---

### Task 5: Scenarios 1+2 — steady state at multiple speeds

**Files:**
- Create: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt`

**Interfaces:**
- Consumes: `SpeedEstimator`, `SyntheticTrajectory.generate`.
- Produces: nothing new for later tasks (each scenario task adds independent tests to this same file).

Noise model per spec section 10.1: `noiseStdMeters = 3.5` (mid-point of the measured 3.0-6.6m normal range), `accuracyMeters` defaults to match. Spec table criterion: after convergence, `|estimated - true| < 0.3 km/h` for ≥95% of samples, discarding an initial warm-up window.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.fred.pacorun.service

import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEstimatorScenarioTest {
    private val warmupFixes = 10 // fixes to discard before judging steady-state accuracy

    @Test
    fun steadyStateAtMultipleSpeeds() {
        for (speedKmh in listOf(5.0, 8.0, 12.0, 16.0)) {
            val estimator = SpeedEstimator()
            val fixes = SyntheticTrajectory.generate(
                speedProfileKmh = listOf(60.0 to speedKmh),
                noiseStdMeters = 3.5,
            )

            val estimates = fixes.map { estimator.accept(it.position, reliable = true) }
            val steadyState = estimates.drop(warmupFixes)
            val withinTolerance = steadyState.count { kotlin.math.abs(it - speedKmh) < 0.3 }
            val fraction = withinTolerance.toDouble() / steadyState.size

            assertTrue(
                "at $speedKmh km/h: only ${fraction * 100}% of samples within 0.3 km/h (need >=95%)",
                fraction >= 0.95,
            )
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails or passes**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorScenarioTest"`

This test exercises code that already exists (Tasks 1-4) — it may already pass. That's fine: its job in this plan is to *codify* the spec's acceptance criterion as an executable regression test, not to drive new production code. If it fails, that's real signal that the chosen constants (Global Constraints) don't hold even in the easiest scenario — stop and reconsider the constants before moving to harder scenarios, don't just loosen this test's tolerance.

- [ ] **Step 3: If it failed, adjust — otherwise skip to commit**

Only if Step 2 failed: the most likely fix is a longer `warmupFixes` (the filter's initial covariance is deliberately wide, so early estimates are noisy by design) — try 15-20 before touching `qLow`/`qHigh`. Re-run after any change.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt
git commit -m "test: verify SpeedEstimator steady-state accuracy across multiple speeds"
```

---

### Task 6: Scenarios 3+4 — step response (acceleration and deceleration)

**Files:**
- Modify: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt`

Spec criterion: settle within 10% of the new true speed within ≤3-5s (≤3-5 fixes at 1Hz) after a sudden, sustained change.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun stepResponseAccelerationSettlesQuickly() {
        assertSettlesWithinFixes(fromKmh = 5.0, toKmh = 12.0, maxFixesToSettle = 5)
    }

    @Test
    fun stepResponseDecelerationSettlesQuickly() {
        assertSettlesWithinFixes(fromKmh = 12.0, toKmh = 5.0, maxFixesToSettle = 5)
    }

    private fun assertSettlesWithinFixes(fromKmh: Double, toKmh: Double, maxFixesToSettle: Int) {
        val estimator = SpeedEstimator()
        val fixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(30.0 to fromKmh, 30.0 to toKmh),
            noiseStdMeters = 3.5,
        )

        val estimates = fixes.map { estimator.accept(it.position, reliable = true) }
        // First 30 fixes are the "from" segment (already converged by construction of the
        // steady-state test above); look at the fixes right after the step.
        val afterStep = estimates.drop(30)
        val settledIndex = afterStep.indexOfFirst { kotlin.math.abs(it - toKmh) < toKmh * 0.10 }

        assertTrue(
            "never settled within 10% of $toKmh km/h in the ${afterStep.size} fixes after the step",
            settledIndex in 0 until maxFixesToSettle,
        )
        // And it stays settled -- not a one-sample fluke.
        val staysSettled = afterStep.drop(settledIndex).count { kotlin.math.abs(it - toKmh) < toKmh * 0.10 }
        assertTrue(staysSettled.toDouble() / (afterStep.size - settledIndex) >= 0.9)
    }
```

- [ ] **Step 2: Run tests**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorScenarioTest"`
Expected: PASS, given Task 3's adaptive switch. If `maxFixesToSettle = 5` is too tight, widen it to 6-8 before touching the algorithm — the spec's own number here was an estimate, not measured against this exact Kotlin implementation.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt
git commit -m "test: verify SpeedEstimator settles quickly after sudden pace changes"
```

---

### Task 7: Scenario 5 — gradual ramp (no overshoot)

**Files:**
- Modify: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt`

- [ ] **Step 1: Write the failing test**

A gradual 12-second ramp from 5 to 12 km/h, built as short constant-speed segments (the generator only supports piecewise-constant segments, which approximates a ramp well at 1s resolution).

```kotlin
    @Test
    fun gradualRampHasNoLargeOvershoot() {
        val estimator = SpeedEstimator()
        val rampSpeeds = (0..12).map { 5.0 + (12.0 - 5.0) * (it / 12.0) }
        val rampProfile = rampSpeeds.map { 1.0 to it }
        val fixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(30.0 to 5.0) + rampProfile + listOf(10.0 to 12.0),
            noiseStdMeters = 3.5,
        )

        var index = 0
        val estimates = fixes.map { fix ->
            val estimate = estimator.accept(fix.position, reliable = true)
            index++
            estimate to fix.trueSpeedKmh
        }

        // During the ramp (roughly fixes 30..42) and shortly after, the estimate should
        // never overshoot the fastest speed in the profile by more than 1 km/h.
        val duringAndAfterRamp = estimates.drop(30)
        val maxOvershoot = duringAndAfterRamp.maxOf { (estimate, _) -> estimate - 12.0 }
        assertTrue("overshot the ramp's target speed by $maxOvershoot km/h", maxOvershoot < 1.0)
    }
```

- [ ] **Step 2: Run test**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorScenarioTest.gradualRampHasNoLargeOvershoot"`
Expected: PASS. A constant-velocity Kalman model has no inherent overshoot mechanism (unlike e.g. a PID controller), so this is mostly a confirmation there's no bug — if it fails, look for a sign error in the adaptive-q logic before assuming the tolerance is wrong.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt
git commit -m "test: verify SpeedEstimator tracks a gradual pace ramp without overshoot"
```

---

### Task 8: Scenario 6 — degraded GPS accuracy burst

**Files:**
- Modify: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt`

Real measured degraded accuracy from spec section 10.1: 18-23m (vs. normal 3-6.6m). This scenario keeps true speed constant throughout — the only thing that changes is the reported accuracy — so any large deviation is a real defect, not measurement of real motion.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun degradedAccuracyBurstDoesNotProduceFalseSpeedSpikes() {
        val estimator = SpeedEstimator()
        val goodFixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(20.0 to 8.0),
            noiseStdMeters = 3.5,
            accuracyMeters = 3.5f,
        )
        val degradedFixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(8.0 to 8.0),
            noiseStdMeters = 20.0,
            accuracyMeters = 20.0f,
            startLatitude = goodFixes.last().position.latitude,
            startLongitude = goodFixes.last().position.longitude,
            startElapsedMillis = goodFixes.last().position.elapsedRealtimeMillis,
            seed = 99L,
        )

        goodFixes.forEach { estimator.accept(it.position, reliable = true) }
        val degradedEstimates = degradedFixes.map { estimator.accept(it.position, reliable = true) }

        val maxDeviation = degradedEstimates.maxOf { kotlin.math.abs(it - 8.0) }
        assertTrue("deviated by $maxDeviation km/h during the degraded-accuracy burst", maxDeviation < 2.0)
    }
```

- [ ] **Step 2: Run test**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorScenarioTest.degradedAccuracyBurstDoesNotProduceFalseSpeedSpikes"`
Expected: PASS — the filter should already down-weight the degraded fixes via `measurementVariance` (Task 2), which is the entire point of feeding `horizontalAccuracyMeters` in. If this fails, the bug is almost certainly in how `measurementVariance` is computed, not in the adaptive-q logic.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt
git commit -m "test: verify SpeedEstimator tolerates a degraded-accuracy GPS burst"
```

---

### Task 9: Scenario 7 — GPS outage and recovery, plus the unconditional NaN/Infinity safety net

**Files:**
- Modify: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt`

Per spec section 10.2, "never NaN/Infinity" must hold across every scenario, not just this one — this task adds both the specific outage test and a cross-cutting helper other tasks could reuse if they're revisited later.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun gpsOutageRecoversWithoutInventingSpeed() {
        val estimator = SpeedEstimator()
        val beforeOutage = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(20.0 to 8.0),
            noiseStdMeters = 3.5,
        )
        beforeOutage.forEach { estimator.accept(it.position, reliable = true) }

        // 8 seconds with no reliable fix -- mirrors how RunSessionService/DistanceAccumulator
        // treat an outage: reliable=false, no real position update.
        repeat(8) { i ->
            val speed = estimator.accept(
                LocationPosition(0.0, 0.0, beforeOutage.last().position.elapsedRealtimeMillis + (i + 1) * 1_000L),
                reliable = false,
            )
            assertTrue("produced non-finite speed during outage", speed.isFinite())
        }

        val afterOutage = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(15.0 to 8.0),
            noiseStdMeters = 3.5,
            startLatitude = beforeOutage.last().position.latitude,
            startLongitude = beforeOutage.last().position.longitude,
            startElapsedMillis = beforeOutage.last().position.elapsedRealtimeMillis + 9_000L,
            seed = 7L,
        )
        val recoveryEstimates = afterOutage.map { estimator.accept(it.position, reliable = true) }

        recoveryEstimates.forEach { assertTrue("non-finite speed after recovery", it.isFinite()) }
        // Same settling budget as the step-response test -- an outage is handled like a break
        // in the chain (see DistanceAccumulator.breakChain semantics), so recovery restarts
        // from scratch and needs the same warm-up, not an instant lock.
        val settledIndex = recoveryEstimates.indexOfFirst { kotlin.math.abs(it - 8.0) < 0.8 }
        assertTrue("never settled after outage recovery", settledIndex in 0..10)
    }
```

- [ ] **Step 2: Run test**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorScenarioTest.gpsOutageRecoversWithoutInventingSpeed"`
Expected: PASS, given Task 1's `breakChain()` on `reliable = false`.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt
git commit -m "test: verify SpeedEstimator handles a GPS outage and recovers cleanly"
```

---

### Task 10: Scenario 8 — curved path

**Files:**
- Modify: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt`

The two axis filters (east/north) are independent — this checks that independence doesn't create artifacts when direction changes (each axis alone sees its own velocity component go through a transient even at constant overall speed).

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun directionChangeAtConstantSpeedDoesNotDipTheSpeedEstimate() {
        val estimator = SpeedEstimator()
        val northLeg = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(20.0 to 9.0),
            noiseStdMeters = 3.5,
            headingDegrees = 0.0,
        )
        northLeg.forEach { estimator.accept(it.position, reliable = true) }

        val eastLeg = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(20.0 to 9.0),
            noiseStdMeters = 3.5,
            headingDegrees = 90.0,
            startLatitude = northLeg.last().position.latitude,
            startLongitude = northLeg.last().position.longitude,
            startElapsedMillis = northLeg.last().position.elapsedRealtimeMillis,
            seed = 13L,
        )
        val turnEstimates = eastLeg.map { estimator.accept(it.position, reliable = true) }

        // Right after the turn the estimate may dip (real physical deceleration+reacceleration
        // is indistinguishable from a sharp turn without heading data), but it must recover
        // and never read as an implausible near-zero for a runner holding pace through a turn.
        val afterSettling = turnEstimates.drop(10)
        assertTrue(afterSettling.all { it > 5.0 }, "speed estimate dropped implausibly low after a turn: $afterSettling")
    }
```

- [ ] **Step 2: Run test**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorScenarioTest.directionChangeAtConstantSpeedDoesNotDipTheSpeedEstimate"`
Expected: PASS after settling. A dip *during* the turn itself (before the 10-fix warm-up) is physically expected and fine — this test only asserts the settled behavior, matching spec section 10.2's framing of scenario 8.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt
git commit -m "test: verify SpeedEstimator recovers cleanly after a direction change"
```

---

### Task 11: Regression check against the real 2026-09-10 recorded run

**Files:**
- Create: `app/src/test/resources/paco-run-2026-09-10-1122.gpx` (copy from `~/Downloads/paco-run-2026-09-10-1122.gpx`)
- Create: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorRealRunTest.kt`

This is lighter-weight than reproducing the spec's full MAE-vs-Strava analysis in Kotlin (that comparison needs Strava's GPX too and a causal-windowing implementation — reasonable to redo as a one-off script if extra confidence is wanted, but not worth committing as permanent test infra). This task's job is narrower and still worth having permanently: replay the one real recording available through the real implementation and assert it never produces garbage, as a sanity net beyond the synthetic scenarios.

**Interfaces:**
- Consumes: `SpeedEstimator`, a minimal GPX parser (JDK's `javax.xml.parsers`, no new dependency).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Copy the fixture**

```bash
mkdir -p app/src/test/resources
cp ~/Downloads/paco-run-2026-09-10-1122.gpx app/src/test/resources/
```

- [ ] **Step 2: Write the failing test**

```kotlin
package dev.fred.pacorun.service

import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class SpeedEstimatorRealRunTest {
    @Test
    fun realRunReplayNeverProducesNonFiniteSpeed() {
        val fixes = loadPacoRunGpxFixture()
        assertTrue("fixture failed to load any fixes", fixes.isNotEmpty())

        val estimator = SpeedEstimator()
        val estimates = fixes.map { (position, reliable) -> estimator.accept(position, reliable) }

        estimates.forEach { assertTrue("non-finite speed in real-run replay: $it", it.isFinite()) }

        // Loose sanity bound, not a precision check (that's the spec's Python analysis,
        // section 7) -- this run's average pace was well within 4-14 km/h throughout.
        val steadyEstimates = estimates.drop(20)
        val average = steadyEstimates.average()
        assertTrue("implausible average speed for this run: $average km/h", average in 4.0..14.0)
    }

    private fun loadPacoRunGpxFixture(): List<Pair<LocationPosition, Boolean>> {
        val stream = javaClass.classLoader.getResourceAsStream("paco-run-2026-09-10-1122.gpx")
            ?: error("fixture not found on test classpath")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val trkpts = document.getElementsByTagName("trkpt")
        val fixes = mutableListOf<Pair<LocationPosition, Boolean>>()

        for (i in 0 until trkpts.length) {
            val trkpt = trkpts.item(i)
            val lat = trkpt.attributes.getNamedItem("lat").nodeValue.toDouble()
            val lon = trkpt.attributes.getNamedItem("lon").nodeValue.toDouble()
            var timeIso: String? = null
            var accuracy: Float? = null
            var reliable = true
            val children = trkpt.childNodes
            for (j in 0 until children.length) {
                val child = children.item(j)
                when (child.nodeName) {
                    "time" -> timeIso = child.textContent
                    "extensions" -> {
                        val extChildren = child.childNodes
                        for (k in 0 until extChildren.length) {
                            val ext = extChildren.item(k)
                            when (ext.nodeName) {
                                "pr:horizontalAccuracyMeters" -> accuracy = ext.textContent.toFloatOrNull()
                                "pr:reliable" -> reliable = ext.textContent == "true"
                            }
                        }
                    }
                }
            }
            val elapsedMillis = java.time.Instant.parse(timeIso).toEpochMilli()
            fixes += LocationPosition(lat, lon, elapsedMillis, accuracy) to reliable
        }
        return fixes
    }
}
```

- [ ] **Step 3: Run test to verify it fails, then passes**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorRealRunTest"`

Before the fixture is copied (Step 1 not yet done): FAILs with a missing-resource error. After Step 1 and with a correct implementation: PASS. If the average-speed bound fails, widen it — this run's actual reported pace ranged roughly 4-11 km/h per the spec, `4.0..14.0` already has headroom.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/resources/paco-run-2026-09-10-1122.gpx app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorRealRunTest.kt
git commit -m "test: replay the 2026-09-10 recorded run through SpeedEstimator as a sanity net"
```

---

### Task 12: Full verification, dual review, and wrap-up

**Files:** none created/modified — this is a checkpoint, not a code task.

- [ ] **Step 1: Run the full verification bar**

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Both must be clean before proceeding. Fix anything that isn't.

- [ ] **Step 2: Self-review**

Re-read `SpeedEstimator.kt` end to end against `docs/superpowers/specs/2026-09-10-speed-accuracy-design.md` section 7 ("Architettura (da implementare)"). Confirm: no use of `location.speed` anywhere, `DistanceAccumulator`/`RunSessionStore.kt` untouched, all 4 constants are constructor parameters with the spec's defaults.

- [ ] **Step 3: Independent fresh-context review (required — GPS-critical work)**

Dispatch a fresh subagent with no prior context on this work. Give it: the spec (`docs/superpowers/specs/2026-09-10-speed-accuracy-design.md`), this plan, and the diff since this plan started. Ask it to trace `SpeedEstimator.accept()` by hand against a few realistic fix sequences (sustained constant pace, a real acceleration, a degraded-accuracy burst) and independently confirm the Kalman predict/update math matches the standard formulas — same dual-review process the project used for the original `DistanceAccumulator` fix (`docs/handoff-2026-09-08.md`). Reconcile any discrepancy before proceeding.

- [ ] **Step 4: Update the spec's status line**

In `docs/superpowers/specs/2026-09-10-speed-accuracy-design.md`, change the header's `**Stato:**` line to reflect that `SpeedEstimator` is implemented and unit-tested, and explicitly note it is **not yet wired into production** — that requires a second real run validated against Strava (spec section 10.4, step 4), which is out of scope for this plan.

- [ ] **Step 5: Final commit**

```bash
git add docs/superpowers/specs/2026-09-10-speed-accuracy-design.md
git commit -m "docs: mark SpeedEstimator implemented and unit-tested, pending field validation"
```

**Explicitly out of scope for this plan (do not do these):** wiring `SpeedEstimator` into `SessionCoordinator`/`PaceEvaluator`/`RunSessionService`; copying a built APK anywhere; fixing the distance bugs from `docs/pending-review-findings-2026-09-10.md`; anything about the accelerometer (spec section 9). Report back to the user with a summary and wait for direction on the second-run validation before any follow-up plan touches production wiring.
