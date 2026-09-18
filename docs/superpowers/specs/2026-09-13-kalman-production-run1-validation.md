# Validazione corsa reale #1 — SpeedEstimator in produzione

**Branch:** `feature/paco-run-m3e` (questo documento) — build testata da `feature/speed-production-wiring`, commit `a2ec63b`. **Data:** 2026-09-13. **Stato:** prima corsa reale con il nuovo motore di velocità Kalman effettivamente collegato ai cue vocali dal vivo (non più solo diagnostico). Run #1 di 5 nel checkpoint di validazione concordato prima di decidere se rimuovere il vecchio motore Doppler come rete di sicurezza.

## Contesto (per chi legge questo documento senza aver seguito il progetto)

**Paco Run** è un'app Android per corsa/camminata, ancora in sviluppo, che dà all'utente feedback vocale in tempo reale sul proprio ritmo ("Too slow" / "Good pace" / "Too fast") confrontando la velocità istantanea misurata dal GPS con un intervallo target configurato (es. "tieni un ritmo tra 7.5 e 8.2 km/h"). Il cuore di questo feedback è `PaceEvaluator`, che riceve un campione di velocità ad ogni fix GPS e decide in quale dei tre stati ci si trova.

Fino a ieri, quella velocità istantanea veniva da `location.speed`, il valore Doppler grezzo fornito da Android — un'indagine precedente (`docs/superpowers/specs/2026-09-10-speed-accuracy-design.md`) ha dimostrato che questo valore è sistematicamente "compresso" verso la media (sottostima gli scatti, sovrastima i tratti lenti) e ha portato alla costruzione di `SpeedEstimator`: un filtro di Kalman a due assi (est/nord) che stima la velocità direttamente dalla sequenza di posizioni GPS, senza usare il Doppler. `SpeedEstimator` è stato validato due volte su corse reali in modalità **solo diagnostica** (il suo output veniva scritto nel file GPX esportato, ma non influenzava nessun cue reale), con una riduzione d'errore del 52-59% rispetto a `location.speed` contro Strava come riferimento.

**Cosa è cambiato ieri/oggi:** un refactor (`feature/speed-production-wiring`, documentato in `docs/superpowers/specs/2026-09-13-speed-production-wiring-design.md`) ha introdotto un'interfaccia `SessionSpeedEngine` con due implementazioni intercambiabili — `DopplerSpeedEngine` (il comportamento vecchio) e `KalmanSpeedEngine` (adattatore su `SpeedEstimator`) — e ha reso **`KalmanSpeedEngine` il motore di default**. Da questa corsa in poi, `SpeedEstimator` non è più solo un calcolo parallelo: è la fonte reale di `PaceEvaluator` (i cue vocali), di `RunSessionState.speedKmh` (il numero a schermo) e dell'input al motore delle routine. La build usata oggi è al commit `a2ec63b`, che include anche un fix dello stesso giorno (Task 10 del piano di implementazione) per un bug per cui il motore si resettava troppo facilmente su singoli fix isolati inaffidabili — **quel bug specifico è già corretto in questa build e va escluso come sospetto** per qualunque comportamento anomalo osservato oggi.

Questa è la **prima corsa reale** con il nuovo motore attivo dal vivo, corsa #1 di un checkpoint di 5 corse concordato prima di decidere se rimuovere `DopplerSpeedEngine` come rete di sicurezza (sezione 7 del documento di design citato sopra). Questo documento esiste per registrare cosa è successo in questa prima corsa, con tre sintomi riportati dall'utente, e per permettere a chiunque (anche senza contesto pregresso) di valutare se il comportamento osservato è un problema del nuovo motore, un problema di configurazione/UX, o entrambi.

## Metodologia

