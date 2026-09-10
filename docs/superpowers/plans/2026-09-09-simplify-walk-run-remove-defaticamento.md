# Semplificazione Cammina-Corri: rimozione Defaticamento — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rimuovere la sezione "Defaticamento" (limite di distanza opzionale che ferma la struttura corsa/cammino e fa continuare a camminare liberamente) dalla modalità Cammina-Corri, in tutti gli strati: motore, editor, riepilogo home, preferenze persistite.

**Architecture:** Rimozione a strati, dal più interno al più esterno, in modo che ogni task lasci il progetto compilante e testabile: (1) motore/fasi, (2) UI editor + riepilogo home, (3) modello dati/persistenza. Nessun nuovo concetto introdotto, solo cancellazione — Costante resta invariata.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 Expressive), JUnit (unit test in `app/src/test`), Compose UI test + AndroidJUnit4 (instrumented, `app/src/androidTest`), Gradle.

**Spec:** `docs/superpowers/specs/2026-09-09-custom-routine-mode-design.md`, sezione "Semplificazione di Costante e Cammina-Corri".

## Global Constraints

- Costante non viene toccata: nessun riscaldamento aggiunto, nessuna modifica al suo comportamento (deciso nello spec: un riscaldamento allo stesso ritmo del resto della modalità non avrebbe effetto osservabile).
- Nessuna nuova astrazione: è una rimozione, non un refactor — cancellare, non generalizzare.
- Verifica dopo ogni task nell'ordine stabilito dal progetto: `./gradlew testDebugUnitTest`, poi `lintDebug`, poi (solo dove serve un test instrumented) `connectedDebugAndroidTest` seguito da `installDebug`.
- L'APK di sviluppo NON va mai copiata sovrascrivendo `~/Documents/Paco-Run-m3e-debug-V2.apk` (è la copia "buona" dell'utente, usata per allenamenti reali). Le build di questo lavoro vanno copiate come `~/Documents/Paco-Run-m3e-debug-V3-simplify-walkrun.apk`.
- Commit frequenti, uno per task, mai un commit che lascia il progetto non compilante.

---

## File Structure

| File | Ruolo | Task |
|---|---|---|
| `app/src/main/java/dev/fred/pacorun/routine/RoutineModels.kt` | Modifica: rimuove `RoutinePhase.CONSTANT_AFTER` e `RoutineCue.ROUTINE_END` | 1 |
| `app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt` | Modifica: rimuove il controllo di cap-distanza e `finishRoutine()` | 1 |
| `app/src/main/java/dev/fred/pacorun/ui/session/SessionHero.kt` | Modifica: `phaseTitle()` non menziona più `CONSTANT_AFTER` | 1 |
| `app/src/main/java/dev/fred/pacorun/ui/session/SessionVisualState.kt` | Modifica: `heroKind()` non menziona più `CONSTANT_AFTER` | 1 |
| `app/src/test/java/dev/fred/pacorun/routine/RoutineEngineTest.kt` | Modifica: rimuove i test del cap/CONSTANT_AFTER | 1 |
| `app/src/test/java/dev/fred/pacorun/ui/session/SessionVisualStateTest.kt` | Modifica: rimuove l'asserzione su `CONSTANT_AFTER` | 1 |
| `app/src/main/java/dev/fred/pacorun/ui/editor/RoutineEditorScreen.kt` | Modifica: rimuove `RoutineEndSection` e la sua chiamata | 2 |
| `app/src/main/java/dev/fred/pacorun/ui/home/HomeScreen.kt` | Modifica: il riepilogo mostra solo il riscaldamento | 2 |
| `app/src/main/java/dev/fred/pacorun/ui/UiTags.kt` | Modifica: rimuove i tag `routineUnlimited`/`routineDistance`/`routineDistanceError` | 2 |
| `app/src/androidTest/java/dev/fred/pacorun/ui/home/HomeScreenTest.kt` | Modifica: aggiorna le asserzioni sul testo di riepilogo | 2 |
| `app/src/main/java/dev/fred/pacorun/settings/RunPreferences.kt` | Modifica: rimuove i campi `routineUnlimited`/`routineKm` | 3 |
| `app/src/main/java/dev/fred/pacorun/settings/RunPreferencesStore.kt` | Modifica: rimuove decode/write/Keys per quei campi | 3 |
| `app/src/test/java/dev/fred/pacorun/settings/RunPreferencesTest.kt` | Modifica: rimuove asserzioni su quei campi | 3 |

