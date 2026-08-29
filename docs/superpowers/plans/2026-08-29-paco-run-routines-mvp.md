# Paco Run Routine MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver an installable Pixel 9a APK supporting constant pace and distance-gated timed Walk–Run routines with reliable offline audio guidance.

**Architecture:** Keep the completed Android scaffold, typed settings, `PaceEvaluator`, and `LocationSampleMapper`. Add a pure `RoutineEngine` that selects the active speed profile and emits transition events, then connect it to a single foreground service that owns location, monotonic timing, distance accumulation, pace evaluation, and an ordered Media3 cue queue. Use a deliberately plain Compose UI for configuration and session control.

**Tech Stack:** Kotlin, AGP 9.1.1, compile/target SDK 37, Jetpack Compose, DataStore Preferences, Google Play Services Location, Media3 ExoPlayer, Coroutines/Flow, JUnit, Compose UI Test.

**Spec:** `docs/superpowers/specs/2026-08-29-paco-run-routines-design.md`

## Global Constraints

- Reuse completed commits through `1d3d1d1`: scaffold, settings base, pace evaluator, and location mapper.
- Product/application ID: `Paco Run` / `dev.fred.pacorun`; Pixel 9a only; min SDK 35; compile/target SDK 37.
- Functional APK is the priority. No Material 3 Expressive work or mockups in this increment.
- Modes: `CONSTANT` and `WALK_RUN`.
- Profiles: Constant, Run, Walk; Walk defaults to Constant via a toggle but can be independent.
- Run and Walk durations are independently configurable in seconds and each must be at least 1 second.
- Initial constant distance may be 0 km. Routine distance is either positive or unlimited.
- Routine starts with Run. Distance-limited routine ends immediately on crossing and switches to constant.
- Reliable GPS distance only; timer continues while GPS is weak, distance and pace evaluation do not.
- Existing pace behavior remains: confirmation default 2, repetition default 5 seconds, Good Pace on confirmed recovery.
- Audio phrases exactly: `Too slow`, `Too fast`, `Good pace`, `Bad GPS`, `Good GPS`, `Routine start`, `Run in ten`, `Walk in ten`, `Run routine`, `Walk routine`, `Routine end`, `Speed up`, `Slow down`; plus three short countdown beeps.
- Audio events are ordered and never silently overwritten.
- Persist configuration only; never persist route, samples, duration, distance, speed, or history.
- One active 1 Hz location request and one foreground service during a run; no wake lock unless physical evidence requires it.
- No account, cloud, maps, backend, database, analytics, ads, statistics, or multi-device compatibility work.

---

### Task 1: Expand typed routine preferences

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/settings/RunPreferences.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/settings/RunPreferencesStore.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/settings/RunPreferencesTest.kt`

**Interfaces:**
- Produces: `RoutineMode`, `SpeedRange`, expanded `RunPreferences`, and atomic `RunPreferencesStore.update(transform)`

- [ ] **Step 1: Write failing defaults and validation tests**

```kotlin
@Test fun routineDefaultsAreUsable() {
    val p = RunPreferences()
    assertEquals(RoutineMode.CONSTANT, p.mode)
    assertEquals(SpeedRange(7.5f, 8.5f), p.constantRange)
    assertTrue(p.walkUsesConstantRange)
    assertEquals(60, p.runSeconds)
    assertEquals(120, p.walkSeconds)
    assertEquals(0f, p.initialConstantKm)
    assertTrue(p.routineUnlimited)
    assertTrue(p.isValid)
}

