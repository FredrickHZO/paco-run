# Roadmap di precisione — documento di riferimento completo

**Data:** 2026-09-11. **Branch di origine:** `feature/paco-run-m3e`. **Scopo:** catalogo esaustivo di ogni tecnica nota (esclusi ML/reti neurali su richiesta esplicita dell'utente) per massimizzare la precisione dei readings di Paco Run — organizzato per zona (quale sensore/sottosistema), con per ciascuna: algoritmo, perché la vogliamo, perché si adatta alla nostra architettura, downside, conflitti/ridondanze con altre voci.

**Ambizione dichiarata dall'utente (2026-09-11):** Paco Run deve diventare più preciso della concorrenza di fascia alta (Garmin, Coros), e il punto di riferimento per chiunque monitori la velocità durante attività fisica — non solo corsa/camminata (rebrand futuro, fuori scope ora). Nessun compromesso accettato tra le opzioni: si vogliono TUTTE, sequenziate per stadio, testate una alla volta, ognuna nel suo branch.

## Stato delle decisioni (aggiornato 2026-09-11, sessione di conferma) — sezione di riferimento autoritativa

Questa sezione è la fonte di verità sullo stato di OGNI voce. Se una sezione più sotto nel documento sembra in contraddizione con questa tabella, **questa tabella vince** (è la più recente).

| Voce | Stato | Note |
|---|---|---|
| Refactor `MotionEngine` + 2 fix minori (doppia scrittura preferenze legacy, trick conversione `RoutineEngine.kt`) | **Confermato, in corso** | Branch `feature/motion-engine-refactor`, priorità 1 assoluta, blocca tutto il resto |
| G1 — doppia frequenza | Confermato, da verificare tecnicamente | Nessuna decisione di design, solo verifica |
| G2 — multi-costellazione | **Già risolto, zero azione, per sempre** | Non è un task, gestito automaticamente da Android |
| G3 — RAIM | **Scartata deliberatamente** | Superflua data la conferma di G6 (Huber), che ne è una generalizzazione |
| G4 — RTK/INS ("Motore B") | Confermato come obiettivo finale | Ramo dedicato futuro, dopo Fase 2/3, **dopo G5** (vedi sotto) |
| G5 — IMM sul meccanismo GPS esistente | **Rimandato, ma con posizione precisa: da fare PRIMA di G4 (RTK/INS), dopo la zona inerziale** | Non è "futuro generico" — l'utente vuole farlo prima di RTK/INS. Tocca `SpeedEstimator` già validato, richiede ri-validazione completa quando si arriva a farlo |
| G6 — filtri Huber | **Confermato** | Scelta definitiva tra G3/G6/G7 per il rigetto delle misure anomale, da validare empiricamente contro G7 nel piano di implementazione |
| G7 — gate anti-falso-allarme (già costruito) | Resta come riferimento di confronto | Non un task nuovo, è il baseline per validare G6 |
| G8 — smoothing a ritardo fisso | 🔴 **ANCORA BLOCCATO — decisione dell'utente in sospeso** | Trade-off ritardo-nei-cue/precisione, non ancora deciso |
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

**Unica decisione ancora aperta che blocca la pianificazione dettagliata della Fase 1 (zona GPS): G8.**

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

### G9. Consapevolezza della geometria satellitare (DOP — Dilution of Precision)

- **Algoritmo:** un valore che descrive quanto è "buona" la geometria dei satelliti visibili in un dato istante, indipendentemente dal rumore di ogni singola misura — satelliti tutti ammassati in una parte di cielo danno un DOP cattivo anche se il segnale di ognuno è pulito. Accessibile su Android tramite l'API `GnssStatus`, senza bisogno dell'accesso completo ai dati grezzi di G4.
- **Perché lo vogliamo:** un segnale di qualità aggiuntivo, più ricco della sola `horizontalAccuracyMeters` già usata, ottenibile con uno sforzo molto minore di G4.
- **Perché si adatta:** potrebbe alimentare lo stesso meccanismo di fiducia già usato per pesare i fix (accanto o al posto di `horizontalAccuracyMeters`).
- **Downside:** non tutti i dispositivi espongono `GnssStatus` in modo completo/affidabile; beneficio da validare empiricamente prima di contarci.
- **Conflitti/ridondanze:** nessuno diretto, ma si sovrappone parzialmente con l'informazione già contenuta in `horizontalAccuracyMeters` (Android calcola quel valore tenendo già conto, in parte, della geometria). Da verificare quanta informazione aggiuntiva porti davvero prima di implementarlo.
- **Zona/stadio:** GPS, nuova voce emersa oggi, priorità da definire.

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