- **File analizzati:** `paco-run-2026-09-13-1655.gpx` (export di Paco Run, 5191 punti, contiene per ogni fix GPS i campi grezzi più le estensioni `pr:speedEstimatorKmh`, `pr:horizontalAccuracyMeters`, `pr:speedAccuracyMps`, `pr:reliable`) e `Corsa_pomeridiana-13-OGGI.fit` (export binario di Strava dello stesso telefono, stessa corsa, 5218 fix con posizione).
- **Stesso telefono, stesso GPS — non è una verità indipendente.** Come già notato nei documenti precedenti, Strava e Paco Run leggono lo stesso chip GPS dello stesso dispositivo: un confronto tra i due non dimostra quale dei due sia "vero", dimostra solo se sono d'accordo o no. È comunque il metodo di confronto consolidato di questo progetto, in assenza di un riferimento indipendente (un campo da corsa certificato, un ricevitore geodetico).
- **Confronto causale, non centrato.** Ogni serie di velocità "Strava" usata qui è una finestra scorrevole **trailing** (solo campioni passati rispetto a ogni istante, mai futuri) sulla posizione grezza di Strava — esattamente il tipo di calcolo che l'app dal vivo potrebbe fare anch'essa, mai un vantaggio di informazione che Paco Run non avrebbe. Finestra principale: 10 secondi (standard di questo progetto).
- **Nessuna libreria FIT disponibile nell'ambiente** (niente `fitparse`, niente `gpsbabel`, nessun accesso a `pip`) — scritto un mini-parser FIT da zero per i soli messaggi "record" (global mesg 20), gestendo sia header normali sia header a timestamp compresso e campi developer (il file di Strava usa entrambi). GPX parsato con `xml.etree.ElementTree` della libreria standard. Script in `/tmp/claude-1000/.../scratchpad/` di questa sessione — non nel repository, da riscrivere se servirà di nuovo (prassi già stabilita in questo progetto).
- **Range di velocità configurati per questa corsa — dati forniti direttamente dall'utente**, non assunti di default: routine "Cammina-Corri" con una fase iniziale di 2 km a ritmo costante (`CONSTANT_BEFORE` in `RoutineEngine.kt`) con range camminata **4.5–6.5 km/h**, poi alternanza corsa/camminata con run **1 minuto** a **7.5–8.2 km/h** e walk **2 minuti** a **4.5–6.5 km/h** (`runSeconds=60`, `walkSeconds=120`, `walkUsesConstantRange=true` di default, quindi la camminata usa lo stesso range della fase costante iniziale). Questi sono gli unici dati di configurazione confermati dall'utente; qualunque altro dettaglio (es. se `routineKm`/`routineUnlimited` hanno fatto terminare la routine prima della fine della corsa) non è noto e viene segnalato esplicitamente dove rilevante.

## Limite metodologico importante: nessuna decisione reale è stata registrata — tutto qui è ricostruzione

**Questo è il limite più importante di tutto il documento, va tenuto presente leggendo ogni numero più sotto.** Paco Run, ad oggi, non scrive da nessuna parte un log delle proprie decisioni in tempo reale: non esiste un log dei cue effettivamente emessi (con timestamp/km/velocità), non esiste un log dei cambi di fase della routine, non esiste un log per-tick dello stato di `PaceEvaluator`. Tutto quello che questo documento ricostruisce — **quando** sono iniziate/finite le fasi RUN/WALK, **quando** lo stato di ritmo sarebbe passato da TOO_SLOW a TOO_FAST, **quali** cue sarebbero stati emessi — è un'**inferenza** costruita a posteriori da:

1. la traccia grezza di `pr:speedEstimatorKmh` nel GPX (questo sì è un dato osservato, non inferito: è il valore che il motore Kalman ha realmente calcolato per ogni fix affidabile);
2. i parametri di configurazione forniti dall'utente (sezione Metodologia sopra);
3. una **ricostruzione in Python della logica di `RoutineEngine.kt` e `PaceEvaluator.kt`**, che riproduce fedelmente l'algoritmo letto dal codice sorgente (soglie del range attivo, `confirmationSamples=2`, il reset di `confirmedPace`/`candidatePace` ad ogni cambio di fase) ma **applicata offline, su un singolo punto GPX per tick** — non è una riesecuzione del codice reale, è una sua riscrittura indipendente in Python basata sulla lettura del codice.

