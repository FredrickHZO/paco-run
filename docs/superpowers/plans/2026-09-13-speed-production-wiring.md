# Fase 0.5 — Collegare SpeedEstimator alla produzione (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sostituire `location.speed` (Doppler grezzo) con `SpeedEstimator` (Kalman, già validato due volte su corse reali) come sorgente di velocità reale per i tre consumatori in produzione (`PaceEvaluator`, `RunSessionState.speedKmh`, `RoutineInput.currentReliableSpeedKmh`), dietro una nuova interfaccia `SessionSpeedEngine` — stesso pattern interfaccia+adattatore già usato tre volte in questo codebase (`SessionRoutineEngine`, `SessionPaceEvaluator`, e `SessionDistanceEngine` della Fase 0).

**Architettura:** `SessionSpeedEngine` ha due implementazioni: `DopplerSpeedEngine` (comportamento di oggi, wrapper su `sample.speedKmh`) e `KalmanSpeedEngine` (adattatore su `SpeedEstimator`, invariato). `KalmanSpeedEngine` diventa il default reale in produzione; `DopplerSpeedEngine` resta come rete di sicurezza esplicita, rimovibile solo dopo 5 corse reali validate (decisione futura dell'utente, non di questo piano). Il piano introduce la plumbing a comportamento invariato prima (default Doppler), verifica zero regressioni, POI in un task dedicato flippa il default e aggiorna i soli test che devono restare ancorati al comportamento Doppler esplicito. Consolida anche il `SpeedEstimator` diagnostico (oggi una seconda istanza indipendente in `RunSessionService`) in un'unica istanza condivisa via un nuovo callback `onSpeedComputed`.

**Tech Stack:** Kotlin, JUnit4, Gradle (`./gradlew testDebugUnitTest`, `lintDebug`).

**Spec:** `docs/superpowers/specs/2026-09-13-speed-production-wiring-design.md` (commit `0d58654` su `feature/paco-run-m3e`, comprensivo della semplificazione della sezione 4 fatta durante la scrittura di questo piano — vedi Task 2).

## Global Constraints

- **Zero cambi di comportamento fino al Task 5 incluso il suo stesso commit di flip.** Fino a quel punto ogni test esistente deve restare verde senza modifiche al proprio contenuto — stessa disciplina della Fase 0.
- **Non toccare la logica interna di `SpeedEstimator`.** È già implementato, testato e validato due volte su corse reali — questo piano lo collega, non lo modifica.
- **Non rimuovere `DopplerSpeedEngine`.** Resta nel codice come rete di sicurezza — vedi spec sezione 7.
- **Non aggiungere alcun marker per i momenti di GPS completamente perso nel GPX.** Deciso esplicitamente di accettare quella lacuna — vedi spec sezione 5.
- **Nome dell'interfaccia: `SessionSpeedEngine`**, firma esatta: `fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double` / `fun breakChain(): Double` — non negoziabile, già discussa e approvata nella spec.
- **Fuori scope:** rimozione di `DopplerSpeedEngine`; qualunque lavoro di Fase 1 (`feature/gps-precision`) o Fase 2 (inerziale); G12 (Doppler satellitare grezzo, concetto diverso nonostante il nome simile); il fixed-lag smoothing G8 (brainstorm futuro separato).

---

## File Structure

| File | Ruolo | Task |
|---|---|---|
| `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt` | Modifica: aggiunge `SessionSpeedEngine`, `DopplerSpeedEngine`, `KalmanSpeedEngine`; wiring in `SessionCoordinator`/`PendingSessionCoordinator` | 1, 2, 3, 5 |
| `app/src/test/java/dev/fred/pacorun/service/SessionSpeedEngineTest.kt` | Nuovo: test delle due implementazioni | 1 |
| `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt` | Modifica: nuovi test di integrazione (Task 4); aggiunta esplicita di `speedEngine = DopplerSpeedEngine()` ai test esistenti (Task 5) | 4, 5 |
| `docs/superpowers/specs/2026-09-11-precision-roadmap.md` | Modifica: stato del flip + avvio conteggio corse per il criterio di rimozione di `DopplerSpeedEngine` | 5 |
| `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt` | Modifica: consolidamento `SpeedEstimator` diagnostico via `onSpeedComputed` | 6 |
| `app/src/main/java/dev/fred/pacorun/export/GpxWriter.kt` | Modifica: `speedEstimatorKmh` diventa opzionale nell'export | 6 |
| `app/src/test/java/dev/fred/pacorun/export/GpxWriterTest.kt` | Modifica: rinomina un test, ne aggiunge uno per il caso omesso | 6 |
| `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorRealRunTest.kt` | Nuovo: replay di sanità di una corsa reale attraverso il vero `SessionCoordinator` | 7 |

---

### Task 1: `SessionSpeedEngine` + `DopplerSpeedEngine` + `KalmanSpeedEngine`

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`
- Test: `app/src/test/java/dev/fred/pacorun/service/SessionSpeedEngineTest.kt`

**Interfaces:**
- Produces: `interface SessionSpeedEngine { fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double; fun breakChain(): Double }`, `class DopplerSpeedEngine : SessionSpeedEngine`, `class KalmanSpeedEngine(private val speedEstimator: SpeedEstimator = SpeedEstimator()) : SessionSpeedEngine`.
- Consumes: `LocationSample` (esistente, `dev.fred.pacorun.location`), `LocationPosition` (esistente, stesso file), `SpeedEstimator` (esistente, stesso package, firma `accept(position: LocationPosition, reliable: Boolean): Double` / `breakChain(): Double`, invariata).

- [ ] **Step 1: Scrivere il test per `DopplerSpeedEngine`**

```kotlin
package dev.fred.pacorun.service

import dev.fred.pacorun.location.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSpeedEngineTest {
    @Test
    fun dopplerEngineReturnsRawSampleSpeedUnchanged() {
        val engine = DopplerSpeedEngine()
        val sample = LocationSample(speedKmh = 9.4f, speedAccuracyKmh = .2f, reliable = true, elapsedRealtimeMillis = 0L)
        val position = LocationPosition(45.0, 9.0, 0L, 3f)

        assertEquals(9.4, engine.accept(sample, position, reliable = true), .0001)
    }

    @Test
    fun dopplerEngineBreakChainIsANoOpReturningZero() {
        val engine = DopplerSpeedEngine()

        assertEquals(0.0, engine.breakChain(), .0001)
    }

    @Test
    fun kalmanEngineDelegatesToSpeedEstimator() {
        val engine = KalmanSpeedEngine()
        val sample = LocationSample(speedKmh = 40f, speedAccuracyKmh = .2f, reliable = true, elapsedRealtimeMillis = 0L)
        val firstFix = LocationPosition(45.0, 9.0, 0L, 3f)
        val secondFix = LocationPosition(45.0001, 9.0, 1_000L, 3f)

        // First fix: SpeedEstimator always returns 0.0 (initialization) -- proves delegation,
        // not a coincidence, since the sample's own speedKmh (40f) is nowhere near 0.
        assertEquals(0.0, engine.accept(sample, firstFix, reliable = true), .0001)
        // Second fix: real position-derived speed from SpeedEstimator, not the sample's 40f --
        // don't pin an exact Kalman output here (that's SpeedEstimatorTest.kt's job), just prove
        // this came from position, not from the sample's speedKmh field.
        val secondSpeed = engine.accept(sample, secondFix, reliable = true)
        assertTrue("expected a finite, non-negative speed", secondSpeed.isFinite() && secondSpeed >= 0.0)
        assertTrue("expected the position-derived speed to differ from the sample's own speedKmh (40)", secondSpeed != 40.0)
    }

    @Test
    fun kalmanEngineBreakChainDelegatesToSpeedEstimator() {
        val engine = KalmanSpeedEngine()
        val sample = LocationSample(speedKmh = 9f, speedAccuracyKmh = .2f, reliable = true, elapsedRealtimeMillis = 0L)
        engine.accept(sample, LocationPosition(45.0, 9.0, 0L, 3f), reliable = true)

        assertEquals(0.0, engine.breakChain(), .0001)
        // After breakChain, the next accept behaves like a fresh first fix again (0.0).
        assertEquals(0.0, engine.accept(sample, LocationPosition(45.0, 9.0001, 1_000L, 3f), reliable = true), .0001)
    }
}
```

- [ ] **Step 2: Eseguire il test, verificare che fallisca (le classi non esistono ancora)**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionSpeedEngineTest"`
Expected: FAIL, "unresolved reference: DopplerSpeedEngine" (o simile).

- [ ] **Step 3: Aggiungere l'interfaccia e `DopplerSpeedEngine`, subito sotto `SessionPaceEvaluator` in `RunSessionStore.kt`**

```kotlin
interface SessionSpeedEngine {
    fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double
    fun breakChain(): Double
}

class DopplerSpeedEngine : SessionSpeedEngine {
    override fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double =
        sample.speedKmh.toDouble()

    // No chain to break for an instantaneous Doppler reading -- no-op, only here to satisfy the interface.
    override fun breakChain(): Double = 0.0
}
```

- [ ] **Step 4: Aggiungere `KalmanSpeedEngine`, in fondo al file, accanto a `RoutineEngineAdapter`/`PaceEvaluatorAdapter`**

```kotlin
class KalmanSpeedEngine(private val speedEstimator: SpeedEstimator = SpeedEstimator()) : SessionSpeedEngine {
    override fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double =
        speedEstimator.accept(position, reliable)

    override fun breakChain(): Double = speedEstimator.breakChain()
}
```

- [ ] **Step 5: Eseguire il test, verificare che passi**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionSpeedEngineTest"`
Expected: PASS.

- [ ] **Step 6: Eseguire l'intera suite, confermare zero regressioni (nessun altro file tocca ancora queste classi)**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt app/src/test/java/dev/fred/pacorun/service/SessionSpeedEngineTest.kt
git commit -m "feat: add SessionSpeedEngine interface with Doppler and Kalman implementations"
```

---

### Task 2: Collegare `speedEngine` a `SessionCoordinator` (default Doppler, zero cambi di comportamento)

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`

**Interfaces:**
- Consumes: `SessionSpeedEngine`, `DopplerSpeedEngine` (Task 1).
- Produces: `SessionCoordinator`'s costruttore guadagna due parametri: `private val speedEngine: SessionSpeedEngine = DopplerSpeedEngine()` (default temporaneo, non ancora quello di produzione -- vedi Task 5) e `private val onSpeedComputed: (Double?) -> Unit = {}`. Nessuna nuova proprietà di stato: la finestra di grazia (`timerRetainsFreshGps`) eredita il valore del motore riusando `latestReliableSample`, già esistente (vedi Step 2 sotto -- questa è la semplificazione rispetto alla spec originale, che proponeva un campo dedicato `latestEngineSpeedKmh`).

Questo task non introduce nuovo comportamento osservabile dall'esterno (il default resta equivalente a oggi) — non c'è un nuovo test a sé; la "prova" è che l'intera suite esistente resti verde senza modifiche al proprio contenuto, esattamente come nella Fase 0.

- [ ] **Step 1: Aggiungere i due parametri al costruttore di `SessionCoordinator`**

```kotlin
class SessionCoordinator(
    private val preferences: RunPreferences,
    private val routineEngine: SessionRoutineEngine,
    private val paceEvaluator: SessionPaceEvaluator,
    private val onCues: (List<AudioCue>, Float) -> Unit,
    private val publish: (RunSessionState) -> Unit,
    private val startedAtElapsedMillis: Long = 0L,
    private val distanceEngine: SessionDistanceEngine = DistanceAccumulator(),
    private val speedEngine: SessionSpeedEngine = DopplerSpeedEngine(),
    private val onSpeedComputed: (Double?) -> Unit = {},
) {
```

- [ ] **Step 2: Modificare `process()` per chiamare `speedEngine` negli stessi punti di `distanceEngine`, sostituendo `speedKmh` in `paceSample`**

Sostituire il blocco che costruisce `paceSample` e quello che gestisce `enteredOutage`:

```kotlin
        val paceSample = when {
            actualReliable -> {
                val engineSpeedKmh = speedEngine.accept(requireNotNull(sample), requireNotNull(position), reliable = true)
                onSpeedComputed(engineSpeedKmh)
                requireNotNull(sample).copy(speedKmh = engineSpeedKmh.toFloat())
            }
            timerRetainsFreshGps -> requireNotNull(latestReliableSample)
            else -> sample ?: LocationSample(0f, null, false, elapsedRealtimeMillis)
        }
        val enteredOutage = !gpsReliable && !outageActive && (sample != null || signalGpsOutage)
        if (actualReliable) {
            latestReliableSample = paceSample
            outageActive = false
        } else if (enteredOutage) {
            latestReliableSample = null
            outageActive = true
            distanceEngine.breakChain()
            speedEngine.breakChain()
        }
```

(Il resto di `process()` — instradamento di `distanceDelta`, `routine`, `pace`, `cues`, `publish` — resta identico: legge tutto da `paceSample`/`gpsReliable` come già fa oggi.)

**Perché questo basta per la finestra di grazia, senza un campo nuovo:** `latestReliableSample = paceSample` è codice già esistente, invariato — e ora `paceSample` (nel ramo `actualReliable`) porta già il valore del motore al posto del Doppler grezzo. Quando più avanti `timerRetainsFreshGps` riusa `latestReliableSample`, riusa automaticamente l'ultimo valore del motore, non il Doppler.

- [ ] **Step 3: Eseguire l'intera suite di test, confermare zero regressioni**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.*"`
Expected: PASS, tutti i test esistenti (incluso `SessionCoordinatorTest.kt`) senza nessuna modifica al loro contenuto — il default `DopplerSpeedEngine()` produce esattamente `sample.speedKmh` (un roundtrip `Float -> Double -> Float` è sempre esatto per un valore finito, quindi `paceSample.speedKmh` resta bit-identico a `sample.speedKmh` di oggi). Se qualcosa fallisce, fermarsi e correggere prima di proseguire, non modificare i test per farli passare.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt
git commit -m "feat: wire speedEngine into SessionCoordinator with zero behavior change (Doppler default)"
```

---

### Task 3: Collegare `speedEngine` e `onSpeedComputed` attraverso `PendingSessionCoordinator`

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`

**Interfaces:**
- Produces: `PendingSessionCoordinator`'s costruttore guadagna gli stessi due parametri (`speedEngine: SessionSpeedEngine = DopplerSpeedEngine()`, `onSpeedComputed: (Double?) -> Unit = {}`), forwardati nella sua `activate()`.

Zero cambi di comportamento anche qui — stessa disciplina del Task 2.

- [ ] **Step 1: Aggiungere i due parametri al costruttore di `PendingSessionCoordinator` e forwardarli in `activate()`**

```kotlin
class PendingSessionCoordinator(
    private val preferences: RunPreferences,
    private val routineEngine: SessionRoutineEngine,
    private val paceEvaluator: SessionPaceEvaluator,
    private val onCues: (List<AudioCue>, Float) -> Unit,
    private val publish: (RunSessionState) -> Unit,
    private val speedEngine: SessionSpeedEngine = DopplerSpeedEngine(),
    private val onSpeedComputed: (Double?) -> Unit = {},
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
            speedEngine = speedEngine,
            onSpeedComputed = onSpeedComputed,
        ).also { activeCoordinator = it }
}
```

- [ ] **Step 2: Eseguire l'intera suite, confermare zero regressioni**

Run: `./gradlew testDebugUnitTest`
Expected: PASS, nessuna modifica ai test esistenti.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt
git commit -m "feat: forward speedEngine and onSpeedComputed through PendingSessionCoordinator"
```

---

### Task 4: Chiudere il gap di test di integrazione (outage/recovery, finestra di grazia, callback)

**Files:**
- Modify: `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt`

**Interfaces:**
- Consumes: `SessionSpeedEngine` (Task 1), i parametri `speedEngine`/`onSpeedComputed` di `SessionCoordinator` (Task 2).
- Produces: `FakeSpeedEngine` (classe privata di test, stesso stile di `FakeRoutineEngine`/`FakePaceEvaluator` già nel file), 3 nuovi test.

- [ ] **Step 1: Aggiungere `FakeSpeedEngine` accanto alle altre classi fake private in fondo al file**

```kotlin
    private class FakeSpeedEngine(private val fixedOutput: Double = 0.0) : SessionSpeedEngine {
        var acceptCalls = 0
            private set
        var breakChainCalls = 0
            private set

        override fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double {
            acceptCalls++
            return fixedOutput
        }

        override fun breakChain(): Double {
            breakChainCalls++
            return 0.0
        }
    }
```

- [ ] **Step 2: Scrivere il test di sincronizzazione lifecycle**

```kotlin
    @Test
    fun speedEngineSyncsWithDistanceEngineOnReliableFixesAndOutage() {
        val speedEngine = FakeSpeedEngine()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = {},
            speedEngine = speedEngine,
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.tick(1_000L, reliableSample(8f, 1_000L), LocationPosition(0.0, .0001, 1_000L))
        assertEquals(2, speedEngine.acceptCalls)
        assertEquals(0, speedEngine.breakChainCalls)

        coordinator.advanceTimer(5_000L, signalGpsOutage = true)
        assertEquals(1, speedEngine.breakChainCalls)
    }
```

- [ ] **Step 3: Scrivere il test di regressione per la finestra di grazia**

```kotlin
    @Test
    fun timerRetainsFreshGpsUsesSpeedEngineOutputNotRawSample() {
        val speedEngine = FakeSpeedEngine(fixedOutput = 42.0)
        val published = mutableListOf<RunSessionState>()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = published::add,
            speedEngine = speedEngine,
        )

        coordinator.tick(0L, reliableSample(9f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.advanceTimer(1_000L, signalGpsOutage = false)

        val state = published.last() as RunSessionState.Running
        assertTrue(state.gpsReliable)
        // Without the fix, this would read 9f (the raw sample's speedKmh, cached in
        // latestReliableSample before the substitution) instead of 42f (the engine's output).
        assertEquals(42f, state.speedKmh)
    }
```

- [ ] **Step 4: Scrivere il test per `onSpeedComputed`**

```kotlin
    @Test
    fun onSpeedComputedFiresOnlyOnActualReliableFixesWithEngineValue() {
        val computed = mutableListOf<Double?>()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = {},
            speedEngine = FakeSpeedEngine(fixedOutput = 7.0),
            onSpeedComputed = { computed += it },
        )

        coordinator.tick(0L, reliableSample(9f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.advanceTimer(1_000L, signalGpsOutage = false) // grace window: must NOT fire
        coordinator.advanceTimer(5_000L, signalGpsOutage = true) // outage: must NOT fire

        assertEquals(listOf(7.0), computed)
    }
```

- [ ] **Step 5: Eseguire i tre nuovi test, verificare che passino**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorTest"`
Expected: PASS (tutti e 3 i nuovi test, più tutti quelli preesistenti nel file — questo task non cambia il default, quindi anche i test esistenti restano verdi senza toccarli).

- [ ] **Step 6: Commit**

```bash
git add app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt
git commit -m "test: cover speed engine lifecycle sync, grace-window fix, and onSpeedComputed callback"
```

---

### Task 5: Flip del default a `KalmanSpeedEngine` (il vero switch di produzione)

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt`
- Modify: `docs/superpowers/specs/2026-09-11-precision-roadmap.md`

**Contesto:** da qui in poi ogni corsa userà `SpeedEstimator` per i cue dal vivo — questo è lo scopo dichiarato della fase (spec sezione 6). I test esistenti in `SessionCoordinatorTest.kt` sono stati scritti iniettando valori di velocità arbitrari via `reliableSample(speedKmh, ...)` indipendenti dalla posizione (per testare la logica di routine/pace/outage isolata dalla geometria) — con `KalmanSpeedEngine` come default reale, quei valori verrebbero ignorati (il motore calcola dalla posizione, non dal campo `speedKmh` iniettato), rompendo le asserzioni esistenti su `state.speedKmh` e sui cue di pace. Per questo, ogni costruzione esistente di `SessionCoordinator`/`PendingSessionCoordinator` in quel file deve ricevere esplicitamente `speedEngine = DopplerSpeedEngine()` — non è un compromesso, è la stessa identica cosa che questi test facevano implicitamente prima che il default cambiasse.

- [ ] **Step 1: Cambiare il default nel costruttore di `SessionCoordinator`**

```kotlin
    private val speedEngine: SessionSpeedEngine = KalmanSpeedEngine(),
```

- [ ] **Step 2: Cambiare il default nel costruttore di `PendingSessionCoordinator`**

```kotlin
    private val speedEngine: SessionSpeedEngine = KalmanSpeedEngine(),
```

- [ ] **Step 3: Eseguire la suite, osservare i fallimenti attesi**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorTest"`
Expected: FAIL sui test che asseriscono un valore preciso di `state.speedKmh` o un cue di pace dipendente da un valore di velocità iniettato (`profileResetPrecedesPaceEvaluationAndRoutineCuesPrecedePaceCues`, `timerBeforeEveryLocationDoesNotBreakPaceConfirmation`, `weakGpsCueAndRecoveryClearPaceCandidates`, `healthyTimerRetainsReliableSpeedForStateAndPhaseDirectionCue`, `profileChangesDuringOneOutageEmitOneBadGpsThenOneGoodGpsOnRecovery`) — questo è il segnale atteso: il default è davvero cambiato. I 3 test aggiunti nel Task 4 restano verdi (specificano `speedEngine` esplicitamente).

- [ ] **Step 4: Aggiungere `speedEngine = DopplerSpeedEngine()` a OGNI costruzione esistente di `SessionCoordinator` e `PendingSessionCoordinator` nel file (13 punti — tutti i test scritti prima del Task 4), senza toccare nessun'altra riga**

Per ciascuno dei seguenti test, aggiungere il parametro nella chiamata al costruttore (nessun'altra modifica al corpo del test):
`profileResetPrecedesPaceEvaluationAndRoutineCuesPrecedePaceCues`, `weakGpsStillAdvancesRoutineAndIsPassedToPaceWithoutDistance`, `timerBeforeEveryLocationKeepsAnchorAndProcessesEachLocationOnce`, `timerBeforeEveryLocationDoesNotBreakPaceConfirmation`, `timerFirstLocationInSameSecondKeepsDistanceAndPaceWithoutAnotherTimingAdvance`, `weakGpsCueAndRecoveryClearPaceCandidates`, `firstTimerOutageBreaksAnchorSoRecoveryDoesNotBridgeDistance`, `healthyTimerRetainsReliableSpeedForStateAndPhaseDirectionCue`, `profileChangesDuringOneOutageEmitOneBadGpsThenOneGoodGpsOnRecovery`, `timerAndRoutineDoNotAdvanceBeforeFirstReliableFix`, `unreliableSampleWhileAcquiringDoesNotActivate`, `firstReliableFixActivatesAndStartsAtZeroWithoutAddingDistance`, `onceActivatedLaterTicksDelegateToTheSameCoordinator`.

Esempio per il primo:

```kotlin
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = pace,
            onCues = { cues, _ -> events += "cues:${cues.joinToString()}" },
            publish = published::add,
            speedEngine = DopplerSpeedEngine(),
        )
```

(Stesso schema per gli altri 12: aggiungere `speedEngine = DopplerSpeedEngine(),` come ultimo argomento nominato della chiamata al costruttore.)

- [ ] **Step 5: Eseguire l'intera suite, confermare che sia di nuovo verde**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — ogni test preesistente si comporta esattamente come prima (perché ora specifica esplicitamente lo stesso motore che prima era il default implicito), i 3 test del Task 4 restano verdi.

- [ ] **Step 6: Aggiornare la tabella "Stato delle decisioni" nella roadmap**

In `docs/superpowers/specs/2026-09-11-precision-roadmap.md`, subito dopo la riga "Refactor `MotionEngine`..." (riga 13 della tabella, quella marcata "Completato (2026-09-13)"), aggiungere una nuova riga:

```markdown
| Fase 0.5 — `SpeedEstimator` collegato alla produzione (`KalmanSpeedEngine`) | **Completato (2026-09-13) — Kalman è ora il motore reale, non più solo diagnostico** | `DopplerSpeedEngine` resta come rete di sicurezza, rimovibile solo dopo 5 corse reali validate col motore vero attivo (decisione dell'utente, non automatica — vedi `docs/superpowers/specs/2026-09-13-speed-production-wiring-design.md` sezione 7). Conteggio corse validate: 0/5 (le 2 corse precedenti erano diagnostiche, non contano) |
```

Usare esattamente lo stesso stile/formato di riga delle altre voci della tabella (colonne `Voce`/`Stato`/`Note`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt docs/superpowers/specs/2026-09-11-precision-roadmap.md
git commit -m "feat: switch production speed source to KalmanSpeedEngine"
```

---

### Task 6: Consolidare il `SpeedEstimator` diagnostico nell'unica istanza di produzione

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/export/GpxWriter.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/export/GpxWriterTest.kt`

**Interfaces:**
- Consumes: `onSpeedComputed: (Double?) -> Unit` (parametro di `PendingSessionCoordinator`, Task 3).
- Produces: `TrackPoint.speedEstimatorKmh` cambia tipo da `Double` a `Double?`.

- [ ] **Step 1: Scrivere il test per l'omissione del tag quando il valore è assente**

In `GpxWriterTest.kt`, aggiungere:

```kotlin
    @Test
    fun speedEstimatorKmhOmittedWhenNull() {
        val gpx = GpxWriter.build(
            listOf(
                TrackPoint(
                    latitude = 0.0,
                    longitude = 0.0,
                    timeEpochMillis = 0L,
                    speedMps = null,
                    horizontalAccuracyMeters = null,
                    speedAccuracyMps = null,
                    reliable = false,
                    speedEstimatorKmh = null,
                ),
            ),
            activityName = "Paco Run",
        )

        assertTrue(!gpx.contains("<pr:speedEstimatorKmh>"))
    }
```

E rinominare il test esistente `pointExtensionsCarrySpeedEstimatorKmhUnconditionally` in `pointExtensionsCarrySpeedEstimatorKmhWhenPresent` (il nome "Unconditionally" non è più corretto — il tag ora è condizionale — il corpo del test resta identico, verifica solo il caso valorizzato).

- [ ] **Step 2: Eseguire il nuovo test, verificare che fallisca**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.export.GpxWriterTest"`
Expected: FAIL a compilazione (`TrackPoint`'s `speedEstimatorKmh` non accetta ancora `null`).

- [ ] **Step 3: Rendere `speedEstimatorKmh` opzionale in `TrackPoint` e nell'export**

In `GpxWriter.kt`:

```kotlin
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val timeEpochMillis: Long,
    val speedMps: Float?,
    val horizontalAccuracyMeters: Float?,
    val speedAccuracyMps: Float?,
    val reliable: Boolean,
    // Diagnostic-only: SpeedEstimator's own speed output for this fix. Null when the fix was
    // completely unreliable (the engine was never called for it) -- see RunSessionService.
    val speedEstimatorKmh: Double?,
)
```

E in `appendTrackPoint`:

```kotlin
        point.speedEstimatorKmh?.let { appendLine("          <pr:speedEstimatorKmh>$it</pr:speedEstimatorKmh>") }
```

- [ ] **Step 4: Eseguire tutti i test di `GpxWriterTest`, verificare che passino**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.export.GpxWriterTest"`
Expected: PASS.

- [ ] **Step 5: Consolidare in `RunSessionService.kt` — rimuovere la seconda istanza, usare il callback**

Rimuovere il campo diagnostico e il suo reset:

```kotlin
    // RIMUOVERE:
    private var speedEstimator = SpeedEstimator()
```

```kotlin
    // RIMUOVERE (in beginTracking()):
    speedEstimator = SpeedEstimator()
```

Aggiungere un campo per catturare l'ultimo valore del motore condiviso:

```kotlin
    private var lastEngineSpeedKmh: Double? = null
```

In `beginTracking()`, passare il callback a `PendingSessionCoordinator`:

```kotlin
        coordinator = PendingSessionCoordinator(
            preferences = preferences,
            routineEngine = RoutineEngineAdapter(RoutineEngine(preferences)),
            paceEvaluator = PaceEvaluatorAdapter(PaceEvaluator(preferences.paceConfig())),
            onCues = { cues, volume -> cuePlayer?.enqueue(cues, volume) },
            publish = ::publish,
            onSpeedComputed = { lastEngineSpeedKmh = it },
        )
```

- [ ] **Step 6: Riordinare `onLocation()` per chiamare `coordinator?.tick(...)` PRIMA di costruire il `TrackPoint`, usando il valore catturato**

```kotlin
    private fun onLocation(location: Location) {
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
        coordinator?.tick(
            elapsedRealtimeMillis = now,
            sample = sample,
            position = position,
        )
        // Every raw fix, reliable or not -- so the exported track shows exactly what was
        // (and wasn't) trusted. speedEstimatorKmh is null when the fix was fully unreliable
        // (the shared engine was never called for it, by design -- see spec section 5).
        trackPoints += TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timeEpochMillis = location.time,
            speedMps = location.takeIf { it.hasSpeed() }?.speed,
            horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy,
            speedAccuracyMps = location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond,
            reliable = sample.reliable,
            speedEstimatorKmh = lastEngineSpeedKmh,
        )
    }
```

- [ ] **Step 7: Compilare ed eseguire l'intera suite**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: PASS. Nessun test unitario esercita direttamente `RunSessionService` (Android `Service`, non testabile su JVM senza Robolectric — non introdotto da questo piano, coerente con l'assenza di `RunSessionServiceTest` nel progetto).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt app/src/main/java/dev/fred/pacorun/export/GpxWriter.kt app/src/test/java/dev/fred/pacorun/export/GpxWriterTest.kt
git commit -m "refactor: consolidate diagnostic SpeedEstimator into the shared production instance"
```

---

### Task 7: Replay di sanità di una corsa reale attraverso il vero `SessionCoordinator`

**Files:**
- Test: `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorRealRunTest.kt`

**Contesto:** `SpeedEstimator` da solo è già validato (`SpeedEstimatorRealRunTest.kt`, stesso fixture GPX). Manca una verifica che l'intera macchina a stati integrata (routine, pace, outage, il nuovo `speedEngine`) non vada in crash e produca numeri plausibili su una corsa reale — non una verifica di precisione (quella è già fatta), un controllo di sanità end-to-end. Riusa il fixture già presente in `app/src/test/resources/paco-run-2026-09-10-1122.gpx` (stesso usato da `SpeedEstimatorRealRunTest.kt`), con un loader indipendente e scoperto solo per questo file (stesso stile, nessuna condivisione di funzioni private tra file di test, come già fatto nel progetto).

- [ ] **Step 1: Scrivere il test di replay**

```kotlin
package dev.fred.pacorun.service

import dev.fred.pacorun.location.LocationSample
import dev.fred.pacorun.pace.PaceConfig
import dev.fred.pacorun.pace.PaceEvaluator
import dev.fred.pacorun.routine.RoutineEngine
import dev.fred.pacorun.settings.RunPreferences
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class SessionCoordinatorRealRunTest {
    @Test
    fun realRunReplayThroughFullCoordinatorNeverCrashesAndProducesPlausibleOutput() {
        val fixes = loadPacoRunGpxFixture()
        assertTrue("fixture failed to load any fixes", fixes.isNotEmpty())

        val preferences = RunPreferences()
        val published = mutableListOf<RunSessionState>()
        val pending = PendingSessionCoordinator(
            preferences = preferences,
            routineEngine = RoutineEngineAdapter(RoutineEngine(preferences)),
            paceEvaluator = PaceEvaluatorAdapter(
                PaceEvaluator(
                    PaceConfig(
                        minKmh = preferences.constantRange.minKmh,
                        maxKmh = preferences.constantRange.maxKmh,
                        confirmationSamples = preferences.confirmationSamples,
                        repeatMillis = preferences.repeatMillis,
                        gpsCuesEnabled = preferences.gpsCuesEnabled,
                    ),
                ),
            ),
            onCues = { _, _ -> },
            publish = published::add,
        )

        fixes.forEach { (sample, position) -> pending.tick(sample.elapsedRealtimeMillis, sample, position) }

        assertTrue("coordinator never activated on this real run", pending.isActivated)
        val runningStates = published.filterIsInstance<RunSessionState.Running>()
        assertTrue("no Running state was ever published", runningStates.isNotEmpty())
        runningStates.forEach { state ->
            state.speedKmh?.let { assertTrue("non-finite speedKmh in published state: $it", it.isFinite()) }
        }
        val finalDistance = runningStates.last().totalDistanceMeters
        // Loose sanity bound (not a precision check -- that's SpeedEstimatorRealRunTest.kt and
        // the spec's own Python analysis): this ~35-40 minute run plausibly covers somewhere
        // between 3 and 10 km.
        assertTrue("implausible total distance for this run: $finalDistance m", finalDistance in 3_000.0..10_000.0)
    }

    private fun loadPacoRunGpxFixture(): List<Pair<LocationSample, LocationPosition>> {
        val stream = javaClass.classLoader?.getResourceAsStream("paco-run-2026-09-10-1122.gpx")
            ?: error("fixture not found on test classpath")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val trkpts = document.getElementsByTagName("trkpt")
        val fixes = mutableListOf<Pair<LocationSample, LocationPosition>>()

        for (i in 0 until trkpts.length) {
            val trkpt = trkpts.item(i)
            val lat = trkpt.attributes.getNamedItem("lat").nodeValue.toDouble()
            val lon = trkpt.attributes.getNamedItem("lon").nodeValue.toDouble()
            var timeIso: String? = null
            var accuracy: Float? = null
            var speedMps: Float? = null
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
                                "pr:speedMps" -> speedMps = ext.textContent.toFloatOrNull()
                                "pr:reliable" -> reliable = ext.textContent == "true"
                            }
                        }
                    }
                }
            }
            val elapsedMillis = java.time.Instant.parse(timeIso).toEpochMilli()
            val sample = LocationSample(
                speedKmh = (speedMps ?: 0f) * 3.6f,
                speedAccuracyKmh = null,
                reliable = reliable,
                elapsedRealtimeMillis = elapsedMillis,
            )
            val position = LocationPosition(lat, lon, elapsedMillis, accuracy)
            fixes += sample to position
        }
        return fixes
    }
}
```

- [ ] **Step 2: Eseguire il test**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorRealRunTest"`
Expected: PASS. Se il bound di distanza fallisce per essere troppo stretto/largo rispetto al replay reale, aggiustare il bound sulla base del numero osservato (non è una cifra pre-validata al centesimo come le percentuali MAE della spec — qui serve solo un sanity check ampio), annotando nel commento il valore osservato.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorRealRunTest.kt
git commit -m "test: add full-coordinator real-run replay sanity check"
```

---

### Task 8: Verifica finale e dual-review

**Files:** nessuno — checkpoint.

- [ ] **Step 1: Suite completa da zero**

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Entrambi devono essere puliti.

- [ ] **Step 2: Self-review**

Rileggere il diff completo dei commit di questo piano (`git log -p` sugli 8 commit, o `git diff <primo-commit-di-questa-fase>^..HEAD`) e confermare:
- Nessun file toccato oltre a quelli elencati in "File Structure".
- `KalmanSpeedEngine()` è davvero il default sia in `SessionCoordinator` che in `PendingSessionCoordinator` (grep `speedEngine: SessionSpeedEngine =` nel file).
- `SpeedEstimator.kt` non ha subito nessuna modifica (`git diff` su quel file, sui commit di questo piano, deve essere vuoto).
- `RunSessionService.kt` ha una sola istanza di `SpeedEstimator` in tutto il percorso (grep `SpeedEstimator(` nel file — deve comparire solo dentro `KalmanSpeedEngine`'s default, non più direttamente in `RunSessionService.kt`).

- [ ] **Step 3: Review indipendente (subagente fresco, senza contesto pregresso)**

Questa fase tocca per la prima volta il percorso di produzione vero (cue vocali dal vivo, non solo diagnostica) — merita una seconda lettura indipendente. Chiedere al subagente di confermare specificamente:
- (a) La sostituzione di `speedKmh` in `paceSample` (Task 2) è l'unico punto in cui il valore del motore entra nel flusso — nessun altro punto nel codice legge ancora `sample.speedKmh` grezzo per pace/stato/routine dopo un fix affidabile.
- (b) Il callback `onSpeedComputed` viene invocato con lo stesso identico valore usato per costruire `paceSample`, non un valore ricalcolato separatamente.
- (c) Nessun altro file nel progetto istanzia `SpeedEstimator` direttamente aspettandosi il vecchio comportamento diagnostico-parallelo (`grep -rn "SpeedEstimator(" app/src/main app/src/test`).
- (d) I 13 test toccati nel Task 5 hanno subito SOLO l'aggiunta del parametro `speedEngine = DopplerSpeedEngine()`, nessuna asserzione modificata (`git show <commit-Task-5> -- app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt`).

- [ ] **Step 4: Riepilogo all'utente**

Riportare un riepilogo all'utente e fermarsi qui. Non procedere alla creazione del branch dedicato, al merge, o a qualunque lavoro di Fase 1/2 senza un via libera esplicito — per la stessa regola permanente che ha guidato tutta questa fase.

**Esplicitamente fuori scope per questo piano (non farle):** rimozione di `DopplerSpeedEngine`; qualunque lavoro della Fase 1 (`feature/gps-precision`) o Fase 2 (inerziale); G12; il fixed-lag smoothing G8.

---

### Task 9 (addendum post-review, 2026-09-13): correggere il reset del motore velocità su un singolo fix inaffidabile

**Contesto:** la review indipendente del Task 8 ha trovato un difetto Critical non catturato dai test del Task 4/7: `speedEngine.breakChain()` scatta su OGNI ingresso in outage, anche per un singolo fix isolato giudicato inaffidabile, non solo per interruzioni GPS genuinamente prolungate. Poiché `SpeedEstimator` riparte da 0.0 dopo un reset e impiega ~7 fix reali per riconvergere, un blip isolato produce alcuni secondi di cue vocali sbagliati (es. "troppo lento" su un runner che non ha mai cambiato ritmo). Root cause, non sintomo: il trigger di reset per `speedEngine` era stato copiato identico da `distanceEngine`, il cui costo di reset è invece trascurabile (un delta di distanza saltato è impercettibile). Analisi completa e alternative considerate/scartate: `docs/superpowers/specs/2026-09-11-precision-roadmap.md`, voce G14.

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorRealRunTest.kt`

**Vincoli non negoziabili per questo task:**
- Non toccare `SpeedEstimator.kt` (il caso `dtSeconds<=0` per fix batchati nello stesso millisecondo, segnalato dallo stesso reviewer, resta esplicitamente fuori scope — è pre-esistente, non introdotto da questa fase).
- Non toccare il trigger di reset di `distanceEngine` — resta `distanceEngine.breakChain()` incondizionato come oggi, è un'asimmetria intenzionale (costi di reset diversi tra i due motori), non un refuso.

- [ ] **Step 1: Scrivere il test di regressione a livello coordinator che riproduce il bug con il motore vero (deve fallire prima del fix)**

In `SessionCoordinatorTest.kt`, aggiungere:

```kotlin
    @Test
    fun isolatedUnreliableFixDoesNotResetKalmanSpeedEngineOrCauseSpuriousPaceCue() {
        val preferences = RunPreferences(confirmationSamples = 1)
        val cues = mutableListOf<AudioCue>()
        val coordinator = SessionCoordinator(
            preferences = preferences,
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = PaceEvaluatorAdapter(
                dev.fred.pacorun.pace.PaceEvaluator(PaceConfig(7f, 9f, 1, 5_000L, false)),
            ),
            onCues = { values, _ -> cues += values },
            publish = {},
            // Real KalmanSpeedEngine, not a fake -- this is exactly what Task 4's fakes missed.
        )
        // Metro fisso a ~0.0001 gradi di longitudine all'equatore per ogni secondo: circa
        // 11.13 m/s... troppo veloce. Per un passo realistico a 8 km/h serve uno spostamento di
        // (8/3.6) m/s = 2.222 m/s, cioè 2.222/111_320*cos(0) gradi di longitudine per secondo.
        val degreesPerSecondAt8Kmh = (8.0 / 3.6) / 111_320.0
        var longitude = 0.0
        var time = 0L

        // Alcuni fix reali a ritmo costante, per lasciare al Kalman il tempo di convergere.
        repeat(15) {
            longitude += degreesPerSecondAt8Kmh
            time += 1_000L
            coordinator.tick(time, reliableSample(8f, time), LocationPosition(0.0, longitude, time))
        }
        cues.clear()

        // Un singolo fix isolato giudicato inaffidabile -- non un'interruzione prolungata.
        // longitude avanza comunque di un secondo pieno: il runner si è mosso davvero, solo il
        // GPS non ce l'ha detto in modo affidabile per quell'istante (la sua posizione qui non
        // viene comunque mai letta da nessun motore, dato che il fix è marcato inaffidabile).
        longitude += degreesPerSecondAt8Kmh
        time += 1_000L
        coordinator.tick(time, unreliableSample(time), LocationPosition(0.0, longitude, time))

        // Il prossimo fix riprende esattamente lo stesso ritmo di prima -- il gap dall'ultimo
        // fix REALMENTE usato è di 2 secondi, e riflette correttamente 2 secondi di
        // spostamento vero a 8 km/h (non un dimezzamento fittizio della velocità media).
        longitude += degreesPerSecondAt8Kmh
        time += 1_000L
        coordinator.tick(time, reliableSample(8f, time), LocationPosition(0.0, longitude, time))

        // Prima del fix: il motore si resetta, riparte da una velocità vicina a 0, e
        // PaceEvaluator emette TOO_SLOW pur non essendoci stato alcun cambio di ritmo reale.
        assertTrue(
            "spurious pace cue after an isolated unreliable fix, no real pace change occurred: $cues",
            AudioCue.TOO_SLOW !in cues,
        )
    }
```

- [ ] **Step 2: Eseguire il test, verificare che fallisca (riproduce il bug)**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorTest.isolatedUnreliableFixDoesNotResetKalmanSpeedEngineOrCauseSpuriousPaceCue"`
Expected: FAIL, `AudioCue.TOO_SLOW` presente nei cue.

- [ ] **Step 3: Applicare la correzione in `RunSessionStore.kt` — un'unica riga**

In `SessionCoordinator.process()`, sostituire:

```kotlin
        } else if (enteredOutage) {
            latestReliableSample = null
            outageActive = true
            distanceEngine.breakChain()
            speedEngine.breakChain()
        }
```

con:

```kotlin
        } else if (enteredOutage) {
            latestReliableSample = null
            outageActive = true
            distanceEngine.breakChain()
            // Solo su un'interruzione genuinamente prolungata (stessa soglia di
            // GpsFreshnessTracker, ~3s) -- non su un singolo fix isolato inaffidabile. Un Kalman
            // filter gestisce da solo un gap breve (predict() sul dt, poi update() normale al
            // fix successivo); resettarlo comunque butta via uno stato buono e costringe a
            // ~7 fix di riconvergenza per un blip che poteva durare un solo istante. Vedi
            // roadmap G14 per l'analisi completa e il perché NON si allenta oltre questo.
            if (signalGpsOutage) speedEngine.breakChain()
        }
```

- [ ] **Step 4: Eseguire il test del Task 9, verificare che passi**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorTest.isolatedUnreliableFixDoesNotResetKalmanSpeedEngineOrCauseSpuriousPaceCue"`
Expected: PASS.

- [ ] **Step 5: Scrivere un test unitario dedicato sul trigger, con `FakeSpeedEngine`, accanto ai test del Task 4**

```kotlin
    @Test
    fun isolatedUnreliableFixDoesNotBreakSpeedEngineChain() {
        val speedEngine = FakeSpeedEngine()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = {},
            speedEngine = speedEngine,
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.tick(1_000L, unreliableSample(1_000L), LocationPosition(0.0, .0001, 1_000L))

        assertEquals(0, speedEngine.breakChainCalls)
    }
```

(Il test già esistente `speedEngineSyncsWithDistanceEngineOnReliableFixesAndOutage`, Task 4, resta valido invariato: usa `advanceTimer(5_000L, signalGpsOutage = true)`, che continua a far scattare `breakChain()` esattamente come prima.)

- [ ] **Step 6: Eseguire tutta la suite di `SessionCoordinatorTest.kt`, confermare zero regressioni**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorTest"`
Expected: PASS, tutti i test (Task 4, 5, 9) verdi.

- [ ] **Step 7: Aggiungere un limite superiore di plausibilità nel replay test del Task 7 (minor del reviewer, a costo quasi zero)**

In `SessionCoordinatorRealRunTest.kt`, nel blocco `runningStates.forEach`, sostituire il solo controllo `isFinite()` con un controllo di plausibilità più stringente:

```kotlin
        runningStates.forEach { state ->
            state.speedKmh?.let {
                assertTrue("non-finite speedKmh in published state: $it", it.isFinite())
                // Loose sanity bound (not a precision check): questa corsa reale non ha mai
                // superato ritmi di corsa ordinari -- un valore fuori da questo range
                // indicherebbe un bug di integrazione (es. una regressione del difetto
                // corretto nel Task 9), non semplice rumore GPS.
                assertTrue("implausible published speedKmh: $it", it in 0.0..20.0)
            }
        }
```

- [ ] **Step 8: Eseguire il replay test, verificare che passi**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorRealRunTest"`
Expected: PASS. Se fallisce per il limite superiore troppo stretto, alzarlo sulla base del valore osservato (stesso principio di sanity-bound del Task 7 originale), non rimuoverlo.

- [ ] **Step 9: Suite completa, lint, commit**

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
git add app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorRealRunTest.kt
git commit -m "fix: only reset speed engine on sustained GPS outage, not isolated unreliable fixes"
```

- [ ] **Step 10: Riepilogo finale e stop**

Riportare un riepilogo (commit, cosa correggeva, risultato dei test) e fermarsi. Non fare merge, non toccare `docs/superpowers/specs/2026-09-11-precision-roadmap.md` (già aggiornato con la voce G14 dalla sessione di review), non procedere ad altro senza via libera esplicito.

---

### Task 10 (addendum post-review del Task 9, 2026-09-13): sostituire il gate `signalGpsOutage` con un controllo sul tempo reale trascorso

**Contesto:** la review indipendente del Task 9 ha trovato un Important reale: `if (signalGpsOutage) speedEngine.breakChain()` non fa quello che il suo commento dichiara. `signalGpsOutage` dentro `enteredOutage` è vero solo se questa specifica chiamata è un tick del timer (`sample == null`) — non riflette "sono passati ~3 secondi". Un'interruzione GPS genuinamente lunga che si manifesta come un **flusso continuo di fix inaffidabili** (non silenzio) blocca `outageActive=true` al primo fix cattivo, e nessuna chiamata successiva rivaluta più nulla per quell'episodio — il motore velocità non si resetta mai, non importa quanto duri. Root cause: la decisione veniva presa una volta sola, nell'istante di ingresso in outage, quando non si sa ancora se l'interruzione sarà breve o lunga.

**La correzione (sostituisce per intero il gate del Task 9, non lo estende):** invece di dedurre la durata da QUALE tipo di chiamata ha innescato la transizione, `SessionCoordinator` tiene traccia direttamente di quando è avvenuto l'ultimo fix davvero affidabile, e verifica il tempo reale trascorso ad ogni chiamata (non solo al momento della transizione). Riusa la stessa costante già usata altrove nel progetto per lo stesso concetto (`LocationSampleMapper.DEFAULT_MAX_AGE_MILLIS`, ~3s) — nessuna soglia nuova o arbitraria. Chiamare `speedEngine.breakChain()` più volte durante lo stesso episodio di interruzione è innocuo (l'operazione è idempotente — vedi `SpeedEstimator.breakChain()`), quindi non serve nessun flag "già resettato per questo episodio": lo stato aggiuntivo necessario è un solo campo.

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt`

**Vincoli non negoziabili per questo task:**
- Non toccare `SpeedEstimator.kt`, `distanceEngine`, o il meccanismo di `GpsFreshnessTracker`/`signalGpsOutage` usato altrove (routine, pace, notifica di stato) — questa correzione riguarda SOLO la decisione interna di reset di `speedEngine` dentro `SessionCoordinator`.
- Non aggiungere un flag "già resettato" — è stato deliberatamente scartato (vedi sopra), non reintrodurlo per "sicurezza".

- [ ] **Step 1: Scrivere il test che riproduce lo scenario trovato dal reviewer (flusso continuo di fix inaffidabili, non silenzio) — deve fallire con l'implementazione del Task 9**

In `SessionCoordinatorTest.kt`, accanto al test `isolatedUnreliableFixDoesNotBreakSpeedEngineChain` del Task 9:

```kotlin
    @Test
    fun sustainedStreamOfUnreliableFixesEventuallyBreaksSpeedEngineChain() {
        val speedEngine = FakeSpeedEngine()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = {},
            speedEngine = speedEngine,
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))
        // Flusso continuo di fix INAFFIDABILI (non silenzio) -- un tick al secondo. Prima dei
        // 3 secondi (DEFAULT_MAX_AGE_MILLIS) dall'ultimo fix affidabile, nessun reset.
        coordinator.tick(1_000L, unreliableSample(1_000L), LocationPosition(0.0, .0001, 1_000L))
        assertEquals(0, speedEngine.breakChainCalls)
        coordinator.tick(2_000L, unreliableSample(2_000L), LocationPosition(0.0, .0002, 2_000L))
        assertEquals(0, speedEngine.breakChainCalls)

        // A t=3000, sono passati 3 secondi REALI dall'ultimo fix affidabile (t=0) -- deve
        // resettarsi anche se il GPS continua a rispondere (male), non è mai stato silenzio.
        coordinator.tick(3_000L, unreliableSample(3_000L), LocationPosition(0.0, .0003, 3_000L))
        assertEquals(1, speedEngine.breakChainCalls)

        // Chiamate successive nello stesso episodio possono richiamare breakChain() di nuovo --
        // è innocuo (idempotente), non un difetto da evitare con un flag dedicato.
        coordinator.tick(4_000L, unreliableSample(4_000L), LocationPosition(0.0, .0004, 4_000L))
        assertEquals(2, speedEngine.breakChainCalls)
    }
```

- [ ] **Step 2: Eseguire il test, verificare che fallisca**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorTest.sustainedStreamOfUnreliableFixesEventuallyBreaksSpeedEngineChain"`
Expected: FAIL a `assertEquals(1, speedEngine.breakChainCalls)` (rimane 0 con l'implementazione del Task 9, perché `signalGpsOutage` è sempre `false` per questi `tick()`).

- [ ] **Step 3: Sostituire il gate del Task 9 in `RunSessionStore.kt`**

Aggiungere il nuovo campo accanto agli altri campi privati di `SessionCoordinator` (vicino a `latestReliableSample`, `outageActive`):

```kotlin
    private var lastReliableFixElapsedMillis: Long = startedAtElapsedMillis
```

Sostituire il blocco introdotto dal Task 9:

```kotlin
        } else if (enteredOutage) {
            latestReliableSample = null
            outageActive = true
            distanceEngine.breakChain()
            // Solo su un'interruzione genuinamente prolungata (stessa soglia di
            // GpsFreshnessTracker, ~3s) -- non su un singolo fix isolato inaffidabile. Un Kalman
            // filter gestisce da solo un gap breve (predict() sul dt, poi update() normale al
            // fix successivo); resettarlo comunque butta via uno stato buono e costringe a
            // ~7 fix di riconvergenza per un blip che poteva durare un solo istante. Vedi
            // roadmap G14 per l'analisi completa e il perché NON si allenta oltre questo.
            if (signalGpsOutage) speedEngine.breakChain()
        }
```

con:

```kotlin
        } else if (enteredOutage) {
            latestReliableSample = null
            outageActive = true
            distanceEngine.breakChain()
        }
        if (actualReliable) {
            lastReliableFixElapsedMillis = elapsedRealtimeMillis
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
            speedEngine.breakChain()
        }
```

(Nota: la scrittura di `lastReliableFixElapsedMillis` è stata spostata fuori dal blocco `if (actualReliable) { latestReliableSample = paceSample; outageActive = false; ... }` in un `if` separato subito dopo, per chiarezza — il resto di quel blocco resta invariato.)

- [ ] **Step 4: Eseguire il test del Task 10, verificare che passi**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorTest.sustainedStreamOfUnreliableFixesEventuallyBreaksSpeedEngineChain"`
Expected: PASS.

- [ ] **Step 5: Eseguire tutta la suite di `SessionCoordinatorTest.kt`, confermare che i test del Task 4 e del Task 9 restino verdi SENZA modifiche**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SessionCoordinatorTest"`
Expected: PASS. In particolare: `isolatedUnreliableFixDoesNotBreakSpeedEngineChain` (Task 9) resta verde perché 1 secondo di gap resta sotto soglia; `speedEngineSyncsWithDistanceEngineOnReliableFixesAndOutage` (Task 4) resta verde perché il suo `advanceTimer(5_000L, ...)` corrisponde comunque a 4 secondi reali trascorsi dall'ultimo fix affidabile (sopra soglia), indipendentemente da `signalGpsOutage`; `isolatedUnreliableFixDoesNotResetKalmanSpeedEngineOrCauseSpuriousPaceCue` (Task 9, motore reale) resta verde per lo stesso motivo del primo. Se uno di questi tre risultasse rotto, il Task 10 ha introdotto una regressione reale — fermarsi e correggere, non modificare le loro asserzioni.

- [ ] **Step 6: Suite completa, lint, commit**

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
git add app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt
git commit -m "fix: track real elapsed time since last reliable fix for speed engine reset, not call type"
```

- [ ] **Step 7: Riepilogo finale e stop**

Riportare un riepilogo (commit, cosa correggeva rispetto al Task 9, risultato dei test) e fermarsi. Non fare merge, non toccare la roadmap, non procedere ad altro senza via libera esplicito.