Nessun file nuovo. Nessuna migrazione dati: i due valori spariscono semplicemente dal DataStore locale al prossimo salvataggio, senza bisogno di codice di migrazione (chiavi orfane innocue).

---

### Task 1: Semplifica il motore (fasi, cue, RoutineEngine)

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/routine/RoutineModels.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/ui/session/SessionHero.kt:213`
- Modify: `app/src/main/java/dev/fred/pacorun/ui/session/SessionVisualState.kt:12`
- Test: `app/src/test/java/dev/fred/pacorun/routine/RoutineEngineTest.kt`
- Test: `app/src/test/java/dev/fred/pacorun/ui/session/SessionVisualStateTest.kt`

**Interfaces:**
- Consumes: nulla di nuovo, solo la superficie esistente di `RoutineEngine`/`RoutinePhase`/`RoutineCue`.
- Produces: `RoutinePhase` ha 4 valori (`CONSTANT, CONSTANT_BEFORE, RUN, WALK`, non più 5). `RoutineCue` non ha più `ROUTINE_END`. `RoutineEngine.accept()` non emette mai più `RoutineCue.ROUTINE_END` né raggiunge `RoutinePhase.CONSTANT_AFTER` — un Cammina-Corri senza cap alterna RUN/WALK indefinitamente.

- [ ] **Step 1: Aggiorna i test che devono cambiare comportamento**

Rimuovi interamente questi tre test da `app/src/test/java/dev/fred/pacorun/routine/RoutineEngineTest.kt` (testano il comportamento che stiamo eliminando):

- `warmupCrossingThatAlsoEndsLimitedRoutineEmitsOnlyRoutineEndThenDirection` (righe 65-76)
- `unlimitedRoutineNeverEntersConstantAfter` (righe 186-196)
- `limitedRoutineEndsImmediatelyMidInterval` (righe 198-210)

Nel test rimasto `warmupCrossingCreditsOnlyPostBoundaryDistanceToRoutine` (riga 54), cambia:

```kotlin
        val engine = RoutineEngine(preferences(warmupKm = .001f, unlimited = false, routineKm = .01f))
```

in:

```kotlin
        val engine = RoutineEngine(preferences(warmupKm = .001f))
```

Nell'helper `preferences()` (righe 251-269), cambia da:

```kotlin
    private fun preferences(
        warmupKm: Float = 0f,
        runSeconds: Int = 20,
        walkSeconds: Int = 30,
        walkUsesConstantRange: Boolean = false,
        unlimited: Boolean = true,
        routineKm: Float = 5f,
    ) = RunPreferences(
        mode = RoutineMode.WALK_RUN,
        constantRange = SpeedRange(7f, 8f),
        runRange = SpeedRange(9f, 10f),
        walkUsesConstantRange = walkUsesConstantRange,
        walkRange = SpeedRange(4f, 5f),
        initialConstantKm = warmupKm,
        runSeconds = runSeconds,
        walkSeconds = walkSeconds,
        routineUnlimited = unlimited,
        routineKm = routineKm,
    )
```

a:

```kotlin
    private fun preferences(
        warmupKm: Float = 0f,
        runSeconds: Int = 20,
        walkSeconds: Int = 30,
        walkUsesConstantRange: Boolean = false,
    ) = RunPreferences(
        mode = RoutineMode.WALK_RUN,
        constantRange = SpeedRange(7f, 8f),
        runRange = SpeedRange(9f, 10f),
        walkUsesConstantRange = walkUsesConstantRange,
        walkRange = SpeedRange(4f, 5f),
        initialConstantKm = warmupKm,
        runSeconds = runSeconds,
        walkSeconds = walkSeconds,
    )