Questa ricostruzione **non è lo stesso genere di evidenza del dato grezzo `speedEstimatorKmh`**: è plausibile e coerente con il codice letto, ma potrebbe divergere dal comportamento reale dell'app in punti che questa analisi non può vedere (es. la finestra di grazia `timerRetainsFreshGps`, l'esatta cadenza dei tick del timer, o se `routineKm`/`routineUnlimited` hanno interrotto la routine prima della fine della corsa). **Ogni numero di questo documento che riguarda "quando" o "quanto spesso" un cue/stato/fase sarebbe cambiato va letto come una stima con incertezza, non come un fatto osservato.** La correzione di questo gap strutturale (un log reale delle decisioni) è un lavoro separato, non qualcosa che questo documento può sostituire.

---

## Sintomo 1 — oscillazione del pace (TOO_FAST↔TOO_SLOW senza passare da GOOD)

**Riportato dall'utente come il più oggettivo e prioritario:** il pace saltava spesso direttamente da "Too fast" a "Too slow" (o viceversa) senza passare da "Good pace", che in questa sessione sarebbe stato un'occorrenza rarissima.

### Cosa mostra la ricostruzione

Simulando lo stato confermato di `PaceEvaluator` sull'intera corsa (5191 punti, `confirmationSamples=2`, reset ad ogni cambio di fase ricostruito — vedi limite metodologico sopra):

| | Valore |
|---|---|
| Transizioni di stato confermato totali | 347 |
| Transizioni **dirette** TOO_SLOW↔TOO_FAST (senza passare da GOOD) | **25 (7.2% delle transizioni totali)** |
| ...di cui avvenute a centro-fase (non a un cambio RUN/WALK) | **19 (76%)** — quindi la maggioranza non è un artefatto del cambio di range |
| ...di cui avvenute entro ±3s da un cambio di fase RUN/WALK | 6 (24%) — qui il salto è in parte spiegabile dal fatto che il range cambia bruscamente mentre il passo del corridore non può cambiare altrettanto in fretta |

**Il dato più chiaro, indipendente da qualunque soglia esatta** (controllo "model-free" richiesto esplicitamente: quanto tempo il segnale passa vicino/dentro una banda di buon ritmo plausibile, vs quanto abbruscamente salta oltre entrambi i bordi): sui soli campioni affidabili, classificati rispetto al range attivo ricostruito in quell'istante,

- **50.5%** del tempo la velocità era **dentro** il range attivo,
- 37.9% **sopra** il massimo,
- 11.7% **sotto** il minimo.

Questo numero aggregato, però, nasconde una differenza enorme tra le fasi:

| Fase (range) | Tempo TOO_SLOW | Tempo GOOD | Tempo TOO_FAST | GOOD sul totale fase |
|---|---|---|---|---|
| WALK (4.5–6.5 km/h, banda larga 2.0) | 23.5s | 1575.6s | 998.0s | **61.1%** |
| CONSTANT_BEFORE (4.5–6.5 km/h) | 31.1s | 763.3s | 355.8s | **62.0%** |
| **RUN (7.5–8.2 km/h, banda larga solo 0.7)** | 549.3s | **209.8s** | 545.7s | **16.1%** |

**Durante le fasi RUN, GOOD occupa solo il 16% del tempo**, contro il 61-62% durante camminata/riscaldamento. La durata mediana di un episodio di GOOD confermato durante RUN è di soli **3.0 secondi** (contro 14.5s in WALK e 11.5s in CONSTANT_BEFORE) — un tempo quasi al limite dei 2 campioni (`confirmationSamples=2`, circa 2 secondi a 1Hz) necessari solo per *confermare* di essere entrati in GOOD. Questo spiega perché l'impressione di "GOOD quasi mai" sia particolarmente plausibile se l'attenzione dell'utente era concentrata sui tratti di corsa (dove il feedback di ritmo conta di più) anche se, sull'intera corsa aggregata, GOOD occupa circa metà del tempo confermato totale.

