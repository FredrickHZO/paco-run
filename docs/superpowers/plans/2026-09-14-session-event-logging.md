# Session event logging (P1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the structured `SessionEvent` JSON-Lines log — 12 event types capturing every cue/phase/pace-state/chain-break decision Paco Run makes during a run, written incrementally to disk as they happen — so the next real validation runs are fully observable instead of requiring reconstruction from raw GPX + verbally-reported config.

**Architecture:** A new, Android-free `dev.fred.pacorun.eventlog` package holds the `SessionEvent` sealed interface (12 concrete types) and a hand-written JSON Lines serializer (`SessionEventWriter`, same style as `GpxWriter`'s hand-written XML — no serialization library). Every domain class that already threads a lambda through its constructor (`SessionCoordinator`'s `onCues`/`publish`/`onSpeedComputed`, matching the pattern established in `feature/speed-production-wiring`) gains one more: `onEvent: (SessionEvent) -> Unit = {}`, propagated into `RoutineEngine`, `PaceEvaluator`, `SpeedEstimator`, `DistanceAccumulator`. `RunSessionService` (the Android "collegamento" layer) is the only place that constructs the four Android-only event types (`SessionStarted`, `LocationFixEvent`, `GpsSilentGapEvent`, `DeviceHealthSampledEvent`) and the one dual-source type (`AppLifecycleChangedEvent`, also emitted by `PacoRunApp.kt`'s existing lifecycle observer), and owns the single `SessionEventLogger` that keeps one `MediaStore` output stream open per session, appending one line per event (Decision 8: incremental, not buffered-to-end-of-session).

**Tech Stack:** Kotlin, JUnit4, Gradle (`./gradlew testDebugUnitTest`, `lintDebug`).

**Spec:** `docs/superpowers/specs/2026-09-14-session-event-logging-design.md` (commit `aff13bd` on `feature/paco-run-m3e`), cross-referenced against `docs/superpowers/specs/2026-09-11-precision-roadmap.md`'s P1/P4 "Stato delle decisioni" entries for scope/ordering context.

## Branch — read before Task 1

**`feature/paco-run-m3e` does NOT have `SessionSpeedEngine`/`KalmanSpeedEngine`/`onSpeedComputed` merged** — that work is fully implemented, tested, and dual-reviewed on a separate branch, `feature/speed-production-wiring`, held back from merging so the owner can field-test the Kalman engine standalone first. P1's entire purpose is to make the *next* field-validation run observable, and that run will use the Kalman engine — so **this plan's branch must fork from `feature/speed-production-wiring`, not from `feature/paco-run-m3e`**, giving the next field run both the Kalman engine and event logging together without forcing a premature merge decision.

**Branch/worktree to create (not created by this plan — a subsequent step, authorized separately by the owner, per `superpowers:using-git-worktrees`):** `feature/session-event-logging`, forked from `feature/speed-production-wiring` (tip at the time this plan was written: `a2ec63b`).

Every file path, line reference, and current-code snippet quoted in this plan was read from `feature/speed-production-wiring`'s tip, not from `feature/paco-run-m3e`. In particular: `SessionCoordinator`'s `onSpeedComputed: (Double?) -> Unit = {}` parameter (from Fase 0.5) already exists on that branch — this plan's new `onEvent` parameter sits alongside it, it does not invent it.

## Global Constraints

- **Zero behavior change to anything not explicitly listed below.** Every existing test must stay green without modification unless a task says otherwise.
- **`onEvent` defaults to `{}` everywhere.** No constructor's existing callers (test or production) break by this addition alone.
- **`DistanceAccumulator`'s `IMPLAUSIBLE_JUMP` → `breakChain()` cleanup (spec 5.5) is in scope**, as a named prerequisite — done together with that class's `onEvent` wiring in Task 3, not as a separate no-op refactor.
- **`RoutineEngine`'s core phase-transition math is NOT touched beyond adding `onEvent(...)` calls at the existing three `phase = ...` assignment sites.** The deeper fix — `advanceTo()` can only represent up to two reassignments per call, so a multi-cycle-skip loses intermediate phases — is **P4**, tracked separately in the roadmap, explicitly scheduled right after P1. Do not fold it in here.
- **P2 (phase-aware cue smoothing) and P3 (the `GOOD_PACE`-after-phase-change suppression bug) are separate, independent work.** Not touched by this plan, even though `PaceStateConfirmedEvent` (Task 5) is exactly what will make P3's bug visible in the log.
- **No new serialization library.** `SessionEventWriter` is hand-written JSON, matching `GpxWriter`'s hand-written XML (spec Decision 3) — no `kotlinx-serialization`/`moshi`/`gson`.
- **Verification is unit-test-only (plain JUnit4, no Robolectric, no instrumented tests).** For pure-Kotlin logic (event types, serialization, non-finite guarding, the domain-class wiring) write real JVM tests. For the Android-only wiring into `RunSessionService`/`PacoRunApp.kt` — not unit-testable without Robolectric, same situation as the existing GPX export code — no dedicated test, verified by the full suite staying green + lint + manual/field verification, matching Task 6 of `2026-09-13-speed-production-wiring.md`.
- **Stop at the final task.** No merge, no branch deletion, no APK build, no work on P2/P3/P4 — matching every prior plan in this project.

---

## File Structure

| File | Role | Task |
|---|---|---|
| `app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt` | New: `SessionEvent` sealed interface, all 12 concrete event types, supporting enums/data classes | 1 |
| `app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt` | New: hand-written JSON Lines serializer, non-finite guard | 2 |
| `app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt` | New: serializer tests | 2 |
| `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt` | Modify: `DistanceAccumulator` (prerequisite + `onEvent`), `SessionCoordinator`/`PendingSessionCoordinator` (`onEvent`, `distanceEngine` param), new `GpsSilentGapTracker` | 3, 7, 15 |
| `app/src/test/java/dev/fred/pacorun/service/DistanceAccumulatorTest.kt` | Modify: new `onEvent` tests | 3 |
| `app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt` | Modify: `onEvent` wiring for `SpeedChainBrokenEvent`, new diagnostics accessor | 4 |
| `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt` | Modify: new `onEvent` tests | 4 |
| `app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt` | Modify: `onEvent` wiring for the 3 pace event types | 5 |
| `app/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt` | Modify: new `onEvent` tests | 5 |
| `app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt` | Modify: `onEvent` wiring for `RoutinePhaseChangedEvent` | 6 |
| `app/src/test/java/dev/fred/pacorun/routine/RoutineEngineTest.kt` | Modify: new `onEvent` tests, incl. multi-transition | 6 |
| `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt` | Modify: new `onEvent` tests | 7 |
| `app/src/main/java/dev/fred/pacorun/export/GpxWriter.kt` | Modify: `TrackPoint.elapsedRealtimeMillis` correlation field | 8 |
| `app/src/test/java/dev/fred/pacorun/export/GpxWriterTest.kt` | Modify: new field test | 8 |
| `app/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt` | Modify: `LocationReliability`/`RejectionReason` list, `qualityTier()` | 9 |
| `app/src/test/java/dev/fred/pacorun/location/LocationSampleMapperTest.kt` | Modify: new tests | 9 |
| `app/src/test/java/dev/fred/pacorun/service/GpsSilentGapTrackerTest.kt` | New: tracker tests | 15 |
| `app/src/main/java/dev/fred/pacorun/service/SessionEventLogger.kt` | New: Android `MediaStore` incremental JSONL writer | 11 |
| `app/src/main/java/dev/fred/pacorun/export/GpxExporter.kt` | Modify: `eventLogFileNameFor()` | 11 |
| `app/build.gradle.kts` | Modify: `buildConfig = true`, `GIT_COMMIT_HASH` field | 12 |
| `app/src/main/AndroidManifest.xml` | Modify: `ACCESS_NETWORK_STATE` permission, new intent constants no-op (actions are internal) | 12 |
| `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt` | Modify: device/sensor/network capture, `SessionStarted`, `SessionEventLogger` lifecycle, `LocationFixEvent`, `GpsSilentGapEvent`, `DeviceHealthSampledEvent`, `AppLifecycleChangedEvent` handling | 13, 14, 15, 16 |
| `app/src/main/java/dev/fred/pacorun/PacoRunApp.kt` | Modify: lifecycle observer sends `ACTION_APP_FOREGROUNDED`/`BACKGROUNDED` while a session is running | 16 |

---

### Task 1: `SessionEvent.kt` — the schema

**Files:**
- New: `app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt`

**Interfaces:**
- Consumes: `dev.fred.pacorun.routine.RoutinePhase`, `dev.fred.pacorun.pace.PaceState`, `dev.fred.pacorun.settings.RunPreferences` — all already pure Kotlin (verified: none of the three files import `android.*`), reused directly rather than duplicated (same principle the spec already applies to `RunPreferences` in section 5.1).
- Produces: `sealed interface SessionEvent { val elapsedRealtimeMillis: Long }` and its 12 implementations, plus supporting types `SensorAvailability`, `NetworkType`, `RejectionReason`, `QualityTier`, `SpeedEstimatorDiagnostics`, `ProcessNoiseRegime`, `PaceCandidateAbandonReason`, `SpeedChainBreakReason`, `DistanceChainBreakReason`, `LifecycleTransition`, `ThermalStatus` — all consumed by every later task.

Pure data declarations, no behavior — same category as `RoutineModels.kt`/`PaceModels.kt`, neither of which has a dedicated test file in this project. No TDD cycle for this task; correctness is verified by Task 2's serializer tests exercising every field kind, and by the full suite compiling.

**Plan-level refinement beyond the spec:** `PaceCandidateAbandonReason` carries a third value, `SUPERSEDED_BY_NEW_CANDIDATE`, that `docs/superpowers/specs/2026-09-14-session-event-logging-design.md` (5.4) does not enumerate — the spec names only `SNAPPED_BACK_TO_CONFIRMED`/`GPS_BECAME_UNRELIABLE`. It was added during review of this plan to instrument a third, previously-unacknowledged candidate-abandon path in `PaceEvaluator.accept()` (direct candidate-to-candidate supersession, without ever passing through a confirmed state) — see Task 5's Decision note and Task 17's final report for the rationale. Flagged here explicitly so this isn't a silent divergence from the spec.

- [ ] **Step 1: Write the file**

```kotlin
package dev.fred.pacorun.eventlog

import dev.fred.pacorun.pace.PaceState
import dev.fred.pacorun.routine.RoutinePhase
import dev.fred.pacorun.settings.RunPreferences

sealed interface SessionEvent {
    val elapsedRealtimeMillis: Long
}

// --- 5.1: one-time session start ---

data class SessionStarted(
    override val elapsedRealtimeMillis: Long,
    val deviceModel: String,
    val osVersionRelease: String,
    val osApiLevel: Int,
    val appGitCommitHash: String,
    val preferences: RunPreferences,
    val sensors: SensorAvailability,
    val networkTypeAtStart: NetworkType,
) : SessionEvent

data class SensorAvailability(
    val hasBarometer: Boolean,
    val hasMagnetometer: Boolean,
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val hasStepCounter: Boolean,
    val hasStepDetector: Boolean,
)

enum class NetworkType { WIFI, CELLULAR, OFFLINE, OTHER }

// --- 5.2: one per raw GPS fix ---

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
) : SessionEvent

enum class RejectionReason {
    MISSING_OR_INVALID_SPEED,
    MISSING_OR_INVALID_SPEED_ACCURACY,
    SPEED_ACCURACY_TOO_HIGH,
    HORIZONTAL_ACCURACY_TOO_HIGH,
    FIX_TOO_OLD,
}

enum class QualityTier { OTTIMA, BUONA, DEGRADATA, SCARSA }

data class SpeedEstimatorDiagnostics(
    val processNoiseRegime: ProcessNoiseRegime,
    val accuracyGateEngaged: Boolean,
    val innovationEmaMagnitude: Double,
)

enum class ProcessNoiseRegime { Q_LOW, Q_HIGH }

// --- 5.3: reception silence ---

data class GpsSilentGapEvent(
    override val elapsedRealtimeMillis: Long,
    val gapMillis: Long,
) : SessionEvent

// --- 5.4: cue/routine/pace discrete events ---

data class AudioCueEmittedEvent(
    override val elapsedRealtimeMillis: Long,
    val cueType: String,
    val cumulativeDistanceMeters: Double,
    val speedKmh: Float?,
) : SessionEvent

data class RoutinePhaseChangedEvent(
    override val elapsedRealtimeMillis: Long,
    val fromPhase: RoutinePhase,
    val toPhase: RoutinePhase,
    val cumulativeDistanceMeters: Double,
) : SessionEvent

data class PaceStateConfirmedEvent(
    override val elapsedRealtimeMillis: Long,
    val fromState: PaceState?,
    val toState: PaceState,
    val speedKmh: Float?,
    val producedAudibleCue: Boolean,
) : SessionEvent

data class PaceCandidateStartedEvent(
    override val elapsedRealtimeMillis: Long,
    val candidateState: PaceState,
) : SessionEvent

data class PaceCandidateAbandonedEvent(
    override val elapsedRealtimeMillis: Long,
    val candidateState: PaceState,
    val samplesAccumulated: Int,
    val reason: PaceCandidateAbandonReason,
) : SessionEvent

enum class PaceCandidateAbandonReason { SNAPPED_BACK_TO_CONFIRMED, GPS_BECAME_UNRELIABLE, SUPERSEDED_BY_NEW_CANDIDATE }

// --- 5.5: chain-break events ---

data class SpeedChainBrokenEvent(
    override val elapsedRealtimeMillis: Long,
    val reason: SpeedChainBreakReason,
) : SessionEvent

enum class SpeedChainBreakReason {
    UNRELIABLE_OR_INVALID_FIX,
    NON_POSITIVE_TIME_DELTA,
    NON_FINITE_RESULT,
    STALE_NO_RECENT_RELIABLE_FIX,
}

data class DistanceChainBrokenEvent(
    override val elapsedRealtimeMillis: Long,
    val reason: DistanceChainBreakReason,
) : SessionEvent

enum class DistanceChainBreakReason {
    UNRELIABLE_OR_INVALID_FIX,
    IMPLAUSIBLE_JUMP,
    GPS_OUTAGE_ENTERED,
}

// --- 5.6: environment/device events ---

data class AppLifecycleChangedEvent(
    override val elapsedRealtimeMillis: Long,
    val transition: LifecycleTransition,
) : SessionEvent

enum class LifecycleTransition { BACKGROUNDED, FOREGROUNDED, SERVICE_RESTARTED_BY_SYSTEM }

data class DeviceHealthSampledEvent(
    override val elapsedRealtimeMillis: Long,
    val batteryPercent: Int?,
    val thermalStatus: ThermalStatus,
) : SessionEvent

enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNKNOWN }
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileDebugKotlin`
Expected: PASS (new file only, nothing else references it yet).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt
git commit -m "feat: add SessionEvent schema (12 event types) for P1 session logging"
```

---

### Task 2: `SessionEventWriter.kt` — hand-written JSON Lines serializer

**Files:**
- New: `app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt`
- Test: `app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt`

**Interfaces:**
- Consumes: `SessionEvent` and all 12 implementations (Task 1).
- Produces: `object SessionEventWriter { fun toJsonLine(event: SessionEvent): String }`, plus `internal fun jsonNumber(value: Double): String`, `internal fun jsonNumber(value: Float): String`, `internal fun String.escapeJson(): String` (internal, not private, so this task's own test file — same module, same package — can test them directly).

Every serialized line carries a `"type"` discriminator (the event's simple class name) as its first field — not explicit in the spec's schema section, but necessary for the stated purpose of Decision 2 ("un file `.jsonl`... che `jq`, uno script Python... sa già leggere"): a JSON Lines consumer needs a way to tell which of the 12 shapes a given line is before it can parse the rest. Called out here as a small, clearly-in-spirit implementation decision, not a spec deviation.

A small `JsonObjectBuilder` (file-private) is used inside `SessionEventWriter` purely to avoid retyping comma-joining logic in 12 near-identical field lists — it is not a general-purpose/reflective serializer, still hand-written, still driven by an exhaustive `when` the compiler checks on every new event type.

**Non-finite guard, extended to `Float` as well as `Double`:** the spec's Decision 3 guard is written against `Double` (motivated by `SpeedEstimatorDiagnostics.innovationEmaMagnitude`), but several event fields are `Float?` (`speedKmh`, the `satelliteCn0*DbHz` fields) and can be just as legitimately non-finite. Applying the identical "non-finite → JSON `null`" rule to `Float` too is the same guard extended to every floating-point field this writer actually serializes, not a new policy.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.fred.pacorun.eventlog

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionEventWriterTest {
    @Test
    fun jsonNumberRendersFiniteDoubleAsIs() {
        assertEquals("3.5", jsonNumber(3.5))
    }

    @Test
    fun jsonNumberRendersNonFiniteDoubleAsNull() {
        assertEquals("null", jsonNumber(Double.NaN))
        assertEquals("null", jsonNumber(Double.POSITIVE_INFINITY))
        assertEquals("null", jsonNumber(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun jsonNumberRendersNonFiniteFloatAsNull() {
        assertEquals("null", jsonNumber(Float.NaN))
        assertEquals("null", jsonNumber(Float.POSITIVE_INFINITY))
    }

    @Test
    fun escapeJsonEscapesQuotesBackslashesAndControlCharacters() {
        assertEquals("line1\\nline2", "line1\nline2".escapeJson())
        assertEquals("say \\\"hi\\\"", "say \"hi\"".escapeJson())
        assertEquals("back\\\\slash", "back\\slash".escapeJson())
    }

    @Test
    fun routinePhaseChangedEventSerializesAllFieldsWithTypeDiscriminator() {
        val event = RoutinePhaseChangedEvent(
            elapsedRealtimeMillis = 12_345L,
            fromPhase = dev.fred.pacorun.routine.RoutinePhase.RUN,
            toPhase = dev.fred.pacorun.routine.RoutinePhase.WALK,
            cumulativeDistanceMeters = 250.5,
        )

        assertEquals(
            "{\"type\":\"RoutinePhaseChangedEvent\",\"elapsedRealtimeMillis\":12345," +
                "\"fromPhase\":\"RUN\",\"toPhase\":\"WALK\",\"cumulativeDistanceMeters\":250.5}",
            SessionEventWriter.toJsonLine(event),
        )
    }

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
                "\"fromState\":null,\"toState\":\"GOOD\",\"speedKmh\":8.0,\"producedAudibleCue\":false}",
            SessionEventWriter.toJsonLine(event),
        )
    }

    @Test
    fun deviceHealthSampledEventSerializesNullBatteryPercentAsJsonNull() {
        val event = DeviceHealthSampledEvent(
            elapsedRealtimeMillis = 2_000L,
            batteryPercent = null,
            thermalStatus = ThermalStatus.MODERATE,
        )

        assertEquals(
            "{\"type\":\"DeviceHealthSampledEvent\",\"elapsedRealtimeMillis\":2000," +
                "\"batteryPercent\":null,\"thermalStatus\":\"MODERATE\"}",
            SessionEventWriter.toJsonLine(event),
        )
    }

    @Test
    fun locationFixEventSerializesRejectionReasonListAndNestedNullableDiagnostics() {
        val event = LocationFixEvent(
            elapsedRealtimeMillis = 3_000L,
            sequenceNumber = 4,
            elapsedSinceStartMillis = 3_000L,
            provider = "gps",
            reliable = false,
            rejectionReasons = listOf(RejectionReason.SPEED_ACCURACY_TOO_HIGH, RejectionReason.FIX_TOO_OLD),
            satellitesUsedInFix = 6,
            satelliteCn0MinDbHz = 18.5f,
            satelliteCn0AvgDbHz = 24.1f,
            satelliteCn0MaxDbHz = 30.0f,
            qualityTier = QualityTier.SCARSA,
            speedEstimatorDiagnostics = null,
        )

        assertEquals(
            "{\"type\":\"LocationFixEvent\",\"elapsedRealtimeMillis\":3000,\"sequenceNumber\":4," +
                "\"elapsedSinceStartMillis\":3000,\"provider\":\"gps\",\"reliable\":false," +
                "\"rejectionReasons\":[\"SPEED_ACCURACY_TOO_HIGH\",\"FIX_TOO_OLD\"]," +
                "\"satellitesUsedInFix\":6,\"satelliteCn0MinDbHz\":18.5,\"satelliteCn0AvgDbHz\":24.1," +
                "\"satelliteCn0MaxDbHz\":30.0,\"qualityTier\":\"SCARSA\",\"speedEstimatorDiagnostics\":null}",
            SessionEventWriter.toJsonLine(event),
        )
    }

    @Test
    fun locationFixEventSerializesNonFiniteInnovationEmaInsideNestedDiagnosticsAsNull() {
        val event = LocationFixEvent(
            elapsedRealtimeMillis = 4_000L,
            sequenceNumber = 5,
            elapsedSinceStartMillis = 4_000L,
            provider = "gps",
            reliable = true,
            rejectionReasons = emptyList(),
            satellitesUsedInFix = null,
            satelliteCn0MinDbHz = null,
            satelliteCn0AvgDbHz = null,
            satelliteCn0MaxDbHz = null,
            qualityTier = QualityTier.BUONA,
            speedEstimatorDiagnostics = SpeedEstimatorDiagnostics(
                processNoiseRegime = ProcessNoiseRegime.Q_HIGH,
                accuracyGateEngaged = true,
                innovationEmaMagnitude = Double.NaN,
            ),
        )

        val line = SessionEventWriter.toJsonLine(event)

        assertEquals(
            "{\"type\":\"LocationFixEvent\",\"elapsedRealtimeMillis\":4000,\"sequenceNumber\":5," +
                "\"elapsedSinceStartMillis\":4000,\"provider\":\"gps\",\"reliable\":true," +
                "\"rejectionReasons\":[],\"satellitesUsedInFix\":null,\"satelliteCn0MinDbHz\":null," +
                "\"satelliteCn0AvgDbHz\":null,\"satelliteCn0MaxDbHz\":null,\"qualityTier\":\"BUONA\"," +
                "\"speedEstimatorDiagnostics\":{\"processNoiseRegime\":\"Q_HIGH\",\"accuracyGateEngaged\":true," +
                "\"innovationEmaMagnitude\":null}}",
            line,
        )
        assertEquals(false, line.contains("NaN"))
    }

    @Test
    fun sessionStartedSerializesNestedPreferencesAndSensors() {
        val event = SessionStarted(
            elapsedRealtimeMillis = 0L,
            deviceModel = "Google Pixel 9a",
            osVersionRelease = "16",
            osApiLevel = 36,
            appGitCommitHash = "abc123def456",
            preferences = dev.fred.pacorun.settings.RunPreferences(),
            sensors = SensorAvailability(
                hasBarometer = true,
                hasMagnetometer = true,
                hasAccelerometer = true,
                hasGyroscope = true,
                hasStepCounter = false,
                hasStepDetector = false,
            ),
            networkTypeAtStart = NetworkType.WIFI,
        )

        val line = SessionEventWriter.toJsonLine(event)

        assertEquals(true, line.startsWith("{\"type\":\"SessionStarted\",\"elapsedRealtimeMillis\":0,"))
        assertEquals(true, line.contains("\"deviceModel\":\"Google Pixel 9a\""))
        assertEquals(true, line.contains("\"preferences\":{\"mode\":\"CONSTANT\""))
        assertEquals(true, line.contains("\"sensors\":{\"hasBarometer\":true"))
        assertEquals(true, line.contains("\"networkTypeAtStart\":\"WIFI\""))
        assertEquals(false, line.contains("null"))
    }
}
```

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.eventlog.SessionEventWriterTest"`
Expected: FAIL, "unresolved reference: SessionEventWriter" (the file doesn't exist yet).

- [ ] **Step 3: Write `SessionEventWriter.kt`**

```kotlin
package dev.fred.pacorun.eventlog

// Hand-written JSON, matching GpxWriter's own precedent for XML (spec Decision 3): this project
// has zero JSON serialization dependency, and JSON is structurally simpler than XML to write by
// hand (no tag matching). JsonObjectBuilder below exists only to avoid retyping comma-joining
// logic across the 12 functions below -- it is not a general-purpose/reflective serializer; the
// exhaustive `when` in toJsonLine() is still what the compiler checks on every new event type.
object SessionEventWriter {
    fun toJsonLine(event: SessionEvent): String = when (event) {
        is SessionStarted -> sessionStarted(event)
        is LocationFixEvent -> locationFixEvent(event)
        is GpsSilentGapEvent -> gpsSilentGapEvent(event)
        is AudioCueEmittedEvent -> audioCueEmittedEvent(event)
        is RoutinePhaseChangedEvent -> routinePhaseChangedEvent(event)
        is PaceStateConfirmedEvent -> paceStateConfirmedEvent(event)
        is PaceCandidateStartedEvent -> paceCandidateStartedEvent(event)
        is PaceCandidateAbandonedEvent -> paceCandidateAbandonedEvent(event)
        is SpeedChainBrokenEvent -> speedChainBrokenEvent(event)
        is DistanceChainBrokenEvent -> distanceChainBrokenEvent(event)
        is AppLifecycleChangedEvent -> appLifecycleChangedEvent(event)
        is DeviceHealthSampledEvent -> deviceHealthSampledEvent(event)
    }

    private fun sessionStarted(event: SessionStarted) = json {
        string("type", "SessionStarted")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        string("deviceModel", event.deviceModel)
        string("osVersionRelease", event.osVersionRelease)
        int("osApiLevel", event.osApiLevel)
        string("appGitCommitHash", event.appGitCommitHash)
        raw("preferences", runPreferences(event.preferences))
        raw("sensors", sensorAvailability(event.sensors))
        enum("networkTypeAtStart", event.networkTypeAtStart)
    }

    private fun runPreferences(preferences: dev.fred.pacorun.settings.RunPreferences) = json {
        enum("mode", preferences.mode)
        raw("constantRange", speedRange(preferences.constantRange))
        raw("runRange", speedRange(preferences.runRange))
        boolean("walkUsesConstantRange", preferences.walkUsesConstantRange)
        raw("walkRange", speedRange(preferences.walkRange))
        double("initialConstantKm", preferences.initialConstantKm.toDouble())
        int("runSeconds", preferences.runSeconds)
        int("walkSeconds", preferences.walkSeconds)
        boolean("routineUnlimited", preferences.routineUnlimited)
        double("routineKm", preferences.routineKm.toDouble())
        int("confirmationSamples", preferences.confirmationSamples)
        long("repeatMillis", preferences.repeatMillis)
        boolean("gpsCuesEnabled", preferences.gpsCuesEnabled)
        double("cueVolume", preferences.cueVolume.toDouble())
        boolean("dynamicColor", preferences.dynamicColor)
    }

    private fun speedRange(range: dev.fred.pacorun.settings.SpeedRange) = json {
        double("minKmh", range.minKmh.toDouble())
        double("maxKmh", range.maxKmh.toDouble())
    }

    private fun sensorAvailability(sensors: SensorAvailability) = json {
        boolean("hasBarometer", sensors.hasBarometer)
        boolean("hasMagnetometer", sensors.hasMagnetometer)
        boolean("hasAccelerometer", sensors.hasAccelerometer)
        boolean("hasGyroscope", sensors.hasGyroscope)
        boolean("hasStepCounter", sensors.hasStepCounter)
        boolean("hasStepDetector", sensors.hasStepDetector)
    }

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

    private fun speedEstimatorDiagnostics(diagnostics: SpeedEstimatorDiagnostics) = json {
        enum("processNoiseRegime", diagnostics.processNoiseRegime)
        boolean("accuracyGateEngaged", diagnostics.accuracyGateEngaged)
        double("innovationEmaMagnitude", diagnostics.innovationEmaMagnitude)
    }

    private fun gpsSilentGapEvent(event: GpsSilentGapEvent) = json {
        string("type", "GpsSilentGapEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        long("gapMillis", event.gapMillis)
    }

    private fun audioCueEmittedEvent(event: AudioCueEmittedEvent) = json {
        string("type", "AudioCueEmittedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        string("cueType", event.cueType)
        double("cumulativeDistanceMeters", event.cumulativeDistanceMeters)
        floatOrNull("speedKmh", event.speedKmh)
    }

    private fun routinePhaseChangedEvent(event: RoutinePhaseChangedEvent) = json {
        string("type", "RoutinePhaseChangedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enum("fromPhase", event.fromPhase)
        enum("toPhase", event.toPhase)
        double("cumulativeDistanceMeters", event.cumulativeDistanceMeters)
    }

    private fun paceStateConfirmedEvent(event: PaceStateConfirmedEvent) = json {
        string("type", "PaceStateConfirmedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enumOrNull("fromState", event.fromState)
        enum("toState", event.toState)
        floatOrNull("speedKmh", event.speedKmh)
        boolean("producedAudibleCue", event.producedAudibleCue)
    }

    private fun paceCandidateStartedEvent(event: PaceCandidateStartedEvent) = json {
        string("type", "PaceCandidateStartedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enum("candidateState", event.candidateState)
    }

    private fun paceCandidateAbandonedEvent(event: PaceCandidateAbandonedEvent) = json {
        string("type", "PaceCandidateAbandonedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enum("candidateState", event.candidateState)
        int("samplesAccumulated", event.samplesAccumulated)
        enum("reason", event.reason)
    }

    private fun speedChainBrokenEvent(event: SpeedChainBrokenEvent) = json {
        string("type", "SpeedChainBrokenEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enum("reason", event.reason)
    }

    private fun distanceChainBrokenEvent(event: DistanceChainBrokenEvent) = json {
        string("type", "DistanceChainBrokenEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enum("reason", event.reason)
    }

    private fun appLifecycleChangedEvent(event: AppLifecycleChangedEvent) = json {
        string("type", "AppLifecycleChangedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enum("transition", event.transition)
    }

    private fun deviceHealthSampledEvent(event: DeviceHealthSampledEvent) = json {
        string("type", "DeviceHealthSampledEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        intOrNull("batteryPercent", event.batteryPercent)
        enum("thermalStatus", event.thermalStatus)
    }

    private fun json(build: JsonObjectBuilder.() -> Unit): String = JsonObjectBuilder().apply(build).build()
}

// NaN/Infinity are not valid JSON tokens -- a hand-written serializer that doesn't guard for
// them emits an unparseable line. SpeedEstimatorDiagnostics.innovationEmaMagnitude can
// legitimately be non-finite (SpeedEstimator.accept() itself guards speed.isFinite() before
// returning, for the same reason) -- "no valid measurement" becomes JSON null, same discipline
// as escapeJson() for strings, applied to every Double/Float this writer serializes (Float
// included, extending the spec's Double-only guard to every floating-point field actually used).
internal fun jsonNumber(value: Double): String = if (value.isFinite()) value.toString() else "null"
internal fun jsonNumber(value: Float): String = if (value.isFinite()) value.toString() else "null"

internal fun String.escapeJson(): String = buildString {
    this@escapeJson.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
}

private class JsonObjectBuilder {
    private val fields = mutableListOf<String>()

    fun string(name: String, value: String) = raw(name, "\"${value.escapeJson()}\"")
    fun stringOrNull(name: String, value: String?) = raw(name, value?.let { "\"${it.escapeJson()}\"" })
    fun long(name: String, value: Long) = raw(name, value.toString())
    fun int(name: String, value: Int) = raw(name, value.toString())
    fun intOrNull(name: String, value: Int?) = raw(name, value?.toString())
    fun boolean(name: String, value: Boolean) = raw(name, value.toString())
    fun double(name: String, value: Double) = raw(name, jsonNumber(value))
    fun floatOrNull(name: String, value: Float?) = raw(name, value?.let { jsonNumber(it) })
    fun enum(name: String, value: Enum<*>) = raw(name, "\"${value.name}\"")
    fun enumOrNull(name: String, value: Enum<*>?) = raw(name, value?.let { "\"${it.name}\"" })
    fun stringArray(name: String, values: List<String>) =
        raw(name, values.joinToString(",", "[", "]") { "\"${it.escapeJson()}\"" })

    fun raw(name: String, jsonValue: String?) {
        fields += "\"$name\":${jsonValue ?: "null"}"
    }

    fun build(): String = fields.joinToString(",", "{", "}")
}
```

- [ ] **Step 4: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.eventlog.SessionEventWriterTest"`
Expected: PASS (all 8 tests).

- [ ] **Step 5: Full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt
git commit -m "feat: add hand-written JSON Lines serializer for SessionEvent with non-finite guard"
```

---

### Task 3: `DistanceAccumulator` — `breakChain()` prerequisite cleanup + `onEvent` wiring

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/DistanceAccumulatorTest.kt`

**Interfaces:**
- Consumes: `SessionEvent`, `DistanceChainBrokenEvent`, `DistanceChainBreakReason` (Task 1).
- Produces: `DistanceAccumulator`'s constructor gains `private val onEvent: (SessionEvent) -> Unit = {}` (last parameter).

**Current code** (`DistanceAccumulator.accept()`, `RunSessionStore.kt`, verified on `feature/speed-production-wiring` tip):

```kotlin
    override fun accept(position: LocationPosition, reliable: Boolean): Double {
        if (!reliable || !position.isValid()) return breakChain()
        val previousAnchor = anchor
        val previousFix = lastFix
        if (previousAnchor == null || previousFix == null) {
            anchor = position
            lastFix = position
            return 0.0
        }
        val fixElapsedMillis = position.elapsedRealtimeMillis - previousFix.elapsedRealtimeMillis
        val fixDistance = haversineMeters(previousFix, position)
        if (fixElapsedMillis <= 0L || !fixDistance.isFinite() || fixDistance > maxSpeedMetersPerSecond * fixElapsedMillis / 1_000.0) {
            anchor = null
            lastFix = null
            return 0.0
        }
        ...
```

The `IMPLAUSIBLE_JUMP` branch (`anchor = null; lastFix = null; return 0.0`) duplicates `breakChain()`'s body inline instead of calling it (spec 5.5's named prerequisite) — an instrumentation hook on `breakChain()` alone would miss this branch entirely. This task fixes that **and** wires `onEvent` at both call sites in the same change, since the fix's only observable effect is exactly this event firing.

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun unreliableOrInvalidFixEmitsDistanceChainBrokenEventBeforeResetting() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val accumulator = DistanceAccumulator(onEvent = events::add)

        accumulator.accept(fix(0.0, 0.0, 0L), reliable = false)

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.DistanceChainBrokenEvent(
                    0L,
                    dev.fred.pacorun.eventlog.DistanceChainBreakReason.UNRELIABLE_OR_INVALID_FIX,
                ),
            ),
            events,
        )
    }

    @Test
    fun implausibleJumpEmitsDistanceChainBrokenEventBeforeResetting() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val accumulator = DistanceAccumulator(maxSpeedMetersPerSecond = 15.0, onEvent = events::add)

        accumulator.accept(fix(0.0, 0.0, 0L), reliable = true)
        // ~1113m in one second -- far beyond the 15 m/s budget, must be rejected as a jump.
        accumulator.accept(fix(0.0, .01, 1_000L), reliable = true)

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.DistanceChainBrokenEvent(
                    1_000L,
                    dev.fred.pacorun.eventlog.DistanceChainBreakReason.IMPLAUSIBLE_JUMP,
                ),
            ),
            events,
        )
    }

    @Test
    fun reliableAcceptedFixesEmitNoEvent() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val accumulator = DistanceAccumulator(onEvent = events::add)

        accumulator.accept(fix(0.0, 0.0, 0L), reliable = true)
        accumulator.accept(fix(0.0, .0001, 1_000L), reliable = true)

        assertEquals(emptyList<dev.fred.pacorun.eventlog.SessionEvent>(), events)
    }
