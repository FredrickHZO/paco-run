# Phase-aware pace-decision smoothing (P2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `PaceEvaluator`'s confirmed pace state from flapping directly `TOO_SLOW`↔`TOO_FAST` (observed 25 times in one real run) by smoothing the speed signal it decides on with a time-normalized EMA, purged at three known discontinuities (real phase change, an early "10s warning" cue, and stale-GPS recovery) instead of a statistically-inferred change-point detector.

**Architecture:** A new, small `EmaPaceSmoother` class (implementing a new `SessionPaceSmoothingEngine` interface) sits between `KalmanSpeedEngine`'s raw output and `PaceEvaluator`'s decision input, in the same file (`RunSessionStore.kt`) and the same style as the existing `DistanceAccumulator`/`KalmanSpeedEngine` pair. `SessionCoordinator.process()` purges it at three points, then feeds it the raw speed and passes the *smoothed* result into `PaceEvaluator.accept()` — everything else (`RunSessionState.speedKmh`, `RoutineInput.currentReliableSpeedKmh`, `AudioCueEmittedEvent.speedKmh`) keeps reading the raw value, unchanged. `PaceEvaluator.accept()` gains a `rawSpeedKmh` parameter, appended last with a default, purely so `PaceStateConfirmedEvent` can log both the smoothed value it decided on and the true raw value — its decision logic (the `when` on `paceState()`, `confirmationSamples` counting, cue selection) is untouched.

**Tech Stack:** Kotlin, JUnit4, Gradle (`./gradlew testDebugUnitTest`, `lintDebug`).

**Spec:** `docs/superpowers/specs/2026-09-18-phase-aware-pace-smoothing-design.md` (commit `f83e3d8` on `feature/paco-run-m3e`), cross-referenced against `docs/superpowers/specs/2026-09-11-precision-roadmap.md`'s P1-P5 table for status/ordering context.

## Branch — read before Task 1

**`feature/paco-run-m3e` does NOT have Kalman speed estimation or P1's event logging merged.** Both are fully implemented, tested, and dual-reviewed on `feature/session-event-logging` (forked from `feature/speed-production-wiring`, per that plan's own Branch section), held back from `feature/paco-run-m3e` so the owner can field-test them independently first. P2's entire purpose is smoothing `PaceEvaluator`'s input using `KalmanSpeedEngine`'s output and logging through P1's `PaceStateConfirmedEvent` schema — neither of which exists on `feature/paco-run-m3e` — so **this plan's branch must fork from `feature/session-event-logging`, not from `feature/paco-run-m3e`**, exactly the same reasoning the P1 plan applied one level down.

**Branch/worktree to create (not created by this plan — a subsequent step, authorized separately by the owner, per `superpowers:using-git-worktrees`):** `feature/pace-decision-smoothing`, forked from `feature/session-event-logging` (tip at the time this plan was written: `4c7b8e9`).

Every file path, line reference, and current-code snippet quoted in this plan was read directly from `feature/session-event-logging`'s tip (worktree `.worktrees/session-event-logging`), not trusted from the design spec's own citations — the spec's line numbers were independently re-verified against the real files while writing this plan, and in a few places (noted inline below) differ slightly from what the spec cites, without changing any conclusion.

## Global Constraints

