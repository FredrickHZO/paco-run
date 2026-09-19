# Wear OS core port (v0) — design spec

**Status:** approved pending owner review of this file. Branch: `feature/wear-os` (forked from `main` @ `8f26e47`, worktree `.worktrees/wear-os`).

## Goal

Paco Run today is a single-module (`:app`), phone-only Android app with no Wear OS code at all. This spec covers porting the app's *existing* GPS/pace-coaching engine — not any new feature — onto a new, standalone Wear OS module, with a deliberately minimal UI, so the owner can run an actual workout wearing only a watch (reference hardware: Mobvoi TicWatch Pro 3 GPS, Wear OS 3.5; Xiaomi Watch 2, Wear OS) and inspect the same event-log/GPX output the phone app already produces today. This is a smoke test of the existing engine on real watch hardware, not a new diagnostic tool and not the adaptive run/walk research described in a separate (shelved) handoff document — that whole line of work is explicitly out of scope for this spec.

## Explicit non-goals (v0)

- No *audible* cues — but `CuePlayer`/`AudioCue` are **not removed**. Removing them turned out to be more work than keeping them (a trimmed `AudioCue` enum, manifest changes, a no-op callback), for something that needs testing anyway. Instead: copy `CuePlayer.kt` verbatim (the `AudioCue` enum lives inside this same file, not a separate one), and hardcode `cueVolume = 0f` in the `RunPreferences` instance (see trim below) — `RunSessionStore.kt:370` passes `preferences.cueVolume` straight through to `onCues(...)`, which `RunSessionService.kt` forwards to `CuePlayer.enqueue(cues, volume)`, which sets `player.volume` (ExoPlayer) directly before playback (`CuePlayer.kt:194`) — confirmed by reading the actual call chain, not assumed. Volume 0 genuinely silences it inside the real pipeline; the manifest's `mediaPlayback` foreground-service type and its permission stay untouched, exactly as `:app` has them today.
- No on-watch routine configuration UI. A single hardcoded `RunPreferences` instance, using the class's own existing defaults (see below) — no new number-picking, no settings screen.
- No phone-watch communication/sync.
- No shared library module extraction. The ported files are a **deliberate, temporary copy** into `:wear`'s own source set, not a refactor of `:app` into a shared module `:app` and `:wear` both depend on. That extraction is a separate, later decision — only worth making once this port has proven itself worth continuing (see `[[project_wear_os_diagnostic]]` in memory for the reasoning already discussed with the owner).
- No sensor/Health-Services capability diagnostics, no adaptive effort engine — both belong to the shelved handoff document's scope, not this one.
- No UI polish. One screen, minimum interaction to start/stop a session.

## Architecture

New Android application module `:wear`, added to `settings.gradle.kts` (`include(":wear")`), built as a **standalone** Wear OS app — does not require the phone app to be installed (`com.google.android.wearable.standalone` = `true` in the manifest), matching the product philosophy already discussed for the eventual paid watch tier.

`:wear`'s Gradle `namespace` is set to the same `dev.fred.pacorun` as `:app` (only `applicationId` needs to differ, e.g. `dev.fred.pacorun.wear`, so both apps can be installed side by side on the same device during development) — this is what makes the file copy below genuinely verbatim: package declarations and internal `import dev.fred.pacorun....` lines need no find-replace.

### Files copied verbatim from `:app` into `:wear`'s own source set

(A copy, not a dependency — see non-goals above.)

- `service/RunSessionStore.kt`, `service/SessionEventLogger.kt`, `service/SpeedEstimator.kt`
- `pace/PaceEvaluator.kt`, `pace/PaceModels.kt`
- `routine/RoutineEngine.kt`, `routine/RoutineModels.kt`
- `location/LocationSampleMapper.kt` (not `LocationReadiness.kt` — it has zero callers in the service/store code path being ported, it's phone-UI-only today; don't drag it in)
- `eventlog/SessionEvent.kt`, `eventlog/SessionEventWriter.kt`
- `export/GpxWriter.kt`, `export/GpxExporter.kt`
- `settings/RunPreferences.kt` (the data classes only — `RunPreferencesStore`, the DataStore-backed persistence layer, is **not** copied; see trim below)
- `audio/CuePlayer.kt` (verbatim, including the `AudioCue` enum and its `@RawRes` references — see non-goals above for why this stays instead of being trimmed)
- All 14 `.wav` files under `app/src/main/res/raw/` (~470KB total) — `AudioCue`'s `R.raw.*` references need these to exist in `:wear`'s own `res/raw/` too, or the build breaks. Copied as assets, not "ported" in any meaningful sense.

