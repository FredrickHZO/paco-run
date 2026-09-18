# Design: smoothing di decisione del ritmo consapevole del cambio fase (P2)

**Data:** 2026-09-18. **Branch:** `feature/paco-run-m3e` (questo documento verrà implementato su `feature/pace-decision-smoothing`, da creare in seguito come fork di `feature/session-event-logging` — non creata qui). **Tipo di documento:** design spec — decisioni prese e giustificate qui, **nessun codice toccato**, nessun piano di implementazione scritto (quello è un documento separato, successivo). Corrisponde alla voce **P2** della roadmap (`docs/superpowers/specs/2026-09-11-precision-roadmap.md`).

## Contesto (per chi legge senza aver seguito il progetto)

**Paco Run** è un'app Android per corsa/camminata che dà feedback vocale in tempo reale sul ritmo, confrontando la velocità istantanea (`KalmanSpeedEngine`/`SpeedEstimator`, un filtro di Kalman a due assi, già validato e in produzione) con un range target configurato. `PaceEvaluator` (`app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt`) decide, campione per campione, lo stato di ritmo confermato (`TOO_SLOW`/`GOOD`/`TOO_FAST`), con un debounce a conteggio di campioni (`confirmationSamples`, default 2) prima di accettare un cambio di stato. Un secondo componente, `RoutineEngine`, guida routine a intervalli ("Cammina-Corri": alternanza RUN/WALK a durate fisse, con eventuale fase iniziale/finale a ritmo costante) e segnala ogni cambio di fase reale (`RoutineResult.profileChanged`) nello stesso tick in cui avviene.

**Il problema, confermato su corsa reale, non su sospetto.** La prima corsa reale con `SpeedEstimator` collegato ai cue dal vivo (2026-09-13) ha mostrato lo stato di ritmo confermato saltare direttamente da `TOO_SLOW` a `TOO_FAST` (o viceversa) senza passare da `GOOD`, ripetutamente. Due cicli di ricerca indipendenti, entrambi letti per intero prima di scrivere questo documento, hanno escluso la causa più ovvia e stabilito la direzione del fix:

- `docs/superpowers/specs/2026-09-13-precision-and-cue-stability-research.md` — ha confermato che il fenomeno **non è rumore del sensore**: il segnale Kalman di Paco Run era, punto per punto, leggermente più pulito di quello di Strava negli stessi istanti. La causa è la normale variabilità del ritmo di corsa umano (variabilità stride-to-stride), che eccede regolarmente una banda di velocità stretta. Un secondo run reale (2026-09-15, lo stesso analizzato in questo documento) ha ri-confermato la pulizia del segnale su tutta la corsa: 342 confronti diretti contro Strava, zero divergenze oltre 2.5 km/h, differenza media -0.06 km/h — verificato direttamente in sessione di lavoro il 2026-09-18 (confronto GPX/FIT via finestra causale a 10s, stesso metodo delle analisi precedenti), non ancora trascritto in un documento a sé prima d'ora.
- `docs/superpowers/specs/2026-09-13-phase-aware-cue-smoothing-research.md` — ha stabilito l'architettura del fix: uno smoothing **EMA (media mobile esponenziale)**, non una finestra scorrevole a lunghezza fissa, applicato solo all'ingresso decisionale di `PaceEvaluator` — non al numero mostrato a schermo. Cruciale: `RoutineEngine` conosce già, con timestamp esatto, il momento di ogni cambio di fase reale (non è qualcosa da inferire statisticamente) — quindi lo stato dell'EMA va **purgato (azzerato)** in quel momento noto e lasciato **riempirsi/riassestarsi naturalmente** dopo, senza inventare una curva di ricrescita né un secondo parametro non validato. Questa è una semplificazione deliberata rispetto a un'idea precedente, scartata dopo revisione avversariale ("collassa la finestra poi fai ricrescere su una curva inventata") — questo documento non la reintroduce.

