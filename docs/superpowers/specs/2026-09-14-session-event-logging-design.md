# Design: logging strutturato di eventi/decisioni di sessione (P1)

**Data:** 2026-09-14. **Branch:** `feature/paco-run-m3e`. **Tipo di documento:** design spec — decisioni di formato/schema/wiring prese e giustificate qui, **nessun codice toccato**, nessun piano di implementazione scritto (quello è un documento separato, successivo). Corrisponde alla voce **P1** della roadmap (`docs/superpowers/specs/2026-09-11-precision-roadmap.md`), segnata lì come "confermato, priorità massima per il prossimo filone di lavoro — prerequisito, non rimandabile".

**Revisione 2026-09-14 (successiva alla stesura iniziale):** applicate 5 correzioni a seguito di una review avversariale indipendente — semantica di `PaceStateConfirmedEvent.fromState` (5.4), asimmetria `DistanceAccumulator`/`SpeedEstimator` nell'osservabilità delle rotture di catena (5.5, con prerequisito di codice per chi scriverà il piano di implementazione), ambiguità sulle transizioni multiple di `RoutineEngine` per singolo tick (Decisione 6), guardia sui numeri non-finiti nel serializzatore JSON (Decisione 3), e strategia di scrittura (Decisione 8, ora incrementale invece che bufferizzata a fine sessione). Dettagli delle singole correzioni nelle sezioni citate.

## Contesto (per chi legge senza aver seguito il progetto)

**Paco Run** è un'app Android per corsa/camminata che dà feedback vocale in tempo reale sul ritmo, confrontando la velocità istantanea misurata dal GPS (`SpeedEstimator`, un filtro di Kalman a due assi) con un range target configurato (`PaceEvaluator`, tre stati confermati: `TOO_SLOW`/`GOOD`/`TOO_FAST`, più `ACQUIRING_GPS`/`GPS_WEAK`). Un secondo componente, `RoutineEngine`, guida routine a intervalli ("Cammina-Corri": alternanza RUN/WALK a durate fisse, con eventuale fase iniziale/finale a ritmo costante).

**Il problema che questo documento risolve non è un bug — è un buco osservativo.** La prima corsa reale col motore Kalman in produzione (2026-09-13) ha mostrato un problema oggettivo di "pace flapping" (`docs/superpowers/specs/2026-09-13-kalman-production-run1-validation.md`), e la sua analisi ha dovuto **ricostruire** cosa l'app aveva realmente deciso — quando i cue erano stati emessi, quando le fasi routine erano cambiate, quale stato di `PaceEvaluator` era attivo in ogni istante — partendo dal solo export GPX grezzo più la configurazione (range di velocità, `confirmationSamples`, ecc.) riferita a voce dal proprietario, mai osservata direttamente. Lo stesso limite è stato segnalato esplicitamente, in modo indipendente, in altri due documenti (`docs/superpowers/specs/2026-09-13-precision-and-cue-stability-research.md`, `docs/superpowers/specs/2026-09-13-phase-aware-cue-smoothing-research.md`): **nessun parametro numerico per il prossimo lavoro (P2 — smoothing di decisione consapevole del cambio fase) può essere scelto con fiducia finché non esiste un log reale delle decisioni**, perché oggi ogni numero candidato sarebbe una stima non verificabile.

**P1 è esattamente e soltanto questo: costruire quel log.** Non implementa nessun fix (quello è P2/P3), è pura osservabilità — un prerequisito per poter scegliere con dati reali, invece che a intuito, qualunque parametro futuro.

**Scala dell'uso previsto, più ampia di "un telefono, un corridore":** dopo questa corsa il proprietario ha iniziato a reclutare altri tester, ha in programma di far correre un secondo telefono Android (al braccio) insieme al primo nella stessa sessione, e uno smartwatch Wear OS (TicWatch Pro 3 GPS, in arrivo, GPS non dual-band, GPS grezzo atteso peggiore di qualunque telefono testato finora) eseguirà in futuro un porting della stessa app. Questo schema di log deve reggere più tester/dispositivi simultanei senza modifiche, e deve essere riusabile **senza modifiche nel dominio Kotlin condiviso** da un futuro build Wear OS — solo lo strato di collegamento Android va riscritto per piattaforma.

## Decisione 1 — un file separato, non dentro l'export GPX

**Già deciso, riportato qui per completezza:** un nuovo file di log, non un'estensione del formato GPX esistente. Motivo concreto: l'export GPX (`GpxWriter.kt`, `TrackPoint`) è un formato **una riga per trackpoint**, cioè un evento per fix GPS. Molti degli eventi richiesti qui — cambio di fase routine, cue emesso, stato di pace confermato, candidato di pace abbandonato, battito di stato salute dispositivo — **non hanno un fix GPS corrispondente**: possono accadere su un tick del timer a 1Hz (`RunSessionService.tickTimer`), durante un'interruzione GPS, o in mezzo a due fix. Forzarli in una riga trackpoint significherebbe o inventare fix GPS falsi, o perdere eventi che cadono tra due fix reali. Un formato eterogeneo per eventi richiede una modellazione diversa da quella un-fix-per-riga del GPX.

## Decisione 2 — formato file: JSON Lines (`.jsonl`), non JSON array né CSV

**Perché non CSV:** gli eventi qui sono un'unione discriminata con forme diverse (un `AudioCueEmitted` ha `cueType`/`cumulativeDistanceMeters`/`speedKmh`; un `RoutinePhaseChanged` ha `fromPhase`/`toPhase`; nessuno schema di colonne fisse li rappresenta entrambi senza colonne quasi sempre vuote o una codifica ad-hoc del tipo di riga — esattamente il problema che il formato GPX-esteso di Decisione 1 avrebbe avuto, spostato di un livello.