**Esempio concreto, dentro una singola fase RUN (15:23:36–15:24:36, range 7.5–8.2 km/h), nessun cambio di fase nel mezzo:**

```
15:23:38  speed=5.93 km/h  -> TOO_SLOW (appena entrato in RUN, il passo è ancora da camminata)
15:24:00  speed=7.86 km/h  -> TOO_SLOW (ancora, sta accelerando)
15:24:01  speed=8.21 km/h  -> TOO_SLOW (ultimo campione prima della conferma)
15:24:02  speed=8.42 km/h  -> TOO_FAST (confermato)
15:24:10  speed=9.38 km/h  -> TOO_FAST (picco)
15:24:19  speed=8.13 km/h  -> GOOD (confermato, dura solo ~6s)
15:24:25  speed=7.19 km/h  -> TOO_SLOW (confermato)
15:24:29  speed=8.23 km/h  -> TOO_SLOW (ultimo campione, poi...)
15:24:30  speed=9.09 km/h  -> TOO_FAST (confermato, salto diretto)
```

In meno di 70 secondi, tutti dentro la stessa fase RUN, la velocità stimata ha oscillato tra un picco di **9.38 km/h** e un minimo di **7.19 km/h** attorno a un target largo solo 0.7 km/h (7.5–8.2) — un'oscillazione di quasi 2.2 km/h picco-picco contro una banda "buona" che ne misura 0.7. **Con un target così stretto rispetto alla variabilità naturale del passo di un runner (per non parlare del rumore residuo del GPS), è quasi impossibile restare "dentro" per più di pochi secondi di fila — questo è un vincolo geometrico della configurazione, non necessariamente solo rumore del filtro.**

### Rumore del segnale Kalman vs Strava — il filtro è "più nervoso" del previsto?

Confronto diretto, punto per punto, tra la variazione campione-a-campione di `speedEstimatorKmh` e quella della finestra scorrevole Strava a 10s, sugli stessi 5173 istanti (tolleranza di accoppiamento 2s):

| Serie | Media \|Δv\|/s (km/h al secondo) | Mediana | Massimo |
|---|---|---|---|
| Paco `speedEstimatorKmh` | 0.164 | 0.122 | 1.776 |
| Strava trailing-10s | 0.184 | 0.128 | 1.227 |

**Il segnale Kalman di Paco Run non è più rumoroso di Strava — è leggermente meno, in media.** Il valore massimo di Paco (1.78 km/h/s) è più alto di quello di Strava (1.23 km/h/s), ma un singolo massimo isolato su oltre 5000 campioni non indica un problema sistematico di rumore. **Conclusione su questo punto specifico: l'oscillazione osservata in Sintomo 1 non è spiegata da un rumore/jitter anomalo del filtro Kalman rispetto al suo riferimento indipendente** — il segnale è, se mai, leggermente più pulito di Strava nello stesso momento. Il problema è altrove: il target RUN (7.5–8.2 km/h) è semplicemente troppo stretto rispetto alla variabilità reale del ritmo di corsa, qualunque sia la qualità del sensore che la misura.

### Un secondo meccanismo distinto, trovato nel codice: il cue "Good pace" può restare silenzioso dopo un cambio di fase

Leggendo `PaceEvaluator.kt`, il cue `GOOD_PACE` viene emesso solo se lo stato precedente (`previous`) era `TOO_SLOW` o `TOO_FAST`:

```kotlin
PaceState.GOOD -> if (previous == PaceState.TOO_SLOW || previous == PaceState.TOO_FAST) {
    Cue.GOOD_PACE
} else {
    null
}
```