```

In `app/src/test/java/dev/fred/pacorun/ui/session/SessionVisualStateTest.kt`, nel test `constantPhasesUseSpeedHero` (riga 16-20), rimuovi la riga:

```kotlin
        assertEquals(HeroKind.SPEED, heroKind(RoutinePhase.CONSTANT_AFTER))
```

lasciando:

```kotlin
    @Test
    fun constantPhasesUseSpeedHero() {
        assertEquals(HeroKind.SPEED, heroKind(RoutinePhase.CONSTANT))
        assertEquals(HeroKind.SPEED, heroKind(RoutinePhase.CONSTANT_BEFORE))
    }
```

- [ ] **Step 2: Verifica che i test compilino ma alcuni falliscano ancora (il codice di produzione non è ancora cambiato)**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.routine.RoutineEngineTest" --tests "dev.fred.pacorun.ui.session.SessionVisualStateTest"`
Expected: compila; i test rimasti passano già a questo punto (non abbiamo ancora tolto `CONSTANT_AFTER`/`ROUTINE_END` dagli enum, quindi nessuna rottura — è normale, il passo successivo cambia la produzione).

- [ ] **Step 3: Rimuovi `CONSTANT_AFTER` e `ROUTINE_END` dagli enum**

In `app/src/main/java/dev/fred/pacorun/routine/RoutineModels.kt`, cambia:

```kotlin
enum class RoutinePhase { CONSTANT, CONSTANT_BEFORE, RUN, WALK, CONSTANT_AFTER }

enum class RoutineCue {
    ROUTINE_START, RUN_IN_TEN, WALK_IN_TEN,
    COUNTDOWN_BEEP, RUN_ROUTINE, WALK_ROUTINE, ROUTINE_END,
    SPEED_UP, SLOW_DOWN,
}
```

in:

```kotlin
enum class RoutinePhase { CONSTANT, CONSTANT_BEFORE, RUN, WALK }

enum class RoutineCue {
    ROUTINE_START, RUN_IN_TEN, WALK_IN_TEN,
    COUNTDOWN_BEEP, RUN_ROUTINE, WALK_ROUTINE,
    SPEED_UP, SLOW_DOWN,
}
```

- [ ] **Step 4: Rimuovi il controllo del cap e `finishRoutine()` da `RoutineEngine`**

In `app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt`, nel metodo `accept()`, cambia il blocco:

```kotlin
        if (phase == RoutinePhase.RUN || phase == RoutinePhase.WALK) {
            if (!preferences.routineUnlimited && routineDistanceMeters >= kmToMeters(preferences.routineKm)) {
                finishRoutine(reliableSpeed(input), cues)
                profileChanged = true
            } else {
                val transitions = advanceTo(input.elapsedRealtimeMillis)
                if (transitions > 0) {
                    cues += phaseCue()
                    directionCue(reliableSpeed(input), activeRange())?.let(cues::add)
                    profileChanged = true
                    if (transitions == 1 && durationMillis() <= 3_000L) cues += timingCues(input.elapsedRealtimeMillis)
                    else lastTimingCheckMillis = input.elapsedRealtimeMillis
                } else {
                    cues += timingCues(input.elapsedRealtimeMillis)
                }
            }
        }
```

in:

```kotlin
        if (phase == RoutinePhase.RUN || phase == RoutinePhase.WALK) {
            val transitions = advanceTo(input.elapsedRealtimeMillis)
            if (transitions > 0) {
                cues += phaseCue()
                directionCue(reliableSpeed(input), activeRange())?.let(cues::add)
                profileChanged = true
                if (transitions == 1 && durationMillis() <= 3_000L) cues += timingCues(input.elapsedRealtimeMillis)
                else lastTimingCheckMillis = input.elapsedRealtimeMillis
            } else {
                cues += timingCues(input.elapsedRealtimeMillis)
            }
        }
```

Poi elimina interamente il metodo `finishRoutine`:

```kotlin
    private fun finishRoutine(speed: Float?, cues: MutableList<RoutineCue>) {
        phase = RoutinePhase.CONSTANT_AFTER
        cues.clear()
        cues += RoutineCue.ROUTINE_END
        directionCue(speed, activeRange())?.let(cues::add)
    }
```

- [ ] **Step 5: Aggiorna `SessionHero.kt` e `SessionVisualState.kt`**

In `app/src/main/java/dev/fred/pacorun/ui/session/SessionHero.kt:213`, cambia:

```kotlin
    RoutinePhase.CONSTANT, RoutinePhase.CONSTANT_BEFORE, RoutinePhase.CONSTANT_AFTER -> "Costante"
```

in:

```kotlin
    RoutinePhase.CONSTANT, RoutinePhase.CONSTANT_BEFORE -> "Costante"
```

In `app/src/main/java/dev/fred/pacorun/ui/session/SessionVisualState.kt:12`, cambia:

```kotlin
    RoutinePhase.CONSTANT, RoutinePhase.CONSTANT_BEFORE, RoutinePhase.CONSTANT_AFTER -> HeroKind.SPEED
```

in:

```kotlin
    RoutinePhase.CONSTANT, RoutinePhase.CONSTANT_BEFORE -> HeroKind.SPEED
```

- [ ] **Step 6: Verifica che tutto compili e i test passino**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tutti i test verdi (incluso `RoutineEngineTest` e `SessionVisualStateTest` aggiornati).

- [ ] **Step 7: Lint**

Run: `./gradlew lintDebug`
Expected: BUILD SUCCESSFUL, nessun nuovo errore.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/routine/RoutineModels.kt \
        app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt \
        app/src/main/java/dev/fred/pacorun/ui/session/SessionHero.kt \
        app/src/main/java/dev/fred/pacorun/ui/session/SessionVisualState.kt \
        app/src/test/java/dev/fred/pacorun/routine/RoutineEngineTest.kt \
        app/src/test/java/dev/fred/pacorun/ui/session/SessionVisualStateTest.kt
git commit -m "refactor(routine): remove distance-cap/defaticamento phase from the engine"
```

---

### Task 2: Rimuovi la UI del Defaticamento (editor + riepilogo home)

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/ui/editor/RoutineEditorScreen.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/ui/UiTags.kt`
- Test: `app/src/androidTest/java/dev/fred/pacorun/ui/home/HomeScreenTest.kt`

**Interfaces:**
- Consumes: `preferences.routineUnlimited`/`preferences.routineKm` esistono ancora su `RunPreferences` a questo punto (rimossi nel Task 3) — questo task smette solo di leggerli dalla UI.
- Produces: l'editor di Cammina-Corri non mostra più una sezione "Defaticamento". Il riepilogo home per Cammina-Corri mostra solo il riscaldamento (es. `"Nessun riscaldamento"` o `"2,5 km di riscaldamento"`), senza più la parte `" · Senza defaticamento"`/`" · Fino a X km"`.

- [ ] **Step 1: Aggiorna le asserzioni di `HomeScreenTest.kt` al nuovo testo atteso**

In `app/src/androidTest/java/dev/fred/pacorun/ui/home/HomeScreenTest.kt`, sostituisci ognuna di queste quattro occorrenze:

Riga 51 e riga 103, da:
```kotlin
        composeRule.onNodeWithText("Nessun riscaldamento · Senza defaticamento").assertExists()
```
a:
```kotlin
        composeRule.onNodeWithText("Nessun riscaldamento").assertExists()
```

Riga 92, da:
```kotlin
        composeRule.onNodeWithText("2,5 km di riscaldamento · Senza defaticamento").assertExists()
```
a:
```kotlin
        composeRule.onNodeWithText("2,5 km di riscaldamento").assertExists()
```

Riga 110, da:
```kotlin
        composeRule.onNodeWithText("Nessun riscaldamento · Senza defaticamento").assertDoesNotExist()
```
a:
```kotlin
        composeRule.onNodeWithText("Nessun riscaldamento").assertDoesNotExist()
```