Each Kotlin file above also gets its existing unit test file copied alongside it (e.g. `PaceEvaluatorTest.kt`, `RoutineEngineTest.kt`, `SessionEventWriterTest.kt`, `SpeedEstimatorTest.kt`, …) into `wear/src/test/...`. This is not new test-writing — it validates the copy stayed byte-faithful to already-tested logic. `./gradlew :wear:testDebugUnitTest` must pass before this task is considered done.

### `RunSessionService.kt`: copied with one trim

`RunSessionService.kt` is otherwise plain Android framework + `com.google.android.gms.location` (Fused Location Provider, available on Wear OS the same as on phone) — confirmed by reading its current imports, no Compose/UI dependency in this file today. `CuePlayer` construction and wiring (`onCues = { cues, volume -> cuePlayer?.enqueue(cues, volume) }`) stays exactly as-is — see non-goals above. The manifest's `foregroundServiceType="location|mediaPlayback"` and its permissions are copied unchanged too.

The one real change: replace the `RunPreferencesStore`-backed settings read with a hardcoded instance constructed directly in code — `RunPreferences(cueVolume = 0f)`, every other field left at the class's own existing defaults (`CONSTANT` mode, 7.5–8.5 km/h, unlimited distance, `gpsCuesEnabled = false`) — no new default values to invent beyond the volume override, no settings UI.

### New, `:wear`-only code

- One Activity/Composable screen using Wear Compose (`androidx.wear.compose:compose-material`, keeping the same Kotlin/Compose toolchain already used on phone rather than classic Views): a Start/Stop button, and optionally one line of status text (e.g. elapsed time). No theming or animation effort — functional only, per the explicit "fatta a cazzo" brief.
- `wear/src/main/AndroidManifest.xml`: `<uses-feature android:name="android.hardware.type.watch" android:required="true"/>`, `<meta-data android:name="com.google.android.wearable.standalone" android:value="true"/>`, the `RunSessionService` declaration copied unchanged (`foregroundServiceType="location|mediaPlayback"`), the same permissions `:app`'s manifest already declares (location + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`).
- A copy of the notification icon/string resources `RunSessionService.notification()` reads today (`R.drawable.ic_run`, `R.string.app_name`) into `:wear`'s own `res/`.

### `minSdk`

`:wear` needs its own `minSdk`, independent of and lower than `:app`'s current `35` — the lowest reference device (TicWatch Pro 3, Wear OS 3.5) needs roughly API 30, but the exact minimum for Wear OS 3.5 should be confirmed against current Wear OS platform documentation during implementation rather than guessed here; this spec does not lock in a number.

## Data retrieval (the constraint the owner flagged as critical)

`SessionEventLogger` and `GpxExporter` already write through `MediaStore` — the same mechanism `:app` uses today. Assumption, not yet verified on real hardware: this behaves the same way on Wear OS's storage, since Wear OS is a regular Android variant with no known reason to differ. Retrieval path: `adb` over Wi-Fi — `adb connect <watch-ip>:5555` (no USB cable needed; confirmed the owner already has `adb` installed locally at `~/Android/Sdk/platform-tools/adb`), then `adb pull` the written file(s) — the same workflow already used for phone builds, just over a network connection instead of USB.

## Testing

Unit tests only (copied alongside their source files, see above) — no Robolectric, no instrumented tests planned for v0, matching how this same code is already tested on `:app`. Real validation is the point of this exercise: an actual run on the TicWatch Pro 3 and the Xiaomi Watch 2, producing a real event log to inspect afterward.

## Branch

`feature/wear-os`, already created from `main` (`8f26e47`), worktree `.worktrees/wear-os`.