Ma ad ogni cambio di fase della routine, `RunSessionStore.kt` chiama `paceEvaluator.updateConfig(...)`, che azzera `confirmedPace` a `null`. Quando subito dopo un cambio di fase lo stato si confermasse GOOD per la prima volta, `previous` sarebbe `null` — non `TOO_SLOW`/`TOO_FAST` — quindi **nessun cue verrebbe emesso**, anche se oggettivamente si è appena raggiunto un buon ritmo. Sulla ricostruzione di oggi questo è accaduto solo **2 volte su 161 ingressi in GOOD** (alle 15:14:37 e 16:17:39, entrambe subito dopo un reset di fase) — un effetto reale e riproducibile dal codice, ma quantitativamente marginale su questa corsa, non la causa principale della sensazione di "GOOD raro". Lo riportiamo perché è un bug concreto, a costo zero da confermare, non perché spieghi la maggioranza del fenomeno.

**In sintesi sul Sintomo 1:** l'oscillazione denunciata dall'utente è reale e misurabile nei dati grezzi (25 salti diretti confermati, concentrati soprattutto a centro-fase durante le fasi RUN, con un esempio concreto di quasi 2.2 km/h di oscillazione picco-picco contro un target di 0.7 km/h), e **non è spiegabile con un rumore anomalo del filtro Kalman** (che anzi oscilla meno di Strava nello stesso momento). La causa più plausibile, per come la banda RUN è configurata oggi, è geometrica: un target di 0.7 km/h è stretto rispetto alla variabilità naturale del ritmo di corsa a braccio libero, qualunque sia la precisione del sensore sottostante.

---

## Sintomo 2 — overspeed percepito ("quasi sempre in overspeed")

**Riportato dall'utente con una riserva esplicita**: potrebbe essere una sua percezione soggettiva, calibrata su un'app meno precisa usata in passato.

### I numeri sono sorprendentemente univoci

Sulla stessa ricostruzione degli ingressi in stato confermato:

| Stato | Episodi confermati | Tempo totale |
|---|---|---|
| TOO_FAST | **161** | **1768s (29.5 min)** |
| TOO_SLOW | **40** | **565s (9.4 min)** |
| GOOD | 161 | 2393s (39.9 min) |

**TOO_FAST è 4 volte più frequente di TOO_SLOW come episodio, e occupa oltre 3 volte più tempo totale.** Questa asimmetria non è concentrata solo nelle fasi RUN (dove ci si aspetterebbe un margine più facile da superare per eccesso se il runner spinge): è presente **anche durante WALK e CONSTANT_BEFORE** (998s e 355.8s sopra il massimo contro appena 23.5s e 31.1s sotto il minimo) — cioè anche durante le fasi di camminata, l'utente ha passato pochissimo tempo sotto il minimo del range e una parte sostanziale sopra il massimo. **La sensazione soggettiva dell'utente di essere "quasi sempre in overspeed" trova qui un riscontro numerico concreto, su tutta la corsa, non solo nei tratti di corsa.**

### Ma Paco Run e Strava sono quasi identici — quindi non è un errore di calcolo

La domanda chiave: la velocità letta da Paco era sistematicamente più alta di quella di Strava (un vero bias del filtro), o i due erano d'accordo e l'utente stava semplicemente correndo/camminando più veloce dei target configurati?

| | Media diff (Paco − Strava, km/h) | Mediana diff |
|---|---|---|
| Intera corsa (5173 campioni accoppiati) | **+0.032** | +0.035 |
| Ultimo km (463 campioni accoppiati) | **+0.064** | +0.030 |

**La differenza è praticamente zero** (un trentesimo di km/h, ben dentro il rumore di misura residuo tra due stime indipendenti dello stesso fenomeno). **Non c'è un bias sistematico di `speedEstimatorKmh` verso l'alto rispetto a Strava.** Questo porta a una conclusione importante: se l'utente percepiva "overspeed quasi costante", i dati suggeriscono che **rispecchia la velocità reale della corsa quel giorno rispetto ai target configurati**, non un errore di lettura di Paco Run — il numero che Paco mostrava era corretto (concorde con Strava); il problema, se c'è, riguarda la calibrazione dei target (forse troppo bassi per lo sforzo reale di quel giorno) o come l'app comunica lo stato, non il calcolo della velocità.