```

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.DistanceAccumulatorTest"`
Expected: FAIL to compile, "no value passed for parameter 'onEvent'" (the constructor doesn't have it yet).

- [ ] **Step 3: Apply the fix in `RunSessionStore.kt`**

```kotlin
class DistanceAccumulator(
    private val maxSpeedMetersPerSecond: Double = 15.0,
    private val minMovementMeters: Double = 2.0,
    private val jitterAccuracyFraction: Double = 0.5,
    private val onEvent: (SessionEvent) -> Unit = {},
) : SessionDistanceEngine {
    private var anchor: LocationPosition? = null
    private var lastFix: LocationPosition? = null

    override fun accept(position: LocationPosition, reliable: Boolean): Double {
        if (!reliable || !position.isValid()) {
            onEvent(DistanceChainBrokenEvent(position.elapsedRealtimeMillis, DistanceChainBreakReason.UNRELIABLE_OR_INVALID_FIX))
            return breakChain()
        }
        val previousAnchor = anchor
        val previousFix = lastFix
        if (previousAnchor == null || previousFix == null) {
            anchor = position
            lastFix = position
            return 0.0
        }
        val fixElapsedMillis = position.elapsedRealtimeMillis - previousFix.elapsedRealtimeMillis
        val fixDistance = haversineMeters(previousFix, position)
        if (fixElapsedMillis <= 0L || !fixDistance.isFinite() || fixDistance > maxSpeedMetersPerSecond * fixElapsedMillis / 1_000.0) {
            onEvent(DistanceChainBrokenEvent(position.elapsedRealtimeMillis, DistanceChainBreakReason.IMPLAUSIBLE_JUMP))
            return breakChain()
        }
        lastFix = position
        val distance = haversineMeters(previousAnchor, position)
        if (distance < jitterThreshold(previousAnchor, position)) return 0.0
        anchor = position
        return distance
    }
```

Add the import at the top of `RunSessionStore.kt`:

```kotlin
import dev.fred.pacorun.eventlog.DistanceChainBreakReason
import dev.fred.pacorun.eventlog.DistanceChainBrokenEvent
import dev.fred.pacorun.eventlog.SessionEvent
```

(Note: `!reliable || !position.isValid()` — when `!reliable`, `position` is still a valid `LocationPosition` object (only its trustworthiness is in question, not its shape), so `position.elapsedRealtimeMillis` is always safe to read here, exactly as `SpeedEstimator`'s equivalent branch already assumes in Task 4.)

- [ ] **Step 4: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.DistanceAccumulatorTest"`
Expected: PASS (all tests, old and new).

- [ ] **Step 5: Full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (no other file constructs `DistanceAccumulator` with positional args past `jitterAccuracyFraction`, verified by reading `SessionCoordinator`'s default `distanceEngine: SessionDistanceEngine = DistanceAccumulator()` — a no-arg call, unaffected by a new trailing default parameter).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt app/src/test/java/dev/fred/pacorun/service/DistanceAccumulatorTest.kt
git commit -m "fix: route DistanceAccumulator's IMPLAUSIBLE_JUMP through breakChain() and emit DistanceChainBrokenEvent"
```

---

### Task 4: `SpeedEstimator` — `onEvent` wiring for `SpeedChainBrokenEvent`

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt`

**Interfaces:**
- Consumes: `SessionEvent`, `SpeedChainBrokenEvent`, `SpeedChainBreakReason` (Task 1).
- Produces: `SpeedEstimator`'s constructor gains `private val onEvent: (SessionEvent) -> Unit = {}` (last parameter); new `internal fun getLastProcessNoiseRegime(): ProcessNoiseRegime` diagnostic accessor (consumed by Task 14, alongside the three that already exist: `getLastInnovationEma()`, `getLastProcessNoise()`, `getLastAccuracyGateEngaged()`).

`SpeedEstimator.accept()` has exactly three internal `return breakChain()` sites (spec 5.5). Each one knows its own specific reason — unlike `breakChain()` itself, whose signature (`fun breakChain(): Double`, fixed non-negotiable per the Fase 0.5 plan) carries no reason parameter and is also called externally (Task 7, `STALE_NO_RECENT_RELIABLE_FIX`, a reason `SpeedEstimator` itself cannot know). So each event is emitted at its call site, immediately before invoking `breakChain()`.

- [ ] **Step 1: Write the failing tests**

```kotlin
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
```

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorTest"`
Expected: FAIL to compile, "no value passed for parameter 'onEvent'".

- [ ] **Step 3: Apply the fix in `SpeedEstimator.kt`**

Add imports at the top:

```kotlin
import dev.fred.pacorun.eventlog.ProcessNoiseRegime
import dev.fred.pacorun.eventlog.SessionEvent
import dev.fred.pacorun.eventlog.SpeedChainBreakReason
import dev.fred.pacorun.eventlog.SpeedChainBrokenEvent
```

Add the parameter to the constructor (last position, after `accuracyBaselineAlpha`):

```kotlin
    private val accuracyBaselineAlpha: Double = 0.05,
    private val onEvent: (SessionEvent) -> Unit = {},
) {
```

Add the new accessor next to the existing three:

```kotlin
    internal fun getLastProcessNoiseRegime(): ProcessNoiseRegime =
        if (lastProcessNoise == qHigh) ProcessNoiseRegime.Q_HIGH else ProcessNoiseRegime.Q_LOW
```

Replace the three `breakChain()` call sites in `accept()`:

```kotlin
    fun accept(position: LocationPosition, reliable: Boolean): Double {
        if (!reliable || !position.isValid()) {
            onEvent(SpeedChainBrokenEvent(position.elapsedRealtimeMillis, SpeedChainBreakReason.UNRELIABLE_OR_INVALID_FIX))
            return breakChain()
        }

        val lat0 = originLatitude ?: position.latitude.also { originLatitude = it }
        val lon0 = originLongitude ?: position.longitude.also { originLongitude = it }
        val metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(lat0))
        val x = (position.longitude - lon0) * metersPerDegreeLongitude
        val y = (position.latitude - lat0) * METERS_PER_DEGREE_LATITUDE

        val accuracyMeters = (position.horizontalAccuracyMeters ?: DEFAULT_HORIZONTAL_ACCURACY_METERS).coerceAtLeast(1f).toDouble()

        val east = eastAxis
        val north = northAxis
        val previousElapsed = lastElapsedRealtimeMillis

        if (east == null || north == null || previousElapsed == null) {
            eastAxis = AxisKalman(x)
            northAxis = AxisKalman(y)
            lastElapsedRealtimeMillis = position.elapsedRealtimeMillis
            accuracyBaselineMeters = accuracyMeters
            return 0.0
        }

        val dtSeconds = (position.elapsedRealtimeMillis - previousElapsed) / 1_000.0
        if (dtSeconds <= 0.0) {
            onEvent(SpeedChainBrokenEvent(position.elapsedRealtimeMillis, SpeedChainBreakReason.NON_POSITIVE_TIME_DELTA))
            return breakChain()
        }

        val accuracyBaseline = accuracyBaselineMeters ?: accuracyMeters
        val accuracyDegraded = accuracyMeters > accuracyBaseline * accuracyDegradationRatio
        lastAccuracyGateEngaged = accuracyDegraded

        val processNoise = if (accuracyDegraded) {
            qLow
        } else if (maxOf(kotlin.math.abs(eastInnovationEma), kotlin.math.abs(northInnovationEma)) > innovationThreshold) {
            qHigh
        } else {
            qLow
        }
        lastProcessNoise = processNoise
        east.predict(dtSeconds, processNoise)
        north.predict(dtSeconds, processNoise)

        val measurementVariance = accuracyMeters.pow(2)
        east.update(x, measurementVariance)
        north.update(y, measurementVariance)
        if (!accuracyDegraded) {
            eastInnovationEma = innovationEmaAlpha * east.lastSignedNormalizedInnovation + (1 - innovationEmaAlpha) * eastInnovationEma
            northInnovationEma = innovationEmaAlpha * north.lastSignedNormalizedInnovation + (1 - innovationEmaAlpha) * northInnovationEma
        }
        accuracyBaselineMeters = accuracyBaselineAlpha * accuracyMeters + (1 - accuracyBaselineAlpha) * accuracyBaseline

        lastElapsedRealtimeMillis = position.elapsedRealtimeMillis
        val speed = hypot(east.velocity, north.velocity) * METERS_PER_SECOND_TO_KMH
        if (speed.isFinite()) return speed
        onEvent(SpeedChainBrokenEvent(position.elapsedRealtimeMillis, SpeedChainBreakReason.NON_FINITE_RESULT))
        return breakChain()
    }
```

- [ ] **Step 4: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorTest"`
Expected: PASS (all tests, old and new — `nanAccuracyDoesNotPermanentlyPoisonFilter` and every other existing test stays green unmodified, since `onEvent` defaults to `{}`).

- [ ] **Step 5: Full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt
git commit -m "feat: wire SpeedEstimator's three internal breakChain() sites to emit SpeedChainBrokenEvent"
```

---

### Task 5: `PaceEvaluator` — `onEvent` wiring for the 3 pace event types

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt`

**Interfaces:**
- Consumes: `SessionEvent`, `PaceStateConfirmedEvent`, `PaceCandidateStartedEvent`, `PaceCandidateAbandonedEvent`, `PaceCandidateAbandonReason` (Task 1).
- Produces: `PaceEvaluator`'s constructor gains `private val onEvent: (SessionEvent) -> Unit = {}` (last parameter).

Per spec 5.4:
- `PaceStateConfirmedEvent` fires every time `confirmedPace` actually changes (the `confirmedPace = pace` assignment), regardless of whether an audible cue results — `producedAudibleCue` is exactly `paceCue != null` computed right there.
- `PaceCandidateStartedEvent` fires whenever a genuinely new candidate begins accumulating (the `else` branch that sets `candidatePace = pace; candidateSamples = 1`).
- `PaceCandidateAbandonedEvent` fires at **three** `accept()` sites, not the two the spec names — a deliberate plan-level addition, decided during review of this plan (see the Task 1 note above `PaceCandidateAbandonReason`'s declaration). The two the spec names — the "snapped back to confirmed" branch and `acceptUnreliable()` — each guarded by "only if a candidate was actually in progress" (spec's own guard), reason `SNAPPED_BACK_TO_CONFIRMED`/`GPS_BECAME_UNRELIABLE`. The third: the `else` branch of the `if (pace == candidatePace) { ... } else { ... }` split inside `accept()` — reached when a new sample's state matches **neither** the confirmed state nor the in-progress candidate, i.e. a candidate is replaced by a *different* candidate without ever confirming or snapping back. Left uninstrumented, this path silently drops the in-progress candidate (`candidatePace = pace; candidateSamples = 1`, no `clearCandidate()`, no event) — exactly the rapid non-converging pace-state churn P1 exists to make visible, and directly relevant to the P2 flapping investigation. Instrumented here with a new reason, `SUPERSEDED_BY_NEW_CANDIDATE`, fired for the **old** candidate (its state and accumulated sample count) immediately before the new candidate starts. Guarded the same way as the other two: only if a candidate was actually in progress (i.e. `candidatePace != null` before the reassignment) — the very first candidate of a session, or the first candidate after a confirm/snap-back/GPS-unreliable reset, has nothing to supersede and emits no abandon event, only `PaceCandidateStartedEvent`. The other two `clearCandidate()` call sites (`updateConfig()`'s phase-change reset, and the post-confirmation bookkeeping reset) remain **deliberately excluded**, exactly as the spec states — unaffected by this addition.

- [ ] **Step 1: Write the failing tests**

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
                dev.fred.pacorun.eventlog.PaceStateConfirmedEvent(1_000L, null, PaceState.TOO_FAST, 8.8f, true),
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
    }

    @Test
    fun newCandidateDifferentFromConfirmedEmitsPaceCandidateStartedEvent() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        evaluator.accept(sample(7.3f, 0L))

        assertEquals(listOf(dev.fred.pacorun.eventlog.PaceCandidateStartedEvent(0L, PaceState.TOO_SLOW)), events)
    }

    @Test
    fun sampleSnappingBackToConfirmedAbandonsInProgressCandidate() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        // config has confirmationSamples = 2 (see the shared `config` fixture above) -- reaching
        // CONFIRMED needs two consecutive same-range samples, same as every other test in this file.
        evaluator.accept(sample(8f, 0L)) // candidate GOOD starts accumulating
        evaluator.accept(sample(8f, 1_000L)) // confirms GOOD
        events.clear()
        evaluator.accept(sample(8.7f, 2_000L)) // candidate TOO_FAST starts accumulating
        evaluator.accept(sample(8f, 3_000L)) // snaps back to already-confirmed GOOD before TOO_FAST confirms

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.PaceCandidateStartedEvent(2_000L, PaceState.TOO_FAST),
                dev.fred.pacorun.eventlog.PaceCandidateAbandonedEvent(
                    3_000L,
                    PaceState.TOO_FAST,
                    1,
                    dev.fred.pacorun.eventlog.PaceCandidateAbandonReason.SNAPPED_BACK_TO_CONFIRMED,
                ),
            ),
            events,
        )
    }

    @Test
    fun candidateDirectlySupersededByADifferentCandidateEmitsAbandonedWithSupersededReason() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        // No confirmation, no snap-back, no unreliable sample in between -- the in-progress
        // TOO_SLOW candidate is replaced directly by a TOO_FAST one before ever confirming.
        evaluator.accept(sample(7.3f, 0L)) // candidate TOO_SLOW starts accumulating
        evaluator.accept(sample(8.7f, 1_000L)) // TOO_FAST supersedes the TOO_SLOW candidate directly

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.PaceCandidateStartedEvent(0L, PaceState.TOO_SLOW),
                dev.fred.pacorun.eventlog.PaceCandidateAbandonedEvent(
                    1_000L,
                    PaceState.TOO_SLOW,
                    1,
                    dev.fred.pacorun.eventlog.PaceCandidateAbandonReason.SUPERSEDED_BY_NEW_CANDIDATE,
                ),
                dev.fred.pacorun.eventlog.PaceCandidateStartedEvent(1_000L, PaceState.TOO_FAST),
            ),
            events,
        )
    }

    @Test
    fun gpsBecomingUnreliableAbandonsInProgressCandidate() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        evaluator.accept(sample(8.7f, 0L)) // candidate TOO_FAST starts accumulating
        events.clear()
        evaluator.accept(unreliableSample(1_000L))

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.PaceCandidateAbandonedEvent(
                    1_000L,
                    PaceState.TOO_FAST,
                    1,
                    dev.fred.pacorun.eventlog.PaceCandidateAbandonReason.GPS_BECAME_UNRELIABLE,
                ),
            ),
            events,
        )
    }

    @Test
    fun phaseChangeResetDoesNotEmitAnAbandonEvent() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        evaluator.accept(sample(8.7f, 0L)) // candidate TOO_FAST starts accumulating
        events.clear()
        evaluator.updateConfig(config) // phase-change reset -- already covered by RoutinePhaseChangedEvent, not an abandon

        assertEquals(emptyList<dev.fred.pacorun.eventlog.SessionEvent>(), events)
    }
