# Estrazione di `:core` e `:android-connector` — piano implementativo

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Estrarre il motore di Paco Run (oggi tutto in `:app`) in due nuovi moduli Gradle condivisi — `:core` (Kotlin puro) e `:android-connector` (libreria Android) — così che `:wear` possa dipendere da codice condiviso invece di copiarlo con `cp`. Nessuna feature nuova, nessun cambiamento di comportamento osservabile: solo riorganizzazione di codice esistente, con un pezzo di riscrittura interna mirata (`RunSessionService` → `RunSessionEventSequencer` + collante Android).

**Architecture:** Approccio A (deciso, non negoziabile — vedi spec): la logica di orchestrazione pura di `RunSessionService` viene interamente riscritta come `RunSessionEventSequencer`, una classe pura in `:core`. Tutto il resto del motore (stima velocità, pace, routine, distanza, eventi, GPX) si sposta com'è. Il collante Android (permessi, notifica, GPS, audio, file) resta/si sposta in `:android-connector`, condiviso fra `:app` e `:wear`.

**Tech Stack:** Kotlin 2.3.20, AGP 9.1.1, Gradle 9.3.1, JUnit 4.13.2, kotlinx-coroutines-test 1.10.2. Nessuna nuova libreria di test introdotta eccetto `androidx.test:rules` (per `ServiceTestRule`, Task 9 — vedi quel task per la motivazione).

**Spec:** `docs/superpowers/specs/2026-09-20-core-extraction-design.md` — questo piano segue l'ordine di migrazione a 7 passi lì definito (passi 1-6 qui; il passo 7, switch-over di `:wear`, è un piano futuro separato su `feature/wear-os`, esplicitamente fuori scope qui). Ogni task cita la sezione/riga della spec da cui deriva; in caso di dubbio, la spec vince.

## ⚠️ Problema critico noto, trovato durante l'implementazione — da discutere e risolvere SEPARATAMENTE (owner, 2026-09-28)

**Non è un difetto di questa estrazione — preesiste nel codice reale di `RunSessionService.kt` da prima di qualunque lavoro di questo piano.** Trovato dalla revisione avversariale sul Task 8, verificato contro il codice reale, giudicato dall'owner "un problema gigantesco" da risolvere ma con una discussione dedicata, non una patch infilata qui.

**Cosa succede oggi (e identicamente in `RunSessionEventSequencer` dopo l'estrazione, comportamento preservato apposta):** `SessionCoordinator.process()` (`RunSessionStore.kt`) chiama in sequenza più motori interni (routine engine, pace evaluator, speed engine, distance engine), ciascuno dei quali può emettere eventi tramite la stessa callback `onEvent` condivisa e può mutare il proprio stato interno permanentemente (es. `RoutineEngine` cambia fase). **Se un motore lancia un'eccezione non gestita a metà di questa sequenza, l'eccezione si propaga fuori da `onLocation()`/`tickTimer()` senza essere mai catturata da nessuna parte** — su Android, un'eccezione non gestita sul thread principale è sempre fatale: **l'intera app crasha immediatamente**, non "perde silenziosamente qualche riga di log". I motori precedenti nella sequenza hanno già mutato il proprio stato in modo permanente (es. la fase della routine È cambiata) ma l'evento che lo racconta (`RoutinePhaseChangedEvent`) non arriva mai al log, perché il buffer che lo conteneva viene distrutto insieme al processo che crasha.

**Perché non è "dati storici forse incompleti in silenzio":** un crash è rumoroso e visibile — se non si sono mai osservati crash improvvisi durante una sessione di corsa, questa specifica classe di bug non si è mai attivata. Non è verificabile con certezza assoluta senza log di crash reali (Play Console / logcat), ma la premessa "i log potrebbero essere silenziosamente incompleti" non è quella corretta: o l'app è crashata (e lo si saprebbe), o il bug non è mai scattato.

**Cosa serve, e perché è fuori scope qui:** l'owner vuole che l'app **non crashi più** quando un motore interno fallisce — la sessione deve sopravvivere, riportando un evento diagnostico invece del crash secco. Questo è un cambio di comportamento osservabile reale (oggi: crash; dopo: nessun crash, sessione viva), non una preservazione — viola esplicitamente il vincolo "Nessuna modifica di comportamento osservabile" di questo piano per costruzione, quindi non può essere un task aggiunto qui. Serve una vera discussione (`superpowers:brainstorming`, hard rule 2 di `CLAUDE.md`) su almeno: cosa succede alla sessione/UI dopo che un motore ha fallito (si continua con lo stato residuo? si forza un riavvio pulito?), quale motore ha davvero bisogno di essere protetto (tutti e quattro, o solo quelli con `error()`/`require()` raggiungibili — es. `RoutineEngine.phaseCue()` ha un `error(...)` esplicito), e come riportare l'evento diagnostico senza che `:core` acquisisca un logger (violerebbe il design "zero dipendenze Android/logging" di tutta questa estrazione).

**Prossimo passo:** aprire una sessione di brainstorming dedicata su questo, dopo che l'estrazione corrente è chiusa e mergiata — non prima, per non mischiare un cambio di comportamento con un'estrazione che deve restare comportamentalmente neutra.

**Tracciato come P7** nella tabella "Stato delle decisioni" di `docs/superpowers/specs/2026-09-11-precision-roadmap.md` — quello è il posto che sopravvive alla chiusura di questo piano (letto da ogni sessione futura), non questo file.

## Global Constraints

- **Nessun compromesso** sull'estrazione di `RunSessionEventSequencer` (spec, "Contesto e motivazione") — l'Approccio A è definitivo, non negoziabile a metà implementazione.
- **Muovere, non copiare** i file (`git mv`), e mantenere lo stesso nome di package su ogni file spostato — nessun file va rinominato o ripackettizzato in questa estrazione (spec, "Ordine di migrazione").
- **Ogni file di test che referenzia codice spostato in un dato task si sposta nello stesso task** — nessuna eccezione (spec, "Principio generale su spostamento dei test").
- **La suite di test di `:app` (e dei moduli già creati) deve restare verde dopo ogni task** — eseguire `./gradlew :app:testDebugUnitTest` (`:android-connector:testDebugUnitTest` una volta che esiste — stesso task name, essendo anche lui un modulo Android; **`:core:test`**, non `testDebugUnitTest` — `:core` è un modulo `kotlin("jvm")` puro, senza varianti di build Android, quindi il suo task di test JUnit standard si chiama semplicemente `test`) prima di passare al task successivo.
- **Nessuna modifica di comportamento osservabile.** Ogni edit che non sia un puro `git mv` deve essere giustificato riga per riga contro il codice reale citato — non riscrivere "a senso" nulla che la spec non richieda esplicitamente.
- **Nessuna feature watch-specifica, nessuna rimozione di `DopplerSpeedEngine`, nessuna riscrittura del meccanismo di emissione eventi callback→return-value** — tutti esplicitamente fuori scope (spec, "Scope esplicitamente escluso").
- Branch di lavoro: `feature/core-extraction` (worktree `.worktrees/core-extraction`), forcato da `main`. Questo piano copre i passi 1-6; il passo 7 (`:wear`) non è eseguibile su questo branch (`main` non ha il modulo `:wear`).

---

## Task 1: Scaffolding Gradle — moduli `:core` e `:android-connector` vuoti

Spec: "Meccanica Gradle", "Ordine di migrazione" passo 1.

**Files:**
- Create: `core/build.gradle.kts`
- Create: `android-connector/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts` (root)

**Interfaces:**
- Produces: modulo Gradle `:core` (plugin `kotlin("jvm")`, nessuna dipendenza Android), modulo Gradle `:android-connector` (plugin `com.android.library`, namespace `dev.fred.pacorun.connector`, dipende da `:core`). Nessun file di produzione spostato in questo task — solo scheletro.

- [ ] **Step 1: Aggiungere il plugin `android-library` e `kotlin-jvm` al catalogo versioni**

`gradle/libs.versions.toml` ha oggi solo `android-application` e `compose-compiler` in `[plugins]`. Aggiungere, nella stessa sezione:

```toml
[plugins]
android-application = { id = "com.android.application", version.ref = "androidGradlePlugin" }
android-library = { id = "com.android.library", version.ref = "androidGradlePlugin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

`androidGradlePlugin` e `kotlin` sono già definiti in `[versions]` — nessun nuovo numero di versione da inventare, solo due nuovi alias sugli stessi valori già pinnati.

- [ ] **Step 2: Dichiarare i due nuovi plugin anche nel `build.gradle.kts` di root, con `apply false`**

**Verificato empiricamente** (non per intuito): senza questo step, `:core:build`/`:android-connector:build` falliscono ENTRAMBI in fase di risoluzione plugin con lo stesso errore Gradle — `Error resolving plugin [id: '...', version: '...'] > The request for this plugin could not be satisfied because the plugin is already on the classpath with an unknown version, so compatibility cannot be checked.` — su `org.jetbrains.kotlin.jvm` per `:core` e su `com.android.library` per `:android-connector`. La causa: il `build.gradle.kts` di root dichiara oggi solo `com.android.application`/`compose-compiler` con `apply false` (che carica le loro classi sul classpath con una versione tracciata); qualunque plugin richiesto DOPO da un sottoprogetto, se le sue classi finiscono comunque sul classpath per via transitiva (qui: Kotlin Gradle Plugin, tirato dentro da `compose-compiler`, e AGP condiviso fra `com.android.application`/`com.android.library`) ma senza essere stato anche lui dichiarato con `apply false` a livello di root, Gradle non riesce a verificarne la compatibilità di versione e fallisce. La soluzione verificata (build reale eseguita, `BUILD SUCCESSFUL` confermato) è dichiarare `apply false` per ENTRAMBI i nuovi plugin a livello di root, esattamente come già fatto per gli altri due:

```kotlin
// PRIMA:
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
}

// DOPO:
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.compose.compiler) apply false
}
```

- [ ] **Step 3: Creare `core/build.gradle.kts`**

`:core` è Kotlin/JVM puro — verificare che NON compaia alcuna dipendenza Android nel classpath.

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
```

- [ ] **Step 4: Creare `android-connector/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.fred.pacorun.connector"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
}
```

- [ ] **Step 5: Aggiungere i due moduli a `settings.gradle.kts` e verificare**

```kotlin
include(":app")
include(":core")
include(":android-connector")
```

**Nota — se `local.properties` manca in questo worktree** (file gitignored, machine-specific, non copiato automaticamente quando si crea un nuovo `git worktree`): la build fallisce con `SDK location not found`. Copiare `local.properties` dalla root del repo principale (`/home/fredworkstation/Documents/Development/paco-run/local.properties`) in questo worktree prima di eseguire la verifica sotto — non è un problema del piano, è un setup una tantum per qualunque lavoro in questo worktree.

Eseguire:
```bash
./gradlew :core:build :android-connector:build :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL su tutti e tre (i due nuovi moduli sono vuoti, `:app` è invariato). Verificato empiricamente eseguendo la build reale in questo ambiente — non solo per lettura del codice.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts settings.gradle.kts gradle/libs.versions.toml core/build.gradle.kts android-connector/build.gradle.kts
git commit -m "build: scaffold empty :core and :android-connector Gradle modules"
```

---

## Task 2: Modelli puri → `:core`

Spec: "Allocazione dei file" (righe `LocationSample.kt`/`SessionEvent.kt`/`PaceModels.kt`/`RoutineModels.kt`/`RunPreferences.kt`), "Ordine di migrazione" passo 2.

**Files:**
- Move: `app/src/main/java/dev/fred/pacorun/location/LocationSample.kt` → `core/src/main/java/dev/fred/pacorun/location/LocationSample.kt`
- Move: `app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt` → `core/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt`
- Move: `app/src/main/java/dev/fred/pacorun/pace/PaceModels.kt` → `core/src/main/java/dev/fred/pacorun/pace/PaceModels.kt`
- Move: `app/src/main/java/dev/fred/pacorun/routine/RoutineModels.kt` → `core/src/main/java/dev/fred/pacorun/routine/RoutineModels.kt`
- Move: `app/src/main/java/dev/fred/pacorun/settings/RunPreferences.kt` → `core/src/main/java/dev/fred/pacorun/settings/RunPreferences.kt`
- Move: `app/src/test/java/dev/fred/pacorun/settings/RunPreferencesTest.kt` → `core/src/test/java/dev/fred/pacorun/settings/RunPreferencesTest.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt` (resta in `:app` per ora — si sposta al Task 6)
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: `dev.fred.pacorun.location.DEFAULT_MAX_AGE_MILLIS` (top-level `Long`, sostituisce `LocationSampleMapper.DEFAULT_MAX_AGE_MILLIS`), tutti i tipi già esistenti (`LocationSample`, `SessionEvent` e sealed subtypes, `PaceState`/`Cue`/`PaceConfig`/`PaceResult`, `RoutinePhase`/`RoutineCue`/`RoutineInput`/`RoutineResult`, `RoutineMode`/`SpeedRange`/`RunPreferences`), ora in `:core`.

- [ ] **Step 1: Muovere i 5 file**

```bash
mkdir -p core/src/main/java/dev/fred/pacorun/{location,eventlog,pace,routine,settings}
mkdir -p core/src/test/java/dev/fred/pacorun/settings
git mv app/src/main/java/dev/fred/pacorun/location/LocationSample.kt core/src/main/java/dev/fred/pacorun/location/LocationSample.kt
git mv app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt core/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt
git mv app/src/main/java/dev/fred/pacorun/pace/PaceModels.kt core/src/main/java/dev/fred/pacorun/pace/PaceModels.kt
git mv app/src/main/java/dev/fred/pacorun/routine/RoutineModels.kt core/src/main/java/dev/fred/pacorun/routine/RoutineModels.kt
git mv app/src/main/java/dev/fred/pacorun/settings/RunPreferences.kt core/src/main/java/dev/fred/pacorun/settings/RunPreferences.kt
git mv app/src/test/java/dev/fred/pacorun/settings/RunPreferencesTest.kt core/src/test/java/dev/fred/pacorun/settings/RunPreferencesTest.kt
```

Nessun cambiamento di package: ogni file resta `package dev.fred.pacorun.location` / `.eventlog` / `.pace` / `.routine` / `.settings` esattamente come oggi.

- [ ] **Step 2: Spostare `DEFAULT_MAX_AGE_MILLIS` in `LocationSample.kt`**

In `core/.../location/LocationSample.kt`, aggiungere (sopra la data class, stesso file):

```kotlin
package dev.fred.pacorun.location

const val DEFAULT_MAX_AGE_MILLIS = 3_000L

data class LocationSample(
    val speedKmh: Float,
    val speedAccuracyKmh: Float?,
    val reliable: Boolean,
    val elapsedRealtimeMillis: Long,
)
```

In `app/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt`, rimuovere il companion object che oggi definisce la costante (righe finali del file):

```kotlin
// PRIMA:
companion object {
    const val DEFAULT_MAX_AGE_MILLIS = 3_000L
    private val MISSING_DATA_REASONS = setOf(...)
}

// DOPO:
companion object {
    private val MISSING_DATA_REASONS = setOf(...)
}
```

Il costruttore di `LocationSampleMapper` referenzia già la costante per nome semplice (`private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS`) — una volta che `:app` dipende da `:core` (Step 3) e lo stesso package esiste su entrambi i moduli, questa risoluzione funziona senza modificare quella riga né aggiungere un import (stesso package, top-level, risolto attraverso il confine di modulo, perché `LocationSampleMapper.kt` è nello stesso package `dev.fred.pacorun.location` della costante).

**`RunSessionStore.kt` (ancora in `:app`, si sposta solo al Task 4) ha DUE riferimenti qualificati a `LocationSampleMapper.DEFAULT_MAX_AGE_MILLIS`** che vanno corretti QUI, non al Task 4 — altrimenti la verifica di build di questo stesso task (Step 4, sotto) fallisce, perché il companion appena rimosso lascia questi due punti irrisolvibili:

```kotlin
// PRIMA (default param di GpsFreshnessTracker):
private val maxAgeMillis: Long = LocationSampleMapper.DEFAULT_MAX_AGE_MILLIS,

// DOPO:
private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
```

