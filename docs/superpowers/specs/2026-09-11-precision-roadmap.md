# Roadmap di precisione — documento di riferimento completo

**Data:** 2026-09-11. **Branch di origine:** `feature/paco-run-m3e`. **Scopo:** catalogo esaustivo di ogni tecnica nota (esclusi ML/reti neurali su richiesta esplicita dell'utente) per massimizzare la precisione dei readings di Paco Run — organizzato per zona (quale sensore/sottosistema), con per ciascuna: algoritmo, perché la vogliamo, perché si adatta alla nostra architettura, downside, conflitti/ridondanze con altre voci.

**Ambizione dichiarata dall'utente (2026-09-11):** Paco Run deve diventare più preciso della concorrenza di fascia alta (Garmin, Coros), e il punto di riferimento per chiunque monitori la velocità durante attività fisica — non solo corsa/camminata (rebrand futuro, fuori scope ora). Nessun compromesso accettato tra le opzioni: si vogliono TUTTE, sequenziate per stadio, testate una alla volta, ognuna nel suo branch.

## Stato delle decisioni (aggiornato 2026-09-11, sessione di conferma) — sezione di riferimento autoritativa

Questa sezione è la fonte di verità sullo stato di OGNI voce. Se una sezione più sotto nel documento sembra in contraddizione con questa tabella, **questa tabella vince** (è la più recente).

| Voce | Stato | Note |
|---|---|---|
| Refactor `MotionEngine` + 2 fix minori (doppia scrittura preferenze legacy, trick conversione `RoutineEngine.kt`) | **Completato (2026-09-13)** | `SessionDistanceEngine` estratto in `RunSessionStore.kt`, dual-review passata, `./gradlew testDebugUnitTest` (123 test) e `lintDebug` puliti, zero regressioni comportamentali. Branch `feature/motion-engine-refactor` (commit non ancora fuso su `feature/paco-run-m3e`) |
| Fase 0.5 — `SpeedEstimator` collegato alla produzione (`KalmanSpeedEngine`) | **Completato (2026-09-13) — Kalman è ora il motore reale, non più solo diagnostico** | `DopplerSpeedEngine` resta come rete di sicurezza, rimovibile solo dopo 5 corse reali validate col motore vero attivo (decisione dell'utente, non automatica — vedi `docs/superpowers/specs/2026-09-13-speed-production-wiring-design.md` sezione 7). Conteggio corse validate: 0/5 (le 2 corse precedenti erano diagnostiche, non contano) |
| G1 — doppia frequenza | Confermato, da verificare tecnicamente | Nessuna decisione di design, solo verifica |
| G2 — multi-costellazione | **Già risolto, zero azione, per sempre** | Non è un task, gestito automaticamente da Android |
| G3 — RAIM | **Scartata deliberatamente** | Superflua data la conferma di G6 (Huber), che ne è una generalizzazione |
| G4 — RTK/INS ("Motore B") | Confermato come obiettivo finale | Ramo dedicato futuro, dopo Fase 2/3, **dopo G5** (vedi sotto) |
| G5 — IMM sul meccanismo GPS esistente | **Rimandato, ma con posizione precisa: da fare PRIMA di G4 (RTK/INS), dopo la zona inerziale** | Non è "futuro generico" — l'utente vuole farlo prima di RTK/INS. Tocca `SpeedEstimator` già validato, richiede ri-validazione completa quando si arriva a farlo |
| G6 — filtri Huber | **Confermato, design scritto (2026-09-19)** | Peso robusto congiunto (statistica χ²(2) sui due assi) sul residuo, non sull'accuratezza dichiarata — vedi `docs/superpowers/specs/2026-09-19-fase1-robust-outlier-rejection-design.md`. Sostituisce SOLO il lavoro di esclusione dalla EMA di G7, non il suo secondo lavoro (forzare qLow su accuratezza degradata) — vedi nota su G7 |
| G7 — gate anti-falso-allarme (già costruito) | **Parzialmente sostituito da G6 (2026-09-19), parzialmente resta** | G7 fa due lavori distinti: (a) esclude un fix degradato dalla EMA di innovazione — sostituito dal peso robusto di G6, più preciso perché reagisce al residuo osservato, non al metadato; (b) **forza il regime qLow quando l'accuratezza crolla, indipendentemente da cosa dice l'EMA — questo lavoro RESTA, non va rimosso.** Trovato in review avversariale (2026-09-19): il peso di G6 normalizza il residuo rispetto alla varianza già gonfiata dall'accuratità scarsa, quindi un fix rumoroso "per colpa dell'accuratezza" può risultare non-anomalo al test di G6 pur essendo esattamente il tipo di rumore che il commento originale in `SpeedEstimator.kt` (righe 30-40) descrive come capace di ingannare il rilevatore di accelerazione — bug già trovato e risolto una volta (motivo per cui G7(b) esiste), da non reintrodurre |
| G8 — smoothing a ritardo fisso | **Scartato per la Fase 1 (2026-09-19)** | Deciso in discussione con l'utente: il residuo tra il MAE attuale (0.242 km/h, Kalman già in produzione) e il tetto teorico non-causale (0.16 km/h) è solo 0.08 km/h — più piccolo del rumore della stessa fonte (Strava) usata per misurarlo. Applicato ai cue live introdurrebbe un ritardo di 2-3s su OGNI cue, incluso a inizio fase, riaprendo un problema che P2 aveva già risolto. Costo reale contro un guadagno sotto la soglia di rumore: non implementato. Vedi `docs/superpowers/specs/2026-09-19-fase1-robust-outlier-rejection-design.md` |
| G9 — consapevolezza DOP | **Confermato** | Da valutare in dettaglio in fase di design |
| G10 — map-matching | Bassa priorità, non pianificato ora | Legato a feature UI mappa future |
| G11 — RTK di rete (a pagamento) | **Escluso permanentemente** | Infrastruttura enterprise fuori scope |
| I1 — passi+cadenza, Approccio B | Confermato | Architettura decisa |
| I2 — modello lunghezza passo | **Confermato: TCSLE** | Scelta definitiva tra Weinberg/Kim/Ladetto/TCSLE |
| I3 — EKF per la direzione | Confermato (necessità tecnica) | Dettaglio implementativo, non una decisione di prodotto |
| I4 — bias/deriva sensori inerziali | **Confermato** | Da valutare in dettaglio in fase di design |
| I5 — classificazione modalità trasporto | **Confermato** | Da valutare in dettaglio in fase di design |
| I6 — ZUPT | Non applicabile ora | In attesa di E1 (footpod) |
| A1 — fusione magnetometro (AHRS) | **Confermato** | Da includere nel design della zona inerziale |
| A2 — altimetro barometrico + graded pace | **Confermato come priorità** | Brainstorming dedicato non ancora iniziato, tocca anche decisioni UI |
| E1 — footpod Bluetooth | Obiettivo dichiarato, non pianificato nel dettaglio | Futuro lontano |
| ML/reti neurali | Escluso permanentemente | Richiesta esplicita dell'utente |
| Radar/telecamera | Escluso permanentemente | Impraticabile per uso mobile in corsa |

| G12 — velocità da Doppler grezzo per-satellite | **Confermato, priorità altissima** | Fase 2.5, subito dopo la zona inerziale — riusa l'arbitro dell'Approccio B |
| G13 — modello CA (accelerazione costante) invece di CV | **Confermato, priorità alta** | Raggruppato con G5, stesso file/stesso costo di riapertura, rimandato ma prima di RTK/INS |
| G14 — costo di reconvergenza dopo un reset del motore velocità | Confermato, priorità bassa/media (ridimensionato da una prima descrizione eccessiva) | Vedi sezione dedicata sotto. Vincolo permanente per qualunque lavoro futuro sui trigger di reset: prima di allentare ulteriormente un reset esistente serve un test che simuli esplicitamente un'interruzione lunga con spostamento reale nel frattempo |
| P1 — logging strutturato di eventi/decisioni (cue, cambi fase, stato interno del filtro) | **Confermato, priorità massima per il prossimo filone di lavoro — prerequisito, non rimandabile** | Nessun log di questo tipo esiste oggi — ogni analisi finora ha dovuto RICOSTRUIRE cue/transizioni di fase dal segnale grezzo più la configurazione dichiarata a voce dall'utente, mai osservarli direttamente. Blocca la scelta di qualunque parametro numerico per P2 (finestra, costante di tempo). Vedi `docs/superpowers/specs/2026-09-13-kalman-production-run1-validation.md` (limite dichiarato esplicitamente) |
| P2 — smoothing della decisione di pace, consapevole del cambio fase | **Confermato nell'approccio, parametri numerici in attesa dei dati di P1** | Risolve il flapping Troppo veloce↔Troppo lento osservato nella prima corsa reale con Kalman in produzione (25 transizioni dirette su 347, non causate da rumore del sensore — il segnale di Paco Run era più pulito di quello di Strava nello stesso istante — ma dalla naturale variabilità della corsa contro un range configurato troppo stretto). Approccio finale, dopo due cicli di ricerca e review indipendente: svuotare il buffer/stato di smoothing al momento esatto del cambio fase (noto in anticipo da `RoutineEngine`, non da rilevare statisticamente), lasciarlo riempire da solo — non una finestra fissa con curva di ricrescita inventata. Vedi `docs/superpowers/specs/2026-09-13-precision-and-cue-stability-research.md` e `docs/superpowers/specs/2026-09-13-phase-aware-cue-smoothing-research.md` |
| P3 — bug: cue "Buon ritmo" soppresso dopo un cambio fase | **Confermato, correzione piccola e slegata da P1/P2** | `PaceEvaluator.updateConfig` azzera `confirmedPace` ad ogni cambio fase, sopprimendo silenziosamente il primo cue "Buon ritmo" riconquistato subito dopo — trovato durante l'analisi della corsa del 2026-09-13, non dipende dal logging né dallo smoothing, correggibile subito |
| P4 — `RoutineEngine` deve enumerare ogni transizione di fase reale, non solo fino a due per chiamata | **Confermato, DA FARE — non è una nota a piè di pagina, è un impegno vero, programmato subito dopo P1** | Trovato durante la review del design di P1 (2026-09-14): `advanceTo()` riassegna `phase` al massimo due volte per chiamata (`transitions` limitato a {0,1,2}); un ritardo che salta due o più cicli interi perde le fasi intermedie perché il codice stesso non le conserva, non per un limite del log. Stessa categoria del problema già trovato e corretto per `DistanceAccumulator` (vedi voce sopra), ma qui la correzione tocca la logica delle fasi della Fase 0 — più invasiva, non una pulizia meccanica. Vedi `docs/superpowers/specs/2026-09-14-session-event-logging-design.md`, sezione su Decisione 6/`RoutineEngine`, per il dettaglio tecnico completo |
| P5 — `AudioCueEmittedEvent` registra l'accodamento del cue, non la riproduzione audio effettiva | **Confermato, DA FARE — tracciato esplicitamente, non da correggere dentro il piano di P1** | Trovato durante la review finale del piano di P1 (2026-09-14): l'evento viene emesso nello stesso punto in cui il cue viene passato a `onCues(...)`, prima che `CuePlayer` lo riproduca davvero — se l'intera coda audio viene scartata (es. audio focus perso per una chiamata in arrivo durante la corsa), il log continuerebbe a dire che il cue è stato "emesso" senza che l'utente l'abbia mai sentito. Non è un errore di collegamento (la review di design richiedeva esplicitamente questo punto di emissione) — correggerlo vorrebbe dire toccare `CuePlayer`, un componente diverso, fuori scope per il piano di P1 |
| P6 — isteresi (zone cuscinetto) sul confine di valore in `PaceEvaluator`, mai implementata | **Confermato, DA BRAINSTORMARE — priorità: immediatamente dopo la Fase 1 (richiesta esplicita dell'utente, 2026-09-19)** | Filo pendente mai chiuso, non una nuova idea. Proposta dall'utente stesso il 2026-09-13 insieme al dwell-time; `docs/superpowers/specs/2026-09-13-precision-and-cue-stability-research.md` (sezione "Fronte 2") ha concluso che isteresi (margine sul *valore* della soglia, entrata/uscita da uno stato con soglie diverse) e debounce a conteggio (durata dell'escursione, già presente come `confirmationSamples`) sono **assi ortogonali, non ridondanti**, e ha raccomandato di implementare entrambi. `PaceEvaluator.kt` righe 138-139 usa oggi la stessa identica soglia per entrare e uscire da uno stato — nessuna isteresi esiste nel codice. È poi arrivato P2 (smoothing EMA phase-aware sul valore, non sulla soglia) a risolvere lo stesso sintomo (flapping TOO_SLOW↔TOO_FAST) con una terza tecnica ancora diversa; la ricerca successiva (`docs/superpowers/specs/2026-09-13-phase-aware-cue-smoothing-research.md`, fine documento) ha segnalato esplicitamente che la composizione a tre vie (isteresi + debounce + EMA-decay) "non è stata analizzata in profondità e andrebbe fatta nel brainstorm di design, non assunta" — quel brainstorm non è mai stato fatto, P2 è stato spedito senza isteresi. Non è Fase 1 (quella è accuratezza del *segnale* di velocità) — è decisione sul ritmo dato un segnale già corretto, stessa famiglia di P2/P3 |

**G8 era l'unica decisione bloccante per la pianificazione della Fase 1 — risolta il 2026-09-19 (scartato, vedi tabella sopra). La Fase 1 ha ora un design scritto: `docs/superpowers/specs/2026-09-19-fase1-robust-outlier-rejection-design.md`.**

## Aggiornamento 2026-09-13 — due nuove voci ad altissima priorità, esplicitamente per la velocità

Contesto: seconda corsa reale (2026-09-12) validata con successo (vedi `docs/superpowers/specs/2026-09-10-speed-accuracy-design.md`), poi confronto punto-per-punto Strava/Paco Run che ha dimostrato che le divergenze osservate sono artefatti del metodo di confronto (finestra di smoothing), non disaccordi reali tra i due telefoni — utile ma ha chiarito che Strava (stesso telefono, stesso GPS) non può fare da arbitro indipendente per la velocità puntuale, e l'utente non ha accesso a un campo da corsa certificato per un test fisico indipendente. Priorità dichiarata dall'utente: la velocità istantanea conta più di tutto il resto del documento — è il prodotto.

### G12 — Velocità da Doppler grezzo per-satellite (NUOVO, priorità altissima)

- **Algoritmo:** ogni satellite GNSS trasmette, oltre alla posizione, un dato di quanto velocemente la distanza satellite-ricevitore sta cambiando (effetto Doppler) — accessibile grezzo per singolo satellite tramite Android `GnssMeasurement.getPseudorangeRateMetersPerSecond()`, **senza richiedere la risoluzione dell'ambiguità di fase** che rende dura la posizione RTK/INS (G4). Combinando il Doppler di più satelliti con un minimi-quadrati pesato (pesato per qualità del segnale, C/N0) si ottiene una velocità tipicamente molto più precisa di `location.speed`, perché il Doppler è quasi immune al multipath che degrada invece la posizione/pseudorange.
- **Perché lo vogliamo:** è la metà "facile" di RTK/INS, quella che risponde direttamente e solo alla priorità dichiarata dall'utente (velocità), senza pagare il costo della metà dura (posizione centimetrica).
- **Rischi reali, non ipotetici:**
  1. Serve un numero minimo di satelliti con buona geometria — con pochi satelliti o geometria scadente (satelliti ammassati nella stessa zona di cielo) la soluzione degrada, serve un controllo di qualità (satelliti visti + loro distribuzione) prima di fidarsi del risultato in quell'istante specifico.
  2. Anche il Doppler può avere dati anomali per singolo satellite (meno soggetto al multipath della posizione, ma non immune) — serve pesatura per qualità del segnale (C/N0) e rigetto/degrado dei satelliti sospetti, stessa filosofia di Huber/RAIM (G6/G3) riapplicata qui.
  3. Il drift dell'orologio interno del chip GPS va risolto congiuntamente alla velocità (un calcolo a più incognite: velocità + drift orologio), non ignorato — altrimenti il drift si traveste da errore di velocità.
  4. **Il rischio più subdolo: non ripetere l'errore di `location.speed`.** Quell'errore non stava nel Doppler in sé, stava nel fidarsi ciecamente di un numero già confezionato da Android il cui margine di errore dichiarato non rifletteva il suo vero comportamento. Costruendo questa soluzione da satelliti grezzi, va caratterizzata e validata da zero con lo stesso rigore (test sintetici + corse reali) — "è dato grezzo" non vuol dire "automaticamente meglio", va dimostrato.
  5. Supporto variabile per dispositivo (stesso discorso di G4: serve `GnssCapabilities` controllato all'avvio, e un ripiego pulito quando non disponibile/affidabile — anche momento per momento dentro la stessa corsa, non solo sì/no fisso per l'intera sessione).
- **Come farlo bene:** minimi quadrati pesati (non media semplice) con peso per satellite basato su C/N0; controllo minimo su numero/geometria satelliti prima di fidarsi del fix in quell'istante, con ripiego automatico se insufficiente; stima congiunta del drift dell'orologio; **architetturalmente NON iniettarlo come misura diretta dentro il Kalman di `SpeedEstimator`** (rischio di ripetere l'errore di `location.speed`) — trattarlo come uno stimatore indipendente, combinato tramite lo stesso arbitro a fiducia dinamica dell'Approccio B già usato per l'accelerometro (I1), come terza fonte; stessa disciplina di validazione (sintetico poi corsa reale) di tutto il resto.
- **Sequenza:** Fase 2.5, subito dopo la zona inerziale (I1-I5) — riusa l'arbitro già costruito lì invece di costruirne uno nuovo da zero. **Non è più raggruppato con G4** (che resta solo la parte posizione/ambiguità di fase, quella dura, Fase 4).

### G13 — Modello a "accelerazione costante" (CA) invece di "velocità costante" (CV) nel filtro (NUOVO, priorità alta)

- **Algoritmo:** il Kalman di `SpeedEstimator` oggi assume velocità costante finché l'interruttore adattivo non rileva un'accelerazione (modello "CV", l'accelerazione è trattata come rumore da tollerare). Il modello "CA" tratta l'accelerazione come parte esplicita dello stato tracciato, non come rumore da scoprire dopo — più naturale nei momenti di vera accelerazione sostenuta.
- **Perché lo vogliamo:** raffinamento diretto del cuore matematico già esistente, non un sistema nuovo.
- **Downside/rischio:** tocca lo stesso file già validato (`SpeedEstimator`) di G5 (IMM) — stessa categoria di costo (ri-validazione completa).
- **Sequenza:** **raggruppato con G5 (IMM)**, stesso motivo (stesso file, stesso tipo di costo di riapertura) — quando si decide di affrontare G5, si valuta insieme anche questo, un'unica riapertura invece di due. Resta comunque "rimandato, ma prima di RTK/INS" come già deciso per G5.

## Architettura a due motori (chiarito 2026-09-11, correzione importante rispetto al ragionamento iniziale)

**RTK/INS (G4) NON sostituisce il resto — è un secondo motore permanente, non una fase successiva che rende superfluo il primo.** Ragionamento: l'accesso ai dati GNSS grezzi di buona qualità (`GnssMeasurement`/`GnssCapabilities`) non è garantito su tutti i dispositivi Android — molti telefoni economici/datati, che saranno una fetta reale e probabilmente ampia del pubblico Play Store, non lo supporteranno mai bene. Per quegli utenti, **il motore "normale"** (`SpeedEstimator` + zona GPS + zona inerziale, quello su cui si sta lavorando ora) **resta l'unica cosa che avranno, per sempre** — non uno stadio temporaneo in attesa di essere sostituito.

**Architettura finale prevista:**
- **Motore A (il "normale"):** `SpeedEstimator` + tutte le tecniche della zona GPS (G3/G6/G7 ecc.) + zona inerziale (I1-I5) + chip ausiliari (A1/A2). Selezionato di default, e sempre selezionato su dispositivi senza supporto GNSS grezzo adeguato.
- **Motore B (RTK/INS, G4):** dati GNSS grezzi + INS accoppiati stretti in un EKF unificato. Selezionato automaticamente (via `GnssCapabilities`, controllato all'avvio) solo sui dispositivi che lo supportano bene — probabilmente il telefono dell'utente stesso (Pixel 9a) rientra in questa categoria.
- Selezione **automatica e trasparente**, nessun intervento manuale per-dispositivo/per-chip richiesto (l'astrazione hardware la fa Android stesso tramite l'API standard).

**Conseguenza pratica sulla priorità:** il lavoro sul Motore A (zona GPS + zona inerziale, sequenza già concordata) **non è più lavoro "verso" RTK/INS che poi diventa superfluo** — è lavoro che determina la qualità reale per la maggioranza probabile degli utenti finali, permanentemente. Motore B resta comunque importante (è quello che l'utente userà personalmente, ed è il traguardo di precisione più alto in assoluto), ma i due si costruiscono e si mantengono IN PARALLELO per pubblici diversi, non in sequenza dove uno rimpiazza l'altro.

**Sequenza di lavoro concordata:** si procede per zona. **Zona GPS prima** (un branch dedicato per ogni singola feature GPS, testata e validata prima di passare alla successiva), poi zona inerziale (accelerometro+giroscopio, branch `feature/imu-speed-fusion`), poi chip ausiliari (magnetometro, barometro), poi hardware esterno futuro (footpod).

---

## Zona GPS (posizione)

Tutte le tecniche qui agiscono sul segnale di posizione GPS, indipendentemente da accelerometro/giroscopio. Componente di riferimento: `SpeedEstimator` (Kalman adattivo su sola posizione, già implementato e validato — `docs/superpowers/specs/2026-09-10-speed-accuracy-design.md`).

### G1. GPS a doppia frequenza (L1+L5)

- **Algoritmo:** i satelliti GNSS moderni trasmettono su due bande di frequenza. La seconda banda (L5, più recente) è molto meno soggetta a multipath (i rimbalzi del segnale su edifici/superfici) rispetto alla singola L1 storica. Un ricevitore dual-band combina le due misure per una posizione più pulita.
- **Perché lo vogliamo:** il Pixel 9a dell'utente ha GPS "Dual Band" (confermato via specifiche ufficiali Google Store) — potenziale beneficio già presente nell'hardware, forse non ancora sfruttato pienamente da come l'app richiede la posizione ad Android.
- **Perché si adatta:** nessuna fusione nostra necessaria in prima battuta — è potenzialmente un beneficio "gratis" se Android lo sta già usando; se non lo sta usando, si tratta solo di verificare/configurare correttamente la richiesta di posizione (`LocationRequest`), non di scrivere un nuovo algoritmo.
- **Downside:** dipende dal chip del dispositivo — su Play Store, molti utenti avranno telefoni senza dual-band (specialmente fascia bassa). Il nostro filtro già gestisce questo caso correttamente pesando l'accuracy dichiarata (vedi Multi-costellazione sotto), quindi non è un vero downside per l'architettura, solo un beneficio non universale.
- **Conflitti/ridondanze:** **parzialmente ridondante con G4** (bypass GNSS grezzo) — se in futuro si costruisce la fusione da dati grezzi, quella sfrutterebbe comunque al meglio qualunque banda disponibile, rendendo la verifica isolata di G1 un investimento "a perdere" nel lungo termine. Non urgente risolverlo ora, va solo saputo.
- **Zona/stadio:** GPS, priorità 2, branch dedicato piccolo.

### G2. Multi-costellazione (GPS+GLONASS+Galileo+BeiDou)

- **Algoritmo:** più satelliti visibili da più sistemi = geometria di posizionamento migliore, meno errore.
- **Perché lo vogliamo:** già ottenuto.
- **Perché si adatta:** **nessuna azione di codice richiesta** — Android/FusedLocationProvider la usa già in automatico in base al chip; il nostro filtro pesa già correttamente l'accuracy dichiarata (che riflette anche una geometria satellitare peggiore su chip più limitati).
- **Downside:** nessuno per noi. Un utente con telefono economico avrà semplicemente accuracy media peggiore, già gestita.
- **Conflitti/ridondanze:** nessuno.
- **Zona/stadio:** GPS, già chiuso, nessuna azione.

### G3. RAIM / rigetto delle misure anomale basato sull'innovazione (Receiver Autonomous Integrity Monitoring)

- **Algoritmo:** tecnica nata in aviazione, oggi documentata anche per GNSS su smartphone (confermato via letteratura recente, 2026): si monitora l'"innovazione" (lo scostamento tra la misura e la predizione del filtro) e si applica un test statistico formale (chi-quadrato sulla distanza di Mahalanobis) per **rigettare del tutto** una misura, non solo pesarla meno, quando è statisticamente troppo improbabile dato lo stato attuale.
- **Perché lo vogliamo:** è la versione "da manuale" di quello che abbiamo già costruito a mano (il gate anti-falso-allarme su crollo di accuratezza). Confermato dalla letteratura: "innovation-based outlier rejection" è l'approccio standard contro multipath/non-line-of-sight — il nostro istinto progettuale era già allineato alla pratica professionale.
- **Perché si adatta:** si integra naturalmente nel passo di aggiornamento del Kalman già esistente in `SpeedEstimator` — non richiede dati satellitari grezzi (a differenza di G4), funziona anche solo sull'innovazione della posizione fusa che già riceviamo.
- **Downside:** la letteratura nota esplicitamente che test basati sull'innovazione "possono confondere errori lenti e progressivi (slow ramp faults) con rumore di misura residuo" — cioè un degrado GRADUALE (non un salto improvviso) è più difficile da distinguere dal rumore normale. Coerente con quello che avevamo già osservato: nella corsa reale disponibile oggi, il rumore cresce sempre gradualmente, mai a scatti.
- **Conflitti/ridondanze:** **sovrapposizione diretta con il gate anti-falso-allarme già costruito (voce G7 sotto) e con G6 (filtri Huber)** — tutte e tre risolvono la stessa classe di problema (misure anomale) con formalismi diversi. Vanno confrontate, non necessariamente sommate tutte e tre.
- **Zona/stadio:** GPS, da valutare nel design della formalizzazione del gate esistente.

### G4. Fusione da dati GNSS grezzi (pseudorange/Doppler per satellite), bypassando il filtro di Android

- **Algoritmo:** invece di fidarsi della posizione già elaborata da Android (`FusedLocationProvider`, una scatola nera), leggere le misure grezze per-satellite (`GnssMeasurement` API) e fare la propria fusione, tipicamente con un EKF che stima posizione, velocità, orientamento E i bias dei sensori come stato congiunto (architettura "RTK/INS fusion").
- **Perché lo vogliamo:** dato concreto da letteratura 2026 trovato oggi — sistemi RTK/INS su smartphone (testati su Xiaomi Mi 8, Samsung Galaxy S21 Ultra) raggiungono **precisione decimetrica (0.38-0.42 metri) anche in ambiente urbano complesso**. Per confronto, oggi siamo a 3-6 metri di accuratezza tipica. È un salto di qualità enorme, reale, documentato — non teorico.
- **Perché si adatta:** richiede un ramo dedicato a sé, come già deciso — è un "overhaul" vero, non un'aggiunta incrementale a `SpeedEstimator`.
- **Downside:** settimane di ingegneria GNSS seria (non giorni); richiede accesso a `GnssMeasurement`, non disponibile/affidabile su tutti i dispositivi Android (dipende dal produttore/chip, va verificato caso per caso su Play Store); massima complessità di tutto questo documento.
- **Conflitti/ridondanze:** rende **parzialmente superflui G1 (doppia frequenza) e G3 (RAIM)** una volta completato, perché li incorporerebbe entrambi in modo più potente e nativo.
- **Zona/stadio:** GPS, **ramo dedicato futuro**, dopo che gli altri sviluppi sono conclusi (deciso esplicitamente dall'utente).

### G5. Interacting Multiple Model (IMM) — applicato al meccanismo GPS esistente

- **Algoritmo:** invece di un singolo interruttore a soglia fissa (qLow/qHigh) come oggi, far girare in parallelo più filtri di Kalman, ognuno specializzato per un "regime" (stabile, in accelerazione), e combinare le loro stime pesandole in base a quanto ciascuna spiega bene le osservazioni recenti — invece di uno switch netto.
- **Perché lo vogliamo:** più principiato del nostro switch a soglia fissa, tecnica consolidata in navigazione/radar professionale da decenni.
- **Perché si adatta:** è un upgrade diretto del meccanismo adattivo già dentro `SpeedEstimator`.
- **Downside importante, già segnalato:** **tocca codice già validato e spedito** — richiede riaprire `SpeedEstimator`, rifare dual-review e ri-validazione completa sui dati reali. Non è un'aggiunta a costo zero.
- **Conflitti/ridondanze:** nessuno diretto, ma **sovrapposizione concettuale con G3/G6** (sono tutti modi di gestire meglio situazioni anomale/multi-regime) — da considerare insieme, non isolatamente, quando si decide il design.
- **Zona/stadio:** GPS, da decidere se includere ora o come suo progetto a parte (vedi sezione conflitti finale).

### G6. Filtri robusti (Huber / M-estimation) al posto del Kalman a minimi quadrati classico

- **Algoritmo:** il Kalman classico assume rumore sempre gaussiano "ben educato"; una variante robusta modifica il passo di aggiornamento per dare meno peso a un singolo residuo anomalo, in modo statisticamente più elegante di una soglia scritta a mano. **Non sostituisce il Kalman — è una variante dello stesso identico algoritmo**, cambia solo la formula del passo di aggiornamento.
- **Perché lo vogliamo:** gestisce gli outlier isolati senza bisogno di una soglia hard-coded.
- **Perché si adatta:** stessa struttura predict/update già in uso, nessun cambio architetturale.
- **Downside:** aggiunge un parametro di tuning in più (la soglia di "quanto è troppo anomalo"), da validare con la stessa disciplina (sintetico + reale) di tutto il resto.
- **Conflitti/ridondanze:** **probabilmente ridondante con il gate anti-falso-allarme già costruito (G7) e con G3 (RAIM)** — risolvono la stessa classe di problema. Va scelto UNO dei tre approcci per questo scopo specifico, non tutti e tre insieme, per non sommare complessità senza beneficio proporzionale.
- **Zona/stadio:** GPS, da confrontare con G3/G7 prima di scegliere.

### G7. Gate anti-falso-allarme su crollo di accuratezza (già costruito)

- **Algoritmo:** implementazione già esistente (2026-09-11) — baseline EMA a memoria lunga dell'accuratezza GPS; se l'accuratezza corrente supera 2× la baseline, forza il regime "calmo" e quel fix non contribuisce alla media mobile dell'innovazione.
- **Perché lo vogliamo:** già fatto, già validato su test sintetici (98% di margine) e su una verifica di non-interferenza sulla corsa reale disponibile.
- **Perché si adatta:** già dentro `SpeedEstimator`, dual-reviewed.
- **Downside:** **mai validato sul caso reale che dovrebbe risolvere** (nessuna corsa dell'utente ha mai avuto un vero crollo di accuratezza — vedi memoria di progetto sulle condizioni di corsa in aperta campagna). Resta "provato solo in teoria" finché non arriva una corsa in condizioni GPS scarse.
- **Conflitti/ridondanze:** **stessa classe di problema di G3 e G6** — è la versione "fatta a mano" di quello che RAIM/Huber farebbero in modo più formale. Da tenere come riferimento/baseline quando si valutano G3 e G6, non necessariamente da sostituire.
- **Zona/stadio:** GPS, già chiuso, riferimento per il confronto G3/G6.

### G8. Smoothing a ritardo fisso (Rauch-Tung-Striebel / fixed-lag smoothing)

- **Algoritmo:** invece di mostrare la velocità nell'istante stesso in cui viene calcolata, la si mostra con un piccolo ritardo controllato (2-3 secondi), usando quei secondi di dati aggiuntivi per rifinire la stima con un passaggio all'indietro oltre a quello in avanti.
- **Perché lo vogliamo:** **beneficio già quantificato nei nostri stessi dati** — il "tetto teorico" calcolato durante l'indagine del 2026-09-10 (0.16 km/h, ottenuto "barando" con dati futuri) è esattamente quello che questa tecnica avvicina concretamente, in modo controllato e limitato.
- **Perché si adatta:** si applica come post-processing sull'output già esistente di `SpeedEstimator`, non richiede toccare il filtro stesso.
- **Downside importante:** **introduce un ritardo percepibile nei cue vocali.** `PaceEvaluator` ha già un suo meccanismo di conferma (`confirmationSamples`) che aggiunge un ritardo prima di annunciare un cambio di ritmo — sommare un ritardo di smoothing sopra quello rischia di rendere la reazione complessiva ai cambi di ritmo percepibilmente più lenta. Da misurare, non da assumere accettabile.
- **Conflitti/ridondanze:** **tensione diretta con la filosofia "reagisci in fretta" del progetto** (era il motivo per cui avevamo scartato le finestre scorrevoli lunghe nell'indagine originale). Non è incompatibile tecnicamente con nient'altro, ma è l'unica tecnica di questo documento con un costo UX diretto e misurabile da validare esplicitamente con l'utente prima di attivarla.
- **Zona/stadio:** GPS, priorità alta ma richiede validazione esplicita del trade-off latenza/precisione.
- **Risolto (2026-09-19): scartato per la Fase 1.** Il trade-off qui sopra è stato validato esplicitamente con l'utente, come richiesto — e la risposta è che il beneficio residuo (0.242→0.16 km/h, cioè 0.08 km/h) è sotto il rumore del metodo di misura stesso, mentre il costo (ritardo su ogni cue, incluso a inizio fase) è reale e riaprirebbe un problema che P2 ha già risolto. Vedi tabella "Stato delle decisioni" e `docs/superpowers/specs/2026-09-19-fase1-robust-outlier-rejection-design.md`.

### G9. Consapevolezza della geometria satellitare (DOP — Dilution of Precision)

- **Algoritmo:** un valore che descrive quanto è "buona" la geometria dei satelliti visibili in un dato istante, indipendentemente dal rumore di ogni singola misura — satelliti tutti ammassati in una parte di cielo danno un DOP cattivo anche se il segnale di ognuno è pulito. **Non è un campo che Android fornisce già pronto**: va calcolato da noi a partire da elevazione/azimuth di ogni satellite usato nel fix (`GnssStatus.getElevationDegrees()`/`getAzimuthDegrees()`/`usedInFix()`, già disponibili dall'API 24, non 26 — errore di citazione corretto in review, irrilevante dato che `minSdk = 29`) — costruire la matrice di geometria e invertirla è vero lavoro di ingegneria, non la lettura di un valore.
- **Perché lo vogliamo:** un segnale di qualità aggiuntivo, più ricco della sola `horizontalAccuracyMeters` già usata, ottenibile con uno sforzo molto minore di G4 — ma resta comunque sforzo reale, non un add-on gratuito.
- **Perché si adatta:** potrebbe alimentare lo stesso meccanismo di fiducia già usato per pesare i fix (accanto o al posto di `horizontalAccuracyMeters`).
- **Downside:** non tutti i dispositivi espongono `GnssStatus` in modo completo/affidabile; beneficio da validare empiricamente prima di contarci.
- **Conflitti/ridondanze:** nessuno diretto, ma si sovrappone parzialmente con l'informazione già contenuta in `horizontalAccuracyMeters` (Android calcola quel valore tenendo già conto, in parte, della geometria). Da verificare quanta informazione aggiuntiva porti davvero prima di implementarlo.
- **Perché non c'è ancora evidenza reale, e perché questo NON significa "non serve" (nota esplicita, 2026-09-19):** nessuna corsa registrata finora mostra un'anomalia attribuibile a geometria satellitare scarsa (l'unica anomalia trovata, il 15 settembre, era legata a un'interruzione GPS, G14, non a DOP) — ma l'unico dato disponibile è **un utente, un dispositivo, un ambiente** (campagna aperta, vedi nota di progetto sulle condizioni di corsa). Questo è esattamente lo stesso limite già riconosciuto per il gate G7 ("mai validato sul caso reale che dovrebbe risolvere") e per qualunque fix di degraded-GPS in questo documento: **l'assenza di evidenza su un singolo dispositivo/ambiente non è evidenza di assenza**, specialmente per un'app destinata al Play Store dove altri dispositivi (chip GNSS diversi, firmware diversi) e altri ambienti (canyon urbani, sentieri boscosi) di altri utenti potrebbero esporre il problema che qui non si è mai visto. **Decisione conseguente (2026-09-19):** non si scarta G9 per mancanza di evidenza — si abilita SOLO il logging di elevazione/azimuth per satellite (costo basso, nessun impatto sul motore), così che il dato esista per essere ispezionato in futuro; il calcolo DOP vero e la sua integrazione nel motore di pesatura restano rimandati fino a quando un caso reale (su questo dispositivo o riportato da altri) li giustifica concretamente — non fino a quando "non capita più a me".
- **Zona/stadio:** GPS, nuova voce emersa oggi, priorità da definire.

### G14. Costo di reconvergenza dopo un reset del motore velocità (NUOVO, trovato durante Fase 0.5, 2026-09-13)

- **Il problema originale (risolto in Fase 0.5, non qui):** `SessionCoordinator` chiamava `speedEngine.breakChain()` su OGNI ingresso in outage, anche per un singolo fix isolato giudicato inaffidabile — non solo per interruzioni GPS genuinamente prolungate. Poiché `SpeedEstimator` riparte da 0.0 dopo un reset e impiega diversi fix reali per riconvergere, un blip isolato (frequente in corsa reale) produceva alcuni secondi di cue vocali sbagliati pur non essendoci mai stato un vero cambio di ritmo. **Corretto in Fase 0.5**: il reset del motore velocità ora scatta solo su `signalGpsOutage == true` (la stessa soglia di ~3 secondi già usata da `GpsFreshnessTracker` per il resto del sistema), non su un singolo fix isolato. Il motore di distanza non è stato toccato (il suo costo di reset è diverso e già accettabile).
- **Quello che RESTA, ridimensionato correttamente rispetto a una prima descrizione eccessiva fatta in chat:** dopo un'interruzione GPS *genuinamente* prolungata (quella che scatena `signalGpsOutage`), il motore velocità continua a resettarsi e a impiegare alcuni fix per riconvergere — esattamente come faceva prima della Fase 0.5, comportamento invariato, non introdotto né peggiorato da questa fase. È un costo UX modesto (qualche cue "lento" di troppo dopo un vero buco GPS), non un numero falso o pericoloso.
- **Il rischio vero, distinto, da NON confondere con quanto sopra:** durante la discussione è emersa un'alternativa scartata — "non resettare mai il motore velocità, lascia che la crescita naturale dell'incertezza del Kalman gestisca qualunque gap". Analizzata a fondo: per un'interruzione lunga durante la quale il runner si sposta davvero con altri mezzi (es. sale in auto, o il GPS riaggancia altrove per multipath), un Kalman "mai resettato" produrrebbe un'estrapolazione a velocità costante sull'intero gap, e il fix successivo genererebbe un'innovazione enorme che il filtro tradurrebbe in una velocità implicita assurda (potenzialmente centinaia di km/h) su un singolo fix marcato affidabile — quindi mostrabile in un cue vero. Questo rischio **non esiste nel codice attuale né nella correzione di Fase 0.5** (che continua a resettare regolarmente sulle interruzioni lunghe) — esiste SOLO se in futuro qualcuno decidesse di allentare ulteriormente il reset anche per le interruzioni lunghe, senza prima avere in piedi un meccanismo di rigetto robusto (G3/G6/G9) capace di riconoscere e scartare un'innovazione implausibile.
- **Vincolo permanente per il futuro (questo è il motivo per cui questa voce esiste):** qualunque lavoro futuro che allenti ulteriormente il reset del motore velocità per interruzioni lunghe (non per i blip isolati, quello è già risolto) DEVE prima costruire un test sintetico che simuli esplicitamente questo identico scenario (gap lungo + spostamento reale nel frattempo) e dimostrare che l'algoritmo scelto non produce un'innovazione/velocità implausibile su quel fix — non basta l'argomento teorico "il Kalman si autocorregge", va verificato empiricamente, con lo stesso rigore sintetico-poi-reale di tutto il resto di questo documento.
- **Perché si adatta:** è esattamente il tipo di lavoro già previsto da G3 (RAIM)/G6 (Huber)/G9 (DOP) — rigettare o scontare un'innovazione statisticamente implausibile invece di fidarsene ciecamente solo perché il Kalman gain lo permette.
- **Conflitti/ridondanze:** stessa famiglia di G3/G6/G7/G9 (gestione delle misure anomale) — va considerato insieme a quelle voci nel design della Fase 1, non come un item isolato.
- **Zona/stadio:** GPS, Fase 1, vincolo di accettazione permanente da rispettare ogni volta che si tocca la logica di reset di un motore basato su Kalman.

## Livello di decisione del pace (cue stability) — nuovo filone, 2026-09-13/14

A differenza di tutto il resto di questo documento (che riguarda quanto è ACCURATA la velocità misurata), queste tre voci riguardano un problema diverso, a valle: quanto è STABILE la decisione presa a partire da una velocità già accurata. Nato dalla prima corsa reale con Kalman come motore di produzione (non più solo diagnostico) — la corsa ha confermato che il segnale è buono, ma ha scoperto un problema reale nella logica che lo trasforma in cue vocali. Tre documenti completi, ciascuno validato da un agente indipendente e antagonista istruito a cercare errori, non a confermare (uno dei due cicli di review ha trovato citazioni fabbricate in entrambi i documenti di ricerca, poi corrette nel ragionamento anche se non nei file stessi — i file restano come sono stati scritti, le correzioni vivono in questa voce e nella conversazione):

- `docs/superpowers/specs/2026-09-13-kalman-production-run1-validation.md` — analisi della corsa reale, causa radice del flapping.
- `docs/superpowers/specs/2026-09-13-precision-and-cue-stability-research.md` — prima ricerca (margine di precisione residuo sulla velocità: nessuno, G4 resta il vero tetto; isteresi vs. smoothing per i cue).
- `docs/superpowers/specs/2026-09-13-phase-aware-cue-smoothing-research.md` — seconda ricerca, mirata al problema dei cambi fase con una finestra fissa.

Ordine di esecuzione deciso (vedi P1/P2/P3/P4 nella tabella sopra): logging (P1) prima di tutto, **poi P4 subito dopo** (limite reale di `RoutineEngine` trovato durante la review del design di P1 — non rimandato a tempo indeterminato, va fatto prima delle corse di validazione così i log di quelle corse sono già completi), poi 1-2 corse reali con logging e P4 attivi per avere parametri misurati non indovinati, solo allora l'implementazione di P2. P3 è indipendente e non aspetta gli altri. Non blocca né è bloccato dal lavoro in corso sulle animazioni (branch/codice separati).

### G10. Map-matching (aggancio a strade/sentieri noti)

- **Algoritmo:** corregge la posizione agganciandola al percorso stradale/sentiero più plausibile conosciuto (come fa Strava con i segmenti).
- **Perché lo vogliamo:** utile soprattutto in area urbana con strade note.
- **Perché si adatta:** richiede dati di mappa e probabilmente una nuova schermata/feature UI (mappa, dati percorso) — non solo un algoritmo.
- **Downside onesto:** **per le corse dell'utente stesso (aperta campagna, spesso senza sentieri mappati), il beneficio è strutturalmente limitato** — stessa nota di onestà già fatta per il gate GPS: utile per altri utenti Play Store in città, meno per lui.
- **Conflitti/ridondanze:** nessuno tecnico, ma dipende da feature UI non ancora decise.
- **Zona/stadio:** GPS, bassa priorità, legato a sviluppi UI futuri.

### G11. RTK di rete (stazioni base + abbonamento)

- **Algoritmo:** correzioni differenziali da una rete di stazioni base fisse, per precisione centimetrica in tempo reale.
- **Perché NON lo vogliamo:** infrastruttura enterprise/survey con costi di abbonamento e copertura di rete — non ha senso per un'app fitness da telefono. **Da non confondere con G4** (RTK/INS "su dispositivo" da dati grezzi, che non richiede una rete esterna a pagamento).
- **Zona/stadio:** escluso esplicitamente.

---

## Zona Inerziale (accelerometro + giroscopio)

Componente nuovo: sub-progetto 1, branch `feature/imu-speed-fusion`, Approccio B (stimatore inerziale separato + arbitro a fiducia dinamica, `SpeedEstimator` intoccato — confermato valido anche con lo scope allargato di questo documento).

### I1. Passi + cadenza + lunghezza del passo calibrata dal GPS

- **Algoritmo:** conteggio passi (sensore `TYPE_STEP_DETECTOR` di Android, non serve costruire un rilevatore da zero), calibrazione dinamica di "metri per passo" nei momenti in cui il GPS è affidabile.
- **Perché lo vogliamo:** il cuore del sub-progetto 1, fornisce un segnale di velocità indipendente dal GPS.
- **Perché si adatta:** architettura Approccio B, confidenza dinamica basata sulla regolarità del segnale.
- **Downside:** il rumore dell'accelerometro-in-tasca (o in fascia) non è caratterizzato con dati reali come quello GPS — richiede raccolta dati dedicata prima di poter scrivere test sintetici altrettanto affidabili (già in `docs/superpowers/specs/2026-09-10-speed-accuracy-design.md` sezione 9/10.5).
- **Conflitti/ridondanze:** nessuno.
- **Zona/stadio:** Inerziale, priorità 1 (dopo la zona GPS).

### I2. Modelli biomeccanici per la lunghezza del passo (Weinberg, Kim, Ladetto, TCSLE)

- **Algoritmo:** invece di calibrare la lunghezza del passo solo da un rapporto costante col GPS, usare l'ampiezza del movimento verticale/orizzontale del passo stesso (già disponibile dall'accelerometro) per stimarla in modo più sofisticato. **Weinberg**: usa max/min dell'accelerazione perpendicolare. **Kim**: variante con normalizzazione diversa. **Ladetto**: combina più feature del segnale. **TCSLE (vincolo a tre passi)**: tecnica più recente trovata in letteratura oggi, migliora Weinberg/Kim/Ladetto gestendo meglio l'andatura dinamica (non perfettamente regolare) — **confermato da letteratura 2026 che supera i tre modelli classici di 2-5 metri di errore medio nei test comparativi.**
- **Perché lo vogliamo:** utile specialmente nel "cold start" di ogni sessione, prima che la calibrazione GPS abbia accumulato abbastanza dati.
- **Perché si adatta:** alimenta la stima di velocità-da-passi di I1, non cambia l'architettura di fusione.
- **Downside:** complessità di implementazione crescente da Weinberg (più semplice) a TCSLE (più sofisticato) — da scegliere in base a quanto guadagno reale misuriamo, non implementare il più complesso a priori.
- **Conflitti/ridondanze:** nessuno, sono alternative tra loro (si sceglie UN modello, non tutti e quattro insieme) — **questa sì è una scelta mutualmente esclusiva vera**, a differenza delle tecniche GPS che si sommano.
- **Zona/stadio:** Inerziale, da scegliere il modello specifico nel design dettagliato.

### I3. Extended Kalman Filter (EKF) per la non-linearità di direzione

- **Algoritmo:** quando si combina cadenza (scalare) con direzione dal giroscopio (angolo), la matematica diventa non lineare (seno/coseno) — un Kalman lineare semplice non è lo strumento corretto per uno stato che include direttamente un angolo. L'EKF gestisce questo linearizzando localmente ad ogni passo.
- **Perché lo vogliamo:** correttezza matematica per questo caso specifico.
- **Perché si adatta:** **l'Approccio B ci permette di evitarlo quasi del tutto** — calcolando la velocità-da-passi già come numero prima di darla all'arbitro, il problema della non-linearità resta "interno" a un piccolo pezzo di codice, non si propaga a tutta l'architettura.
- **Downside:** se comunque necessario in qualche punto, l'EKF è più delicato da validare di un Kalman lineare (linearizzazione locale, può divergere se lo stato iniziale è molto sbagliato).
- **Conflitti/ridondanze:** nessuno.
- **Zona/stadio:** Inerziale, dettaglio implementativo del design I1.

### I4. Stima del bias dei sensori inerziali

- **Algoritmo:** i MEMS economici (accelerometro/giroscopio del telefono) derivano leggermente nel tempo (errore sistematico crescente). Le navigazioni inerziali professionali stimano il bias come parte dello stato del filtro invece di ignorarlo — confermato anche nella letteratura RTK/INS trovata oggi ("lo stato include posizione, velocità, orientamento, E i bias").
- **Perché lo vogliamo:** rilevante ora che il giroscopio entra sul serio in gioco per periodi prolungati (non solo pochi secondi).
- **Perché si adatta:** estensione naturale dello stato del filtro esistente, stessa famiglia matematica.
- **Downside:** stato del filtro più grande, più lento a convergere all'inizio di ogni sessione.
- **Conflitti/ridondanze:** nessuno.
- **Zona/stadio:** Inerziale, da valutare nel design dettagliato.

### I5. Classificazione della modalità di trasporto (tasca vs fascia vs mano)

- **Algoritmo:** il segnale accelerometro ha caratteristiche molto diverse a seconda di dove sta il telefono (oscillazione del bacino in tasca vs oscillazione del braccio con la fascia). Rilevare automaticamente quale modalità è in uso (dalla frequenza/asse dominante del segnale) e adattare di conseguenza il modello di passo.
- **Perché lo vogliamo:** **rilevante concretamente per l'utente** — oggi porta il telefono in tasca, presto comprerà una fascia da braccio. Un modello che si adatta automaticamente evita di dover ricalibrare manualmente o peggio dare risultati scadenti quando cambia modalità.
- **Perché si adatta:** raffina l'input a I1/I2, non cambia l'architettura di fusione.
- **Downside:** aggiunge un ulteriore livello di classificazione/incertezza da validare.
- **Conflitti/ridondanze:** nessuno.
- **Zona/stadio:** Inerziale, nuova voce emersa oggi, rilevante dato il contesto fisico dell'utente.

### I6. Zero-velocity update (ZUPT)

- **Algoritmo:** ad ogni appoggio del piede a terra, la velocità in quell'istante è certamente zero — si usa questo per azzerare periodicamente la deriva dell'errore.
- **Perché NON è applicabile ora:** richiede un sensore montato sul piede (footpod), non nella tasca/fascia.
- **Zona/stadio:** Inerziale, rilevante solo insieme a un futuro footpod (vedi zona hardware esterno).

---

## Zona chip ausiliari

### A1. Fusione con magnetometro per la direzione (AHRS — Madgwick/Mahony)

- **Algoritmo:** il giroscopio da solo "deriva" nel tempo quando integrato (l'errore si accumula); il magnetometro (bussola) dà un riferimento assoluto per correggerla periodicamente. Tecnica standard in robotica/droni, non esotica.
- **Perché lo vogliamo:** rafforza direttamente il giroscopio del sub-progetto 1, zero hardware nuovo (il chip è già nel telefono).
- **Perché si adatta:** si affianca al giroscopio nella stessa zona inerziale.
- **Downside:** i magnetometri dei telefoni sono sensibili a interferenze magnetiche locali (cinture con fibbie metalliche, altri dispositivi vicini) — serve gestione robusta delle interferenze.
- **Conflitti/ridondanze:** nessuno.
- **Zona/stadio:** Chip ausiliari, priorità alta, da includere nel design del sub-progetto 1.

### A2. Altimetro barometrico + passo corretto in pendenza ("graded pace")

- **Algoritmo:** usa la quota (dal barometro, quasi certamente presente sul Pixel 9a) per separare "sei più lento per la salita" da "hai davvero rallentato" — tecnica usata da Coros/Garmin.
- **Perché lo vogliamo:** priorità dichiarata alta dall'utente. Non tocca la fusione della velocità (calcolo a valle), quindi indipendente architetturalmente da tutto il resto.
- **Downside:** richiede anche una decisione di interfaccia (dove/come mostrare il dato) — non solo un calcolo interno.
- **Conflitti/ridondanze:** nessuno.
- **Zona/stadio:** Chip ausiliari, suo brainstorming separato (come già deciso), dopo il sub-progetto 1.

---

## Zona hardware esterno (futuro)

### E1. Footpod Bluetooth (Stryd, Coros Pod, Garmin Running Dynamics Pod)

Gold standard di settore per cadenza/passo. Dipende da hardware esterno posseduto dall'utente finale — obiettivo dichiarato per il futuro "staple per l'attività fisica", non pianificato nel dettaglio ora. Abiliterebbe anche I6 (ZUPT).

## Escluse esplicitamente (non per pigrizia — motivate)

- **ML/reti neurali** (qualunque forma) — esclusione esplicita richiesta dall'utente.
- **RTK di rete/abbonamento** (G11) — infrastruttura enterprise fuori scope, non confondere con G4.
- **Radar/telecamera per la velocità** — impraticabile per uso mobile in corsa.

---

## Riepilogo conflitti/ridondanze/scelte mutualmente esclusive

| Voci coinvolte | Tipo di relazione | Nota |
|---|---|---|
| G3 (RAIM), G6 (Huber), G7 (gate già costruito) | **Ridondanza di scopo, non conflitto tecnico** | Risolvono la stessa classe di problema (misure anomale) con formalismi diversi — da confrontare e sceglierne l'insieme minimo che serve davvero, non sommarle tutte per principio |
| G1 (doppia frequenza), G3 (RAIM) vs G4 (bypass GNSS grezzo) | **Investimento a perdere se G4 si realizza** | G4, se fatto, incorpora entrambe in modo più potente — non un conflitto immediato, ma va saputo prima di investire tempo su G1/G3 pensando siano permanenti |
| G5 (IMM) | **Nessun conflitto tecnico, ma costo di progetto reale** | Tocca codice già validato (`SpeedEstimator`), richiede ri-validazione completa — non è una tensione tra algoritmi, è una tensione tra ambizione e rischio di regressione |
| G8 (smoothing a ritardo fisso) | **Tensione diretta con la filosofia di reattività del progetto** | Unica voce con un costo UX diretto (ritardo percepibile nei cue), da validare esplicitamente con l'utente, non solo tecnicamente |
| I2 (Weinberg/Kim/Ladetto/TCSLE) | **Mutualmente esclusive tra loro** | A differenza di quasi tutto il resto del documento, qui si sceglie UN modello, non li si somma — sono alternative dello stesso calcolo, non tecniche complementari |
| Tutto il resto (EKF, IMM, RTS smoothing, Huber, magnetometro, bias estimation) | **Composabili, stessa famiglia (Kalman)** | Nessun conflitto tecnico reale — la vera domanda è quanti impilare insieme in un colpo solo prima di validare, non se sono compatibili |