```

Check the file already has an `unreliableSample` helper (used by `unreliableSampleBreaksPaceConfirmation`, seen at line 99 of the current file) — reuse it; if it is named differently, use the existing helper's exact name instead of introducing a duplicate.

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.pace.PaceEvaluatorTest"`
Expected: FAIL to compile, "no value passed for parameter 'onEvent'".

- [ ] **Step 3: Apply the fix in `PaceEvaluator.kt`**

Add imports at the top:

```kotlin
import dev.fred.pacorun.eventlog.PaceCandidateAbandonReason
import dev.fred.pacorun.eventlog.PaceCandidateAbandonedEvent
import dev.fred.pacorun.eventlog.PaceCandidateStartedEvent
import dev.fred.pacorun.eventlog.PaceStateConfirmedEvent
import dev.fred.pacorun.eventlog.SessionEvent
```

Replace the whole class body:

```kotlin
class PaceEvaluator(private var config: PaceConfig, private val onEvent: (SessionEvent) -> Unit = {}) {
    var current = PaceResult(PaceState.ACQUIRING_GPS, null, null)
        private set

    private var confirmedPace: PaceState? = null
    private var candidatePace: PaceState? = null
    private var candidateSamples = 0
    private var gpsWeak = false
    private var lastPaceCueMillis: Long? = null
    private val pendingPaceCues = ArrayDeque<Cue>()

    fun updateConfig(config: PaceConfig) {
        this.config = config
        confirmedPace = null
        clearCandidate()
        gpsWeak = false
        lastPaceCueMillis = null
        pendingPaceCues.clear()
        current = PaceResult(PaceState.ACQUIRING_GPS, null, null)
    }

    fun accept(sample: LocationSample): PaceResult {
        if (!sample.reliable) return acceptUnreliable(sample.elapsedRealtimeMillis)

        val gpsCue = if (gpsWeak && config.gpsCuesEnabled) Cue.GOOD_GPS else null
        gpsWeak = false

        val pace = sample.paceState()
        if (pace == confirmedPace) {
            abandonCandidateIfAny(sample.elapsedRealtimeMillis, PaceCandidateAbandonReason.SNAPPED_BACK_TO_CONFIRMED)
            val paceCue = repeatedCue(pace, sample.elapsedRealtimeMillis)
            return update(
                confirmedPace ?: PaceState.ACQUIRING_GPS,
                sample.speedKmh,
                selectCue(gpsCue, paceCue, sample.elapsedRealtimeMillis),
            )
        }

        if (pace == candidatePace) {
            candidateSamples++
        } else {
            val supersededCandidate = candidatePace
            if (supersededCandidate != null) {
                onEvent(
                    PaceCandidateAbandonedEvent(
                        sample.elapsedRealtimeMillis,
                        supersededCandidate,
                        candidateSamples,
                        PaceCandidateAbandonReason.SUPERSEDED_BY_NEW_CANDIDATE,
                    ),
                )
            }
            candidatePace = pace
            candidateSamples = 1
            onEvent(PaceCandidateStartedEvent(sample.elapsedRealtimeMillis, pace))
        }

        if (candidateSamples < config.confirmationSamples) {
            return update(
                confirmedPace ?: PaceState.ACQUIRING_GPS,
                sample.speedKmh,
                selectCue(gpsCue, null, sample.elapsedRealtimeMillis),
            )
        }

        val previous = confirmedPace
        confirmedPace = pace
        clearCandidate()
        val paceCue = when (pace) {
            PaceState.TOO_SLOW -> Cue.TOO_SLOW
            PaceState.TOO_FAST -> Cue.TOO_FAST
            PaceState.GOOD -> if (previous == PaceState.TOO_SLOW || previous == PaceState.TOO_FAST) {
                Cue.GOOD_PACE
            } else {
                null
            }
            else -> null
        }
        onEvent(
            PaceStateConfirmedEvent(
                elapsedRealtimeMillis = sample.elapsedRealtimeMillis,
                fromState = previous,
                toState = pace,
                speedKmh = sample.speedKmh,
                producedAudibleCue = paceCue != null,
            ),
        )
        lastPaceCueMillis = null
        return update(pace, sample.speedKmh, selectCue(gpsCue, paceCue, sample.elapsedRealtimeMillis))
    }

    private fun acceptUnreliable(elapsedRealtimeMillis: Long): PaceResult {
        abandonCandidateIfAny(elapsedRealtimeMillis, PaceCandidateAbandonReason.GPS_BECAME_UNRELIABLE)
        val cue = if (!gpsWeak && config.gpsCuesEnabled) Cue.BAD_GPS else null
        gpsWeak = true
        return update(PaceState.GPS_WEAK, null, cue)
    }

    private fun abandonCandidateIfAny(elapsedRealtimeMillis: Long, reason: PaceCandidateAbandonReason) {
        val abandonedState = candidatePace
        if (abandonedState != null) {
            onEvent(PaceCandidateAbandonedEvent(elapsedRealtimeMillis, abandonedState, candidateSamples, reason))
        }
        clearCandidate()
    }

    private fun repeatedCue(pace: PaceState, elapsedMillis: Long): Cue? {
        val cue = when (pace) {
            PaceState.TOO_SLOW -> Cue.TOO_SLOW
            PaceState.TOO_FAST -> Cue.TOO_FAST
            else -> return null
        }
        if (cue in pendingPaceCues) return null
        val lastCue = lastPaceCueMillis ?: return null
        if (elapsedMillis < lastCue || elapsedMillis - lastCue < config.repeatMillis) return null
        return cue
    }

    private fun selectCue(gpsCue: Cue?, paceCue: Cue?, elapsedMillis: Long): Cue? {
        if (paceCue != null) pendingPaceCues.addLast(paceCue)
        if (gpsCue != null) return gpsCue
        return pendingPaceCues.removeFirstOrNull()?.also {
            if (it == Cue.TOO_SLOW || it == Cue.TOO_FAST) lastPaceCueMillis = elapsedMillis
        }
    }

    private fun LocationSample.paceState() = when {
        speedKmh < config.minKmh -> PaceState.TOO_SLOW
        speedKmh > config.maxKmh -> PaceState.TOO_FAST
        else -> PaceState.GOOD
    }

    private fun clearCandidate() {
        candidatePace = null
        candidateSamples = 0
    }

    private fun update(state: PaceState, speedKmh: Float?, cue: Cue?): PaceResult =
        PaceResult(state, speedKmh, cue).also { current = it }
}
```