```kotlin
// PRIMA (dentro SessionCoordinator.process()):
elapsedRealtimeMillis - lastReliableFixElapsedMillis >= LocationSampleMapper.DEFAULT_MAX_AGE_MILLIS

// DOPO:
elapsedRealtimeMillis - lastReliableFixElapsedMillis >= DEFAULT_MAX_AGE_MILLIS
```

A differenza di `LocationSampleMapper.kt`, **`RunSessionStore.kt` è nel package `dev.fred.pacorun.service`, diverso da `dev.fred.pacorun.location`** dove vive la costante — qui l'accesso non risolve automaticamente per package condiviso, serve un import esplicito:

```kotlin
import dev.fred.pacorun.location.DEFAULT_MAX_AGE_MILLIS
```

Rimuovere anche `import dev.fred.pacorun.location.LocationSampleMapper`: verificato (grep), le due righe appena corrette erano i suoi UNICI due usi in questo file — non resta nessun altro riferimento a `LocationSampleMapper` da questo punto in poi, quindi l'import diventerebbe subito morto se lasciato.

- [ ] **Step 3: Aggiungere la dipendenza `:app` → `:core`**

In `app/build.gradle.kts`, dentro il blocco `dependencies { ... }` esistente, aggiungere come prima riga:

```kotlin
dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    // ... resto invariato
```

- [ ] **Step 4: Verificare**

```bash
./gradlew :core:test :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL. `RunPreferencesTest.kt` ora gira come test JVM puro dentro `:core` (task `test`, non `testDebugUnitTest` — `:core` non ha build type Android, quindi nessun suffisso di variante sul nome del task).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: move pure data models to :core"
```

---

## Task 3: `CueSignal` — nuovo tipo puro in `:core` (additivo, zero rischio)

Spec: "Ordine di migrazione" passo 3 (introduzione di `CueSignal`), righe 744-784.

Questo task NON sposta ancora `RunSessionStore.kt` — crea solo il nuovo tipo e il ponte verso `AudioCue`, entrambi compilabili subito con lo stato attuale del codice, così il Task 4 (il vero spostamento degli engine) può usarlo senza dover introdurre due cose contemporaneamente.

**Files:**
- Create: `core/src/main/java/dev/fred/pacorun/service/CueSignal.kt`
- Create: `core/src/test/java/dev/fred/pacorun/service/CueSignalTest.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/audio/CuePlayer.kt`

**Interfaces:**
- Produces: `sealed interface CueSignal { val name: String; data class Pace(val cue: Cue); data class Routine(val cue: RoutineCue) }` in `:core`; `fun CueSignal.toAudioCue(): AudioCue` in `:app`'s `CuePlayer.kt` (si sposterà con lui al Task 6).
- Consumes: `dev.fred.pacorun.pace.Cue`, `dev.fred.pacorun.routine.RoutineCue` (già in `:core` dal Task 2).

- [ ] **Step 1: Scrivere il test per `CueSignal.name`**

```kotlin
package dev.fred.pacorun.service

import dev.fred.pacorun.pace.Cue
import dev.fred.pacorun.routine.RoutineCue
import org.junit.Assert.assertEquals
import org.junit.Test

class CueSignalTest {
    @Test
    fun `Pace delegates name to the wrapped Cue`() {
        assertEquals("TOO_FAST", CueSignal.Pace(Cue.TOO_FAST).name)
    }

    @Test
    fun `Routine delegates name to the wrapped RoutineCue`() {
        assertEquals("ROUTINE_START", CueSignal.Routine(RoutineCue.ROUTINE_START).name)
    }
}
```

- [ ] **Step 2: Eseguire il test, verificare che fallisca**

```bash
./gradlew :core:test --tests "dev.fred.pacorun.service.CueSignalTest"
```
Expected: FAIL — `CueSignal` non esiste ancora (errore di compilazione).

- [ ] **Step 3: Creare `CueSignal.kt`**

```kotlin
package dev.fred.pacorun.service

import dev.fred.pacorun.pace.Cue
import dev.fred.pacorun.routine.RoutineCue

sealed interface CueSignal {
    val name: String
    data class Pace(val cue: Cue) : CueSignal {
        override val name get() = cue.name
    }
    data class Routine(val cue: RoutineCue) : CueSignal {
        override val name get() = cue.name
    }
}
```

- [ ] **Step 4: Eseguire il test, verificare che passi**

```bash
./gradlew :core:test --tests "dev.fred.pacorun.service.CueSignalTest"
```
Expected: PASS.

- [ ] **Step 5: Aggiungere `CueSignal.toAudioCue()` a `CuePlayer.kt`**

In `app/src/main/java/dev/fred/pacorun/audio/CuePlayer.kt`, subito dopo `fun RoutineCue.toAudioCue(): AudioCue = AudioCue.valueOf(name)` (riga 44 di oggi), aggiungere:

```kotlin
import dev.fred.pacorun.service.CueSignal

fun CueSignal.toAudioCue(): AudioCue = when (this) {
    is CueSignal.Pace -> cue.toAudioCue()
    is CueSignal.Routine -> cue.toAudioCue()
}
```

