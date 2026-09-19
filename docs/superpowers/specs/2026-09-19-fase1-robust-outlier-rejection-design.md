# Fase 1 — rigetto robusto delle misure GPS anomale (G6) + logging geometria satellitare (G9)

## Cos'è Paco Run e perché questo documento esiste

Paco Run è un'app Android (Kotlin/Jetpack Compose) per la corsa, il cui valore centrale è il coaching vocale di ritmo in tempo reale basato su GPS. La velocità istantanea usata per quei cue non viene dal campo `location.speed` di Android (Doppler grezzo, sistematicamente compresso — vedi `docs/superpowers/specs/2026-09-10-speed-accuracy-design.md`) ma da un filtro di Kalman a stati (`SpeedEstimator.kt`), validato su due corse reali contro Strava con una riduzione di errore del 52-59%.

Questo documento è la Fase 1 della roadmap di precisione GPS (`docs/superpowers/specs/2026-09-11-precision-roadmap.md`, sezione "Stato delle decisioni", voci G6/G7/G8/G9/G3/G14). È leggibile senza aver letto quel documento — le decisioni rilevanti sono riassunte qui.

## Il problema che questo documento risolve

`SpeedEstimator.kt` mantiene due filtri di Kalman indipendenti (uno per l'asse est, uno per l'asse nord — classe interna `AxisKalman`). Ad ogni nuovo fix GPS, `AxisKalman.update(measurement, measurementVariance)` fa:

```kotlin
val innovation = measurement - position
val innovationVariance = p00 + measurementVariance
val k0 = p00 / innovationVariance
val k1 = p10 / innovationVariance
position += k0 * innovation
velocity += k1 * innovation
// + aggiornamento della covarianza (p00, p01, p10, p11) usando gli stessi k0/k1
```

dove `measurementVariance = accuracyMeters.pow(2)` e `accuracyMeters` è il valore di accuratezza orizzontale che Android stesso dichiara per quel fix (`horizontalAccuracyMeters`).

**Questo già funziona correttamente quando Android dichiara un'accuratezza scarsa**: `measurementVariance` cresce, il guadagno di Kalman (`k0`/`k1`) si riduce automaticamente, quel fix pesa poco — è la matematica normale del filtro, nessuna logica speciale necessaria.

**Il buco reale è l'opposto**: un fix per cui Android dichiara un'accuratezza *buona* (`accuracyMeters` basso, quindi `measurementVariance` bassa, quindi guadagno di Kalman alto) ma la cui posizione effettiva è un'anomalia spaziale — tipicamente un rimbalzo di multipath, un riflesso su una superficie, un singolo fix corrotto. In questo caso il filtro **si fida ciecamente** di un dato sbagliato, perché non ha alcun modo di sapere che `accuracyMeters` sta mentendo per quel fix specifico.

Il meccanismo oggi in produzione contro le anomalie, `SpeedEstimator.kt` righe 165-195 (il "gate G7"), reagisce solo a un **crollo del campo di accuratezza stesso** (`accuracyMeters > baseline * 2.0`) — non guarda mai il residuo/l'innovazione effettiva prodotta dal fix. Non copre il caso sopra descritto per definizione.

## Decisioni già prese, riportate qui per chi legge questo documento senza contesto pregresso

- **G3 (RAIM, rigetto statistico duro)**: scartata. È la stessa classe di problema di G6, con formalismo diverso (rigetto secco vs. peso morbido) — si è scelto G6 come approccio definitivo per questa famiglia di problema.
- **G5 (IMM, più filtri di Kalman in parallelo)**: fuori scope per questo documento. Tocca codice già validato e richiederebbe una revalidazione completa; la roadmap lo tiene come voce futura separata, non parte della Fase 1.
- **G8 (smoothing a ritardo fisso)**: scartato per la Fase 1 (2026-09-19). Numeri concreti dietro la decisione: MAE oggi (dopo il fix Kalman già in produzione) è 0.242 km/h contro un "tetto teorico" non-causale (che richiede dati futuri, irraggiungibile in produzione) di 0.16 km/h — un residuo di soli 0.08 km/h, più piccolo del rumore della stessa fonte (Strava) usata per misurarlo. Applicare G8 ai cue live introdurrebbe inoltre un ritardo di 2-3s su OGNI cue, inclusi quelli a inizio fase — esattamente il tipo di staleness che P2 (smoothing EMA phase-aware, già spedito) è stato costruito apposta per evitare. Costo reale contro un guadagno sotto la soglia di rumore: non vale la pena.
- **G9 (DOP — geometria satellitare)**: scope ridotto a **solo logging** in questa fase (dettagli sotto). Il calcolo vero del DOP e la sua integrazione nel motore di pesatura NON sono in questo documento — nessuna corsa reale ha mai mostrato un'anomalia attribuibile a geometria satellitare scarsa, ma questo NON significa che il problema non esista: l'unico dato disponibile è un utente, un dispositivo, un ambiente (campagna aperta). Per un'app destinata al Play Store, "non osservato su questo singolo banco di prova" non equivale a "non necessario" — vedi `docs/superpowers/specs/2026-09-11-precision-roadmap.md`, sezione G9, per il ragionamento completo.
- **G14 (costo di riconvergenza dopo un reset)**: questo documento **non tocca** la logica di reset/interruzione (`breakChain()`, `SessionCoordinator`). Il lavoro qui descritto opera esclusivamente sulla pesatura di un singolo fix dentro una catena di aggiornamenti già in corso — non decide mai se interrompere la catena. Il vincolo permanente di G14 (serve un test sintetico dedicato prima di allentare un reset per interruzioni lunghe) non si applica a questo lavoro.

