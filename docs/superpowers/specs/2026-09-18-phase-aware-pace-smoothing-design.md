# Design: smoothing di decisione del ritmo consapevole del cambio fase (P2)

**Data:** 2026-09-18. **Branch:** `feature/paco-run-m3e` (questo documento verrà implementato su `feature/pace-decision-smoothing`, da creare in seguito come fork di `feature/session-event-logging` — non creata qui). **Tipo di documento:** design spec — decisioni prese e giustificate qui, **nessun codice toccato**, nessun piano di implementazione scritto (quello è un documento separato, successivo). Corrisponde alla voce **P2** della roadmap (`docs/superpowers/specs/2026-09-11-precision-roadmap.md`).

## Contesto (per chi legge senza aver seguito il progetto)

**Paco Run** è un'app Android per corsa/camminata che dà feedback vocale in tempo reale sul ritmo, confrontando la velocità istantanea (`KalmanSpeedEngine`/`SpeedEstimator`, un filtro di Kalman a due assi, già validato e in produzione) con un range target configurato. `PaceEvaluator` (`app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt`) decide, campione per campione, lo stato di ritmo confermato (`TOO_SLOW`/`GOOD`/`TOO_FAST`), con un debounce a conteggio di campioni (`confirmationSamples`, default 2) prima di accettare un cambio di stato. Un secondo componente, `RoutineEngine`, guida routine a intervalli ("Cammina-Corri": alternanza RUN/WALK a durate fisse, con eventuale fase iniziale/finale a ritmo costante) e segnala ogni cambio di fase reale (`RoutineResult.profileChanged`) nello stesso tick in cui avviene.

**Il problema, confermato su corsa reale, non su sospetto.** La prima corsa reale con `SpeedEstimator` collegato ai cue dal vivo (2026-09-13) ha mostrato lo stato di ritmo confermato saltare direttamente da `TOO_SLOW` a `TOO_FAST` (o viceversa) senza passare da `GOOD`, ripetutamente. Due cicli di ricerca indipendenti, entrambi letti per intero prima di scrivere questo documento, hanno escluso la causa più ovvia e stabilito la direzione del fix:

- `docs/superpowers/specs/2026-09-13-precision-and-cue-stability-research.md` — ha confermato che il fenomeno **non è rumore del sensore**: il segnale Kalman di Paco Run era, punto per punto, leggermente più pulito di quello di Strava negli stessi istanti. La causa è la normale variabilità del ritmo di corsa umano (variabilità stride-to-stride), che eccede regolarmente una banda di velocità stretta. Un secondo run reale (2026-09-15, lo stesso analizzato in questo documento) ha ri-confermato la pulizia del segnale su tutta la corsa: 342 confronti diretti contro Strava, zero divergenze oltre 2.5 km/h, differenza media -0.06 km/h.
- `docs/superpowers/specs/2026-09-13-phase-aware-cue-smoothing-research.md` — ha stabilito l'architettura del fix: uno smoothing **EMA (media mobile esponenziale)**, non una finestra scorrevole a lunghezza fissa, applicato solo all'ingresso decisionale di `PaceEvaluator` — non al numero mostrato a schermo. Cruciale: `RoutineEngine` conosce già, con timestamp esatto, il momento di ogni cambio di fase reale (non è qualcosa da inferire statisticamente) — quindi lo stato dell'EMA va **purgato (azzerato)** in quel momento noto e lasciato **riempirsi/riassestarsi naturalmente** dopo, senza inventare una curva di ricrescita né un secondo parametro non validato. Questa è una semplificazione deliberata rispetto a un'idea precedente, scartata dopo revisione avversariale ("collassa la finestra poi fai ricrescere su una curva inventata") — questo documento non la reintroduce.

Entrambi i documenti hanno lasciato esplicitamente aperta la costante di tempo dell'EMA (quanti secondi di "memoria"), in attesa di dati reali per-tick — che ora esistono grazie a **P1** (`docs/superpowers/specs/2026-09-14-session-event-logging-design.md`, già implementato e fuso su `feature/session-event-logging`): un log strutturato JSON Lines di ogni decisione dell'app durante una sessione reale.

**Cosa fa questo documento, esattamente:** (1) usa il log reale P1 di una corsa vera per stimare la costante di tempo dell'EMA con numeri reali, non un valore rotondo scelto a intuito; (2) decide precisamente dove vive il nuovo componente di smoothing nel codice e come si collega a `SessionCoordinator`/`PaceEvaluator`; (3) decide esplicitamente, con motivazione, se includere ora o rimandare il raffinamento del "purge anticipato" per le transizioni RUN/WALK programmate, individuato dalla seconda revisione avversariale del documento di ricerca ma non ancora deciso in nessun documento precedente.