- [ ] **Step 4: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.pace.PaceEvaluatorTest"`
Expected: PASS (all tests, old and new).

- [ ] **Step 5: Full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt app/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt
git commit -m "feat: wire PaceEvaluator to emit PaceStateConfirmed/PaceCandidateStarted/PaceCandidateAbandoned events"
```

---

### Task 6: `RoutineEngine` — `onEvent` wiring for `RoutinePhaseChangedEvent`

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/routine/RoutineEngineTest.kt`

**Interfaces:**
- Consumes: `SessionEvent`, `RoutinePhaseChangedEvent` (Task 1).
- Produces: `RoutineEngine`'s constructor gains `onEvent: (SessionEvent) -> Unit = {}` (second parameter, after `preferences`).

Per spec Decision 6: one event per **real** `phase = ...` reassignment (`startRun`, `advanceTo`'s up to two reassignments, `finishRoutine`), capturing `fromPhase` immediately before each reassignment. The initial `phase = ...` in the constructor is not a "real transition" (no tick, no prior value to compare against) and emits nothing. This task does **not** touch the phase-transition math itself (`advanceTo`'s `fullCycles`/`transitions` logic stays byte-for-byte identical) — only adds `onEvent(...)` calls at the three existing assignment sites, per this plan's Global Constraints (P4 is separate, deferred work).

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun zeroWarmupPhaseChangeEmitsOneRoutinePhaseChangedEvent() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val engine = RoutineEngine(preferences(), onEvent = events::add)

        engine.accept(input(time = 0L, distance = 5.0))

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.RoutinePhaseChangedEvent(
                    0L,
                    RoutinePhase.CONSTANT_BEFORE,
                    RoutinePhase.RUN,
                    5.0,
                ),
            ),
            events,
        )
    }

    @Test
    fun finishingTheRoutineEmitsRoutinePhaseChangedEventToConstantAfter() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val engine = RoutineEngine(preferences(unlimited = false, routineKm = .001f), onEvent = events::add)

        engine.accept(input(time = 0L)) // starts RUN
        events.clear()
        engine.accept(input(time = 1_000L, distance = 2.0)) // routineDistanceMeters (2.0) crosses the 1.0m threshold

        assertEquals(
            listOf(dev.fred.pacorun.eventlog.RoutinePhaseChangedEvent(1_000L, RoutinePhase.RUN, RoutinePhase.CONSTANT_AFTER, 2.0)),
            events,
        )
    }

    @Test
    fun aDelayedTickThatSkipsAFullCycleEmitsTwoSeparateRoutinePhaseChangedEvents() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val engine = RoutineEngine(preferences(runSeconds = 5, walkSeconds = 5), onEvent = events::add)

        engine.accept(input(time = 0L)) // starts RUN immediately (warmupKm = 0)
        events.clear()
        // elapsed=12000: durationMillis(RUN)=5000 -> remaining=7000; 1st reassignment RUN->WALK;
        // fullCycles=7000/10000=0, remaining stays 7000 >= walkMillis(5000) -> 2nd reassignment WALK->RUN.
        // Hand-verified: this is exactly the "RUN->WALK->RUN before the next observation" case
        // Decision 6 describes -- both real `phase = ...` reassignments must produce their own event.
        val result = engine.accept(input(time = 12_000L))

        assertEquals(RoutinePhase.RUN, result.phase)
        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.RoutinePhaseChangedEvent(12_000L, RoutinePhase.RUN, RoutinePhase.WALK, 0.0),
                dev.fred.pacorun.eventlog.RoutinePhaseChangedEvent(12_000L, RoutinePhase.WALK, RoutinePhase.RUN, 0.0),
            ),
            events,
        )
    }

    @Test
    fun constantModeNeverEmitsAnEvent() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val engine = RoutineEngine(RunPreferences(), onEvent = events::add)

        engine.accept(input(time = 0L, distance = 100.0))

        assertEquals(emptyList<dev.fred.pacorun.eventlog.SessionEvent>(), events)
    }
```

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.routine.RoutineEngineTest"`
Expected: FAIL to compile, "too many arguments" / "no value passed for parameter 'onEvent'".

- [ ] **Step 3: Apply the fix in `RoutineEngine.kt`**

Add imports at the top:

```kotlin
import dev.fred.pacorun.eventlog.RoutinePhaseChangedEvent
import dev.fred.pacorun.eventlog.SessionEvent
```

Change the class declaration:

```kotlin
class RoutineEngine(private val preferences: RunPreferences, private val onEvent: (SessionEvent) -> Unit = {}) {
```

Replace `startRun`, `finishRoutine`, `advanceTo`, and their one call site inside `accept()`:

```kotlin
    private fun startRun(now: Long, speed: Float?, cues: MutableList<RoutineCue>) {
        val fromPhase = phase
        phase = RoutinePhase.RUN
        onEvent(RoutinePhaseChangedEvent(now, fromPhase, phase, totalDistanceMeters))
        intervalStartedAtMillis = now
        resetIntervalCues()
        cues += phaseCue()
        directionCue(speed, activeRange())?.let(cues::add)
    }

    private fun finishRoutine(now: Long, speed: Float?, cues: MutableList<RoutineCue>) {
        val fromPhase = phase
        phase = RoutinePhase.CONSTANT_AFTER
        onEvent(RoutinePhaseChangedEvent(now, fromPhase, phase, totalDistanceMeters))
        cues.clear()
        cues += RoutineCue.ROUTINE_END
        directionCue(speed, activeRange())?.let(cues::add)
    }

    private fun advanceTo(now: Long): Int {
        val elapsed = now - intervalStartedAtMillis
        if (elapsed < durationMillis()) return 0

        var remaining = elapsed - durationMillis()
        intervalStartedAtMillis += durationMillis()
        val firstFromPhase = phase
        phase = nextPhase()
        onEvent(RoutinePhaseChangedEvent(now, firstFromPhase, phase, totalDistanceMeters))
        val runMillis = preferences.runSeconds * 1_000L
        val walkMillis = preferences.walkSeconds * 1_000L
        val cycleMillis = runMillis + walkMillis
        val fullCycles = remaining / cycleMillis
        intervalStartedAtMillis += fullCycles * cycleMillis
        remaining -= fullCycles * cycleMillis

        var transitions = if (fullCycles > 0L) 2 else 1
        if (phase == RoutinePhase.RUN && remaining >= runMillis) {
            val secondFromPhase = phase
            phase = RoutinePhase.WALK
            onEvent(RoutinePhaseChangedEvent(now, secondFromPhase, phase, totalDistanceMeters))
            intervalStartedAtMillis += runMillis
            transitions = 2
        } else if (phase == RoutinePhase.WALK && remaining >= walkMillis) {
            val secondFromPhase = phase
            phase = RoutinePhase.RUN
            onEvent(RoutinePhaseChangedEvent(now, secondFromPhase, phase, totalDistanceMeters))
            intervalStartedAtMillis += walkMillis
            transitions = 2
        }
        resetIntervalCues()
        return transitions
    }
```

And update `finishRoutine`'s one call site inside `accept()`:

```kotlin
            if (!preferences.routineUnlimited && routineDistanceMeters >= kmToMeters(preferences.routineKm)) {
                finishRoutine(input.elapsedRealtimeMillis, reliableSpeed(input), cues)
                profileChanged = true
```

- [ ] **Step 4: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.routine.RoutineEngineTest"`
Expected: PASS (all tests, old and new — including `largeTimeJumpEmitsOnlyCurrentTransitionStateWithoutCueStorm`, whose `cues` assertion is untouched since cue logic wasn't changed).

- [ ] **Step 5: Full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt app/src/test/java/dev/fred/pacorun/routine/RoutineEngineTest.kt
git commit -m "feat: wire RoutineEngine to emit RoutinePhaseChangedEvent at every real phase reassignment"
```

---

### Task 7: `SessionCoordinator` / `PendingSessionCoordinator` — `onEvent` wiring

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt`

**Interfaces:**
- Consumes: `SessionEvent`, `AudioCueEmittedEvent`, `SpeedChainBrokenEvent`, `SpeedChainBreakReason.STALE_NO_RECENT_RELIABLE_FIX`, `DistanceChainBrokenEvent`, `DistanceChainBreakReason.GPS_OUTAGE_ENTERED` (Task 1); `DistanceAccumulator` (Task 3).
- Produces: `SessionCoordinator`'s constructor gains `private val onEvent: (SessionEvent) -> Unit = {}` (last parameter, after `onSpeedComputed`). `PendingSessionCoordinator`'s constructor gains `private val distanceEngine: SessionDistanceEngine = DistanceAccumulator()` **and** `private val onEvent: (SessionEvent) -> Unit = {}` (both new — `distanceEngine` was never exposed for injection before this task, unlike `speedEngine`, which Fase 0.5 already exposed; `PendingSessionCoordinator` forwards both into `SessionCoordinator` in `activate()`).

Three emission points inside `SessionCoordinator.process()`:
1. **`AudioCueEmittedEvent`** — one per cue, at the same point `onCues(cues, preferences.cueVolume)` is already called, using `routine.totalDistanceMeters` and `paceSample.speedKmh.takeIf { gpsReliable }`, both already local at that point (spec Decision 6).
2. **`DistanceChainBrokenEvent(GPS_OUTAGE_ENTERED)`** — at the `enteredOutage` branch, immediately before calling `distanceEngine.breakChain()`.
3. **`SpeedChainBrokenEvent(STALE_NO_RECENT_RELIABLE_FIX)`** — at the elapsed-time gate (the Task 10 fix from `2026-09-13-speed-production-wiring.md`), immediately before calling `speedEngine.breakChain()`, **guarded to fire once per outage episode**. `speedEngine.breakChain()` itself is called on every tick for the entire duration of a sustained outage — deliberately idempotent by production design (see that gate's own comment: "Chiamare breakChain() più volte nello stesso episodio è innocuo"), and this task does not touch that. But wiring `onEvent(...)` at the same call site unguarded would emit one `SpeedChainBrokenEvent` per tick (once per second) for the entire outage, which is not what this log is for. Verified against `SessionCoordinator.process()` on `feature/speed-production-wiring`: the staleness gate (`elapsedRealtimeMillis - lastReliableFixElapsedMillis >= DEFAULT_MAX_AGE_MILLIS`) is a **separate** condition from `enteredOutage`/`outageActive` (`DistanceChainBrokenEvent(GPS_OUTAGE_ENTERED)`'s own guard) — it opens on a later tick than `outageActive` first flips true (`DEFAULT_MAX_AGE_MILLIS` is 3000ms, `outageActive` flips on the very first unreliable tick), so reusing `outageActive`'s transition to gate this event does not line up with when the gate itself first opens. Instead, a dedicated boolean — `speedChainBrokenReportedForOutage`, same "already reported for this episode" pattern `GpsSilentGapTracker` (Task 10) uses for its own `reportedForCurrentGap` flag — is set `true` the first time the event fires and reset `false` on the next `actualReliable` tick (the same point that already resets `lastReliableFixElapsedMillis`), so the event fires exactly once per outage episode while `speedEngine.breakChain()` keeps firing every tick as before.

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun everyEmittedCueProducesItsOwnAudioCueEmittedEvent() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val routine = FakeRoutineEngine(mutableListOf(), profileChanged = true)
        val pace = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.TOO_FAST, 9f, Cue.TOO_FAST))
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = pace,
            onCues = { _, _ -> },
            publish = {},
            speedEngine = DopplerSpeedEngine(),
            onEvent = events::add,
        )

        coordinator.tick(1_000L, reliableSample(9f, 1_000L), LocationPosition(0.0, 0.0, 1_000L))

        val cueEvents = events.filterIsInstance<dev.fred.pacorun.eventlog.AudioCueEmittedEvent>()
        assertEquals(
            listOf("ROUTINE_START", "TOO_FAST"),
            cueEvents.map { it.cueType },
        )
        assertEquals(3.0, cueEvents[0].cumulativeDistanceMeters, .001) // FakeRoutineEngine's fixed totalDistanceMeters
        assertEquals(9f, cueEvents[0].speedKmh)
    }

    @Test
    fun enteringOutageEmitsDistanceChainBrokenEventBeforeBreakingTheDistanceChain() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = {},
            speedEngine = DopplerSpeedEngine(),
            onEvent = events::add,
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.tick(1_000L, unreliableSample(1_000L), LocationPosition(0.0, .0001, 1_000L))

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.DistanceChainBrokenEvent(
                    1_000L,
                    dev.fred.pacorun.eventlog.DistanceChainBreakReason.GPS_OUTAGE_ENTERED,
                ),
            ),
            events.filterIsInstance<dev.fred.pacorun.eventlog.DistanceChainBrokenEvent>(),
        )
    }

    @Test
    fun sustainedUnreliableStreamEmitsSpeedChainBrokenEventOnlyOnceAcrossTheWholeOutage() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val speedEngine = FakeSpeedEngine()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = {},
            speedEngine = speedEngine,
            onEvent = events::add,
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.tick(1_000L, unreliableSample(1_000L), LocationPosition(0.0, .0001, 1_000L))
        coordinator.tick(2_000L, unreliableSample(2_000L), LocationPosition(0.0, .0002, 2_000L))
        // DEFAULT_MAX_AGE_MILLIS is 3000ms -- the staleness gate first opens on this tick.
        coordinator.tick(3_000L, unreliableSample(3_000L), LocationPosition(0.0, .0003, 3_000L))
        // The gate stays open on every subsequent unreliable tick (elapsed keeps growing past the
        // threshold) -- speedEngine.breakChain() is called again each time (idempotent, unchanged
        // production behavior), but the event itself must not repeat.
        coordinator.tick(4_000L, unreliableSample(4_000L), LocationPosition(0.0, .0004, 4_000L))
        coordinator.tick(5_000L, unreliableSample(5_000L), LocationPosition(0.0, .0005, 5_000L))

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.SpeedChainBrokenEvent(
                    3_000L,
                    dev.fred.pacorun.eventlog.SpeedChainBreakReason.STALE_NO_RECENT_RELIABLE_FIX,
                ),
            ),
            events.filterIsInstance<dev.fred.pacorun.eventlog.SpeedChainBrokenEvent>(),
        )
        assertEquals(3, speedEngine.breakChainCalls) // breakChain() itself still fires every stale tick (3000/4000/5000), unguarded
    }

    @Test
    fun aFreshReliableFixAfterAnOutageAllowsTheEventToFireAgainOnTheNextOutage() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = {},
            speedEngine = FakeSpeedEngine(),
            onEvent = events::add,
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.tick(1_000L, unreliableSample(1_000L), LocationPosition(0.0, .0001, 1_000L))
        coordinator.tick(2_000L, unreliableSample(2_000L), LocationPosition(0.0, .0002, 2_000L))
        coordinator.tick(3_000L, unreliableSample(3_000L), LocationPosition(0.0, .0003, 3_000L)) // fires once
        coordinator.tick(4_000L, reliableSample(8f, 4_000L), LocationPosition(0.0, .0004, 4_000L)) // outage ends
        coordinator.tick(5_000L, unreliableSample(5_000L), LocationPosition(0.0, .0005, 5_000L))
        coordinator.tick(6_000L, unreliableSample(6_000L), LocationPosition(0.0, .0006, 6_000L))
        coordinator.tick(7_000L, unreliableSample(7_000L), LocationPosition(0.0, .0007, 7_000L)) // fires again, new episode

        assertEquals(
            listOf(3_000L, 7_000L),
            events.filterIsInstance<dev.fred.pacorun.eventlog.SpeedChainBrokenEvent>().map { it.elapsedRealtimeMillis },
        )
    }
```

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorTest"`
Expected: FAIL to compile, "no value passed for parameter 'onEvent'".

- [ ] **Step 3: Apply the fix in `RunSessionStore.kt`**

Add imports at the top:

```kotlin
import dev.fred.pacorun.eventlog.AudioCueEmittedEvent
import dev.fred.pacorun.eventlog.DistanceChainBreakReason
import dev.fred.pacorun.eventlog.DistanceChainBrokenEvent
import dev.fred.pacorun.eventlog.SessionEvent
import dev.fred.pacorun.eventlog.SpeedChainBreakReason
import dev.fred.pacorun.eventlog.SpeedChainBrokenEvent
```

Change `SessionCoordinator`'s constructor:

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

Add a new field next to the existing `outageActive`/`lastReliableFixElapsedMillis` fields — "already reported for the current stale-speed episode", same pattern `GpsSilentGapTracker` (Task 10) uses for `reportedForCurrentGap`:

```kotlin
    private var speedChainBrokenReportedForOutage = false
```

Inside `process()`, change the outage-entry block:

```kotlin
        } else if (enteredOutage) {
            latestReliableSample = null
            outageActive = true
            onEvent(DistanceChainBrokenEvent(elapsedRealtimeMillis, DistanceChainBreakReason.GPS_OUTAGE_ENTERED))
            distanceEngine.breakChain()
        }
        if (actualReliable) {
            lastReliableFixElapsedMillis = elapsedRealtimeMillis
            speedChainBrokenReportedForOutage = false
        } else if (
            !gpsReliable &&
            elapsedRealtimeMillis - lastReliableFixElapsedMillis >= LocationSampleMapper.DEFAULT_MAX_AGE_MILLIS
        ) {
            // speedEngine.breakChain() stays unconditional -- calling it repeatedly for the same
            // outage is deliberately idempotent (see this gate's own comment above, unchanged by
            // this task). The event is different: without a guard, onEvent(...) here would fire
            // once per tick for the entire outage. speedChainBrokenReportedForOutage makes it fire
            // once per episode, reset the moment a fresh reliable fix arrives above.
            if (!speedChainBrokenReportedForOutage) {
                onEvent(SpeedChainBrokenEvent(elapsedRealtimeMillis, SpeedChainBreakReason.STALE_NO_RECENT_RELIABLE_FIX))
                speedChainBrokenReportedForOutage = true
            }
            speedEngine.breakChain()
        }
```

And the cue-publishing block:

```kotlin
        val cues = routine.cues.map { AudioCue.valueOf(it.name) } + listOfNotNull(pace.cue?.let { AudioCue.valueOf(it.name) })
        if (cues.isNotEmpty()) {
            onCues(cues, preferences.cueVolume)
            cues.forEach {
                onEvent(
                    AudioCueEmittedEvent(
                        elapsedRealtimeMillis = elapsedRealtimeMillis,
                        cueType = it.name,
                        cumulativeDistanceMeters = routine.totalDistanceMeters,
                        speedKmh = paceSample.speedKmh.takeIf { gpsReliable },
                    ),
                )
            }
        }
```

Change `PendingSessionCoordinator`'s constructor and `activate()`:

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
    private var activeCoordinator: SessionCoordinator? = null

    val isActivated: Boolean get() = activeCoordinator != null

    fun tick(elapsedRealtimeMillis: Long, sample: LocationSample?, position: LocationPosition?) {
        val coordinator = activeCoordinator
        if (coordinator != null) {
            coordinator.tick(elapsedRealtimeMillis, sample, position)
            return
        }
        if (sample?.reliable == true && position != null) {
            activate(sample.elapsedRealtimeMillis).tick(sample.elapsedRealtimeMillis, sample, position)
        }
    }

    fun advanceTimer(elapsedRealtimeMillis: Long, signalGpsOutage: Boolean) {
        activeCoordinator?.advanceTimer(elapsedRealtimeMillis, signalGpsOutage)
    }

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

- [ ] **Step 4: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorTest"`
Expected: PASS (all tests, old and new — the 13 existing tests that already name `speedEngine = DopplerSpeedEngine()` explicitly are unaffected by a new trailing default parameter).

- [ ] **Step 5: Full suite + lint**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: both PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt
git commit -m "feat: wire SessionCoordinator/PendingSessionCoordinator to emit AudioCueEmitted, DistanceChainBroken, SpeedChainBroken events"
```

---

### Task 8: GPX correlation field — `TrackPoint.elapsedRealtimeMillis`

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/export/GpxWriter.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/export/GpxWriterTest.kt`

**Interfaces:**
- Produces: `TrackPoint` gains a new non-nullable field `elapsedRealtimeMillis: Long`, and its `<pr:elapsedRealtimeMillis>` extension tag.

Spec Decision 4's required minimal GPX change: `elapsedRealtimeMillis` (`SystemClock.elapsedRealtime()`-based, monotone) is the join key between the GPX and the new events log — `timeEpochMillis` (wall clock, already present) can drift/be corrected mid-session and is not reliable for that purpose. `TrackPoint` currently lives inside `GpxWriter.kt` on this branch (not a separate `TrackPoint.kt` file — verified by reading the actual file, which differs from the spec's own "codice letto" file list; both classes are edited here together).

- [ ] **Step 1: Write the failing test**

Add to `GpxWriterTest.kt`:

```kotlin
    @Test
    fun pointExtensionsCarryElapsedRealtimeMillisForCorrelationWithTheEventsLog() {
        val gpx = GpxWriter.build(
            listOf(
                TrackPoint(
                    latitude = 0.0,
                    longitude = 0.0,
                    timeEpochMillis = 0L,
                    speedMps = null,
                    horizontalAccuracyMeters = null,
                    speedAccuracyMps = null,
                    reliable = true,
                    speedEstimatorKmh = null,
                    elapsedRealtimeMillis = 123_456L,
                ),
            ),
            activityName = "Paco Run",
        )

        assertTrue(gpx.contains("<pr:elapsedRealtimeMillis>123456</pr:elapsedRealtimeMillis>"))
    }
```

Update the two constructor calls in `multiplePointsPreserveOrder` (the only test using positional args) to add the new trailing value:

```kotlin
                TrackPoint(0.0, 0.0, 0L, null, null, null, true, 0.0, 0L),
                TrackPoint(0.001, 0.001, 1_000L, null, null, null, true, 0.0, 1_000L),
                TrackPoint(0.002, 0.002, 2_000L, null, null, null, true, 0.0, 2_000L),
```

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.export.GpxWriterTest"`
Expected: FAIL to compile — the new test's named-arg call won't compile (`elapsedRealtimeMillis` unresolved), and the two positional `TrackPoint(...)` calls above will also fail once the field exists but is missing from those two lines... actually at this step the field doesn't exist yet, so only the new test's named argument fails to compile. Confirm: "no value passed for parameter 'elapsedRealtimeMillis'" is NOT yet the error since the field doesn't exist — the actual error is "unresolved reference" for the named arg in the new test.

- [ ] **Step 3: Apply the fix in `GpxWriter.kt`**

```kotlin
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val timeEpochMillis: Long,
    val speedMps: Float?,
    val horizontalAccuracyMeters: Float?,
    val speedAccuracyMps: Float?,
    val reliable: Boolean,
    val speedEstimatorKmh: Double?,
    // SystemClock.elapsedRealtime()-based, monotone timestamp of this exact fix -- the join key
    // against the new SessionEvent log (which also keys on elapsedRealtimeMillis, not wall
    // clock). See session-event-logging-design.md Decision 4.
    val elapsedRealtimeMillis: Long,
)
```

Add the extension tag in `appendTrackPoint`:

```kotlin
        point.speedEstimatorKmh?.let { appendLine("          <pr:speedEstimatorKmh>$it</pr:speedEstimatorKmh>") }
        appendLine("          <pr:elapsedRealtimeMillis>${point.elapsedRealtimeMillis}</pr:elapsedRealtimeMillis>")
```

- [ ] **Step 4: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.export.GpxWriterTest"`
Expected: PASS.

- [ ] **Step 5: Full suite**

Run: `./gradlew testDebugUnitTest`
Expected: FAIL — `RunSessionService.kt`'s `onLocation()` constructs `TrackPoint(...)` with named args and is now missing the required `elapsedRealtimeMillis` argument (this will be fixed in Task 14, which rewrites `onLocation()` anyway). For this task alone, add the field at its call site with a minimal, behavior-preserving value so the build stays green until Task 14 does the fuller rewrite:

In `RunSessionService.kt`'s `onLocation()`, add one argument to the existing `TrackPoint(...)` call:

```kotlin
        trackPoints += TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timeEpochMillis = location.time,
            speedMps = location.takeIf { it.hasSpeed() }?.speed,
            horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy,
            speedAccuracyMps = location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond,
            reliable = sample.reliable,
            speedEstimatorKmh = lastEngineSpeedKmh,
            elapsedRealtimeMillis = position.elapsedRealtimeMillis,
        )
```

- [ ] **Step 6: Run the full suite again**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: both PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/export/GpxWriter.kt app/src/test/java/dev/fred/pacorun/export/GpxWriterTest.kt app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt
git commit -m "feat: add elapsedRealtimeMillis to GPX TrackPoint as the join key with the events log"
```

---

### Task 9: `LocationSampleMapper` — `RejectionReason` list + `QualityTier`

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/location/LocationSampleMapperTest.kt`

**Interfaces:**
- Produces: `data class LocationReliability(val reliable: Boolean, val reasons: List<RejectionReason>)`, `LocationSampleMapper.evaluate(speedMps: Float?, accuracyMps: Float?, ageMillis: Long, horizontalAccuracyMeters: Float? = null): LocationReliability`, `LocationSampleMapper.qualityTier(reliability: LocationReliability, accuracyMps: Float?, horizontalAccuracyMeters: Float?): QualityTier`. `map()`'s existing signature and behavior are **unchanged** (spec 5.2: "che resta invariato per i chiamanti attuali") — it now delegates its `reliable` boolean to `evaluate(...).reliable` internally.

Per spec's mapping table (5.2), `reasons` can hold more than one entry per fix — hence a `List`, not a single value. `QualityTier`: `SCARSA` when 2+ reasons, **or** when the single reason present is a "no data at all" reason (`MISSING_OR_INVALID_SPEED`/`MISSING_OR_INVALID_SPEED_ACCURACY`, distinct from a value merely exceeding a threshold); `DEGRADATA` for exactly one threshold-exceeded reason; `BUONA`/`OTTIMA` split by the "half the configured threshold" margin the spec names as the one genuinely new cut (5.2, flagged there as an open question for the owner — see this plan's final report).

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun evaluateProducesNoReasonsWhenEverythingIsWithinBounds() {
        val reliability = mapper.evaluate(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 0L)

        assertTrue(reliability.reliable)
        assertEquals(emptyList<dev.fred.pacorun.eventlog.RejectionReason>(), reliability.reasons)
    }

    @Test
    fun evaluateCanProduceMultipleReasonsForTheSameFix() {
        // Both speed AND its accuracy are missing at once -- the spec explicitly calls for a
        // list, not a single value, exactly for this case.
        val reliability = mapper.evaluate(speedMps = null, accuracyMps = null, ageMillis = 0L)

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.RejectionReason.MISSING_OR_INVALID_SPEED,
                dev.fred.pacorun.eventlog.RejectionReason.MISSING_OR_INVALID_SPEED_ACCURACY,
            ),
            reliability.reasons,
        )
    }

    @Test
    fun evaluateMatchesMapsExistingReliableBooleanForEveryThreshold() {
        assertEquals(mapper.map(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 0L).reliable, mapper.evaluate(2.5f, .1f, 0L).reliable)
        assertEquals(mapper.map(speedMps = null, accuracyMps = .1f, ageMillis = 0L).reliable, mapper.evaluate(null, .1f, 0L).reliable)
        assertEquals(mapper.map(speedMps = 2.5f, accuracyMps = .5f, ageMillis = 0L).reliable, mapper.evaluate(2.5f, .5f, 0L).reliable)
        assertEquals(mapper.map(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 3_001L).reliable, mapper.evaluate(2.5f, .1f, 3_001L).reliable)
        assertEquals(
            mapper.map(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 0L, horizontalAccuracyMeters = 25f).reliable,
            mapper.evaluate(2.5f, .1f, 0L, 25f).reliable,
        )
    }

    @Test
    fun qualityTierIsOttimaWhenWellWithinHalfEveryThreshold() {
        val reliability = mapper.evaluate(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 0L, horizontalAccuracyMeters = 5f)

        assertEquals(dev.fred.pacorun.eventlog.QualityTier.OTTIMA, mapper.qualityTier(reliability, .1f, 5f))
    }

    @Test
    fun qualityTierIsBuonaWhenReliableButNotWithinHalfThreshold() {
        // accuracyMps = .3f is <= maxSpeedAccuracyMps (.35f) but > half of it (.175f).
        val reliability = mapper.evaluate(speedMps = 2.5f, accuracyMps = .3f, ageMillis = 0L)

        assertEquals(dev.fred.pacorun.eventlog.QualityTier.BUONA, mapper.qualityTier(reliability, .3f, null))
    }

    @Test
    fun qualityTierIsDegradataForExactlyOneThresholdExceededReason() {
        val reliability = mapper.evaluate(speedMps = 2.5f, accuracyMps = .5f, ageMillis = 0L)

        assertEquals(dev.fred.pacorun.eventlog.QualityTier.DEGRADATA, mapper.qualityTier(reliability, .5f, null))
    }

    @Test
    fun qualityTierIsScarsaForTwoOrMoreReasons() {
        val reliability = mapper.evaluate(speedMps = 2.5f, accuracyMps = .5f, ageMillis = 3_001L)

        assertEquals(dev.fred.pacorun.eventlog.QualityTier.SCARSA, mapper.qualityTier(reliability, .5f, null))
    }

    @Test
    fun qualityTierIsScarsaWhenTheSingleReasonIsMissingDataNotJustAThresholdMiss() {
        val reliability = mapper.evaluate(speedMps = null, accuracyMps = .1f, ageMillis = 0L)

        assertEquals(dev.fred.pacorun.eventlog.QualityTier.SCARSA, mapper.qualityTier(reliability, .1f, null))
    }
```

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.location.LocationSampleMapperTest"`
Expected: FAIL to compile, "unresolved reference: evaluate" / "unresolved reference: qualityTier".

- [ ] **Step 3: Apply the fix in `LocationSampleMapper.kt`**

```kotlin
package dev.fred.pacorun.location

import android.location.Location
import dev.fred.pacorun.eventlog.QualityTier
import dev.fred.pacorun.eventlog.RejectionReason
import java.util.concurrent.TimeUnit

class LocationSampleMapper(
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val maxSpeedAccuracyMps: Float = .35f,
    private val maxHorizontalAccuracyMeters: Float = 20f,
) {
    fun map(location: Location, nowElapsedRealtimeMillis: Long): LocationSample {
        val elapsedRealtimeMillis = TimeUnit.NANOSECONDS.toMillis(location.elapsedRealtimeNanos)
        return map(
            speedMps = location.takeIf { it.hasSpeed() }?.speed,
            accuracyMps = location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond,
            ageMillis = nowElapsedRealtimeMillis - elapsedRealtimeMillis,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy,
        )
    }

    fun map(
        speedMps: Float?,
        accuracyMps: Float?,
        ageMillis: Long,
        elapsedRealtimeMillis: Long = 0L,
        horizontalAccuracyMeters: Float? = null,
    ): LocationSample {
        val reliability = evaluate(speedMps, accuracyMps, ageMillis, horizontalAccuracyMeters)
        return LocationSample(
            speedKmh = (speedMps ?: 0f) * 3.6f,
            speedAccuracyKmh = accuracyMps?.times(3.6f),
            reliable = reliability.reliable,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
        )
    }

    // Exposed separately from map() (whose own return type/behavior stays unchanged for its
    // existing callers) so a caller that also wants to know WHICH threshold(s) failed -- for a
    // diagnostic LocationFixEvent -- can ask, without map() changing shape.
    fun evaluate(
        speedMps: Float?,
        accuracyMps: Float?,
        ageMillis: Long,
        horizontalAccuracyMeters: Float? = null,
    ): LocationReliability {
        val reasons = buildList {
            if (speedMps == null || !speedMps.isFinite() || speedMps < 0f) add(RejectionReason.MISSING_OR_INVALID_SPEED)
            if (accuracyMps == null || !accuracyMps.isFinite() || accuracyMps < 0f) {
                add(RejectionReason.MISSING_OR_INVALID_SPEED_ACCURACY)
            } else if (accuracyMps > maxSpeedAccuracyMps) {
                add(RejectionReason.SPEED_ACCURACY_TOO_HIGH)
            }
            if (horizontalAccuracyMeters != null &&
                (!horizontalAccuracyMeters.isFinite() || horizontalAccuracyMeters > maxHorizontalAccuracyMeters)
            ) {
                add(RejectionReason.HORIZONTAL_ACCURACY_TOO_HIGH)
            }
            if (ageMillis !in 0..maxAgeMillis) add(RejectionReason.FIX_TOO_OLD)
        }
        return LocationReliability(reliable = reasons.isEmpty(), reasons = reasons)
    }

    // Readable projection of thresholds that already exist elsewhere in this class -- not a new
    // judgment except for the OTTIMA/BUONA "half the configured threshold" margin, called out
    // explicitly in the spec (5.2) as the one genuinely new cut, open for the owner to revise.
    fun qualityTier(
        reliability: LocationReliability,
        accuracyMps: Float?,
        horizontalAccuracyMeters: Float?,
    ): QualityTier = when {
        reliability.reasons.size >= 2 || reliability.reasons.any { it in MISSING_DATA_REASONS } -> QualityTier.SCARSA
        reliability.reasons.size == 1 -> QualityTier.DEGRADATA
        accuracyMps != null &&
            accuracyMps <= maxSpeedAccuracyMps / 2f &&
            (horizontalAccuracyMeters == null || horizontalAccuracyMeters <= maxHorizontalAccuracyMeters / 2f) -> QualityTier.OTTIMA
        else -> QualityTier.BUONA
    }

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS = 3_000L
        private val MISSING_DATA_REASONS = setOf(
            RejectionReason.MISSING_OR_INVALID_SPEED,
            RejectionReason.MISSING_OR_INVALID_SPEED_ACCURACY,
        )
    }
}

data class LocationReliability(val reliable: Boolean, val reasons: List<RejectionReason>)
```

- [ ] **Step 4: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.location.LocationSampleMapperTest"`
Expected: PASS (all tests, old and new — every existing test asserts only on `.reliable`, unaffected by the internal delegation to `evaluate()`).

- [ ] **Step 5: Full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt app/src/test/java/dev/fred/pacorun/location/LocationSampleMapperTest.kt
git commit -m "feat: expose RejectionReason list and QualityTier from LocationSampleMapper for LocationFixEvent"
```

---

### Task 10: `GpsSilentGapTracker` — sister of `GpsFreshnessTracker`

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`
- New: `app/src/test/java/dev/fred/pacorun/service/GpsSilentGapTrackerTest.kt`

**Interfaces:**
- Produces: `class GpsSilentGapTracker(private val thresholdMillis: Long = 5_000L) { fun reset(startElapsedRealtimeMillis: Long); fun recordCallback(elapsedRealtimeMillis: Long); fun checkGap(nowElapsedRealtimeMillis: Long): Long? }` — consumed by Task 15.

Per spec 5.3: distinct from "a fix arrived but was rejected" (that's a `LocationFixEvent` with `reliable == false`) — this tracks the case where **no** callback of any kind arrives for an anomalous interval, checked periodically (the existing 1Hz ticker) since a missing callback can't announce itself. `checkGap()` returns the gap once per silent episode (not once per second the gap continues), and `reset()`/`recordCallback()` both clear that "already reported" flag so a fresh gap after any callback can be reported again. Threshold defaults to 5000ms (5× the 1000ms tick cadence) per spec's own proposed starting value — flagged there as an open question for the owner (see this plan's final report).

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.fred.pacorun.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsSilentGapTrackerTest {
    @Test
    fun noGapBeforeThresholdReturnsNull() {
        val tracker = GpsSilentGapTracker(thresholdMillis = 5_000L)
        tracker.reset(0L)

        assertNull(tracker.checkGap(4_999L))
    }

    @Test
    fun gapAtOrAboveThresholdReturnsItOnce() {
        val tracker = GpsSilentGapTracker(thresholdMillis = 5_000L)
        tracker.reset(0L)

        assertEquals(5_000L, tracker.checkGap(5_000L))
        assertNull(tracker.checkGap(6_000L)) // same silent episode -- already reported
    }

    @Test
    fun aNewCallbackAllowsANewGapToBeReportedAgain() {
        val tracker = GpsSilentGapTracker(thresholdMillis = 5_000L)
        tracker.reset(0L)
        tracker.checkGap(5_000L)

        tracker.recordCallback(6_000L)

        assertNull(tracker.checkGap(10_999L))
        assertEquals(5_000L, tracker.checkGap(11_000L))
    }

    @Test
    fun resetEstablishesTheBaselineForAPreFirstFixGap() {
        val tracker = GpsSilentGapTracker(thresholdMillis = 5_000L)

        tracker.reset(1_000L)

        assertNull(tracker.checkGap(5_999L))
        assertEquals(5_000L, tracker.checkGap(6_000L))
    }
}
```

- [ ] **Step 2: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.GpsSilentGapTrackerTest"`
Expected: FAIL, "unresolved reference: GpsSilentGapTracker".

- [ ] **Step 3: Add the class to `RunSessionStore.kt`**, right after `GpsFreshnessTracker`:

```kotlin
class GpsSilentGapTracker(private val thresholdMillis: Long = 5_000L) {
    private var lastCallbackElapsedRealtimeMillis: Long = 0L
    private var reportedForCurrentGap = false

    fun reset(startElapsedRealtimeMillis: Long) {
        lastCallbackElapsedRealtimeMillis = startElapsedRealtimeMillis
        reportedForCurrentGap = false
    }

    fun recordCallback(elapsedRealtimeMillis: Long) {
        lastCallbackElapsedRealtimeMillis = elapsedRealtimeMillis
        reportedForCurrentGap = false
    }

    /** Returns the elapsed gap once, the first time it crosses the threshold; null otherwise (including for its continuation). */
    fun checkGap(nowElapsedRealtimeMillis: Long): Long? {
        if (reportedForCurrentGap) return null
        val gap = nowElapsedRealtimeMillis - lastCallbackElapsedRealtimeMillis
        if (gap < thresholdMillis) return null
        reportedForCurrentGap = true
        return gap
    }
}
```

- [ ] **Step 4: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.GpsSilentGapTrackerTest"`
Expected: PASS.

- [ ] **Step 5: Full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt app/src/test/java/dev/fred/pacorun/service/GpsSilentGapTrackerTest.kt
git commit -m "feat: add GpsSilentGapTracker for GpsSilentGapEvent detection"
```

---

### Task 11: `SessionEventLogger` — Android incremental JSONL writer

**Files:**
- New: `app/src/main/java/dev/fred/pacorun/service/SessionEventLogger.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/export/GpxExporter.kt`

**Interfaces:**
- Produces: `class SessionEventLogger(context: Context) { fun start(fileName: String); fun log(event: SessionEvent); fun stop() }`; `GpxExporter.eventLogFileNameFor(startedAt: Instant): String`.

Per Decision 8: one `MediaStore` output stream opened once at session start and kept open, appending one JSONL line per event as it happens (not accumulated in memory and flushed once at the end — that would lose the *entire* log on a mid-session crash, defeating this log's whole purpose). Every write wrapped in its own `try/catch`, matching `GpxExporter`'s existing best-effort discipline (a full disk or restrictive OEM storage behavior must never crash the app over a diagnostic log). Android-only, `Context`/`MediaStore`-dependent — **no dedicated unit test**, same precedent as `GpxExporter.kt` itself (which has no test file in this project either). Verified by the full suite staying green (nothing here is exercised by JVM tests) plus lint, plus manual/field verification per this plan's Global Constraints.

- [ ] **Step 1: Add `eventLogFileNameFor` to `GpxExporter.kt`**

```kotlin
object GpxExporter {
    private val fileNameTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    fun fileNameFor(startedAt: Instant): String = "paco-run-${timestampFor(startedAt)}.gpx"