## Parte A — G6: rigetto robusto (Huber/M-estimation) delle misure anomale

### Principio

Si introduce una **reponderazione robusta basata sul residuo effettivo**, che si affianca al gate G7 basato sui metadati **senza rimpiazzarne nessuna parte** (vedi sotto per la cronistoria di due correzioni su questo punto): per ogni fix, si calcola quanto la posizione misurata si discosta da quella che il filtro si aspettava, e si usa quella discrepanza — non l'accuratezza dichiarata da Android — per decidere quanto fidarsi del fix nell'aggiornamento di posizione/velocità e come CRITERIO AGGIUNTIVO (non sostitutivo) di esclusione dalla EMA di innovazione.

### Perché una statistica congiunta sui due assi, non due pesi indipendenti

Un errore di multipath reale sposta la posizione in una direzione nello spazio 2D — è uno spostamento correlato su est e nord, non due errori indipendenti generati a caso su ciascun asse. Due pesi calcolati indipendentemente (uno per asse) rischiano di non accorgersi di uno spostamento diagonale che è moderato su ciascun asse preso singolarmente ma grande se combinato.

La correzione: calcolare la grandezza congiunta

```
d² = (innovazione_est_normalizzata)² + (innovazione_nord_normalizzata)²
```

dove ogni innovazione normalizzata è calcolata come oggi (`innovation / sqrt(p00 + measurementVariance)`), PRIMA che l'una o l'altra applichi il proprio aggiornamento. Sotto l'ipotesi "fix normale", `d²` segue una distribuzione chi-quadrato con 2 gradi di libertà — è la stessa identica quantità statistica che avrebbe usato G3 (RAIM) per un rigetto secco; G6 la usa per un peso continuo invece di un taglio netto, com'è già scritto nella roadmap ("G6 non sostituisce RAIM, ne è una versione più elegante").

**Un rinforzo a questo argomento, e un costo onesto, trovati in review avversariale:** `eastAxis` e `northAxis` in questa implementazione ricevono sempre lo stesso `dt`, lo stesso `processNoise` e la stessa `measurementVariance` (scalare, non separata per asse) e partono dalla stessa varianza iniziale — quindi il loro `p00`/`p10` (e di conseguenza i loro guadagni `k0`/`k1`) sono **matematicamente identici ad ogni passo**, non solo "simili". Un peso condiviso non introduce quindi nessuna asimmetria nuova rispetto a come i due assi sono già trattati oggi. **Il costo onesto, da accettare consapevolmente e non da nascondere**: se l'anomalia è concentrata su un solo asse (es. un riflesso che sposta la posizione quasi solo in direzione est), il peso condiviso scredita leggermente anche il residuo dell'asse pulito (nord). Non separare i due casi è una scelta deliberata per restare dentro l'architettura attuale a due filtri indipendenti (separarli richiederebbe un filtro 2D unico con covarianza incrociata, un cambio strutturale più grande, fuori scope qui) — non una svista.

### Come si applica il peso senza rompere la coerenza matematica del filtro

**Vincolo di correttezza, non negoziabile (hard rule 12 di `CLAUDE.md`):** non basta scalare la correzione di posizione/velocità (`k0 * innovation`, `k1 * innovation`) per un peso `w` lasciando inalterato il resto — l'aggiornamento della covarianza (`p00`, `p01`, `p10`, `p11`) userebbe ancora i k0/k1 originali, calcolati come se il filtro si fosse fidato pienamente del fix. Il risultato sarebbe un filtro che si crede più sicuro di quanto la sua stessa correzione giustifichi — internamente incoerente, un "algoritmo che sembra funzionare ma mente".