### Controllo di ancoraggio: l'ultimo km (corsa sostenuta, nessuna camminata)

L'utente ha indicato l'ultimo km come riferimento: corsa sostenuta senza camminare, quindi sia Strava sia Paco Run dovrebbero mostrare un ritmo veloce e coerente.

| | Paco `speedEstimatorKmh` | Strava trailing-10s |
|---|---|---|
| Media | 7.72 km/h | 7.63 km/h |
| Mediana | 8.11 km/h | 8.05 km/h |
| Dev. standard | 1.53 | 1.44 |
| Min – Max | 3.69 – 12.58 | 3.26 – 10.96 |

**Confermato: entrambe le fonti, indipendentemente, mostrano un ritmo sostenuto e coerente nell'ultimo km** (mediana ~8 km/h su entrambe, differenza di mediana di 0.03 km/h), con un accordo quasi perfetto tra Paco e Strava. Il minimo di 3.69/3.26 km/h in coda alla serie è quasi certamente la fase di decelerazione finale a fine corsa (ultimi 1-2 campioni), non una camminata nel mezzo del km — coerente con quanto riportato dall'utente.

**In sintesi sul Sintomo 2:** il "sentirsi quasi sempre in overspeed" ha un riscontro oggettivo nei dati (TOO_FAST è 3-4 volte più frequente/lungo di TOO_SLOW su tutta la corsa), ma **non per un errore di calcolo di Paco Run** — Strava, indipendentemente, conferma gli stessi numeri con un accordo praticamente perfetto anche nell'unico tratto di ancoraggio fornito dall'utente. La causa è altrove: o i target configurati erano leggermente bassi rispetto allo sforzo reale di quel giorno, o — più probabilmente, combinato col Sintomo 1 — il modo in cui l'app comunica "troppo veloce" (un cue ripetuto ogni 5 secondi per tutta la durata dello stato, vedi `PaceEvaluator.repeatedCue()`, contro un singolo annuncio silenzioso quando si rientra in GOOD, che non si ripete mai) amplifica percettivamente ogni minuto passato sopra il target rispetto a ogni minuto passato dentro il range.

---

## Sintomo 3 — acquisizione GPS lenta/instabile e primo cue "BAD GPS"

**Riportato come priorità più bassa ma reale**: oggi il GPS ha impiegato più tempo ad agganciarsi ed è stato instabile nei primi ~100m, con la prima occorrenza in assoluto di un cue "BAD GPS".

### Confronto con le due corse precedenti validate

| Corsa | Ritardo al primo fix affidabile | Fix inaffidabili (intera corsa) | Max `horizontalAccuracyMeters` osservata |
|---|---|---|---|
| 2026-09-10 | 0.0s (già affidabile dal primo punto) | 1 / 5196 | 6.6m |
| 2026-09-12 | 0.0s (già affidabile dal primo punto) | 0 / 5222 | 7.9m |
| **2026-09-13 (oggi)** | **14.0s** | **18 / 5191** | **12.2m** |

**Oggettivamente diverso dalle due corse precedenti**, su tutte le tre metriche. Coerente con la nota di progetto secondo cui l'utente corre abitualmente in aperta campagna e non aveva mai incontrato prima una vera degradazione GPS — questa è la prima volta che succede in una corsa validata.

### Cosa è successo nei primi ~200m (114 secondi)

Su 111 punti GPX in questa finestra, **17 sono risultati inaffidabili** — **17 dei 18 fix inaffidabili dell'intera corsa di 85 minuti sono concentrati qui**, il restante singolo caso isolato è avvenuto molto più tardi (15:21:01, un blip isolato di `speedAccuracy` con posizione comunque buona — esattamente il tipo di evento singolo che il fix Task 10 del 2026-09-13 è stato scritto per non far resettare il motore in modo eccessivo).