    // Same timestamp basis as fileNameFor(), so the two files stay associable by name (in
    // addition to elapsedRealtimeMillis) -- session-event-logging-design.md Decision 8.
    fun eventLogFileNameFor(startedAt: Instant): String = "paco-run-${timestampFor(startedAt)}-events.jsonl"

    private fun timestampFor(startedAt: Instant) =
        fileNameTimestamp.format(startedAt.atZone(java.time.ZoneId.systemDefault()))

    fun writeToDownloads(context: Context, fileName: String, contents: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { it.write(contents.toByteArray(Charsets.UTF_8)) } ?: return false
        return true
    }
}
```

- [ ] **Step 2: Write `SessionEventLogger.kt`**

```kotlin
package dev.fred.pacorun.service

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dev.fred.pacorun.eventlog.SessionEvent
import dev.fred.pacorun.eventlog.SessionEventWriter
import java.io.OutputStream

// Android-level sink for SessionEvent: opens one MediaStore Downloads file per session and
// appends one JSON Lines row per event as it happens (Decision 8 -- incremental, not buffered
// to a single end-of-session write, so a crash mid-session still leaves a readable partial log).
// Every write is wrapped in its own try/catch, matching GpxExporter's best-effort discipline --
// a full disk or restrictive OEM storage behavior must never crash the app over a diagnostic log.
class SessionEventLogger(private val context: Context) {
    private var outputStream: OutputStream? = null

    fun start(fileName: String) {
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/jsonl")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            outputStream = resolver.openOutputStream(uri, "w")
        } catch (_: Exception) {
        }
    }

    fun log(event: SessionEvent) {
        try {
            outputStream?.write((SessionEventWriter.toJsonLine(event) + "\n").toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            outputStream?.close()
        } catch (_: Exception) {
        }
        outputStream = null
    }
}
```

- [ ] **Step 3: Compile + full suite**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: both PASS (new file, nothing references it yet).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/SessionEventLogger.kt app/src/main/java/dev/fred/pacorun/export/GpxExporter.kt
git commit -m "feat: add SessionEventLogger, an incremental MediaStore JSONL writer for SessionEvent"
```

---

### Task 12: `app/build.gradle.kts` + `AndroidManifest.xml` — git commit hash, network permission

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`

Per spec Decision 7: `buildConfig = true` (currently `false`, verified) plus a `GIT_COMMIT_HASH` field populated by `git rev-parse --short=12 HEAD` at build time, with an explicit `"unknown"` fallback for a CI environment without `.git`. `ACCESS_NETWORK_STATE` is required by `ConnectivityManager.getNetworkCapabilities()` (Task 13) and is **not currently declared** in the manifest (verified by reading it) — a real gap Task 13 would otherwise hit at runtime.

- [ ] **Step 1: Edit `app/build.gradle.kts`**

Add a top-level function (above the `android {}` block):

```kotlin
fun gitCommitHash(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short=12", "HEAD")
        .redirectErrorStream(true)
        .start()
    process.waitFor()
    process.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
} catch (e: Exception) {
    "unknown"
}
```

Change `buildConfig = false` to `buildConfig = true`:

```kotlin
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }
```

Add the field inside `defaultConfig { ... }`:

```kotlin
    defaultConfig {
        applicationId = "dev.fred.pacorun"
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GIT_COMMIT_HASH", "\"${gitCommitHash()}\"")
    }
```

- [ ] **Step 2: Edit `AndroidManifest.xml`**

```xml
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 3: Build + full suite**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: both PASS. `dev.fred.pacorun.BuildConfig.GIT_COMMIT_HASH` is now generated and usable from Task 13.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "feat: enable BuildConfig git commit hash and declare ACCESS_NETWORK_STATE for SessionStarted"
```

---

### Task 13: `RunSessionService` — device/sensor/network capture, `SessionStarted`, `SessionEventLogger` lifecycle, `onEvent` threading

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt`

**Interfaces:**
- Consumes: `SessionEventLogger` (Task 11), `SessionStarted`/`SensorAvailability`/`NetworkType`/`AppLifecycleChangedEvent`/`LifecycleTransition` (Task 1), `GpxExporter.eventLogFileNameFor` (Task 11), `BuildConfig.GIT_COMMIT_HASH` (Task 12), `RoutineEngine`/`PaceEvaluator`/`SpeedEstimator`/`DistanceAccumulator`'s new `onEvent` params (Tasks 3-6), `PendingSessionCoordinator`'s new `distanceEngine`/`onEvent` params (Task 7).
- Produces: `RunSessionService` gains new private fields (`sessionStartedAtElapsedMillis`, `sessionStartedAtInstant`, `eventLogger`, `onEvent`, `speedEstimatorForDiagnostics`, `gnssStatusCallback`, `lastGnssSnapshot`, `locationSequenceNumber`) — consumed by this task and by Tasks 14-16.

No dedicated unit test (Android `Service`, `Context`-dependent — same precedent as the rest of `RunSessionService.kt`, which has no test file). Verified by the full suite staying green + lint + manual/field verification.

**A deliberate, small judgment call beyond the letter of the spec:** the spec doesn't say where `sessionStartedAtInstant` (used to name the events file) comes from. This task also switches `exportTrackAsGpx()`'s filename basis from `points.first().timeEpochMillis` (the first GPS fix's wall time, a few seconds after session start) to this same captured session-start instant — so the GPX and events files share a byte-identical timestamp in their names, matching Decision 8's "stesso timestamp" literally, not just approximately. `exportTrackAsGpx()` has no dedicated test (verified), so this is safe.

- [ ] **Step 1: Replace the full `RunSessionService.kt` file**

```kotlin
package dev.fred.pacorun.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.fred.pacorun.BuildConfig
import dev.fred.pacorun.R
import dev.fred.pacorun.audio.CuePlayer
import dev.fred.pacorun.eventlog.AppLifecycleChangedEvent
import dev.fred.pacorun.eventlog.LifecycleTransition
import dev.fred.pacorun.eventlog.NetworkType
import dev.fred.pacorun.eventlog.SensorAvailability
import dev.fred.pacorun.eventlog.SessionEvent
import dev.fred.pacorun.eventlog.SessionStarted
import dev.fred.pacorun.export.GpxExporter
import dev.fred.pacorun.export.GpxWriter
import dev.fred.pacorun.export.TrackPoint
import dev.fred.pacorun.location.LocationSampleMapper
import dev.fred.pacorun.pace.PaceConfig
import dev.fred.pacorun.pace.PaceEvaluator
import dev.fred.pacorun.routine.RoutineEngine
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.settings.RunPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.TimeUnit

class RunSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var notificationManager: NotificationManager
    private var cuePlayer: CuePlayer? = null
    private var coordinator: PendingSessionCoordinator? = null
    private var locationRequested = false
    private var starting = false
    private var sessionToken = 0L
    private var lastTimerSecond = Long.MIN_VALUE
    private var lastLocationElapsedRealtimeNanos = Long.MIN_VALUE
    private var gpsFreshness = GpsFreshnessTracker()
    private var gpsSilentGap = GpsSilentGapTracker()
    private var lastDeviceHealthBucket = Long.MIN_VALUE
    // Diagnostic-only: captures the shared production engine's output for the current fix, so
    // the exported TrackPoint can reuse it -- see onLocation() and the onSpeedComputed wiring.
    private var lastEngineSpeedKmh: Double? = null
    private val notificationTracker = SessionNotificationTracker()
    private val trackPoints = mutableListOf<TrackPoint>()

    // -- P1 session event logging state --
    private var sessionStartedAtElapsedMillis: Long = 0L
    private var sessionStartedAtInstant: Instant = Instant.EPOCH
    private var eventLogger: SessionEventLogger? = null
    private var onEvent: (SessionEvent) -> Unit = {}
    private var locationSequenceNumber = 0
    private var speedEstimatorForDiagnostics: SpeedEstimator? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var lastGnssSnapshot: GnssSnapshot? = null

    private data class GnssSnapshot(
        val satellitesUsedInFix: Int,
        val cn0MinDbHz: Float,
        val cn0AvgDbHz: Float,
        val cn0MaxDbHz: Float,
    )

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tickTimer(SystemClock.elapsedRealtime())
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_START -> if (!starting && coordinator == null) startSession()
            ACTION_APP_FOREGROUNDED ->
                onEvent(AppLifecycleChangedEvent(SystemClock.elapsedRealtime(), LifecycleTransition.FOREGROUNDED))
            ACTION_APP_BACKGROUNDED ->
                onEvent(AppLifecycleChangedEvent(SystemClock.elapsedRealtime(), LifecycleTransition.BACKGROUNDED))
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSession() {
        if (!SessionStartPrerequisites(
                hasFineLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            ).canStartLocationForegroundService
        ) {
            stopSession()
            return
        }
        starting = true
        val token = ++sessionToken
        val acquiringGps = RunSessionState.AcquiringGps
        RunSessionStore.publish(acquiringGps)
        startForeground(NOTIFICATION_ID, notification(notificationTracker.textFor(acquiringGps)))
        notificationTracker.markDisplayed(acquiringGps)
        scope.launch {
            val preferences = RunPreferencesStore(this@RunSessionService).preferences.first()
            if (token != sessionToken) return@launch
            if (!preferences.isValid) {
                stopSession()
                return@launch
            }
            beginTracking(preferences)
        }
    }

    private fun beginTracking(preferences: RunPreferences) {
        cuePlayer = CuePlayer(this)
        sessionStartedAtElapsedMillis = SystemClock.elapsedRealtime()
        sessionStartedAtInstant = Instant.now()
        locationSequenceNumber = 0
        lastGnssSnapshot = null
        lastDeviceHealthBucket = Long.MIN_VALUE

        eventLogger = SessionEventLogger(applicationContext)
        eventLogger?.start(GpxExporter.eventLogFileNameFor(sessionStartedAtInstant))
        onEvent = { eventLogger?.log(it) }

        val marker = restartMarkerPrefs()
        if (marker.getBoolean(KEY_SESSION_OPEN, false)) {
            onEvent(AppLifecycleChangedEvent(sessionStartedAtElapsedMillis, LifecycleTransition.SERVICE_RESTARTED_BY_SYSTEM))
        }
        marker.edit().putBoolean(KEY_SESSION_OPEN, true).apply()

        onEvent(
            SessionStarted(
                elapsedRealtimeMillis = sessionStartedAtElapsedMillis,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                osVersionRelease = Build.VERSION.RELEASE,
                osApiLevel = Build.VERSION.SDK_INT,
                appGitCommitHash = BuildConfig.GIT_COMMIT_HASH,
                preferences = preferences,
                sensors = sensorAvailability(),
                networkTypeAtStart = currentNetworkType(),
            ),
        )

        val speedEstimator = SpeedEstimator(onEvent = onEvent)
        speedEstimatorForDiagnostics = speedEstimator

        coordinator = PendingSessionCoordinator(
            preferences = preferences,
            routineEngine = RoutineEngineAdapter(RoutineEngine(preferences, onEvent = onEvent)),
            paceEvaluator = PaceEvaluatorAdapter(PaceEvaluator(preferences.paceConfig(), onEvent = onEvent)),
            onCues = { cues, volume -> cuePlayer?.enqueue(cues, volume) },
            publish = ::publish,
            distanceEngine = DistanceAccumulator(onEvent = onEvent),
            speedEngine = KalmanSpeedEngine(speedEstimator),
            onSpeedComputed = { lastEngineSpeedKmh = it },
            onEvent = onEvent,
        )
        lastTimerSecond = Long.MIN_VALUE
        lastLocationElapsedRealtimeNanos = Long.MIN_VALUE
        gpsFreshness = GpsFreshnessTracker()
        gpsSilentGap = GpsSilentGapTracker().also { it.reset(sessionStartedAtElapsedMillis) }
        trackPoints.clear()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSession()
            return
        }
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest(), locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSession()
            return
        }
        registerGnssStatusCallback()
        locationRequested = true
        handler.post(ticker)
        starting = false
    }

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

    private fun sensorAvailability(): SensorAvailability {
        val sensorManager = getSystemService(SensorManager::class.java)
        fun has(type: Int) = sensorManager?.getDefaultSensor(type) != null
        return SensorAvailability(
            hasBarometer = has(Sensor.TYPE_PRESSURE),
            hasMagnetometer = has(Sensor.TYPE_MAGNETIC_FIELD),
            hasAccelerometer = has(Sensor.TYPE_ACCELEROMETER),
            hasGyroscope = has(Sensor.TYPE_GYROSCOPE),
            hasStepCounter = has(Sensor.TYPE_STEP_COUNTER),
            hasStepDetector = has(Sensor.TYPE_STEP_DETECTOR),
        )
    }

    private fun currentNetworkType(): NetworkType {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return NetworkType.OFFLINE
        val network = connectivityManager.activeNetwork ?: return NetworkType.OFFLINE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.OFFLINE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            else -> NetworkType.OTHER
        }
    }

    private fun restartMarkerPrefs(): SharedPreferences =
        getSharedPreferences("session_event_logging", MODE_PRIVATE)

    private fun onLocation(location: Location) {
        // Unchanged from Task 8 as of this task -- Task 14 extends this same function further
        // (LocationFixEvent construction), shown there as its own full replacement.
        if (location.elapsedRealtimeNanos <= lastLocationElapsedRealtimeNanos) return
        lastLocationElapsedRealtimeNanos = location.elapsedRealtimeNanos
        val now = SystemClock.elapsedRealtime()
        val sample = LocationSampleMapper().map(location, now)
        gpsFreshness.record(sample)
        val position = LocationPosition(
            latitude = location.latitude,
            longitude = location.longitude,
            elapsedRealtimeMillis = TimeUnit.NANOSECONDS.toMillis(location.elapsedRealtimeNanos),
            horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy,
        )
        lastEngineSpeedKmh = null
        coordinator?.tick(elapsedRealtimeMillis = now, sample = sample, position = position)
        trackPoints += TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timeEpochMillis = location.time,
            speedMps = location.takeIf { it.hasSpeed() }?.speed,
            horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy,
            speedAccuracyMps = location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond,
            reliable = sample.reliable,
            speedEstimatorKmh = lastEngineSpeedKmh,
            elapsedRealtimeMillis = position.elapsedRealtimeMillis,
        )
    }

    private fun tickTimer(elapsedRealtimeMillis: Long) {
        val second = elapsedRealtimeMillis / TICK_MILLIS
        if (second == lastTimerSecond) return
        lastTimerSecond = second
        val gpsOutage = gpsFreshness.isOutage(elapsedRealtimeMillis)
        coordinator?.advanceTimer(elapsedRealtimeMillis, signalGpsOutage = gpsOutage)
    }

    private fun publish(state: RunSessionState) {
        RunSessionStore.publish(state)
        if (notificationTracker.shouldUpdate(state)) {
            notificationManager.notify(NOTIFICATION_ID, notification(notificationTracker.textFor(state)))
        }
    }

    private fun stopSession() {
        exportTrackAsGpx()
        gnssStatusCallback?.let { getSystemService(LocationManager::class.java)?.unregisterGnssStatusCallback(it) }
        gnssStatusCallback = null
        lastGnssSnapshot = null
        restartMarkerPrefs().edit().putBoolean(KEY_SESSION_OPEN, false).apply()
        eventLogger?.stop()
        eventLogger = null
        onEvent = {}
        speedEstimatorForDiagnostics = null
        sessionToken++
        handler.removeCallbacks(ticker)
        if (locationRequested) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationRequested = false
        }
        coordinator = null
        starting = false
        cuePlayer?.close()
        cuePlayer = null
        RunSessionStore.publish(RunSessionState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun exportTrackAsGpx() {
        if (trackPoints.isEmpty()) return
        val points = trackPoints.toList()
        val context = applicationContext
        val fileName = GpxExporter.fileNameFor(sessionStartedAtInstant)
        Thread {
            try {
                val gpx = GpxWriter.build(points, activityName = "Paco Run")
                GpxExporter.writeToDownloads(context, fileName, gpx)
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun locationRequest(): LocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TICK_MILLIS)
        .setMinUpdateIntervalMillis(TICK_MILLIS)
        .setMaxUpdateDelayMillis(TICK_MILLIS)
        .setWaitForAccurateLocation(true)
        .build()

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_run)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .addAction(0, "Termina", stopPendingIntent())
        .build()

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, RunSessionService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sessione di corsa", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun RunPreferences.paceConfig() = PaceConfig(
        minKmh = constantRange.minKmh,
        maxKmh = constantRange.maxKmh,
        confirmationSamples = confirmationSamples,
        repeatMillis = repeatMillis,
        gpsCuesEnabled = gpsCuesEnabled,
    )

    companion object {
        const val ACTION_START = "dev.fred.pacorun.action.START_RUN_SESSION"
        const val ACTION_STOP = "dev.fred.pacorun.action.STOP_RUN_SESSION"
        const val ACTION_APP_FOREGROUNDED = "dev.fred.pacorun.action.APP_FOREGROUNDED"
        const val ACTION_APP_BACKGROUNDED = "dev.fred.pacorun.action.APP_BACKGROUNDED"
        private const val CHANNEL_ID = "run_session"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MILLIS = 1_000L
        private const val KEY_SESSION_OPEN = "session_open"
    }
}
```

Note: `currentBatteryPercent()`/`currentThermalStatus()`/`sampleDeviceHealthIfDue()` are added in Task 15, not here — `tickTimer()` above is still Task 12-era (unchanged from before this task) and gets its own full replacement in Task 15. `onLocation()` above is the Task 8-era version plus the new `lastGnssSnapshot`/sequence-number groundwork is deliberately **not yet used** here — Task 14 replaces `onLocation()` fully to add `LocationFixEvent` construction.

- [ ] **Step 2: Full suite + lint**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: both PASS (no JVM test exercises `RunSessionService`; this verifies compilation and that nothing else broke).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt
git commit -m "feat: capture device/sensor/network info and emit SessionStarted via SessionEventLogger"
```

---

### Task 14: `RunSessionService` — `LocationFixEvent` in `onLocation()`

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt`

**Interfaces:**
- Consumes: `LocationSampleMapper.evaluate()`/`qualityTier()` (Task 9), `SpeedEstimator.getLastProcessNoiseRegime()`/`getLastInnovationEma()`/`getLastAccuracyGateEngaged()` (Task 4, existing), `LocationFixEvent`/`SpeedEstimatorDiagnostics` (Task 1).

Per spec 5.2: `speedEstimatorDiagnostics` is populated **only** when the fix actually went through `SpeedEstimator.accept()`'s `predict()`/`update()` path — i.e. when the fix was reliable — never after a `breakChain()` for this same fix, since `lastProcessNoise`/`lastAccuracyGateEngaged` are not reset by `breakChain()` and would otherwise report a stale value from a previous fix as if it were current (spec's own documented reasoning, 5.2). No dedicated unit test — same precedent as Task 13.