- **Do not touch `SpeedEstimator`/`KalmanSpeedEngine`'s internals.** Already validated, in production, out of scope (spec Decision 2).
- **`PaceEvaluator`'s decision logic stays byte-for-byte unchanged.** It keeps deciding pace state from whichever `speedKmh` its `sample` parameter carries — the new `rawSpeedKmh` parameter exists only to populate `PaceStateConfirmedEvent.rawSpeedKmh`, never read anywhere in the `when`/debounce/cue-selection logic.
- **`RunSessionState.speedKmh`, `RoutineInput.currentReliableSpeedKmh`, and `AudioCueEmittedEvent.speedKmh` all keep reading the RAW (unsmoothed) `KalmanSpeedEngine` output.** Only the value passed into `paceEvaluator.accept()` is smoothed. Task 4 includes an explicit test asserting this, not just an implicit assumption.
- **τ=6.0s ships as a code constant** (`EmaPaceSmoother`'s `timeConstantSeconds` default parameter), not a `RunPreferences` user setting. Per the spec: this is a **starting estimate from a single real run, in a regime already less severe than the run that originally motivated this work** (see spec's "Il limite più importante" in Decision 1) — revisable with the same discipline as `SpeedEstimator`'s `qHigh`/`innovationEmaAlpha`, not a settled final value. Do not let any test or comment in this plan imply otherwise.
- **Do not touch P3** (the `GOOD_PACE`-after-`previous==null` suppression bug, `PaceEvaluator.kt` lines 81-86) **or P4** (`RoutineEngine.advanceTo()`'s two-transitions-per-call cap) **or P5** (audio enqueue-vs-playback). Independent, separate work — not folded in here. Note the one real interaction: P2 changes `PaceEvaluator.accept()`'s signature (adds `rawSpeedKmh`), so whoever implements P3 next will find that signature already changed — not a conflict, just an ordering dependency to keep in mind.
- **No new event type for the three purge triggers** (spec Decision 7) — they're already fully reconstructible from existing P1 events (`RoutinePhaseChangedEvent`, `AudioCueEmittedEvent` with `cueType` `WALK_IN_TEN`/`RUN_IN_TEN`, `SpeedChainBrokenEvent(reason=STALE_NO_RECENT_RELIABLE_FIX)`) by joining on `elapsedRealtimeMillis`.
- **Verification is unit-test-only (plain JUnit4, no Robolectric, no instrumented tests)** — this plan touches no Android-only code (`RunSessionService.kt` needs zero changes: `PendingSessionCoordinator`'s new `paceSmoother` parameter is named with a default, and its one production call site in `RunSessionService.kt:214-224` already uses named arguments exclusively), so every task here is fully unit-testable.
- **The synthetic sensitivity-analysis table (spec Decision 8.2) is an already-completed OFFLINE result, printed in the spec itself, from real 2026-09-15 log data — it is not an in-app feature and ships no code.** See the note after Task 5 for why this plan does not add a committed analysis script either.
- **Stop at the final task.** No merge, no branch deletion, no APK build, no work on P3/P4/P5 — matching every prior plan in this project.

---

## File Structure

| File | Role | Task |
|---|---|---|
| `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt` | Modify: new `SessionPaceSmoothingEngine` interface + `EmaPaceSmoother` class (Task 1); `SessionPaceEvaluator` interface + `PaceEvaluatorAdapter` signature (Task 3); `SessionCoordinator`/`PendingSessionCoordinator` wiring — `paceSmoother` param, three purge triggers, smoothed decision input (Task 4) | 1, 3, 4 |
| `app/src/test/java/dev/fred/pacorun/service/EmaPaceSmootherTest.kt` | New: `EmaPaceSmoother` unit tests | 1 |
| `app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt` | Modify: `PaceStateConfirmedEvent` gains `rawSpeedKmh: Float? = null`, last field | 2 |
| `app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt` | Modify: `paceStateConfirmedEvent()` serializes `rawSpeedKmh` | 2 |
| `app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt` | Modify: update exact-JSON assertion, add a non-null-`rawSpeedKmh` test | 2 |
| `app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt` | Modify: `accept()` gains `rawSpeedKmh: Float? = sample.speedKmh`, last parameter; threads into `PaceStateConfirmedEvent` | 3 |
| `app/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt` | Modify: fix broken positional `PaceStateConfirmedEvent(...)` assertions; add `rawSpeedKmh` passthrough test (Task 3); add synthetic worst-case tests (Task 5) | 3, 5 |
| `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt` | Modify: `FakePaceEvaluator` signature fix (Task 3); `FakePaceEvaluator` raw-speed capture, `FakeRoutineEngine` cues param, new `FakePaceSmoother`, 5 new wiring tests (Task 4) | 3, 4 |

---

### Task 1: `EmaPaceSmoother` / `SessionPaceSmoothingEngine` — the smoothing component

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`
- Test: `app/src/test/java/dev/fred/pacorun/service/EmaPaceSmootherTest.kt`

**Interfaces:**
- Consumes: nothing new — pure Kotlin, `kotlin.math.exp`.
- Produces: `interface SessionPaceSmoothingEngine { fun accept(rawSpeedKmh: Float, elapsedRealtimeMillis: Long): Float; fun purge() }` and `class EmaPaceSmoother(private val timeConstantSeconds: Double = 6.0) : SessionPaceSmoothingEngine` — both consumed by Task 4's `SessionCoordinator`/`PendingSessionCoordinator` wiring, and by Task 5's synthetic test.

This task is fully standalone — no dependency on any other task, and nothing outside this file references it yet, so the full suite stays green throughout.

- [ ] **Step 1: Write the failing tests**

```kotlin
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
```

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.EmaPaceSmootherTest"`
Expected: FAIL to compile, "unresolved reference: EmaPaceSmoother".

- [ ] **Step 3: Write the implementation in `RunSessionStore.kt`**

Add the import (alphabetically, between the existing `kotlin.math.cos` and `kotlin.math.pow` at lines 26-27):

```kotlin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
```

Insert the new interface right after `interface SessionSpeedEngine { ... }` (current lines 203-206), before `class DopplerSpeedEngine` (current line 208):

```kotlin
interface SessionPaceSmoothingEngine {
    /** Fuses a new raw speed reading into the smoothed value, returns the updated value. */
    fun accept(rawSpeedKmh: Float, elapsedRealtimeMillis: Long): Float
    /** Discards all accumulated memory; the next [accept] restarts from scratch, from the raw value. */
    fun purge()
}
```

Add the implementation at the end of the file, right after `class KalmanSpeedEngine { ... }` (current lines 432-437):

```kotlin
/**
 * Time-normalized exponential moving average smoothing PaceEvaluator's speed input, so that
 * [timeConstantSeconds] means the same thing regardless of the actual gap between reliable fixes
 * (which isn't perfectly uniform -- unreliable fixes intermittently interrupt the stream). Not a
 * fixed-length sliding window -- mirrors the EMA mechanism SpeedEstimator already validates in
 * production for its own innovation tracking, rather than inventing a second smoothing primitive.
 *
 * timeConstantSeconds=6.0: derived from real pace-decision data during RUN phases (median gap
 * between consecutive confirmed pace-state changes: 6.5s across 130 samples, a single real run,
 * 2026-09-15) -- see docs/superpowers/specs/2026-09-18-phase-aware-pace-smoothing-design.md,
 * Decision 1, for the full distribution and this estimate's explicit limits (that run's RUN band
 * was 1.5 km/h -- more than double the 0.7 km/h band of the run that originally motivated this
 * work, so this estimate is likely on the generous side for a narrow-band run). A starting
 * estimate to revalidate on the next real run, not a final value -- same discipline as
 * SpeedEstimator's qHigh/innovationEmaAlpha, not a RunPreferences user setting.
 */
class EmaPaceSmoother(
    private val timeConstantSeconds: Double = 6.0,
) : SessionPaceSmoothingEngine {
    private var smoothedKmh: Double? = null
    private var lastElapsedRealtimeMillis: Long? = null

    override fun accept(rawSpeedKmh: Float, elapsedRealtimeMillis: Long): Float {
        val previousSmoothed = smoothedKmh
        val previousElapsed = lastElapsedRealtimeMillis
        lastElapsedRealtimeMillis = elapsedRealtimeMillis
        if (previousSmoothed == null || previousElapsed == null) {
            smoothedKmh = rawSpeedKmh.toDouble()
            return rawSpeedKmh
        }
        val dtSeconds = (elapsedRealtimeMillis - previousElapsed) / 1_000.0
        if (dtSeconds <= 0.0) return previousSmoothed.toFloat() // stale/duplicate tick, keep the previous value
        val alpha = 1.0 - exp(-dtSeconds / timeConstantSeconds)
        val next = alpha * rawSpeedKmh + (1.0 - alpha) * previousSmoothed
        smoothedKmh = next
        return next.toFloat()
    }

    override fun purge() {
        smoothedKmh = null
        lastElapsedRealtimeMillis = null
    }
}
```

No non-finite guard is needed here (unlike `SessionEventWriter`'s `jsonNumber`): `speedEngine.accept()` (both `KalmanSpeedEngine` and the `SpeedEstimator` it wraps) already guarantees it never returns a non-finite value — a non-finite result triggers `breakChain()` internally and returns `0.0` instead. `EmaPaceSmoother` can rely on that existing upstream guarantee without duplicating it.

- [ ] **Step 4: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.EmaPaceSmootherTest"`
Expected: PASS (all 4 tests).

- [ ] **Step 5: Full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt app/src/test/java/dev/fred/pacorun/service/EmaPaceSmootherTest.kt
git commit -m "feat: add EmaPaceSmoother, a time-normalized EMA for PaceEvaluator's speed input"
```

---

### Task 2: `SessionEvent.kt` + `SessionEventWriter.kt` — `rawSpeedKmh` schema addition

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `PaceStateConfirmedEvent.rawSpeedKmh: Float?` (default `null`, last field), serialized by `SessionEventWriter`. Consumed by Task 3's `PaceEvaluator.accept()`.

Adding the field to the data class alone is not enough — `SessionEventWriter.paceStateConfirmedEvent()` (`SessionEventWriter.kt`, current lines 116-123) is hand-written, field-by-field, not reflection-based, so a field that exists on the Kotlin class but isn't added to this function silently never reaches the JSONL file. Both changes belong in the same task so that gap can't happen.

- [ ] **Step 1: Write the failing test**

In `SessionEventWriterTest.kt`, update the existing `paceStateConfirmedEventSerializesNullFromStateAsJsonNull` test (current lines 49-63) to expect the new trailing field, and add a new test for a non-null value:

```kotlin
    @Test
    fun paceStateConfirmedEventSerializesNullFromStateAsJsonNull() {
        val event = PaceStateConfirmedEvent(
            elapsedRealtimeMillis = 1_000L,
            fromState = null,
            toState = dev.fred.pacorun.pace.PaceState.GOOD,
            speedKmh = 8f,
            producedAudibleCue = false,
        )

        assertEquals(
            "{\"type\":\"PaceStateConfirmedEvent\",\"elapsedRealtimeMillis\":1000," +
                "\"fromState\":null,\"toState\":\"GOOD\",\"speedKmh\":8.0,\"producedAudibleCue\":false," +
                "\"rawSpeedKmh\":null}",
            SessionEventWriter.toJsonLine(event),
        )
    }

    @Test
    fun paceStateConfirmedEventSerializesRawSpeedKmhWhenPresent() {
        val event = PaceStateConfirmedEvent(
            elapsedRealtimeMillis = 1_000L,
            fromState = dev.fred.pacorun.pace.PaceState.TOO_SLOW,
            toState = dev.fred.pacorun.pace.PaceState.GOOD,
            speedKmh = 7.8f,
            producedAudibleCue = true,
            rawSpeedKmh = 7.4f,
        )

        assertEquals(
            "{\"type\":\"PaceStateConfirmedEvent\",\"elapsedRealtimeMillis\":1000," +
                "\"fromState\":\"TOO_SLOW\",\"toState\":\"GOOD\",\"speedKmh\":7.8,\"producedAudibleCue\":true," +
                "\"rawSpeedKmh\":7.4}",
            SessionEventWriter.toJsonLine(event),
        )
    }
```

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.eventlog.SessionEventWriterTest"`
Expected: FAIL — `paceStateConfirmedEventSerializesNullFromStateAsJsonNull` fails on the string comparison (actual output has no `rawSpeedKmh` field yet), and `paceStateConfirmedEventSerializesRawSpeedKmhWhenPresent` fails to compile (`rawSpeedKmh` is not a constructor parameter of `PaceStateConfirmedEvent` yet).

- [ ] **Step 3: Add the field to `SessionEvent.kt`**

Current (lines 93-99):

```kotlin
data class PaceStateConfirmedEvent(
    override val elapsedRealtimeMillis: Long,
    val fromState: PaceState?,
    val toState: PaceState,
    val speedKmh: Float?,
    val producedAudibleCue: Boolean,
) : SessionEvent
```

New:

```kotlin
data class PaceStateConfirmedEvent(
    override val elapsedRealtimeMillis: Long,
    val fromState: PaceState?,
    val toState: PaceState,
    val speedKmh: Float?,
    val producedAudibleCue: Boolean,
    // Appended LAST with a default, not inserted before producedAudibleCue: this keeps every
    // existing positional PaceStateConfirmedEvent(...) call site (e.g. PaceEvaluatorTest.kt)
    // compiling -- inserting it earlier would silently shift producedAudibleCue's position.
    val rawSpeedKmh: Float? = null,
) : SessionEvent
```

- [ ] **Step 4: Serialize it in `SessionEventWriter.kt`**

Current (`paceStateConfirmedEvent()`, lines 116-123):

```kotlin
    private fun paceStateConfirmedEvent(event: PaceStateConfirmedEvent) = json {
        string("type", "PaceStateConfirmedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enumOrNull("fromState", event.fromState)
        enum("toState", event.toState)
        floatOrNull("speedKmh", event.speedKmh)
        boolean("producedAudibleCue", event.producedAudibleCue)
    }
```

New (one appended line, same order as the constructor):

```kotlin
    private fun paceStateConfirmedEvent(event: PaceStateConfirmedEvent) = json {
        string("type", "PaceStateConfirmedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enumOrNull("fromState", event.fromState)
        enum("toState", event.toState)
        floatOrNull("speedKmh", event.speedKmh)
        boolean("producedAudibleCue", event.producedAudibleCue)
        floatOrNull("rawSpeedKmh", event.rawSpeedKmh)
    }
```

- [ ] **Step 5: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.eventlog.SessionEventWriterTest"`
Expected: PASS (all tests, old and new).

- [ ] **Step 6: Full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS. (`PaceEvaluatorTest.kt`'s positional `PaceStateConfirmedEvent(...)` calls still compile — the new field has a default — but may now fail at runtime on the equality assertion; that is Task 3's fix, not this task's.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt
git commit -m "feat: add rawSpeedKmh to PaceStateConfirmedEvent schema and its serializer"
```

---

### Task 3: `PaceEvaluator.accept()` — `rawSpeedKmh` passthrough parameter

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt` (`SessionPaceEvaluator` interface, `PaceEvaluatorAdapter`)
- Modify: `app/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt` (`FakePaceEvaluator` — minimal signature fix only; Task 4 extends it further)

**Interfaces:**
- Consumes: `PaceStateConfirmedEvent.rawSpeedKmh` (Task 2).
- Produces: `PaceEvaluator.accept(sample: LocationSample, rawSpeedKmh: Float? = sample.speedKmh): PaceResult` and the matching `SessionPaceEvaluator.accept(...)` interface signature — both consumed by Task 4's `SessionCoordinator` wiring.

**Why the default is `sample.speedKmh`, not `null`:** a caller that doesn't pass `rawSpeedKmh` explicitly (every existing call site outside `SessionCoordinator`, including most of `PaceEvaluatorTest.kt`) should keep observing exactly the old behavior — `PaceStateConfirmedEvent.speedKmh` and `.rawSpeedKmh` both equal to the value the caller actually passed in as `sample.speedKmh`, since before this change there was no distinction between "smoothed" and "raw" at all. Only `SessionCoordinator` (Task 4) ever passes a genuinely different value for the two.

- [ ] **Step 1: Write the failing tests**

In `PaceEvaluatorTest.kt`, fix the existing broken assertion (current lines 195-210) and extend the one right after it (current lines 212-227) with a `rawSpeedKmh` check, then add a new test proving the parameter doesn't touch decision logic:

```kotlin
    @Test
    fun confirmingAPaceChangeEmitsPaceStateConfirmedEventWithFromAndToState() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        evaluator.accept(sample(8.7f, 0L))
        evaluator.accept(sample(8.8f, 1_000L)) // confirms TOO_FAST, fromState null (first confirmation)

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.PaceCandidateStartedEvent(0L, PaceState.TOO_FAST),
                // rawSpeedKmh defaults to sample.speedKmh when the caller (this test) doesn't pass
                // it explicitly -- both fields equal 8.8f here, same as pre-P2 behavior.
                dev.fred.pacorun.eventlog.PaceStateConfirmedEvent(1_000L, null, PaceState.TOO_FAST, 8.8f, true, rawSpeedKmh = 8.8f),
            ),
            events,
        )
    }

    @Test
    fun goodPaceAfterConfirmedTooFastProducesAnAudibleCueMarkedTrue() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        evaluator.accept(sample(8.7f, 0L))
        evaluator.accept(sample(8.8f, 1_000L)) // confirms TOO_FAST
        events.clear()
        evaluator.accept(sample(8f, 2_000L))
        evaluator.accept(sample(8f, 3_000L)) // confirms GOOD, previous == TOO_FAST -> audible

        val confirmed = events.filterIsInstance<dev.fred.pacorun.eventlog.PaceStateConfirmedEvent>().single()
        assertEquals(PaceState.TOO_FAST, confirmed.fromState)
        assertEquals(PaceState.GOOD, confirmed.toState)
        assertEquals(true, confirmed.producedAudibleCue)
        assertEquals(8f, confirmed.rawSpeedKmh)
    }

    @Test
    fun rawSpeedKmhParameterFlowsToEventPayloadWithoutAffectingDecisionLogic() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        // sample.speedKmh is the (here: stand-in "smoothed") value PaceEvaluator decides on --
        // 8.7/8.8, both TOO_FAST. rawSpeedKmh is deliberately a different reading (9.1/9.4) to
        // prove decision logic ignores it entirely, only the event payload sees it.
        evaluator.accept(sample(8.7f, 0L), rawSpeedKmh = 9.1f)
        val result = evaluator.accept(sample(8.8f, 1_000L), rawSpeedKmh = 9.4f)

        assertEquals(PaceState.TOO_FAST, result.state) // decided from sample.speedKmh=8.8, not rawSpeedKmh
        val confirmed = events.filterIsInstance<dev.fred.pacorun.eventlog.PaceStateConfirmedEvent>().single()
        assertEquals(8.8f, confirmed.speedKmh) // decision input, unchanged behavior
        assertEquals(9.4f, confirmed.rawSpeedKmh) // the raw value, threaded through for the payload only
    }
```

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.pace.PaceEvaluatorTest"`
Expected: FAIL — `confirmingAPaceChangeEmitsPaceStateConfirmedEventWithFromAndToState` and `goodPaceAfterConfirmedTooFastProducesAnAudibleCueMarkedTrue` fail at runtime (comparing an expected `rawSpeedKmh` to an actual `null`); `rawSpeedKmhParameterFlowsToEventPayloadWithoutAffectingDecisionLogic` fails to compile (`accept()` doesn't take a second parameter yet).

- [ ] **Step 3: Change `PaceEvaluator.accept()`'s signature**

Current (`PaceEvaluator.kt`, lines 31 and 88-96):

```kotlin
    fun accept(sample: LocationSample): PaceResult {
```

```kotlin
        onEvent(
            PaceStateConfirmedEvent(
                elapsedRealtimeMillis = sample.elapsedRealtimeMillis,
                fromState = previous,
                toState = pace,
                speedKmh = sample.speedKmh,
                producedAudibleCue = paceCue != null,
            ),
        )
```

New:

```kotlin
    fun accept(sample: LocationSample, rawSpeedKmh: Float? = sample.speedKmh): PaceResult {
```

```kotlin
        onEvent(
            PaceStateConfirmedEvent(
                elapsedRealtimeMillis = sample.elapsedRealtimeMillis,
                fromState = previous,
                toState = pace,
                speedKmh = sample.speedKmh,
                producedAudibleCue = paceCue != null,
                rawSpeedKmh = rawSpeedKmh,
            ),
        )
```

(The rest of `accept()`'s body — the `when` on `paceState()`, `confirmationSamples` counting, `abandonCandidateIfAny`, cue selection — is untouched. `rawSpeedKmh` is read exactly once, at this `onEvent(...)` call.)

- [ ] **Step 4: Update `SessionPaceEvaluator` and `PaceEvaluatorAdapter` in `RunSessionStore.kt`**

Current (lines 198-201 and 427-430):

```kotlin
interface SessionPaceEvaluator {
    fun updateConfig(config: PaceConfig)
    fun accept(sample: LocationSample): PaceResult
}
```

```kotlin
class PaceEvaluatorAdapter(private val evaluator: PaceEvaluator) : SessionPaceEvaluator {
    override fun updateConfig(config: PaceConfig) = evaluator.updateConfig(config)
    override fun accept(sample: LocationSample) = evaluator.accept(sample)
}
```

New:

```kotlin
interface SessionPaceEvaluator {
    fun updateConfig(config: PaceConfig)
    fun accept(sample: LocationSample, rawSpeedKmh: Float? = sample.speedKmh): PaceResult
}
```

```kotlin
class PaceEvaluatorAdapter(private val evaluator: PaceEvaluator) : SessionPaceEvaluator {
    override fun updateConfig(config: PaceConfig) = evaluator.updateConfig(config)
    // Kotlin forbids default parameter values on an override -- the interface's default already
    // covers callers that omit the argument; this override just forwards whatever it receives.
    override fun accept(sample: LocationSample, rawSpeedKmh: Float?) = evaluator.accept(sample, rawSpeedKmh)
}
```

- [ ] **Step 5: Minimal compile fix for `FakePaceEvaluator` in `SessionCoordinatorTest.kt`**

Current (lines 647-661):

```kotlin
        override fun accept(sample: LocationSample): PaceResult {
            events += "pace"
            samples += sample
            return result
        }
```

New (signature only — Task 4 extends this fake further):

```kotlin
        override fun accept(sample: LocationSample, rawSpeedKmh: Float?): PaceResult {
            events += "pace"
            samples += sample
            return result
        }
```

- [ ] **Step 6: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.pace.PaceEvaluatorTest"`
Expected: PASS (all tests, old and new).

- [ ] **Step 7: Full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt app/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt
git commit -m "feat: add rawSpeedKmh parameter to PaceEvaluator.accept() for event-log payload only"
```

---

### Task 4: `SessionCoordinator`/`PendingSessionCoordinator` — wire the smoother and its three purge triggers

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt`

**Interfaces:**
- Consumes: `SessionPaceSmoothingEngine`/`EmaPaceSmoother` (Task 1), `SessionPaceEvaluator.accept(sample, rawSpeedKmh)` (Task 3), `RoutineCue.WALK_IN_TEN`/`RUN_IN_TEN` (`dev.fred.pacorun.routine.RoutineModels.kt`, unchanged).
- Produces: `SessionCoordinator`/`PendingSessionCoordinator` both gain `paceSmoother: SessionPaceSmoothingEngine = EmaPaceSmoother()`. Nothing later in this plan depends on this task's output beyond the final verification task.

**Current code, `SessionCoordinator.process()` (`RunSessionStore.kt`, verified on `feature/session-event-logging` tip — three places differ slightly from the spec's own line citations, noted below):**

The constructor (current lines 216-227):

```kotlin
class SessionCoordinator(
    private val preferences: RunPreferences,
    private val routineEngine: SessionRoutineEngine,
    private val paceEvaluator: SessionPaceEvaluator,
    private val onCues: (List<AudioCue>, Float) -> Unit,
    private val publish: (RunSessionState) -> Unit,
    private val startedAtElapsedMillis: Long = 0L,
    private val distanceEngine: SessionDistanceEngine = DistanceAccumulator(),
    private val speedEngine: SessionSpeedEngine = KalmanSpeedEngine(),
    private val onSpeedComputed: (Double?) -> Unit = {},
    private val onEvent: (SessionEvent) -> Unit = {},
) {
```

The stale-GPS branch (current lines 276-300 — the spec cites 276-300 too, this one matches exactly):

```kotlin
        } else if (
            !gpsReliable &&
            elapsedRealtimeMillis - lastReliableFixElapsedMillis >= LocationSampleMapper.DEFAULT_MAX_AGE_MILLIS
        ) {
            // Tempo REALE trascorso dall'ultimo fix affidabile, non "che tipo di chiamata ha
            // innescato la transizione" (il gate del Task 9 confondeva le due cose: un flusso
            // continuo di fix inaffidabili non passa mai da qui con signalGpsOutage=true, quindi
            // non si resettava mai, non importa quanto durasse -- vedi review Task 9).
            // Chiamare breakChain() più volte nello stesso episodio è innocuo (idempotente),
            // quindi non serve un flag "già fatto per questo episodio". Stessa soglia usata
            // altrove nel progetto per lo stesso concetto (non una soglia nuova). Vedi roadmap
            // G14 per il perché NON si allenta oltre questo (interruzioni lunghe + spostamento
            // reale nel frattempo restano un problema di Fase 1, deliberatamente non affrontato
            // qui).
            // speedEngine.breakChain() stays unconditional -- calling it repeatedly for the same
            // outage is deliberately idempotent (comment above, unchanged by this task). The
            // event is different: without a guard, onEvent(...) here would fire once per tick for
            // the entire outage. speedChainBrokenReportedForOutage makes it fire once per
            // episode, reset the moment a fresh reliable fix arrives above.
            if (!speedChainBrokenReportedForOutage) {
                onEvent(SpeedChainBrokenEvent(elapsedRealtimeMillis, SpeedChainBreakReason.STALE_NO_RECENT_RELIABLE_FIX))
                speedChainBrokenReportedForOutage = true
            }
            speedEngine.breakChain()
        }
```

The routine/profileChanged/pace-decision block (current lines 306-334 — the spec's Decision 5 concept diff does not cite exact line numbers here, so this is a from-scratch verification):

```kotlin
        val routine = routineEngine.accept(
            RoutineInput(
                elapsedRealtimeMillis = routineTimingElapsedRealtimeMillis(elapsedRealtimeMillis),
                reliableDistanceDeltaMeters = distanceDelta,
                currentReliableSpeedKmh = paceSample.speedKmh.takeIf { gpsReliable },
                gpsReliable = gpsReliable,
            ),
        )
        if (routine.profileChanged) {
            paceEvaluator.updateConfig(
                PaceConfig(
                    minKmh = routine.activeRange.minKmh,
                    maxKmh = routine.activeRange.maxKmh,
                    confirmationSamples = preferences.confirmationSamples,
                    repeatMillis = preferences.repeatMillis,
                    gpsCuesEnabled = preferences.gpsCuesEnabled,
                ),
            )
            latestPace = PaceResult(PaceState.ACQUIRING_GPS, null, null)
        }
        if (routine.profileChanged && wasOutage) {
            latestPace = paceEvaluator.accept(LocationSample(0f, null, false, elapsedRealtimeMillis)).copy(cue = null)
        }
        val pace = when {
            sample != null -> paceEvaluator.accept(paceSample)
            signalGpsOutage && enteredOutage -> paceEvaluator.accept(paceSample)
            else -> latestPace
        }
        latestPace = pace
```

- [ ] **Step 1: Write the failing tests**

Extend the two test doubles and add a third, then add 5 new tests, all in `SessionCoordinatorTest.kt`.

Extend `FakePaceEvaluator` (Task 3 already changed its signature; add raw-speed capture — current lines 647-661):

```kotlin
    private class FakePaceEvaluator(
        private val events: MutableList<String>,
        private val result: PaceResult,
    ) : SessionPaceEvaluator {
        val samples = mutableListOf<LocationSample>()
        val rawSpeeds = mutableListOf<Float?>()
        override fun updateConfig(config: PaceConfig) {
            events += "reset"
        }

        override fun accept(sample: LocationSample, rawSpeedKmh: Float?): PaceResult {
            events += "pace"
            samples += sample
            rawSpeeds += rawSpeedKmh
            return result
        }
    }
```

Extend `FakeRoutineEngine` with an optional `cues` parameter, default preserving today's behavior (current lines 624-645):

```kotlin
    private class FakeRoutineEngine(
        private val events: MutableList<String>,
        private val profileChanged: Boolean,
        private val cues: List<RoutineCue> = listOf(RoutineCue.ROUTINE_START),
    ) : SessionRoutineEngine {
        lateinit var lastInput: RoutineInput
        val inputs = mutableListOf<RoutineInput>()

        override fun accept(input: RoutineInput): RoutineResult {
            events += "routine"
            lastInput = input
            inputs += input
            return RoutineResult(
                phase = RoutinePhase.RUN,
                activeRange = SpeedRange(7f, 8f),
                intervalRemainingMillis = 4_000L,
                totalDistanceMeters = 3.0,
                routineDistanceMeters = 2.0,
                cues = cues,
                profileChanged = profileChanged,
            )
        }
    }
```

Add a new `FakePaceSmoother` (place it next to `FakeSpeedEngine`, further down in the file's private-class section):

```kotlin
    private class FakePaceSmoother(private val transform: (Float) -> Float = { it }) : SessionPaceSmoothingEngine {
        var purgeCalls = 0
            private set
        val acceptedRawSpeeds = mutableListOf<Float>()

        override fun accept(rawSpeedKmh: Float, elapsedRealtimeMillis: Long): Float {
            acceptedRawSpeeds += rawSpeedKmh
            return transform(rawSpeedKmh)
        }

        override fun purge() {
            purgeCalls++
        }
    }
```

New tests (add near the other purge/chain-break tests, e.g. after `aFreshReliableFixAfterAnOutageAllowsTheEventToFireAgainOnTheNextOutage`):

```kotlin
    @Test
    fun profileChangePurgesThePaceSmoother() {
        val routine = FakeRoutineEngine(mutableListOf(), profileChanged = true)
        val pace = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null))
        val smoother = FakePaceSmoother()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = pace,
            onCues = { _, _ -> },
            publish = {},
            speedEngine = DopplerSpeedEngine(),
            paceSmoother = smoother,
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))

        assertEquals(1, smoother.purgeCalls)
    }

    @Test
    fun earlyWalkInTenCuePurgesThePaceSmootherAheadOfTheRealPhaseChange() {
        val routine = FakeRoutineEngine(mutableListOf(), profileChanged = false, cues = listOf(RoutineCue.WALK_IN_TEN))
        val pace = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null))
        val smoother = FakePaceSmoother()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = pace,
            onCues = { _, _ -> },
            publish = {},
            speedEngine = DopplerSpeedEngine(),
            paceSmoother = smoother,
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))

        assertEquals(1, smoother.purgeCalls)
    }

    @Test
    fun staleGpsRecoveryPurgesThePaceSmootherWhenTheSpeedChainBreaks() {
        val routine = FakeRoutineEngine(mutableListOf(), profileChanged = false)
        val pace = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null))
        val smoother = FakePaceSmoother()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = pace,
            onCues = { _, _ -> },
            publish = {},
            speedEngine = FakeSpeedEngine(),
            paceSmoother = smoother,
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.tick(1_000L, unreliableSample(1_000L), LocationPosition(0.0, .0001, 1_000L))
        coordinator.tick(2_000L, unreliableSample(2_000L), LocationPosition(0.0, .0002, 2_000L))
        // DEFAULT_MAX_AGE_MILLIS is 3000ms -- the staleness gate first opens on this tick, same
        // setup as sustainedUnreliableStreamEmitsSpeedChainBrokenEventOnlyOnceAcrossTheWholeOutage.
        coordinator.tick(3_000L, unreliableSample(3_000L), LocationPosition(0.0, .0003, 3_000L))

        assertEquals(1, smoother.purgeCalls)
    }

    @Test
    fun paceEvaluatorReceivesTheSmoothedSpeedAsInputAndTheRawSpeedAsThePayloadParameter() {
        val routine = FakeRoutineEngine(mutableListOf(), profileChanged = false)
        val pace = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null))
        // Doubles every raw reading, so smoothed and raw are unmistakably different values.
        val smoother = FakePaceSmoother(transform = { it * 2f })
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = pace,
            onCues = { _, _ -> },
            publish = {},
            speedEngine = DopplerSpeedEngine(),
            paceSmoother = smoother,
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))

        assertEquals(listOf(8f), smoother.acceptedRawSpeeds) // the smoother itself always sees the raw reading
        assertEquals(16f, pace.samples.single().speedKmh) // PaceEvaluator decides on the SMOOTHED value
        assertEquals(8f, pace.rawSpeeds.single()) // ...but the raw value still threads through as the payload param
    }

    @Test
    fun smoothingNeverLeaksIntoTheDisplayedRoutineInputOrLoggedRawSpeedFields() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val routine = FakeRoutineEngine(mutableListOf(), profileChanged = false)
        val pace = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.TOO_FAST, 8f, Cue.TOO_FAST))
        val published = mutableListOf<RunSessionState>()
        // Doubles every raw reading -- if smoothing leaked into any of the three fields below,
        // they'd read 16f instead of the raw 8f and this test would catch it immediately.
        val smoother = FakePaceSmoother(transform = { it * 2f })
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = pace,
            onCues = { _, _ -> },
            publish = published::add,
            speedEngine = DopplerSpeedEngine(),
            paceSmoother = smoother,
            onEvent = events::add,
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))

        val state = published.single() as RunSessionState.Running
        assertEquals(8f, state.speedKmh) // RunSessionState.speedKmh: raw, per Decision 6
        assertEquals(8f, routine.lastInput.currentReliableSpeedKmh) // RoutineInput.currentReliableSpeedKmh: raw
        val cueEvent = events.filterIsInstance<dev.fred.pacorun.eventlog.AudioCueEmittedEvent>().first()
        assertEquals(8f, cueEvent.speedKmh) // AudioCueEmittedEvent.speedKmh: raw
    }
```

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorTest"`
Expected: FAIL to compile — `paceSmoother` is not a constructor parameter yet, `FakeRoutineEngine`'s `cues` parameter doesn't exist yet, `FakePaceSmoother`/`SessionPaceSmoothingEngine` don't exist as a constructor argument type yet on `SessionCoordinator`.

- [ ] **Step 3: Wire `paceSmoother` and the three purge triggers into `RunSessionStore.kt`**

Add the constructor parameter, right after `speedEngine` (current line 224):

```kotlin
class SessionCoordinator(
    private val preferences: RunPreferences,
    private val routineEngine: SessionRoutineEngine,
    private val paceEvaluator: SessionPaceEvaluator,
    private val onCues: (List<AudioCue>, Float) -> Unit,
    private val publish: (RunSessionState) -> Unit,
    private val startedAtElapsedMillis: Long = 0L,
    private val distanceEngine: SessionDistanceEngine = DistanceAccumulator(),
    private val speedEngine: SessionSpeedEngine = KalmanSpeedEngine(),
    private val paceSmoother: SessionPaceSmoothingEngine = EmaPaceSmoother(),
    private val onSpeedComputed: (Double?) -> Unit = {},
    private val onEvent: (SessionEvent) -> Unit = {},
) {
```

Purge trigger 4.3 (stale-GPS recovery) — add `paceSmoother.purge()` right after `speedEngine.breakChain()`:

```kotlin
            if (!speedChainBrokenReportedForOutage) {
                onEvent(SpeedChainBrokenEvent(elapsedRealtimeMillis, SpeedChainBreakReason.STALE_NO_RECENT_RELIABLE_FIX))
                speedChainBrokenReportedForOutage = true
            }
            speedEngine.breakChain()
            paceSmoother.purge()
        }
```

Purge trigger 4.2 (early `WALK_IN_TEN`/`RUN_IN_TEN` warning) and 4.1 (`profileChanged`), plus the smoothed-input wiring — replace the block from `val routine = routineEngine.accept(...)` through `latestPace = pace` (current lines 306-334):

```kotlin
        val routine = routineEngine.accept(
            RoutineInput(
                elapsedRealtimeMillis = routineTimingElapsedRealtimeMillis(elapsedRealtimeMillis),
                reliableDistanceDeltaMeters = distanceDelta,
                currentReliableSpeedKmh = paceSample.speedKmh.takeIf { gpsReliable },
                gpsReliable = gpsReliable,
            ),
        )
        // The RUN/WALK warning fires ~10s before the real transition, with no advance notice for
        // the CONSTANT_BEFORE->RUN warmup boundary -- purging here is speculative (spec Decision
        // 4.2: unverified on real data, a one-line change to remove if the next real run disproves
        // it), separate from the reactive 4.1 purge below.
        if (routine.cues.any { it == RoutineCue.WALK_IN_TEN || it == RoutineCue.RUN_IN_TEN }) {
            paceSmoother.purge()
        }
        if (routine.profileChanged) {
            paceEvaluator.updateConfig(
                PaceConfig(
                    minKmh = routine.activeRange.minKmh,
                    maxKmh = routine.activeRange.maxKmh,
                    confirmationSamples = preferences.confirmationSamples,
                    repeatMillis = preferences.repeatMillis,
                    gpsCuesEnabled = preferences.gpsCuesEnabled,
                ),
            )
            paceSmoother.purge()
            latestPace = PaceResult(PaceState.ACQUIRING_GPS, null, null)
        }
        if (routine.profileChanged && wasOutage) {
            latestPace = paceEvaluator.accept(LocationSample(0f, null, false, elapsedRealtimeMillis)).copy(cue = null)
        }
        // All three purge triggers above run before this point in the same tick, so a fresh raw
        // sample arriving on a purge tick is always the first post-purge sample -- returned raw,
        // per EmaPaceSmoother's own contract -- never fused into stale pre-purge memory first.
        val smoothedSpeedKmh = if (actualReliable) {
            paceSmoother.accept(paceSample.speedKmh, elapsedRealtimeMillis)
        } else {
            paceSample.speedKmh
        }
        val pace = when {
            sample != null -> paceEvaluator.accept(
                paceSample.copy(speedKmh = smoothedSpeedKmh),
                rawSpeedKmh = paceSample.speedKmh.takeIf { gpsReliable },
            )
            signalGpsOutage && enteredOutage -> paceEvaluator.accept(
                paceSample.copy(speedKmh = smoothedSpeedKmh),
                rawSpeedKmh = paceSample.speedKmh.takeIf { gpsReliable },
            )
            else -> latestPace
        }
        latestPace = pace
```

Everything after this (`routine.cues`/`AudioCueEmittedEvent`/`publish(...)`) is unchanged and continues to read `paceSample.speedKmh` (raw), never `smoothedSpeedKmh`.

Add the `RoutineCue` import (current imports at lines 16-19 already have `RoutineEngine`/`RoutineInput`/`RoutinePhase`/`RoutineResult` but not `RoutineCue`):

```kotlin
import dev.fred.pacorun.routine.RoutineCue
import dev.fred.pacorun.routine.RoutineEngine
```

- [ ] **Step 4: Thread `paceSmoother` through `PendingSessionCoordinator`**

Current (lines 378-421):

```kotlin
class PendingSessionCoordinator(
    private val preferences: RunPreferences,
    private val routineEngine: SessionRoutineEngine,
    private val paceEvaluator: SessionPaceEvaluator,
    private val onCues: (List<AudioCue>, Float) -> Unit,
    private val publish: (RunSessionState) -> Unit,
    private val distanceEngine: SessionDistanceEngine = DistanceAccumulator(),
    private val speedEngine: SessionSpeedEngine = KalmanSpeedEngine(),
    private val onSpeedComputed: (Double?) -> Unit = {},
    private val onEvent: (SessionEvent) -> Unit = {},
) {
    // ...
    private fun activate(startedAtElapsedMillis: Long): SessionCoordinator =
        SessionCoordinator(
            preferences = preferences,
            routineEngine = routineEngine,
            paceEvaluator = paceEvaluator,
            onCues = onCues,
            publish = publish,
            startedAtElapsedMillis = startedAtElapsedMillis,
            distanceEngine = distanceEngine,
            speedEngine = speedEngine,
            onSpeedComputed = onSpeedComputed,
            onEvent = onEvent,
        ).also { activeCoordinator = it }
}
```

New:

```kotlin
class PendingSessionCoordinator(
    private val preferences: RunPreferences,
    private val routineEngine: SessionRoutineEngine,
    private val paceEvaluator: SessionPaceEvaluator,
    private val onCues: (List<AudioCue>, Float) -> Unit,
    private val publish: (RunSessionState) -> Unit,
    private val distanceEngine: SessionDistanceEngine = DistanceAccumulator(),
    private val speedEngine: SessionSpeedEngine = KalmanSpeedEngine(),
    private val paceSmoother: SessionPaceSmoothingEngine = EmaPaceSmoother(),
    private val onSpeedComputed: (Double?) -> Unit = {},
    private val onEvent: (SessionEvent) -> Unit = {},
) {
    // ...
    private fun activate(startedAtElapsedMillis: Long): SessionCoordinator =
        SessionCoordinator(
            preferences = preferences,
            routineEngine = routineEngine,
            paceEvaluator = paceEvaluator,
            onCues = onCues,
            publish = publish,
            startedAtElapsedMillis = startedAtElapsedMillis,
            distanceEngine = distanceEngine,
            speedEngine = speedEngine,
            paceSmoother = paceSmoother,
            onSpeedComputed = onSpeedComputed,
            onEvent = onEvent,
        ).also { activeCoordinator = it }
}
```

`RunSessionService.kt:214-224`, the only production call site of `PendingSessionCoordinator(...)`, uses exclusively named arguments and does not need to change — it picks up the new `paceSmoother = EmaPaceSmoother()` default automatically.

- [ ] **Step 5: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorTest"`
Expected: PASS (all tests, old and new).

- [ ] **Step 6: Full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt
git commit -m "feat: wire EmaPaceSmoother into SessionCoordinator with three purge triggers"
```

---

### Task 5: Synthetic worst-case validation test (spec Decision 8.1)

**Files:**
- Modify: `app/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt`

**Interfaces:**
- Consumes: `PaceEvaluator` (unchanged decision logic), `EmaPaceSmoother` (Task 1).
- Produces: nothing consumed by later tasks — validation only.

This reproduces the run that *originally* motivated this whole investigation (2026-09-13: 0.7 km/h RUN band, 25 confirmed `TOO_SLOW`↔`TOO_FAST` direct flips) — not the wider 2026-09-15 run τ=6.0s was estimated from, which the spec itself flags as already-attenuated (Decision 1's "Il limite più importante"). No real per-tick data exists for the 2026-09-13 run (P1 didn't exist yet), so this is a deliberately-documented synthetic approximation of that worst case, in the style of `SpeedEstimatorTest.kt`'s existing synthetic-trajectory tests, not a replay of real data.

**Numeric bar, a plan-level refinement:** the spec states the qualitative acceptance criteria for this test (Decision 8.1: flips must drop "substantially" vs. baseline; lag must stay "a few seconds beyond the existing debounce, not tens of seconds") but does not fix exact numbers — explicitly left for the implementation task. This plan sets: baseline must reach ≥5 direct flips (proof the synthetic trajectory reproduces real flapping, not a fluke of the parameters), smoothing must cut that by at least half, and lag from a sustained step-change to confirmation must stay ≤15,000ms (comfortably above the ~7s theoretical minimum for these parameters, while still ruling out "tens of seconds").

- [ ] **Step 1: Write the failing tests**

Add near the bottom of `PaceEvaluatorTest.kt`, before the `sample()`/`unreliable()` helpers (current lines 330-343):

```kotlin
    @Test
    fun emaSmoothingSubstantiallyReducesDirectFlipsOnTheNarrowBandWorstCaseOscillation() {
        val config = PaceConfig(minKmh = 7.5f, maxKmh = 8.2f, confirmationSamples = 2, repeatMillis = 5_000L, gpsCuesEnabled = false)
        val trajectory = narrowBandOscillation(periodSeconds = 7.0, sampleCount = 90)

        val baselineFlips = confirmedDirectFlips(PaceEvaluator(config), trajectory, smoother = null)
        val smoothedFlips = confirmedDirectFlips(PaceEvaluator(config), trajectory, smoother = dev.fred.pacorun.service.EmaPaceSmoother(timeConstantSeconds = 6.0))

        // The baseline must actually reproduce repeated flapping on these parameters, or the
        // synthetic trajectory isn't representative of the real worst case (design doc Decision
        // 8.1's own instruction: if it doesn't flap, revise the parameters before drawing any
        // conclusion about tau). If this fails, adjust periodSeconds within the design doc's
        // specified 6-8s range before touching anything else.
        assertTrue("baseline should flap repeatedly on a 0.7 km/h band, got $baselineFlips", baselineFlips >= 5)
        // EMA at tau=6.0s must cut that flapping substantially, not just marginally.
        assertTrue(
            "smoothing should cut direct flips by at least half, got $baselineFlips -> $smoothedFlips",
            smoothedFlips <= baselineFlips / 2,
        )
    }

    @Test
    fun emaSmoothingConfirmsASustainedEffortChangeWithinABoundedLagBeyondTheExistingDebounce() {
        // Not an oscillation -- a genuine, sustained effort change: TOO_SLOW held steady for 20s,
        // then a real step up into the GOOD band, held steady. Decision 8.1's second criterion:
        // smoothing must not turn responsiveness into practical unresponsiveness.
        val config = PaceConfig(minKmh = 7.5f, maxKmh = 8.2f, confirmationSamples = 2, repeatMillis = 5_000L, gpsCuesEnabled = false)
        val evaluator = PaceEvaluator(config)
        val smoother = dev.fred.pacorun.service.EmaPaceSmoother(timeConstantSeconds = 6.0)

        var elapsedMillis = 0L
        repeat(20) {
            val decisionSpeed = smoother.accept(7.0f, elapsedMillis)
            evaluator.accept(sample(decisionSpeed, elapsedMillis), rawSpeedKmh = 7.0f)
            elapsedMillis += 1_000L
        }
        val stepElapsedMillis = elapsedMillis
        var confirmedAtMillis: Long? = null
        repeat(30) {
            val decisionSpeed = smoother.accept(7.85f, elapsedMillis)
            val result = evaluator.accept(sample(decisionSpeed, elapsedMillis), rawSpeedKmh = 7.85f)
            if (confirmedAtMillis == null && result.state == PaceState.GOOD) confirmedAtMillis = elapsedMillis
            elapsedMillis += 1_000L
        }

        val lagMillis = requireNotNull(confirmedAtMillis) { "GOOD was never confirmed after the step" } - stepElapsedMillis
        // Explicit bound (a plan-level refinement, the design doc left the exact number
        // unspecified): comfortably above the theoretical ~7s (5.3s for the EMA to re-enter the
        // band + up to 2s for the confirmationSamples debounce) while still ruling out "tens of
        // seconds" unresponsiveness.
        assertTrue("lag should stay bounded, was ${lagMillis}ms", lagMillis <= 15_000L)
    }

    /**
     * Reproduces the 2026-09-13 narrow-band run's approximate shape (see design doc Decision
     * 8.1): 7.19-9.38 km/h peak-to-peak oscillation (the one concrete per-second extract that
     * run's own validation doc reports), around a [periodSeconds] cycle, sampled at 1Hz -- not a
     * replay of real per-tick data (none exists for that run; P1 didn't exist yet).
     */
    private fun narrowBandOscillation(periodSeconds: Double, sampleCount: Int): List<Pair<Float, Long>> {
        val center = 8.285 // midpoint of the 7.19-9.38 km/h peak-to-peak extract
        val amplitude = 1.095 // (9.38 - 7.19) / 2
        return (0 until sampleCount).map { i ->
            val tSeconds = i.toDouble()
            val speedKmh = center + amplitude * kotlin.math.sin(2.0 * Math.PI * tSeconds / periodSeconds)
            speedKmh.toFloat() to (tSeconds * 1_000.0).toLong()
        }
    }

    private fun confirmedDirectFlips(
        evaluator: PaceEvaluator,
        trajectory: List<Pair<Float, Long>>,
        smoother: dev.fred.pacorun.service.SessionPaceSmoothingEngine?,
    ): Int {
        var previousState: PaceState? = null
        var directFlips = 0
        trajectory.forEach { (rawSpeedKmh, elapsedMillis) ->
            val decisionSpeed = smoother?.accept(rawSpeedKmh, elapsedMillis) ?: rawSpeedKmh
            val state = evaluator.accept(sample(decisionSpeed, elapsedMillis), rawSpeedKmh).state
            if (previousState != null && previousState != state &&
                (previousState == PaceState.TOO_SLOW && state == PaceState.TOO_FAST ||
                    previousState == PaceState.TOO_FAST && state == PaceState.TOO_SLOW)
            ) {
                directFlips++
            }
            previousState = state
        }
        return directFlips
    }
```

Add the missing import for `assertTrue`:

```kotlin
import org.junit.Assert.assertTrue
```

- [ ] **Step 2: Run the tests, verify they fail (or pass)**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.pace.PaceEvaluatorTest"`

Both new tests exercise only already-existing behavior (`PaceEvaluator` and `EmaPaceSmoother` from Tasks 1/3, unchanged here) — they should compile and PASS immediately. If `emaSmoothingSubstantiallyReducesDirectFlipsOnTheNarrowBandWorstCaseOscillation`'s baseline assertion (`baselineFlips >= 5`) fails, the synthetic trajectory isn't reproducing real flapping on these parameters — adjust `periodSeconds` within the spec's stated 6-8s range (try 6.0 or 8.0) and re-run before concluding anything about τ, per the spec's own explicit instruction for this scenario.

- [ ] **Step 3: Full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt
git commit -m "test: add synthetic narrow-band worst-case validation for EmaPaceSmoother tau=6.0s"
```

**On the sensitivity-analysis table (spec Decision 8.2) — deliberately not a task here:** the spec's Decision 8.2 table is a completed, offline, proxy analysis of the existing 2026-09-15 P1 log, already printed in the spec document itself — not an app feature, and this plan ships no in-app code for it, per the brief. This plan also does not add a committed analysis script to the repository: there is no existing `scripts/`/`tools/` directory or precedent for committing this kind of throwaway analysis code in this project (verified — `git log` and a repo-wide search found none); every prior ad-hoc script in this project's history (including the one behind Decision 8.2 itself, and P1's own log analysis) lived only in the scratchpad, never in the repo. Re-running the analysis with real per-tick `rawSpeedKmh` data after the next field run (once Tasks 1-4 are merged and that run happens) is exactly the kind of one-off `/tmp` scratchpad script this project already does without a plan task for it — not something this plan should invent repo machinery for.

---

### Task 6: Final verification and dual review

**Files:** none — checkpoint.

- [ ] **Step 1: Full suite from a clean state**

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Both must be clean.

- [ ] **Step 2: Self-review**

Re-read the full diff (`git diff feature/session-event-logging...HEAD` from within the `feature/pace-decision-smoothing` branch, once it exists) and confirm:
- `PaceEvaluator.kt`'s `when` on `paceState()`, `confirmationSamples` counting, `abandonCandidateIfAny`, and cue-selection logic (`repeatedCue`/`selectCue`) are byte-for-byte unchanged from `feature/session-event-logging` — only the `accept()` signature and the one `PaceStateConfirmedEvent(...)` call gained `rawSpeedKmh`.
- `RunSessionState.speedKmh`, `RoutineInput.currentReliableSpeedKmh`, and `AudioCueEmittedEvent.speedKmh` in `SessionCoordinator.process()` still read `paceSample.speedKmh`, never `smoothedSpeedKmh` — `git diff` on that hunk in `RunSessionStore.kt` should show no change to those three lines.
- All three purge triggers (`speedEngine.breakChain()` pairing, `WALK_IN_TEN`/`RUN_IN_TEN` cue check, `routine.profileChanged`) run before `paceSmoother.accept(...)` in the same tick — re-read the final `process()` body top to bottom to confirm the ordering, not just that each purge call exists somewhere.
- `EmaPaceSmoother`'s `timeConstantSeconds` default is `6.0`, and its doc comment doesn't overstate the estimate's certainty (re-read against spec Decision 1's own explicit caveats).
- No file outside this plan's File Structure table was touched (`git diff --stat`).
- `SpeedEstimator.kt`/`KalmanSpeedEngine`'s bodies are untouched (`git diff` on those files should be empty).

- [ ] **Step 3: Independent review (fresh subagent, no prior context)**

Ask the subagent to confirm specifically:
- (a) `EmaPaceSmoother.accept()` implements `alpha = 1 - exp(-dt/tau)` exactly, and returns the raw value unchanged (no blending) on the very first call and on the first call after `purge()`.
- (b) Every call site of `paceEvaluator.accept(...)` in `SessionCoordinator.process()` passes `rawSpeedKmh = paceSample.speedKmh.takeIf { gpsReliable }` — specifically confirm the `routine.profileChanged && wasOutage` branch's synthetic-unreliable-sample call is correctly left using the default (since that sample is never reliable, `rawSpeedKmh` is moot there) and wasn't accidentally left calling the old one-argument `accept(sample)` in a way that would silently drop the smoothed value.
- (c) `PaceStateConfirmedEvent.rawSpeedKmh` is serialized by `SessionEventWriter.paceStateConfirmedEvent()` (grep `floatOrNull("rawSpeedKmh"` — confirm it's actually called, not just present in a comment).
- (d) `FakePaceEvaluator`/`FakeRoutineEngine`/`FakePaceSmoother` in `SessionCoordinatorTest.kt` are the only test doubles modified for this plan's `SessionPaceEvaluator`/`SessionRoutineEngine`/`SessionPaceSmoothingEngine` interfaces — no other implementer of these three interfaces exists anywhere in `app/src/test` or `app/src/main` that this plan missed updating.

- [ ] **Step 4: Report to the owner, then stop**

Report: task count, one-line summary per task, the commit hash of the final commit, and the following open questions carried forward from the spec (unresolved by this plan, since they're explicitly the owner's calls per the spec's own "Domande aperte" section):
1. **τ=6.0s is a starting estimate from a single run, in an already-attenuated regime** (1.5 km/h RUN band, more than double the 0.7 km/h band that originally motivated this investigation) — needs validation on the next real run, especially one recorded with a narrower band. This plan's Task 5 adds a synthetic worst-case test and Decision 8.2's already-completed sensitivity table raises confidence in the meantime, but neither substitutes for real narrow-band data.
2. **The early purge on `WALK_IN_TEN`/`RUN_IN_TEN` (Task 4, spec Decision 4.2) is a plausible but unverified causal hypothesis** — no data today confirms real runners actually start changing pace during the 10s countdown rather than exactly at the phase change. Verify on the next real run by comparing `rawSpeedKmh` in the seconds immediately before/after each `RoutinePhaseChangedEvent`; if disproven, removing the trigger is a one-line change (delete the `if (routine.cues.any { ... }) paceSmoother.purge()` block).
3. **No second real event log exists yet** — this plan, like the design spec, is built on a single real run's data for the τ estimate. Doesn't block implementation (τ stays a revisable constant), but shouldn't be treated as more solid than it is.
4. **This plan assumed P2 forks from `feature/session-event-logging`** (per this plan's Branch section) — confirm before merging any of these branches.

**Do not merge, delete the branch, build an APK, or start P3/P4/P5 work.** Stop here for an explicit go-ahead, per this project's standing rule.