**Perché non un singolo array JSON top-level:** dato lo strategy di scrittura incrementale deciso in Decisione 8 (scrittura via via che gli eventi accadono, non un blocco unico a fine sessione), un array JSON ha uno svantaggio concreto che JSON Lines non ha: un'interruzione a metà scrittura (storage pieno, eccezione OEM-specifica, crash del processo) produce un file JSON troncato e **non parsabile nemmeno parzialmente** (manca la `]` di chiusura, spesso anche l'ultimo oggetto è tagliato a metà). Con JSON Lines, ogni riga è un oggetto JSON completo e indipendente: un file troncato resta leggibile riga per riga fino all'ultima riga scritta per intero, l'unica persa è l'ultima se interrotta a metà — e questa proprietà si realizza davvero solo perché la scrittura è incrementale (Decisione 8), non un singolo flush finale. Per un log diagnostico il cui intero scopo è "recuperare quanto più possibile di cosa è successo", questa proprietà non è un dettaglio.

**JSON Lines è anche lo standard de facto per stream di eventi eterogenei tipizzati** (log strutturati, dataset di training, export di sistemi di telemetria) — non è solo la scelta più comoda qui, è quella che qualunque strumento esterno (`jq`, un notebook Python, uno script di analisi come quelli già scritti a mano in questo progetto per le corse precedenti) sa già leggere riga per riga senza scrivere un parser dedicato.

## Decisione 3 — nessuna libreria di serializzazione, JSON scritto a mano

Il progetto non ha oggi **nessuna** dipendenza di serializzazione JSON (verificato in `app/build.gradle.kts`: niente `kotlinx-serialization`, `moshi`, `gson`). Il precedente diretto in questo stesso codebase è `GpxWriter.kt`: un `object` che costruisce XML — un formato più complesso di JSON da scrivere a mano (tag di apertura/chiusura da far corrispondere, namespace) — interamente con `StringBuilder`/`buildString` e un singolo `escapeXml()` esplicito, senza libreria. JSON è strutturalmente più semplice da scrivere a mano di XML (nessun matching di tag, solo valori/liste/oggetti), quindi lo stesso approccio si applica qui altrettanto bene, con lo stesso costo (una funzione `escapeJson()` sulle stringhe, formattazione esplicita di numeri/booleani/null) e senza introdurre una dipendenza nuova per ~12 tipi di evento con campi quasi tutti primitivi. Scelta: un `object SessionEventWriter` in stile identico a `GpxWriter`, una funzione `toJsonLine(event: SessionEvent): String` con un `when` esaustivo sui sottotipi (il compilatore segnala se un nuovo tipo di evento viene aggiunto e dimenticato qui).

**Guardia esplicita sui numeri non-finiti, accanto a `escapeJson()`:** `NaN`/`Infinity` non sono token JSON validi — un serializzatore scritto a mano che non se ne accorge emette una riga non parsabile. Questo non è ipotetico qui: `SpeedEstimatorDiagnostics.innovationEmaMagnitude` (5.2) può legittimamente essere non-finito in casi limite — `SpeedEstimator.accept()` stesso controlla esplicitamente `speed.isFinite()` prima di restituire un valore, proprio per questo motivo. Decisione: ogni campo `Double` serializzato che risulti `NaN` o infinito va scritto come `null` JSON (semanticamente: "nessuna misura valida disponibile"), non come token bareword non quotato e non omesso silenziosamente — stessa disciplina di `escapeJson()` per le stringhe, applicata ai numeri: una piccola funzione, es. `jsonNumber(value: Double): String = if (value.isFinite()) value.toString() else "null"`, usata ovunque un `Double` venga serializzato.

## Decisione 4 — correlazione con l'export GPX esistente, stessa base temporale