- [ ] **Step 1: Replace `onLocation()` in `RunSessionService.kt`**

```kotlin
    private fun onLocation(location: Location) {
        if (location.elapsedRealtimeNanos <= lastLocationElapsedRealtimeNanos) return
        lastLocationElapsedRealtimeNanos = location.elapsedRealtimeNanos
        val now = SystemClock.elapsedRealtime()
        val mapper = LocationSampleMapper()
        val sample = mapper.map(location, now)
        gpsFreshness.record(sample)
        gpsSilentGap.recordCallback(now)
        val position = LocationPosition(
            latitude = location.latitude,
            longitude = location.longitude,
            elapsedRealtimeMillis = TimeUnit.NANOSECONDS.toMillis(location.elapsedRealtimeNanos),
            horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy,
        )
        lastEngineSpeedKmh = null
        coordinator?.tick(elapsedRealtimeMillis = now, sample = sample, position = position)
        trackPoints += TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timeEpochMillis = location.time,
            speedMps = location.takeIf { it.hasSpeed() }?.speed,
            horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy,
            speedAccuracyMps = location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond,
            reliable = sample.reliable,
            speedEstimatorKmh = lastEngineSpeedKmh,
            elapsedRealtimeMillis = position.elapsedRealtimeMillis,
        )

        val speedAccuracyMps = location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond
        val horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy
        val reliability = mapper.evaluate(
            speedMps = location.takeIf { it.hasSpeed() }?.speed,
            accuracyMps = speedAccuracyMps,
            ageMillis = now - position.elapsedRealtimeMillis,
            horizontalAccuracyMeters = horizontalAccuracyMeters,
        )
        val snapshot = lastGnssSnapshot
        val estimator = speedEstimatorForDiagnostics
        onEvent(
            LocationFixEvent(
                elapsedRealtimeMillis = position.elapsedRealtimeMillis,
                sequenceNumber = locationSequenceNumber++,
                elapsedSinceStartMillis = position.elapsedRealtimeMillis - sessionStartedAtElapsedMillis,
                provider = location.provider,
                reliable = reliability.reliable,
                rejectionReasons = reliability.reasons,
                satellitesUsedInFix = snapshot?.satellitesUsedInFix,
                satelliteCn0MinDbHz = snapshot?.cn0MinDbHz,
                satelliteCn0AvgDbHz = snapshot?.cn0AvgDbHz,
                satelliteCn0MaxDbHz = snapshot?.cn0MaxDbHz,
                qualityTier = mapper.qualityTier(reliability, speedAccuracyMps, horizontalAccuracyMeters),
                speedEstimatorDiagnostics = if (reliability.reliable && estimator != null) {
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
    }
```

Add the new imports at the top of `RunSessionService.kt`:

```kotlin
import dev.fred.pacorun.eventlog.LocationFixEvent
import dev.fred.pacorun.eventlog.SpeedEstimatorDiagnostics
```

- [ ] **Step 2: Full suite + lint**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: both PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt
git commit -m "feat: emit LocationFixEvent with GNSS satellite snapshot and speed-estimator diagnostics"
```

---

### Task 15: `RunSessionService` — `GpsSilentGapEvent` + `DeviceHealthSampledEvent`

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt`

**Interfaces:**
- Consumes: `GpsSilentGapTracker` (Task 10), `GpsSilentGapEvent`/`DeviceHealthSampledEvent`/`ThermalStatus` (Task 1).

Both checked on the existing 1Hz `tickTimer()` — no new timer (spec 5.3/5.6). `DeviceHealthSampledEvent`'s 30-second bucket reuses the same "dedup by time bucket" idea as `SessionCoordinator.routineTimingElapsedRealtimeMillis()`. No dedicated unit test — same precedent as Tasks 13/14.

- [ ] **Step 1: Replace `tickTimer()` and add two private functions in `RunSessionService.kt`**

```kotlin
    private fun tickTimer(elapsedRealtimeMillis: Long) {
        val second = elapsedRealtimeMillis / TICK_MILLIS
        if (second == lastTimerSecond) return
        lastTimerSecond = second
        val gpsOutage = gpsFreshness.isOutage(elapsedRealtimeMillis)
        gpsSilentGap.checkGap(elapsedRealtimeMillis)?.let { gap ->
            onEvent(GpsSilentGapEvent(elapsedRealtimeMillis, gap))
        }
        sampleDeviceHealthIfDue(elapsedRealtimeMillis)
        coordinator?.advanceTimer(elapsedRealtimeMillis, signalGpsOutage = gpsOutage)
    }

    private fun sampleDeviceHealthIfDue(elapsedRealtimeMillis: Long) {
        val bucket = elapsedRealtimeMillis / DEVICE_HEALTH_BUCKET_MILLIS
        if (bucket == lastDeviceHealthBucket) return
        lastDeviceHealthBucket = bucket
        onEvent(
            DeviceHealthSampledEvent(
                elapsedRealtimeMillis = elapsedRealtimeMillis,
                batteryPercent = currentBatteryPercent(),
                thermalStatus = currentThermalStatus(),
            ),
        )
    }

    private fun currentBatteryPercent(): Int? {
        val batteryManager = getSystemService(BatteryManager::class.java) ?: return null
        val percent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return percent.takeIf { it in 0..100 }
    }

    private fun currentThermalStatus(): ThermalStatus {
        val powerManager = getSystemService(PowerManager::class.java) ?: return ThermalStatus.UNKNOWN
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
            else -> ThermalStatus.UNKNOWN
        }
    }
```

Add the new imports and the bucket constant (inside `companion object`):

```kotlin
import dev.fred.pacorun.eventlog.DeviceHealthSampledEvent
import dev.fred.pacorun.eventlog.GpsSilentGapEvent
import dev.fred.pacorun.eventlog.ThermalStatus
```

```kotlin
        private const val DEVICE_HEALTH_BUCKET_MILLIS = 30_000L
```

- [ ] **Step 2: Full suite + lint**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: both PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt
git commit -m "feat: emit GpsSilentGapEvent and DeviceHealthSampledEvent (30s bucket) from the 1Hz ticker"
```

---

### Task 16: `AppLifecycleChangedEvent` — `PacoRunApp.kt` lifecycle observer

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/PacoRunApp.kt`

**Interfaces:** none new — reuses `RunSessionService.ACTION_APP_FOREGROUNDED`/`ACTION_APP_BACKGROUNDED` (already declared in Task 13).

Extends the existing Compose lifecycle `DisposableEffect` (currently only reacting to `ON_RESUME`) to also react to `ON_START`/`ON_STOP`, following exactly the same `Intent`+`startService` pattern already used for `ACTION_START`/`ACTION_STOP` elsewhere in this file (spec Decision 6). **Guarded to only fire while a session is actually `Running`** — a deliberate, small addition beyond the spec's literal text: without it, every app background/foreground transition while idle would start `RunSessionService` (via `onCreate()`) for no reason, leaving a non-foreground `Service` instance running until the system reclaims it. `RunSessionService.onStartCommand()`'s handler for these two actions is itself already safe with no active session (`onEvent` defaults to `{}` until `beginTracking()` runs) — this guard is about not starting the `Service` pointlessly in the first place, not about safety of the handler itself. No dedicated unit test (Compose UI file, no test file precedent in this project either — `PacoRunApp.kt` has none).

- [ ] **Step 1: Edit the lifecycle `DisposableEffect` in `PacoRunApp.kt`**

Replace:

```kotlin
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) readinessRefresh++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
```

with:

```kotlin
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> readinessRefresh++
                Lifecycle.Event.ON_START -> if (session is RunSessionState.Running) {
                    context.startService(
                        Intent(context, RunSessionService::class.java).setAction(RunSessionService.ACTION_APP_FOREGROUNDED),
                    )
                }
                Lifecycle.Event.ON_STOP -> if (session is RunSessionState.Running) {
                    context.startService(
                        Intent(context, RunSessionService::class.java).setAction(RunSessionService.ACTION_APP_BACKGROUNDED),
                    )
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
```

(`session` and `context` are both already in scope earlier in `PacoRunApp()` — no new parameters, no new imports; `Lifecycle`/`LifecycleEventObserver`/`RunSessionState`/`Intent` are already imported.)

- [ ] **Step 2: Full suite + lint**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: both PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/PacoRunApp.kt
git commit -m "feat: emit AppLifecycleChangedEvent on foreground/background while a session is running"
```

---

### Task 17: Final verification and dual review

**Files:** none — checkpoint.

- [ ] **Step 1: Full suite from a clean state**

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Both must be clean.

- [ ] **Step 2: Self-review**

Re-read the full diff (`git diff feature/speed-production-wiring...HEAD` from within the `feature/session-event-logging` branch, once it exists) and confirm:
- Every one of the 12 `SessionEvent` types from Task 1 has at least one emission site somewhere in Tasks 3-16 (grep `SessionEventWriter.toJsonLine` isn't needed — grep each type name as a constructor call across `app/src/main`).
- `DistanceAccumulator.accept()`'s `IMPLAUSIBLE_JUMP` branch calls `breakChain()` (not a duplicated inline reset) — `git diff` on that one hunk in `RunSessionStore.kt`.
- `RoutineEngine.kt`'s `advanceTo()` phase-transition arithmetic (`fullCycles`, `remaining`, `transitions`) is byte-for-byte unchanged from `feature/speed-production-wiring` — only `onEvent(...)` calls were inserted.
- `RunPreferences`/`RoutinePhase`/`PaceState` are reused directly in `eventlog/SessionEvent.kt`, not duplicated.
- `onEvent` defaults to `{}` on every constructor that gained it (grep `onEvent: (SessionEvent) -> Unit = {}` across `app/src/main`).
- No file outside this plan's File Structure table was touched (`git diff --stat`).

- [ ] **Step 3: Independent review (fresh subagent, no prior context)**

Ask the subagent to confirm specifically:
- (a) `SessionEventWriter.toJsonLine()`'s `when` is exhaustive over `SessionEvent` (the compiler already enforces this, but confirm no `else ->` branch was added that would silently swallow a future 13th type).
- (b) Every `Double`/`Float` field in every one of the 12 event types passes through `jsonNumber()` somewhere in `SessionEventWriter.kt`, not a raw `.toString()`.
- (c) `PaceEvaluator`'s `PaceCandidateAbandonedEvent` fires from exactly the three sites this plan wires (the "snap back to confirmed" branch and `acceptUnreliable()`, both via `abandonCandidateIfAny()`, plus the direct-supersession `else` branch inside `accept()`'s candidate split, guarded on `candidatePace != null`) and nowhere else — specifically, confirm `updateConfig()`'s `clearCandidate()` and the post-confirmation `clearCandidate()` (inside the successful-confirmation branch) do **not** go through `abandonCandidateIfAny()` or emit an event some other way.
- (d) `SessionCoordinator`'s `AudioCueEmittedEvent` emission happens for every cue in the same list passed to `onCues(...)`, not a re-derived list.
- (e) `RunSessionService`'s `SessionEventLogger` is `start()`-ed before any other event in `beginTracking()` could fire, and `stop()`-ed in `stopSession()` even on the early-return paths (permission denial, `SecurityException`) — trace both early `stopSession()` calls inside `beginTracking()` to confirm they reach the `eventLogger?.stop()` line.

- [ ] **Step 4: Report to the owner, then stop**

Report: task count, one-line summary per task, the commit hash of the final commit, and the following open questions carried forward from the spec (unresolved by this plan, since they're explicitly the owner's calls per the spec's own "Domande aperte" section):
1. The `QualityTier.OTTIMA`/`BUONA` half-threshold margin (Task 9) — keep as implemented, or drop to a 3-tier scheme until real data justifies the cut?
2. `GpsSilentGapEvent`'s 5000ms threshold (Task 10) — fine as a first estimate, or adjust?
3. `DeviceHealthSampledEvent`'s 30-second cadence (Task 15) — fine, or move closer to 60s to cut volume on long runs?
4. This plan assumed P1 forks from `feature/speed-production-wiring` (per this plan's Branch section) — confirm before merging either branch.
5. **Plan-level schema addition, not from the spec:** `PaceCandidateAbandonReason.SUPERSEDED_BY_NEW_CANDIDATE` (Task 1/5) — the spec's 5.4 names only two reasons; this plan adds a third to instrument a previously-unacknowledged direct candidate-to-candidate supersession path in `PaceEvaluator.accept()`, decided during review, not an open question but flagged here so the divergence from `2026-09-14-session-event-logging-design.md` is explicit and on the record.

**Do not merge, delete the branch, build an APK, or start P2/P3/P4 work.** Stop here for an explicit go-ahead, per this project's standing rule.