Entrambi i documenti hanno lasciato esplicitamente aperta la costante di tempo dell'EMA (quanti secondi di "memoria"), in attesa di dati reali per-tick — che ora esistono grazie a **P1** (`docs/superpowers/specs/2026-09-14-session-event-logging-design.md`, già implementato e fuso su `feature/session-event-logging`): un log strutturato JSON Lines di ogni decisione dell'app durante una sessione reale.

**Cosa fa questo documento, esattamente:** (1) usa il log reale P1 di una corsa vera per stimare la costante di tempo dell'EMA con numeri reali, non un valore rotondo scelto a intuito; (2) decide precisamente dove vive il nuovo componente di smoothing nel codice e come si collega a `SessionCoordinator`/`PaceEvaluator`; (3) decide esplicitamente, con motivazione, se includere ora o rimandare il raffinamento del "purge anticipato" per le transizioni RUN/WALK programmate, individuato dalla seconda revisione avversariale del documento di ricerca ma non ancora deciso in nessun documento precedente; (4) specifica un test sintetico del caso peggiore e un'analisi di sensitività su τ (Decisione 8), aggiunti dopo una revisione avversariale di questo stesso documento e una domanda diretta del proprietario sulla genericità del design, da eseguire come parte dell'implementazione di P2.

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

**Scelta metodologica esplicita, da non nascondere: come viene trattato l'ultimo intervallo RUN del log, mai chiuso.** La registrazione si interrompe a metà di un intervallo RUN (nessun `RoutinePhaseChangedEvent` di chiusura, e lo schema P1 non ha nemmeno un evento di fine sessione). I numeri riportati sotto (n=23 intervalli, n=130 gap, mediana 6.5s) trattano quell'ultimo intervallo come se finisse all'ultimo timestamp del log. Applicando invece la metodologia letta alla lettera sopra — solo intervalli con un `RoutinePhaseChangedEvent` di chiusura reale — si ottiene n=22 intervalli (21.95 minuti di tempo RUN totale), n=128 gap, **mediana 6.7s**. La conclusione sostanziale (τ nell'intervallo 6-7s) non cambia in nessuno dei due casi.

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

**Flip diretto estremo-a-estremo (`TOO_SLOW`↔`TOO_FAST`, il sintomo esatto del flapping) all'interno dello stesso intervallo RUN: solo n=2 campioni.** Troppo raro per una statistica affidabile con questo dataset — atteso, perché la banda qui è più larga (1.5 km/h, non 0.7 km/h) della corsa che ha originariamente mostrato 25 flip diretti, e perché un intervallo RUN di soli 60s lascia poco spazio per un flip completo estremo-a-estremo prima che la fase stessa cambi. Su 153 episodi confermati totali in RUN, 71 sono `GOOD` (71/153 = 46% **degli episodi**) — ma questo è un conteggio a episodi, non la stessa metrica del 16.1% citato dal documento di validazione 2026-09-13 e non è direttamente comparabile ad esso (correzione rispetto a una versione precedente di questo documento, che li confrontava come se fossero la stessa cosa).