Entrambi i file useranno **`elapsedRealtimeMillis`** (`SystemClock.elapsedRealtime()`, monotono, non affetto da correzioni dell'orologio di sistema) come chiave di join — non il timestamp epoch/wall-clock. Questo permette di allineare con precisione al millisecondo un fix GPS nel GPX con qualunque evento discreto nel nuovo log (es. "a che punto esatto del GPX è avvenuto questo cambio di fase").

**Cambiamento richiesto, minimo, nell'export GPX esistente — necessario perché questa decisione funzioni davvero:** oggi `TrackPoint` (`app/src/main/java/dev/fred/pacorun/export/TrackPoint.kt`) porta solo `timeEpochMillis` (da `location.time`, wall-clock), **non** `elapsedRealtimeMillis` — pur essendo quest'ultimo già disponibile nello stesso punto del codice (`RunSessionService.onLocation()` lo calcola già, `TimeUnit.NANOSECONDS.toMillis(location.elapsedRealtimeNanos)`, per costruire `LocationPosition`). Senza aggiungere questo campo al GPX, il join tra i due file richiederebbe convertire wall-clock↔elapsed-realtime, che non è affidabile (l'orologio di sistema può derivare o essere corretto durante la sessione, elapsedRealtime no). **Va aggiunto un campo `pr:elapsedRealtimeMillis` all'estensione GPX esistente** (accanto a `<time>`, non al posto — `<time>` resta per compatibilità con viewer GPX generici/Strava). Cambiamento di una riga in `TrackPoint`/`GpxWriter`/`RunSessionService`, da fare come parte dell'implementazione di P1, non scelto qui nel dettaglio del diff.

**Disciplina "niente log inutile" applicata alla correlazione:** dato questo timestamp condiviso, il nuovo log **non ripete** campi già presenti nel GPX per lo stesso istante (es. `horizontalAccuracyMeters`, `speedAccuracyMps` — chi vuole quei valori per un fix specifico li joina dal GPX via `elapsedRealtimeMillis`). Fa eccezione un solo caso, dichiarato esplicitamente: `LocationFixEvent` porta comunque un proprio `elapsedSinceStartMillis` (vedi schema sotto) anche se è aritmeticamente derivabile da `elapsedRealtimeMillis` meno il timestamp di `SessionStarted` — non è duplicazione di un dato "già pronto" nello stesso file (richiede comunque una sottrazione contro un evento diverso), ed è stato chiesto esplicitamente per rendere il tempo-al-primo-fix-affidabile misurabile con un solo sguardo, senza fare quel join nemmeno per l'analisi più semplice.

## Decisione 5 — lo schema: `SessionEvent`

Package nuovo, Kotlin puro (nessun tipo Android — stesso principio già seguito da `LocationSample`/`PaceConfig`/`RoutineResult`/`RunPreferences`, verificato leggendo i rispettivi file: nessuno importa `android.*`): `dev.fred.pacorun.eventlog`, file `SessionEvent.kt`.

```kotlin
package dev.fred.pacorun.eventlog

sealed interface SessionEvent {
    val elapsedRealtimeMillis: Long
}
```

(stesso pattern di `RunSessionState` in `RunSessionStore.kt`: `sealed interface` con implementazioni `data class`/`data object`, non `sealed class`.)

### 5.1 — Evento one-time di inizio sessione

```kotlin
data class SessionStarted(
    override val elapsedRealtimeMillis: Long,  // l'istante esatto della pressione di "Avvia"
    val deviceModel: String,                    // Build.MANUFACTURER + " " + Build.MODEL
    val osVersionRelease: String,                // Build.VERSION.RELEASE, es. "14"
    val osApiLevel: Int,                         // Build.VERSION.SDK_INT
    val appGitCommitHash: String,                // vedi 6.1
    val preferences: RunPreferences,             // riuso diretto del tipo esistente, zero duplicazione
    val sensors: SensorAvailability,
    val networkTypeAtStart: NetworkType,
) : SessionEvent

data class SensorAvailability(
    val hasBarometer: Boolean,       // Sensor.TYPE_PRESSURE -- informa A2 (roadmap)
    val hasMagnetometer: Boolean,    // Sensor.TYPE_MAGNETIC_FIELD -- informa A1
    val hasAccelerometer: Boolean,   // Sensor.TYPE_ACCELEROMETER -- informa la zona inerziale (I1-I6)
    val hasGyroscope: Boolean,       // Sensor.TYPE_GYROSCOPE -- informa I3
    val hasStepCounter: Boolean,     // Sensor.TYPE_STEP_COUNTER
    val hasStepDetector: Boolean,    // Sensor.TYPE_STEP_DETECTOR
)

enum class NetworkType { WIFI, CELLULAR, OFFLINE, OTHER }
```

`RunPreferences` è già un `data class` Kotlin puro (`settings/RunPreferences.kt`, verificato: nessun import Android) — riusarlo direttamente invece di ricopiarne i campi in un secondo tipo evita di dover tenere sincronizzate due liste di campi ogni volta che `RunPreferences` cambia (include anche campi puramente UI come `dynamicColor`: innocuo da loggare, evita quel rischio di sincronizzazione per un costo pari a zero).

**Perché device model/OS/commit qui e non altrove:** il proprietario ha segnalato esplicitamente l'hash di commit come critico perché l'algoritmo di decisione cambia più volte al giorno durante lo sviluppo attivo — senza saperlo, un log di due sessioni diverse non è confrontabile.

### 5.2 — Evento per ogni fix GPS grezzo

```kotlin
data class LocationFixEvent(
    override val elapsedRealtimeMillis: Long,
    val sequenceNumber: Int,             // dalla prima chiamata della sessione, non dal primo fix affidabile
    val elapsedSinceStartMillis: Long,   // vedi Decisione 4 per perché è esplicito e non solo derivabile
    val provider: String?,               // Location.getProvider() grezzo: "gps"/"fused"/"network"/"passive"/null
    val reliable: Boolean,
    val rejectionReasons: List<RejectionReason>,   // vuota se reliable == true
    val satellitesUsedInFix: Int?,        // null se provider != GPS o GnssStatus non ancora arrivato
    val satelliteCn0MinDbHz: Float?,
    val satelliteCn0AvgDbHz: Float?,
    val satelliteCn0MaxDbHz: Float?,
    val qualityTier: QualityTier,
    val speedEstimatorDiagnostics: SpeedEstimatorDiagnostics?,  // null se il fix ha rotto la catena (vedi 6.4)
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
    val processNoiseRegime: ProcessNoiseRegime,   // da getLastProcessNoise(): qLow/qHigh
    val accuracyGateEngaged: Boolean,             // da getLastAccuracyGateEngaged()
    val innovationEmaMagnitude: Double,           // da getLastInnovationEma()
)

enum class ProcessNoiseRegime { Q_LOW, Q_HIGH }
```

**`RejectionReason` mappa esattamente le soglie di `LocationSampleMapper.map()`** (`app/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt`, righe 34-42), letto riga per riga:

| Condizione in `LocationSampleMapper` | `RejectionReason` |
|---|---|
| `speedMps == null \|\| !isFinite \|\| < 0f` | `MISSING_OR_INVALID_SPEED` |
| `accuracyMps == null \|\| !isFinite \|\| < 0f` | `MISSING_OR_INVALID_SPEED_ACCURACY` |
| `accuracyMps > maxSpeedAccuracyMps` (default 0.35 m/s) | `SPEED_ACCURACY_TOO_HIGH` |
| `horizontalAccuracyMeters` presente e (`!isFinite` o `> maxHorizontalAccuracyMeters`, default 20m) | `HORIZONTAL_ACCURACY_TOO_HIGH` |
| `ageMillis !in 0..maxAgeMillis` (default 3000ms) | `FIX_TOO_OLD` (copre anche un'età negativa/clock-skew, caso raro, non merita una voce a parte) |

`reliable` in `LocationSampleMapper` è un singolo AND di tutte queste condizioni — non distingue quale sia fallita. Più soglie possono fallire sullo stesso fix: `rejectionReasons` è una **lista**, non un singolo valore, per non nascondere quel caso. Per abilitarlo, `LocationSampleMapper.map()` deve restituire, oltre al `Boolean reliable` già esistente (che resta invariato per i chiamanti attuali), anche un piccolo `LocationReliability(reliable: Boolean, reasons: List<RejectionReason>)` interno di cui il livello di collegamento Android legge `reasons` solo per costruire l'evento — dettaglio implementativo, non scelto oltre questo livello qui.

**`QualityTier`** — proiezione leggibile delle soglie *già esistenti*, non un nuovo giudizio: `SCARSA` (2+ `rejectionReasons`, o nessun dato di velocità/accuratezza disponibile), `DEGRADATA` (esattamente 1 `rejectionReason`), `BUONA` (`reliable == true`), `OTTIMA` (`reliable == true` e con margine: `accuracyMps <= maxSpeedAccuracyMps / 2` e, se presente, `horizontalAccuracyMeters <= maxHorizontalAccuracyMeters / 2`). **L'unico taglio non presente altrove nel codice è il margine "metà soglia" per separare OTTIMA da BUONA** — dichiarato qui esplicitamente, non nascosto; se il proprietario preferisce una soglia diversa o vuole eliminare la distinzione OTTIMA/BUONA, è un aggiustamento di una riga (vedi Domande aperte).

**GNSS satelliti/C/N0** — via `LocationManager.registerGnssStatusCallback(GnssStatus.Callback, Handler)`, un canale **indipendente** dai fix di posizione: Android consegna `onSatelliteStatusChanged(status)` con una propria cadenza, non sincronizzata 1:1 con `onLocationResult`. Il livello di collegamento tiene l'ultimo snapshot ricevuto (satelliti con `usedInFix(i) == true`, contati; `getCn0DbHz(i)` min/media/max su quello stesso sottoinsieme) e lo allega a ogni `LocationFixEvent` costruito — è il **miglior dato disponibile in quell'istante**, non una garanzia di corrispondenza esatta allo stesso fix; questo va detto esplicitamente per non far leggere il dato come più preciso di quanto sia. `null` per fix non-GPS (`provider != "gps"`) o prima che il primo `GnssStatus` sia mai arrivato.

**Ambito dichiarato esplicitamente: solo forza del segnale (C/N0), non dati grezzi di pseudorange/fase portante.** `GnssMeasurementsEvent`/pseudorange/carrier-phase sono il territorio, molto più grande, di G4/G12 (RTK, Doppler grezzo per-satellite) nella roadmap — deliberatamente fuori scope qui. Lo scopo di questo campo è un confronto raw-vs-derivato: il proprietario vuole poter vedere quando il giudizio di affidabilità dell'app (derivato da `horizontalAccuracyMeters`, già processato da Android) è in disaccordo con la forza del segnale grezza sottostante — non è ridondante con l'accuratezza in metri, è un secondo segnale indipendente per lo stesso controllo.

**`SpeedEstimatorDiagnostics` è `null` quando il fix ha causato un `breakChain()`** (vedi 6.4): dopo `breakChain()`, `SpeedEstimator` azzera `eastAxis`/`northAxis`/le EMA di innovazione/`accuracyBaselineMeters`, ma **non** azzera `lastProcessNoise`/`lastAccuracyGateEngaged` (verificato leggendo `breakChain()`, righe 181-189 di `SpeedEstimator.kt`: quei due campi non sono nella lista dei reset) — quindi leggerli dopo una rottura di catena restituirebbe un valore residuo del fix precedente, non un dato reale per il fix corrente. Popolare il campo solo quando il fix è realmente passato per `predict()`/`update()` evita di loggare un valore stantio spacciandolo per attuale.

### 5.3 — Evento di silenzio di ricezione

```kotlin
data class GpsSilentGapEvent(
    override val elapsedRealtimeMillis: Long,
    val gapMillis: Long,   // tempo trascorso dall'ultima callback grezza di QUALUNQUE tipo (affidabile o no)
) : SessionEvent
```

Distinto per costruzione da "un fix è arrivato ma è stato rifiutato" (quello è un `LocationFixEvent` con `reliable == false`): questo evento esiste solo per il caso in cui Android non ha consegnato **nessuna** callback per un intervallo anomalo — non rilevabile dalla callback stessa (per definizione non arriva), va controllato periodicamente. Soglia proposta: **5000ms** (5× la cadenza attesa di 1000ms della richiesta di localizzazione, `TICK_MILLIS` in `RunSessionService`) — abbastanza sopra la cadenza normale da non scattare su una singola callback in ritardo, abbastanza corto da restare utile per misurare la lentezza di acquisizione segnalata dal proprietario. Implementato da un tracker sorella di `GpsFreshnessTracker` già esistente (stesso file, stesso stile), controllato sullo stesso ticker a 1Hz già presente in `RunSessionService.tickTimer()` — nessun nuovo timer.

### 5.4 — Eventi discreti di cue/routine/pace

```kotlin
data class AudioCueEmittedEvent(
    override val elapsedRealtimeMillis: Long,
    val cueType: String,                  // AudioCue.name -- non il tipo AudioCue stesso, vedi nota sotto
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
    val fromState: PaceState?,    // null sia alla primissima conferma della sessione, sia alla prima
                                   // conferma dopo OGNI cambio fase successivo (vedi nota sotto)
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

enum class PaceCandidateAbandonReason { SNAPPED_BACK_TO_CONFIRMED, GPS_BECAME_UNRELIABLE }
```

**Nota su `cueType: String`, non `AudioCue`:** `AudioCue` (`audio/CuePlayer.kt`) porta `@param:RawRes val resourceId: Int` — un tipo Android (annotazione `androidx.annotation.RawRes`, ID di risorsa) che romperebbe il vincolo "nessun tipo Android nel dominio evento" e non avrebbe comunque senso portato su Wear OS (risorse audio diverse). `.name` (stesso pattern già usato da `AudioCue.valueOf(it.name)` in `SessionCoordinator`) porta l'informazione senza l'accoppiamento.

**`PaceStateConfirmedEvent` si emette ad ogni cambio di `confirmedPace` in `PaceEvaluator`, indipendentemente da `producedAudibleCue`** — è esattamente il punto che avrebbe reso osservabile in diretta il bug già noto e separato (P3 nella roadmap): `confirmedPace = null` viene azzerato da `updateConfig()` ad ogni cambio fase, e il primo ritorno a `GOOD` dopo quel reset non produce `Cue.GOOD_PACE` (`PaceEvaluator.kt`, righe 64-68: il controllo richiede `previous == TOO_SLOW || previous == TOO_FAST`, non `previous == null`) — con questo evento quel caso sarebbe visibile nel log come `PaceStateConfirmedEvent(fromState=null, toState=GOOD, producedAudibleCue=false)` — `fromState=null` esattamente perché il cambio fase ha appena azzerato `confirmedPace`, non perché sia la prima conferma della sessione — anche senza sentire il cue mancato dal vivo.

**`PaceCandidateAbandonedEvent` si emette solo per i due casi in cui un candidato realmente in corso viene scartato prima di confermare** (guardia: solo se `candidatePace != null` prima del reset), letti nei punti esatti in cui `PaceEvaluator.clearCandidate()` viene chiamato oggi (`PaceEvaluator.kt`):
- riga 34 (`accept()`, il campione torna coincidente con `confirmedPace` mentre un candidato diverso stava accumulando) → `SNAPPED_BACK_TO_CONFIRMED`;
- riga 76 (`acceptUnreliable()`, il GPS diventa inaffidabile mentre un candidato stava accumulando) → `GPS_BECAME_UNRELIABLE`.

**Deliberatamente esclusa** la `clearCandidate()` chiamata da `updateConfig()` (riga 19, reset per cambio fase): è già coperta da `RoutinePhaseChangedEvent` nello stesso tick, e non aggiunge nessuna informazione nuova — loggarla sarebbe esattamente il "log inutile" che il proprietario ha chiesto di evitare. Allo stesso modo, la `clearCandidate()` dopo una conferma riuscita (riga 60) non è un abbandono, è bookkeeping della conferma stessa (già coperta da `PaceStateConfirmedEvent`).

### 5.5 — Eventi di rottura catena

```kotlin
data class SpeedChainBrokenEvent(
    override val elapsedRealtimeMillis: Long,
    val reason: SpeedChainBreakReason,
) : SessionEvent

enum class SpeedChainBreakReason {
    UNRELIABLE_OR_INVALID_FIX,        // SpeedEstimator.accept(): !reliable || !position.isValid()
    NON_POSITIVE_TIME_DELTA,          // dtSeconds <= 0.0
    NON_FINITE_RESULT,                // speed calcolata non finita
    STALE_NO_RECENT_RELIABLE_FIX,     // chiamata esterna da SessionCoordinator, vedi sotto
}

data class DistanceChainBrokenEvent(
    override val elapsedRealtimeMillis: Long,
    val reason: DistanceChainBreakReason,
) : SessionEvent

enum class DistanceChainBreakReason {
    UNRELIABLE_OR_INVALID_FIX,   // DistanceAccumulator.accept(): !reliable || !position.isValid()
    IMPLAUSIBLE_JUMP,            // delta tempo non positivo, distanza non finita, o salto oltre maxSpeedMetersPerSecond
    GPS_OUTAGE_ENTERED,          // chiamata esterna da SessionCoordinator all'ingresso in un'interruzione GPS
}
```

**`SpeedChainBreakReason.STALE_NO_RECENT_RELIABLE_FIX`** corrisponde al trigger introdotto dal fix del Task 10 (branch `feature/speed-production-wiring`, non ancora fuso in `feature/paco-run-m3e` al momento di scrivere — vedi nota di dipendenza sotto): in `SessionCoordinator.process()`, `speedEngine.breakChain()` viene chiamato quando `!gpsReliable && elapsedRealtimeMillis - lastReliableFixElapsedMillis >= LocationSampleMapper.DEFAULT_MAX_AGE_MILLIS` — **tempo reale trascorso dall'ultimo fix affidabile**, non "quale tipo di chiamata ha innescato la transizione" (il bug del Task 9, corretto dal Task 10, confondeva le due cose). Questo è l'unico trigger esterno di `SpeedEstimator.breakChain()`; gli altri tre (`UNRELIABLE_OR_INVALID_FIX`, `NON_POSITIVE_TIME_DELTA`, `NON_FINITE_RESULT`) sono interni a `SpeedEstimator.accept()` stesso. `DistanceAccumulator.breakChain()` ha, **dopo la piccola pulizia di codice descritta sotto**, la stessa struttura: due trigger interni in `DistanceAccumulator.accept()`, uno esterno (`GPS_OUTAGE_ENTERED`, alla riga 220 di `RunSessionStore.kt`, dentro `enteredOutage` in `SessionCoordinator.process()`).

**Prerequisito per un `DistanceChainBrokenEvent` completo/affidabile — nota per chi scriverà il piano di implementazione, non da fare qui:** oggi (letto in `DistanceAccumulator.accept()`, `RunSessionStore.kt`) i due trigger interni **non** sono strutturalmente simmetrici come i tre di `SpeedEstimator.accept()`. Il ramo `UNRELIABLE_OR_INVALID_FIX` fa correttamente `return breakChain()`, ma il ramo `IMPLAUSIBLE_JUMP` (delta tempo non positivo, distanza non finita, o salto oltre `maxSpeedMetersPerSecond`) **duplica inline** il corpo di `breakChain()` (`anchor = null; lastFix = null; return 0.0`) invece di chiamarlo. Poiché `SpeedEstimator.breakChain()` è il singolo choke point per tutti i suoi reset interni — instrumentarlo basta a intercettarli tutti — mentre in `DistanceAccumulator` il ramo `IMPLAUSIBLE_JUMP` sarebbe **invisibile** a un'instrumentazione che aggancia solo `breakChain()`. La correzione decisa: instradare quel ramo attraverso `breakChain()` invece di duplicarne il corpo — una pulizia meccanica, di comportamento identico (le due righe duplicate sono già funzionalmente identiche a quelle di `breakChain()`), coerente con l'avversione già dichiarata in questo progetto per la logica duplicata, e che chiude anche il buco di osservabilità come effetto collaterale. Questo è un piccolo cambiamento di codice in `DistanceAccumulator.kt`, da trattare come prerequisito dell'implementazione di P1, non fatto in questo documento.

**Nota importante di dipendenza, non un ostacolo ma va dichiarata:** `feature/paco-run-m3e`, il branch di questo documento, non ha ancora `SessionSpeedEngine`/`KalmanSpeedEngine`/`onSpeedComputed` fusi da `feature/speed-production-wiring` — oggi `SpeedEstimator` in questo branch è ancora solo diagnostico in `RunSessionService`, non collegato a `PaceEvaluator`. Questo documento assume che l'implementazione di P1 avvenga dopo (o come parte dello stesso lavoro di) quel merge, dato che l'intero caso d'uso di P1 — osservare in diretta le decisioni del motore Kalman in produzione — presuppone `speedEngine` già attivo. Se l'ordine reale differisse, i nomi `speedEngine`/`SessionSpeedEngine`/`onSpeedComputed` citati qui vanno riallineati allo stato effettivo del branch in quel momento; lo schema degli eventi (Decisione 5) resta valido in entrambi i casi.

### 5.6 — Eventi di ambiente/dispositivo

```kotlin
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

**Cadenza di `DeviceHealthSampledEvent`: ogni 30 secondi**, non ad ogni fix. Batteria e stato termico cambiano su scale di minuti, non di secondi — campionarli ad ogni fix (fino a 1Hz) sarebbe puro rumore ripetuto senza nuova informazione, esattamente il tipo di "log inutile" da evitare. 30s è la metà del limite superiore indicato (30-60s), abbastanza fitto da individuare un episodio di throttling termico dentro un singolo intervallo RUN/WALK anche breve (60s nella corsa già validata), implementato riusando lo stesso schema a "bucket" già presente in `SessionCoordinator.routineTimingElapsedRealtimeMillis()` (dedup per bucket temporale), applicato sul ticker a 1Hz già esistente in `RunSessionService` — nessun nuovo timer.

**`SERVICE_RESTARTED_BY_SYSTEM` è un'inferenza best-effort, non una certezza**, va dichiarato come tale: si rileva persistendo un piccolo marker (es. una preferenza booleana "sessione avviata, non ancora terminata pulitamente") impostato a `true` in `beginTracking()` e a `false` solo nel percorso normale di `stopSession()`; se all'avvio successivo il marker risulta già `true`, si logga questo evento prima di sovrascriverlo. Non distingue con certezza "il sistema ha riavviato il servizio da solo" da "l'utente ha riaperto l'app dopo un'uscita non pulita precedente" — utile comunque come segnale di "l'ultima sessione non si è chiusa in modo pulito", da leggere con quel limite in mente.

## Decisione 6 — wiring: `onEvent`, stesso pattern di `onCues`/`publish`/`onSpeedComputed`

I costruttori esistenti in questo codebase passano già comportamento tramite lambda a parametro di default (`SessionCoordinator`: `onCues: (List<AudioCue>, Float) -> Unit`, `publish: (RunSessionState) -> Unit`, e — nel branch `feature/speed-production-wiring` — `onSpeedComputed: (Double?) -> Unit = {}`). `onEvent: (SessionEvent) -> Unit = {}` si aggiunge con lo stesso pattern, propagato dagli stessi punti:

- `SpeedEstimator(onEvent: (SessionEvent) -> Unit = {})` — emette `SpeedChainBrokenEvent` nei suoi tre punti interni di `breakChain()`.
- `DistanceAccumulator(onEvent: (SessionEvent) -> Unit = {})` — emette `DistanceChainBrokenEvent` nei suoi due punti interni.
- `RoutineEngine(preferences, onEvent: (SessionEvent) -> Unit = {})` — emette `RoutinePhaseChangedEvent` in ogni punto dove `phase = ...` cambia realmente (`startRun`, `advanceTo`, `finishRoutine`), catturando `fromPhase` prima della riassegnazione. **Decisione esplicita sul caso multi-transizione:** `advanceTo()` (letto in `RoutineEngine.kt`) può riassegnare `phase` **due volte in una sola chiamata** — quando un tick ritardato fa saltare un ciclo intero (`fullCycles > 0L`, `transitions = 2`) o quando il resto dell'intervallo supera l'intera durata della fase successiva (i due rami `if`/`else if` dopo il calcolo di `fullCycles`), producendo ad es. RUN→WALK→RUN prima che arrivi la prossima osservazione. In questo caso si emettono **due** `RoutinePhaseChangedEvent` separati (uno per ogni riassegnazione reale di `phase`, con `fromPhase`/`toPhase` catturati a quel punto preciso), non un singolo evento "netto" che collassi RUN→RUN. Motivazione: collassare nasconderebbe esattamente il tipo di informazione diagnostica che questo log esiste per rendere visibile — cioè che un tick è arrivato in ritardo abbastanza da saltare un'intera fase. Non è più una domanda aperta.
- `PaceEvaluator(config, onEvent: (SessionEvent) -> Unit = {})` — emette `PaceStateConfirmedEvent`, `PaceCandidateStartedEvent`, `PaceCandidateAbandonedEvent` nei punti descritti in 5.4.
- `SessionCoordinator`/`PendingSessionCoordinator` — ricevono `onEvent` come parametro aggiuntivo, lo passano giù a `RoutineEngine`/`PaceEvaluator`/`speedEngine`/`distanceEngine` costruiti al loro interno, **e** lo chiamano direttamente per `AudioCueEmittedEvent` (un evento per ogni cue nella lista, nello stesso punto in cui oggi si chiama `onCues(cues, preferences.cueVolume)`, con `cumulativeDistanceMeters = routine.totalDistanceMeters` e `speedKmh = paceSample.speedKmh.takeIf { gpsReliable }` già disponibili localmente in quel punto).
- `RunSessionService` (livello di collegamento Android) — costruisce/riceve tutti gli eventi che richiedono API Android dirette (`SessionStarted`, `LocationFixEvent`, `GpsSilentGapEvent`, `AppLifecycleChangedEvent`, `DeviceHealthSampledEvent`) e passa giù per `PendingSessionCoordinator` un `onEvent` che, insieme a quelli propagati dal dominio, scrive **subito** ogni evento (append su disco o flush periodico frequente, vedi Decisione 8) — non un `onEvent = events::add` che si limita ad accumulare in una lista in-memory da svuotare a fine sessione: quello è esattamente lo schema che Decisione 8 esclude, per lo stesso motivo (perdita totale del log in caso di crash a metà sessione).

`DopplerSpeedEngine` (l'implementazione alternativa di `SessionSpeedEngine`, motore Doppler grezzo, non quello di produzione oggi) non ha stato interno significativo da rompere — il suo `breakChain()` resta un no-op che non chiama `onEvent`, per coerenza: non c'è niente di reale da diagnosticare lì.

**Lifecycle app (`BACKGROUNDED`/`FOREGROUNDED`):** `PacoRunApp.kt` osserva già il lifecycle della UI Compose (`DisposableEffect(lifecycle)` con un `LifecycleEventObserver`, oggi limitato a `ON_RESUME` per un altro scopo). Estendere lo stesso observer per intercettare anche `ON_START`/`ON_STOP` e inviare un nuovo `Intent` action (`ACTION_APP_FOREGROUNDED`/`ACTION_APP_BACKGROUNDED`) a `RunSessionService`, seguendo esattamente il pattern già usato da `ACTION_START`/`ACTION_STOP` — nessuna nuova dipendenza (es. `lifecycle-process`/`ProcessLifecycleOwner`), riuso di un observer già presente.

## Decisione 7 — cattura dati specifici Android

Tutto quanto segue vive nel livello di collegamento (`RunSessionService` e piccoli helper nel package `service`, mai nel package `eventlog`):

- **Hash di commit git:** `app/build.gradle.kts` deve abilitare `buildConfig = true` (oggi `false`) e popolare un `buildConfigField("String", "GIT_COMMIT_HASH", ...)` eseguendo `git rev-parse --short=12 HEAD` a tempo di build, con un fallback esplicito (es. `"unknown"`) se `git` non è disponibile (ambiente CI senza `.git`). Standard per progetti Android, nessuna libreria nuova.
- **Sensori:** `SensorManager.getDefaultSensor(Sensor.TYPE_X) != null` per ciascuno dei sei tipi in `SensorAvailability` — una query una tantum all'avvio sessione, economica.
- **Rete:** `ConnectivityManager.getNetworkCapabilities(activeNetwork)`, controllo `TRANSPORT_WIFI`/`TRANSPORT_CELLULAR`; nessuna rete attiva → `OFFLINE`.
- **GnssStatus:** `LocationManager.registerGnssStatusCallback(GnssStatus.Callback, Handler)`, registrato all'avvio sessione insieme alla richiesta di posizione, deregistrato in `stopSession()`.
- **Batteria/termico:** `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)`; `PowerManager.getCurrentThermalStatus()` (API 29+, coerente con `minSdk = 29` di questo progetto — nessun problema di compatibilità).

## Decisione 8 — scrittura incrementale, **non** il pattern GPX esistente

**Corretto rispetto a una bozza precedente di questo documento, che proponeva di rispecchiare esattamente lo schema di `trackPoints`/`exportTrackAsGpx()` in `RunSessionService.kt`** (accumulo in memoria per tutta la sessione, un singolo `Thread` best-effort che scrive tutto in blocco a fine sessione). Quello schema è sbagliato **per questo log specifico**: lo scenario in cui l'app crasha o viene terminata in modo anomalo a metà sessione — esattamente il caso in cui questo log ha più valore, perché è l'unico modo per ricostruire cosa stava succedendo nel motore Kalman/routine prima dell'interruzione — con un buffer-fino-alla-fine perde **l'intero log**, non solo la coda. Questo contraddice anche la motivazione con cui JSON Lines è stato scelto in Decisione 2: la proprietà "un file troncato resta leggibile riga per riga fino all'ultima riga scritta per intero" è un vantaggio **solo se si scrive davvero in modo incrementale** — con un unico write finale non c'è niente da recuperare comunque, il beneficio del formato non si realizza mai.

**Decisione corretta: gli eventi vanno scritti su disco via via che accadono** (append per ogni evento, o flush periodici frequenti — non un singolo flush di fine sessione). In pratica questo significa aprire il file una sola volta all'avvio sessione (tramite `MediaStore`, stesso meccanismo di `GpxExporter.writeToDownloads`) e mantenere aperto lo stream per scrivervi ogni riga (o un piccolo batch di righe) mano a mano che gli eventi arrivano, chiudendolo allo stop sessione — non i dettagli esatti dell'API di scrittura, quello è materia del piano di implementazione, non di questo documento.

**La proprietà "best-effort, non deve mai far cadere l'app se la scrittura fallisce" resta, invariata nello spirito, solo applicata per-append invece che a un singolo write finale:** ogni scrittura di riga (o flush periodico) va avvolta nel proprio `try { } catch (_: Exception) { }` — uno storage pieno o un comportamento OEM restrittivo a metà sessione non deve mai far cadere il processo, esattamente come per l'export GPX, solo che qui la superficie che può fallire è "ogni singola scrittura" invece di "l'unica scrittura finale".

**Va detto esplicitamente: `exportTrackAsGpx()` e questo nuovo scrittore hanno esigenze di ciclo di vita della scrittura genuinamente diverse, non vanno implementati in modo identico.** Il GPX può permettersi di accumulare tutto in memoria e scrivere una volta sola a fine sessione, perché se la sessione finisce in modo anomalo l'export GPX semplicemente non avviene (nessun dato parziale da salvare per un formato che non degrada bene a metà scrittura, vedi Decisione 2) — non c'è niente da perdere in più rispetto a quanto già perso. Il log eventi esiste apposta per il caso opposto, quindi la sua strategia di scrittura deve essere diversa fin dal principio. Il nome file segue comunque la stessa convenzione di `GpxExporter` (`paco-run-<timestamp>.gpx` → `paco-run-<timestamp>-events.jsonl`, stesso timestamp, così i due file restano associabili dal nome oltre che dalla chiave `elapsedRealtimeMillis`), scritto nella stessa cartella Downloads condivisa — solo il meccanismo di scrittura (incrementale invece che un blocco unico) cambia.

## Compatibilità Wear OS — obiettivo di design dichiarato, non un ripensamento successivo

Le classi di dominio (`SessionCoordinator`, `PaceEvaluator`, `RoutineEngine`, `SpeedEstimator`, `DistanceAccumulator`) sono già Kotlin puro, senza dipendenza Android. Il codice di emissione evento **dentro** di loro (Decisione 6) è quindi automaticamente condiviso gratis con un futuro build Wear OS — stesso schema `SessionEvent`, stessi nomi di campo, nessuna modifica. Solo il livello di collegamento — l'equivalente di `RunSessionService` che cattura modello dispositivo/batteria/rete/`GnssStatus` e scrive il file — va riscritto per piattaforma (Wear OS ha le proprie API `BatteryManager`/`ConnectivityManager`/`GnssStatus`, sostanzialmente le stesse superfici Android ma un contesto applicativo diverso, e uno storage locale diverso da "Downloads condiviso" per un dispositivo senza schermo grande da cui esportare comodamente — dettaglio da risolvere quando si arriverà al porting, non qui).

## Non-obiettivi espliciti

- **Nessun dump di accelerometro/giroscopio grezzo** o altro dato non legato a una domanda reale già identificata — richiesta esplicita del proprietario ("non voglio log inutili"), applicata in tutto questo documento (vedi in particolare l'esclusione motivata in 5.4).
- **Nessuna misura grezza di pseudorange/fase portante** (`GnssMeasurementsEvent`) — solo forza del segnale (C/N0), come dichiarato in 5.2. Pseudorange/carrier-phase è il territorio, molto più grande, di G4 (RTK/INS)/G12 (Doppler grezzo per-satellite) nella roadmap, deliberatamente fuori scope qui.
- **Questo documento non sceglie né implementa P2** (lo smoothing di decisione consapevole del cambio fase) — costruisce solo la raccolta dati necessaria a progettarlo con numeri reali invece che stimati.

## Domande aperte per il proprietario

1. **Margine "metà soglia" per `QualityTier.OTTIMA` vs `BUONA`** (5.2) — l'unico taglio numerico non già presente nelle soglie esistenti di `LocationSampleMapper`. Va bene come punto di partenza, o si preferisce eliminare la distinzione OTTIMA/BUONA (solo 3 livelli: buona/degradata/scarsa) finché non ci sono dati reali per tarare quel margine?
2. **Soglia di 5000ms per `GpsSilentGapEvent`** (5.3) — proposta come 5× la cadenza attesa di 1000ms; nessun dato reale ancora la conferma o la smentisce (la stessa lentezza di acquisizione che ha motivato questa richiesta è tra le cose che questo log dovrebbe misurare, quindi la soglia è necessariamente una prima stima).
3. **Cadenza di 30s per `DeviceHealthSampledEvent`** (5.6) — scelta come metà del range indicato (30-60s) senza un vincolo più stringente; va bene o si preferisce partire più vicino a 60s per ridurre ulteriormente il volume di eventi in corse lunghe?
4. **Ordine di merge tra `feature/speed-production-wiring` e l'implementazione di P1** (nota in 5.5) — questo documento assume che P1 sia implementato dopo (o assieme a) quel merge; da confermare prima di scrivere il piano di implementazione, perché cambia i nomi di classe esatti da toccare.
5. **Dove va scritto il file su Wear OS** (sezione Compatibilità Wear OS) — non c'è un equivalente ovvio di "cartella Downloads condivisa" su un dispositivo senza schermo grande da cui esportare; lasciato esplicitamente irrisolto per quando si arriverà al porting.

## Codice letto per questo documento

- `app/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt`
- `app/src/main/java/dev/fred/pacorun/location/LocationSample.kt`
- `app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt`
- `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt` (`SessionCoordinator`, `PendingSessionCoordinator`, `DistanceAccumulator`, `GpsFreshnessTracker`) — sia sullo stato attuale di `feature/paco-run-m3e`, sia (via `git show`) sullo stato equivalente in `feature/speed-production-wiring` per verificare il trigger esatto di `speedEngine.breakChain()` introdotto dal Task 10
- `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt`
- `app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt`, `RoutineModels.kt`
- `app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt`, `PaceModels.kt`
- `app/src/main/java/dev/fred/pacorun/settings/RunPreferences.kt`
- `app/src/main/java/dev/fred/pacorun/audio/CuePlayer.kt` (`AudioCue`)
- `app/src/main/java/dev/fred/pacorun/export/GpxWriter.kt`, `GpxExporter.kt`, `TrackPoint.kt`
- `app/src/main/java/dev/fred/pacorun/PacoRunApp.kt` (observer di lifecycle Compose esistente)
- `app/build.gradle.kts` (dipendenze attuali, `buildConfig = false`)

**Documenti interni di riferimento (letti per intero prima di questo, non riprodotti qui):**
- `docs/superpowers/specs/2026-09-13-kalman-production-run1-validation.md`
- `docs/superpowers/specs/2026-09-13-precision-and-cue-stability-research.md`
- `docs/superpowers/specs/2026-09-13-phase-aware-cue-smoothing-research.md`
- `docs/superpowers/specs/2026-09-11-precision-roadmap.md` (voci P1/P2/P3)