@Test fun walkRunRequiresEveryActiveRangeAndPositiveDurations() {
    val base = RunPreferences(mode = RoutineMode.WALK_RUN, routineUnlimited = false, routineKm = 5f)
    assertFalse(base.copy(runSeconds = 0).isValid)
    assertFalse(base.copy(walkSeconds = 0).isValid)
    assertFalse(base.copy(routineKm = 0f).isValid)
    assertFalse(base.copy(walkUsesConstantRange = false, walkRange = SpeedRange(6f, 6f)).isValid)
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.RunPreferencesTest'`

Expected: compilation failure because routine types/fields do not exist.

- [ ] **Step 3: Implement the model**

```kotlin
enum class RoutineMode { CONSTANT, WALK_RUN }

data class SpeedRange(val minKmh: Float, val maxKmh: Float) {
    val isValid get() = minKmh.isFinite() && maxKmh.isFinite() && minKmh < maxKmh
}

data class RunPreferences(
    val mode: RoutineMode = RoutineMode.CONSTANT,
    val constantRange: SpeedRange = SpeedRange(7.5f, 8.5f),
    val runRange: SpeedRange = SpeedRange(7.5f, 8.5f),
    val walkUsesConstantRange: Boolean = true,
    val walkRange: SpeedRange = SpeedRange(4f, 6f),
    val initialConstantKm: Float = 0f,
    val runSeconds: Int = 60,
    val walkSeconds: Int = 120,
    val routineUnlimited: Boolean = true,
    val routineKm: Float = 5f,
    // retain confirmationSamples, repeatMillis, gpsCuesEnabled, cueVolume, dynamicColor
)
```

`isValid` always checks Constant; in Walk–Run also check Run, effective Walk, `initialConstantKm >= 0`, positive durations, and positive finite `routineKm` when limited.

- [ ] **Step 4: Update DataStore atomically**

Keep backwards-compatible defaults for existing keys. Add keys for all new fields and:

```kotlin
suspend fun update(transform: (RunPreferences) -> RunPreferences)
```

Implement it with one `dataStore.edit` transaction by decoding current preferences, applying the transform, and writing every configuration field. Existing small setters delegate to `update`.

- [ ] **Step 5: Verify GREEN and commit**

Run: `./gradlew testDebugUnitTest`

Expected: all JVM tests pass.

```bash
git add app/src/main/java/dev/fred/pacorun/settings app/src/test/java/dev/fred/pacorun/settings
git commit -m "feat: configure constant and walk-run routines"
```

---

### Task 2: Pure routine engine

**Files:**
- Create: `app/src/main/java/dev/fred/pacorun/routine/RoutineModels.kt`
- Create: `app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt`
- Test: `app/src/test/java/dev/fred/pacorun/routine/RoutineEngineTest.kt`

**Interfaces:**
- Consumes: expanded `RunPreferences`
- Produces: `RoutineEngine.accept(RoutineInput): RoutineResult`

- [ ] **Step 1: Define the public types**

```kotlin
enum class RoutinePhase { CONSTANT, CONSTANT_BEFORE, RUN, WALK, CONSTANT_AFTER }
enum class RoutineCue {
    ROUTINE_START, RUN_IN_TEN, WALK_IN_TEN,
    COUNTDOWN_BEEP, RUN_ROUTINE, WALK_ROUTINE, ROUTINE_END,
    SPEED_UP, SLOW_DOWN,
}

data class RoutineInput(
    val elapsedRealtimeMillis: Long,
    val reliableDistanceDeltaMeters: Double,
    val currentReliableSpeedKmh: Float?,
    val gpsReliable: Boolean,
)

data class RoutineResult(
    val phase: RoutinePhase,
    val activeRange: SpeedRange,
    val intervalRemainingMillis: Long?,
    val totalDistanceMeters: Double,
    val routineDistanceMeters: Double,
    val cues: List<RoutineCue>,
    val profileChanged: Boolean,
)
```

- [ ] **Step 2: Write failing timeline tests**

Tests must cover:

```kotlin
@Test fun zeroWarmupStartsRunImmediatelyWithRoutineStart()
@Test fun positiveWarmupStartsAtDistanceBoundary()
@Test fun runAndWalkAlternateAtIndependentDurations()
@Test fun tenSecondWarningAndFinalThreeBeepsFireOnce()
@Test fun shortIntervalsOmitTenSecondWarning()
@Test fun walkCanReuseConstantOrUseDedicatedRange()
@Test fun unlimitedRoutineNeverEntersConstantAfter()
@Test fun limitedRoutineEndsImmediatelyMidInterval()
@Test fun weakGpsAdvancesTimerButNotDistance()
@Test fun transitionAddsSpeedUpOrSlowDownAfterPhaseCue()
@Test fun largeTimeJumpEmitsOnlyCurrentTransitionStateWithoutCueStorm()
```

Use exact monotonic timestamps and distance deltas; assert cue order as lists.

- [ ] **Step 3: Verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.RoutineEngineTest'`

Expected: compilation failure because routine engine is absent.

- [ ] **Step 4: Implement deterministic transitions**

Rules:

- Constant mode always returns `CONSTANT` and no routine cues.
- Walk–Run begins `CONSTANT_BEFORE` unless warmup distance is zero.
- First Run emits `ROUTINE_START`; later Runs emit `RUN_ROUTINE`; Walk emits `WALK_ROUTINE`.
- At 10 seconds remaining emit one `*_IN_TEN`; at 3, 2, and 1 seconds emit one beep each.
- Time crossing can skip multiple boundaries, but do not replay stale warnings/beeps; arrive at the mathematically correct current phase and emit only the current phase transition cue.
- Append one direction cue after a phase cue using the current reliable speed and new range.
- Only `gpsReliable` positive finite distance deltas increment distances.
- Limited routine crossing takes priority over timed transitions and emits only `ROUTINE_END` plus an optional direction cue.

- [ ] **Step 5: Verify and commit**

Run:

```bash
./gradlew testDebugUnitTest --tests '*.RoutineEngineTest'
./gradlew testDebugUnitTest
```

Expected: all tests pass.

```bash
git add app/src/main/java/dev/fred/pacorun/routine app/src/test/java/dev/fred/pacorun/routine
git commit -m "feat: schedule walk-run routine phases"
```

---

### Task 3: Pace evaluator profile reset

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt`

**Interfaces:**
- Consumes: `SpeedRange` chosen by `RoutineEngine`
- Produces: `PaceEvaluator.updateConfig(config: PaceConfig)`

- [ ] **Step 1: Write failing reset tests**

```kotlin
@Test fun profileChangeDropsOldCandidatesRemindersAndQueuedCues() {
    val evaluator = PaceEvaluator(PaceConfig(4f, 6f, 2, 5_000L, true))
    evaluator.accept(LocationSample(7f, .1f, true, 0L))
    evaluator.updateConfig(PaceConfig(8f, 9f, 2, 5_000L, true))
    assertEquals(PaceState.ACQUIRING_GPS, evaluator.current.state)
    assertNull(evaluator.accept(LocationSample(7f, .1f, true, 1_000L)).cue)
    assertEquals(Cue.TOO_SLOW, evaluator.accept(LocationSample(7f, .1f, true, 2_000L)).cue)
}
```

Add a second test that first queues a displaced pace cue through a GPS-recovery collision, calls `updateConfig`, and confirms the queued old-profile cue is never emitted.

- [ ] **Step 2: Verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.PaceEvaluatorTest*profileChange*'`

Expected: FAIL because `updateConfig` is absent.

- [ ] **Step 3: Implement reset**

`updateConfig` replaces config and clears candidate state, last-warning timestamp, pending cues, confirmed previous pace, and GPS episode state. Set `current` to `PaceResult(ACQUIRING_GPS, null, null)`.

- [ ] **Step 4: Verify and commit**

Run: `./gradlew testDebugUnitTest`

Expected: all tests pass.

```bash
git add app/src/main/java/dev/fred/pacorun/pace app/src/test/java/dev/fred/pacorun/pace
git commit -m "feat: reset pace tracking between routine profiles"
```

---

### Task 4: Complete ordered offline audio cues

**Files:**
- Reconcile existing uncommitted: `app/src/main/java/dev/fred/pacorun/audio/CuePlayer.kt`
- Reconcile existing uncommitted: `app/src/androidTest/java/dev/fred/pacorun/audio/CueAssetsTest.kt`
- Reconcile existing uncommitted: `app/src/main/res/raw/*`
- Add resources for all routine phrases and `countdown_beep.wav`

**Interfaces:**
- Consumes: pace `Cue` and `RoutineCue`
- Produces: `AudioCue`, `CuePlayer.enqueue(cues: List<AudioCue>, volume: Float)`, `close()`

- [ ] **Step 1: Inspect and preserve valid partial Task 5 work**

Review the uncommitted files left by the quota-interrupted agent. Keep correct assets/code; do not overwrite blindly. Record retained versus replaced pieces in the report.

- [ ] **Step 2: Generate missing deterministic assets**

Use `espeak-ng -v en-us -s 210 -w` for all phrase WAVs. Generate a brief neutral beep with `ffmpeg` sine source or `sox`; duration target 100–180 ms. All assets must be local and non-empty.

- [ ] **Step 3: Write failing asset/queue tests**

The instrumentation asset test opens every required raw resource. A JVM-testable queue helper verifies FIFO ordering and that enqueuing `[ROUTINE_START, SPEED_UP]` never replaces either item.

- [ ] **Step 4: Implement Media3 FIFO playback**

Use one ExoPlayer with navigation-guidance/speech audio attributes and focus handling. On `STATE_ENDED`, play the next queued item; `enqueue` appends; volume clamps to `0f..1f`; `close` clears queue and releases exactly once.

- [ ] **Step 5: Verify and commit**

Run:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.fred.pacorun.audio.CueAssetsTest
```

Expected: tests pass; all assets package successfully.

```bash
git add app/src/main/java/dev/fred/pacorun/audio app/src/test app/src/androidTest/java/dev/fred/pacorun/audio app/src/main/res/raw
git commit -m "feat: queue offline routine audio guidance"
```

---

### Task 5: Foreground session orchestration and reliable distance

**Files:**
- Create: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`
- Create: `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/drawable/ic_run.xml`
- Test: `app/src/test/java/dev/fred/pacorun/service/DistanceAccumulatorTest.kt`
- Test: `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt`

**Interfaces:**
- Consumes: preferences snapshot, `LocationSampleMapper`, reliable positions, `RoutineEngine`, `PaceEvaluator`, `CuePlayer`
- Produces: `RunSessionStore.state: StateFlow<RunSessionState>` and `ACTION_START`/`ACTION_STOP`

- [ ] **Step 1: Test reliable distance accumulation**

Define a small `DistanceAccumulator` that accepts timestamped latitude/longitude only when the mapped sample is reliable. Tests assert first fix adds zero, reliable consecutive fixes add `Location.distanceBetween` equivalent distance, unreliable fixes break the chain, and the first reliable fix after a break becomes a new anchor and adds zero.

- [ ] **Step 2: Test coordinator ordering**

With fakes for engines/audio, assert each 1 Hz tick:

1. computes reliable distance delta;
2. advances `RoutineEngine` even with GPS weak;
3. on `profileChanged`, resets `PaceEvaluator` before passing any sample;
4. skips pace accept when GPS weak;
5. enqueues routine cues before pace cues;
6. publishes phase, speed, remaining time, and distances.

- [ ] **Step 3: Implement store and coordinator**

Expose Idle, AcquiringGps, and Running states. Never write Running state to DataStore. Use `SystemClock.elapsedRealtime()` for every timer.

- [ ] **Step 4: Implement the foreground service**

Declare fine location, notifications, foreground service, and foreground location permissions/type. On start, snapshot valid preferences, call `startForeground` immediately, request high accuracy at 1,000 ms with max delay 1,000 ms, and start a lightweight one-second ticker so routine transitions continue if location callbacks pause. Deduplicate ticks by monotonic timestamp so a location and timer callback cannot advance the same logical second twice.

On stop/destroy remove callback/ticker, close player, publish Idle, stop foreground, and stop self. Notification shows phase and has `Termina` action. No background-location permission and no wake lock.

- [ ] **Step 5: Verify service behavior**

Run JVM tests, assemble, then start/stop on emulator and inspect:

```bash
./gradlew testDebugUnitTest assembleDebug
adb shell dumpsys activity services dev.fred.pacorun
adb shell dumpsys location
```

Expected: one service/request while active; none after stop.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/dev/fred/pacorun/service app/src/main/res/drawable app/src/test/java/dev/fred/pacorun/service
git commit -m "feat: run timed routines in foreground"
```

---

### Task 6: Minimal functional Compose UI

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/MainActivity.kt`
- Create: `app/src/main/java/dev/fred/pacorun/PacoRunApp.kt`
- Create: `app/src/main/java/dev/fred/pacorun/ui/SetupScreen.kt`
- Create: `app/src/main/java/dev/fred/pacorun/ui/ActiveRunScreen.kt`
- Test: `app/src/androidTest/java/dev/fred/pacorun/ui/SetupScreenTest.kt`

**Interfaces:**
- Consumes: `RunPreferencesStore`, `RunSessionStore`, service actions, runtime permissions
- Produces: configure/start/stop flow

- [ ] **Step 1: Write failing UI tests**

Assert Constant/Walk–Run selector, all approved routine inputs, conditional Walk/routine-distance fields, validation-disabled `Avvia`, and active screen fields/`Termina`.

- [ ] **Step 2: Implement one scrollable setup form**

Use standard Compose Material 3 controls. Minutes/seconds edit the stored total seconds. Hide Walk range when reusing Constant. Hide routine distance when unlimited. Show validation errors inline. No navigation library is necessary for two conditional screens; switch on `RunSessionStore.state`.

- [ ] **Step 3: Implement permissions and start/stop**

Request precise location and notifications while Activity is visible. Reject approximate-only access clearly. Start service only after valid settings and permissions. `Termina` sends stop.

- [ ] **Step 4: Show live essentials**

Active screen displays phase, speed or `--`, interval remaining, total distance, routine distance, GPS status, and `Termina`. Keep screen state simple; it may sleep normally.

- [ ] **Step 5: Verify and commit**

Run:

```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.fred.pacorun/.MainActivity
```

Expected: tests/build/install pass and complete form is usable on Pixel 9a emulator.

```bash
git add app/src/main/java/dev/fred/pacorun app/src/androidTest/java/dev/fred/pacorun/ui
git commit -m "feat: add minimal routine controls"
```

---

### Task 7: Accelerated acceptance and Pixel APK

**Files:**
- Create: `docs/testing/routine-emulator-checklist.md`
- Create: `docs/testing/pixel-9a-install.md`
- Modify: only components shown defective by acceptance evidence

**Interfaces:**
- Consumes: finished debug application and Pixel 9a emulator/physical device
- Produces: verified APK path and exact installation instructions

- [ ] **Step 1: Run full clean verification**

```bash
./gradlew clean testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug
```

Expected: exit 0 with no failed tests or lint errors.

- [ ] **Step 2: Run accelerated routine scenarios**

Use 12-second Run, 15-second Walk, zero warmup, and unlimited routine to verify warning/beeps/transitions quickly. Then use a short GPS route with positive warmup and limited routine distance to verify `Routine start` and `Routine end`. Turn screen off during both.

- [ ] **Step 3: Verify coexistence and cleanup**

Run music during cues. Confirm brief ducking, session continuation with screen off, notification stop, and zero Paco Run location requests/services afterward. Record results in `routine-emulator-checklist.md`.

- [ ] **Step 4: Produce installation handoff**

Document:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Include permission steps, battery-optimization caveat, and a short outdoor test checklist running Paco Run + the usual fitness app + music.

- [ ] **Step 5: Final commit**

```bash
git add docs/testing app
git commit -m "test: verify routine MVP on Pixel emulator"
```