**Il motivo dell'inaffidabilità è sempre lo stesso campo**, in tutti i 17 casi iniziali: `speedAccuracyMps` oltre la soglia di 0.35 m/s usata da `LocationSampleMapper.kt` (valori osservati: 0.35–0.44 m/s). **Non** è mai stato `horizontalAccuracyMeters` a superare la sua soglia (20m) — il valore peggiore osservato in questa finestra è stato 12.163m, ben sotto soglia. Questo è un punto di precisione importante: il cue "BAD GPS" di oggi è stato causato da un GPS con **velocità Doppler incerta** (il chip non è riuscito a misurare con sicurezza quanto ci si stava muovendo), non da una posizione scadente — i due meccanismi sono distinti nel codice (`LocationSampleMapper`, soglia assoluta su età/accuratezza/velocità) e non vanno confusi.

**Il primo fix affidabile è arrivato a 14:55:25** (14.0s dopo il primo punto GPX a 14:55:11). Da lì, `SpeedEstimator` si è inizializzato da zero (comportamento noto e accettato, G14 nella roadmap di precisione) e ha riconvergito verso un valore plausibile di camminata in circa 10-12 secondi:

```
14:55:25  speed_estimator=0.00 km/h  (primo fix affidabile, filtro appena inizializzato)
14:55:26  speed_estimator=0.61 km/h
14:55:28  speed_estimator=3.33 km/h
14:55:33  speed_estimator=6.17 km/h
14:55:37  speed_estimator=6.31 km/h  (stabile, coerente con la fase CONSTANT_BEFORE, range 4.5-6.5)
```

### Il gate anti-falso-allarme su crollo di accuratezza — primo vero test sul campo, ma inconcludente

