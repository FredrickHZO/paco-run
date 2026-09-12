# MotionEngine Refactor (Fase 0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Estrarre un'interfaccia `SessionDistanceEngine` dal comportamento oggi hardcoded su `DistanceAccumulator`, seguendo esattamente il pattern interfaccia+adattatore già usato due volte nello stesso file (`SessionRoutineEngine`/`RoutineEngineAdapter`, `SessionPaceEvaluator`/`PaceEvaluatorAdapter` in `RunSessionStore.kt`) — così `SessionCoordinator` dipende da un'astrazione, non da una classe concreta, aprendo il punto d'aggancio per un futuro motore di distanza alternativo (Fase 4, RTK/INS) senza modificare `SessionCoordinator` di nuovo quando quel giorno arriverà. Più due fix minori trovati in una review architetturale dedicata del 2026-09-11.

**Architettura:** Nessun cambio di comportamento — è un refactor puro (stesso identico output per gli stessi input, verificato dalla suite di test esistente che deve restare verde senza modifiche). L'interfaccia `SessionDistanceEngine` replica esattamente la firma pubblica attuale di `DistanceAccumulator` (`accept(position, reliable): Double`, `breakChain(): Double`) — non aggiunge metodi, non anticipa capacità che non servono ancora (niente astrazione per la velocità: quella resta un refactor separato, futuro, quando `SpeedEstimator`/fusione inerziale sarà pronta per la produzione — fuori scope qui, per non costruire generalità non ancora necessaria).

**Tech Stack:** Kotlin, JUnit4, Gradle (`./gradlew testDebugUnitTest`, `lintDebug`).

**Spec:** Nessuna spec dedicata — refactor "bounded" che estende un pattern già documentato e approvato nel codice esistente. Contesto completo in `docs/superpowers/specs/2026-09-11-precision-roadmap.md` (sezione "Stato delle decisioni", voce "Refactor MotionEngine") e nella review architetturale che ha originato questo piano (vedi commit history/conversazione del 2026-09-11).

## Global Constraints

- **Zero cambi di comportamento.** Ogni test esistente deve passare SENZA modifiche al proprio contenuto (al massimo un rename del parametro nominato nel costruttore, se qualche test lo usa esplicitamente — verificato che nessun test in `SessionCoordinatorTest.kt` lo fa).
- **Non toccare `SpeedEstimator`, l'export GPX, o qualunque path di produzione relativo alla velocità.** Questo piano riguarda SOLO distanza + le due cosette minori.
- **Non introdurre un'interfaccia per la velocità.** Fuori scope, deliberatamente — vedi sezione Architettura sopra.
- **Nome dell'interfaccia: `SessionDistanceEngine`**, non negoziabile — deve seguire esattamente la convenzione di naming già in uso (`SessionRoutineEngine`, `SessionPaceEvaluator`).

---

## File Structure

| File | Ruolo | Task |
|---|---|---|
| `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt` | Modifica: aggiunge `interface SessionDistanceEngine`, `DistanceAccumulator` la implementa, `SessionCoordinator` dipende dall'interfaccia | 1 |
| `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt` | Nessuna modifica prevista (verificare in Step 4 del Task 1) | 1 |
| `app/src/main/java/dev/fred/pacorun/settings/RunPreferencesStore.kt` | Modifica: rimuove la doppia scrittura delle chiavi legacy `minKmh`/`maxKmh` | 2 |
| `app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt` | Modifica: chiarisce `kmToMeters` con un commento | 3 |

---