(l'`import` va nella sezione import in cima al file, non qui in mezzo — aggiungerlo vicino agli altri import `dev.fred.pacorun.*` già presenti).

`:app` già dipende da `:core` dal Task 2, quindi questo compila senza altre modifiche a `app/build.gradle.kts`.

- [ ] **Step 6: Verificare tutto**

```bash
./gradlew :core:test :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL — nessun file esistente ancora modificato oltre a `CuePlayer.kt` (un'aggiunta pura, nessuna riga esistente toccata).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: introduce CueSignal in :core, bridge to AudioCue in CuePlayer"
```

---

## Task 4: Motori → `:core` (il task più grande e vincolato nell'ordine)

Spec: "Allocazione dei file" (righe `SpeedEstimator.kt`/`RunSessionStore.kt`/`PaceEvaluator.kt`/`RoutineEngine.kt`/`SessionEventWriter.kt`/`GpxWriter.kt`/`paceConfig()`), "Interfacce di supporto" (visibilità `internal`→pubblico), "Ordine di migrazione" passo 3.

Questo task sposta 6 file di produzione più `CueSignal`'s consumer (`RunSessionStore.kt`) tutti insieme perché si bloccano a vicenda a compilare separatamente (spec, righe 707-713, 726-730): `PaceEvaluator` richiede `RunPreferences.paceConfig()` per compilare i suoi call site di test, `RunSessionStore.kt` richiede `CueSignal` (già pronto dal Task 3) per smettere di referenziare `AudioCue`.

**Files:**
- Move: `app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt` → `core/.../service/SpeedEstimator.kt`
- Move: `app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt` → `core/.../pace/PaceEvaluator.kt`
- Move: `app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt` → `core/.../routine/RoutineEngine.kt`
- Move: `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt` → `core/.../service/RunSessionStore.kt`
- Move: `app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt` → `core/.../eventlog/SessionEventWriter.kt`
- Move: `app/src/main/java/dev/fred/pacorun/export/GpxWriter.kt` → `core/.../export/GpxWriter.kt`
- Modify (in `:core` dopo il move): `SpeedEstimator.kt`, `RunSessionStore.kt`, `pace/PaceModels.kt`
- Modify (resta in `:app`): `RunSessionService.kt` (una riga), `LocationSampleMapper.kt` (nessuna, verificare solo che compili ancora — vedi Step 11)
- Modify: `gradle/libs.versions.toml`, `core/build.gradle.kts` (nuova dipendenza `kotlinx-coroutines-core`)
- Move: 18 file di test (elencati sotto) — di cui 3 richiedono anche modifiche di contenuto, non solo `git mv`: `GpsFreshnessTrackerTest.kt`, `SessionCoordinatorRealRunTest.kt`, `SessionCoordinatorTest.kt`

**Interfaces:**
- Produces: `RunSessionStore.publish` pubblico (era `internal`); 6 accessor pubblici su `SpeedEstimator` (`getLastInnovationEma`, `getLastProcessNoiseRegime`, `getLastAccuracyGateEngaged`, `getLastRobustWeight`, `getLastPostBurstExemptionApplied`, `getLastAcceptWasMeasurement` — `getLastProcessNoise()` resta `internal`); `fun RunPreferences.paceConfig(): PaceConfig` top-level in `pace/PaceModels.kt`; `PendingSessionCoordinator`/`SessionCoordinator.onCues` ora `(List<CueSignal>) -> Unit` (era `(List<AudioCue>, Float) -> Unit`).
- Consumes: `CueSignal` (Task 3), `RunPreferences`/`PaceConfig`/`SessionEvent`/ecc. (Task 2).

- [ ] **Step 1: Muovere i 6 file di produzione**

```bash
mkdir -p core/src/main/java/dev/fred/pacorun/{service,export}
git mv app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt core/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt
git mv app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt core/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt
git mv app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt core/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt
git mv app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt core/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt
git mv app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt core/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt
git mv app/src/main/java/dev/fred/pacorun/export/GpxWriter.kt core/src/main/java/dev/fred/pacorun/export/GpxWriter.kt
```

- [ ] **Step 2: Aggiungere `kotlinx-coroutines-core` a `:core`**

**Verificato empiricamente** (compilazione reale eseguita, non per lettura): `RunSessionStore.kt`, appena spostato, importa `kotlinx.coroutines.flow.MutableStateFlow`/`StateFlow`/`asStateFlow` (per il proprio `RunSessionStore.state: StateFlow<RunSessionState>`) — ma né il Task 1 né nessun punto precedente del piano avevano mai aggiunto una dipendenza coroutines a `:core`. Senza questo step, `:core:compileKotlin` fallisce con `Unresolved reference 'kotlinx'`/`'MutableStateFlow'`/`'StateFlow'`. `:app` dichiara solo `kotlinx-coroutines-android` (non raggiungibile da `:core`, che non può dipendere da `:app`) — serve la dipendenza diretta sul modulo puro `-core` (non `-android`, che porterebbe con sé dispatcher Android in un modulo che deve restare privo di SDK Android).

In `gradle/libs.versions.toml`, sezione `[libraries]`, aggiungere un nuovo alias (riusa il version ref `coroutines` già esistente, stesso usato da `kotlinx-coroutines-test`):
```toml
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
```

In `core/build.gradle.kts`:
```kotlin
// PRIMA:
dependencies {
    testImplementation(libs.junit)
}

// DOPO:
dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
```
`api`, non `implementation`: `StateFlow<RunSessionState>` fa parte della superficie pubblica di `:core` (consumata direttamente da `:app`, es. `PacoRunApp.kt`/`ActiveRunScreen.kt` via `RunSessionStore.state.collectAsStateWithLifecycle()`) — chi dipende da `:core` deve vedere quel tipo senza dover dichiarare la stessa libreria da sé.

- [ ] **Step 3: Rendere pubblici i 6 accessor diagnostici di `SpeedEstimator`**

In `core/.../service/SpeedEstimator.kt`, righe (oggi) 170-177 — rimuovere `internal` da 6 delle 7 dichiarazioni, **lasciare `getLastProcessNoise()` `internal`**:

```kotlin
// PRIMA:
internal fun getLastInnovationEma(): Double = maxOf(kotlin.math.abs(eastInnovationEma), kotlin.math.abs(northInnovationEma))
internal fun getLastProcessNoise(): Double = lastProcessNoise
internal fun getLastAccuracyGateEngaged(): Boolean = lastAccuracyGateEngaged
internal fun getLastRobustWeight(): Double = lastRobustWeight
internal fun getLastPostBurstExemptionApplied(): Boolean = lastPostBurstExemptionApplied
internal fun getLastProcessNoiseRegime(): ProcessNoiseRegime =
    if (lastProcessNoise == qHigh) ProcessNoiseRegime.Q_HIGH else ProcessNoiseRegime.Q_LOW
internal fun getLastAcceptWasMeasurement(): Boolean = lastAcceptWasMeasurement

// DOPO:
fun getLastInnovationEma(): Double = maxOf(kotlin.math.abs(eastInnovationEma), kotlin.math.abs(northInnovationEma))
internal fun getLastProcessNoise(): Double = lastProcessNoise
fun getLastAccuracyGateEngaged(): Boolean = lastAccuracyGateEngaged
fun getLastRobustWeight(): Double = lastRobustWeight
fun getLastPostBurstExemptionApplied(): Boolean = lastPostBurstExemptionApplied
fun getLastProcessNoiseRegime(): ProcessNoiseRegime =
    if (lastProcessNoise == qHigh) ProcessNoiseRegime.Q_HIGH else ProcessNoiseRegime.Q_LOW
fun getLastAcceptWasMeasurement(): Boolean = lastAcceptWasMeasurement
```

(`getLastProcessNoise()` resta `internal`: il suo unico chiamante reale, `SpeedEstimatorTest.kt`, si sposta insieme a `SpeedEstimator` in questo stesso task — Step 8 — quindi test e produzione condividono ancora lo stesso modulo, nessun motivo di renderlo pubblico.)

- [ ] **Step 4: Rendere pubblico `RunSessionStore.publish`**

In `core/.../service/RunSessionStore.kt`:

```kotlin
// PRIMA:
internal fun publish(state: RunSessionState) {

// DOPO:
fun publish(state: RunSessionState) {
```

- [ ] **Step 5: Aggiungere `RunPreferences.paceConfig()` a `PaceModels.kt`**

In `core/.../pace/PaceModels.kt`, aggiungere in cima l'import e in fondo al file la funzione (oggi membro privato di `RunSessionService.kt:511`, resa top-level e pubblica):

```kotlin
package dev.fred.pacorun.pace

import dev.fred.pacorun.settings.RunPreferences

enum class PaceState { ACQUIRING_GPS, GPS_WEAK, TOO_SLOW, GOOD, TOO_FAST }

enum class Cue { TOO_SLOW, TOO_FAST, GOOD_PACE, BAD_GPS, GOOD_GPS }

data class PaceConfig(
    val minKmh: Float,
    val maxKmh: Float,
    val confirmationSamples: Int,
    val repeatMillis: Long,
    val gpsCuesEnabled: Boolean,
)

data class PaceResult(
    val state: PaceState,
    val speedKmh: Float?,
    val cue: Cue?,
)

fun RunPreferences.paceConfig() = PaceConfig(
    minKmh = constantRange.minKmh,
    maxKmh = constantRange.maxKmh,
    confirmationSamples = confirmationSamples,
    repeatMillis = repeatMillis,
    gpsCuesEnabled = gpsCuesEnabled,
)
```

Non rimuovere ancora la vecchia `private fun RunPreferences.paceConfig()` da `RunSessionService.kt:511` — resta lì fino al Task 9, quando l'intero file si sposta (la versione privata in `:app` e quella pubblica in `:core` convivono senza conflitto: sono in scope diverso, `RunSessionService.kt` continua a usare la propria membro-privata fino a quel momento; la nuova versione top-level in `:core` serve al costruttore di `RunSessionEventSequencer`, che la chiama al posto di `RunSessionService.beginTracking()` — vedi Task 8).

- [ ] **Step 6: Cambiare la firma di `onCues` e la costruzione di `CueSignal` in `RunSessionStore.kt`**

In `core/.../service/RunSessionStore.kt`:

1. Rimuovere `import dev.fred.pacorun.audio.AudioCue` (riga 3 di oggi) — `:core` non può dipendere da `:app`/`:android-connector`, e questo import diventerebbe irrisolvibile appena il file è fisicamente in `:core`.

2. Su **entrambe** le classi `SessionCoordinator` e `PendingSessionCoordinator`, cambiare il tipo del parametro:
```kotlin
// PRIMA (su entrambe):
private val onCues: (List<AudioCue>, Float) -> Unit,

// DOPO (su entrambe):
private val onCues: (List<CueSignal>) -> Unit,
```

3. Dentro `SessionCoordinator.process()`, cambiare la costruzione delle cue e la chiamata:
```kotlin
// PRIMA:
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

// DOPO:
val cues = routine.cues.map { CueSignal.Routine(it) } + listOfNotNull(pace.cue?.let { CueSignal.Pace(it) })
if (cues.isNotEmpty()) {
    onCues(cues)
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
`it.name` alla riga `cueType = it.name` non cambia: `it` è ora un `CueSignal`, e `CueSignal.name` delega al nome dell'enum avvolto — stessa stringa di output di oggi (`"ROUTINE_START"`, `"TOO_FAST"`, ecc.).

`CueSignal` non serve importarlo: è nello stesso package `dev.fred.pacorun.service` di `RunSessionStore.kt` (Task 3).

**Nota:** il riferimento a `LocationSampleMapper.DEFAULT_MAX_AGE_MILLIS` in `RunSessionStore.kt` (`GpsFreshnessTracker`'s default param + dentro `SessionCoordinator.process()`) è già stato corretto al Task 2 Step 2, insieme allo spostamento della costante — non c'è nulla da fare qui. Verificare con `grep -n "LocationSampleMapper" core/.../service/RunSessionStore.kt` dopo il `git mv` di questo task: deve restituire zero righe (né la vecchia qualificazione, né un import residuo).

- [ ] **Step 7: Aggiungere la nuova riga a `RunSessionService.kt` (resta in `:app`)**

In `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt`, riga 220 di oggi:

```kotlin
// PRIMA:
onCues = { cues, volume -> cuePlayer?.enqueue(cues, volume) },

// DOPO:
onCues = { cues -> cuePlayer?.enqueue(cues.map { it.toAudioCue() }, preferences.cueVolume) },
```

Aggiungere l'import (vicino agli altri `import dev.fred.pacorun.audio.*` esistenti):
```kotlin
import dev.fred.pacorun.audio.toAudioCue
```

Nessun'altra riga di `RunSessionService.kt` cambia in questo task — il resto del file resta esattamente com'è fino al Task 9.

- [ ] **Step 8: Spostare i 18 file di test e la fixture GPX che due di loro caricano da resource**

`SessionCoordinatorRealRunTest.kt`/`SpeedEstimatorRealRunTest.kt` caricano `paco-run-2026-09-10-1122.gpx` da `app/src/test/resources/` via `javaClass.classLoader?.getResourceAsStream(...)` — quel file deve spostarsi insieme a loro, altrimenti entrambi falliscono a runtime con "fixture not found" una volta che girano sul classpath di test di `:core` (che non include `app/src/test/resources`).

```bash
mkdir -p core/src/test/java/dev/fred/pacorun/{pace,routine,eventlog,export,service}
mkdir -p core/src/test/resources
git mv app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt core/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt
git mv app/src/test/java/dev/fred/pacorun/export/GpxWriterTest.kt core/src/test/java/dev/fred/pacorun/export/GpxWriterTest.kt
git mv app/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt core/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt
git mv app/src/test/java/dev/fred/pacorun/routine/RoutineEngineTest.kt core/src/test/java/dev/fred/pacorun/routine/RoutineEngineTest.kt
git mv app/src/test/java/dev/fred/pacorun/service/DistanceAccumulatorTest.kt core/src/test/java/dev/fred/pacorun/service/DistanceAccumulatorTest.kt
git mv app/src/test/java/dev/fred/pacorun/service/EmaPaceSmootherTest.kt core/src/test/java/dev/fred/pacorun/service/EmaPaceSmootherTest.kt
git mv app/src/test/java/dev/fred/pacorun/service/GpsFreshnessTrackerTest.kt core/src/test/java/dev/fred/pacorun/service/GpsFreshnessTrackerTest.kt
git mv app/src/test/java/dev/fred/pacorun/service/GpsSilentGapTrackerTest.kt core/src/test/java/dev/fred/pacorun/service/GpsSilentGapTrackerTest.kt
git mv app/src/test/java/dev/fred/pacorun/service/RobustOutlierRejectionTest.kt core/src/test/java/dev/fred/pacorun/service/RobustOutlierRejectionTest.kt
git mv app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorRealRunTest.kt core/src/test/java/dev/fred/pacorun/service/SessionCoordinatorRealRunTest.kt
git mv app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt core/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt
git mv app/src/test/java/dev/fred/pacorun/service/SessionServiceSupportTest.kt core/src/test/java/dev/fred/pacorun/service/SessionServiceSupportTest.kt
git mv app/src/test/java/dev/fred/pacorun/service/SessionSpeedEngineTest.kt core/src/test/java/dev/fred/pacorun/service/SessionSpeedEngineTest.kt
git mv app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorRealRunTest.kt core/src/test/java/dev/fred/pacorun/service/SpeedEstimatorRealRunTest.kt
git mv app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt core/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt
git mv app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt core/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt
git mv app/src/test/java/dev/fred/pacorun/service/SyntheticTrajectory.kt core/src/test/java/dev/fred/pacorun/service/SyntheticTrajectory.kt
git mv app/src/test/java/dev/fred/pacorun/service/SyntheticTrajectoryTest.kt core/src/test/java/dev/fred/pacorun/service/SyntheticTrajectoryTest.kt
git mv app/src/test/resources/paco-run-2026-09-10-1122.gpx core/src/test/resources/paco-run-2026-09-10-1122.gpx
```

- [ ] **Step 9: Modificare `GpsFreshnessTrackerTest.kt` — smettere di usare `LocationSampleMapper`, e `SessionCoordinatorRealRunTest.kt` — sistemare `onCues`**

**Verificato empiricamente** (compilazione reale): oltre a `GpsFreshnessTrackerTest.kt` (sotto), anche `SessionCoordinatorRealRunTest.kt` — spostato allo Step 8 ma non elencato fra i file da modificare — contiene un residuo della vecchia firma `onCues` a due argomenti: `onCues = { _, _ -> },` (una sola occorrenza). Senza questa correzione `:core:compileTestKotlin` fallisce con `Argument type mismatch`. Cambiare in `onCues = { _ -> },` — stessa trasformazione dello Step 6, un solo punto in questo file (nessun'altra modifica necessaria: non usa `AudioCue` in nessun'altra forma).

Leggere il file appena spostato (`core/.../service/GpsFreshnessTrackerTest.kt`). Oggi costruisce i suoi due fixture con `LocationSampleMapper().map(...)` — che non compila più in `:core` (`LocationSampleMapper` è Android-only, resta in `:app`/si sposta in `:android-connector` al Task 6). Il test non ha bisogno del mapper: gli serve solo un `LocationSample` con un dato `reliable`/`elapsedRealtimeMillis`. Sostituire ogni costruzione `LocationSampleMapper().map(speedMps = ..., accuracyMps = ..., ageMillis = ..., elapsedRealtimeMillis = ...)` con una costruzione diretta:

```kotlin
LocationSample(speedKmh = 0f, speedAccuracyKmh = null, reliable = true, elapsedRealtimeMillis = <lo stesso valore>)
```

usando `reliable = true` per il fixture "fresco" e `reliable = false` (o un `elapsedRealtimeMillis` vecchio) per quello "stale", secondo quale dei due casi il test originale costruiva con quali parametri — leggere l'intento di ciascun test case dal nome del metodo prima di scegliere i valori esatti. Rimuovere l'import di `LocationSampleMapper` se resta inutilizzato.

- [ ] **Step 10: Modificare `SessionCoordinatorTest.kt` — asserire su `CueSignal`, non `AudioCue`**

Leggere il file appena spostato. Verificato (grep): ~28 punti nel file usano `onCues` a due argomenti (lambda `{ cues, _ -> ... }`) o liste `MutableList<AudioCue>`. Per ciascuno:

- Ogni lambda `onCues = { cues, _ -> capturedCues += cues }` (o simile, con volume come secondo parametro ignorato) diventa `onCues = { cues -> capturedCues += cues }` (un solo parametro).
- Ogni `mutableListOf<AudioCue>()` (5 occorrenze — righe 116, 160, 203, 236, 408 di oggi) diventa `mutableListOf<CueSignal>()`.
- Ogni assert che oggi confronta un elemento contro un valore `AudioCue` (es. `assertEquals(AudioCue.ROUTINE_START, capturedCues[0])`) va cambiato per confrontare contro il `CueSignal` equivalente: `assertEquals(CueSignal.Routine(RoutineCue.ROUTINE_START), capturedCues[0])` se il cue originale veniva da una routine, o `CueSignal.Pace(Cue.TOO_FAST)` se veniva dal pace evaluator — usare il contesto del test (quale motore genera quel cue) per scegliere `Pace` vs `Routine`, non indovinare. Rimuovere l'import di `AudioCue` se resta inutilizzato; aggiungere `import dev.fred.pacorun.service.CueSignal`.
- Gli assert su `cueType` come stringa (es. `assertEquals("ROUTINE_START", event.cueType)`) **non cambiano** — restano identici, dato che `CueSignal.name` produce lo stesso valore di `AudioCue.name` per lo stesso cue logico.
- **Verificato empiricamente (test reale eseguito, fallito, capito il motivo)**: due lambda `onCues = { cues -> events += "cues:${cues.joinToString()}" }` (righe 31 e 71 di oggi) NON vanno lasciate con `joinToString()` nudo. `CueSignal` (sealed interface, `Pace`/`Routine` sono `data class`) non ha un `toString()` proprio — `joinToString()` di default chiama `toString()` su ogni elemento, che per una `data class` produce `Pace(cue=TOO_FAST)`/`Routine(cue=ROUTINE_START)`, non il nome nudo dell'enum come faceva `AudioCue` (un `enum class`, il cui `toString()` di default è il nome della costante). Gli assert di questi due test si aspettano stringhe come `"cues:ROUTINE_START, TOO_FAST"` — con `joinToString()` nudo falliscono. Fix: `cues.joinToString { it.name }` in entrambi i punti — usa lo stesso `CueSignal.name` già usato ovunque nel resto del file per lo stesso motivo, non un meccanismo nuovo.

- [ ] **Step 11: Verificare**

```bash
./gradlew :core:test :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL. Se `SessionCoordinatorTest.kt`/`GpsFreshnessTrackerTest.kt`/`SessionCoordinatorRealRunTest.kt` falliscono a compilare, o se `SessionCoordinatorTest.kt` fallisce l'esecuzione (non la compilazione) su un assert di stringa `cues:...`, rileggere lo Step corrispondente — non indovinare un fix diverso da quello descritto.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "refactor: move engines (SpeedEstimator, PaceEvaluator, RoutineEngine, RunSessionStore) to :core; switch onCues to CueSignal"
```

---

## Task 5: `SpeedEngineDiagnostics` — interfaccia capability in `:core`

Spec: "Interfacce di supporto" (righe 525-546), "Ordine di migrazione" passo 4.

**Files:**
- Create: `core/src/main/java/dev/fred/pacorun/service/SpeedEngineDiagnostics.kt`
- Modify: `core/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt` (classe `KalmanSpeedEngine`)
- Create: `core/src/test/java/dev/fred/pacorun/service/SpeedEngineDiagnosticsTest.kt`

**Interfaces:**
- Produces: `interface SpeedEngineDiagnostics { fun diagnostics(): SpeedEstimatorDiagnostics; fun lastAcceptWasMeasurement(): Boolean }`, implementata da `KalmanSpeedEngine`.
- Consumes: i 6 accessor pubblici di `SpeedEstimator` (Task 4).

- [ ] **Step 1: Scrivere il test**

```kotlin
package dev.fred.pacorun.service

import dev.fred.pacorun.location.LocationSample
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEngineDiagnosticsTest {
    @Test
    fun `KalmanSpeedEngine implements SpeedEngineDiagnostics`() {
        val engine = KalmanSpeedEngine()
        assertNotNull(engine as? SpeedEngineDiagnostics)
    }

    @Test
    fun `DopplerSpeedEngine does not implement SpeedEngineDiagnostics`() {
        val engine = DopplerSpeedEngine()
        assertNull(engine as? SpeedEngineDiagnostics)
    }

    @Test
    fun `diagnostics reflects the underlying SpeedEstimator after a real measurement`() {
        val engine = KalmanSpeedEngine()
        val diagnosticsView = engine as SpeedEngineDiagnostics
        val position = LocationPosition(latitude = 45.0, longitude = 9.0, elapsedRealtimeMillis = 0L, horizontalAccuracyMeters = 5f)
        engine.accept(sample = fakeSample(), position = position, reliable = true)
        // Second fix: first accept() only seeds the filter (returns 0.0, lastAcceptWasMeasurement=false).
        val secondPosition = position.copy(elapsedRealtimeMillis = 1_000L, latitude = 45.0001)
        engine.accept(sample = fakeSample(), position = secondPosition, reliable = true)
        assertTrue(diagnosticsView.lastAcceptWasMeasurement())
        assertNotNull(diagnosticsView.diagnostics())
    }

    private fun fakeSample() = LocationSample(
        speedKmh = 10f, speedAccuracyKmh = .3f, reliable = true, elapsedRealtimeMillis = 0L,
    )
}
```

- [ ] **Step 2: Eseguire, verificare che fallisca**

```bash
./gradlew :core:test --tests "dev.fred.pacorun.service.SpeedEngineDiagnosticsTest"
```
Expected: FAIL — `SpeedEngineDiagnostics` non esiste.

- [ ] **Step 3: Creare l'interfaccia**

```kotlin
package dev.fred.pacorun.service

import dev.fred.pacorun.eventlog.SpeedEstimatorDiagnostics

interface SpeedEngineDiagnostics {
    fun diagnostics(): SpeedEstimatorDiagnostics
    fun lastAcceptWasMeasurement(): Boolean
}
```

- [ ] **Step 4: Far implementare l'interfaccia a `KalmanSpeedEngine`**

In `RunSessionStore.kt`:

```kotlin
// PRIMA:
class KalmanSpeedEngine(private val speedEstimator: SpeedEstimator = SpeedEstimator()) : SessionSpeedEngine {
    override fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double =
        speedEstimator.accept(position, reliable)

    override fun breakChain(): Double = speedEstimator.breakChain()
}

// DOPO:
class KalmanSpeedEngine(private val speedEstimator: SpeedEstimator = SpeedEstimator()) : SessionSpeedEngine, SpeedEngineDiagnostics {
    override fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double =
        speedEstimator.accept(position, reliable)

    override fun breakChain(): Double = speedEstimator.breakChain()

    override fun diagnostics(): SpeedEstimatorDiagnostics = SpeedEstimatorDiagnostics(
        processNoiseRegime = speedEstimator.getLastProcessNoiseRegime(),
        accuracyGateEngaged = speedEstimator.getLastAccuracyGateEngaged(),
        innovationEmaMagnitude = speedEstimator.getLastInnovationEma(),
        robustWeight = speedEstimator.getLastRobustWeight(),
        postBurstExemptionApplied = speedEstimator.getLastPostBurstExemptionApplied(),
    )

    override fun lastAcceptWasMeasurement(): Boolean = speedEstimator.getLastAcceptWasMeasurement()
}
```

Aggiungere `import dev.fred.pacorun.eventlog.SpeedEstimatorDiagnostics` se non già presente nel file.

- [ ] **Step 5: Eseguire, verificare che passi**

```bash
./gradlew :core:test --tests "dev.fred.pacorun.service.SpeedEngineDiagnosticsTest"
```
Expected: PASS.

- [ ] **Step 6: Verificare tutto**

```bash
./gradlew :core:test :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: introduce SpeedEngineDiagnostics capability interface, implemented by KalmanSpeedEngine"
```

---

## Task 6: File Android-only condivisi → `:android-connector`

Spec: "Allocazione dei file" (righe `LocationSampleMapper.kt`/`SessionEventLogger.kt`/`GpxExporter.kt`/`CuePlayer.kt`/`RunPreferencesSource`), "Ordine di migrazione" passo 5.

**Files:**
- Move: `app/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt` → `android-connector/.../location/LocationSampleMapper.kt`
- Move: `app/src/main/java/dev/fred/pacorun/service/SessionEventLogger.kt` → `android-connector/.../service/SessionEventLogger.kt`
- Move: `app/src/main/java/dev/fred/pacorun/export/GpxExporter.kt` → `android-connector/.../export/GpxExporter.kt`
- Move: `app/src/main/java/dev/fred/pacorun/audio/CuePlayer.kt` → `android-connector/.../audio/CuePlayer.kt`
- Move: 14 file `app/src/main/res/raw/*.wav` → `android-connector/src/main/res/raw/`
- Create: `android-connector/src/main/java/dev/fred/pacorun/service/RunPreferencesSource.kt`
- Move: 6 file di test (elencati sotto)
- Modify: `app/build.gradle.kts`, `android-connector/build.gradle.kts`

**Interfaces:**
- Produces: `interface RunPreferencesSource { suspend fun current(): RunPreferences }` in `:android-connector`.
- Consumes: `DEFAULT_MAX_AGE_MILLIS`, `LocationSample`, `QualityTier`, `RejectionReason` (già in `:core`).

- [ ] **Step 1: Muovere i 4 file di produzione e le 14 risorse audio**

```bash
mkdir -p android-connector/src/main/java/dev/fred/pacorun/{location,service,export,audio}
mkdir -p android-connector/src/main/res/raw
git mv app/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt android-connector/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt
git mv app/src/main/java/dev/fred/pacorun/service/SessionEventLogger.kt android-connector/src/main/java/dev/fred/pacorun/service/SessionEventLogger.kt
git mv app/src/main/java/dev/fred/pacorun/export/GpxExporter.kt android-connector/src/main/java/dev/fred/pacorun/export/GpxExporter.kt
git mv app/src/main/java/dev/fred/pacorun/audio/CuePlayer.kt android-connector/src/main/java/dev/fred/pacorun/audio/CuePlayer.kt
git mv app/src/main/res/raw/*.wav android-connector/src/main/res/raw/
```

Nel `CuePlayer.kt` appena spostato, cambiare l'import della classe `R`: il `namespace` di `:android-connector` è `dev.fred.pacorun.connector` (Task 1), non `dev.fred.pacorun` — la classe `R` generata è quindi `dev.fred.pacorun.connector.R`, e `:android-connector` non dipende da `:app` (direzione delle dipendenze vincolata), quindi il vecchio `dev.fred.pacorun.R` non è raggiungibile da qui.

```kotlin
// PRIMA:
import dev.fred.pacorun.R

// DOPO:
import dev.fred.pacorun.connector.R
```

- [ ] **Step 2: Aggiungere le dipendenze Android che questi file portano con sé**

In `android-connector/build.gradle.kts`, dentro `dependencies { ... }`:

```kotlin
dependencies {
    implementation(project(":core"))
    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation(libs.junit)
}
```

**Verificato empiricamente** (non per intuito, e non una ripetizione della nota di una versione precedente di questo piano che si è rivelata sbagliata): `CuePlayer.kt` usa `@RawRes` (righe 9, 20) e `@VisibleForTesting` (righe 152, 155), entrambe da `androidx.annotation`. Un'affermazione precedente in questo piano diceva che questa dipendenza arrivasse transitivamente da `androidx.media3:media3-exoplayer` — **falso**: `./gradlew :android-connector:dependencies --configuration debugCompileClasspath` mostra che `media3-exoplayer` porta `androidx.annotation` solo sul classpath di **runtime**, non su quello di **compilazione** (dove serve per risolvere le annotazioni durante `compileDebugKotlin`). Senza questa riga esplicita, `:android-connector:compileDebugKotlin` fallisce con `Unresolved reference 'RawRes'`/`'VisibleForTesting'`. Nessun alias di catalogo esiste per `androidx.annotation` — dichiarata come stringa diretta, stessa convenzione già usata per `media3-exoplayer`/`play-services-location` (Task 9) in questo piano. Versione `1.9.1`: quella effettivamente risolta su `:app`'s `debugCompileClasspath` oggi (dove la dipendenza arriva transitivamente da varie librerie AndroidX/Compose che `:android-connector` non ha), verificato con `./gradlew :app:dependencies --configuration debugCompileClasspath`.

`LocationSampleMapper.kt`/`SessionEventLogger.kt`/`GpxExporter.kt` usano solo `android.*` core, nessun'altra libreria esterna.

- [ ] **Step 3: Creare `RunPreferencesSource.kt`**

```kotlin
package dev.fred.pacorun.service

import dev.fred.pacorun.settings.RunPreferences

interface RunPreferencesSource {
    suspend fun current(): RunPreferences
}
```

- [ ] **Step 4: Aggiungere la dipendenza `:app` → `:android-connector`**

In `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":core"))
    implementation(project(":android-connector"))
    // ... resto invariato
```

- [ ] **Step 5: Spostare i 6 file di test**

```bash
mkdir -p android-connector/src/test/java/dev/fred/pacorun/{location,audio}
mkdir -p android-connector/src/androidTest/java/dev/fred/pacorun/{export,audio}
git mv app/src/test/java/dev/fred/pacorun/location/LocationSampleMapperTest.kt android-connector/src/test/java/dev/fred/pacorun/location/LocationSampleMapperTest.kt
git mv app/src/test/java/dev/fred/pacorun/audio/AudioCueQueueTest.kt android-connector/src/test/java/dev/fred/pacorun/audio/AudioCueQueueTest.kt
git mv app/src/androidTest/java/dev/fred/pacorun/export/GpxExporterTest.kt android-connector/src/androidTest/java/dev/fred/pacorun/export/GpxExporterTest.kt
git mv app/src/androidTest/java/dev/fred/pacorun/audio/CuePlayerAudioDuckingTest.kt android-connector/src/androidTest/java/dev/fred/pacorun/audio/CuePlayerAudioDuckingTest.kt
git mv app/src/androidTest/java/dev/fred/pacorun/audio/CuePlayerLooperTest.kt android-connector/src/androidTest/java/dev/fred/pacorun/audio/CuePlayerLooperTest.kt
git mv app/src/androidTest/java/dev/fred/pacorun/audio/CueAssetsTest.kt android-connector/src/androidTest/java/dev/fred/pacorun/audio/CueAssetsTest.kt
```

In `CueAssetsTest.kt` appena spostato, stesso identico problema di `CuePlayer.kt` allo Step 1: cambiare `import dev.fred.pacorun.R` in `import dev.fred.pacorun.connector.R` (usato per i 14 `R.raw.*` referenziati nel test). Nessun test locale (`testDebugUnitTest`) compila il source set `androidTest`, quindi questo specifico errore non emerge dalla verifica dello Step 6 sotto — solo eseguendo `connectedDebugAndroidTest` su un device reale, che (hard rule 8) tocca all'utente. Non saltare comunque la correzione qui: lasciarla per quando si accorgerà qualcuno a mano sarebbe esattamente il tipo di buco silenzioso che questo piano deve evitare.

`android-connector/build.gradle.kts` ha bisogno del blocco `defaultConfig.testInstrumentationRunner` per gli androidTest — aggiungere se assente:

```kotlin
android {
    // ... resto invariato
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // ... resto invariato
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
}
```

- [ ] **Step 6: Verificare**

```bash
./gradlew :android-connector:testDebugUnitTest :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL sui test locali. Gli androidTest (`GpxExporterTest`, `CuePlayerAudioDuckingTest`, `CuePlayerLooperTest`, `CueAssetsTest`) richiedono un device/emulatore — segnalare all'utente di eseguire `./gradlew :android-connector:connectedDebugAndroidTest` lui stesso (hard rule 8: non guidare l'emulatore in autonomia), non bloccare questo task su quello.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move Android-only shared files (LocationSampleMapper, SessionEventLogger, GpxExporter, CuePlayer) to :android-connector"
```

---

## Task 7: `GnssSnapshot` pubblico + `LocationReliability` → `:core`

Spec: "Allocazione dei file" (riga `GnssSnapshot`), "Ordine di migrazione" passo 6 (righe 825-827) — separato in un task proprio perché è un prerequisito mccanico, a basso rischio, per il Task 8.

**Files:**
- Create: `core/src/main/java/dev/fred/pacorun/service/GnssSnapshot.kt`
- Modify: `android-connector/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt`
- Modify: `core/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt`

**Interfaces:**
- Produces: `data class GnssSnapshot(val satellitesUsedInFix: Int, val cn0MinDbHz: Float?, val cn0AvgDbHz: Float?, val cn0MaxDbHz: Float?, val geometry: List<SatelliteGeometry>)` pubblico in `:core`; `data class LocationReliability(val reliable: Boolean, val reasons: List<RejectionReason>)` in `:core`'s `SessionEvent.kt`.

- [ ] **Step 1: Creare `GnssSnapshot.kt` pubblico in `:core`**

Oggi è `private data class GnssSnapshot(...)` annidata dentro `RunSessionService.kt` (righe 104-110). Crearla come file proprio, pubblica:

```kotlin
package dev.fred.pacorun.service

import dev.fred.pacorun.eventlog.SatelliteGeometry

data class GnssSnapshot(
    val satellitesUsedInFix: Int,
    val cn0MinDbHz: Float?,
    val cn0AvgDbHz: Float?,
    val cn0MaxDbHz: Float?,
    val geometry: List<SatelliteGeometry>,
)
```

Non rimuovere ancora la data class annidata da `RunSessionService.kt` — quella rimozione, e il rewiring del codice che la usa, avviene al Task 9 insieme al resto della riscrittura di `RunSessionService`. Qui creiamo solo il tipo pubblico gemello in `:core`, non ancora usato da nessuno.

- [ ] **Step 2: Spostare `LocationReliability` da `LocationSampleMapper.kt` a `SessionEvent.kt`**

In `core/.../eventlog/SessionEvent.kt`, aggiungere in fondo al file, vicino a `RejectionReason`:

```kotlin
data class LocationReliability(val reliable: Boolean, val reasons: List<RejectionReason>)
```

In `android-connector/.../location/LocationSampleMapper.kt`, rimuovere la riga finale del file:

```kotlin
// RIMUOVERE:
data class LocationReliability(val reliable: Boolean, val reasons: List<RejectionReason>)
```

e aggiungere l'import:

```kotlin
import dev.fred.pacorun.eventlog.LocationReliability
```

(`:android-connector` dipende già da `:core` dal Task 1 — nessun'altra modifica di dipendenza necessaria.)

- [ ] **Step 3: Verificare**

```bash
./gradlew :core:test :android-connector:testDebugUnitTest :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL — `RunSessionService.kt` non ha ancora usato il nuovo `GnssSnapshot` pubblico, quindi non deve fallire per questo; `LocationSampleMapperTest.kt`/tutti gli altri consumatori di `LocationReliability` (via `import`) devono continuare a risolvere il tipo correttamente dal nuovo file.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: make GnssSnapshot public in :core, move LocationReliability to SessionEvent.kt"
```

---

## Task 8: `RunSessionEventSequencer` — la classe pura, con i 10 casi di test testabili senza Android

Spec: "Split di `RunSessionService.kt` — Approccio A" (l'intera sezione), "Piano di test" casi 2-9, 11-12 (i casi 1 e 10 sono testati al Task 9 — vedi nota lì).

Questo è il pezzo più delicato di tutta l'estrazione (spec: "il pezzo più delicato, fatto per ultimo di proposito"). Ogni riga qui sotto è stata derivata leggendo `RunSessionService.kt` reale riga per riga (non per categoria architetturale) — vedi la spec per la giustificazione di ogni scelta non ovvia.

**Files:**
- Create: `core/src/main/java/dev/fred/pacorun/service/RunSessionEventSequencer.kt`
- Create: `core/src/test/java/dev/fred/pacorun/service/RunSessionEventSequencerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class RunSessionEventSequencer(
      preferences: RunPreferences,
      onCues: (List<CueSignal>) -> Unit,
      publish: (RunSessionState) -> Unit,
      sessionStartedAtElapsedMillis: Long,
  ) {
      fun onLocationFix(sample: LocationSample, position: LocationPosition, snapshot: GnssSnapshot?, provider: String?, reliability: LocationReliability, qualityTier: QualityTier, now: Long): LocationFixResult
      fun onTimerTick(now: Long): TimerTickResult
      fun recordDeviceHealthSample(batteryPercent: Int?, thermalStatus: ThermalStatus, now: Long): SessionEvent
      companion object { const val TICK_MILLIS = 1_000L }
  }
  data class LocationFixResult(val events: List<SessionEvent>, val engineSpeedKmh: Double?)
  data class TimerTickResult(val events: List<SessionEvent>, val deviceHealthSampleDue: Boolean)
  ```
- Consumes: `PendingSessionCoordinator`, `RoutineEngineAdapter`, `PaceEvaluatorAdapter`, `DistanceAccumulator`, `KalmanSpeedEngine`, `SpeedEstimator`, `GpsFreshnessTracker`, `GpsSilentGapTracker`, `RunPreferences.paceConfig()` (tutti già in `:core` dal Task 4), `SpeedEngineDiagnostics` (Task 5), `GnssSnapshot`/`LocationReliability` (Task 7).

- [ ] **Step 1: Scrivere i 10 test (falliranno a compilare — la classe non esiste ancora)**

```kotlin
package dev.fred.pacorun.service

import dev.fred.pacorun.eventlog.AudioCueEmittedEvent
import dev.fred.pacorun.eventlog.DeviceHealthSampledEvent
import dev.fred.pacorun.eventlog.DistanceChainBreakReason
import dev.fred.pacorun.eventlog.DistanceChainBrokenEvent
import dev.fred.pacorun.eventlog.GpsSilentGapEvent
import dev.fred.pacorun.eventlog.LocationFixEvent
import dev.fred.pacorun.eventlog.LocationReliability
import dev.fred.pacorun.eventlog.PaceCandidateStartedEvent
import dev.fred.pacorun.eventlog.QualityTier
import dev.fred.pacorun.eventlog.RejectionReason
import dev.fred.pacorun.eventlog.RoutinePhaseChangedEvent
import dev.fred.pacorun.eventlog.SpeedChainBreakReason
import dev.fred.pacorun.eventlog.SpeedChainBrokenEvent
import dev.fred.pacorun.eventlog.ThermalStatus
import dev.fred.pacorun.location.LocationSample
import dev.fred.pacorun.settings.RoutineMode
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.settings.SpeedRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSessionEventSequencerTest {

    private fun newSequencer(
        preferences: RunPreferences = RunPreferences(),
        startedAt: Long = 0L,
    ) = RunSessionEventSequencer(
        preferences = preferences,
        onCues = {},
        publish = {},
        sessionStartedAtElapsedMillis = startedAt,
    )

    private fun reliableSample(now: Long) =
        LocationSample(speedKmh = 10f, speedAccuracyKmh = .3f, reliable = true, elapsedRealtimeMillis = now)

    private fun position(now: Long, lat: Double = 45.0, lon: Double = 9.0) =
        LocationPosition(latitude = lat, longitude = lon, elapsedRealtimeMillis = now, horizontalAccuracyMeters = 5f)

    private fun invalidPosition(now: Long) =
        LocationPosition(latitude = Double.NaN, longitude = 9.0, elapsedRealtimeMillis = now, horizontalAccuracyMeters = 5f)

    private fun reliableReliability() = LocationReliability(reliable = true, reasons = emptyList())
    private fun unreliableReliability() =
        LocationReliability(reliable = false, reasons = listOf(RejectionReason.MISSING_OR_INVALID_SPEED))

    // Caso 2: ordinamento causale -- LocationFixEvent (costruito dal Sequencer stesso, stato
    // post-tick) deve comparire PRIMA delle sue conseguenze (eventi emessi dai motori interni
    // durante la stessa chiamata a coordinator.tick()), non dopo.
    @Test
    fun `LocationFixEvent comes before the events its own tick caused`() {
        val sequencer = newSequencer()
        sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        // Salto di 1 grado di latitudine in 1s (~111km) supera il maxSpeedMetersPerSecond=15.0
        // di default di DistanceAccumulator -> IMPLAUSIBLE_JUMP, emesso DENTRO coordinator.tick().
        val result = sequencer.onLocationFix(
            reliableSample(1_000L), position(1_000L, lat = 46.0), null, "gps", reliableReliability(), QualityTier.OTTIMA, 1_000L,
        )
        val fixIndex = result.events.indexOfFirst { it is LocationFixEvent }
        val consequenceIndex = result.events.indexOfFirst { it is DistanceChainBrokenEvent }
        assertTrue("both events must be present", fixIndex >= 0 && consequenceIndex >= 0)
        assertTrue("LocationFixEvent must precede its own consequences", fixIndex < consequenceIndex)
    }

    // Caso 3(a): un'interruzione GPS rilevata SOLO da onTimerTick, indipendentemente da qualunque
    // fix NEL FRATTEMPO (non "nessun fix mai" -- PendingSessionCoordinator.advanceTimer() è un
    // no-op finché activeCoordinator è null, e activeCoordinator si crea SOLO dentro tick() al
    // primo fix affidabile, RunSessionStore.kt:427-440 -- serve quindi un primo fix per attivare
    // il coordinator, poi il gap di tempo puramente temporale che segue deve bastare a far
    // scattare l'interruzione).
    @Test
    fun `onTimerTick alone detects a GPS outage after a prior fix, with no further fix in between`() {
        val sequencer = newSequencer(preferences = RunPreferences(mode = RoutineMode.CONSTANT))
        sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        // GpsFreshnessTracker.isOutage(now) confronta now - <ultimo fix affidabile> contro
        // DEFAULT_MAX_AGE_MILLIS (3_000ms) -- 4_000ms dopo l'unico fix supera la soglia, e questo
        // tick non ha mai chiamato onLocationFix di nuovo.
        val result = sequencer.onTimerTick(4_000L)
        assertTrue(result.events.any { it is DistanceChainBrokenEvent && it.reason == DistanceChainBreakReason.GPS_OUTAGE_ENTERED })
    }

    // Caso 3(b): un tick puramente temporale (nessun fix GPS in QUEL tick) che fa scattare un cue
    // di routine produce un AudioCueEmittedEvent. Stessa nota del caso 3(a): serve un primo fix
    // per attivare il coordinator (in modalità WALK_RUN con initialConstantKm=0.01f, non 0f, così
    // quel primo fix da solo NON fa scattare startRun()/ROUTINE_START -- resta in
    // CONSTANT_BEFORE, perché il primo fix non ha ancora un anchor precedente e quindi contribuisce
    // sempre 0 metri di distanza). Il cue che vogliamo isolare in un tick puramente temporale è la
    // transizione RUN->WALK dopo runSeconds, che RoutineEngine.advanceTo() decide solo dal tempo
    // trascorso, non dalla distanza -- ma per raggiungere la fase RUN serve comunque partire da
    // lì: si usa quindi initialConstantKm=0f per il primo fix (attiva + fa scattare ROUTINE_START
    // su QUEL fix, ignorato da questo test) e si isola il cue di interesse (WALK_ROUTINE) sul
    // secondo tick, puramente temporale, 11s dopo, con runSeconds=10.
    @Test
    fun `a purely temporal tick that triggers a routine phase transition emits AudioCueEmittedEvent`() {
        val sequencer = newSequencer(
            preferences = RunPreferences(mode = RoutineMode.WALK_RUN, initialConstantKm = 0f, runSeconds = 10, walkSeconds = 10),
        )
        // Attiva il coordinator e fa scattare ROUTINE_START su questo stesso fix (ignorato qui --
        // lo si prova già nel caso 9, sotto).
        sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        // 11s dopo, nessun fix nel mezzo: solo il tempo trascorso fa scattare la transizione
        // RUN->WALK (durationMillis()=10_000L per la fase RUN) e il cue WALK_ROUTINE.
        val result = sequencer.onTimerTick(11_000L)
        assertTrue(result.events.any { it is AudioCueEmittedEvent })
    }

    // Caso 4: lo snapshot GNSS passato a onLocationFix deve finire nel LocationFixEvent di QUEL
    // fix, non in un altro.
    @Test
    fun `the GNSS snapshot passed to onLocationFix ends up on that fix's LocationFixEvent`() {
        val sequencer = newSequencer()
        val snapshot = GnssSnapshot(satellitesUsedInFix = 7, cn0MinDbHz = 18f, cn0AvgDbHz = 22f, cn0MaxDbHz = 30f, geometry = emptyList())
        val result = sequencer.onLocationFix(reliableSample(0L), position(0L), snapshot, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        val fixEvent = result.events.filterIsInstance<LocationFixEvent>().single()
        assertEquals(7, fixEvent.satellitesUsedInFix)
        assertEquals(18f, fixEvent.satelliteCn0MinDbHz)
    }

    // Caso 5: cadenza dei 30s per deviceHealthSampleDue, e recordDeviceHealthSample() costruisce
    // l'evento corretto con i valori passati. (L'ANTEPOSIZIONE di questo evento rispetto agli
    // altri eventi dello stesso tick è comportamento del layer Android, non del Sequencer --
    // testata al Task 9, non qui: recordDeviceHealthSample() qui produce solo l'evento isolato.)
    @Test
    fun `deviceHealthSampleDue fires exactly every 30 seconds, and recordDeviceHealthSample builds the right event`() {
        val sequencer = newSequencer(preferences = RunPreferences(mode = RoutineMode.CONSTANT))
        val firstTick = sequencer.onTimerTick(1_000L)
        assertTrue("first tick must be due (bucket 0 -> first observation)", firstTick.deviceHealthSampleDue)
        val secondTick = sequencer.onTimerTick(2_000L)
        assertFalse("same 30s bucket, must not be due again", secondTick.deviceHealthSampleDue)
        val thirdTick = sequencer.onTimerTick(31_000L)
        assertTrue("new 30s bucket, must be due again", thirdTick.deviceHealthSampleDue)

        val event = sequencer.recordDeviceHealthSample(batteryPercent = 42, thermalStatus = ThermalStatus.MODERATE, now = 31_000L)
        val health = event as DeviceHealthSampledEvent
        assertEquals(42, health.batteryPercent)
        assertEquals(ThermalStatus.MODERATE, health.thermalStatus)
        assertEquals(31_000L, health.elapsedRealtimeMillis)
    }

    // Caso 6: locationSequenceNumber riparte da 0 a ogni nuova istanza (una per sessione).
    @Test
    fun `sequence number starts at 0 for a fresh Sequencer instance`() {
        val sequencer = newSequencer()
        val first = sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        assertEquals(0, first.events.filterIsInstance<LocationFixEvent>().single().sequenceNumber)
        val second = sequencer.onLocationFix(reliableSample(1_000L), position(1_000L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 1_000L)
        assertEquals(1, second.events.filterIsInstance<LocationFixEvent>().single().sequenceNumber)
    }

    // Caso 7: fusione delle due chiamate a evaluate() -- qui verifichiamo solo che il Sequencer
    // stesso accetti reliability/qualityTier come PARAMETRI (calcolati una sola volta dal
    // chiamante, non ricalcolati internamente) e li riporti fedelmente sul LocationFixEvent --
    // il resto della fusione (chi chiama evaluate() e quante volte) è comportamento di
    // :android-connector, testato al Task 9.
    @Test
    fun `onLocationFix reports the reliability and qualityTier it was given, without recomputing them`() {
        val sequencer = newSequencer()
        val reliability = LocationReliability(reliable = false, reasons = listOf(RejectionReason.HORIZONTAL_ACCURACY_TOO_HIGH))
        val result = sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliability, QualityTier.SCARSA, 0L)
        val fixEvent = result.events.filterIsInstance<LocationFixEvent>().single()
        assertFalse(fixEvent.reliable)
        assertEquals(listOf(RejectionReason.HORIZONTAL_ACCURACY_TOO_HIGH), fixEvent.rejectionReasons)
        assertEquals(QualityTier.SCARSA, fixEvent.qualityTier)
    }

    // Caso 8: gpsSilentGap -- un gap che supera la soglia produce un GpsSilentGapEvent PRIMA del
    // LocationFixEvent nella stessa lista.
    @Test
    fun `a silent gap over threshold produces GpsSilentGapEvent before LocationFixEvent`() {
        val sequencer = newSequencer(startedAt = 0L)
        // GpsSilentGapTracker's default threshold is 5_000ms -- a fix arriving 6s after reset()
        // (called with sessionStartedAtElapsedMillis=0 in the constructor) triggers it.
        val result = sequencer.onLocationFix(reliableSample(6_000L), position(6_000L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 6_000L)
        val gapIndex = result.events.indexOfFirst { it is GpsSilentGapEvent }
        val fixIndex = result.events.indexOfFirst { it is LocationFixEvent }
        assertTrue("gap event must be present", gapIndex >= 0)
        assertTrue("gap event must precede the fix event", gapIndex < fixIndex)
    }

    // Caso 9: collegamento dei quattro motori interni alla onEvent del Sequencer -- ciascuno
    // deve poter emettere il proprio evento specifico, distinto da quelli che
    // SessionCoordinator.process() emette per conto proprio.
    @Test
    fun `all four inner engines are wired to the Sequencer's own onEvent`() {
        // RoutineEngine: RoutinePhaseChangedEvent al primo FIX (non tick -- PendingSessionCoordinator
        // non è ancora attivato prima di un primo fix affidabile, vedi nota sui casi 3(a)/3(b) sopra)
        // in WALK_RUN con initialConstantKm=0, che fa scattare startRun() sullo stesso fix che attiva
        // il coordinator.
        val routineSequencer = newSequencer(
            preferences = RunPreferences(mode = RoutineMode.WALK_RUN, initialConstantKm = 0f, runSeconds = 30, walkSeconds = 30),
        )
        val routineResult = routineSequencer.onLocationFix(
            reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L,
        )
        assertTrue(routineResult.events.any { it is RoutinePhaseChangedEvent })

        // PaceEvaluator: PaceCandidateStartedEvent al primo fix affidabile nel range di pace.
        val paceSequencer = newSequencer(preferences = RunPreferences(constantRange = SpeedRange(9f, 11f)))
        val paceResult = paceSequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        assertTrue(paceResult.events.any { it is PaceCandidateStartedEvent })

        // SpeedEstimator: SpeedChainBrokenEvent(UNRELIABLE_OR_INVALID_FIX) SOLO con una posizione
        // non valida -- SessionCoordinator.process() chiama sempre speedEngine.accept(reliable=true),
        // quindi un fixture "non affidabile" non basta a raggiungere questo ramo.
        val speedSequencer = newSequencer()
        val speedResult = speedSequencer.onLocationFix(
            reliableSample(0L), invalidPosition(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L,
        )
        assertTrue(speedResult.events.any { it is SpeedChainBrokenEvent && it.reason == SpeedChainBreakReason.UNRELIABLE_OR_INVALID_FIX })

        // DistanceAccumulator: DistanceChainBrokenEvent(IMPLAUSIBLE_JUMP), stesso salto del caso 2.
        val distanceSequencer = newSequencer()
        distanceSequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        val distanceResult = distanceSequencer.onLocationFix(
            reliableSample(1_000L), position(1_000L, lat = 46.0), null, "gps", reliableReliability(), QualityTier.OTTIMA, 1_000L,
        )
        assertTrue(distanceResult.events.any { it is DistanceChainBrokenEvent && it.reason == DistanceChainBreakReason.IMPLAUSIBLE_JUMP })
    }

    // Caso 11: engineSpeedKmh si azzera esplicitamente prima di ogni chiamata interna -- un fix
    // inaffidabile che segue un fix affidabile NON deve ereditare la velocità del fix precedente.
    @Test
    fun `engineSpeedKmh is null after an unreliable fix, even right after a reliable one`() {
        val sequencer = newSequencer()
        val firstResult = sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        // Primo accept() su un KalmanSpeedEngine vergine ritorna sempre 0.0 (semina il filtro) --
        // non è null, ma non è ancora questo il punto: verifichiamo solo che NON resti "appeso"
        // sul secondo fix inaffidabile sotto.
        assertTrue(firstResult.engineSpeedKmh != null)
        val secondSample = LocationSample(speedKmh = 0f, speedAccuracyKmh = null, reliable = false, elapsedRealtimeMillis = 1_000L)
        val secondResult = sequencer.onLocationFix(secondSample, position(1_000L), null, "gps", unreliableReliability(), QualityTier.SCARSA, 1_000L)
        assertNull("an unreliable fix must not inherit the previous fix's speed", secondResult.engineSpeedKmh)
    }

    // Caso 12: il gate reliability.reliable su speedEstimatorDiagnostics -- un fix inaffidabile con
    // getLastAcceptWasMeasurement() ancora vero (stato residuo del fix precedente) non deve
    // allegare diagnostica.
    @Test
    fun `speedEstimatorDiagnostics is null on an unreliable fix, even with residual estimator state`() {
        val sequencer = newSequencer()
        sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        // Secondo fix affidabile: il filtro ha ora eseguito una misura reale (lastAcceptWasMeasurement=true).
        sequencer.onLocationFix(reliableSample(1_000L), position(1_000L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 1_000L)
        // Terzo fix, GPS-livello (reliability.reliable=false) ma senza breakChain() (nessuna posizione
        // invalida) -- lastAcceptWasMeasurement() dell'estimator resta true dal fix precedente.
        val thirdSample = LocationSample(speedKmh = 0f, speedAccuracyKmh = null, reliable = false, elapsedRealtimeMillis = 2_000L)
        val result = sequencer.onLocationFix(
            thirdSample, position(2_000L), null, "gps", unreliableReliability(), QualityTier.SCARSA, 2_000L,
        )
        val fixEvent = result.events.filterIsInstance<LocationFixEvent>().single()
        assertNull("reliability.reliable=false must block diagnostics regardless of estimator state", fixEvent.speedEstimatorDiagnostics)
    }
}
```

- [ ] **Step 2: Eseguire, verificare che i test falliscano a compilare**

```bash
./gradlew :core:test --tests "dev.fred.pacorun.service.RunSessionEventSequencerTest"
```
Expected: FAIL — `RunSessionEventSequencer`/`LocationFixResult`/`TimerTickResult` non esistono ancora.

- [ ] **Step 3: Scrivere `RunSessionEventSequencer.kt`**

```kotlin
package dev.fred.pacorun.service

import dev.fred.pacorun.eventlog.DeviceHealthSampledEvent
import dev.fred.pacorun.eventlog.GpsSilentGapEvent
import dev.fred.pacorun.eventlog.LocationFixEvent
import dev.fred.pacorun.eventlog.LocationReliability
import dev.fred.pacorun.eventlog.QualityTier
import dev.fred.pacorun.eventlog.SessionEvent
import dev.fred.pacorun.eventlog.ThermalStatus
import dev.fred.pacorun.location.LocationSample
import dev.fred.pacorun.pace.PaceEvaluator
import dev.fred.pacorun.pace.paceConfig
import dev.fred.pacorun.routine.RoutineEngine
import dev.fred.pacorun.settings.RunPreferences

class RunSessionEventSequencer(
    preferences: RunPreferences,
    onCues: (List<CueSignal>) -> Unit,
    publish: (RunSessionState) -> Unit,
    private val sessionStartedAtElapsedMillis: Long,
) {
    private val pendingEvents = mutableListOf<SessionEvent>()
    private var locationSequenceNumber = 0
    private var lastTimerSecond = Long.MIN_VALUE
    private var lastDeviceHealthBucket = Long.MIN_VALUE
    private var engineSpeedKmh: Double? = null

    private val gpsFreshness = GpsFreshnessTracker()
    private val gpsSilentGap = GpsSilentGapTracker().also { it.reset(sessionStartedAtElapsedMillis) }

    private val onEvent: (SessionEvent) -> Unit = { pendingEvents += it }

    private val speedEstimator = SpeedEstimator(onEvent = onEvent)
    private val speedEngine = KalmanSpeedEngine(speedEstimator)
    private val coordinator = PendingSessionCoordinator(
        preferences = preferences,
        routineEngine = RoutineEngineAdapter(RoutineEngine(preferences, onEvent = onEvent)),
        paceEvaluator = PaceEvaluatorAdapter(PaceEvaluator(preferences.paceConfig(), onEvent = onEvent)),
        onCues = onCues,
        publish = publish,
        distanceEngine = DistanceAccumulator(onEvent = onEvent),
        speedEngine = speedEngine,
        onSpeedComputed = { engineSpeedKmh = it },
        onEvent = onEvent,
    )

    fun onLocationFix(
        sample: LocationSample,
        position: LocationPosition,
        snapshot: GnssSnapshot?,
        provider: String?,
        reliability: LocationReliability,
        qualityTier: QualityTier,
        now: Long,
    ): LocationFixResult {
        gpsFreshness.record(sample)
        val silentGapEvent = gpsSilentGap.recordCallback(now)?.let { GpsSilentGapEvent(now, it) }

        engineSpeedKmh = null
        coordinator.tick(elapsedRealtimeMillis = now, sample = sample, position = position)

        val diagnostics = if (reliability.reliable) {
            (speedEngine as? SpeedEngineDiagnostics)?.let { if (it.lastAcceptWasMeasurement()) it.diagnostics() else null }
        } else {
            null
        }
        val fixEvent = LocationFixEvent(
            elapsedRealtimeMillis = now,
            sequenceNumber = locationSequenceNumber++,
            elapsedSinceStartMillis = now - sessionStartedAtElapsedMillis,
            provider = provider,
            reliable = reliability.reliable,
            rejectionReasons = reliability.reasons,
            satellitesUsedInFix = snapshot?.satellitesUsedInFix,
            satelliteCn0MinDbHz = snapshot?.cn0MinDbHz,
            satelliteCn0AvgDbHz = snapshot?.cn0AvgDbHz,
            satelliteCn0MaxDbHz = snapshot?.cn0MaxDbHz,
            qualityTier = qualityTier,
            speedEstimatorDiagnostics = diagnostics,
            satelliteGeometry = snapshot?.geometry ?: emptyList(),
        )

        val buffered = pendingEvents.toList()
        pendingEvents.clear()
        val events = buildList {
            if (silentGapEvent != null) add(silentGapEvent)
            add(fixEvent)
            addAll(buffered)
        }
        return LocationFixResult(events, engineSpeedKmh)
    }

    fun onTimerTick(now: Long): TimerTickResult {
        val second = now / TICK_MILLIS
        if (second == lastTimerSecond) return TimerTickResult(emptyList(), false)
        lastTimerSecond = second

        val gpsOutage = gpsFreshness.isOutage(now)
        coordinator.advanceTimer(now, signalGpsOutage = gpsOutage)

        val buffered = pendingEvents.toList()
        pendingEvents.clear()

        val bucket = now / DEVICE_HEALTH_BUCKET_MILLIS
        val due = bucket != lastDeviceHealthBucket
        if (due) lastDeviceHealthBucket = bucket

        return TimerTickResult(buffered, due)
    }

    fun recordDeviceHealthSample(batteryPercent: Int?, thermalStatus: ThermalStatus, now: Long): SessionEvent =
        DeviceHealthSampledEvent(elapsedRealtimeMillis = now, batteryPercent = batteryPercent, thermalStatus = thermalStatus)

    companion object {
        const val TICK_MILLIS = 1_000L
        private const val DEVICE_HEALTH_BUCKET_MILLIS = 30_000L
    }
}

data class LocationFixResult(val events: List<SessionEvent>, val engineSpeedKmh: Double?)
data class TimerTickResult(val events: List<SessionEvent>, val deviceHealthSampleDue: Boolean)
```

- [ ] **Step 4: Eseguire, verificare che tutti i 10 test passino**

```bash
./gradlew :core:test --tests "dev.fred.pacorun.service.RunSessionEventSequencerTest"
```
Expected: PASS su tutti e 10. Se un test specifico fallisce, NON modificare il test per farlo passare senza prima capire se il fallimento riflette un vero disaccordo fra l'implementazione qui sopra e il codice reale di `RunSessionService.kt`/`RunSessionStore.kt` citato nella spec — rileggere quella sezione della spec prima di cambiare qualunque riga.

- [ ] **Step 5: Verificare tutto il modulo**

```bash
./gradlew :core:test
```
Expected: BUILD SUCCESSFUL su tutta la suite di `:core`, non solo sui 10 nuovi test.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: introduce RunSessionEventSequencer, the pure :core replacement for RunSessionService's orchestration logic"
```

### Correzione post-review: `pendingEvents` corrotto se un motore interno lancia un'eccezione a metà `tick()`/`advanceTimer()`

Trovata dalla revisione avversariale su questo task (non dai 10 casi di test, che non la coprivano), verificata contro il codice reale, e **confermata preesistente** (non una regressione di questa estrazione — il codice reale di oggi ha lo stesso difetto: `bufferingLocationEvents = false`, `RunSessionService.kt:342`, non verrebbe mai raggiunto se `coordinator?.tick(...)` lanciasse un'eccezione, lasciando il flag bloccato a `true` fino alla chiamata successiva). Decisione esplicita dell'owner: da correggere subito qui, non rimandata come debito.

Se `coordinator.tick(...)`/`coordinator.advanceTimer(...)` lancia un'eccezione, oggi (nel codice appena scritto sopra) `pendingEvents.clear()` non viene mai raggiunto — gli eventi già accumulati in quella chiamata fallita restano nel campo mutabile e si mescolerebbero, fuori ordine, con quelli della prossima chiamata riuscita; il `LocationFixEvent`/gli eventi di quel tick andrebbero persi senza traccia. Fix: un `try/catch` che, solo in caso di eccezione, svuota il buffer e rilancia l'eccezione originale intatta — il percorso di successo non cambia di una riga.

- [ ] **Step 7: Applicare il fix a `onLocationFix`**

In `core/src/main/java/dev/fred/pacorun/service/RunSessionEventSequencer.kt`:

```kotlin
// PRIMA:
        engineSpeedKmh = null
        coordinator.tick(elapsedRealtimeMillis = now, sample = sample, position = position)

// DOPO:
        engineSpeedKmh = null
        try {
            coordinator.tick(elapsedRealtimeMillis = now, sample = sample, position = position)
        } catch (e: Throwable) {
            pendingEvents.clear()
            throw e
        }
```

- [ ] **Step 8: Applicare il fix a `onTimerTick`**

Stesso file:

```kotlin
// PRIMA:
        val gpsOutage = gpsFreshness.isOutage(now)
        coordinator.advanceTimer(now, signalGpsOutage = gpsOutage)

// DOPO:
        val gpsOutage = gpsFreshness.isOutage(now)
        try {
            coordinator.advanceTimer(now, signalGpsOutage = gpsOutage)
        } catch (e: Throwable) {
            pendingEvents.clear()
            throw e
        }
```

`catch (e: Throwable)`, non `Exception`: l'obiettivo è pulire lo stato interno su QUALUNQUE fallimento (non solo `Exception`), senza mai inghiottirlo — `throw e` rilancia sempre, immediato, nessuna logica condizionale sopra. Non è un cambio di comportamento osservabile dall'esterno (l'eccezione si propaga identica a oggi verso `:android-connector`); è solo una garanzia in più sullo stato interno del Sequencer per la chiamata successiva, che oggi (nel Service reale) non esiste.

**Nessun nuovo test dedicato**: innescare una vera eccezione da un motore interno reale richiederebbe iniettare dipendenze che il costruttore del Sequencer non accetta (tutti i cinque motori sono costruiti internamente, non parametrizzabili) — aggiungere quel punto di iniezione solo per questo test sarebbe uno scope creep sproporzionato rispetto a un fix di 4 righe, verificabile a vista. Se in futuro emergesse un percorso reale e riproducibile che fa lanciare un motore interno, vale la pena aggiungere un test mirato allora.

- [ ] **Step 9: Verificare**

```bash
./gradlew :core:test --rerun-tasks
```
Expected: BUILD SUCCESSFUL, tutti gli 11 test di `RunSessionEventSequencerTest.kt` passano ancora (il fix non tocca il percorso di successo) più il resto della suite di `:core`.

- [ ] **Step 10: Commit**

```bash
git add core/src/main/java/dev/fred/pacorun/service/RunSessionEventSequencer.kt
git commit -m "fix: clear pendingEvents on internal engine exception in RunSessionEventSequencer"
```

### Seconda correzione post-review: `lastTimerSecond` non viene ripristinato in caso di eccezione

Trovata dalla revisione avversariale sul fix appena sopra (Step 7-10) — stessa classe di difetto, un secondo punto che il primo fix non copriva. Verificata contro il codice reale, confermata preesistente anche questa (l'ordine `lastTimerSecond = second` → chiamata non protetta era identico prima di qualunque fix di questo documento).

`onTimerTick` scrive `lastTimerSecond = second` (riga 101) **prima** del `try/catch` che avvolge `coordinator.advanceTimer(...)`. Se `advanceTimer` lancia, il `catch` pulisce `pendingEvents` e rilancia — ma `lastTimerSecond` resta aggiornato al secondo appena fallito. Scenario concreto: un doppio fire del timer nello stesso secondo (il caso per cui esiste il guard di dedup a riga 100) — se il primo tentativo in quel secondo lancia, il secondo tentativo trova `second == lastTimerSecond` e ritorna silenziosamente `TimerTickResult(emptyList(), false)`: nessun retry, quel tick è perso per sempre (non solo rimandato), perché il guard crede — a torto — che il secondo sia già stato processato con successo.

`lastDeviceHealthBucket` (scritto solo *dopo* il try/catch) non ha questo problema — nessuna azione lì. `gpsFreshness.record(sample)`/`gpsSilentGap.recordCallback(now)` in `onLocationFix` (scritti prima del loro try/catch) nemmeno: sono registrazioni fattuali ("questo callback è avvenuto a tempo X"), non guardiani di completamento come `lastTimerSecond` — corrette a prescindere dall'esito del motore interno, non serve rollback.

- [ ] **Step 11: Applicare il fix**

In `core/src/main/java/dev/fred/pacorun/service/RunSessionEventSequencer.kt`, `onTimerTick`:

```kotlin
// PRIMA:
    fun onTimerTick(now: Long): TimerTickResult {
        val second = now / TICK_MILLIS
        if (second == lastTimerSecond) return TimerTickResult(emptyList(), false)
        lastTimerSecond = second

        val gpsOutage = gpsFreshness.isOutage(now)
        try {
            coordinator.advanceTimer(now, signalGpsOutage = gpsOutage)
        } catch (e: Throwable) {
            pendingEvents.clear()
            throw e
        }

// DOPO:
    fun onTimerTick(now: Long): TimerTickResult {
        val second = now / TICK_MILLIS
        if (second == lastTimerSecond) return TimerTickResult(emptyList(), false)
        val previousTimerSecond = lastTimerSecond
        lastTimerSecond = second

        val gpsOutage = gpsFreshness.isOutage(now)
        try {
            coordinator.advanceTimer(now, signalGpsOutage = gpsOutage)
        } catch (e: Throwable) {
            lastTimerSecond = previousTimerSecond
            pendingEvents.clear()
            throw e
        }
```

Il resto del metodo (dal `val buffered = ...` in poi) non cambia.

- [ ] **Step 12: Verificare**

```bash
./gradlew :core:test --rerun-tasks
```
Expected: BUILD SUCCESSFUL, tutti gli 11 test di `RunSessionEventSequencerTest.kt` passano ancora (il percorso di successo non cambia).

- [ ] **Step 13: Commit**

```bash
git add core/src/main/java/dev/fred/pacorun/service/RunSessionEventSequencer.kt
git commit -m "fix: also roll back lastTimerSecond on internal engine exception in onTimerTick"
```

---

## Task 9: `RunSessionHostConfig` + riscrittura/spostamento di `RunSessionService` in `:android-connector`

Spec: "Interfacce di supporto" (`RunPreferencesSource`/`RunSessionHostConfig`), "Ciclo di vita del campo che sostituisce `coordinator`", "Data flow" Percorso A/B, "Ordine di migrazione" passo 6, "Piano di test" casi 1, 5 (parte di ordinamento), 10.

Questo task chiude l'estrazione: sposta `RunSessionService.kt` per intero in `:android-connector`, lo riscrive per delegare a `RunSessionEventSequencer`, introduce `RunSessionHostConfig` e la classe `Application` di `:app`, e aggiunge i 2 test che restano (casi 1 e 10) — entrambi a livello `:android-connector`/`Service`, quindi **strumentati** (`androidTest`), non JVM puri: costruire e guidare un `android.app.Service` reale richiede un device/emulatore o Robolectric, e questo progetto non ha Robolectric configurato oggi. Si introduce **una sola** nuova dipendenza di test minima e standard per questo — `androidx.test:rules` (`ServiceTestRule`, lo strumento AndroidX ufficiale per esattamente questo caso, non un framework nuovo) — giustificata dal fatto che quest'area è esplicitamente "il rischio più grave descritto in questo intero documento" (spec, caso 10) e merita una rete di sicurezza reale, non solo una verifica manuale.

**Files:**
- Create: `android-connector/src/main/java/dev/fred/pacorun/service/RunSessionHostConfig.kt`
- Create: `app/src/main/java/dev/fred/pacorun/PacoRunApplication.kt`
- Move: `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt` → `android-connector/.../service/RunSessionService.kt` (con riscrittura)
- Modify: `app/src/main/AndroidManifest.xml`
- Move: `app/src/main/res/drawable/ic_run.xml` (o l'estensione reale) → `android-connector/src/main/res/drawable/`
- Create: `android-connector/src/androidTest/java/dev/fred/pacorun/service/RunSessionServiceTest.kt`
- Modify: `android-connector/build.gradle.kts`, `app/build.gradle.kts`

**Interfaces:**
- Produces: `interface RunSessionHostConfig { fun runPreferencesSource(): RunPreferencesSource; val notificationTitle: String; val gitCommitHash: String }`, `class PacoRunApplication : Application(), RunSessionHostConfig`.
- Consumes: `RunSessionEventSequencer` (Task 8), `RunPreferencesSource` (Task 6), `GnssSnapshot`/`LocationReliability` (Task 7), `LocationSampleMapper` (Task 6).

- [ ] **Step 1: Trovare l'estensione reale dell'icona notifica prima di spostarla**

```bash
ls app/src/main/res/drawable/ic_run*
```
Usare l'estensione trovata (probabilmente `.xml`, un vector drawable) nel comando `git mv` dello Step successivo — non assumerla.

- [ ] **Step 2: Creare `RunSessionHostConfig.kt`**

```kotlin
package dev.fred.pacorun.service

interface RunSessionHostConfig {
    fun runPreferencesSource(): RunPreferencesSource
    val notificationTitle: String
    val gitCommitHash: String
}
```

`gitCommitHash` è bundlato qui per lo stesso motivo di `notificationTitle` (spec, righe 489-491: entrambi obbligatori per ogni modulo, non una capability opzionale): `RunSessionService.beginTracking()` (Task 9) costruisce `SessionStarted(..., appGitCommitHash = ...)` e ha bisogno di questo valore, ma il meccanismo che lo produce (`GitCommitHashValueSource`, `buildConfigField` in `app/build.gradle.kts`) esiste oggi solo in `:app` — **non** si replica in `:android-connector` (duplicherebbe la stessa infrastruttura Gradle in due moduli per lo stesso identico valore): si passa invece attraverso `hostConfig`, che `:app` può popolare con il proprio `BuildConfig.GIT_COMMIT_HASH` già corretto.

- [ ] **Step 3: Creare `PacoRunApplication.kt` in `:app`**

```kotlin
package dev.fred.pacorun

import android.app.Application
import dev.fred.pacorun.service.RunPreferencesSource
import dev.fred.pacorun.service.RunSessionHostConfig
import dev.fred.pacorun.settings.RunPreferencesStore

class PacoRunApplication : Application(), RunSessionHostConfig {
    override fun runPreferencesSource(): RunPreferencesSource {
        val store = RunPreferencesStore(this)
        return object : RunPreferencesSource {
            override suspend fun current() = store.preferences.first()
        }
    }

    override val notificationTitle: String get() = "Paco Run"
    override val gitCommitHash: String get() = BuildConfig.GIT_COMMIT_HASH
}
```

`BuildConfig` qui è `dev.fred.pacorun.BuildConfig` — il `BuildConfig` di `:app` (già corretto, `buildFeatures.buildConfig = true` e `buildConfigField("String", "GIT_COMMIT_HASH", ...)` sono già presenti in `app/build.gradle.kts` da prima di questa estrazione) — nessun import esplicito necessario, `BuildConfig` è generato nello stesso package `dev.fred.pacorun` di questo file.

Aggiungere l'import mancante `kotlinx.coroutines.flow.first` in cima al file.

- [ ] **Step 4: Aggiungere l'attributo `android:name` al manifest di `:app`**

In `app/src/main/AndroidManifest.xml`:

```xml
<!-- PRIMA: -->
<application
    android:allowBackup="true"
    ...>

<!-- DOPO: -->
<application
    android:name=".PacoRunApplication"
    android:allowBackup="true"
    ...>
```

E cambiare la dichiarazione del `<service>` per usare il nome pienamente qualificato (`RunSessionService` non vive più nel package di `:app`):

```xml
<!-- PRIMA: -->
<service
    android:name=".service.RunSessionService"
    android:exported="false"
    android:foregroundServiceType="location|mediaPlayback" />

<!-- DOPO: -->
<service
    android:name="dev.fred.pacorun.service.RunSessionService"
    android:exported="false"
    android:foregroundServiceType="location|mediaPlayback" />
```

- [ ] **Step 5: Spostare l'icona notifica**

```bash
mkdir -p android-connector/src/main/res/drawable
git mv app/src/main/res/drawable/ic_run.<estensione-trovata-allo-Step-1> android-connector/src/main/res/drawable/
```

- [ ] **Step 6: Muovere `RunSessionService.kt`**

```bash
git mv app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt android-connector/src/main/java/dev/fred/pacorun/service/RunSessionService.kt
```

- [ ] **Step 7: Riscrivere `RunSessionService.kt` per delegare al Sequencer**

Sostituire l'intero contenuto del file (package invariato: `dev.fred.pacorun.service`) con:

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
import dev.fred.pacorun.audio.CuePlayer
import dev.fred.pacorun.audio.toAudioCue
import dev.fred.pacorun.connector.R
import dev.fred.pacorun.eventlog.AppLifecycleChangedEvent
import dev.fred.pacorun.eventlog.LifecycleTransition
import dev.fred.pacorun.eventlog.NetworkType
import dev.fred.pacorun.eventlog.SatelliteGeometry
import dev.fred.pacorun.eventlog.SensorAvailability
import dev.fred.pacorun.eventlog.SessionEvent
import dev.fred.pacorun.eventlog.SessionStarted
import dev.fred.pacorun.eventlog.ThermalStatus
import dev.fred.pacorun.export.GpxExporter
import dev.fred.pacorun.export.GpxWriter
import dev.fred.pacorun.export.TrackPoint
import dev.fred.pacorun.location.LocationSample
import dev.fred.pacorun.location.LocationSampleMapper
import dev.fred.pacorun.settings.RunPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.TimeUnit

class RunSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var notificationManager: NotificationManager
    private lateinit var hostConfig: RunSessionHostConfig
    private var cuePlayer: CuePlayer? = null
    private var sequencer: RunSessionEventSequencer? = null
    private var locationRequested = false
    private var starting = false
    private var sessionToken = 0L
    private var lastLocationElapsedRealtimeNanos = Long.MIN_VALUE
    private val notificationTracker = SessionNotificationTracker()
    private val trackPoints = mutableListOf<TrackPoint>()

    private var sessionStartedAtElapsedMillis: Long = 0L
    private var sessionStartedAtInstant: Instant = Instant.EPOCH
    private var eventLogger: SessionEventLogger? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var lastGnssSnapshot: GnssSnapshot? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tickTimer(SystemClock.elapsedRealtime())
            handler.postDelayed(this, RunSessionEventSequencer.TICK_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        hostConfig = application as RunSessionHostConfig
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_START -> if (!starting && sequencer == null) startSession()
            ACTION_APP_FOREGROUNDED ->
                dispatch(AppLifecycleChangedEvent(SystemClock.elapsedRealtime(), LifecycleTransition.FOREGROUNDED))
            ACTION_APP_BACKGROUNDED ->
                dispatch(AppLifecycleChangedEvent(SystemClock.elapsedRealtime(), LifecycleTransition.BACKGROUNDED))
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
            val preferences = hostConfig.runPreferencesSource().current()
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
        lastGnssSnapshot = null

        eventLogger = SessionEventLogger(applicationContext)
        eventLogger?.start(GpxExporter.eventLogFileNameFor(sessionStartedAtInstant))

        val marker = restartMarkerPrefs()
        if (marker.getBoolean(KEY_SESSION_OPEN, false)) {
            dispatch(AppLifecycleChangedEvent(sessionStartedAtElapsedMillis, LifecycleTransition.SERVICE_RESTARTED_BY_SYSTEM))
        }
        marker.edit().putBoolean(KEY_SESSION_OPEN, true).apply()

        dispatch(
            SessionStarted(
                elapsedRealtimeMillis = sessionStartedAtElapsedMillis,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                osVersionRelease = Build.VERSION.RELEASE,
                osApiLevel = Build.VERSION.SDK_INT,
                appGitCommitHash = hostConfig.gitCommitHash,
                preferences = preferences,
                sensors = sensorAvailability(),
                networkTypeAtStart = currentNetworkType(),
            ),
        )

        sequencer = RunSessionEventSequencer(
            preferences = preferences,
            onCues = { cues -> cuePlayer?.enqueue(cues.map { it.toAudioCue() }, preferences.cueVolume) },
            publish = ::publish,
            sessionStartedAtElapsedMillis = sessionStartedAtElapsedMillis,
        )
        lastLocationElapsedRealtimeNanos = Long.MIN_VALUE
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
                val geometry = mutableListOf<SatelliteGeometry>()
                for (i in 0 until status.satelliteCount) {
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
                lastGnssSnapshot = GnssSnapshot(
                    satellitesUsedInFix = used,
                    cn0MinDbHz = if (used > 0) min else null,
                    cn0AvgDbHz = if (used > 0) sum / used else null,
                    cn0MaxDbHz = if (used > 0) max else null,
                    geometry = geometry,
                )
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
        if (location.elapsedRealtimeNanos <= lastLocationElapsedRealtimeNanos) return
        lastLocationElapsedRealtimeNanos = location.elapsedRealtimeNanos
        val now = SystemClock.elapsedRealtime()

        val mapper = LocationSampleMapper()
        val elapsedRealtimeMillis = TimeUnit.NANOSECONDS.toMillis(location.elapsedRealtimeNanos)
        val speedMps = location.takeIf { it.hasSpeed() }?.speed
        val accuracyMps = location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond
        val horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy
        val ageMillis = now - elapsedRealtimeMillis

        // Fusa: una sola chiamata a evaluate() (oggi ne esistono due, una dentro mapper.map(),
        // una esplicita) -- vedi spec, Data Flow Percorso A.
        val reliability = mapper.evaluate(speedMps, accuracyMps, ageMillis, horizontalAccuracyMeters)
        val sample = LocationSample(
            speedKmh = (speedMps ?: 0f) * 3.6f,
            speedAccuracyKmh = accuracyMps?.times(3.6f),
            reliable = reliability.reliable,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
        )
        val qualityTier = mapper.qualityTier(reliability, accuracyMps, horizontalAccuracyMeters)

        val position = LocationPosition(
            latitude = location.latitude,
            longitude = location.longitude,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            horizontalAccuracyMeters = horizontalAccuracyMeters,
        )

        val result = sequencer?.onLocationFix(
            sample = sample,
            position = position,
            snapshot = lastGnssSnapshot,
            provider = location.provider,
            reliability = reliability,
            qualityTier = qualityTier,
            now = now,
        ) ?: return

        trackPoints += TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timeEpochMillis = location.time,
            speedMps = speedMps,
            horizontalAccuracyMeters = horizontalAccuracyMeters,
            speedAccuracyMps = accuracyMps,
            reliable = sample.reliable,
            speedEstimatorKmh = result.engineSpeedKmh,
            elapsedRealtimeMillis = position.elapsedRealtimeMillis,
        )
        result.events.forEach(::dispatch)
    }

    private fun tickTimer(elapsedRealtimeMillis: Long) {
        val result = sequencer?.onTimerTick(elapsedRealtimeMillis) ?: return
        val events = if (result.deviceHealthSampleDue) {
            val healthEvent = sequencer?.recordDeviceHealthSample(
                batteryPercent = currentBatteryPercent(),
                thermalStatus = currentThermalStatus(),
                now = elapsedRealtimeMillis,
            )
            listOfNotNull(healthEvent) + result.events
        } else {
            result.events
        }
        events.forEach(::dispatch)
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

    private fun dispatch(event: SessionEvent) {
        eventLogger?.log(event)
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
        sessionToken++
        handler.removeCallbacks(ticker)
        if (locationRequested) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationRequested = false
        }
        sequencer = null
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

    private fun locationRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, RunSessionEventSequencer.TICK_MILLIS)
            .setMinUpdateIntervalMillis(RunSessionEventSequencer.TICK_MILLIS)
            .setMaxUpdateDelayMillis(RunSessionEventSequencer.TICK_MILLIS)
            .setWaitForAccurateLocation(true)
            .build()

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_run)
        .setContentTitle(hostConfig.notificationTitle)
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

    companion object {
        const val ACTION_START = "dev.fred.pacorun.action.START_RUN_SESSION"
        const val ACTION_STOP = "dev.fred.pacorun.action.STOP_RUN_SESSION"
        const val ACTION_APP_FOREGROUNDED = "dev.fred.pacorun.action.APP_FOREGROUNDED"
        const val ACTION_APP_BACKGROUNDED = "dev.fred.pacorun.action.APP_BACKGROUNDED"
        private const val CHANNEL_ID = "run_session"
        private const val NOTIFICATION_ID = 1
        private const val KEY_SESSION_OPEN = "session_open"
    }
}
```

**Nota — un punto genuinamente incerto da verificare dopo lo spostamento, non assumerlo:**
`dev.fred.pacorun.connector.R`: il namespace di `:android-connector` è `dev.fred.pacorun.connector` (Task 1) — verificare con `./gradlew :android-connector:compileDebugKotlin` che la classe `R` generata sia effettivamente in quel package (dipende dal `namespace` in `android-connector/build.gradle.kts`). (`BuildConfig.GIT_COMMIT_HASH` **non** è più un punto incerto: risolto sopra passando `hostConfig.gitCommitHash` invece di importare `BuildConfig` di `:android-connector` — quel modulo non ha bisogno di `buildFeatures.buildConfig` per questo motivo.)

Un secondo punto, non incerto ma da tenere a mente: `dispatch()` oggi non ha più il ramo "bufferizza vs scrivi" di `onEvent` — `dispatch()` chiama sempre e solo `eventLogger?.log(event)`, perché il buffering è ora interamente responsabilità del Sequencer (Task 8) — questo è INTENZIONALE, non un buco: la vecchia `onEvent` bufferizzava per riordinare gli eventi PRIMA che arrivassero al logger; ora il Sequencer restituisce gli eventi già ordinati, quindi `dispatch()` può limitarsi a scriverli in ordine.

- [ ] **Step 8: Aggiungere `play-services-location` a `android-connector/build.gradle.kts`**

`RunSessionService.kt`, appena riscritto allo Step 7, importa `com.google.android.gms.location.FusedLocationProviderClient`/`LocationCallback`/`LocationRequest`/`LocationResult`/`LocationServices`/`Priority` — nessun task precedente ha mai aggiunto questa libreria a `:android-connector` (Task 6 Step 2 ha aggiunto solo `media3-exoplayer`, per `CuePlayer.kt`; questa dipendenza è rimasta dichiarata solo in `app/build.gradle.kts` fino ad ora). Senza questa riga il source set principale di `:android-connector` non compila da questo punto in poi:

```kotlin
dependencies {
    // ... resto invariato
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
```

- [ ] **Step 9: Aggiornare `app/build.gradle.kts` — rimuovere `play-services-location`/`media3-exoplayer` se non più usati direttamente da `:app`**

Verificare con `grep -rn "com.google.android.gms.location\|androidx.media3" app/src/main/java/` — se zero risultati (tutto il codice che li usava si è spostato in `:android-connector`), rimuovere le due righe `implementation("com.google.android.gms:play-services-location:...")` e `implementation("androidx.media3:media3-exoplayer:...")` da `app/build.gradle.kts` (ora dichiarate in `android-connector/build.gradle.kts` — `media3-exoplayer` dal Task 6, `play-services-location` dallo Step 8 appena sopra — da cui `:app` le eredita transitivamente per compilare; verificare che `:app` compili comunque dopo la rimozione).

- [ ] **Step 10: Aggiungere `androidx.test:rules` per `ServiceTestRule`**

In `android-connector/build.gradle.kts`:

```kotlin
dependencies {
    // ... resto invariato
    androidTestImplementation("androidx.test:rules:1.7.0")
}
```

(stessa versione di `androidxTest`/`androidxTestRunner`, `1.7.0` — già nel catalogo per `androidx.test:core-ktx`/`androidx.test:runner`, **non** `androidxTestExt`, che nel catalogo è `1.3.0`, un numero diverso per un artefatto diverso — non è un numero nuovo inventato, verificare comunque contro `androidx.test:rules`'s pagina release che `1.7.0` esista davvero prima di eseguire la build, dato che non è ancora usata da nessuna parte in questo repo.)

- [ ] **Step 11: Scrivere il test strumentato per il caso 1 (dedup nanosecondo) e il caso 10 (ciclo di vita di `sequencer`)**

```kotlin
package dev.fred.pacorun.service

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunSessionServiceTest {
    @get:Rule
    val serviceRule: ServiceTestRule = ServiceTestRule()

    // Caso 10: il rischio più grave del documento -- il ciclo di vita del campo `sequencer`.
    // Senza l'azzeramento esplicito in stopSession(), una seconda sessione non partirebbe MAI:
    // il guard onStartCommand `if (!starting && sequencer == null) startSession()` resterebbe
    // permanentemente falso dopo la prima sessione.
    @Test
    fun `a second session starts after the first one is stopped`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()

        val startIntent = Intent(context, RunSessionService::class.java).setAction(RunSessionService.ACTION_START)
        serviceRule.startService(startIntent)
        Thread.sleep(500) // lascia che startSession()/beginTracking() (coroutine) completino

        val stopIntent = Intent(context, RunSessionService::class.java).setAction(RunSessionService.ACTION_STOP)
        serviceRule.startService(stopIntent)
        Thread.sleep(500)

        // Se `sequencer` non fosse stato azzerato in stopSession(), questa seconda ACTION_START
        // non chiamerebbe mai startSession() di nuovo -- non c'è modo diretto di leggere il
        // campo privato `sequencer` da qui, quindi la prova osservabile è che RunSessionStore
        // torni in AcquiringGps/Running dopo questo secondo avvio, non resti Idle.
        val secondStartIntent = Intent(context, RunSessionService::class.java).setAction(RunSessionService.ACTION_START)
        serviceRule.startService(secondStartIntent)
        Thread.sleep(500)

        assertNotNull(
            "a second ACTION_START after stopSession() must re-enter startSession(), " +
                "observable as RunSessionStore leaving Idle",
            RunSessionStore.state.value.takeIf { it != RunSessionState.Idle },
        )
    }
}
```

**Nota per l'implementatore:** questo test richiede il permesso `ACCESS_FINE_LOCATION` concesso sul device/emulatore di test (altrimenti `startSession()` chiama `stopSession()` e torna sempre a `Idle`, facendo fallire l'assert per il motivo sbagliato) — concederlo via `GrantPermissionRule` se necessario, o via `adb shell pm grant` prima di eseguire la suite. Verificare a mano, eseguendo il test, che il fallimento (se capita) sia per il motivo giusto (guard rotto) e non per un permesso mancante prima di fidarsi del risultato PASS o FAIL.

Il caso 1 (dedup nanosecondo) non ha un test dedicato scritto qui perché richiede di iniettare `Location` sintetici nel `locationCallback` privato del Service — non esposto pubblicamente, e instrumentarlo bene servirebbe un refactor di visibilità che la spec non richiede. Verificarlo manualmente (guardare i log della sessione con due fix ravvicinati con lo stesso `elapsedRealtimeNanos`, confermare che solo il primo produce un `LocationFixEvent`) è accettabile per questo singolo caso — annotarlo esplicitamente come verifica manuale nel report finale del task, non come test automatico mancante per trascuratezza.

Stesso trattamento per la metà di caso 5 non ancora coperta da un test automatico: Task 8 verifica cadenza e contenuto di `deviceHealthSampleDue`/`recordDeviceHealthSample(...)` a livello di Sequencer, ma la parte "posizionale" del caso 5 (`DeviceHealthSampledEvent` deve comparire PRIMA degli eventi di routine/interruzione GPS dello stesso tick nel flusso dispatchato) vive nel corpo di `tickTimer()` qui sopra (`listOfNotNull(healthEvent) + result.events`) — un one-liner ispezionabile a vista, non nascosto. Automatizzarlo servirebbe far leva sullo stesso `ServiceTestRule` del caso 10 (nessun modo più leggero di osservare l'ordine reale scritto da `eventLogger`, che scrive su file `MediaStore`) — accettabile come verifica per ispezione del codice invece che con un test dedicato, per lo stesso motivo del caso 1: annotarlo esplicitamente nel report finale, non lasciarlo un buco silenzioso.

- [ ] **Step 12: Verificare tutta la build**

```bash
./gradlew :core:test :android-connector:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. Poi, se possibile su un device/emulatore reale:
```bash
./gradlew :android-connector:connectedDebugAndroidTest
```
(hard rule 8 — se non c'è un device disponibile in questo momento, segnalarlo esplicitamente all'utente invece di saltare la verifica in silenzio.)

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "refactor: rewrite RunSessionService to delegate to RunSessionEventSequencer, move to :android-connector"
```

### Correzione post-review (trovata dalla review sull'intero branch, dopo il Task 10): il test del Caso 10 non può passare così com'è, più un overload morto rimasto

Trovato da una revisione avversariale sull'intero diff `main..feature/core-extraction` preso come blocco unico (non dalla review per-task) — verificato indipendentemente generando il manifest merge reale di `:android-connector:androidTest` e ispezionandolo.

**Il problema (serio):** `RunSessionServiceTest.kt` (Step 11, caso 10 — "il rischio più grave del documento" per lo spec stesso) chiama `serviceRule.startService(...)`, che fa partire un vero `RunSessionService`. `RunSessionService.onCreate()` fa `hostConfig = application as RunSessionHostConfig`. Ma l'APK di test strumentato di `:android-connector` non ha **nessuna** classe `Application` propria — nessun `testApplicationId`, nessun manifest `androidTest`, nessuna configurazione in `android-connector/build.gradle.kts` la fornisce. Il cast lancia `ClassCastException` prima ancora che la logica del test (il riavvio di sessione) venga mai eseguita. **Secondo motivo di fallimento indipendente**: il test non ha nessun `GrantPermissionRule` — senza il permesso `ACCESS_FINE_LOCATION` concesso, `startSession()` chiamerebbe subito `stopSession()`, facendo fallire il test per il motivo sbagliato anche se il primo problema fosse risolto.

**Il problema minore:** `LocationSampleMapper.kt` (in `:android-connector`) ha ancora l'overload `map(location: Location, nowElapsedRealtimeMillis: Long): LocationSample` — il piano diceva esplicitamente di eliminarlo in questo stesso task ("si elimina di conseguenza l'overload... rimasto senza chiamanti", vedi Allocazione dei file e Step 7 sopra), ma è rimasto. Verificato con grep: zero chiamanti in tutto il repo — codice morto, sicuro da rimuovere.

- [ ] **Step 14: Creare una `Application` di test per `:android-connector`, che implementi `RunSessionHostConfig`**

```kotlin
// android-connector/src/androidTest/java/dev/fred/pacorun/service/TestApplication.kt
package dev.fred.pacorun.service

import android.app.Application
import dev.fred.pacorun.settings.RunPreferences

class TestApplication : Application(), RunSessionHostConfig {
    override fun runPreferencesSource(): RunPreferencesSource = object : RunPreferencesSource {
        override suspend fun current(): RunPreferences = RunPreferences()
    }

    override val notificationTitle: String get() = "Paco Run Test"
    override val gitCommitHash: String get() = "test"
}
```

- [ ] **Step 15: Dichiarare la `TestApplication` nel manifest del source set `androidTest`**

```xml
<!-- android-connector/src/androidTest/AndroidManifest.xml (nuovo file) -->
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:name="dev.fred.pacorun.service.TestApplication" />
</manifest>
```

Nome pienamente qualificato, non relativo (`dev.fred.pacorun.service.TestApplication`, non `.TestApplication`) — il `namespace` di `:android-connector` è `dev.fred.pacorun.connector` (Task 1), diverso dal package `dev.fred.pacorun.service` dove vive questa classe; un nome relativo risolverebbe contro il namespace sbagliato (stesso tipo di errore già incontrato con `R`/`BuildConfig` in questo modulo).

- [ ] **Step 16: Aggiungere `GrantPermissionRule` a `RunSessionServiceTest.kt`**

```kotlin
// PRIMA:
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunSessionServiceTest {
    @get:Rule
    val serviceRule: ServiceTestRule = ServiceTestRule()

// DOPO:
import android.Manifest
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunSessionServiceTest {
    @get:Rule
    val serviceRule: ServiceTestRule = ServiceTestRule()

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
```

`GrantPermissionRule` viene dallo stesso artefatto già dichiarato al Task 9 Step 10 (`androidx.test:rules:1.7.0`) — nessuna nuova dipendenza Gradle.

- [ ] **Step 17: Rimuovere l'overload morto da `LocationSampleMapper.kt`**

```kotlin
// RIMUOVERE (l'intera funzione):
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
```

Rimuovere anche, se restano inutilizzati dopo questa rimozione (verificare con grep sul file, non assumere): `import android.location.Location`, `import java.util.concurrent.TimeUnit`.

- [ ] **Step 18: Verificare**

```bash
./gradlew build --rerun-tasks
./gradlew :android-connector:compileDebugAndroidTestKotlin --rerun-tasks
```
Expected: `BUILD SUCCESSFUL` su entrambi. **Non è possibile verificare che il test del Caso 10 passi davvero senza un device/emulatore reale** — questo step verifica solo che tutto compili; l'esecuzione resta manuale, a cura dell'utente (`./gradlew :android-connector:connectedDebugAndroidTest`, hard rule 8).

- [ ] **Step 19: Commit**

```bash
git add android-connector/src/androidTest/java/dev/fred/pacorun/service/TestApplication.kt android-connector/src/androidTest/AndroidManifest.xml android-connector/src/androidTest/java/dev/fred/pacorun/service/RunSessionServiceTest.kt android-connector/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt
git commit -m "fix: provide a test Application for :android-connector's androidTest, grant location permission in RunSessionServiceTest, remove dead LocationSampleMapper.map(Location) overload"
```

### Seconda correzione post-review sullo stesso fix: il nome del test con backtick e spazi impedisce il dexing, l'APK di test non si può nemmeno costruire

Trovata dalla revisione avversariale sul fix precedente (Step 14-19) — questa volta con un test genuinamente più severo del solo `compileDebugAndroidTestKotlin`: `./gradlew :android-connector:assembleDebugAndroidTest` (che include il dexing, non solo la compilazione Kotlin/JVM) fallisce con:

```
D8: com.android.tools.r8.internal.ez: Space characters in SimpleName
'a second session starts after the first one is stopped' are not allowed
prior to DEX version 040 (method name `a second session starts after
the first one is stopped` on class dev.fred.pacorun.service.RunSessionServiceTest)
```

**Verificato empiricamente** (riprodotto il fallimento, poi applicato il fix e confermato `BUILD SUCCESSFUL` con un vero APK di test impacchettato): il nome del metodo di test in stile backtick con spazi (`` fun `a second session starts after the first one is stopped`() ``) — uno stile comune nei test JVM puri di questo stesso progetto (`:core`/`:android-connector`'s `testDebugUnitTest`, che non passa mai da un dexer) — non è valido come nome di metodo DEX quando il test deve girare su un device reale (`androidTest`, che *viene* dexato). Nessun altro test strumentato di questo progetto usa questo stile (tutti gli altri file in `androidTest/` usano nomi camelCase semplici, es. `writesAReadableFileToDownloads()`) — è specifico di questo test, introdotto per la prima volta al Task 9. La verifica del Task 9 originale (`compileDebugAndroidTestKotlin`) e la prima correzione post-review (stesso comando) non l'avevano mai preso, perché il dexing è un passo successivo, mai eseguito fino ad ora.

- [ ] **Step 20: Rinominare il metodo di test**

In `android-connector/src/androidTest/java/dev/fred/pacorun/service/RunSessionServiceTest.kt`:

```kotlin
// PRIMA:
    @Test
    fun `a second session starts after the first one is stopped`() {

// DOPO:
    @Test
    fun secondSessionStartsAfterFirstIsStopped() {
```

Il commento esplicativo sopra il metodo (che già descrive il Caso 10 per intero) resta invariato — il nome del metodo non è l'unica documentazione di cosa testa, quindi accorciarlo in camelCase non perde informazione.

- [ ] **Step 21: Verificare — stavolta con il dexing incluso, non solo la compilazione**

```bash
./gradlew :android-connector:assembleDebugAndroidTest --rerun-tasks
```
Expected: `BUILD SUCCESSFUL`, con un vero file APK di test prodotto (non solo classi compilate). Questo è il comando giusto per verificare che l'APK di test sia davvero costruibile — `compileDebugAndroidTestKotlin` da solo, usato nelle verifiche precedenti, non basta: non include il dexing, che è dove questo problema si è manifestato.

- [ ] **Step 22: Commit**

```bash
git add android-connector/src/androidTest/java/dev/fred/pacorun/service/RunSessionServiceTest.kt
git commit -m "fix: rename backtick-with-spaces test method name to avoid a DEX SimpleName error"
```

### Terza correzione post-review: la prima esecuzione reale su device (non solo compilazione/dexing) del Caso 10, tre problemi reali trovati in sequenza

Fino a questo punto, ogni verifica del branch (Task 1-10, tutte le correzioni sopra) aveva controllato solo compilazione/packaging/dexing — mai l'esecuzione vera su un device. Il 2026-09-29, su richiesta esplicita dell'owner ("usiamo l'emulatore, se basta per vedere non perdiamo tempo"), `./gradlew :android-connector:connectedDebugAndroidTest` è stato eseguito per la prima volta contro un emulatore reale (`Pixel_9a_API_37`, API 37). Tre problemi reali sono emersi in sequenza, ciascuno mascherato dal precedente finché non risolto:

1. **`RunSessionService` non dichiarato nel manifest dell'APK di test isolato di `:android-connector`.** L'APK di test di questo modulo unisce solo `android-connector/src/main/AndroidManifest.xml` + `android-connector/src/androidTest/AndroidManifest.xml` — non include il manifest di `:app`, unico posto dove il `<service>` era dichiarato fino a questo momento. Fix iniziale: `<service>` aggiunto (duplicato a mano, stessi attributi) in `android-connector/src/androidTest/AndroidManifest.xml`.
2. **`SecurityException: ... requires android.permission.FOREGROUND_SERVICE`.** `RunSessionService.startSession()` chiama `startForeground(..., foregroundServiceType = location|mediaPlayback)`; i permessi `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` erano dichiarati solo in `app/src/main/AndroidManifest.xml`, mai raggiunti dall'APK di test isolato. Fix: le tre righe aggiunte a `android-connector/src/main/AndroidManifest.xml` — non solo al manifest androidTest, perché sono una proprietà del codice della libreria (chi chiama `startForeground` con quel `foregroundServiceType`), stesso principio già applicato ai permessi di localizzazione al Task 9/10.
3. **`TimeoutException: ... service was never connected` in `ServiceTestRule.startService(...)`.** Causa: `ServiceTestRule.startService(Intent)` fa `bindService()` e aspetta `onServiceConnected()` — ma `RunSessionService.onBind()` (riga 112) ritorna sempre `null` per design (è un servizio avviato/foreground, mai pensato per il binding); l'attesa va deterministicamente in timeout, indipendentemente da manifest o permessi. Fix: `RunSessionServiceTest.kt` riscritto per non usare più `ServiceTestRule` — le tre chiamate diventano `context.startForegroundService(...)`/`context.startService(...)` dirette, mirror esatto di come `PacoRunApp.kt` (righe 91/108) avvia/ferma lo stesso servizio in produzione; aggiunto un `@After fun tearDown()` con `context.stopService(...)` per non lasciare il servizio in esecuzione a fine test (perso il cleanup automatico di `ServiceTestRule`).

Dopo il terzo fix: **9/9 test passano in `:android-connector`** (incluso il Caso 10, `secondSessionStartsAfterFirstIsStopped`) **e 13/13 in `:app`**, confermato dai report JUnit XML grezzi, non solo dal log console — prima conferma reale su device di questo branch.

**Revisione avversariale su questi tre fix** ha trovato un problema architetturale genuino nel fix #1: il `<service>` finiva duplicato a mano in due manifest indipendenti (`app/src/main/AndroidManifest.xml` e `android-connector/src/androidTest/AndroidManifest.xml`), senza nulla che li tenga sincronizzati — coerenza verificata leggendo il manifest *unito* reale già generato dalla build: i permessi di `:android-connector` si propagano già automaticamente a `:app` via merge di AGP, quindi lo stesso meccanismo funziona anche per un `<service>` dichiarato una sola volta nel manifest `main` della libreria (le librerie Android possono dichiarare componenti applicativi propri, dentro un `<application>` proprio — è così che ogni SDK di terze parti registra il proprio `Service`/`Receiver`). Consolidato e verificato: `<service>` spostato in `android-connector/src/main/AndroidManifest.xml` (dentro un `<application>` proprio del modulo), rimosso sia da `app/src/main/AndroidManifest.xml` sia da `android-connector/src/androidTest/AndroidManifest.xml`. Coerente con un precedente già presente in questo branch (commit `7a179ce`, che rimosse `ACCESS_NETWORK_STATE` duplicato da `:app` per lo stesso motivo). Verificato indipendentemente: `./gradlew build` pulito, lint di entrambi i moduli senza alcun `MissingPermission`/`ForegroundServicePermission`/`MissingForegroundServiceType`, manifest unito reale di `:app` (`app/build/intermediates/merged_manifest*/debug/*/AndroidManifest.xml`) ispezionato direttamente e contiene il `<service>` propagato da `:android-connector`, 9/9 test su `:android-connector` + 13/13 su `:app` passano su device reale (incluso `ForegroundServiceManifestTest`, ulteriore conferma indipendente che la propagazione funziona anche a runtime via `PackageManager`).

**Nota minore, non affrontata (deliberatamente, YAGNI finché non si applica davvero):** il nuovo `tearDown()` chiama `context.stopService(...)` senza attendere che `onDestroy()`/`stopSession()` abbiano davvero finito — oggi innocuo (`RunSessionServiceTest` ha un solo `@Test`, il processo di test termina subito dopo), ma se in futuro questa classe guadagnasse un secondo metodo di test, quel secondo test potrebbe partire mentre `RunSessionStore` (singleton di processo) sta ancora pubblicando stato residuo del test precedente — una race silenziosa, non oggi osservabile. **Se si aggiunge un secondo `@Test` a questa classe**, sostituire lo `stopService()` fire-and-forget con un poll su `RunSessionStore.state` fino a `Idle` (con timeout), prima di procedere.

---

## Task 10: Verifica finale e chiusura

**Files:** nessuno (solo verifica).

- [ ] **Step 1: Suite completa**

```bash
./gradlew build
```
Expected: BUILD SUCCESSFUL su `:core`, `:android-connector`, `:app` — build, lint, tutti i test JVM.

- [ ] **Step 2: Verificare che nessun file sia rimasto orfano**

```bash
find app/src/main/java app/src/test/java app/src/androidTest/java -type f -name "*.kt" | sort
```
Confrontare con la tabella "Allocazione dei file" della spec — ogni file di produzione elencato come spostato deve essere assente da questo output; ogni file elencato come "resta `:app`" deve essere presente (`RunPreferencesStore.kt`, `LocationReadiness.kt`, tutto sotto `ui/`, `MainActivity.kt`, `PacoRunApp.kt`, `PacoRunApplication.kt`).

Per i test, confrontare con l'elenco "File di test che restano in `:app`, invariati" della spec. Un file di questo elenco merita una nota esplicita perché referenzia codice spostato senza dover spostarsi lui stesso: `ForegroundServiceManifestTest.kt` (package `dev.fred.pacorun.service`) referenzia `RunSessionService::class.java` — ma resta in `:app` perché verifica la dichiarazione `<service>` nel manifest **di questo modulo specifico** (`android:foregroundServiceType`, permessi), non la logica interna di `RunSessionService`; risolve comunque per nome di classe attraverso `:android-connector` (`:app` dipende da lui dal Task 6) senza bisogno di alcuna modifica.

- [ ] **Step 3: Verificare `:core` a zero dipendenze Android**

```bash
grep -rln "^import android\." core/src/main/java/ core/src/test/java/
```
Expected: nessun risultato. Se qualcosa compare, è un errore da correggere prima di chiudere — non un dettaglio da ignorare (`:core` non deve avere l'SDK Android sul classpath per costruzione, quindi un `import android.*` lì non dovrebbe nemmeno compilare; se compila, la configurazione del modulo stesso è sbagliata).

- [ ] **Step 4: Report all'utente**

Riassumere: numero di file spostati per modulo, numero di test nuovi/modificati, eventuali verifiche manuali ancora pendenti (caso 1, e gli androidTest se non eseguiti su device reale in questa sessione). **Non procedere al merge in `main` né a costruire un APK** — quello richiede il via libera esplicito e separato dell'utente (hard rule 3 e 5).