Questo progetto nota esplicitamente (`docs/superpowers/specs/2026-09-11-precision-roadmap.md`, voce G7) che il gate anti-falso-allarme di `SpeedEstimator` (`accuracyDegradationRatio=2.0`, `accuracyBaselineAlpha=0.05` — forza il filtro in modalità "calma" quando l'accuratezza corrente supera 2× una baseline a memoria lunga) non era mai stato testato su una vera degradazione GPS reale. Oggi è la prima occasione. **Risultato: il gate non si è mai attivato (0 engagement su 5173 fix affidabili)**, riproducendo la sua formula esatta sulla sequenza reale di `horizontalAccuracyMeters`.

Questo è **notabile ma non conclusivo**, per un motivo preciso visibile nei dati: l'accuratezza dei fix affidabili, nei primi secondi, **decresce in modo smooth e monotono** (10.1m → 9.9m → 9.5m → ... → 5.2m), non con un salto improvviso. La letteratura citata nella roadmap di precisione nota esplicitamente che i meccanismi basati sull'innovazione/rapporto "possono confondere un degrado lento e progressivo con rumore di misura residuo" — esattamente il pattern osservato oggi. **Il gate non ha fallito un test che avrebbe dovuto superare: semplicemente oggi non si è mai presentata la condizione (un salto brusco di accuratezza) che è progettato per intercettare.** I fix peggiori di tutti (12.2m) sono quelli marcati inaffidabili da `LocationSampleMapper` e quindi non arrivano mai al gate — che vede solo la sequenza già filtrata dei fix "affidabili".

**In sintesi sul Sintomo 3:** la lentezza/instabilità di oggi è oggettivamente diversa dalle due corse precedenti validate (14s contro 0s di ritardo, 18 contro 0-1 fix inaffidabili, causati dalla sola `speedAccuracy` e mai da `horizontalAccuracy`). Il gate anti-falso-allarme ha avuto il suo primo vero confronto con GPS degradato reale ma senza mai attivarsi, perché il degrado di oggi è stato graduale e non a scatto — resta quindi ancora privo di una vera prova sul campo della sua efficacia (solo una prova che, in questa specifica condizione, non era necessario).

---

## Conclusioni

**Sintomo 1 (oscillazione, priorità massima):** confermato come reale e misurabile — 25 transizioni dirette TOO_SLOW↔TOO_FAST su 347 transizioni totali confermate, concentrate soprattutto a centro-fase (76%) e specificamente nelle fasi RUN, dove GOOD occupa solo il 16% del tempo con una durata mediana di appena 3 secondi contro una banda larga solo 0.7 km/h. **Non è causato da rumore anomalo del filtro Kalman** (che, confrontato punto-per-punto con Strava negli stessi istanti, oscilla leggermente meno, non più). La causa più probabile è la larghezza del target RUN (7.5-8.2 km/h) rispetto alla variabilità naturale del ritmo di corsa. Trovato anche, nel codice, un meccanismo distinto e reale (il reset di `confirmedPace` ad ogni cambio di fase può azzerare silenziosamente il cue "Good pace" la prima volta che si rientra in GOOD dopo il cambio) — marginale su questa corsa (2 casi) ma riproducibile.

**Sintomo 2 (overspeed percepito):** la sensazione è numericamente fondata (TOO_FAST 4× più frequente di TOO_SLOW, anche durante le fasi di camminata) **ma Paco Run e Strava sono in accordo quasi perfetto** (differenza media +0.03 km/h su tutta la corsa, +0.06 km/h nell'ultimo km) — quindi non è un errore di lettura della velocità. Il riferimento di ancoraggio sull'ultimo km è confermato da entrambe le fonti indipendentemente (mediana ~8 km/h su entrambe). La causa plausibile è la combinazione di target forse calibrati bassi per lo sforzo reale di quel giorno e l'asimmetria nel modo in cui i cue vengono comunicati (ripetuti ogni 5s per gli stati "cattivi", mai ripetuti per GOOD).

**Sintomo 3 (GPS lento/instabile):** confermato come oggettivamente diverso dalle due corse precedenti, causato dalla sola incertezza sulla velocità Doppler (non dalla posizione) nei primi ~17 fix. Il gate anti-falso-allarme ha avuto il suo primo confronto reale con GPS degradato ma non si è attivato, perché il degrado di oggi è stato un declino smooth, non un salto — resta non dimostrato (né smentito) sul campo.

### Cose che restano irrisolte o non verificabili con i dati disponibili

- **Nessun log reale delle decisioni esiste** (vedi sezione dedicata sopra) — ogni numero su "quando" è una ricostruzione, non un'osservazione diretta. Le conclusioni su Sintomo 1 in particolare andrebbero confermate con un vero log evento-per-evento in una prossima corsa.
- **Non è noto se `routineKm`/`routineUnlimited` abbiano fatto terminare la routine prima della fine della corsa** (es. se l'ultimo km fosse già in `CONSTANT_AFTER`, senza range attivo e quindi senza cue di ritmo) — se così fosse, il buon accordo Paco/Strava sull'ultimo km sarebbe comunque valido come controllo di precisione del sensore, ma l'assenza di oscillazioni in quel tratto non sarebbe un controllo di Sintomo 1 (semplicemente non ci sarebbe stato nessun cue di ritmo attivo). Non risolvibile dai soli dati GPX/FIT.
- **Non è noto se `gpsCuesEnabled` fosse esplicitamente attivato nelle impostazioni** — il default del codice è `false`, ma l'utente ha sentito un cue "BAD GPS" oggi, quindi deve essere stato attivato per questa corsa; non è verificabile dai file analizzati, solo inferito dal fatto che il cue è stato sentito.
- **La ricostruzione della fine della fase `CONSTANT_BEFORE` (2km) usa la distanza cumulata sui soli fix affidabili come proxy**, non il vero accumulatore di distanza dell'app (`DistanceAccumulator`, che ha una propria logica di ancoraggio/jitter, nota altrove per una differenza di circa -0.3/-0.65% rispetto alla distanza haversine grezza) — lo scostamento stimato è dell'ordine di pochi metri/secondi, non sufficiente a cambiare le conclusioni, ma non è lo stesso identico calcolo del codice reale.
- **Il gate anti-falso-allarme resta non validato sul campo** in entrambe le direzioni: non ha prodotto falsi allarmi (buono), ma nemmeno ha mai affrontato la condizione (un salto bruco di accuratezza) per cui è stato costruito.