### Task 1: Estrarre `SessionDistanceEngine`

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`

**Interfaces:**
- Produces: `interface SessionDistanceEngine { fun accept(position: LocationPosition, reliable: Boolean): Double; fun breakChain(): Double }`. `DistanceAccumulator` la implementa senza modifiche al proprio corpo. `SessionCoordinator`'s constructor: `private val distanceAccumulator: DistanceAccumulator = DistanceAccumulator()` diventa `private val distanceEngine: SessionDistanceEngine = DistanceAccumulator()`, e le due chiamate interne (`distanceAccumulator.accept(...)`, `distanceAccumulator.breakChain()`) diventano `distanceEngine.accept(...)`/`distanceEngine.breakChain()`.

Questo task non segue lo schema test-prima-del-codice standard (non c'è un nuovo comportamento da testare) — è un refactor a comportamento invariato, quindi la "prova" è che la suite esistente resti verde senza modifiche. Passi:

- [ ] **Step 1: Aggiungere l'interfaccia, subito sopra la definizione di `DistanceAccumulator` in `RunSessionStore.kt`**

```kotlin
interface SessionDistanceEngine {
    fun accept(position: LocationPosition, reliable: Boolean): Double
    fun breakChain(): Double
}
```

- [ ] **Step 2: Far implementare l'interfaccia a `DistanceAccumulator`**

Cambiare solo la riga della dichiarazione di classe:

```kotlin
class DistanceAccumulator(
    private val maxSpeedMetersPerSecond: Double = 15.0,
    private val minMovementMeters: Double = 2.0,
    private val jitterAccuracyFraction: Double = 0.5,
) : SessionDistanceEngine {
```

(Il resto della classe — campi, `accept()`, `breakChain()`, `jitterThreshold()`, `haversineMeters()` — resta identico, `accept`/`breakChain` ora hanno semplicemente `override` implicito soddisfatto dalla firma già esistente; aggiungere la keyword `override` esplicita su entrambi i metodi per chiarezza del compilatore.)

- [ ] **Step 3: Aggiornare il costruttore e i due call site interni di `SessionCoordinator`**

```kotlin
class SessionCoordinator(
    private val preferences: RunPreferences,
    private val routineEngine: SessionRoutineEngine,
    private val paceEvaluator: SessionPaceEvaluator,
    private val onCues: (List<AudioCue>, Float) -> Unit,
    private val publish: (RunSessionState) -> Unit,
    private val startedAtElapsedMillis: Long = 0L,
    private val distanceEngine: SessionDistanceEngine = DistanceAccumulator(),
) {
```

E dentro `process()`, sostituire le due occorrenze di `distanceAccumulator` con `distanceEngine` (la chiamata `distanceAccumulator.accept(requireNotNull(position), reliable = true)` e `distanceAccumulator.breakChain()`).

- [ ] **Step 4: Eseguire l'intera suite di test, confermare zero regressioni**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.*"`
Expected: PASS, tutti i test esistenti (incluso `SessionCoordinatorTest.kt`, `DistanceAccumulatorTest.kt`) senza nessuna modifica al loro contenuto. Se qualcosa fallisce, il refactor ha introdotto una regressione comportamentale — fermarsi e correggere prima di proseguire, non modificare i test per farli passare.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt
git commit -m "refactor: extract SessionDistanceEngine interface for DistanceAccumulator"
```

---

### Task 2: Rimuovere la doppia scrittura di preferenze legacy

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/settings/RunPreferencesStore.kt`

**Contesto:** `decode()` legge già `constantMinKmh`/`constantMaxKmh` con fallback su `minKmh`/`maxKmh` (le chiavi vecchie) se le nuove non esistono — questo fallback in lettura resta, serve a non perdere dati per chi ha già l'app installata con le chiavi vecchie. Il problema è SOLO in `write()`, che scrive su ENTRAMBE le coppie di chiavi ad ogni salvataggio, per sempre — non necessario, dato che dopo la primissima scrittura post-refactor le chiavi nuove esistono già e il fallback in lettura non serve più. **Verificato (2026-09-11): nessun test in tutto il progetto referenzia `minKmh`/`maxKmh` o `RunPreferencesStore` — nessuna suite di test esiste per questa classe, sicuro da modificare senza rischio di rompere test esistenti.**

- [ ] **Step 1: Rimuovere le due righe di scrittura legacy in `write()`**

Cancellare queste due righe (subito dopo `values[Keys.routineKm] = preferences.routineKm`):

```kotlin
        values[Keys.minKmh] = preferences.constantRange.minKmh
        values[Keys.maxKmh] = preferences.constantRange.maxKmh
```

**Non toccare `decode()`** — il fallback in lettura (`values[Keys.constantMinKmh] ?: values[Keys.minKmh] ?: defaults...`) resta, protegge chi ha dati vecchi salvati prima di questo commit.

- [ ] **Step 2: Compilare e lanciare la suite completa (non c'è un test dedicato da eseguire, verifica che nulla si rompa nel resto del progetto)**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: PASS, nessuna regressione (nessun test tocca questo file, quindi il segnale vero è "compila e il resto della suite resta verde").

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/settings/RunPreferencesStore.kt
git commit -m "cleanup: stop writing legacy minKmh/maxKmh preference keys"
```

---

### Task 3: Chiarire il trick di conversione in `RoutineEngine.kt`

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt`

**Contesto:** riga 177, `private fun kmToMeters(km: Float) = km.toString().toDouble() * 1_000.0` — la conversione via stringa è probabilmente intenzionale (evita l'imprecisione di un cast diretto `Float -> Double`, che può introdurre rumore nelle cifre decimali basse per via di come i float sono rappresentati in binario), ma senza un commento è facile scambiarlo per un errore o toglierlo per "semplificarlo" in futuro.

- [ ] **Step 1: Aggiungere un commento che spiega il motivo, non riscrivere la funzione**

```kotlin
    // Via String, non un cast diretto: un Float->Double diretto (es. 7.5f.toDouble()) porta
    // dietro l'imprecisione binaria del Float originale (es. 7.4999998...), che qui
    // si propaga nei metri e può disallineare i confronti di soglia. Il giro per String
    // riparte dalla rappresentazione decimale "pulita" che il Float mostra quando stampato.
    private fun kmToMeters(km: Float) = km.toString().toDouble() * 1_000.0
```

- [ ] **Step 2: Eseguire la suite esistente per `RoutineEngine`, confermare zero regressioni (è solo un commento, nessun cambio di comportamento atteso)**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.routine.*"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt
git commit -m "docs: explain the string-roundtrip Float->Double conversion in kmToMeters"
```

---

### Task 4: Verifica finale e dual-review

**Files:** nessuno — checkpoint.

- [ ] **Step 1: Suite completa da zero**

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Entrambi devono essere puliti.

- [ ] **Step 2: Self-review**

Rileggere il diff completo (`git diff feature/paco-run-m3e...HEAD` oppure `git log -p` sui 3 commit) e confermare: nessun file toccato oltre ai 3 elencati in "File Structure"; `SessionCoordinatorTest.kt` e `DistanceAccumulatorTest.kt` risultano invariati (`git diff` su quei due file deve essere vuoto).

- [ ] **Step 3: Review indipendente (subagente fresco, senza contesto pregresso)**

Anche se questo è un refactor a comportamento invariato, tocca il file centrale (`RunSessionStore.kt`) che orchestra tutto il percorso di produzione — merita comunque una seconda lettura indipendente prima di considerarlo chiuso. Chiedere al subagente di confermare specificamente: (a) `SessionDistanceEngine` replica esattamente la firma pubblica precedente di `DistanceAccumulator`, nessun comportamento diverso nascosto; (b) nessun altro file nel progetto istanzia `DistanceAccumulator` direttamente aspettandosi il vecchio tipo concreto in un modo che questo refactor romperebbe (`grep -rn "DistanceAccumulator" app/src/main app/src/test`).

- [ ] **Step 4: Aggiornare lo stato nella roadmap**

In `docs/superpowers/specs/2026-09-11-precision-roadmap.md`, nella tabella "Stato delle decisioni", cambiare la riga del refactor da "Confermato, in corso" a "Completato" con la data.

- [ ] **Step 5: Commit finale**

```bash
git add docs/superpowers/specs/2026-09-11-precision-roadmap.md
git commit -m "docs: mark motion-engine refactor (Fase 0) complete"
```

**Esplicitamente fuori scope per questo piano (non farle):** qualunque lavoro della Fase 1 (zona GPS, Huber/RAIM/gate, ecc.) — quello è un piano a parte, sul branch `feature/gps-precision`, non ancora scritto. Qualunque cosa relativa a `SpeedEstimator`, accelerometro, o velocità. Riportare un riepilogo all'utente e fermarsi qui.