- [ ] **Step 2: Verifica che compili (i test falliranno finché non tocchi la produzione — atteso)**

Run: `./gradlew compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL (compila; l'esecuzione effettiva del test avviene dopo il passo successivo).

- [ ] **Step 3: Semplifica il testo di riepilogo in `HomeScreen.kt`**

Trova il blocco (attorno alla riga 239):

```kotlin
        if (warmupLabelVisible) {
            Text(
                (
                    if (preferences.initialConstantKm == 0f) {
                        "Nessun riscaldamento"
                    } else {
                        "${preferences.initialConstantKm.number()} km di riscaldamento"
                    }
                ) + " · " +
                    if (preferences.routineUnlimited) "Senza defaticamento" else "Fino a ${preferences.routineKm.number()} km",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.TopCenter).offset(y = (-12).dp),
                textAlign = TextAlign.Center,
            )
        }
```

e sostituiscilo con:

```kotlin
        if (warmupLabelVisible) {
            Text(
                if (preferences.initialConstantKm == 0f) {
                    "Nessun riscaldamento"
                } else {
                    "${preferences.initialConstantKm.number()} km di riscaldamento"
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.TopCenter).offset(y = (-12).dp),
                textAlign = TextAlign.Center,
            )
        }
```

- [ ] **Step 4: Rimuovi `RoutineEndSection` dall'editor**

In `app/src/main/java/dev/fred/pacorun/ui/editor/RoutineEditorScreen.kt`, nel blocco `Column` di `RoutineEditorScreen` (attorno alla riga 103-108), cambia:

```kotlin
                } else {
                    InitialConstantSection(preferences.initialConstantKm) { onPreferencesChange(preferences.copy(initialConstantKm = it)) }
                    RunSection(preferences, onPreferencesChange)
                    WalkSection(preferences, onPreferencesChange)
                    RoutineEndSection(preferences, onPreferencesChange)
                }
```

in:

```kotlin
                } else {
                    InitialConstantSection(preferences.initialConstantKm) { onPreferencesChange(preferences.copy(initialConstantKm = it)) }
                    RunSection(preferences, onPreferencesChange)
                    WalkSection(preferences, onPreferencesChange)
                }
