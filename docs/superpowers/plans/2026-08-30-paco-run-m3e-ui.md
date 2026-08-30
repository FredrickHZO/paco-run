# Paco Run Material 3 Expressive UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Paco Run's minimal functional interface with a Pixel 9a-first, fully Material 3 Expressive interface while preserving its tested tracking, routine, persistence, and offline-audio behavior.

**Architecture:** Add a small UI design-system layer around the existing Compose app, then replace one screen at a time. Keep `RunPreferences` as persisted configuration, use a separate in-memory editor draft, and introduce an explicit pre-session coordinator so the routine starts only on the first reliable GPS fix. Each visible screen is an isolated composable with state and events passed from `PacoRunApp`.

**Tech Stack:** Kotlin 2.3.20, Jetpack Compose, `androidx.compose.material3:material3:1.5.0-alpha27`, Android SDK 37, DataStore Preferences, Play Services Location, Media3, Compose UI Test, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-30-paco-run-m3e-ui-design.md`

## Global Constraints

- Pixel 9a only: 1080 × 2424, 20:9; validate at 360 × 808 logical pixels and with real system bars.
- Use real Material 3 Expressive APIs, tokens, components, shapes, motion, and Material Symbols; no HTML mockup is a visual reference.
- Follow system light/dark theme; retain real Android Dynamic Color and add three complete fixed light/dark schemes for comparison.
- Ask the user to approve each screen on the emulator before beginning the next screen task.
- Preserve offline operation, configuration persistence, one foreground session service, one 1 Hz high-accuracy request, GPS reliability gate, distance rules, audio FIFO, and screen-off behavior.
- Home location status is technical only; opening Home must not request location.
- Start is user-initiated. The first reliable GPS fix automatically starts the routine; no second Go action and no extra countdown.
- Editor values are drafts until explicit Save; duration is any value of at least one second with no arbitrary maximum.
- Stop requires press-and-hold with visible progress, cancellation on early release, shape response, and system-respecting haptics.
- No history, statistics, maps, account, cloud, analytics, bottom navigation, or speculative screens.

---

### Task 1: Expressive theme, fixed schemes, and reusable primitives

**Files:**
- Create: `app/src/main/java/dev/fred/pacorun/ui/theme/PacoRunTheme.kt`
- Create: `app/src/main/java/dev/fred/pacorun/ui/theme/PacoRunPalettes.kt`
- Create: `app/src/main/java/dev/fred/pacorun/ui/components/PacoRunScaffold.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/PacoRunApp.kt`
- Modify: `app/src/androidTest/java/dev/fred/pacorun/PacoRunThemeTest.kt`

**Interfaces:**
- Produces: `enum class FixedPalette { ONE, TWO, THREE }`
- Produces: `PacoRunTheme(dynamicColor: Boolean, fixedPalette: FixedPalette = FixedPalette.ONE, darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`
- Produces: `PacoRunScaffold(title: String?, status: (@Composable () -> Unit)?, primaryAction: (@Composable () -> Unit)?, content: @Composable ColumnScope.() -> Unit)`

- [ ] **Step 1: Write failing theme-selection tests**

Extend `PacoRunThemeTest` to set `darkTheme` and each `FixedPalette`, then expose `MaterialTheme.colorScheme.primary` through semantics:

```kotlin
@Test fun eachFixedPaletteHasDistinctLightAndDarkPrimaryRoles() {
    FixedPalette.entries.forEach { palette ->
        composeRule.setContent {
            PacoRunTheme(dynamicColor = false, fixedPalette = palette, darkTheme = false) {
                Text(MaterialTheme.colorScheme.primary.toString(), Modifier.testTag("primary-${palette.name}"))
            }
        }
        composeRule.onNodeWithTag("primary-${palette.name}").assertExists()
    }
}

@Test fun darkThemeUsesDarkSurface() {
    composeRule.setContent {
        PacoRunTheme(dynamicColor = false, darkTheme = true) {
            Box(Modifier.testTag("surface").background(MaterialTheme.colorScheme.surface))
        }
    }
    composeRule.onNodeWithTag("surface").assertExists()
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.fred.pacorun.PacoRunThemeTest`

Expected: compilation failure because `FixedPalette` and the new theme signature do not exist.

- [ ] **Step 3: Define three complete role-based schemes**

In `PacoRunPalettes.kt`, define six `ColorScheme` values using `lightColorScheme`/`darkColorScheme`. Populate every role used by the application: primary/onPrimary, primaryContainer/onPrimaryContainer, secondary pairs, tertiary pairs, error pairs, background/onBackground, surface variants, outline variants, inverse roles, and scrim. Do not override component colors outside these schemes.

```kotlin
enum class FixedPalette { ONE, TWO, THREE }

internal fun fixedColorScheme(palette: FixedPalette, dark: Boolean): ColorScheme = when (palette to dark) {
    FixedPalette.ONE to false -> PaletteOneLight
    FixedPalette.ONE to true -> PaletteOneDark
    FixedPalette.TWO to false -> PaletteTwoLight
    FixedPalette.TWO to true -> PaletteTwoDark
    FixedPalette.THREE to false -> PaletteThreeLight
    FixedPalette.THREE to true -> PaletteThreeDark
}
```

Generate the role values from three coherent seeds with Material Theme Builder/material color utilities before transcribing them. Record seed and generated role values in comments at each scheme; do not invent colors per component.

- [ ] **Step 4: Build the expressive theme**

Move `PacoRunTheme` out of `PacoRunApp.kt`. Select dynamic schemes only when `dynamicColor` is true; otherwise use `fixedColorScheme`. Provide the expressive motion scheme and typography available in Material3 1.5.0-alpha27. If an expressive API is experimental, use the narrowest `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` annotation.

```kotlin
@Composable
fun PacoRunTheme(
    dynamicColor: Boolean,
    fixedPalette: FixedPalette = FixedPalette.ONE,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
        dynamicColor -> dynamicLightColorScheme(LocalContext.current)
        else -> fixedColorScheme(fixedPalette, darkTheme)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
```

- [ ] **Step 5: Add one shared edge-to-edge scaffold**

`PacoRunScaffold` owns `WindowInsets.safeDrawing`, the app-level background, predictable 16 dp horizontal margins, and optional top status/primary action slots. It must not encode screen-specific layout.

- [ ] **Step 6: Verify and compare palettes**

Run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.fred.pacorun.PacoRunThemeTest
```

Install on Pixel 9a emulator. Render one temporary `@Preview`/debug-only palette gallery containing the same M3E button, container, text hierarchy, and state chip for all three fixed palettes in light and dark. Capture screenshots at 360 × 808 and inspect them for clipping, contrast, unintended muddy surfaces, and inconsistent role use.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/ui/theme app/src/main/java/dev/fred/pacorun/ui/components app/src/main/java/dev/fred/pacorun/PacoRunApp.kt app/src/androidTest/java/dev/fred/pacorun/PacoRunThemeTest.kt
git commit -m "feat: establish Paco Run expressive theme"
```

---

### Task 2: Home, technical GPS readiness, and visual approval

**Files:**
- Create: `app/src/main/java/dev/fred/pacorun/ui/home/HomeScreen.kt`
- Create: `app/src/main/java/dev/fred/pacorun/ui/home/HomeModels.kt`
- Create: `app/src/main/java/dev/fred/pacorun/location/LocationReadiness.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/PacoRunApp.kt`
- Delete: Home responsibilities from `app/src/main/java/dev/fred/pacorun/ui/SetupScreen.kt`
- Test: `app/src/test/java/dev/fred/pacorun/location/LocationReadinessTest.kt`
- Test: `app/src/androidTest/java/dev/fred/pacorun/ui/home/HomeScreenTest.kt`

**Interfaces:**
- Produces: `enum class LocationReadiness { READY, LOCATION_OFF, PERMISSION_NEEDED, APPROXIMATE_ONLY }`
- Produces: `HomeUiState(preferences: RunPreferences, locationReadiness: LocationReadiness)`
- Produces: `HomeScreen(state: HomeUiState, onStart: () -> Unit, onEdit: () -> Unit)`

- [ ] **Step 1: Write failing readiness tests**

```kotlin
@Test fun finePermissionAndProviderProduceReady() {
    assertEquals(LocationReadiness.READY, locationReadiness(fine = true, coarse = true, providerEnabled = true))
}

@Test fun coarseOnlyIsApproximate() {
    assertEquals(LocationReadiness.APPROXIMATE_ONLY, locationReadiness(fine = false, coarse = true, providerEnabled = true))
}

@Test fun disabledProviderWinsAfterPermissions() {
    assertEquals(LocationReadiness.LOCATION_OFF, locationReadiness(fine = true, coarse = true, providerEnabled = false))
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.LocationReadinessTest'`

Expected: compilation failure because the readiness model is absent.

- [ ] **Step 3: Implement technical readiness without requesting location**

Use permission checks plus `LocationManager.isLocationEnabled`; do not create a `FusedLocationProviderClient`, call `getCurrentLocation`, or request updates from Home.

```kotlin
fun locationReadiness(fine: Boolean, coarse: Boolean, providerEnabled: Boolean) = when {
    !fine && coarse -> LocationReadiness.APPROXIMATE_ONLY
    !fine -> LocationReadiness.PERMISSION_NEEDED
    !providerEnabled -> LocationReadiness.LOCATION_OFF
    else -> LocationReadiness.READY
}
```

- [ ] **Step 4: Write failing Home semantics tests**

Cover Constant and Walk–Run summaries, GPS copy, dominant Start action, Edit action, and absence of technical audio fields:

```kotlin
@Test fun walkRunHomeShowsDecisionSummaryNotTechnicalSettings() {
    composeRule.setContent { HomeScreen(walkRunState, {}, {}) }
    composeRule.onNodeWithText("Walk–Run").assertExists()
    composeRule.onNodeWithText("1:00").assertExists()
    composeRule.onNodeWithText("2:00").assertExists()
    composeRule.onNodeWithText("Volume").assertDoesNotExist()
    composeRule.onNodeWithTag(UiTags.start).assertHasClickAction()
}
```

- [ ] **Step 5: Implement Home with official M3E components**

Use an expressive large button for Start, an official status chip/button treatment for technical GPS readiness, emphasized mode typography, and bounded tonal containers for the summary. Use one clear spatial rhythm; do not reproduce the rejected stacked-dashboard HTML mockup.

Home includes exactly: title, technical GPS pill, mode, identifying routine summary, Start, and Edit routine.

- [ ] **Step 6: Wire navigation and permission flow**

`PacoRunApp` keeps Home visible until Start is tapped. Only then launch the existing permission request if needed and start the service. During this checkpoint, `onEdit` opens the existing `SetupScreen`; Task 3 replaces that screen with the dedicated editor.

- [ ] **Step 7: Verify and stop for user approval**

Run:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.fred.pacorun.ui.home.HomeScreenTest
./gradlew lintDebug assembleDebug
```

On the visible Pixel 9a emulator, show the identical Home in all three fixed schemes, then the selected scheme in system light/dark and Dynamic Color. Inspect before presentation. **Do not begin Task 3 until the user approves Home composition and chooses the fixed palette.**

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/ui/home app/src/main/java/dev/fred/pacorun/location app/src/main/java/dev/fred/pacorun/PacoRunApp.kt app/src/test/java/dev/fred/pacorun/location app/src/androidTest/java/dev/fred/pacorun/ui/home
git commit -m "feat: add expressive quick-start home"
```

---

### Task 3: Dedicated draft-based routine editor

**Files:**
- Create: `app/src/main/java/dev/fred/pacorun/ui/editor/RoutineEditorScreen.kt`
- Create: `app/src/main/java/dev/fred/pacorun/ui/editor/RoutineEditorState.kt`
- Create: `app/src/main/java/dev/fred/pacorun/ui/components/ExpressiveNumberControl.kt`
- Create: `app/src/main/java/dev/fred/pacorun/ui/components/DurationControl.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/PacoRunApp.kt`
- Delete: `app/src/main/java/dev/fred/pacorun/ui/SetupScreen.kt`
- Delete: superseded setup tests from `app/src/androidTest/java/dev/fred/pacorun/ui/SetupScreenTest.kt`
- Test: `app/src/test/java/dev/fred/pacorun/ui/editor/RoutineEditorStateTest.kt`
- Test: `app/src/androidTest/java/dev/fred/pacorun/ui/editor/RoutineEditorScreenTest.kt`

**Interfaces:**
- Produces: `RoutineEditorState(original: RunPreferences, draft: RunPreferences, durationInputValid: Boolean = true)`
- Produces: `val RoutineEditorState.canSave: Boolean`
- Produces: `RoutineEditorScreen(state: RoutineEditorState, onDraftChange: (RunPreferences) -> Unit, onSave: () -> Unit, onBack: () -> Unit)`
- Produces: `DurationControl(totalSeconds: Int, onTotalSecondsChange: (Int) -> Unit)`

- [ ] **Step 1: Write failing editor-state tests**

```kotlin
@Test fun unchangedDraftCannotSave() {
    val p = RunPreferences()
    assertFalse(RoutineEditorState(p, p).canSave)
}

@Test fun validChangedDraftCanSave() {
    val p = RunPreferences()
    assertTrue(RoutineEditorState(p, p.copy(runSeconds = 73)).canSave)
}

@Test fun arbitraryPositiveSecondTotalsAreAccepted() {
    listOf(7, 73, 761).forEach { assertTrue(RunPreferences(mode = RoutineMode.WALK_RUN, runSeconds = it).isValid) }
}
```

- [ ] **Step 2: Verify RED, then implement the pure state**

Run: `./gradlew testDebugUnitTest --tests '*.RoutineEditorStateTest'`

Expected: compilation failure, then GREEN after implementing `canSave = draft != original && draft.isValid && durationInputValid`.

- [ ] **Step 3: Write failing component and conditional-section tests**

Tests cover:

- M3E mode ButtonGroup selection.
- Constant fields only in Constant mode.
- Initial/Run/Walk/end sections in Walk–Run.
- Walk range hidden while reusing Constant.
- Routine distance hidden while unlimited.
- Speed increment/decrement changes by exactly 0.1 km/h.
- Direct speed entry accepts any finite valid range.
- Duration accepts `0:07`, `1:13`, and `12:41` and rejects total zero.
- Save remains disabled for invalid/unchanged drafts.

- [ ] **Step 4: Implement numeric and duration controls**

Use official expressive button grouping for decrement/value/increment. The value opens direct input with Material fields. Duration presents one large `MM:SS` value but edits minute and second segments independently; normalize entered seconds into total seconds without imposing a 0–59 input restriction on the raw second change:

```kotlin
internal fun durationTotal(minutes: Long, seconds: Long): Int? {
    if (minutes < 0 || seconds < 0) return null
    val total = minutes * 60L + seconds
    return total.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
}
```

The `+`/`−` time affordance changes one second only; direct entry is the primary unrestricted mechanism.

- [ ] **Step 5: Implement the editor sections and draft lifecycle**

Use `AnimatedVisibility`/M3 motion tokens for conditional sections. Save calls `RunPreferencesStore.update { draft }` once, then returns Home. Back with a changed draft shows an `AlertDialog` with `Continua a modificare` and `Scarta`; unchanged Back returns immediately.

- [ ] **Step 6: Verify and stop for user approval**

Run:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.fred.pacorun.ui.editor.RoutineEditorScreenTest
./gradlew lintDebug assembleDebug
```

Show Constant and Walk–Run editor states plus the discard dialog on Pixel 9a. Inspect scrolling, keyboard avoidance, 200% font scale, light/dark, and both gesture/three-button navigation. **Do not begin Task 4 until the user approves the Editor.**

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/ui app/src/main/java/dev/fred/pacorun/PacoRunApp.kt app/src/test/java/dev/fred/pacorun/ui app/src/androidTest/java/dev/fred/pacorun/ui
git commit -m "feat: edit routines with expressive controls"
```

---

### Task 4: Gate routine start on the first reliable GPS fix

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SessionServiceSupportTest.kt`

**Interfaces:**
- Changes: `RunSessionState.AcquiringGps` remains visible until the first reliable sample.
- Changes: construct `SessionCoordinator` with `startedAtElapsedMillis` from the accepted first reliable sample, then pass that sample through the existing `tick` entry point.
- Contract: timer origin, RoutineEngine, PaceEvaluator, and distance anchor begin at the accepted first reliable fix, not service creation.

- [ ] **Step 1: Write failing acquisition tests**

```kotlin
@Test fun timerAndRoutineDoNotAdvanceBeforeFirstReliableFix() {
    val harness = sessionHarness()
    harness.advanceTimer(10_000L, signalGpsOutage = true)
    assertTrue(harness.published.none { it is RunSessionState.Running })
    assertTrue(harness.routineInputs.isEmpty())
}

@Test fun firstReliableFixStartsAtZeroWithoutAddingDistance() {
    val harness = sessionHarness()
    harness.location(reliableSample(at = 12_000L), position(at = 12_000L))
    val running = harness.published.last() as RunSessionState.Running
    assertEquals(0L, running.elapsedMillis)
    assertEquals(0.0, running.totalDistanceMeters, 0.0)
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew testDebugUnitTest --tests '*.SessionCoordinatorTest' --tests '*.SessionServiceSupportTest'`

Expected: current coordinator advances/publishes Running before a reliable fix.

- [ ] **Step 3: Split acquisition from active coordination**

Do not instantiate/start the RoutineEngine clock at service creation. While acquiring:

- Keep one active location request and foreground notification.
- Map every callback through `LocationSampleMapper`.
- Ignore unreliable samples for session timing/distance.
- On first reliable sample, create/start the coordinator with that sample timestamp and seed the distance anchor without adding distance.
- Start the 1 Hz routine ticker only after activation.

Keep later GPS-outage behavior unchanged.

- [ ] **Step 4: Verify all service/routine regressions**

Run:

```bash
./gradlew testDebugUnitTest --tests '*.SessionCoordinatorTest' --tests '*.SessionServiceSupportTest' --tests '*.RoutineEngineTest'
./gradlew testDebugUnitTest lintDebug assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service app/src/test/java/dev/fred/pacorun/service
git commit -m "fix: start routines on reliable GPS"
```

---

### Task 5: Expressive GPS acquisition screen and automatic transition

**Files:**
- Create: `app/src/main/java/dev/fred/pacorun/ui/gps/GpsAcquisitionScreen.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/PacoRunApp.kt`
- Delete: `AcquiringGpsScreen` from `app/src/main/java/dev/fred/pacorun/ui/ActiveRunScreen.kt`
- Test: `app/src/androidTest/java/dev/fred/pacorun/ui/gps/GpsAcquisitionScreenTest.kt`

**Interfaces:**
- Produces: `GpsAcquisitionScreen(onCancel: () -> Unit)`
- Consumes: `RunSessionState.AcquiringGps`; automatic navigation occurs when store publishes `Running`.

- [ ] **Step 1: Write failing acquisition-screen test**

```kotlin
@Test fun acquiringShowsExpressiveProgressAndCancelOnly() {
    composeRule.setContent { GpsAcquisitionScreen(onCancel = {}) }
    composeRule.onNodeWithText("Ricerca GPS").assertExists()
    composeRule.onNodeWithTag(UiTags.gpsProgress).assertExists()
    composeRule.onNodeWithText("Annulla").assertHasClickAction()
    composeRule.onNodeWithText("Parti").assertDoesNotExist()
}
```

- [ ] **Step 2: Verify RED, then implement with the official expressive loading indicator**

Use the Material3 1.5 expressive loading/progress API and emphasized typography. Do not draw a custom spinner. Cancel sends the existing Stop action.

- [ ] **Step 3: Add an integration test for automatic transition**

Drive `RunSessionStore` from Idle → AcquiringGps → Running and assert Home → acquisition → active with no Go button and no user click after acquisition.

- [ ] **Step 4: Verify and stop for user approval**

Run full JVM, focused connected test, lint, and assembly. Use emulator route injection only to demonstrate state transition if it can satisfy the 0.35 m/s speed-accuracy gate; otherwise use the tested debug state source and label the visual demonstration as simulated. **Do not weaken the GPS gate. Do not begin Task 6 until the user approves acquisition.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/ui/gps app/src/main/java/dev/fred/pacorun/PacoRunApp.kt app/src/main/java/dev/fred/pacorun/ui/ActiveRunScreen.kt app/src/androidTest/java/dev/fred/pacorun/ui/gps
git commit -m "feat: add expressive GPS acquisition"
```

---

### Task 6: Active-session hierarchy and hold-to-stop

**Files:**
- Replace: `app/src/main/java/dev/fred/pacorun/ui/ActiveRunScreen.kt`
- Create: `app/src/main/java/dev/fred/pacorun/ui/session/SessionHero.kt`
- Create: `app/src/main/java/dev/fred/pacorun/ui/session/HoldToStopButton.kt`
- Create: `app/src/main/java/dev/fred/pacorun/ui/session/SessionVisualState.kt`
- Test: `app/src/test/java/dev/fred/pacorun/ui/session/SessionVisualStateTest.kt`
- Test: `app/src/androidTest/java/dev/fred/pacorun/ui/session/ActiveRunScreenTest.kt`
- Test: `app/src/androidTest/java/dev/fred/pacorun/ui/session/HoldToStopButtonTest.kt`

**Interfaces:**
- Produces: `SessionHeroModel(primaryText: String, supportingText: String, progress: Float?, kind: HeroKind)`
- Produces: `enum class HeroKind { SPEED, RUN_COUNTDOWN, WALK_COUNTDOWN }`
- Produces: `sessionHero(state: RunSessionState.Running): SessionHeroModel`
- Produces: `HoldToStopButton(holdMillis: Long = 1_500L, onStop: () -> Unit)`

- [ ] **Step 1: Write failing pure hierarchy tests**

```kotlin
@Test fun timedRunUsesCountdownHero() {
    val hero = sessionHero(running(phase = RoutinePhase.RUN, remaining = 42_000L, speed = 7.9f))
    assertEquals(HeroKind.RUN_COUNTDOWN, hero.kind)
    assertEquals("0:42", hero.primaryText)
}

@Test fun constantUsesSpeedHero() {
    val hero = sessionHero(running(phase = RoutinePhase.CONSTANT_BEFORE, remaining = null, speed = 7.9f))
    assertEquals(HeroKind.SPEED, hero.kind)
    assertEquals("7,9", hero.primaryText)
}
```

- [ ] **Step 2: Verify RED, then implement `SessionVisualState`**

Map phases and pace states into semantic UI models. Keep formatted strings deterministic and locale-aware. Progress must be based on actual interval duration/remaining state; if the service does not expose interval total, add `intervalTotalMillis` to `RoutineResult` and `RunSessionState.Running` with pure engine/service regression tests.

- [ ] **Step 3: Write failing hold gesture tests**

Use Compose's test clock:

```kotlin
@Test fun earlyReleaseDoesNotStop() {
    var stopped = false
    composeRule.setContent { HoldToStopButton(holdMillis = 1_500L) { stopped = true } }
    composeRule.onNodeWithTag(UiTags.stop).performTouchInput {
        down(center); advanceEventTime(700); up()
    }
    assertFalse(stopped)
}

@Test fun completedHoldStopsExactlyOnce() {
    var count = 0
    composeRule.setContent { HoldToStopButton(holdMillis = 1_500L) { count++ } }
    composeRule.onNodeWithTag(UiTags.stop).performTouchInput {
        down(center); advanceEventTime(1_600); up()
    }
    assertEquals(1, count)
}
```

- [ ] **Step 4: Implement hold-to-stop**

Use `pointerInput`/`awaitEachGesture` with an `Animatable` progress value. Cancel on pointer cancellation, movement outside bounds, or early release. Invoke `onStop` once at completion. Drive container size/shape/content through Material motion tokens, and use `LocalHapticFeedback` for start/completion while respecting enabled system feedback. Expose progress/state descriptions to TalkBack.

- [ ] **Step 5: Implement Active Session**

Use:

- Countdown hero for RUN/WALK.
- Speed hero for CONSTANT/CONSTANT_BEFORE/CONSTANT_AFTER.
- Official expressive progress component tied to real interval progress.
- Pace state communicated by copy, icon/shape, and color role.
- GPS, total distance, and routine distance as supporting information.
- No runtime audio controls.
- AnimatedContent/container transform between hero kinds with reduced-motion fallback.

- [ ] **Step 6: Verify and stop for user approval**

Run:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.fred.pacorun.ui.session.ActiveRunScreenTest,dev.fred.pacorun.ui.session.HoldToStopButtonTest
./gradlew lintDebug assembleDebug
```

Present constant, Run, Walk, GPS weak, Too slow, Too fast, Good pace, and hold-in-progress states on Pixel 9a. Inspect every screenshot first. Demonstrate actual hold cancellation/completion and haptic invocation evidence. **Do not begin final polish until the user approves Active Session.**

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/ui app/src/main/java/dev/fred/pacorun/service app/src/main/java/dev/fred/pacorun/routine app/src/test/java/dev/fred/pacorun app/src/androidTest/java/dev/fred/pacorun/ui/session
git commit -m "feat: add expressive active session"
```

---

### Task 7: Cross-screen motion, accessibility, and final Pixel acceptance

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/PacoRunApp.kt`
- Modify: expressive UI files proven defective by acceptance
- Create: `app/src/androidTest/java/dev/fred/pacorun/ui/PacoRunFlowTest.kt`
- Modify: `docs/testing/routine-emulator-checklist.md`
- Modify: `docs/testing/pixel-9a-install.md`

**Interfaces:**
- Consumes: all approved screen composables and existing session service.
- Produces: a verified debug APK and updated physical-device checklist.

- [ ] **Step 1: Write failing end-to-end UI flow tests**

Cover:

- Home → Editor → valid Save → updated Home.
- Home → Editor → changed Back → Continue/Discard.
- Home Start → permission flow → AcquiringGps.
- AcquiringGps → first reliable fix → Active automatically.
- Early Stop release leaves session running.
- Completed Stop returns Home.

- [ ] **Step 2: Add coordinated transitions and reduced-motion behavior**

Use Material motion scheme values and `AnimatedContent`/shared bounds supported by the installed Compose version. Start must transform toward acquisition; acquisition toward hero; editor sections animate locally. When system animator duration scale is zero, state changes remain immediate and understandable without spatial motion.

- [ ] **Step 3: Run accessibility checks**

Verify:

- TalkBack labels/state descriptions.
- 200% font scale and display scaling on Pixel 9a.
- Gesture and three-button navigation insets.
- Light/dark contrast for chosen fixed palette and representative Dynamic Color.
- No state relies only on color.
- Touch targets meet component specs.
- Hold-to-stop has an accessible alternative custom action if TalkBack cannot perform the hold gesture reliably.

- [ ] **Step 4: Run full clean verification**

```bash
./gradlew clean testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug
```

Expected: all unit/instrumentation tests and lint pass; debug APK builds.

- [ ] **Step 5: Repeat accelerated emulator acceptance**

Repeat 12-second Run/15-second Walk, screen-off continuity, audio playback evidence, one 1 Hz request, notification Stop, and full service/location cleanup. Do not claim physical GPS accuracy or perceived audio quality from the emulator.

- [ ] **Step 6: Produce final APK and checksum**

```bash
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/Paco-Run-M3E-debug.apk
sha256sum dist/Paco-Run-M3E-debug.apk
```

- [ ] **Step 7: Update physical Pixel checklist**

Retain explicit physical checks for reliable distance gating, positive warm-up/limited routine completion, intelligibility, music ducking, coexistence with the user's fitness app, and screen-off use. Add theme, font scale, GPS readiness pill, automatic first-fix start, and hold-to-stop checks.

- [ ] **Step 8: Commit**

```bash
git add app docs/testing
git commit -m "test: verify expressive Paco Run UI"
```