**Il confronto corretto, comparabile riga per riga con quel 16.1%, è pesato nel tempo, non a episodi.** "Tempo-pesato" significa sommare quanto tempo lo stato confermato resta in ciascun valore (dalla conferma di un episodio alla conferma del successivo), non contare gli episodi uno per uno: un episodio di `GOOD` durato 40s pesa 40 volte più di uno durato 1s in un conteggio a tempo, mentre pesa uguale in un conteggio a episodi — la stessa distinzione che rendeva sbagliato il confronto originale. Ricalcolato sullo stesso log 2026-09-15 (fasi RUN, variante con l'ultimo intervallo chiuso al log, vedi nota metodologica sopra): `GOOD` occupa **831.8s su 1345.2s di tempo RUN totale = 61.8%** (`TOO_SLOW` 19.5%, `TOO_FAST` 18.6%) — contro il **16.1%** (209.8s/1304.8s) della corsa 2026-09-13 a banda stretta. Coerente con l'effetto atteso di una banda più larga (1.5 vs 0.7 km/h), e coerente in ordine di grandezza con una stima indipendente di 61.3% ottenuta nella revisione avversariale che ha richiesto questa correzione (la piccola differenza è dovuta al trattamento dell'intervallo RUN finale mai chiuso, vedi nota metodologica sopra — non cambia la conclusione).

**Metrica scartata come non rilevante per la scelta di τ: tempo da inizio candidato a conferma (n=143, mediana 1.0s).** Questa metrica misura quasi esclusivamente la latenza del debounce già esistente (`confirmationSamples=2` a una cadenza fix di ~1 Hz ⇒ ~1s tra il campione che avvia il candidato e quello che lo conferma), non la durata fisiologica reale dell'escursione di ritmo — non è il numero giusto su cui basare τ.

### La scelta: τ = 6.0 secondi

Il gap mediano di cambio-stato-confermato (6.5s, sia sull'insieme completo sia sul sottoinsieme "da GOOD") è la misura più diretta e meno contaminata dal debounce di **quanto dura un ciclo naturale di oscillazione** del ritmo confermato durante RUN — esattamente il timescale che un'EMA deve attenuare senza uccidere la risposta a un cambio di sforzo genuino. **τ = 6.0s** è proposto come valore di partenza: la mediana osservata è 6.5s, arrotondata per difetto al secondo intero (non alla decina di secondo più vicina, che lascerebbe 6.5 invariato — correzione rispetto a una versione precedente di questo documento che descriveva un arrotondamento diverso da quello effettivamente applicato). L'arrotondamento per difetto, non per eccesso a 7.0s, è una scelta deliberata verso il lato leggermente più reattivo (meno memoria, meno lag di decisione) tra i due secondi interi adiacenti alla mediana, non un calcolo che produce 6.0 in modo univoco da 6.5 — un altro arrotondamento ragionevole (per eccesso, o al secondo pari più vicino) avrebbe dato 7.0 in modo altrettanto legittimo; nulla nei dati impone 6.0 su 7.0 con certezza, coerente con il trattamento esplicito di τ come stima di partenza rivedibile, non come valore derivato con precisione.

Questo numero **converge in modo indipendente** con la stima puramente qualitativa già presente in entrambi i documenti di ricerca precedenti ("una breve media mobile, 5-10s"), scelta allora senza alcun dato reale — un segnale di coerenza incoraggiante, non una prova che il numero sia definitivamente corretto.

**Il limite più importante di questa stima, da leggere prima di ogni altro, non come una voce tra le altre in una lista di limiti:** la corsa 2026-09-15 usata per stimare τ **non è il caso peggiore** — è, al contrario, un caso già sostanzialmente attenuato. La sua banda RUN (1.5 km/h) è **più del doppio** di quella della corsa 2026-09-13 che ha originariamente fatto nascere questa intera indagine (0.7 km/h, 25 flip diretti estremo-a-estremo confermati). Una banda più larga riduce da sola la frequenza e l'intensità del flapping — è esattamente per questo che nella corsa 2026-09-15 si osservano solo 2 flip diretti invece di decine, e che `GOOD` occupa 61.8% del tempo RUN invece del 16.1% della corsa a banda stretta (vedi sopra). Questo significa che **τ=6.0s è stato stimato in un regime in cui il problema che deve risolvere era già in gran parte attenuato da un'altra leva (la larghezza della banda), non nel regime patologico che ha originariamente motivato la ricerca.** Il gap mediano di 6.5s osservato qui è probabilmente più lungo di quello che si otterrebbe su una banda stretta come 0.7 km/h, dove le escursioni oltre banda dovrebbero, in linea di principio, essere più frequenti e più brevi — cioè τ potrebbe dover essere più corto, non più lungo, per il caso che ha originariamente motivato questo lavoro. Nessun log eventi P1 esiste per la corsa 2026-09-13 (precedente a P1) per verificarlo direttamente sui dati reali di quella corsa — motivo diretto per cui Decisione 8 (sotto) aggiunge un test sintetico costruito apposta per riprodurre quelle condizioni, invece di fidarsi solo di questa stima su un caso già attenuato.

**Altri limiti espliciti di questa stima, da non nascondere:**

- **Una sola corsa reale, un solo corridore, una sola configurazione di routine** (RUN 60s/WALK 120s, banda RUN 1.5 km/h). Non è noto se τ=6.0s regga con durate di intervallo molto diverse (es. RUN da 30s).
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

**Il raffinamento individuato dalla seconda revisione avversariale, non ancora deciso in nessun documento precedente, va deciso qui esplicitamente.** Letto `RoutineEngine.timingCues()` (`app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt`, righe 124-152): per le transizioni RUN↔WALK programmate specificamente (non per il confine CONSTANT_BEFORE→RUN del warmup a distanza, che non ha preavviso — quel ramo di `accept()` passa comunque per `timingCues()` (viene chiamata su ogni tick, non solo per le transizioni RUN↔WALK), ma su un intervallo appena iniziato l'elapsed è vicino a zero, quindi non è mai vicino a `warningAt` e non può mai far scattare un cue lì), `RoutineEngine` emette già `RoutineCue.WALK_IN_TEN`/`RoutineCue.RUN_IN_TEN` circa 10 secondi **prima** della transizione effettiva (`warningAt = durationMillis() - 10_000L`, un solo colpo per intervallo, guardia `warningEmitted`).

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
            producedAudibleCue = paceCue != null,
            rawSpeedKmh = rawSpeedKmh,    // NUOVO: il vero valore grezzo di KalmanSpeedEngine allo stesso istante -- ULTIMO parametro, vedi sotto
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

**Il campo `rawSpeedKmh` va aggiunto come ULTIMO parametro del costruttore di `PaceStateConfirmedEvent`, dopo `producedAudibleCue`, non tra `speedKmh` e `producedAudibleCue`.** L'ordine reale attuale dei campi (letto da `app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt`, branch `feature/session-event-logging`, righe 93-99) è `elapsedRealtimeMillis, fromState, toState, speedKmh, producedAudibleCue`:

```kotlin
// SessionEvent.kt -- ordine reale attuale, verificato leggendo il file
data class PaceStateConfirmedEvent(
    override val elapsedRealtimeMillis: Long,
    val fromState: PaceState?,
    val toState: PaceState,
    val speedKmh: Float?,
    val producedAudibleCue: Boolean,
    val rawSpeedKmh: Float? = null,   // NUOVO -- ULTIMO, con default, per non rompere i call site posizionali esistenti
) : SessionEvent
```

Appenderlo in coda con un default `= null` mantiene compilabili le chiamate posizionali esistenti a 5 argomenti (che continuerebbero a compilare con `rawSpeedKmh` implicitamente `null`, da correggere comunque nei test dove serve asserire il vero valore atteso — vedi sotto). Inserirlo prima di `producedAudibleCue`, come mostrava una versione precedente di questo documento nello snippet sopra (ora corretto), avrebbe invece spostato la posizione di `producedAudibleCue`, rompendo silenziosamente o con un errore di tipo qualunque chiamata posizionale che lo passa nella quinta posizione — esattamente il tipo di rottura da evitare aggiungendo un campo a una classe con call site posizionali già esistenti in questo codebase.

**Il gap più importante trovato da questa revisione, non presente in nessuna versione precedente di questo documento: `SessionEventWriter.kt` non era mai stato letto, e senza modificarlo `rawSpeedKmh` non arriverebbe mai al file JSONL.** `app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt` è un serializzatore JSON **scritto a mano, campo per campo** (non basato su reflection — vedi il commento in testa al file stesso), con un `when` esaustivo per tipo di evento e una funzione privata dedicata per ciascuno. La funzione per questo tipo di evento, `paceStateConfirmedEvent()` (righe 116-123), oggi serializza esplicitamente solo cinque campi:

```kotlin
// SessionEventWriter.kt -- stato reale attuale, letto per questa revisione
private fun paceStateConfirmedEvent(event: PaceStateConfirmedEvent) = json {
    string("type", "PaceStateConfirmedEvent")
    long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
    enumOrNull("fromState", event.fromState)
    enum("toState", event.toState)
    floatOrNull("speedKmh", event.speedKmh)
    boolean("producedAudibleCue", event.producedAudibleCue)
}
```

Aggiungere `rawSpeedKmh` alla data class **da sola non basta**: senza aggiungere anche una riga a questa funzione, il campo esisterebbe sulla classe Kotlin ma non verrebbe mai scritto nel log JSONL — vanificando silenziosamente l'intero scopo del cambiamento di schema (usare la divergenza grezzo-vs-smussato per validare τ sulla prossima corsa). **Decisione/passo esplicito, da includere nel piano di implementazione:** `paceStateConfirmedEvent()` deve guadagnare `floatOrNull("rawSpeedKmh", event.rawSpeedKmh)`, verosimilmente come ultima riga, seguendo lo stesso ordine del costruttore.

**Tre rotture reali nei test esistenti, trovate leggendo il codice per questa revisione — da elencare qui esplicitamente così un piano di implementazione le trovi già pronte come task, non da riscoprire:**

1. **`app/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt` costruisce `PaceStateConfirmedEvent` posizionalmente** in più punti (es. riga 206: `PaceStateConfirmedEvent(1_000L, null, PaceState.TOO_FAST, 8.8f, true)`, 5 argomenti nell'ordine attuale). Con `rawSpeedKmh` in coda e default `null`, queste chiamate continuano a compilare, ma vanno comunque aggiornate dove il test deve asserire il vero `rawSpeedKmh` prodotto da `PaceEvaluator.accept(sample, rawSpeedKmh)` (altrimenti l'asserzione confronterebbe un valore atteso `null` con un valore reale non-null, e fallirebbe a runtime, non a compile time).
2. **`app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt` ha un'asserzione a stringa JSON esatta per questo tipo di evento** (il test che verifica `paceStateConfirmedEvent()`, righe ~50-62) — non è "rotto" nel senso di un bug esistente, ma **va aggiornato** per includere `"rawSpeedKmh":...` nell'output atteso non appena il serializzatore cambia; un aggiornamento richiesto, da elencare come task, non da scoprire durante l'implementazione.
3. **`app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt`'s `FakePaceEvaluator`** (righe 647-661) implementa `SessionPaceEvaluator` con la firma odierna `override fun accept(sample: LocationSample): PaceResult`. Quando la firma dell'interfaccia cambia in `accept(sample: LocationSample, rawSpeedKmh: Float? = sample.speedKmh): PaceResult` (sopra), **questo test double va aggiornato di conseguenza**, non solo `PaceEvaluatorAdapter` (la sua implementazione di produzione) — altrimenti `FakePaceEvaluator` smette di implementare l'interfaccia e il modulo di test non compila più.

## Decisione 7 — nessun nuovo tipo di evento per i tre trigger di purge

I tre momenti in cui `paceSmoother.purge()` scatta (Decisione 4) sono **già tutti osservabili nel log P1 esistente**, senza aggiungere nulla: il purge di fase (4.1) coincide con `RoutinePhaseChangedEvent`; il purge anticipato (4.2) coincide con l'`AudioCueEmittedEvent` di `WALK_IN_TEN`/`RUN_IN_TEN`; il purge da GPS stantio (4.3) coincide con `SpeedChainBrokenEvent(reason=STALE_NO_RECENT_RELIABLE_FIX)`. Aggiungere un quarto tipo di evento dedicato solo a "il purge è scattato" sarebbe registrare un'informazione già interamente ricostruibile per join su `elapsedRealtimeMillis` — esattamente il tipo di "log inutile" che il documento P1 ha esplicitamente escluso come principio guida, applicato qui allo stesso modo.

## Decisione 8 — validare τ prima della prossima corsa reale, dentro P2: test sintetico + analisi di sensitività

**Contesto della decisione, aggiunta dopo la stesura iniziale di questo documento, su domanda diretta del proprietario:** il proprietario ha chiesto se questo design fosse implicitamente tarato sul proprio range di ritmo configurato personale, piuttosto che generico. Risposta verificata: **no** — il meccanismo EMA/purge (Decisioni 2-5) non dipende da nessun valore di range specifico, solo τ (Decisione 1) è una costante empirica derivata da dati, e quella dipendenza è già dichiarata esplicitamente nei limiti sopra. La domanda ha però portato a concordare due modi concreti per alzare la fiducia in τ **prima** della prossima corsa reale, entrambi da fare **come parte dell'implementazione e del testing di P2**, non come una fase di ricerca separata né rimandati a "dopo": un test sintetico del caso peggiore, e un'analisi di sensitività sui dati reali già disponibili.

### 8.1 — Test sintetico del caso peggiore (banda stretta, alta frequenza di oscillazione)

**Precedente da seguire, stesso stile, non un nuovo framework di test:** questo progetto ha già una disciplina di validazione consolidata per `SpeedEstimator` — scenari sintetici prima, conferma su corsa reale poi (`app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt`: `NoisyConstantVelocityTrajectory`, una traiettoria a velocità vera nota con rumore gaussiano iniettato sulle posizioni, e `sustainedAccelerationTriggersFastSettlingViaAdaptiveProcessNoise`/`sustainedAccelerationSettlesFasterThanFixedQWithRealNoise`, che confrontano un comportamento adattivo contro un controllo a parametro fisso su una traiettoria deterministica a gradino, con tolleranze numeriche esplicite). Il test sintetico per τ deve seguire lo stesso stile: traiettoria costruita esplicitamente in codice, non registrata da un dispositivo reale, con numeri e tolleranze motivati nei commenti.

**Parametri di partenza, da riprodurre le condizioni della corsa 2026-09-13 (banda stretta, non quella 2026-09-15 usata per stimare τ):**

- **Banda RUN: 7.5-8.2 km/h (0.7 km/h)** — la stessa identica configurazione reale della corsa 2026-09-13, già citata in questo documento.
- **Ampiezza di oscillazione: picco-picco ~2.2 km/h** (7.19-9.38 km/h), presa dall'unico estratto concreto per-secondo che il documento di validazione 2026-09-13 riporta (una singola fase RUN di 70s, righe 66-80 di quel documento) — non è un dato per-tick completo di quella corsa (non esiste, P1 non esisteva ancora), ma è l'unica ricostruzione concreta disponibile, e va usata onestamente come tale nel commento del test, non spacciata per un replay di dati reali.
- **Periodo di oscillazione: breve, indicativamente 6-8s per ciclo completo**, scelto per essere coerente con la durata mediana di un episodio `GOOD` confermato in quella corsa (3.0s, citata nello stesso documento) — un ciclo che attraversa entrambi i bordi della banda in pochi secondi, abbastanza per produrre ripetuti flip diretti TOO_SLOW↔TOO_FAST senza smoothing, come osservato realmente (25 flip diretti).
- **Cadenza di campionamento: ~1Hz**, coerente con la cadenza reale dei fix GPS usati altrove in questo progetto e nei test di `SpeedEstimator`.

**Cosa il test deve verificare programmaticamente**, alimentando la stessa traiettoria sintetica sia direttamente a `PaceEvaluator` (baseline, nessuno smoothing) sia attraverso `EmaPaceSmoother(timeConstantSeconds = 6.0)` prima di `PaceEvaluator` (con smoothing):

1. **Il conteggio di flip diretti confermati `TOO_SLOW`↔`TOO_FAST`** cala in modo sostanziale con lo smoothing rispetto al baseline (il baseline, su questi parametri, deve riprodurre un comportamento di flapping ripetuto simile a quello osservato realmente — se non lo fa, i parametri sintetici vanno rivisti prima di trarre conclusioni su τ).
2. **Il lag di decisione introdotto resta limitato**, non degenera in un'unresponsività pratica: es. tempo dal momento in cui la traiettoria sintetica entra stabilmente in una nuova banda (es. un vero cambio di sforzo sostenuto, non un'oscillazione) al momento in cui `PaceEvaluator` conferma lo stato corrispondente, con smoothing attivo, resta entro un limite esplicito da fissare nel task di implementazione (es. qualche secondo oltre al lag già intrinseco del debounce `confirmationSamples`, non decine di secondi).

**Onestà esplicita:** questo test non sostituisce la validazione su corsa reale a banda stretta (Decisione 1, Domande aperte) — verifica che τ=6.0s si comporti ragionevolmente su un'approssimazione ragionata del caso peggiore, non sulla corsa 2026-09-13 originale, per cui non esistono dati per-tick.

### 8.2 — Analisi di sensitività su τ (stesso log reale 2026-09-15)

**Obiettivo:** verificare se la conclusione (τ≈6-7s) è stabile su un intervallo ragionevole di valori candidati, o se è fragile — nel qual caso saperlo è importante quanto il valore stesso.

**Onestà esplicita sul metodo, necessaria prima della tabella:** il log 2026-09-15 non contiene un flusso denso per-tick della velocità grezza (`LocationFixEvent` non porta `speedKmh`; solo `PaceStateConfirmedEvent`/`AudioCueEmittedEvent`, sparsi nel tempo, la portano) — è esattamente il gap che l'aggiunta di `rawSpeedKmh` (Decisione 6) risolve **per la prossima corsa**, non per questa. Una vera riproduzione tick-per-tick della formula EMA su dati storici non è quindi possibile su questo log. L'analisi di sensitività qui descritta è perciò una **stima approssimata per proxy**, non una simulazione esatta: usa gli n=130 intervalli già calcolati tra cambi di stato confermato consecutivi (Decisione 1) come proxy della durata di ogni escursione, e classifica un'escursione come "probabilmente smorzata" da un dato τ se la sua durata è più breve di τ (un'EMA con costante di tempo τ non ha il tempo di inseguire un'oscillazione più corta di τ fino a farla confermare come stato opposto) — un'euristica ragionevole, non un calcolo esatto sull'ampiezza reale del segnale.

**Tabella illustrativa, calcolata per questo documento sugli stessi n=130 intervalli di Decisione 1 (variante con l'ultimo intervallo RUN chiuso al log — vedi nota metodologica) — da riprodurre/raffinare come parte del task di implementazione P2, non un risultato finale:**

| τ candidato | Escursioni smorzate (durata < τ, su 130) | Escursioni che sopravvivono (durata ≥ τ) | Lag di assestamento implicito (~3τ, Decisione 2) |
|---|---|---|---|
| 4s | 42 (32%) | 88 (68%) | 12s |
| 6s | 61 (47%) | 69 (53%) | 18s |
| 8s | 83 (64%) | 47 (36%) | 24s |
| 10s | 101 (78%) | 29 (22%) | 30s |

**Lettura esplicita:** la frazione di escursioni smorzate cresce in modo continuo e senza discontinuità evidenti tra 4s e 10s — nessun "gomito" nella curva che indichi un valore chiaramente corretto o chiaramente sbagliato in questo intervallo. Questo è di per sé un'evidenza indipendente a favore della conclusione già dichiarata (τ nell'intervallo 6-7s è una scelta ragionevole, non fragile): spostarsi di qualche secondo in una direzione o nell'altra cambia il risultato gradualmente, non lo ribalta. Il costo del compromesso è visibile nella terza colonna: a τ=10s quasi 4 escursioni su 5 verrebbero smorzate, ma al prezzo di un lag di assestamento di 30s — probabilmente eccessivo per rispondere a un vero cambio di sforzo. Il task di implementazione deve rieseguire questa analisi (idealmente con dati per-tick reali dopo la prossima corsa con `rawSpeedKmh`, non solo con questo proxy) e riportare la stessa tabella con numeri aggiornati.

## Non-obiettivi espliciti

- **Non tocca `SpeedEstimator`/`KalmanSpeedEngine`** — già validati, fuori scope (Decisione 2).
- **Non tocca P3** (il bug `GOOD_PACE` silenzioso dopo `previous == null`, `PaceEvaluator.kt` righe 81-86) — bug distinto, ortogonale, da correggere a parte. Nota di interazione: questo documento aggiunge un parametro `rawSpeedKmh` alla stessa firma `accept()` che P3 dovrà toccare per il proprio fix — chi implementerà P3 dopo P2 troverà la firma già cambiata, non un conflitto ma una dipendenza d'ordine da tenere a mente.
- **Non tocca P4** (il limite di `RoutineEngine.advanceTo()` a due transizioni per chiamata) — bug distinto, già tracciato nella roadmap.
- **Non tocca P5** (audio enqueue-vs-playback) — lavoro indipendente.
- **Non allarga i range di pace configurati** — leva di prodotto/UX distinta, già raccomandata come complementare e a costo zero nel primo documento di ricerca (Fronte 2, raccomandazione 1); resta a disposizione del proprietario come leva indipendente, non scelta qui.

## Domande aperte per il proprietario

1. **τ = 6.0s è una stima di partenza da un solo run, in un regime già attenuato** (banda RUN 1.5 km/h, più del doppio della banda 0.7 km/h che ha originariamente motivato l'indagine) — va validata sulla prossima corsa reale, specialmente se registrata con una banda più stretta, dove il ciclo di oscillazione naturale potrebbe essere più breve. Decisione 8 aggiunge un test sintetico del caso peggiore e un'analisi di sensitività **come parte di P2, prima** della prossima corsa, per alzare la fiducia in τ nel frattempo — non sostituiscono la validazione su dati reali a banda stretta, la anticipano.
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
- `app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt` (branch `feature/session-event-logging`) — **aggiunto in questa revisione**: il serializzatore JSON scritto a mano di P1, non basato su reflection; letto per verificare che `paceStateConfirmedEvent()` serializzi davvero ogni campo dichiarato sulla data class, e per la gap del cambiamento di schema in Decisione 6
- `app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt` (branch `feature/session-event-logging`) — **aggiunto in questa revisione**: per l'ordine reale dei parametri del costruttore di `PaceStateConfirmedEvent`
- `app/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt`, `app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt`, `app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt` (branch `feature/session-event-logging`) — **aggiunti in questa revisione**: per i tre punti di rottura reali nei test elencati in Decisione 6
- `/home/fredworkstation/Downloads/paco-run-2026-09-15-110804-events.jsonl` (log eventi reale P1, 7346 righe, analizzato con uno script Python — analisi rieseguita indipendentemente in questa revisione per verificare tutti i numeri di Decisione 1)

**Documenti interni di riferimento (letti per intero prima di questo, non riprodotti qui):**
- `docs/superpowers/specs/2026-09-13-precision-and-cue-stability-research.md`
- `docs/superpowers/specs/2026-09-13-phase-aware-cue-smoothing-research.md`
- `docs/superpowers/specs/2026-09-14-session-event-logging-design.md`
- `docs/superpowers/specs/2026-09-11-precision-roadmap.md` (voci P1/P2/P3/P4/P5)
