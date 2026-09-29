# Estrazione di `:core` e `:android-connector` — design

Data: 2026-09-20 (consolidato 2026-09-22)
Branch di lavoro: `feature/core-extraction` (forcato da `main`) per i
passi 1-6 della migrazione; il passo 7 (switch-over di `:wear`) avviene
su `feature/wear-os` dopo un merge — vedi sezione "Ordine di
migrazione" per i dettagli e il perché.

Stato: in revisione (non ancora approvato dall'utente). Storia:
venticinque round di revisione avversariale a patch (2026-09-20/22) più
2 problemi trovati direttamente dall'owner/dall'autore avevano prodotto
27 correzioni reali, poi consolidate in testo finale unico (niente più
note "Correzione (round N...)"). Il primo round completo sul documento
consolidato ("a caccia di tutto, non di un punto specifico") ha trovato
altri 3 problemi bloccanti e 1 buco di test grave: gli accessor
diagnostici `internal` di `SpeedEstimator` (stesso identico problema
già risolto per `RunSessionStore.publish`, ma non applicato qui — la
build di `:app` si sarebbe rotta per tre passi), la formula di gating
della diagnostica che aveva perso `reliability.reliable` durante il
consolidamento (contraddicendo la stessa spec una sezione più sotto),
`GpsFreshnessTrackerTest.kt` descritto come già corretto quando invece
va ancora modificato, e nessun caso di test per il rischio più grave
del documento (il ciclo di vita del campo `sequencer`). Trovati anche,
verificando personalmente questi problemi, altri due buchi introdotti
dal consolidamento stesso: `CueSignal` usato ma non dichiarato, e la
derivazione di `cueType` da `CueSignal.name` sparita. Tutti corretti.
Il secondo round completo, sugli stessi termini del primo, non ha
trovato **nessun nuovo problema bloccante** — solo 5 rifiniture di
completezza (codice di conversione `CueSignal` mai mostrato per intero,
con relativa sottostima del costo di aggiornamento di
`SessionCoordinatorTest.kt`; `lastDeviceHealthBucket` mancante
dall'elenco dello stato del Sequencer; tipi non nominati esplicitamente
in tabella; una contraddizione interna `startSession()`/`beginTracking()`
nello stesso paragrafo; una classe descritta come annidata quando è
sorella). Tutte corrette. Primo segnale di reale convergenza dopo il
consolidamento.
**Regola non negoziabile del progetto**: qualunque modifica futura a
questo documento passa comunque da un round di revisione avversariale
prima di essere considerata definitiva — nessuna eccezione basata sulla
fiducia
di chi scrive.

## Contesto e motivazione

Paco Run esiste oggi in due moduli Android: `:app` (telefono) e `:wear`
(v0 diagnostico, appena installato e verificato su hardware reale —
TicWatch Pro 3 GPS). `:wear` oggi duplica il motore (SpeedEstimator,
PaceEvaluator, RoutineEngine, RunSessionService, ecc.) con una copia
`cp` letterale dei file di `:app`, deliberatamente temporanea (vedi
`docs/superpowers/plans/2026-09-18-wear-os-core-port.md`).

Questa duplicazione è stata accettata solo per portare in fretta un
diagnostico funzionante sul watch. Prima di costruire qualunque feature
watch-specifica sopra quella base, l'owner del progetto ha deciso
(sessione 2026-09-20) di eliminare la duplicazione estraendo il motore
in moduli Gradle condivisi, con un vincolo esplicito: **nessun
compromesso**. Non "va bene se funziona", ma "fatto per bene" — questo
vale in particolare per la parte delicata di questo lavoro (`RunSessionEventSequencer`,
vedi sotto), dove l'owner ha rifiutato esplicitamente due opzioni più
economiche (spostare `RunSessionService` intatto, o estrarne solo il
pezzo di buffering) in favore della riscrittura interna completa.

Motivazione a lungo termine, oltre a togliere la duplicazione: rendere
più semplice collegare futuri "motori"/arbitri (sensori inerziali nativi
del watch — I1-I6 della roadmap di precisione — ed eventualmente
RTK/INS, G4) senza dover ripetere la logica di business su due moduli
Android paralleli.

**Decisione deliberata sul meccanismo di emissione eventi:** l'emissione
di eventi nel motore è callback-based (push, durante la chiamata),
mentre il chiamante esterno (`RunSessionService`) ha bisogno dello
stato POST-tick per costruire il proprio evento (pattern "outer needs
post-state while inner emits during-call"). Si è deciso di **non**
risolverlo alla radice ora (riscrivendo le firme di
`SpeedEstimator`/`PaceEvaluator`/`RoutineEngine`/`DistanceAccumulator`
per restituire eventi invece di usare una callback condivisa) — sia
perché nessun bug reale derivava da quel pattern, sia perché il buffer
contenuto che questo design introduce (vedi `RunSessionEventSequencer`)
copre automaticamente qualunque nuovo motore interno aggiunto DENTRO la
stessa chiamata `tick()` (inclusa una futura arbitro I1) senza bisogno
di rifare il lavoro. Questa spec quindi mantiene il meccanismo a
callback per l'emissione INTERNA, e introduce un ritorno per valore
solo al confine esterno del Sequencer.

## Architettura

Quattro moduli Gradle:

- **`:core`** — modulo Kotlin/JVM puro (plugin `kotlin("jvm")`, nessun
  plugin Android). Contiene tutta la logica motore: stima velocità,
  valutazione del passo, routine, accumulo distanza, modelli di eventi,
  scrittura GPX, orchestrazione della sessione. Zero dipendenze da
  `android.*` — non per disciplina di code review, ma perché il modulo
  non ha l'SDK Android sul classpath: importare `android.*` lì è un
  errore di compilazione, non un refactor da controllare a occhio.
- **`:android-connector`** — libreria Android (`com.android.library`),
  dipende da `:core`. Contiene tutto il codice che tocca l'SDK Android
  ma è condiviso fra telefono e watch: mapping `Location` → modello
  puro, logging su file, cue audio, e la parte Android-only della vecchia
  `RunSessionService` (permessi, notifica, `FusedLocationProviderClient`,
  callback GNSS, letture `BatteryManager`/`PowerManager`/
  `ConnectivityManager`). Ha un proprio `namespace` e una propria
  cartella `res/` (icona notifica, 14 file audio dei cue).
- **`:app`** — solo UI/manifest/Activity del telefono, più una nuova
  classe `Application` minuscola. Dipende esplicitamente sia da `:core`
  sia da `:android-connector` (non solo transitivamente — vedi sezione
  Gradle).
- **`:wear`** — solo UI/manifest Wear OS, più la stessa nuova classe
  `Application` minuscola. Il `Service` stesso vive interamente in
  `:android-connector` — non ne resta nessuna versione per modulo. Le
  copie `cp`-duplicate esistenti vengono eliminate a fine migrazione.

## Allocazione dei file

Verificata contando gli `import android.*` in ogni file candidato
(`grep -c "^import android" <file>`), non per intuito:

| File | Import android | Destinazione |
|---|---|---|
| `service/SpeedEstimator.kt` | 0 | `:core` |
| `service/RunSessionStore.kt` (oggetto singleton `RunSessionStore`, `RunSessionState`, `LocationPosition`, coordinators, `SessionSpeedEngine`/`SessionPaceSmoothingEngine`/`SessionDistanceEngine`/`SessionRoutineEngine`/`SessionPaceEvaluator`, `DopplerSpeedEngine`/`KalmanSpeedEngine`, `GpsFreshnessTracker`/`GpsSilentGapTracker`, `RoutineEngineAdapter`, `PaceEvaluatorAdapter`, `SessionStartPrerequisites`, `SessionNotificationTracker` — l'intero file, nessuna eccezione) | 0 | `:core`. `RunSessionState`/`RunSessionStore` restano osservabili dalla UI di `:app`/`:wear` esattamente come oggi (`PacoRunApp.kt`, `ActiveRunScreen.kt` li referenziano già) — nessun cambiamento a quel confine |
| `pace/PaceEvaluator.kt` | 0 | `:core` |
| `pace/PaceModels.kt` | 0 | `:core` |
| `routine/RoutineEngine.kt` | 0 | `:core` |
| `routine/RoutineModels.kt` | 0 | `:core` |
| `location/LocationSample.kt` | 0 | `:core` — assorbe anche `DEFAULT_MAX_AGE_MILLIS` (oggi companion constant su `LocationSampleMapper.kt`, un semplice `Long` senza nulla di Android-specifico: si sposta qui, `LocationSampleMapper` e `RunSessionStore` la referenziano entrambi da qui) |
| `eventlog/SessionEvent.kt` | 0 | `:core` — assorbe anche `LocationReliability` (oggi in `LocationSampleMapper.kt`, va accanto a `RejectionReason` che avvolge) |
| `eventlog/SessionEventWriter.kt` | 0 | `:core` |
| `export/GpxWriter.kt` | 0 | `:core` |
| `settings/RunPreferences.kt` (modello dati) | 0 | `:core` |
| `RunPreferences.paceConfig()` (oggi `private fun RunPreferences.paceConfig()`, funzione membro **privata** di `RunSessionService.kt:511`, non top-level in `RunPreferences.kt`) | 0 | `:core` — resa top-level e **non più privata** (era `private`, accessibile solo dentro `RunSessionService`; il suo unico chiamante si sposta al passo 6, ma la funzione stessa non tocca nulla di `RunSessionService` — solo il ricevitore `RunPreferences` — quindi si sposta al passo 3 insieme al resto dei motori, senza aspettare il chiamante) |
| *(nuovo)* `RunSessionEventSequencer.kt` | 0 (per costruzione) | `:core` |
| *(nuova)* `SpeedEngineDiagnostics` (interfaccia) | 0 (per costruzione) | `:core` — implementata da `KalmanSpeedEngine`, che è in `:core` |
| *(nuova)* `GnssSnapshot` (oggi `private data class` annidata in `RunSessionService.kt`, zero import android, solo primitivi + `List<SatelliteGeometry>`) | 0 | `:core`, resa pubblica e spostata in un proprio file (necessaria come tipo del parametro di `onLocationFix`, quindi non può restare privata/annidata) |
| *(nuova)* `CueSignal` (sealed interface) | 0 (per costruzione) | `:core` — avvolge `Cue`/`RoutineCue`, già in `:core` |
| *(nuova)* `TimerTickResult` (data class, ritorno di `onTimerTick`) | 0 (per costruzione) | `:core` |
| *(nuova)* `LocationFixResult` (data class, ritorno di `onLocationFix`) | 0 (per costruzione) | `:core` |
| `location/LocationSampleMapper.kt` | 1 | `:android-connector` — l'overload `map(location: Location, nowElapsedRealtimeMillis: Long): LocationSample` (quello che prende un `Location` grezzo) **si elimina**: ha oggi un solo chiamante in produzione (`RunSessionService.kt:326`, esattamente il call site che il passo 6 riscrive per la fusione — vedi Data Flow, Percorso A, e Migrazione, passo 6) e zero chiamanti nei test (verificato: `LocationSampleMapperTest.kt` usa solo l'overload primitivo `map(speedMps, accuracyMps, ageMillis, ...)`, 20 chiamate). L'overload primitivo resta identico, non tocca nulla |
| `service/SessionEventLogger.kt` | 4 | `:android-connector` |
| `export/GpxExporter.kt` | 4 | `:android-connector` |
| `audio/CuePlayer.kt` | 12 | `:android-connector` |
| *(nuova)* `RunPreferencesSource` (interfaccia) | 0 (per costruzione) | `:android-connector` — non `:core`: nessuna classe di `:core` la consuma, solo `RunSessionService` |
| *(nuova)* `RunSessionHostConfig` (interfaccia) | 0 (per costruzione) | `:android-connector` — stesso motivo |
| *(nuova, da `RunSessionService.kt`)* parte Android-only (permessi, notifica, `FusedLocationProviderClient`, GNSS callback, letture sistema) | — | `:android-connector` |
| `settings/RunPreferencesStore.kt` | 8 | resta `:app`-only (implementa `RunPreferencesSource`) |
| `location/LocationReadiness.kt` | 0 | resta `:app` (logica di pre-check UI, non motore) |

`RunSessionService.kt` ha oggi 24 import `android.*` — è il file che
viene *diviso*, non spostato intero (vedi sotto).

## Split di `RunSessionService.kt` — Approccio A (deciso, non negoziabile)

Tre opzioni erano sul tavolo: (B) spostare il file intatto in
`:android-connector` facendogli comunque referenziare tipi `:core`; (C)
estrarre solo il buffer di causal-ordering lasciando il resto nel
Service; (A) estrazione completa della logica di orchestrazione pura in
una nuova classe `:core`, lasciando in `:android-connector` solo il
collante Android vero e proprio. L'owner ha rifiutato B e C
esplicitamente come compromessi ("il lavoro che non facciamo oggi è il
lavoro che faremo domani") — si procede con **A**.

Nuova classe pura in `:core`: **`RunSessionEventSequencer`** (nome
finale — scartati "Orchestrator", che collide concettualmente con
`SessionCoordinator`/`PendingSessionCoordinator` già esistenti, ed
"Engine", già sovraccarico da SpeedEngine/DistanceEngine/RoutineEngine).

### Costruzione

`RunSessionEventSequencer` viene istanziato ex-novo a ogni inizio
sessione (esattamente come `PendingSessionCoordinator` è ricostruito
oggi in `beginTracking()`), non riusato fra sessioni. Il suo costruttore
accetta gli stessi parametri che oggi configurano `PendingSessionCoordinator`
in `beginTracking()`: `preferences: RunPreferences`, `onCues: (List<CueSignal>) -> Unit`,
`publish: (RunSessionState) -> Unit`, `sessionStartedAtElapsedMillis: Long`
(usato sia per `GpsSilentGapTracker.reset()` sia per calcolare
`LocationFixEvent.elapsedSinceStartMillis` ad ogni fix).

Internamente costruisce, con la propria closure `onEvent` (che accoda
incondizionatamente in `pendingEvents` — a differenza dell'`onEvent` di
oggi in `RunSessionService`, questa non ha un ramo "scrivi su logger":
ogni sua invocazione avviene sempre dentro la finestra di buffering
attiva di `onLocationFix`/`onTimerTick`, dato che `:core` non ha alcun
logger su cui scrivere direttamente — vedi sotto), tutti i componenti
che oggi `beginTracking()` costruisce a mano
(`RunSessionService.kt:213-230`):

```kotlin
val speedEstimator = SpeedEstimator(onEvent = onEvent)
val speedEngine = KalmanSpeedEngine(speedEstimator)
val gpsFreshness = GpsFreshnessTracker()
val gpsSilentGap = GpsSilentGapTracker().also { it.reset(sessionStartedAtElapsedMillis) }
val coordinator = PendingSessionCoordinator(
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
```

Punto critico, verificato riga per riga contro il codice reale:
`distanceEngine`, `speedEngine`, `onSpeedComputed` e `onEvent` sono
TUTTI parametri con un default silenzioso su `PendingSessionCoordinator`
(`DistanceAccumulator()`, `KalmanSpeedEngine()`, `{}`, `{}` —
`RunSessionStore.kt:417-421`). Se il costruttore del Sequencer si
fermasse a "costruisce `PendingSessionCoordinator`" senza ricollegare
esplicitamente tutti e cinque i componenti (`SpeedEstimator`,
`RoutineEngine`+`RoutineEngineAdapter`, `PaceEvaluator`+`PaceEvaluatorAdapter`,
`DistanceAccumulator`, `KalmanSpeedEngine`) alla propria `onEvent`,
un'implementazione potrebbe affidarsi a questi default — ottenendo un
Sequencer che compila e gira, ma **perde in silenzio**
`DistanceChainBrokenEvent`/`SpeedChainBrokenEvent` (emessi da
`DistanceAccumulator`/`SpeedEstimator` stessi, non solo quelli emessi
da `SessionCoordinator.process()`), `RoutinePhaseChangedEvent`, e
`PaceCandidateStartedEvent`/`PaceCandidateAbandonedEvent` — oltre a
rompere il cast diagnostico sotto, se `speedEngine` non fosse lo stesso
riferimento passato al coordinator. `paceSmoother` ha anch'esso un
default (`EmaPaceSmoother()`), ma è un algoritmo EMA vero, non un
no-op, e oggi `beginTracking()` si affida già a quello stesso default
senza passarne uno esplicito — qui il silenzio è corretto, non un buco.

### Stato interno posseduto dal Sequencer

- Il buffer di causal-ordering (`pendingEvents: MutableList<SessionEvent>`,
  mirror di `pendingEventsDuringTick` di oggi) — usato da **entrambi**
  `onLocationFix` e `onTimerTick`, non solo dal primo. A differenza di
  oggi non serve un flag `Boolean` gemello di `bufferingLocationEvents`:
  quel flag esiste oggi solo perché la closure `onEvent` di
  `RunSessionService` ha due rami (bufferizza o scrive sul logger) e
  deve sapere in quale si trova; la closure `onEvent` del Sequencer non
  ha un ramo "scrivi" (vedi Costruzione, sopra) — accoda sempre e
  basta, quindi non ha nulla da decidere in base a un flag. Il "buffer
  attivo" nei passi sotto è solo la finestra temporale della chiamata a
  `coordinator.tick()`/`advanceTimer()`: `pendingEvents` si accumula
  durante quella chiamata e viene svuotato subito dopo.
  Condividere un unico campo mutabile fra i due metodi è sicuro solo
  perché non vengono mai chiamati in concorrenza: sia `locationCallback`
  sia il `ticker` (`RunSessionService.kt:68,237`) girano sullo stesso
  `Looper.getMainLooper()` oggi, quindi il Sequencer eredita questa
  stessa garanzia dal suo unico chiamante in `:android-connector` (non
  introduce concorrenza propria). Ogni chiamata a `onLocationFix`/
  `onTimerTick` deve **svuotare il buffer alla fine della propria
  chiamata** (`.toList()` + `.clear()`, mirror di
  `RunSessionService.kt:391-393`) — senza questo passo esplicito,
  eventi vecchi/duplicati filtrerebbero nella chiamata successiva.
- Il contatore di sequenza (`locationSequenceNumber`, un intero,
  resettato a 0 dalla costruzione ex-novo del Sequencer a ogni sessione).
- Un `Long` (`lastTimerSecond`, mirror del campo omonimo del Service
  oggi) per il dedup al secondo di `onTimerTick` — nessun motivo per
  restare fuori dal Sequencer.
- Un altro `Long` (`lastDeviceHealthBucket`, mirror dell'omonimo campo
  del Service) per il bucket dei 30s che decide `deviceHealthSampleDue`
  dentro `onTimerTick` — senza questo campo il calcolo della cadenza
  non avrebbe stato su cui basarsi.
- La propria istanza di `GpsFreshnessTracker` e `GpsSilentGapTracker`
  (già puri), costruite come mostrato sopra.
- `coordinator: PendingSessionCoordinator`, costruito una volta in
  Costruzione e tenuto per tutta la vita del Sequencer: sia
  `onLocationFix` (`coordinator.tick(...)`) sia `onTimerTick`
  (`coordinator.advanceTimer(...)`) lo richiamano da un metodo diverso
  dal costruttore, quindi deve persistere come campo.
- `speedEngine: KalmanSpeedEngine` (lo stesso riferimento passato a
  `coordinator` in Costruzione), tenuto per il cast
  `SpeedEngineDiagnostics` in `onLocationFix` passo 3.
- `engineSpeedKmh: Double?`, azzerato a null prima di ogni chiamata a
  `coordinator.tick()`, impostato dalla callback `onSpeedComputed`
  passata al coordinator in Costruzione, letto subito dopo la chiamata
  (dettagli in `onLocationFix` passo 5 — qui solo inventariato come
  campo).
- `sessionStartedAtElapsedMillis: Long`, lo stesso parametro del
  costruttore, tenuto come campo perché letto a ogni chiamata di
  `onLocationFix` per calcolare `LocationFixEvent.elapsedSinceStartMillis`
  (`now - sessionStartedAtElapsedMillis`, mirror di
  `RunSessionService.kt:368`) — oltre all'uso one-shot già descritto in
  Costruzione per `GpsSilentGapTracker.reset()`.
- `TICK_MILLIS` (1000ms) diventa una costante pubblica su
  `RunSessionEventSequencer`: `:android-connector` la referenzia da lì
  per il proprio scheduling (`handler.postDelayed`, `locationRequest()`)
  invece di duplicarne una copia — evita che le due cadenze divergano
  silenziosamente un giorno.

### `onLocationFix`

```kotlin
fun onLocationFix(
    sample: LocationSample,
    position: LocationPosition,
    snapshot: GnssSnapshot?,
    provider: String?,
    reliability: LocationReliability,
    qualityTier: QualityTier,
    now: Long,
): LocationFixResult

data class LocationFixResult(val events: List<SessionEvent>, val engineSpeedKmh: Double?)
```

Sostituisce il vecchio `onLocation()`+`tick()`. `position` (lat/lon/
accuratezza, tutti numeri) è **l'input principale del filtro di
Kalman** — alimenta sia `DistanceAccumulator.accept()` sia
`KalmanSpeedEngine.accept()` — senza di esso il metodo non potrebbe
calcolare né velocità né distanza. `provider`/`reliability`/
`qualityTier` sono richiesti per costruire `LocationFixEvent`
(`provider: String?`, `rejectionReasons: List<RejectionReason>`,
`qualityTier: QualityTier`), non derivabili dagli altri parametri;
`:android-connector` li calcola con un'unica chiamata a
`mapper.evaluate()`/`mapper.qualityTier()` (fondendo la chiamata
ridondante che oggi esiste due volte — una dentro `mapper.map()`, una
esplicita in `onLocation()`). `snapshot` è **nullable**: il primo/i
primi fix di quasi ogni sessione arrivano prima che qualunque snapshot
GNSS esista (la callback `GnssStatus` richiede almeno un ciclo prima di
scattare, e può non scattare mai se il permesso viene negato) — un
parametro non-nullable avrebbe costretto a inventare uno snapshot
fittizio o a crashare sul percorso più comune.

Comportamento:
1. Chiama `gpsFreshness.record(sample)` e `gpsSilentGap.recordCallback(now)`.
   Se quest'ultima ritorna non-null, `GpsSilentGapEvent(now, silentGap)`
   viene anteposto all'inizio della lista finale — nel codice reale
   questo accade **prima** della chiamata a `coordinator.tick()`,
   quindi prima ancora che `pendingEvents` cominci ad accumulare o che
   `LocationFixEvent` esista.
2. Chiama `coordinator.tick(now, sample, position)` (che internamente
   alimenta anche `DistanceAccumulator`/`KalmanSpeedEngine` con
   `position`); durante questa chiamata `onEvent` accoda in
   `pendingEvents`, che viene svuotato non appena la chiamata ritorna.
3. Costruisce il proprio `LocationFixEvent` dallo stato post-tick.
   `speedEstimatorDiagnostics` si ottiene con
   `if (reliability.reliable) (speedEngine as? SpeedEngineDiagnostics)?.let { if (it.lastAcceptWasMeasurement()) it.diagnostics() else null } else null`
   — cast contro un'interfaccia capability (vedi sotto), sul
   riferimento `speedEngine` legato nel costruttore, non su un campo
   separato. Il controllo `reliability.reliable` è **necessario e
   distinto** da `lastAcceptWasMeasurement()`: la formula reale di oggi
   (`RunSessionService.kt:377`) ha tre condizioni, non una —
   `reliability.reliable && estimator != null && estimator.getLastAcceptWasMeasurement()`.
   Senza il primo controllo, un fix inaffidabile erediterebbe
   silenziosamente la diagnostica dell'ULTIMO fix affidabile (magari
   diversi fix prima), allegandola a un `LocationFixEvent` che non
   dovrebbe averne alcuna — lo stesso tipo di errore di correttezza dei
   dati, non un crash, già discusso per `engineSpeedKmh` al punto 5
   sotto. `satelliteGeometry` è `snapshot?.geometry ?: emptyList()`.
   `elapsedSinceStartMillis` è `now - sessionStartedAtElapsedMillis`,
   sul campo omonimo tenuto dal costruttore (vedi Stato interno).
4. Svuota il buffer e costruisce la lista finale nell'ordine:
   `GpsSilentGapEvent` (se presente) → proprio `LocationFixEvent` →
   eventi bufferizzati dai motori interni (conseguenze). L'ordine
   proprio-poi-conseguenze non è arbitrario: il codice reale porta un
   commento di design esplicito (`RunSessionService.kt:92-96`,
   "Buffering tick()'s events lets the fix event be written first and
   its consequences after it") — invertirlo cambierebbe silenziosamente
   l'ordine causale scritto nel log ad ogni fix.
5. Calcola `engineSpeedKmh`: un campo privato del Sequencer che azzera
   a `null` **subito prima** di ogni chiamata interna a
   `coordinator.tick()` (mirror di `RunSessionService.kt:338`), reso
   `non-null` dal proprio `onSpeedComputed` interno solo se il fix era
   affidabile (`onSpeedComputed` scatta solo nel ramo `actualReliable`
   di `SessionCoordinator.process()`), letto DOPO la chiamata. Senza
   questo azzeramento esplicito, un fix inaffidabile che segue un fix
   affidabile erediterebbe silenziosamente la velocità del fix
   precedente — un errore di correttezza dei dati nel GPX esportato,
   non un crash, il tipo di difetto più difficile da scoprire dopo il
   fatto. Nessun altro campo del Sequencer segue questo stesso schema
   "azzera-prima-di-chiamare".

Il guard di deduplicazione fix duplicati/stale
(`if (location.elapsedRealtimeNanos <= lastLocationElapsedRealtimeNanos) return`,
confronto alla precisione del **nanosecondo** sul `Location` grezzo)
**resta in `:android-connector`**, eseguito prima ancora di chiamare
`onLocationFix` — `LocationSample` porta solo `elapsedRealtimeMillis`
(millisecondi), nessun campo al nanosecondo esiste nel modello puro, né
dovrebbe (sarebbe un tipo legato a `Location`).

### `onTimerTick`

```kotlin
fun onTimerTick(now: Long): TimerTickResult
data class TimerTickResult(val events: List<SessionEvent>, val deviceHealthSampleDue: Boolean)
```

Sostituisce il vecchio `tickTimer()` — chiamato dal layer Android una
volta al secondo dal suo stesso `Handler` (comportamento di scheduling
identico a oggi, resta in `:android-connector`). È un percorso
**indipendente** dall'arrivo di un fix GPS: nel codice reale,
`tickTimer()` è l'unico posto che rileva un'interruzione GPS
(`gpsFreshness.isOutage(...)`) e l'unico che campiona salute-dispositivo
— descriverlo come parte dello stesso flusso di `onLocationFix`
legherebbe entrambi all'arrivo di un fix, l'opposto del loro scopo.

Comportamento:
1. Dedup al secondo tramite `lastTimerSecond`.
2. Calcola l'eventuale interruzione GPS (`gpsFreshness.isOutage(now)`)
   e chiama `coordinator.advanceTimer(now, signalGpsOutage = ...)`;
   come in `onLocationFix`, `pendingEvents` accumula durante questa
   chiamata interna — `tick()` e `advanceTimer()` chiamano entrambe la stessa
   `process()` interna, che può emettere `AudioCueEmittedEvent`/
   `DistanceChainBrokenEvent`/`SpeedChainBrokenEvent` anche durante un
   tick puramente temporale, senza alcun fix GPS di mezzo. Senza il
   buffer anche qui, questi eventi andrebbero persi (non solo
   disordinati): `:core` non ha un logger su cui farli cadere come fa
   oggi il Service.
3. Svuota il buffer e ritorna gli eventi raccolti così come sono stati
   emessi (nessun riordino necessario qui: l'ordine relativo fra
   `DistanceChainBrokenEvent`/`SpeedChainBrokenEvent` e
   `AudioCueEmittedEvent` dentro una chiamata a `process()` è già
   corretto per costruzione, essendo una lista che si accoda in ordine
   di emissione), più `deviceHealthSampleDue` — flag puro basato solo
   sul bucket di 30s trascorso (nessun dato Android nel calcolo).

Se `deviceHealthSampleDue` è true, il layer Android legge
`BatteryManager`/`PowerManager` e chiama `recordDeviceHealthSample(batteryPercent, thermalStatus, now): SessionEvent`
— un metodo puro separato che costruisce solo l'evento con i valori già
letti dal chiamante (`DeviceHealthSampledEvent` non ha un campo di
sequenza, a differenza di `LocationFixEvent` — nessuna numerazione da
fare qui). Il layer Android **antepone** quell'evento a
`TimerTickResult.events` nel flusso da dispatchare, non lo aggiunge in
coda: nel codice reale, `sampleDeviceHealthIfDue` scatta prima di
`advanceTimer` nello stesso tick — se entrambi generano eventi nello
stesso momento (plausibile: le routine a intervalli spesso usano durate
come 30s che coincidono col bucket salute-dispositivo),
`DeviceHealthSampledEvent` deve comparire prima. La parte pura non
tocca mai `BatteryManager`/`PowerManager`; la cadenza dei 30s resta
decisa in `:core`, la lettura resta in `:android-connector`.

### Eventi che non attraversano il Sequencer

`SessionStarted` (costruito una volta in `beginTracking()`) e
`AppLifecycleChangedEvent` (foreground/background, riavvio di sistema)
sono oggi loggati direttamente tramite lo stesso `onEvent` condiviso,
fuori da qualunque tick — non attraversano `RunSessionEventSequencer`.
Sono costruiti ed emessi direttamente da `:android-connector`, che
chiama `SessionEventLogger.log(...)` per loro senza passare dal
Sequencer. Nessuna delle due classi contiene logica di decisione
(riportano solo fatti Android: stato del ciclo di vita, informazioni di
sistema all'avvio) — restano quindi interamente in `:android-connector`.

### Ciclo di vita del campo che sostituisce `coordinator`

Il campo `coordinator: PendingSessionCoordinator?` di oggi ha tre usi
nel Service: guard di riavvio in `onStartCommand`
(`if (!starting && coordinator == null) startSession()`), dispatch
(`coordinator?.tick(...)`, `coordinator?.advanceTimer(...)`), e
azzeramento in `stopSession()` (`coordinator = null`). Il Service tiene
un campo `sequencer: RunSessionEventSequencer?` (sostituisce
`coordinator` 1:1) con lo stesso identico ciclo di vita sui tre punti:
il guard di riavvio diventa `if (!starting && sequencer == null) startSession()`,
il dispatch chiama `sequencer?.onLocationFix(...)`/
`sequencer?.onTimerTick(...)`, e **`stopSession()` deve azzerarlo**
(`sequencer = null`). Senza questo terzo punto, `sequencer` resterebbe
sempre non-null dopo la prima sessione — il guard di riavvio non
tornerebbe mai vero, e l'app non potrebbe più avviare una seconda
sessione. Non un caso limite: si romperebbe ad ogni singolo avvio dopo
il primo.

### Interfacce di supporto

**`RunPreferencesSource`** (in `:android-connector`, non `:core` —
nessuna classe di `:core` la consuma):

```kotlin
interface RunPreferencesSource {
    suspend fun current(): RunPreferences
}
```

Risolve una divergenza già reale oggi (implicita): `RunSessionService.startSession()`
chiama direttamente `RunPreferencesStore(this).preferences.first()`
(DataStore-backed, `:app`-only), mentre la copia `RunPreferences` di
`:wear` bypassa `RunPreferencesStore` del tutto, costruendo un
`RunPreferences(...)` letterale hardcoded. Il glue Android condiviso
dipende da `RunPreferencesSource`, non dalla classe concreta: `:app`
fornisce un adapter che wrappa `RunPreferencesStore` (un semplice
`store.preferences.first()`, dispatcher-agnostico: DataStore gestisce
la propria IO internamente); `:wear` fornisce la sua implementazione
hardcoded.

**`RunSessionHostConfig`** (in `:android-connector`, stesso motivo):

```kotlin
interface RunSessionHostConfig {
    fun runPreferencesSource(): RunPreferencesSource
    val notificationTitle: String
}
```

`notificationTitle` è bundlato insieme (non una seconda interfaccia
separata) perché entrambi i valori sono obbligatori per ogni modulo —
non è una capability opzionale come `SpeedEngineDiagnostics`, quindi
qui il bundling è la scelta corretta. `RunSessionService` è istanziato
da Android via manifest, costruttore senza argomenti — non esiste
alcun framework DI nel progetto (zero Dagger/Hilt/Koin). Si adotta lo
stesso pattern di `WorkManager.Configuration.Provider`: la classe
`Application` di ciascun modulo implementa `RunSessionHostConfig` (né
`:app` né `:wear` ne hanno una propria oggi — nuova aggiunta piccola:
wrappa `RunPreferencesStore` + `"Paco Run"` in `:app`, l'implementazione
hardcoded + `"Paco Run Wear"` in `:wear`, più l'attributo
`android:name` sul tag `<application>` di ciascun manifest).
`RunSessionService` — che resta interamente ed esclusivamente in
`:android-connector`, mantenendo il proprio package attuale
(`dev.fred.pacorun.service`, invariato dallo spostamento — nessun
motivo di rinominarlo), riferito nei due manifest col nome pienamente
qualificato `dev.fred.pacorun.service.RunSessionService` (non la
scorciatoia relativa `.service....`, che risolverebbe contro il
package del modulo che lo dichiara, non quello di `:android-connector`)
— fa `(application as RunSessionHostConfig)` in `onCreate()`. Nessuno stato
globale mutabile, nessuna sottoclasse Service per modulo.

`R.drawable.ic_run` (icona notifica) e i 14 `R.raw.*` (audio dei cue)
compilano oggi solo perché `:app` e `:wear` condividono lo stesso
`namespace`. `:android-connector` ottiene il proprio `namespace` e una
cartella `res/` con le copie identiche di questi file (contenuto
verificato identico fra `:app`/`:wear` — nessuna divergenza di valore).
`R.string.app_name` invece ha valori realmente diversi ("Paco Run" vs
"Paco Run Wear") — centralizzarlo cambierebbe silenziosamente il
titolo della notifica su una piattaforma. Si risolve con
`RunSessionHostConfig.notificationTitle`: `RunSessionService` continua a
referenziare direttamente `R.drawable.ic_run` per l'icona (identica —
`setSmallIcon` prende un ID risorsa, nessun `getString()` coinvolto), e
sostituisce l'attuale `getString(R.string.app_name)` con
`hostConfig.notificationTitle` per il testo che diverge.

**`SpeedEngineDiagnostics`** (capability interface, in `:core`,
implementata da `KalmanSpeedEngine`):

```kotlin
interface SpeedEngineDiagnostics {
    fun diagnostics(): SpeedEstimatorDiagnostics
    fun lastAcceptWasMeasurement(): Boolean
}
```

Elimina un vizio di accoppiamento di oggi: `RunSessionService` tiene
due riferimenti allo stesso `SpeedEstimator` sottostante (`coordinator`
e un separato `speedEstimatorForDiagnostics`), sincronizzati solo per
convenzione. Nel nuovo design il campo `speedEstimatorForDiagnostics`
scompare del tutto — non resta "in un altro posto": il Sequencer
costruisce da sé `speedEngine` una sola volta (vedi Costruzione sopra)
e il cast lo interroga direttamente. `DopplerSpeedEngine` resta
`SessionSpeedEngine` puro, senza dover fingere di avere diagnostica che
non ha. `lastAcceptWasMeasurement()` preserva il gating esatto di oggi
(`if (reliability.reliable && estimator != null && estimator.getLastAcceptWasMeasurement())`
— `reliability.reliable` è indipendente dall'interfaccia,
`estimator != null` è già gestito dal cast sicuro `as?`).

I sei accessor diagnostici di `SpeedEstimator` che alimentano
`diagnostics()` (`getLastInnovationEma`, `getLastProcessNoiseRegime`,
`getLastAccuracyGateEngaged`, `getLastRobustWeight`,
`getLastPostBurstExemptionApplied`, `getLastAcceptWasMeasurement`) sono
oggi `internal` — e il loro unico chiamante reale è
`RunSessionService.kt:377-383`. `SpeedEstimator` si sposta a `:core`
al passo 3; `RunSessionService.kt` resta in `:app` fino al passo 6:
per tre passi (3, 4, 5) il chiamante non compilerebbe più, perché
`internal` è applicato per modulo Gradle, non per albero dei
sorgenti. Stesso identico motivo, stessa identica soluzione già
applicata a `RunSessionStore.publish`: questi sei accessor diventano
**pubblici** al passo 3, insieme allo spostamento di `SpeedEstimator`
— un cambio di design permanente, non un dettaglio transitorio della
migrazione. Esiste anche un settimo accessor `internal`, `getLastProcessNoise()` —
il suo unico chiamante reale è `SpeedEstimatorTest.kt`
(`SpeedEstimatorScenarioTest.kt` lo cita solo in un commento KDoc, non
lo invoca), già nella lista dei test che si spostano al passo 3 insieme
a `SpeedEstimator`: resta `internal` senza problemi, perché test e
produzione condividono di nuovo lo stesso modulo da quel momento.

## `DopplerSpeedEngine` / `KalmanSpeedEngine` — verifica di indipendenza

Verificato per richiesta esplicita dell'owner: le due implementazioni di
`SessionSpeedEngine` sono completamente indipendenti, zero codice
condiviso/fuso, ~6 righe ciascuna. `DopplerSpeedEngine` non è usato in
alcun percorso di produzione oggi — esiste solo come fixture di test in
`SessionCoordinatorTest.kt`/`SessionSpeedEngineTest.kt` (~20 call site).
La migrazione lo sposta in `:core` così com'è; non viene rimosso in
questa estrazione (fuori scope, YAGNI).

## Data flow (dopo l'estrazione)

Due percorsi paralleli, indipendenti l'uno dall'altro:

**Percorso A — arrivo di un fix GPS:**
1. Callback location Android (in `:android-connector`) riceve un
   `Location` grezzo dall'OS.
2. `:android-connector` **non** chiama `mapper.map(location, now)` (il
   metodo di comodo di oggi, che internamente richiama `evaluate()` e
   scarta il dettaglio della `LocationReliability` risultante, tenendo
   solo `.reliable` — vedi tabella di allocazione, quell'overload si
   elimina). Estrae invece da `location` esattamente ciò che oggi
   quell'overload estrae internamente, una volta sola:
   `elapsedRealtimeMillis = TimeUnit.NANOSECONDS.toMillis(location.elapsedRealtimeNanos)`,
   `ageMillis = now - elapsedRealtimeMillis`, e i tre campi opzionali
   via `takeIf` (`speedMps`, `accuracyMps` da
   `speedAccuracyMetersPerSecond`, `horizontalAccuracyMeters`).
   Con questi valori chiama `mapper.evaluate(speedMps, accuracyMps,
   ageMillis, horizontalAccuracyMeters)` **una sola volta**, ottenendo
   `reliability: LocationReliability`, poi costruisce da sé
   `LocationSample(speedKmh = (speedMps ?: 0f) * 3.6f, speedAccuracyKmh
   = accuracyMps?.times(3.6f), reliable = reliability.reliable,
   elapsedRealtimeMillis = elapsedRealtimeMillis)` — stesso identico
   risultato di oggi, ma senza scartare `reliability`, che serve anche
   per `qualityTier = mapper.qualityTier(reliability, accuracyMps,
   horizontalAccuracyMeters)`. Lo stesso `elapsedRealtimeMillis`
   calcolato qui è anche quello che finisce in `position` (punto 3) —
   un solo calcolo, non due. Questo è il "fondere le due chiamate
   ridondanti" di cui parla il Piano di test caso 7: oggi `evaluate()`
   viene chiamato due volte (una dentro `map()`, una esplicita) con gli
   stessi input, e `elapsedRealtimeMillis` viene calcolato due volte
   dallo stesso `location.elapsedRealtimeNanos` (una dentro `map()`,
   una per costruire `position`) — qui bastano una chiamata a
   `evaluate()` e un calcolo di `elapsedRealtimeMillis`.
3. Il layer Android costruisce `position: LocationPosition` e lo
   snapshot GNSS puro dai dati già raccolti dalla callback `GnssStatus`
   (`null` se nessun callback è ancora scattato — caso comune nei primi
   fix di una sessione) e chiama
   `sequencer.onLocationFix(sample, position, snapshot, location.provider, reliability, qualityTier, now)`
   — solo dati puri attraversano il confine.
4. Il Sequencer ritorna `LocationFixResult`: eventi nell'ordine
   `GpsSilentGapEvent` (se presente) → `LocationFixEvent` → eventi
   bufferizzati dal motore, più `engineSpeedKmh`.

**Percorso B — timer periodico, una volta al secondo, indipendente dal
GPS (stesso `Handler.postDelayed` di oggi, resta in `:android-connector`):**
5. Il layer Android chiama `sequencer.onTimerTick(now)`.
6. Il Sequencer ritorna `TimerTickResult`: eventi di routine/interruzione
   GPS raccolti durante la chiamata interna, più il flag
   `deviceHealthSampleDue`.
7. Se il flag è true, il layer Android legge batteria/termico, chiama
   `sequencer.recordDeviceHealthSample(...)`, e **antepone** quell'evento
   al flusso da dispatchare.

**Comune a entrambi i percorsi:**
8. Il layer Android riceve la lista finale di eventi e dispatcha ogni
   evento a `SessionEventLogger` (append JSONL) e `CuePlayer`
   (audio/TTS), più l'aggiornamento notifica. Solo nel percorso A, il
   layer Android usa anche `LocationFixResult.engineSpeedKmh` per
   accumulare un `TrackPoint` in una lista (comportamento identico a
   oggi). `GpxWriter`/`GpxExporter` **non** scrivono ad ogni evento
   dispatchato: `TrackPoint` viene solo accumulato per ogni fix; la
   scrittura GPX vera e propria avviene **una sola volta per sessione**,
   a fine corsa (`exportTrackAsGpx()`, chiamata da `stopSession()` su un
   thread separato) — completamente disaccoppiata dal meccanismo di
   dispatch per-evento.
9. La UI di `:app`/`:wear` osserva lo stato esposto da `:core` come già
   fa oggi — questo pezzo non cambia, resta fuori scope.

Stesso schema identico per `:app` e `:wear`, stesso codice condiviso in
`:android-connector` — questo è l'intero punto dell'estrazione.

## Gestione errori

Nessuna gestione nuova da inventare: l'estrazione sposta codice, non
ridisegna il comportamento esistente. `:android-connector` continua a
catturare tutto ciò che è Android-specifico (permesso revocato,
provider GPS che sparisce, ecc.) esattamente come fa oggi
`RunSessionService`, prima di passare dati puri a `:core`. `:core` non
vede mai un'eccezione Android — non per convenzione, ma perché il
modulo non ha l'SDK Android sul classpath.

## Meccanica Gradle

- `:core` → plugin `kotlin("jvm")`. Test JUnit puri (beneficio concreto:
  i test degli engine, oggi legati al test runner Android solo perché
  vivono in `:app`, diventano JVM puri e più veloci).
- `:android-connector` → plugin `com.android.library`,
  `implementation(project(":core"))`. Porta con sé le dipendenze Android
  usate dai file che vi si spostano (es. `play-services-location` per
  `FusedLocationProviderClient`).
- `:app` e `:wear` dipendono **esplicitamente da entrambi**
  (`implementation(project(":core"))` e
  `implementation(project(":android-connector"))`), non solo
  transitivamente. Scelta deliberata sopra l'alternativa
  (`api(project(":core"))` dentro `android-connector`): la dipendenza
  esplicita evita che un cambiamento futuro interno ad
  `android-connector` alteri silenziosamente il grafo delle dipendenze
  di chi lo consuma.
- Niente Kotlin Multiplatform: entrambi i consumatori (`:app`, `:wear`)
  sono già Android/JVM — KMP sarebbe complessità non richiesta ora.
- `settings.gradle.kts` aggiunge `include(":core", ":android-connector")`.

## Ordine di migrazione

Deciso: creare i moduli vuoti, poi **spostare** (non copiare) i file da
`:app` in ordine dal più puro/semplice al più delicato, verificando che
la suite di test completa di `:app` resti verde dopo ogni passo.

**Principio generale su spostamento dei test**: ogni file di test che
referenzia codice spostato in un dato passo si sposta nello stesso
passo, nello stesso modulo — senza eccezioni, indipendentemente dal
fatto che usi accessori `internal` o no. Nessun componente reso a sé
stante deve esporre API all'esterno solo per i test, e nessun test
resta a testare codice ormai altrove. Mappatura completa (verificata su
tutti i 24 file in `app/src/test` e 10 in `app/src/androidTest`):

1. Scaffolding: creare `:core` e `:android-connector` vuoti, wiring
   Gradle (incluso il `namespace` di `:android-connector` fin da qui —
   un modulo `com.android.library` lo richiede per esistere), nessun
   file spostato ancora.
2. Modelli e utility pure senza dipendenze interne complesse
   (`LocationSample` — con `DEFAULT_MAX_AGE_MILLIS` —, `SessionEvent`,
   `PaceModels`, `RoutineModels`, `RunPreferences`). Test: `RunPreferencesTest.kt`.
3. Motori (`SpeedEstimator`, `PaceEvaluator`, `RoutineEngine`,
   `RunSessionStore` — coordinators, `SessionSpeedEngine`/
   `SessionPaceSmoothingEngine`, `DopplerSpeedEngine`/`KalmanSpeedEngine`,
   i tracker, `DistanceAccumulator`, `EmaPaceSmoother`,
   `SessionStartPrerequisites`, `SessionNotificationTracker`),
   `SessionEventWriter`, `GpxWriter`, `RunPreferences.paceConfig()`
   (resa top-level e non più `private` — vedi Allocazione dei file:
   oggi è un membro privato di `RunSessionService.kt`, che resta in
   `:app` fino al passo 6; se non de-privatizzata e spostata qui, il
   codice non compilerebbe già dal passo 3, quando `PaceEvaluator`
   inizia a richiedere un `PaceConfig` costruibile solo tramite questa
   funzione).

   `RunSessionStore.publish` (oggi `internal`) diventa **pubblico** —
   non un dettaglio transitorio ma un cambio di design permanente:
   `RunSessionService.kt` (che chiama `publish`) resta in `:app` fino
   al passo 6, poi si sposta in `:android-connector` — in ogni caso un
   modulo diverso da `RunSessionStore`, quindi la visibilità pubblica
   serve fin da questo passo, non solo a partire dal passo 6.

   Stesso motivo, stessa soluzione per i sei accessor diagnostici di
   `SpeedEstimator` (`getLastInnovationEma`, `getLastProcessNoiseRegime`,
   `getLastAccuracyGateEngaged`, `getLastRobustWeight`,
   `getLastPostBurstExemptionApplied`, `getLastAcceptWasMeasurement`,
   oggi `internal`): il loro unico chiamante reale
   (`RunSessionService.kt:377-383`) resta in `:app` fino al passo 6,
   quindi diventano **pubblici** anche loro fin da questo passo, insieme
   allo spostamento di `SpeedEstimator` — altrimenti la build di `:app`
   si romperebbe per i passi 3, 4 e 5.

   `RunSessionStore.kt` importa e usa direttamente `AudioCue` (l'enum
   con `@param:RawRes val resourceId: Int` definito dentro `CuePlayer.kt`,
   destinato a `:android-connector`) come tipo del parametro
   `onCues: (List<AudioCue>, Float) -> Unit` su **entrambi**
   `SessionCoordinator` e `PendingSessionCoordinator`, e per convertire
   `RoutineCue`/`Cue` in `AudioCue` via
   `AudioCue.valueOf(it.name)` — bloccherebbe la compilazione al passo
   3. `Cue` (5 casi) e `RoutineCue` (9 casi) coprono esattamente e senza
   sovrapposizioni i 14 casi di `AudioCue` — `AudioCue` è solo la loro
   unione più un `resourceId` Android per caso. Si introduce quindi
   (vedi Allocazione dei file):

   ```kotlin
   sealed interface CueSignal {
       val name: String
       data class Pace(val cue: Cue) : CueSignal { override val name get() = cue.name }
       data class Routine(val cue: RoutineCue) : CueSignal { override val name get() = cue.name }
   }
   ```

   La riga di `RunSessionStore.kt` che oggi fa
   `routine.cues.map { AudioCue.valueOf(it.name) } + listOfNotNull(pace.cue?.let { AudioCue.valueOf(it.name) })`
   diventa
   `routine.cues.map { CueSignal.Routine(it) } + listOfNotNull(pace.cue?.let { CueSignal.Pace(it) })`
   — niente più cast per nome. `onCues` diventa `(List<CueSignal>) -> Unit` — **senza** un secondo parametro `Float`
   per il volume: il motore leggeva `preferences.cueVolume` solo per
   spedirlo al chiamante, che ha già lo stesso `preferences` in scope
   nello stesso punto (`RunSessionService.kt:220`); il callback puro non
   deve portare nessun concetto audio, né file né volume — solo "è
   successo questo cue". Importante anche in vista di un futuro
   text-to-speech: sostituire la riproduzione file-audio con TTS toccherà
   solo `:android-connector`, zero righe nel motore.

   La proprietà `name` su `CueSignal` (vedi dichiarazione sopra) esiste
   apposta per `AudioCueEmittedEvent.cueType: String` — oggi costruito
   con `it.name` dove `it: AudioCue`, un `enum class` vero. Senza `name`
   su `CueSignal`, quella riga non compilerebbe più: si delega al nome
   dell'enum puro avvolto (`Cue`/`RoutineCue`), stesse stringhe di
   output di oggi (`SessionCoordinatorTest.kt` asserisce valori come
   `"ROUTINE_START"`/`"TOO_FAST"` su `cueType`, invariati da questo
   fix), nessun cambiamento di comportamento osservabile.

   Al passo 3, `RunSessionService.kt` e `CuePlayer.kt` sono ancora
   entrambi in `:app` — si aggiunge quindi, in questo stesso passo,
   `fun CueSignal.toAudioCue(): AudioCue = when (this) { is CueSignal.Pace -> cue.toAudioCue(); is CueSignal.Routine -> cue.toAudioCue() }`
   (riusando le estensioni `Cue.toAudioCue()`/`RoutineCue.toAudioCue()`
   già presenti in `CuePlayer.kt`), e si modifica
   `RunSessionService.kt:220` in
   `onCues = { cues -> cuePlayer?.enqueue(cues.map { it.toAudioCue() }, preferences.cueVolume) }`
   — unica riga toccata al passo 3, il resto del file resta in `:app`
   invariato fino al passo 6. Al passo 5, quando `CuePlayer.kt`/
   `AudioCue` si spostano in `:android-connector`, `CueSignal.toAudioCue()`
   si sposta con loro.

   Test spostati in questo passo: `SessionEventWriterTest.kt`,
   `GpxWriterTest.kt`, `PaceEvaluatorTest.kt`, `RoutineEngineTest.kt`,
   `DistanceAccumulatorTest.kt`, `EmaPaceSmootherTest.kt`,
   `GpsFreshnessTrackerTest.kt` (**da modificare in questo stesso
   passo**: oggi costruisce i suoi fixture con
   `LocationSampleMapper().map(...)` — la classe intera, destinata a
   `:android-connector` — non solo `DEFAULT_MAX_AGE_MILLIS` già
   risolto sopra. Se spostato invariato, dipenderebbe da un tipo non
   ancora disponibile in `:core` e comunque destinato al modulo
   sbagliato. Il test non ha realmente bisogno del mapper: va
   modificato per costruire `LocationSample(...)` direttamente, unico
   dato di cui i suoi due casi hanno bisogno), `GpsSilentGapTrackerTest.kt`,
   `RobustOutlierRejectionTest.kt`, `SessionCoordinatorRealRunTest.kt`,
   `SessionCoordinatorTest.kt` (da aggiornare per asserire su
   `CueSignal` invece di `AudioCue` — verificato: ~28 punti nel file
   usano `onCues` a due argomenti (`{ cues, _ -> ... }`) o liste
   `MutableList<AudioCue>`, non un cambio di una riga isolata),
   `SessionServiceSupportTest.kt`,
   `SessionSpeedEngineTest.kt`, `SpeedEstimatorRealRunTest.kt`,
   `SpeedEstimatorScenarioTest.kt`, `SpeedEstimatorTest.kt`,
   `SyntheticTrajectory.kt` (fixture), `SyntheticTrajectoryTest.kt`.
4. Introduzione di `SpeedEngineDiagnostics`.
5. File Android-only condivisi verso `:android-connector`
   (`LocationSampleMapper`, `SessionEventLogger`, `GpxExporter`,
   `CuePlayer` con la classe sorella `AudioCueQueue` (stesso file, non
   annidata), e
   `RunPreferencesSource`). I 14 file audio dei cue si spostano **in
   questo stesso passo**, insieme a `CuePlayer.kt` — non al passo 6 —
   perché `CuePlayer.kt` referenzia `R.raw.*` e non compilerebbe
   altrimenti. L'icona della notifica resta al passo 6 (l'unico
   consumatore, `RunSessionService`, non si sposta prima). Test:
   (locale) `LocationSampleMapperTest.kt`, `AudioCueQueueTest.kt`;
   (strumentati) `GpxExporterTest.kt`, `CuePlayerAudioDuckingTest.kt`,
   `CuePlayerLooperTest.kt`, `CueAssetsTest.kt`.
6. **Ultimo su questo branch**: estrazione di `RunSessionEventSequencer`
   da `RunSessionService` (il pezzo più delicato, fatto per ultimo di
   proposito, dopo che l'impalcatura dei moduli è provata stabile).
   Include: `RunSessionHostConfig` con la nuova classe `Application`
   per `:app`, l'attributo `android:name` sul manifest, il `res/` di
   `:android-connector` per l'icona, `GnssSnapshot` reso pubblico e
   spostato in `:core`, `LocationReliability` spostata da
   `LocationSampleMapper.kt` a `eventlog/SessionEvent.kt` in `:core`, e
   `RunSessionService` spostato per intero in `:android-connector`,
   riferito col nome pienamente qualificato. In questo stesso passo si
   riscrive il call site di `onLocation()` per la fusione (vedi Data
   Flow, Percorso A) e si elimina di conseguenza l'overload
   `LocationSampleMapper.map(location: Location, ...)`, rimasto senza
   chiamanti — vedi la nota sulla riga di `LocationSampleMapper.kt`
   nella tabella di allocazione. `LocationSampleMapperTest.kt` (già
   spostato al passo 5, invariato) non ne è toccato: esercita solo
   l'overload primitivo. Nessun file di test
   esistente copre oggi la logica di sequenza/buffering di
   `RunSessionService` — i 12 casi del Piano di test sotto sono l'unica
   rete di sicurezza per questa logica.
7. `:wear` smette di usare le copie `cp`-duplicate e dipende da
   `:core`/`:android-connector`; le copie duplicate vengono eliminate.
   Include la propria nuova classe `Application` (implementazione
   hardcoded di `RunSessionHostConfig`, con `"Paco Run Wear"`) e il
   proprio attributo `android:name` — analogo al passo 6, ma qui perché
   `:wear` non esiste sul branch dove avviene il passo 6.

`main` non ha il modulo `:wear` (esiste solo su `feature/wear-os`) — il
passo 7 è ineseguibile su `feature/core-extraction` (forcato da
`main`). Corrisponde al flusso già descritto in `CLAUDE.md` per il
lavoro sul core engine: i passi 1-6 avvengono su `feature/core-extraction`
(che non tocca mai `:wear`); a fine passo 6, merge in `main`; poi merge
`main` dentro `feature/wear-os`; il passo 7 avviene lì.

`:wear` resta intoccato (continua a usare le sue copie duplicate) fino
al passo 7 — questo limita il rischio: se qualcosa va storto durante i
passi 1-6, `:wear` non è mai in uno stato rotto a metà.

**File di test che restano in `:app`, invariati** (testano codice
UI/manifest-specifico che resta per-modulo): `LocationReadinessTest.kt`,
`DurationControlTest.kt`, `SessionVisualStateTest.kt` (logica di
presentazione, non motore), `PacoRunThemeTest.kt`,
`ForegroundServiceManifestTest.kt` (verifica la dichiarazione del
Service nel manifest di questo specifico modulo), `StartupTest.kt`,
`GpsAcquisitionScreenTest.kt`, `HomeScreenTest.kt`,
`HoldToStopButtonTest.kt`.

## Piano di test per `RunSessionEventSequencer`

Dodici casi da verificare (oltre a preservare 1:1 la copertura esistente
di `RunSessionService`, che viene spostata assieme al codice):

1. Rifiuto di fix duplicati/stale — **non** un test di
   `RunSessionEventSequencer` (il guard resta in `:android-connector`,
   sul `Location.elapsedRealtimeNanos` grezzo, prima di chiamare
   `onLocationFix`) — ma un test di `:android-connector` da preservare
   1:1, spostato insieme al resto del Service al passo 6.
2. Ordinamento causale: un evento generato da un motore interno durante
   `onLocationFix` deve comparire nella lista ritornata **DOPO** il
   `LocationFixEvent` costruito dal Sequencer stesso con lo stato
   post-tick — non prima.
3. `onTimerTick` chiamato una volta al secondo fa avanzare le fasi
   della routine e rileva un'interruzione GPS esattamente come oggi,
   **indipendentemente da eventuali fix GPS nello stesso intervallo** —
   il test deve simulare un periodo senza alcuna chiamata a
   `onLocationFix`, e asserire direttamente sul contenuto di
   `TimerTickResult.events`: (a) durante un'interruzione GPS, la lista
   contiene `DistanceChainBrokenEvent`/`SpeedChainBrokenEvent`; (b) in
   un tick puramente temporale che fa scattare un cue di routine (es.
   `RUN_IN_TEN`) senza alcuna interruzione, la lista contiene
   `AudioCueEmittedEvent`. Verificare solo lo stato pubblicato
   (`RunSessionState.gpsReliable` via `publish()`) non basta: `publish()`
   è un canale completamente separato da `onEvent`/dal buffer, quindi
   un test che controlla solo quello passerebbe identico anche senza il
   meccanismo di raccolta eventi di `onTimerTick`.
4. Associazione corretta dello snapshot GNSS al fix a cui appartiene
   (in `onLocationFix`).
5. Cadenza dei bucket di stato del dispositivo: `onTimerTick` deve
   ritornare `deviceHealthSampleDue = true` esattamente con la stessa
   cadenza di 30s di oggi, e `recordDeviceHealthSample(...)` deve
   produrre un evento equivalente a quello che il vecchio
   `sampleDeviceHealthIfDue()` costruiva. Verifica anche la
   **posizione**: quando un tick fa scattare sia `deviceHealthSampleDue`
   sia eventi di routine/interruzione GPS, `DeviceHealthSampledEvent`
   deve comparire PRIMA di quegli eventi nel flusso dispatchato, non
   dopo.
6. Reset del numero di sequenza, comportamento identico a oggi.
7. Test di regressione prima/dopo sulla fusione delle due chiamate
   duplicate a `LocationSampleMapper.evaluate()` (verificato che gli
   input sono identici entrambe le volte — sicuro fonderle in una sola
   chiamata).
8. `onLocationFix` chiama `gpsFreshness.record(sample)` su ogni fix
   (verificabile osservando che `onTimerTick` successivo NON rileva
   un'interruzione dopo un fix affidabile recente); e
   `gpsSilentGap.recordCallback(now)` produce un `GpsSilentGapEvent`
   nella lista ritornata quando il gap supera la soglia, assente
   altrimenti. Verifica anche la **posizione**: `GpsSilentGapEvent`,
   quando presente, deve comparire PRIMA del `LocationFixEvent` nella
   stessa lista, non dopo.
9. Collegamento dei quattro motori interni alla `onEvent` del
   Sequencer, distinto dagli eventi che `SessionCoordinator.process()`
   emette per conto proprio (un regresso che scollegasse silenziosamente
   uno dei quattro non verrebbe scoperto da nessuno degli altri 11 casi).
   Il test deve pilotare scenari che facciano scattare, e verificare la
   presenza nella lista ritornata di, ciascuno dei quattro:
   - `RoutinePhaseChangedEvent` (da `RoutineEngine`, non da
     `SessionCoordinator`).
   - `PaceCandidateStartedEvent`/`PaceCandidateAbandonedEvent` (da
     `PaceEvaluator`).
   - `SpeedChainBrokenEvent(UNRELIABLE_OR_INVALID_FIX)` (da
     `SpeedEstimator`) — distinto da
     `SpeedChainBrokenEvent(STALE_NO_RECENT_RELIABLE_FIX)`, che viene
     da `SessionCoordinator.process()` e non prova questo collegamento.
     Raggiungibile solo con una **posizione non valida** (lat/lon non
     finiti), non con un flag "non affidabile": `SessionCoordinator.process()`
     chiama sempre `speedEngine.accept(..., reliable = true)` con
     `reliable` hardcoded (`RunSessionStore.kt:266`) — un fixture che
     provi a marcare il fix come inaffidabile non farebbe scattare
     questo evento.
   - `DistanceChainBrokenEvent(IMPLAUSIBLE_JUMP)` o
     `(UNRELIABLE_OR_INVALID_FIX)` (da `DistanceAccumulator`) —
     distinto da `DistanceChainBrokenEvent(GPS_OUTAGE_ENTERED)`, che
     viene da `SessionCoordinator.process()` e non prova questo
     collegamento. Stessa nota: `distanceEngine.accept(..., reliable = true)`
     ha anch'esso `reliable` hardcoded (`RunSessionStore.kt:313`) —
     raggiungibile solo con posizione non valida o un salto di
     posizione implausibile, non con un flag di inaffidabilità.
10. **Il rischio più grave descritto in questo intero documento — il
    ciclo di vita del campo `sequencer` — non ha oggi nessun test
    dedicato.** Nessun file di test esistente copre il guard di
    riavvio di `RunSessionService` (`onStartCommand`) né l'azzeramento
    in `stopSession()`. Il test deve verificare: (a) dopo una prima
    sessione completa (avvio + `stopSession()`), un secondo
    `ACTION_START` avvia davvero una nuova sessione (il guard
    `if (!starting && sequencer == null) startSession()` deve tornare
    vero); (b) senza l'azzeramento esplicito in `stopSession()`,
    questo stesso test fallirebbe — la seconda sessione non
    partirebbe mai. Questo caso esiste specificamente perché il
    documento stesso descrive questo comportamento come "non un caso
    limite: si romperebbe ad ogni singolo avvio dopo il primo" — il
    rischio più alto merita la rete di sicurezza più esplicita, non
    zero copertura.
11. **Azzeramento di `engineSpeedKmh` prima di ogni chiamata interna —
    stessa categoria di rischio del caso 10 (errore di correttezza dati
    silenzioso, non un crash), oggi senza copertura propria.** Il test
    deve simulare due fix consecutivi in `onLocationFix`: il primo
    affidabile (che produce un `engineSpeedKmh` non-null in
    `LocationFixResult`), il secondo inaffidabile — e verificare che il
    secondo `LocationFixResult.engineSpeedKmh` sia `null`, non il
    valore del primo fix ereditato per errore. Nessun test esistente
    (`SpeedEstimatorTest.kt`, `SessionEventWriterTest.kt`) tocca questo
    comportamento specifico del Sequencer.
12. **Gate `reliability.reliable` su `speedEstimatorDiagnostics` —
    stessa categoria di rischio dei casi 10 e 11.** Il test deve
    simulare un fix affidabile (che produce diagnostica non-null),
    seguito da un fix inaffidabile con `estimator.getLastAcceptWasMeasurement()`
    ancora vero (stato residuo dal fix precedente) — e verificare che
    `LocationFixEvent.speedEstimatorDiagnostics` del secondo fix sia
    `null`, perché `reliability.reliable` da solo deve bloccare
    l'allegazione, indipendentemente da cosa dice
    `lastAcceptWasMeasurement()`. Nessun test esistente esercita questo
    gate a tre condizioni nella sua interezza.

## Scope esplicitamente escluso da questa estrazione

- Riscrittura del meccanismo di emissione eventi da callback a
  return-value all'interno dei motori (`SpeedEstimator`/`PaceEvaluator`/
  `RoutineEngine`/`DistanceAccumulator`) — discusso, deliberatamente
  rimandato (vedi nota in Contesto).
- Rimozione di `DopplerSpeedEngine` — nessuna richiesta di farlo ora.
- Qualunque feature watch-specifica (player integrato, sensori nativi,
  UI di configurazione routine su `:wear`) — questa spec è solo
  l'estrazione strutturale; le feature arrivano dopo, come voci separate
  della roadmap wear-os.
- Sostituzione di `RunPreferencesStore` (DataStore-backed) con una
  versione condivisa — resta `:app`-only, dietro l'interfaccia
  `RunPreferencesSource`. `:wear` continua a usare la sua implementazione
  hardcoded finché non avrà una sua UI di configurazione (voce futura
  della roadmap) — a quel punto potrà scrivere una propria
  implementazione DataStore-backed senza toccare `:android-connector`.