```

Poi elimina interamente la funzione `RoutineEndSection` (l'intero blocco, dalla riga `@Composable` che la precede fino alla sua chiusura):

```kotlin
@Composable
private fun RoutineEndSection(preferences: RunPreferences, onPreferencesChange: (RunPreferences) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Defaticamento", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = colors.secondary)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleRow(
                label = "Senza defaticamento",
                checked = preferences.routineUnlimited,
                color = colors.secondary,
                tag = UiTags.routineUnlimited,
            ) { onPreferencesChange(preferences.copy(routineUnlimited = it)) }
            Text(
                "Con una distanza impostata, superato il limite la routine di camminata-corsa si ferma e si continua a camminare all'andatura scelta.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary.copy(alpha = 0.75f),
            )
        }
        AnimatedVisibility(!preferences.routineUnlimited) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DistanceControl(
                    label = "Distanza",
                    km = preferences.routineKm,
                    tag = UiTags.routineDistance,
                    roleColor = colors.secondary,
                    roleOnColor = colors.onSecondary,
                    onChange = { onPreferencesChange(preferences.copy(routineKm = it)) },
                )
                if (!preferences.routineKm.isFinite() || preferences.routineKm <= 0f) {
                    Text(
                        "La distanza routine deve essere positiva.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        modifier = Modifier.testTag(UiTags.routineDistanceError),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 5: Rimuovi i tag ora inutilizzati da `UiTags.kt`**

In `app/src/main/java/dev/fred/pacorun/ui/UiTags.kt`, rimuovi queste tre righe:

```kotlin
    const val routineUnlimited = "routine-unlimited"
    const val routineDistance = "routine-distance"
    const val routineDistanceError = "routine-distance-error"
```

- [ ] **Step 6: Verifica unit test e lint**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Avvia l'emulatore se non è già attivo, poi esegui i test instrumented**

Se `adb devices` non mostra un device attivo, avvia l'AVD esistente (`Pixel_9a_API_37`) e attendi il boot prima di procedere.

Run: `./gradlew connectedDebugAndroidTest`
Expected: BUILD SUCCESSFUL, tutti i test instrumented verdi (incluso `HomeScreenTest` con le nuove asserzioni).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/ui/editor/RoutineEditorScreen.kt \
        app/src/main/java/dev/fred/pacorun/ui/home/HomeScreen.kt \
        app/src/main/java/dev/fred/pacorun/ui/UiTags.kt \
        app/src/androidTest/java/dev/fred/pacorun/ui/home/HomeScreenTest.kt
git commit -m "refactor(ui): remove the Defaticamento section from the routine editor"
```

---

### Task 3: Rimuovi i campi dalle preferenze e dalla persistenza

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/settings/RunPreferences.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/settings/RunPreferencesStore.kt`
- Test: `app/src/test/java/dev/fred/pacorun/settings/RunPreferencesTest.kt`

**Interfaces:**
- Consumes: nessun altro file del progetto legge più `routineUnlimited`/`routineKm` a questo punto (rimosso nei Task 1 e 2) — è sicuro cancellare i campi.
- Produces: `RunPreferences` non ha più i campi `routineUnlimited`/`routineKm`. Le chiavi DataStore corrispondenti (`routine_unlimited`, `routine_km`) restano orfane sul disco degli utenti esistenti ma non vengono più lette né scritte — nessuna migrazione necessaria.

- [ ] **Step 1: Aggiorna `RunPreferencesTest.kt` al nuovo modello dati**

In `app/src/test/java/dev/fred/pacorun/settings/RunPreferencesTest.kt`, nel test `routineDefaultsAreUsable` (righe 9-20), rimuovi la riga:

```kotlin
        assertTrue(p.routineUnlimited)
```

Nel test `walkRunRequiresEveryActiveRangeAndPositiveDurations` (righe 22-29), cambia:

```kotlin
    @Test
    fun walkRunRequiresEveryActiveRangeAndPositiveDurations() {
        val base = RunPreferences(mode = RoutineMode.WALK_RUN, routineUnlimited = false, routineKm = 5f)
        assertFalse(base.copy(runSeconds = 0).isValid)
        assertFalse(base.copy(walkSeconds = 0).isValid)
        assertFalse(base.copy(routineKm = 0f).isValid)
        assertFalse(base.copy(walkUsesConstantRange = false, walkRange = SpeedRange(6f, 6f)).isValid)
    }
```

in:

```kotlin
    @Test
    fun walkRunRequiresEveryActiveRangeAndPositiveDurations() {
        val base = RunPreferences(mode = RoutineMode.WALK_RUN)
        assertFalse(base.copy(runSeconds = 0).isValid)
        assertFalse(base.copy(walkSeconds = 0).isValid)
        assertFalse(base.copy(walkUsesConstantRange = false, walkRange = SpeedRange(6f, 6f)).isValid)
    }
```

- [ ] **Step 2: Verifica che i test compilino (falliranno ancora finché non tocchi la produzione — atteso)**

Run: `./gradlew compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Rimuovi i campi da `RunPreferences.kt`**

Cambia:

```kotlin
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
    val confirmationSamples: Int = 2,
    val repeatMillis: Long = 5_000L,
    val gpsCuesEnabled: Boolean = false,
    val cueVolume: Float = .6f,
    val dynamicColor: Boolean = true,
) {
    val isValid: Boolean
        get() {
            if (!constantRange.isValid) return false
            if (!cueVolume.isFinite() || cueVolume !in 0f..1f) return false
            if (mode != RoutineMode.WALK_RUN) return true

            val effectiveWalkRange = if (walkUsesConstantRange) constantRange else walkRange
            return runRange.isValid &&
                effectiveWalkRange.isValid &&
                initialConstantKm.isFinite() &&
                initialConstantKm >= 0f &&
                runSeconds > 0 &&
                walkSeconds > 0 &&
                (routineUnlimited || (routineKm.isFinite() && routineKm > 0f))
        }
}
```

in:

```kotlin
data class RunPreferences(
    val mode: RoutineMode = RoutineMode.CONSTANT,
    val constantRange: SpeedRange = SpeedRange(7.5f, 8.5f),
    val runRange: SpeedRange = SpeedRange(7.5f, 8.5f),
    val walkUsesConstantRange: Boolean = true,
    val walkRange: SpeedRange = SpeedRange(4f, 6f),
    val initialConstantKm: Float = 0f,
    val runSeconds: Int = 60,
    val walkSeconds: Int = 120,
    val confirmationSamples: Int = 2,
    val repeatMillis: Long = 5_000L,
    val gpsCuesEnabled: Boolean = false,
    val cueVolume: Float = .6f,
    val dynamicColor: Boolean = true,
) {
    val isValid: Boolean
        get() {
            if (!constantRange.isValid) return false
            if (!cueVolume.isFinite() || cueVolume !in 0f..1f) return false
            if (mode != RoutineMode.WALK_RUN) return true

            val effectiveWalkRange = if (walkUsesConstantRange) constantRange else walkRange
            return runRange.isValid &&
                effectiveWalkRange.isValid &&
                initialConstantKm.isFinite() &&
                initialConstantKm >= 0f &&
                runSeconds > 0 &&
                walkSeconds > 0
        }
}
```

- [ ] **Step 4: Rimuovi decode/write/Keys da `RunPreferencesStore.kt`**

Nel metodo `decode`, rimuovi le righe:

```kotlin
            routineUnlimited = values[Keys.routineUnlimited] ?: defaults.routineUnlimited,
            routineKm = values[Keys.routineKm] ?: defaults.routineKm,
```

Nel metodo `write`, rimuovi le righe:

```kotlin
        values[Keys.routineUnlimited] = preferences.routineUnlimited
        values[Keys.routineKm] = preferences.routineKm
```

Nell'oggetto `Keys`, rimuovi le righe:

```kotlin
        val routineUnlimited = booleanPreferencesKey("routine_unlimited")
        val routineKm = floatPreferencesKey("routine_km")
```

- [ ] **Step 5: Verifica unit test e lint**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: BUILD SUCCESSFUL, tutti i test verdi.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/settings/RunPreferences.kt \
        app/src/main/java/dev/fred/pacorun/settings/RunPreferencesStore.kt \
        app/src/test/java/dev/fred/pacorun/settings/RunPreferencesTest.kt
git commit -m "refactor(settings): drop routineKm/routineUnlimited, defaticamento is gone"
```

---

### Task 4: Verifica finale e build

**Files:** nessuno (solo verifica).

**Interfaces:**
- Consumes: l'intero progetto dopo i Task 1-3.
- Produces: conferma finale + APK di sviluppo installabile, con nome che non tocca la copia buona dell'utente.

- [ ] **Step 1: Suite completa, nell'ordine standard del progetto**

Run:
```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
./gradlew installDebug
```
Expected: tutti e quattro i comandi BUILD SUCCESSFUL, nessun test fallito.

- [ ] **Step 2: Copia l'APK con un nome che NON sovrascrive la copia buona dell'utente**

```bash
cp app/build/outputs/apk/debug/app-debug.apk ~/Documents/Paco-Run-m3e-debug-V3-simplify-walkrun.apk
```

Verifica prima con `ls ~/Documents/*.apk` che `Paco-Run-m3e-debug-V2.apk` sia ancora presente e con la stessa dimensione/data di prima — non deve essere stata toccata.

- [ ] **Step 3: Grep di conferma — nessun residuo del concetto rimosso**

```bash
grep -rn "routineKm\|routineUnlimited\|CONSTANT_AFTER\|RoutineEndSection\|Defaticamento\|defaticamento" --include="*.kt" app/src
```
Expected: nessun risultato (grep esce con status 1, "no matches").