**La correzione va applicata gonfiando la varianza di misura effettiva PRIMA di calcolare qualunque cosa**, così che guadagno E aggiornamento della covarianza restino derivati dallo stesso numero coerente:

```
effectiveMeasurementVariance = measurementVariance / weight(d²)
```

dove `weight(d²)` è 1.0 per un fix normale e scende verso 0 per un fix anomalo. Tutto il resto del calcolo (innovation, innovationVariance, k0, k1, l'aggiornamento di posizione/velocità/covarianza) procede esattamente come oggi, ma usando questa varianza gonfiata al posto di `measurementVariance` grezza.

### Conseguenza architetturale su `AxisKalman`

`AxisKalman.update()` oggi calcola l'innovazione e applica l'aggiornamento nella stessa chiamata. Per calcolare `d²` congiunto servono le innovazioni grezze di **entrambi** gli assi prima che uno dei due applichi il proprio aggiornamento. `AxisKalman` va quindi diviso in due fasi:

1. **Peek**: calcola e restituisce l'innovazione normalizzata grezza, senza modificare lo stato interno.
2. **Apply**: dato il peso (calcolato in `SpeedEstimator.accept()` dopo aver fatto il peek su entrambi gli assi), applica l'aggiornamento vero usando la varianza gonfiata.

`SpeedEstimator.accept()` orchestra: peek(est) + peek(nord) → calcola `d²` e il peso congiunto → apply(est, peso) + apply(nord, peso).

### Cosa succede all'EMA di innovazione (qLow/qHigh) e al gate G7 — CORRETTO dopo la review avversariale (2026-09-19)

**Prima versione di questo documento, sbagliata, corretta qui:** la prima stesura diceva che G6 sostituisce interamente G7. Una review avversariale indipendente ha trovato che questo avrebbe reintrodotto un bug già risolto una volta. Il dettaglio, perché conta:

`accuracyDegraded` (righe 171-172 del file attuale) fa **due lavori distinti**, non uno:

1. **Esclude il fix dalla EMA di innovazione** (righe 189-192) — così un'anomalia isolata non falsa il rilevatore di accelerazione reale.
2. **Forza il regime `qLow` indipendentemente da cosa dice l'EMA** (righe 174-180) — questo è un lavoro diverso, e già una volta si è scoperto che è necessario: il commento originale in `SpeedEstimator.kt` (righe 30-40) documenta che una degradazione di accuratezza severa può produrre rumore che "assomiglia a un'accelerazione vera" per il rilevatore di innovazione, e uno sweep empirico su 5000 semi ha dimostrato che il rilevatore da solo NON protegge tutti i fix di una raffica degradata — motivo per cui questo secondo lavoro esiste.

**Perché il peso di G6 non copre il lavoro (2):** il peso di G6 normalizza il residuo rispetto a `innovationVariance = p00 + measurementVariance` — una varianza già gonfiata dall'accuratezza scarsa. Un fix rumoroso "per colpa dell'accuratezza" produce quindi un residuo normalizzato che può risultare non-anomalo al test di G6 (il filtro si è già "aspettato" più rumore, quindi non lo segnala come outlier), pur essendo esattamente il tipo di rumore che il lavoro (2) doveva intercettare. Rimuovere G7 del tutto avrebbe rimosso questa protezione senza sostituirla.

**Seconda correzione, trovata nel secondo giro di review avversariale (2026-09-19):** la prima correzione qui sopra diceva ancora che G6 "sostituisce" il lavoro (1) — stesso errore concettuale del lavoro (2), in un punto diverso. Il ragionamento del paragrafo precedente (il peso di G6 tende a restare ≈1 durante un'accuratezza degradata, perché la varianza è già gonfiata) si applica anche qui: se l'esclusione dalla EMA usasse SOLO il peso di G6 invece del flag `accuracyDegraded`, un fix degradato ma non abbastanza anomalo da attivare il peso non verrebbe più escluso — esattamente la contaminazione che l'esclusione incondizionata esisteva per prevenire.

**Decisione corretta, questa volta senza sostituzioni:** G6 **non sostituisce nessuna parte di G7**. G7 resta intatto in entrambi i suoi lavori — il calcolo di `accuracyDegraded`/`accuracyBaselineMeters`, la sua capacità di forzare `qLow`, e la sua esclusione incondizionata dalla EMA quando `accuracyDegraded` è vero, tutto invariato. **G6 aggiunge** due cose nuove, senza toccare quanto già esiste: (i) il peso congiunto che gonfia la varianza effettiva usata nell'aggiornamento di posizione/velocità (la vera capacità nuova, l'unica cosa che il codice di oggi non sa fare); (ii) un **secondo, indipendente** criterio di esclusione dalla EMA — un fix viene escluso se `accuracyDegraded` **oppure** se il peso di G6 è basso (OR, non sostituzione). I due criteri di esclusione coesistono; nessuno dei due viene mai rimosso a favore dell'altro.

`accuracyMeters` continua a determinare la `measurementVariance` di base — G6 agisce sopra quella base, non la sostituisce.

### Cosa resta deliberatamente aperto per la fase di implementazione

- **La forma esatta di `weight(d²)`**: un Huber classico (`w = k / sqrt(d²)` per `d² > k²`, altrimenti 1) dà sempre un peso residuo non-nullo anche a un'anomalia enorme. Un peso "redescending" (es. stile Tukey, azzerato del tutto oltre una seconda soglia più estrema) protegge meglio da uno spike davvero grande — raccomandato, ma la scelta finale e i valori numerici delle soglie vanno tarati con lo stesso metodo empirico già usato per `qHigh`/`accuracyDegradationRatio` in questo file (sweep sintetico + verifica di non-interferenza sulla corsa reale registrata), non decisi qui a tavolino.
- **I valori numerici delle soglie stesse.**

### Validazione richiesta (da dettagliare nel piano di implementazione, non qui)

1. **Test sintetico dedicato al buco specifico**: una traiettoria pulita con uno o più fix iniettati che abbiano *buona* accuratezza dichiarata ma posizione anomala — esattamente lo scenario che G7 non copre e G6 deve coprire. Verificare che il peso robusto lo rilevi e ne limiti l'influenza su posizione/velocità.
2. **Non-regressione su rumore normale**: verificare che sul rumore GPS tipico (nessuna vera anomalia) il peso resti ≈1 e il comportamento sia indistinguibile da oggi.
3. **Non-regressione sulla raffica di accuratezza degradata (il bug che G7 risolveva già una volta)**: ri-eseguire lo stesso tipo di test empirico già usato per validare `accuracyDegradationRatio`/`accuracyBaselineAlpha` (sweep su una raffica di accuratezza degradata simulata, più semi) e confermare che il regime resta `qLow` durante la raffica **esattamente come oggi**.
4. **Non-contaminazione della EMA subito DOPO la fine della raffica** (trovato nel secondo giro di review avversariale, 2026-09-19 — non basta testare solo durante la raffica): verificare che, terminata una raffica di accuratezza degradata, la EMA di innovazione non sia rimasta "sporcata" e non causi un falso `qHigh` sui primi fix puliti successivi. Con l'esclusione dalla EMA impostata come OR (`accuracyDegraded` oppure peso di G6 basso — non una sostituzione), questo dovrebbe già essere garantito per costruzione dato che `accuracyDegraded` continua a escludere incondizionatamente ogni fix della raffica come faceva prima — ma va verificato con un test esplicito, non assunto.
5. **Confronto sulla corsa reale registrata** (`paco-run-2026-09-15-110804-events.jsonl`, MAE baseline 0.242 km/h): verificare nessuna regressione. Nota onesta da riportare comunque nel piano: l'unica anomalia già trovata in quella corsa era legata a un'interruzione GPS (G14), non a un fix "accuratezza buona, posizione cattiva" — è plausibile che questo cambiamento non mostri alcun effetto visibile su QUESTA corsa specifica, il che sarebbe un risultato onesto da riportare, non un fallimento del test.
6. **Verifica di autoconsistenza matematica** (hard rule 12): confermare per iscritto, prima di considerare l'implementazione finita, che l'aggiornamento della covarianza usa sempre la stessa varianza effettiva usata per il guadagno — nessuna scalatura post-hoc della sola correzione di stato.
7. **Recupero legittimo dopo una raffica NON deve essere scambiato per un'anomalia** (trovato nel terzo giro di review avversariale, 2026-09-19 — interazione nuova, non una ripetizione dei due errori precedenti): durante una raffica di accuratezza degradata il regime resta `qLow` di proposito (reagisce piano). Se nel frattempo il runner accelera davvero, il primo fix buono dopo la raffica mostra un residuo grande e **legittimo** (il filtro deve recuperare il ritardo accumulato), non un'anomalia spaziale — ma dal punto di vista del peso di G6 le due cose sono indistinguibili (entrambe producono un residuo normalizzato grande). Test richiesto: traiettoria sintetica con (a) raffica di accuratezza degradata, (b) accelerazione vera durante la raffica, (c) fix buono di recupero subito dopo — verificare che il peso di G6 non soffochi quel recupero né lo escluda dalla EMA in modo da ritardare il passaggio a `qHigh` proprio quando servirebbe di più. Se il test mostra un problema reale, la soglia di `weight(d²)` (già lasciata aperta sopra) va scelta tenendo conto anche di questo scenario, non solo del rigetto degli outlier — non è necessariamente un cambio di design, potrebbe essere solo un vincolo in più sulla taratura empirica già prevista.

## Parte B — G9: logging elevazione/azimuth per satellite (nessun cambio al motore)

### Cosa si aggiunge — CORRETTO dopo la review avversariale (2026-09-19)

**Prima versione di questo documento, ambigua, corretta qui:** dicevo semplicemente di "estendere lo schema esistente" con elevazione/azimuth. La review avversariale ha trovato che questo si presta a due letture con conseguenze molto diverse: (a) aggregare elevazione/azimuth in min/media/max, seguendo esattamente il pattern già usato per il CN0 (righe 250-264 del file attuale) — più semplice, coerente con lo schema di oggi, **ma inutile per lo scopo dichiarato**: il calcolo del DOP richiede la geometria di OGNI satellite individualmente (per costruire la matrice di geometria), non statistiche riassuntive; oppure (b) un record vero per-satellite (elevazione, azimuth, `usedInFix` di CIASCUN satellite, non un riassunto) — l'unica forma che serve davvero al fine dichiarato.

**Decisione: opzione (b).** `RunSessionService.kt` già registra un callback `GnssStatus.Callback` (righe 246-267) che oggi calcola solo CN0 min/media/max su tutti i satelliti visibili — un riassunto scalare per fix. Il nuovo logging è strutturalmente diverso, non un'estensione dello stesso pattern: per ogni fix, una **lista** con un elemento per ogni satellite riportato da `GnssStatus`, contenente:

- `svid` (identificativo del satellite, per poter distinguere le voci)
- `elevationDegrees` (`GnssStatus.getElevationDegrees(i)`)
- `azimuthDegrees` (`GnssStatus.getAzimuthDegrees(i)`)
- `usedInFix` (`GnssStatus.usedInFix(i)`)
- `constellationType` (utile per distinguere GPS/GLONASS/Galileo/BeiDou nella stessa lista)

Questo è un nuovo tipo di evento/campo in `SessionEvent.kt`/`SessionEventWriter.kt` (una lista di 5-30+ elementi per fix, non 3-4 scalari), non una piccola estensione — il piano di implementazione deve trattarlo come tale (costo di serializzazione/dimensione del file di log da valutare esplicitamente, non assunto trascurabile).

### Cosa NON si fa in questo documento

- Nessun calcolo del DOP vero (richiederebbe costruire la matrice di geometria dai vettori satellite-ricevitore e invertirla — lavoro a parte, deliberatamente rimandato).
- Nessuna modifica a `SpeedEstimator` o al meccanismo di pesatura dei fix.
- Nessuna azione se i dati raccolti mostrano che la geometria non ha mai influenzato una decisione — il logging esiste per rendere possibile scoprirlo in futuro (su questo dispositivo o su altri, se l'app arriva su altri utenti/Play Store), non per giustificare retroattivamente un lavoro non ancora fatto.

### Perché è nella stessa fase di G6 nonostante non abbia nulla a che fare con l'algoritmo

Puramente per convenienza di sviluppo/dispatch: entrambi toccano lo stesso file (`RunSessionService.kt`/`SessionEvent.kt`) e nessuno dei due comporta rischio per l'altro. Non c'è una dipendenza logica tra le due parti — potrebbero anche essere due piani separati senza perdere nulla.

## Fuori scope (per completezza, non lasciato ambiguo)

- G1 (doppia frequenza), G2 (multi-costellazione): nessuna azione, non richiedono design (vedi roadmap).
- G8: scartato, vedi sopra.
- Isteresi/zone cuscinetto su `PaceEvaluator` (tracciata come P6 nella roadmap): non è questo documento. Riguarda la decisione sul ritmo dato un segnale già corretto, non l'accuratezza del segnale stesso. Da brainstormare separatamente, subito dopo questa fase.
- Qualunque modifica alla logica di reset/interruzione del motore (G14): esplicitamente non toccata qui.