**Nota sui dati disponibili, corretta rispetto al brief iniziale:** il brief di questo lavoro citava "due" log evento reali da analizzare. Verificato con un `ls` diretto della cartella Downloads: **esiste un solo file di log eventi JSON Lines reale sul disco**, `paco-run-2026-09-15-110804-events.jsonl` (2026-09-15, sessione WALK_RUN di 86 minuti). Il "secondo run" citato nel brief (342 confronti Strava, zero divergenze) è la validazione GPS-vs-Strava basata su GPX della **stessa** corsa del 2026-09-15, non un secondo log eventi distinto — P1 non esisteva ancora prima di quella data. Questo documento usa quindi un solo run reale per la stima numerica sotto, coerentemente segnalato come limite esplicito in fondo al documento, non nascosto.

## Cosa è già deciso e non viene ridiscusso qui

1. **EMA, non finestra scorrevole a lunghezza fissa** — mirror del meccanismo adattivo già validato e in produzione in `SpeedEstimator` (la sua EMA dell'innovazione firmata), non un nuovo primitivo di smoothing per questo codebase.
2. **Purge agganciato all'evento noto (`profileChanged`), non un change-point detector** — `RoutineEngine` sa già quando una fase cambia; non serve stimarlo dal segnale.
3. **Nessuna curva di ricrescita esplicita** — il riassestamento dopo un purge è una conseguenza naturale della formula EMA stessa (vedi Decisione 2), non un meccanismo separato da codificare.
4. **`PaceEvaluator` non diventa "consapevole" dello smoothing nella sua logica decisionale** — continua a decidere lo stato di ritmo da qualunque `speedKmh` gli venga passato, esattamente come oggi.

## Decisione 1 — la costante di tempo dell'EMA: 6 secondi, da dati reali

### Metodologia

Script Python (`/tmp/claude-1000/.../scratchpad/analyze_pace_excursions.py`, throwaway, non fa parte dell'app — stesso stile degli script ad-hoc già usati in questo progetto per le corse precedenti) che legge `paco-run-2026-09-15-110804-events.jsonl` riga per riga e:

1. Ricostruisce gli intervalli di fase RUN da `RoutinePhaseChangedEvent` (`toPhase == "RUN"` → inizio intervallo, successivo `fromPhase == "RUN"` → fine).
2. Filtra `PaceStateConfirmedEvent`/`PaceCandidateStartedEvent`/`PaceCandidateAbandonedEvent` a quelli che cadono dentro un intervallo RUN (la banda più stretta configurata, dove il fenomeno è più marcato).
3. Calcola il tempo tra `PaceStateConfirmedEvent` consecutivi **all'interno dello stesso intervallo RUN** (scartando esplicitamente i gap che attraversano un segmento WALK tra due intervalli RUN separati — un bug della prima versione dello script, corretto: 22 gap spuri di questo tipo sono stati esclusi).

### Numeri trovati

Sessione: `mode=WALK_RUN`, `runSeconds=60`, `walkSeconds=120`, `runRange=[7.5, 9.0]` km/h (banda larga 1.5 km/h — **più larga** della banda di 0.7 km/h della corsa che ha originariamente motivato questa ricerca il 2026-09-13; probabile effetto della raccomandazione di prodotto "range più larghi per attività a margine stretto" già nel primo documento di ricerca, applicata nel frattempo). 23 intervalli RUN ricostruiti, 22.4 minuti di tempo RUN totale.

**Gap tra cambi di stato confermato consecutivi, tutte le transizioni, stesso intervallo RUN (n=130):**

| Percentile | Valore |
|---|---|
| min | 1.8s |
| p25 | 3.1s |
| **mediana** | **6.5s** |
| p75 | 9.6s |
| max | 40.0s |
| media | 7.9s |

Distribuzione (bucket da 5s): 51 campioni in 0-5s, 50 in 5-10s, 16 in 10-15s, poi una coda che si assottiglia rapidamente fino a 40s.

**Stesso gap, ma solo le transizioni che partono da `GOOD` (n=58)** — cioè: da quanto tempo `GOOD` è stato confermato prima che lo stato cambi di nuovo:

| Percentile | Valore |
|---|---|
| min | 1.8s |
| p25 | 3.1s |
| **mediana** | **6.5s** |
| p75 | 12.9s |
| max | 40.0s |
| media | 10.0s |

**Flip diretto estremo-a-estremo (`TOO_SLOW`↔`TOO_FAST`, il sintomo esatto del flapping) all'interno dello stesso intervallo RUN: solo n=2 campioni.** Troppo raro per una statistica affidabile con questo dataset — atteso, perché la banda qui è più larga (1.5 km/h, non 0.7 km/h) della corsa che ha originariamente mostrato 25 flip diretti, e perché un intervallo RUN di soli 60s lascia poco spazio per un flip completo estremo-a-estremo prima che la fase stessa cambi. La percentuale di tempo in `GOOD` in RUN qui è 71/153 = 46%, contro il 16% della corsa originale a banda stretta — coerente con l'effetto atteso di una banda più larga.

**Metrica scartata come non rilevante per la scelta di τ: tempo da inizio candidato a conferma (n=143, mediana 1.0s).** Questa metrica misura quasi esclusivamente la latenza del debounce già esistente (`confirmationSamples=2` a una cadenza fix di ~1 Hz ⇒ ~1s tra il campione che avvia il candidato e quello che lo conferma), non la durata fisiologica reale dell'escursione di ritmo — non è il numero giusto su cui basare τ.

### La scelta: τ = 6.0 secondi

Il gap mediano di cambio-stato-confermato (6.5s, sia sull'insieme completo sia sul sottoinsieme "da GOOD") è la misura più diretta e meno contaminata dal debounce di **quanto dura un ciclo naturale di oscillazione** del ritmo confermato durante RUN — esattamente il timescale che un'EMA deve attenuare senza uccidere la risposta a un cambio di sforzo genuino. **τ = 6.0s**, arrotondato alla decina di centesimo più vicina alla mediana osservata, è proposto come valore di partenza.

Questo numero **converge in modo indipendente** con la stima puramente qualitativa già presente in entrambi i documenti di ricerca precedenti ("una breve media mobile, 5-10s"), scelta allora senza alcun dato reale — un segnale di coerenza incoraggiante, non una prova che il numero sia definitivamente corretto.

**Limiti espliciti di questa stima, da non nascondere:**

- **Una sola corsa reale, un solo corridore, una sola configurazione di routine** (RUN 60s/WALK 120s, banda RUN 1.5 km/h). Non è noto se τ=6.0s regga con durate di intervallo molto diverse (es. RUN da 30s) o bande più strette.
- **La banda RUN di questa corsa (1.5 km/h) è più larga di quella della corsa che ha originariamente motivato l'intera indagine (0.7 km/h)** — il fenomeno di flapping diretto qui è già attenuato dalla banda più larga, quindi il gap mediano di 6.5s è probabilmente una stima leggermente **più lunga** di quella che si otterrebbe su una banda stretta come 0.7 km/h (dove le escursioni verso l'esterno della banda dovrebbero, in linea di principio, essere più frequenti/brevi). Non è stato possibile verificarlo: nessun log eventi P1 esiste per la corsa a banda stretta del 2026-09-13, che è precedente all'implementazione di P1.
- **n=130 (o n=58 per il sottoinsieme "da GOOD") è un campione modesto** per un intervallo di confidenza stretto — la distribuzione ha una coda lunga fino a 40s, non è simmetrica.

**Trattamento esplicito, come richiesto dal proprietario in entrambi i documenti precedenti:** τ=6.0s è una **stima di partenza ragionevole da validare sulla prossima corsa reale**, non un parametro definitivamente corretto. Va trattato con la stessa disciplina di `qHigh`/`innovationEmaAlpha` in `SpeedEstimator` — una costante di codice tarata empiricamente, rivedibile con la stessa procedura (nuovo dato reale + nuova analisi), **non un'impostazione utente** in `RunPreferences` (l'utente non deve capire cos'è una costante di tempo EMA — coerente con la filosofia "Play Store polish bar" del progetto).

## Decisione 2 — dove vive il componente: nuova classe, non dentro `PaceEvaluator` né `SpeedEstimator`

**Non dentro `PaceEvaluator`:** aggiungerebbe una responsabilità (smoothing temporale del segnale) a una classe che oggi ha un solo lavoro (decidere lo stato da un campione già pronto) — violerebbe la disciplina "una classe, un lavoro" già seguita ovunque in questo codebase, e comprometterebbe la garanzia esplicita richiesta: `PaceEvaluator` deve restare **completamente non toccato nella sua logica decisionale**.

**Non dentro `SpeedEstimator`/`KalmanSpeedEngine`:** sono già validati, in produzione, e risolvono un problema diverso (stima di posizione→velocità da un segnale rumoroso senza oracolo esterno). Toccarli per un problema che vive concettualmente un livello più in alto (decisione di ritmo, con un oracolo esterno — `RoutineEngine` — disponibile) rischierebbe codice già validato per zero beneficio, esattamente l'errore concettuale (IMM applicato a un problema con "quando" già noto) escluso nel secondo documento di ricerca.

**Scelta: una nuova, piccola classe indipendentemente testabile**, analoga in spirito a `SessionSpeedEngine`/`KalmanSpeedEngine` — un componente che siede tra il segnale grezzo (l'uscita di `speedEngine`) e il suo consumatore (`PaceEvaluator`), con la stessa disciplina di interfaccia+implementazione già usata per `SessionDistanceEngine`/`DistanceAccumulator` e `SessionSpeedEngine`/`KalmanSpeedEngine` nello stesso file (`app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`) — non un file nuovo: questo codebase tiene già questi piccoli motori di sessione nello stesso file di `SessionCoordinator`, non uno per file, e questo documento segue quel precedente stabilito invece di introdurne uno nuovo.

```kotlin
interface SessionPaceSmoothingEngine {
    /** Fonde una nuova lettura di velocità grezza nel valore smussato, restituisce il valore aggiornato. */
    fun accept(rawSpeedKmh: Float, elapsedRealtimeMillis: Long): Float
    /** Scarta tutta la memoria accumulata; la prossima [accept] riparte da zero, dal valore grezzo. */
    fun purge()
}

/**
 * Media mobile esponenziale sull'ingresso di velocità di PaceEvaluator, normalizzata nel tempo
 * cosi che [timeConstantSeconds] significhi la stessa cosa indipendentemente dal gap reale tra
 * fix affidabili (che non è perfettamente uniforme -- vedi Decisione 3). Deliberatamente NON una
 * finestra scorrevole: mirror del meccanismo EMA già validato di SpeedEstimator, il precedente
 * diretto più vicino in questo codebase, invece di inventare un secondo primitivo di smoothing.
 *
 * timeConstantSeconds=6.0: derivato da dati reali di decisione di ritmo in fase RUN (gap mediano
 * di cambio-stato-confermato: 6.5s su 130 campioni, una sola corsa reale 2026-09-15) -- vedi
 * design doc 2026-09-18 per la distribuzione completa e i limiti espliciti di questa stima.
 */
class EmaPaceSmoother(
    private val timeConstantSeconds: Double = 6.0,
) : SessionPaceSmoothingEngine {
    private var smoothedKmh: Double? = null
    private var lastElapsedRealtimeMillis: Long? = null

    override fun accept(rawSpeedKmh: Float, elapsedRealtimeMillis: Long): Float {
        val previousSmoothed = smoothedKmh
        val previousElapsed = lastElapsedRealtimeMillis
        lastElapsedRealtimeMillis = elapsedRealtimeMillis
        if (previousSmoothed == null || previousElapsed == null) {
            smoothedKmh = rawSpeedKmh.toDouble()
            return rawSpeedKmh
        }
        val dtSeconds = (elapsedRealtimeMillis - previousElapsed) / 1_000.0
        if (dtSeconds <= 0.0) return previousSmoothed.toFloat() // tick stantio/duplicato, mantiene il valore precedente
        val alpha = 1.0 - exp(-dtSeconds / timeConstantSeconds)
        val next = alpha * rawSpeedKmh + (1.0 - alpha) * previousSmoothed
        smoothedKmh = next
        return next.toFloat()
    }

    override fun purge() {
        smoothedKmh = null
        lastElapsedRealtimeMillis = null
    }
}
```

**Perché non serve una curva di ricrescita esplicita, verificato sulla formula stessa:** dopo `purge()`, `smoothedKmh` è `null`. Il prossimo `accept()` imposta `smoothedKmh = rawSpeedKmh` e **restituisce il valore grezzo direttamente** — zero smoothing sul primo campione post-purge. Le chiamate successive applicano la normale fusione EMA, che converge naturalmente verso lo smoothing di regime in circa 3τ secondi (proprietà standard di un'EMA) — senza nessun codice dedicato a "gestire il riassestamento": è una conseguenza diretta di come la formula tratta uno stato iniziale nullo, esattamente il comportamento "riempirsi/riassestarsi naturalmente" richiesto dal documento di ricerca precedente, non una feature separata da implementare o validare a parte.

**Niente guardia sui numeri non-finiti in `accept()`:** `speedEngine.accept()` (sia `KalmanSpeedEngine` sia `SpeedEstimator` al suo interno) già garantisce di non restituire mai un valore non finito — un risultato non finito attiva `breakChain()` e restituisce `0.0` invece (`SpeedEstimator.kt`, riga 199-201). `EmaPaceSmoother` può contare su questa garanzia già esistente a monte senza duplicarla.

## Decisione 3 — formula EMA: alpha normalizzato nel tempo, non alpha fisso per campione

`SpeedEstimator` usa un alpha **fisso per campione** per la propria EMA dell'innovazione (`innovationEmaAlpha = 0.3`, applicato identicamente ad ogni fix indipendentemente dal `dtSeconds` reale trascorso) — una scelta adeguata lì perché quell'EMA è un segnale ausiliario di rilevamento regime, tollerante a un po' di imprecisione sotto un campionamento quasi-uniforme a 1 Hz.

Qui la richiesta è diversa e più letterale: una costante di tempo **espressa in secondi** ("quanti secondi di memoria"). Questo significa qualcosa di preciso solo se l'alpha si adatta al gap reale tra fix, non se resta fisso — un gap di 0.8s e un gap di 2.5s tra due fix affidabili (che capitano: fix inaffidabili intermittenti, jitter GPS) non dovrebbero pesare allo stesso modo se la costante dichiarata è "N secondi di memoria". La formula standard per un'EMA a campionamento irregolare, `alpha = 1 - exp(-dt/τ)`, è l'implementazione diretta e corretta di "N secondi di memoria" indipendentemente dal dt effettivo — degenera esattamente nella forma fissa `alpha = costante` quando dt è davvero uniforme, quindi non contraddice la scelta di `SpeedEstimator`, la raffina per un caso in cui il significato letterale della costante conta di più.

## Decisione 4 — tre punti di purge, non uno solo

### 4.1 — Purge alla transizione di fase nota (`profileChanged`)

Il meccanismo già deciso nei documenti precedenti: quando `RoutineResult.profileChanged == true`, `paceSmoother.purge()` va chiamato nello stesso punto in cui oggi si chiama `paceEvaluator.updateConfig(...)` (`RunSessionStore.kt`, dentro `SessionCoordinator.process()`) — stesso evento, stesso istante, nessuna nuova sorgente di verità da costruire.

### 4.2 — Purge anticipato sulle transizioni RUN/WALK programmate — deciso: **includerlo ora**, non rimandarlo

**Il raffinamento individuato dalla seconda revisione avversariale, non ancora deciso in nessun documento precedente, va deciso qui esplicitamente.** Letto `RoutineEngine.timingCues()` (`app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt`, righe 124-152): per le transizioni RUN↔WALK programmate specificamente (non per il confine CONSTANT_BEFORE→RUN del warmup a distanza, che non ha preavviso — quel ramo di `accept()` non passa mai per `timingCues()`), `RoutineEngine` emette già `RoutineCue.WALK_IN_TEN`/`RoutineCue.RUN_IN_TEN` circa 10 secondi **prima** della transizione effettiva (`warningAt = durationMillis() - 10_000L`, un solo colpo per intervallo, guardia `warningEmitted`).

**Decisione: sì, purgare l'EMA anche a questo evento anticipato, non solo al `profileChanged` reattivo.** Motivazione, distinta e più concreta della semplice citazione del "bumpless transfer" del documento di ricerca (che presuppone di conoscere in anticipo il valore del nuovo regime — cosa che qui non abbiamo: non sappiamo quale sarà la velocità futura del corridore 10s prima): il cue vocale `WALK_IN_TEN`/`RUN_IN_TEN` non è solo un'informazione, è **il momento in cui il corridore comincia realisticamente a cambiare comportamento in anticipo** — chi sente "cammina tra 10 secondi" tipicamente comincia a rallentare prima che il timer interno scatti, non esattamente al secondo dello scatto. Il vero istante di "inizio del cambio di sforzo intenzionale" è quindi plausibilmente il cue anticipato stesso, non il `profileChanged` che arriva 10s dopo — e usare il cue anticipato come trigger significa sincronizzare il purge con quando il comportamento reale probabilmente comincia a cambiare, non con un evento puramente interno alla state machine.

**Perché questo non introduce un secondo parametro non validato (il vincolo esplicito posto dal documento di ricerca):** i 10 secondi non sono un nuovo numero scelto qui — sono la costante già esistente, già spedita in produzione, hardcoded in `RoutineEngine.timingCues()` (`durationMillis() - 10_000L`). Il trigger è un semplice controllo su un evento che `RoutineEngine` produce già: `routine.cues.any { it == RoutineCue.WALK_IN_TEN || it == RoutineCue.RUN_IN_TEN }` — nessuna nuova soglia, nessun nuovo stato da tarare.

**Costo:** quasi zero — `purge()` è idempotente (chiamarlo due volte, al cue anticipato e poi di nuovo al `profileChanged` reale 10s dopo, non fa danno: il secondo purge azzera semplicemente uno stato che nel frattempo si era solo parzialmente riassestato, dato che a τ=6.0s dopo 10s l'EMA è già all'~81% di convergenza verso il pieno smoothing — `1 - exp(-10/6) ≈ 0.81` — quindi il secondo purge non "butta via" molto smoothing accumulato).

**Onestà epistemica esplicita, come richiesto:** questa è un'ipotesi plausibile e a costo pressoché nullo, **non verificata su dati reali**. Nessun log P1 esistente permette oggi di verificare se i corridori reali di questa app effettivamente iniziano a cambiare ritmo durante il countdown di 10s (nel qual caso il purge anticipato aiuta) o solo esattamente al cambio fase (nel qual caso il purge anticipato è neutro nella migliore delle ipotesi, o introduce una finestra di 10s leggermente più reattiva del necessario nella peggiore). **Va validato sulla prossima corsa reale**, confrontando (via `PaceStateConfirmedEvent.rawSpeedKmh`, Decisione 6) se la velocità reale comincia effettivamente a muoversi durante il countdown — se i dati smentiscono l'ipotesi, rimuovere il trigger 4.2 è una modifica di una riga, non una riprogettazione.

### 4.3 — Purge quando la catena di velocità grezza si rompe per GPS stantio

**Trovato leggendo il codice di wiring esistente, non menzionato in nessun documento precedente — un punto reale, non ipotetico, che va specificato qui.** In `SessionCoordinator.process()` (`RunSessionStore.kt`, righe 276-300), quando il GPS resta inaffidabile per `>= LocationSampleMapper.DEFAULT_MAX_AGE_MILLIS` dall'ultimo fix affidabile, `speedEngine.breakChain()` viene chiamato — `SpeedEstimator` scarta il proprio stato interno (assi Kalman, EMA di innovazione, baseline di accuratezza) e ripartirà da zero al prossimo fix affidabile, restituendo `0.0` sul primo fix di ripristino (comportamento standard "prima misura dopo un reset", righe 150-157 di `SpeedEstimator.kt`).

**Se `EmaPaceSmoother` non viene purgato nello stesso momento**, al ripristino fonderebbe questo primo valore di ripristino (un artefatto transitorio del filtro che si sta riassestando, non una lettura di velocità reale) con la propria memoria EMA **pre-interruzione**, potenzialmente vecchia di minuti — producendo una decisione di ritmo sbagliata proprio nei primi campioni dopo un'interruzione GPS, l'esatto tipo di bug silenzioso che questo documento deve evitare di introdurre. **Decisione: `paceSmoother.purge()` va chiamato nello stesso punto in cui si chiama `speedEngine.breakChain()`** in `SessionCoordinator.process()` — tiene la memoria dello smoother in lockstep con il reset del segnale grezzo a monte, stesso principio del purge di Decisione 4.1, trigger diverso (discontinuità del segnale, non cambio di fase intenzionale).

## Decisione 5 — wiring esatto in `SessionCoordinator.process()`

Diff concettuale su `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt` (i numeri di riga si riferiscono allo stato attuale di `feature/session-event-logging`, letto per questo documento):

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
    private val paceSmoother: SessionPaceSmoothingEngine = EmaPaceSmoother(),  // NUOVO
    private val onSpeedComputed: (Double?) -> Unit = {},
    private val onEvent: (SessionEvent) -> Unit = {},
) {
    private fun process(...) {
        // ... invariato fino a paceSample (Decisione 6: paceSample resta SEMPRE grezzo) ...

        if (actualReliable) {
            lastReliableFixElapsedMillis = elapsedRealtimeMillis
            speedChainBrokenReportedForOutage = false
        } else if (!gpsReliable && elapsedRealtimeMillis - lastReliableFixElapsedMillis >= LocationSampleMapper.DEFAULT_MAX_AGE_MILLIS) {
            if (!speedChainBrokenReportedForOutage) {
                onEvent(SpeedChainBrokenEvent(elapsedRealtimeMillis, SpeedChainBreakReason.STALE_NO_RECENT_RELIABLE_FIX))
                speedChainBrokenReportedForOutage = true
            }
            speedEngine.breakChain()
            paceSmoother.purge()  // NUOVO (Decisione 4.3)
        }

        val distanceDelta = ...
        val routine = routineEngine.accept(RoutineInput(...))

        if (routine.cues.any { it == RoutineCue.WALK_IN_TEN || it == RoutineCue.RUN_IN_TEN }) {
            paceSmoother.purge()  // NUOVO (Decisione 4.2)
        }
        if (routine.profileChanged) {
            paceEvaluator.updateConfig(...)
            paceSmoother.purge()  // NUOVO (Decisione 4.1)
            latestPace = PaceResult(PaceState.ACQUIRING_GPS, null, null)
        }
        if (routine.profileChanged && wasOutage) { ... }  // invariato

        val smoothedSpeedKmh = if (actualReliable) {
            paceSmoother.accept(paceSample.speedKmh, elapsedRealtimeMillis)
        } else {
            paceSample.speedKmh  // nessun nuovo campione grezzo da fondere questo tick
        }
        val pace = when {
            sample != null -> paceEvaluator.accept(
                paceSample.copy(speedKmh = smoothedSpeedKmh),
                rawSpeedKmh = paceSample.speedKmh.takeIf { gpsReliable },
            )
            signalGpsOutage && enteredOutage -> paceEvaluator.accept(
                paceSample.copy(speedKmh = smoothedSpeedKmh),
                rawSpeedKmh = paceSample.speedKmh.takeIf { gpsReliable },
            )
            else -> latestPace
        }
        latestPace = pace

        // ... resto INVARIATO: routine.cues/AudioCueEmittedEvent/publish() usano tutti
        // paceSample.speedKmh (grezzo), MAI smoothedSpeedKmh -- vedi Decisione 6.
    }
}
```

**Ordine deliberato:** i tre punti di purge (4.3, 4.2, 4.1) avvengono tutti **prima** della chiamata a `paceSmoother.accept()` nello stesso tick. Questo garantisce che se un purge scatta nello stesso tick in cui arriva anche un nuovo campione grezzo, quel campione sia il primo dopo il reset — restituito grezzo al 100%, come da Decisione 2 — invece di essere prima fuso nella memoria stantia e solo poi scartato.

`PendingSessionCoordinator` riceve lo stesso parametro `paceSmoother: SessionPaceSmoothingEngine = EmaPaceSmoother()` e lo passa giù nello stesso identico modo in cui già passa `distanceEngine`/`speedEngine`/`onEvent` al `SessionCoordinator` che costruisce in `activate()` — nessun pattern nuovo, stesso threading già esistente.

`app/src/main/java/dev/fred/pacorun/routine/RoutineModels.kt`'s `RoutineCue` va importato in `RunSessionStore.kt` (oggi importa `RoutineEngine`/`RoutineInput`/`RoutinePhase`/`RoutineResult` ma non `RoutineCue`).

## Decisione 6 — cosa resta grezzo, esplicitamente, e perché

**Solo l'ingresso decisionale di `PaceEvaluator` viene smussato. Tutto il resto resta il valore grezzo di `KalmanSpeedEngine`, invariato:**

- **`RunSessionState.speedKmh`** (il numero mostrato a schermo, se mostrato) — resta `paceSample.speedKmh`, mai smussato. Smussare il numero visualizzato sopprimerebbe letture istantanee reali e correttamente osservate per nessun beneficio: il display non è ciò che flappa, è la *decisione* di ritmo a flappare.
- **`RoutineInput.currentReliableSpeedKmh`** (alimenta `directionCue`/SPEED_UP/SLOW_DOWN di `RoutineEngine`, un meccanismo distinto, a colpo singolo, non ripetuto — fuori scope per questo documento) — resta `paceSample.speedKmh`, mai smussato.
- **`AudioCueEmittedEvent.speedKmh`** (il log P1) — resta `paceSample.speedKmh` del `SessionCoordinator`, invariato: questo campo non deriva dal campione passato a `paceEvaluator.accept()`, deriva direttamente dalla variabile esterna `paceSample` già presente in `process()` — nessuna modifica necessaria qui.

**Un conflitto reale, non ovvio, trovato leggendo il codice esistente — va risolto esplicitamente, non lasciato implicito.** `PaceStateConfirmedEvent` (emesso **da dentro** `PaceEvaluator.accept()`, non da `SessionCoordinator`) oggi costruisce il proprio campo `speedKmh` da `sample.speedKmh`, cioè dal parametro che gli viene passato. Se quel parametro diventa il campione smussato (come richiesto: `PaceEvaluator` deve decidere sul valore smussato), **questo campo smetterebbe silenziosamente di essere grezzo** — violando esattamente il principio richiesto ("P1 deve continuare a osservare il segnale VERO, grezzo, o smette di essere utile per la diagnosi futura").

**Risoluzione: `PaceEvaluator.accept()` guadagna un secondo parametro, con default, usato solo per il payload dell'evento — la sua logica decisionale (il `when` su `paceState()`, il conteggio di `confirmationSamples`, la selezione dei cue) resta byte-per-byte identica.**

```kotlin
// PaceEvaluator.kt
fun accept(sample: LocationSample, rawSpeedKmh: Float? = sample.speedKmh): PaceResult {
    // ... logica INVARIATA ...
    onEvent(
        PaceStateConfirmedEvent(
            elapsedRealtimeMillis = sample.elapsedRealtimeMillis,
            fromState = previous,
            toState = pace,
            speedKmh = sample.speedKmh,   // ORA: il valore SMUSSATO su cui PaceEvaluator ha deciso
            rawSpeedKmh = rawSpeedKmh,    // NUOVO: il vero valore grezzo di KalmanSpeedEngine allo stesso istante
            producedAudibleCue = paceCue != null,
        ),
    )
    // ...
}
```

```kotlin
// RunSessionStore.kt
interface SessionPaceEvaluator {
    fun updateConfig(config: PaceConfig)
    fun accept(sample: LocationSample, rawSpeedKmh: Float? = sample.speedKmh): PaceResult
}

class PaceEvaluatorAdapter(private val evaluator: PaceEvaluator) : SessionPaceEvaluator {
    override fun updateConfig(config: PaceConfig) = evaluator.updateConfig(config)
    override fun accept(sample: LocationSample, rawSpeedKmh: Float?) = evaluator.accept(sample, rawSpeedKmh)
}
```

**Cambiamento di schema dichiarato esplicitamente, non solo un'aggiunta silenziosa:** `PaceStateConfirmedEvent` (`dev.fred.pacorun.eventlog.SessionEvent.kt`) guadagna un nuovo campo `val rawSpeedKmh: Float?`, e il significato del campo esistente `speedKmh` **cambia** (prima: grezzo per definizione, dato che nessuno smoothing esisteva; ora: il valore smussato su cui la decisione si è basata). Chi analizza log P1 futuri deve sapere che, a partire dall'implementazione di questo documento, `PaceStateConfirmedEvent.speedKmh` e `PaceStateConfirmedEvent.rawSpeedKmh` possono divergere, e che la divergenza stessa è il segnale utile per validare τ (Decisione 1) e il purge anticipato (Decisione 4.2) sulla prossima corsa reale.

`PaceCandidateStartedEvent`/`PaceCandidateAbandonedEvent` non portano alcun campo di velocità oggi — nessun conflitto, nessuna modifica necessaria a questi due tipi.

## Decisione 7 — nessun nuovo tipo di evento per i tre trigger di purge

I tre momenti in cui `paceSmoother.purge()` scatta (Decisione 4) sono **già tutti osservabili nel log P1 esistente**, senza aggiungere nulla: il purge di fase (4.1) coincide con `RoutinePhaseChangedEvent`; il purge anticipato (4.2) coincide con l'`AudioCueEmittedEvent` di `WALK_IN_TEN`/`RUN_IN_TEN`; il purge da GPS stantio (4.3) coincide con `SpeedChainBrokenEvent(reason=STALE_NO_RECENT_RELIABLE_FIX)`. Aggiungere un quarto tipo di evento dedicato solo a "il purge è scattato" sarebbe registrare un'informazione già interamente ricostruibile per join su `elapsedRealtimeMillis` — esattamente il tipo di "log inutile" che il documento P1 ha esplicitamente escluso come principio guida, applicato qui allo stesso modo.

## Non-obiettivi espliciti

- **Non tocca `SpeedEstimator`/`KalmanSpeedEngine`** — già validati, fuori scope (Decisione 2).
- **Non tocca P3** (il bug `GOOD_PACE` silenzioso dopo `previous == null`, `PaceEvaluator.kt` righe 81-86) — bug distinto, ortogonale, da correggere a parte. Nota di interazione: questo documento aggiunge un parametro `rawSpeedKmh` alla stessa firma `accept()` che P3 dovrà toccare per il proprio fix — chi implementerà P3 dopo P2 troverà la firma già cambiata, non un conflitto ma una dipendenza d'ordine da tenere a mente.
- **Non tocca P4** (il limite di `RoutineEngine.advanceTo()` a due transizioni per chiamata) — bug distinto, già tracciato nella roadmap.
- **Non tocca P5** (audio enqueue-vs-playback) — lavoro indipendente.
- **Non allarga i range di pace configurati** — leva di prodotto/UX distinta, già raccomandata come complementare e a costo zero nel primo documento di ricerca (Fronte 2, raccomandazione 1); resta a disposizione del proprietario come leva indipendente, non scelta qui.

## Domande aperte per il proprietario

1. **τ = 6.0s è una stima di partenza da un solo run** (banda RUN 1.5 km/h, non la banda 0.7 km/h che ha originariamente motivato l'indagine) — va validata sulla prossima corsa reale, specialmente se registrata con una banda più stretta, dove il ciclo di oscillazione naturale potrebbe essere più breve.
2. **Il purge anticipato di Decisione 4.2 è un'ipotesi causale plausibile ma non verificata** — nessun dato oggi conferma che i corridori reali comincino davvero a cambiare ritmo durante il countdown di 10s piuttosto che esattamente al cambio fase. Da verificare sulla prossima corsa (confrontando `rawSpeedKmh` nei secondi immediatamente precedenti e seguenti ogni `RoutinePhaseChangedEvent`); se smentita, rimuovere il trigger è una modifica di una riga.
3. **Nessun secondo log eventi reale esiste ancora** (vedi nota in Contesto) — questo documento non ha potuto fare il confronto multi-corsa che l'idea originale del brief presupponeva. Non blocca l'implementazione (τ resta una costante rivedibile), ma va segnalato per non sovrastimare la solidità della stima.

## Codice letto per questo documento

- `app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt`, `PaceModels.kt` (branch `feature/session-event-logging`)
- `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt` (`SessionCoordinator.process()`, `PendingSessionCoordinator`, `DistanceAccumulator`, `SessionSpeedEngine`/`KalmanSpeedEngine`/`DopplerSpeedEngine`) (branch `feature/session-event-logging`)
- `app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt` (branch `feature/session-event-logging`) — per il precedente EMA/alpha fisso citato in Decisione 3
- `app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt`, `RoutineModels.kt` (branch `feature/session-event-logging`) — per `timingCues()`/`WALK_IN_TEN`/`RUN_IN_TEN`/`profileChanged`
- `app/src/main/java/dev/fred/pacorun/location/LocationSample.kt` (branch `feature/session-event-logging`)
- `app/src/main/java/dev/fred/pacorun/audio/CuePlayer.kt` (`AudioCue`, per confermare che `WALK_IN_TEN`/`RUN_IN_TEN` sopravvivono al mapping `RoutineCue→AudioCue`)
- `app/src/main/java/dev/fred/pacorun/settings/RunPreferences.kt` (default `runSeconds=60`/`walkSeconds=120`, per verificare che la corsa analizzata usasse la configurazione di default)
- `/home/fredworkstation/Downloads/paco-run-2026-09-15-110804-events.jsonl` (log eventi reale P1, 7346 righe, analizzato con lo script Python di Decisione 1)

**Documenti interni di riferimento (letti per intero prima di questo, non riprodotti qui):**
- `docs/superpowers/specs/2026-09-13-precision-and-cue-stability-research.md`
- `docs/superpowers/specs/2026-09-13-phase-aware-cue-smoothing-research.md`
- `docs/superpowers/specs/2026-09-14-session-event-logging-design.md`
- `docs/superpowers/specs/2026-09-11-precision-roadmap.md` (voci P1/P2/P3/P4/P5)
