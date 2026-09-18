# Precisione della velocità in Paco Run — indagine e design

**Branch:** `feature/paco-run-m3e` (live). **Data:** 2026-09-10. **Stato:** `SpeedEstimator` (Approccio A, Kalman adattivo) implementato e testato — 12 task completati con TDD e dual-review (self + subagent indipendente), suite completa verde (`./gradlew testDebugUnitTest`, `lintDebug`). I quattro parametri di partenza (sezione 7) sono stati ritarati due volte durante l'implementazione: i valori originali venivano da una simulazione Python mai verificata su questa implementazione Kotlin e non reggevano contro rumore GPS reale iniettato nei test. Valori finali: `qLow=0.10` (invariato), `qHigh=0.75` (era 25.0), `innovationThreshold=0.60` (era 1.75), `innovationEmaAlpha=0.3` (invariato); il meccanismo è passato da EMA dell'innovazione non firmata a EMA firmata per asse. Validato su una seconda fonte indipendente dalla corsa reale del 2026-09-10 contro Strava (MAE 0.25-0.27 km/h, corr 0.94-0.96 — in linea con il target originale di questo documento). **Aggiornamento 2026-09-11/12:** il comportamento transitorio sopra descritto (crollo di accuratezza scambiato per un'accelerazione vera) è stato **corretto**, non solo documentato: `SpeedEstimator` ora ha un secondo gate indipendente dall'interruttore adattivo, basato su una baseline EMA a memoria lunga (~20 fix) dell'accuratezza GPS — quando l'accuratezza corrente supera 2× quella baseline, il fix forza `qLow` e non contribuisce alla media mobile dell'innovazione, così un crollo di accuratezza non "avvelena" la decisione dei fix successivi. Il limite di deviazione del test del burst di accuratezza degradata (sezione 10.2, scenario 6) si è ristretto da 8.0 a 6.0 km/h con margine quasi raddoppiato (77%→98% sul caso peggiore osservato). I quattro parametri originali (`qLow`/`qHigh`/`innovationThreshold`/`innovationEmaAlpha`) non sono stati ritoccati — il gate è additivo. Sulla corsa reale disponibile il gate non scatta mai per errore (0/5195 fix), ma quella corsa non ha mai un vero crollo di accuratezza (l'utente corre in aperta campagna), quindi questo dimostra solo l'assenza di falsi positivi sul rumore ordinario, non l'efficacia del gate in un evento reale — resta valido solo sui test sintetici finché non arriva una corsa con copertura GPS degradata.

In parallelo, l'output di `SpeedEstimator` è stato collegato **solo** all'export GPX diagnostico (nuovo campo `pr:speedEstimatorKmh` accanto a `pr:speedMps`) per permettere il confronto sulla prossima corsa reale — zero impatto sull'esperienza dal vivo, non tocca `PaceEvaluator`/`SessionCoordinator`/`RunSessionState`, verificato a livello di codice in review indipendente.

**Aggiornamento 2026-09-12 — seconda corsa reale di validazione, requisito del piano soddisfatto.** Corsa indipendente dalla prima (`paco-run-2026-09-12-1740.gpx`, 5222 punti, confrontata contro `Corsa_pomeridiana.fit` di Strava, stesso metodo causale a finestra scorrevole di sempre), con l'implementazione Kotlin reale sul telefono, non una simulazione: `location.speed` (ancora quello usato dal vivo) MAE=0.619 km/h corr=0.823; `speedEstimatorKmh` (nuovo) MAE=0.297 km/h corr=0.946 — **riduzione errore -52%**, coerente con la prima corsa (-59% il 9/10, numeri leggermente diversi ma stessa conclusione netta, variazione normale corsa-su-corsa). Distanza raw -0.43% (in linea con -0.28% della prima corsa). Il requisito del piano originale (sezione 10.4, passo 4: seconda corsa reale prima del wiring) è ora soddisfatto. **Non risolto da questa seconda corsa:** anche questa non ha mai avuto accuratezza GPS degradata (3.0-7.9m, zero fix sopra 10m) — il gate anti-falso-allarme resta validato solo sui test sintetici, non sul caso reale, per lo stesso motivo strutturale (vedi memoria di progetto `project_running_conditions_gps_validation`). **Il collegamento alla produzione (`PaceEvaluator`/`SessionCoordinator`) resta una decisione aperta dell'utente**, non più bloccata dal requisito di validazione ma non ancora presa.

**Ancora non collegato alla produzione** (`PaceEvaluator`/`SessionCoordinator` per il feedback vocale/vibrazione dal vivo) — resta il requisito di una seconda corsa reale di validazione (sezione 10.4, passo 4) prima di quel wiring, idealmente con un tratto a copertura GPS degradata (sezione urbana densa o sotto alberi): è l'unico modo per validare sul campo sia la precisione generale sia il nuovo gate anti-falso-allarme, dato che le corse dell'utente in campagna aperta non produrranno mai quella condizione. Accelerometro come lavoro futuro, non ancora progettato nel dettaglio. Dettagli completi del percorso di implementazione, ritaratura e review nei messaggi di commit di `git log` sul branch (12 commit del piano originale + fix di review, poi 8 commit del follow-up gate+export) e nei piani `docs/superpowers/plans/2026-09-10-speed-estimator-kalman.md` e `docs/superpowers/plans/2026-09-11-speed-estimator-accuracy-gate-and-export.md`.

Questo documento esiste per permettere a una sessione/agente senza contesto pregresso di riprendere il lavoro senza dover rifare l'indagine. Copre: perché è partita l'indagine, cosa è stato scoperto, quali soluzioni sono state provate e scartate (e perché), la soluzione scelta, e cosa resta da fare.

---

## 1. Perché è partita l'indagine

Il 2026-09-10 l'utente ha corso portando contemporaneamente Paco Run (in sviluppo, branch `feature/paco-run-m3e`) e Strava (app separata, stesso telefono) per confrontare i dati. Priorità dichiarata dall'utente: **la velocità conta più della distanza**, perché tutta la logica di feedback dell'app (`PaceEvaluator`, i cue vocali "troppo lento"/"troppo veloce") dipende dalla velocità istantanea, non dalla distanza totale.

Obiettivo esplicito dell'utente: Paco Run deve diventare fonte di verità autoritaria **almeno quanto Strava**, sia in stato stazionario (ritmo costante) sia durante scatti/accelerazioni — non è accettabile un compromesso dove si migliora un asse a scapito dell'altro.

## 2. Dati usati e come sono stati ottenuti

- `app/../paco-run-2026-09-10-1122.gpx` — export GPX di Paco Run (`export/GpxWriter.kt`), contiene ogni fix grezzo (lat/lon/tempo/`speedMps`/`horizontalAccuracyMeters`/`speedAccuracyMps`/`reliable`).
- File Strava — **attenzione**: sono stati scaricati 3 file prima di ottenere quello giusto:
  1. `Corsa_dell_ora_di_pranzo.gpx` — scaricato dal bottone "GPX" del widget mappa/"Create Route" nella pagina attività. Sospettato inizialmente di essere processato da Strava (quota ripetuta identica nei primi punti, tipico di correzione altimetrica). **Verificato non essere un problema per posizione/distanza** (vedi punto 4).
  2. `Corsa_dell_ora_di_pranzo(1).gpx` — bytewise identico al file 1 (stesso bottone ripremuto per errore).
  3. `Corsa_dell_ora_di_pranzo.fit` — il vero **Export Original**, formato binario FIT. Nessun parser Python disponibile nell'ambiente (no `fitparse`, no `gpsbabel`, no accesso pip); **scritto un mini-parser FIT da zero** (`/tmp/.../scratchpad/fitparse_mini.py`, non nel repo — da riscrivere se serve di nuovo) per estrarre `position_lat`/`position_long` (semicircles), `timestamp`, `speed`/`enhanced_speed`, `gps_accuracy` dai messaggi "record" (global message 20).

**Tutti gli script di analisi sono in `/tmp/claude-1000/.../scratchpad/` (temporanei, sessione-specifici, NON nel repo).** Se serve rifare l'analisi, vanno riscritti — non fare affidamento sul fatto che esistano ancora.

## 3. Il problema di distanza (secondario, non oggetto di questo documento)

Riassunto rapido, dettagli completi in `docs/pending-review-findings-2026-09-10.md`:
- Distanza raw GPS Paco Run vs Strava: -0.28% (ottimo)
- Distanza mostrata a schermo (simulando `DistanceAccumulator` reale): -0.65% (bug #3 già noto: soglia jitter con ancora congelata)
- **Questo documento NON copre il fix della distanza** — è stato deliberatamente disaccoppiato (vedi sezione 6).

## 4. Verifica: il file Strava era processato?

Preoccupazione: se il GPX Strava usato come riferimento fosse già "smussato" da Strava, tutto il confronto sarebbe sballato.

**Verificato NON essere un problema.** Confronto tra il file FIT (originale, binario, non processabile da Strava lato export) e il GPX usato:
- Somma haversine su tutti i punti FIT grezzi: **9.667 km**
- Somma haversine su tutti i punti del GPX usato per l'analisi: **9.669 km**
- Differenza: 2 metri su 9.6 km (0.02%) — **il GPX non era processato, tutta l'analisi a valle resta valida.**

## 5. Il problema di velocità — caratterizzazione

`PaceEvaluator.accept()` (in `app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt`) usa direttamente `sample.speedKmh`, che viene da `location.speed` mappato in `LocationSampleMapper.kt` — il valore Doppler grezzo fornito da Android, nessuna elaborazione propria.

**Metodologia di confronto (importante, causale vs non-causale):**
- Confronto "non-causale" (usa anche punti futuri, impossibile in tempo reale): usato solo per stabilire un tetto teorico.
- Confronto "causale" (guarda solo indietro, quello che l'app può davvero calcolare live): usato per tutte le decisioni di design. Metodologia: per ogni finestra scorrevole trailing di N secondi, calcolata **con lo stesso identico algoritmo sia su Paco Run sia su Strava** (per evitare di confrontare mele con pere), poi accoppiate per timestamp più vicino (tolleranza 1.5s).

**Risultati chiave (causale, baseline):**

| Segnale | MAE (km/h) | RMSE | Correlazione |
|---|---|---|---|
| `location.speed` (oggi) | 0.591 | 0.770 | 0.865 |
| Tetto teorico (non-causale, "baro" guardando il futuro) | 0.16 | — | 0.986 |

**MAE = errore medio assoluto di velocità istantanea, in km/h, misurato ogni finestra di 10s contro Strava. Non è una percentuale, non riguarda la distanza.**

**Diagnosi — non è rumore né salti impossibili:**
- Nessun salto di velocità fisicamente impossibile in `location.speed` (0 casi su 5195 intervalli con accelerazione implicita > 3 m/s²).
- Variazione punto-a-punto piccola e regolare (media 0.21 km/h, stdev 0.28) — il segnale è liscio, non rumoroso.
- Test di sfasamento temporale (-30s..+30s): il miglior match è a **0s di shift** — non è un problema di ritardo/lag.
- **È una compressione sistematica.** Regressione lineare: `device_speed = 0.685 * strava_speed + 1.726`. Schiaccia i valori verso la media: sottostima nelle fasi veloci (fino a -2.85 km/h durante scatti), lieve sovrastima nelle fasi lente. Verosimilmente il filtro interno del chip GPS/Android (Doppler/carrier-based), non un bug di Paco Run.

## 6. Approcci esplorati (in ordine cronologico) e perché sono stati scartati o accettati

### 6.1 Finestra scorrevole trailing (posizione, causale) — scartata come soluzione finale, ma utile come riferimento

Velocità = distanza percorsa negli ultimi N secondi / tempo, calcolata su Paco Run e Strava con lo stesso metodo:

| Finestra | MAE | Corr |
|---|---|---|
| 1s | 0.833 | 0.710 |
| 2s | 0.569 | 0.876 |
| 3s | 0.429 | 0.924 |
| 5s | 0.298 | 0.960 |
| 8s | 0.212 | 0.979 |
| 10s | 0.182 | 0.984 |
| 15s | 0.130 | 0.991 |

Finestre lunghe = più precise ma troppo lente a reagire per i cue vocali in tempo reale (10-15s di ritardo percepito è inaccettabile). Finestre corte = troppo rumorose. **Nessun singolo valore fisso soddisfa contemporaneamente precisione e reattività** — primo indizio del vincolo strutturale sotto.

### 6.2 Kalman filter a q fisso (posizione sola, vincolato ANCHE alla distanza)

Filtro 1D per asse (est/nord, proiezione equirettangolare locale), stato `[posizione, velocità]`, modello a velocità costante, rumore di processo `q` costante, misura = posizione pesata per `horizontalAccuracyMeters²`.

Testato vincolando l'ottimizzazione SIA su distanza totale SIA su MAE velocità (obiettivo originale, prima dell'insight del disaccoppiamento in 6.5):

| q | distanza | diff vs Strava | MAE | Corr |
|---|---|---|---|---|
| 0.25 | 9.570 km | -1.02% | 0.309 | 0.953 |
| 0.50 | 9.584 km | -0.88% | 0.397 | 0.929 |
| 1.00 | 9.596 km | -0.75% | 0.490 | 0.898 |
| 2.00 | 9.607 km | -0.64% | 0.576 | 0.864 |
| 4.00 | 9.617 km | -0.54% | 0.652 | 0.832 |
| 8.00 | 9.626 km | -0.45% | 0.721 | 0.803 |

Stesso identico vincolo della finestra scorrevole, solo riparametrizzato: q basso = velocità precisa ma distanza sotto Strava (smussare la posizione "taglia gli angoli" e riduce i km); q alto = distanza più vicina ma velocità rumorosa.

### 6.3 Kalman adattivo (q che sale quando rileva un'accelerazione vera) — ancora vincolato a distanza+velocità

Aggiunta rilevazione di "innovazione" (quanto la nuova posizione si scosta dalla predizione, normalizzata per l'incertezza) con media mobile esponenziale; sopra una soglia, passa da `q_low` a `q_high`.

Risultato: velocità migliorata rispetto al q fisso (MAE fino a 0.245), **ma la distanza è PEGGIORATA** (fino a -1.22%), non migliorata. La reattività aggiunta per la velocità va contro la stabilità richiesta per la distanza — confermato essere un limite strutturale del filtrare **solo la posizione per servire entrambi gli obiettivi con la stessa stima**, non un problema di tuning dei parametri.

### 6.4 Fusione con `location.speed` — TENTATA E SCARTATA

Ipotesi: dato che `location.speed` è poco rumoroso (buono in stato stazionario) e la posizione è fedele ma rumorosa (buona per gli scatti), fondere i due segnali in un Kalman a due misure (posizione + velocità Doppler proiettata sulla direzione stimata) avrebbe dovuto dare il meglio di entrambi.

**Risultato: PEGGIORA sempre, su entrambi gli assi, a qualunque livello di fiducia testato** — anche pesando `location.speed` pochissimo (varianza di misura `R` grande, cioè bassa fiducia):

| Config (q, fiducia in location.speed) | distanza diff | MAE | Corr |
|---|---|---|---|
| Fiducia alta (R piccolo) | -5.9/-6.0% | ~0.59 | ~0.86 |
| Fiducia media (R=4.0-20) | -3.0/-3.4% | 0.39-0.43 | 0.91-0.93 |

Tutte le configurazioni testate sono **peggiori del Kalman su sola posizione** su entrambi gli assi. **Motivo:** `speedAccuracyMps` (l'incertezza che Android stesso dichiara per `location.speed`) riflette solo il rumore casuale, non il bias sistematico di compressione scoperto in sezione 5 — quindi il filtro non ha modo di sapere "non fidarti proprio adesso", e qualunque peso dato al segnale reintroduce il suo stesso bias senza portare informazione nuova rispetto a quella già presente nella posizione. **Conclusione: `location.speed` va abbandonato del tutto per il calcolo della velocità istantanea, non solo corretto.**

### 6.5 L'insight che sblocca tutto: disaccoppiare velocità e distanza

Il vincolo "un solo filtro deve servire bene sia distanza sia velocità" era **auto-imposto, non necessario**. Poiché il Kalman su posizione non batte comunque chiaramente l'euristica di distanza esistente (`DistanceAccumulator`, oggi a -0.65%; Kalman varia da -0.45% a -1.2%, nessun chiaro miglioramento), **non c'è motivo di sostituire la distanza**. Si tiene `DistanceAccumulator` esattamente com'è (bug #3 resta un task separato, vedi `docs/pending-review-findings-2026-09-10.md`), e si progetta un filtro dedicato **solo** alla velocità, libero da quel vincolo.

## 7. Soluzione scelta — Approccio A: Kalman adattivo, solo velocità, disaccoppiato dalla distanza

Rimossa la necessità di servire bene anche la distanza, il Kalman adattivo (6.3) è stato ri-ottimizzato **solo** per MAE/correlazione velocità, via grid search (432 combinazioni testate su `q_low`, `q_high`, soglia, alpha).

**Configurazione ottima trovata (plateau stabile, non un punto isolato — più combinazioni vicine danno risultati quasi identici, buon segno di robustezza):**

```
q_low   = 0.10   (rumore di processo in stato stazionario — basso, filtro stabile)
q_high  = 15-40  (rumore di processo durante accelerazione rilevata — alto, filtro reattivo; poco sensibile al valore esatto sopra 15)
soglia  = 1.5-2.0 (innovazione normalizzata sopra cui si passa a q_high)
alpha   = 0.3    (fattore della media mobile esponenziale sull'innovazione)
```

**Risultato: MAE 0.242 km/h, RMSE 0.352, correlazione 0.9663** (causale, stesso metodo di confronto di tutta l'indagine).

Confronto:

| | MAE | Corr |
|---|---|---|
| Oggi (`location.speed`) | 0.591 | 0.865 |
| **Fix (Approccio A)** | **0.242** | **0.966** |
| Tetto teorico (non-causale, non raggiungibile in pratica) | 0.16 | 0.986 |

Riduzione errore: **59%**. Il fix recupera quasi tutto il margine disponibile tra oggi e il tetto teorico (che comunque non è zero: anche Strava è un'altra stima rumorosa dello stesso fenomeno fisico, non un righello).

**Esempio concreto (transizione cammina→corri reale, 12:11:33-12:11:42):**

```
ora      |   Strava | device (oggi) | Kalman (fix)
12:11:33 |     6.22 |         5.96  |         6.16
12:11:34 |     6.66 |         5.75  |         7.03
12:11:37 |     8.63 |         8.86  |         8.35
12:11:39 |     9.62 |         8.42  |         9.59
12:11:40 |     9.97 |         8.59  |         9.98   <- Strava e fix quasi identici, device 1.4 km/h sotto
12:11:41 |    10.13 |         8.44  |        10.25
```

### Architettura (da implementare)

- Un componente nuovo e isolato (nome suggerito: `SpeedEstimator`, stesso spirito/cartella di `DistanceAccumulator` in `RunSessionStore.kt`), **scollegabile** dal motore come richiesto dall'utente — non deve rimpiazzare `DistanceAccumulator`, deve affiancarlo.
- Due filtri di Kalman scalari (uno per asse est, uno per nord — proiezione locale equirettangolare attorno al primo fix della sessione), stato `[posizione, velocità]` ciascuno.
- Input per ogni fix: `latitude`, `longitude`, `horizontalAccuracyMeters` (già disponibile, usato anche da `DistanceAccumulator`), `reliable` (già calcolato da `LocationSampleMapper`).
- **Non usa `location.speed` in nessuna forma.**
- Output: `speed = hypot(vx, vy)`.
- Parametri di partenza: quelli della sezione sopra — da ri-validare in Kotlin con test propri, i numeri qui sono da una simulazione Python su UNA corsa.

### Testing raccomandato (vedi anche sezione 10 sui test sintetici)

Seguire lo standing instruction del progetto per lavoro GPS-critico (da `docs/handoff-2026-09-08.md`): **sempre TDD, sempre dual-review** (self + subagent fresco indipendente) prima di considerarlo chiuso. In aggiunta, dato quanto scoperto qui:
- Test con traiettorie sintetiche a velocità nota (vedi sezione 10) — prioritario, non richiede nuove corse.
- Validazione su almeno una seconda corsa reale contro Strava prima di fidarsene in modo definitivo (questa indagine si basa su una sola corsa).

## 8. Cosa NON fare (esplorato e scartato, per non riprovarlo)

- **Non fondere `location.speed`** nella stima di velocità, a nessun livello di fiducia — peggiora sempre (sezione 6.4).
- **Non usare un'unica finestra/filtro fisso** per servire sia distanza sia velocità — vincolo strutturale, non risolvibile con tuning (sezioni 6.1-6.3).
- **Non fidarsi di `speedAccuracyMps`** come indicatore di affidabilità di `location.speed` — non riflette il bias sistematico di compressione, solo il rumore casuale.

## 9. Lavoro futuro (non ancora progettato in dettaglio): fusione con accelerometro/cadenza passi

Discusso ma non progettato — questa sezione cattura il ragionamento fatto, da riprendere quando si deciderà di affrontarlo.

**Perché:** chiudere ulteriormente il divario dal tetto teorico, e dare un segnale di velocità anche quando il GPS è debole/assente.

**Come (concetto, non design finale):**
- Usare il sensore hardware/OS di Android per il conteggio passi (`TYPE_STEP_DETECTOR`) invece di implementare la rilevazione picchi da zero sui dati grezzi dell'accelerometro.
- Calibrare dinamicamente "metri per passo" usando il GPS quando è affidabile (accuracy buona, filtro in stato stabile); usare quella calibrazione come stima di velocità quando il GPS peggiora.
- **Vincolo di design non negoziabile (richiesto esplicitamente dall'utente):** l'accelerometro non deve mai peggiorare rispetto a oggi. Ottenuto per costruzione, non per taratura: (a) fiducia dinamica basata sulla regolarità del segnale passi rilevato (segnale pulito = alta fiducia, segnale caotico = fiducia quasi zero, si "spegne da solo"); (b) ruolo limitato — correttore/ripiego subordinato al GPS, non sostituto paritario sempre attivo.

**Contesto fisico rilevante (utente):**
- Telefono: Google Pixel 9a, 154.7 x 73.3 x 8.9 mm, 185.9 g.
- Porta il telefono nella tasca **stretta dei pantaloni da running** (non tasca larga generica) — vincolo al bacino ragionevolmente ripetibile, caso più favorevole di quanto inizialmente ipotizzato per la rilevazione passi.
- Prevede di comprare una fascia da braccio in futuro — consigliato tessuto elastico/a compressione (neoprene/lycra) invece di guscio rigido sagomato, per evitare il rischio di gioco/ballo con un telefono per cui non esistono fasce "dedicate". Esempi con queste caratteristiche trovati in ricerca web: Sprigs Banjees Armband, Gritin Armband, Nike Lean Arm Band.
- **Implicazione:** il guadagno da questa feature potrebbe essere utile già con la tasca attuale, non solo dopo l'acquisto della fascia — non è un prerequisito bloccante come inizialmente ipotizzato.

**Perché è più grande del lavoro sul Kalman posizione:** il pattern (fusione GPS/inerziale con fiducia a gating, calibrazione dinamica del passo) è un campo consolidato e documentato ("Pedestrian Dead Reckoning" / "loosely-coupled GPS/INS fusion"), non ricerca da inventare — ma il rumore dell'accelerometro dipende fortemente da COME il telefono è portato, ed è molto più difficile modellarlo in modo sintetico realistico rispetto al rumore di posizione GPS (per cui bastano le distribuzioni di accuracy già misurate). Richiede più iterazioni di test reale, non solo simulazione offline.

## 10. Test con traiettorie sintetiche — FONDAMENTALI, non opzionali

**Perché sono più importanti del confronto con Strava, non solo un complemento:** ogni numero in questo documento (MAE, correlazione, ecc.) viene dal confronto con Strava — cioè con **un'altra stima rumorosa dello stesso fenomeno fisico, mai con la verità**. Non sappiamo con certezza assoluta quale fosse la velocità vera dell'utente in nessun istante della corsa del 2026-09-10. I test sintetici risolvono questo: la velocità "vera" è quella scritta nel test, per costruzione, senza margine di dubbio. Sono inoltre l'unico modo per validare il filtro contro scenari che **non sono capitati** nell'unica corsa disponibile (es. un'accelerazione bruschissima, un buco GPS di 10 secondi, una fase di accuracy pessima) senza dover uscire a correre apposta sperando che capitino da soli.

**Devono essere il primo gate di validazione, prima di qualunque corsa reale**, incluse quelle già fatte: se il filtro fallisce su uno scenario sintetico controllato, nessun numero di corse reali contro Strava lo salva, perché Strava stessa potrebbe sbagliare nello stesso identico modo nello stesso istante (i due telefoni vedono lo stesso cielo, con condizioni simili) senza che il confronto lo riveli.

### 10.1 Modello di rumore — usare i numeri reali misurati oggi, non stime a caso

Il generatore di traiettorie sintetiche deve iniettare rumore GPS calibrato su dati veri, non inventato:

| Parametro | Valore misurato oggi | Fonte |
|---|---|---|
| `horizontalAccuracyMeters` medio | 3.17 m | corsa 2026-09-10, condizioni normali |
| `horizontalAccuracyMeters` minimo | 3.00 m | idem |
| `horizontalAccuracyMeters` massimo osservato | 6.60 m (normale), fino a ~20-23 m nei primissimi fix dopo l'acquisizione GPS | idem, + campo `acc` nel file FIT (valori 18-23 osservati nei primi record) |
| Intervallo tra fix | ~1.00s medio, min 0.90s, max 1.74s (occasionali gap) | corsa 2026-09-10 |
| Distribuzione errore posizione | assumibile gaussiana isotropa attorno alla posizione vera, deviazione standard ≈ `horizontalAccuracyMeters` (approssimazione standard per GPS consumer, coerente con come viene già usata come `r` nel Kalman) | — |

Il generatore dovrebbe includere ESPLICITAMENTE fasi con accuracy degradata (18-23m) non solo il caso normale (3-6m), perché è proprio nei fix scadenti che un filtro mal tarato produce i danni peggiori.

### 10.2 Catalogo scenari da implementare (ognuno un test, o un caso parametrico dello stesso test)

| # | Scenario | Cosa verifica | Criterio di accettazione (di partenza, da affinare) |
|---|---|---|---|
| 1 | Ritmo costante prolungato (es. 60s a 8 km/h, rumore normale 3-6m) | Stabilità in stato stazionario — l'asse "costanza" richiesto dall'utente | Dopo la convergenza iniziale, `|velocità_stimata - velocità_vera| < 0.3 km/h` per ≥95% dei campioni; nessuna deriva sistematica nel tempo |
| 2 | Più velocità costanti diverse (5, 8, 12, 16 km/h) | Il filtro non ha bias dipendente dalla velocità assoluta | Stesso criterio del caso 1, ripetuto per ciascuna velocità |
| 3 | Scatto brusco (5→12 km/h in 1-2s, poi mantieni) | Reattività agli scatti — l'asse "scatti" richiesto dall'utente | Tempo di assestamento entro il 10% del nuovo valore vero: ≤ 3-5s (calibrare sul comportamento osservato oggi: il fix reale ha seguito l'accelerazione quasi in tempo reale, vedi tabella in 7) |
| 4 | Rallentamento brusco (12→5 km/h) | Simmetria della risposta (non deve reagire diversamente in decelerazione) | Stesso criterio del caso 3 |
| 5 | Rampa graduale (accelerazione progressiva su 10-15s, tipo un vero "riscaldamento") | Nessun overshoot/undershoot vistoso durante una transizione lenta | La stima resta entro una banda ragionevole (es. ±0.5 km/h) dalla vera velocità istantanea per tutta la rampa, non solo a inizio/fine |
| 6 | Burst di accuracy degradata (accuracy 18-23m per 5-10s) durante ritmo costante | Il filtro non produce spike di velocità falsi quando i fix peggiorano ma la persona non ha davvero accelerato | Nessun valore stimato che si scosti più di 1-2 km/h dalla velocità vera durante il burst, nonostante l'accuracy pessima |
| 7 | Buco GPS (nessun fix, o `reliable=false`, per 5-10s) seguito da ripresa | Comportamento in assenza di dati — deve degradare in modo sensato, non produrre valori assurdi né mai un crash/NaN | Nessun NaN/Infinity in nessun momento (assert incondizionato); dopo la ripresa, riconverge entro lo stesso tempo del caso 3 |
| 8 | Percorso con curve (cambio di direzione a velocità costante, es. una U o una serie di angoli) | I due filtri per asse (est/nord) indipendenti non generano artefatti in curva | Il modulo della velocità stimata resta vicino al valore vero durante la curva, nessun calo/picco spurio attribuibile solo al cambio di direzione |
| 9 | Caso degenere: sessione appena iniziata (1-2 fix soli) | Inizializzazione del filtro | Nessun crash, nessun NaN, velocità stimata non usata/ignorata finché il filtro non ha abbastanza fix per essere affidabile (comportamento analogo a `anchor == null` in `DistanceAccumulator`) |

**Il caso 7 (mai NaN/Infinity) e il caso 9 (inizializzazione) sono assert di sicurezza incondizionati — devono valere SEMPRE, in ogni combinazione di scenari, non solo nei test dedicati.**

### 10.3 Come strutturarli nel codice

Stesso stile già in uso nel progetto per `DistanceAccumulatorTest.kt` (JUnit, `app/src/test/java/...`, nessuna libreria di mocking). Suggerito:
- Un helper riutilizzabile tipo `syntheticTrajectory(startLat, startLon, speedProfile: List<Pair<durationSeconds, speedKmh>>, noiseStdMeters, sampleIntervalSeconds, seed)` che genera la sequenza di fix con rumore gaussiano riproducibile (seed fisso per test deterministici).
- Un file nuovo, es. `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt`, un test per riga della tabella in 10.2 (o parametrico dove sensato, es. il caso 2).
- Eseguire con `./gradlew testDebugUnitTest` come da prassi del progetto, prima di qualunque validazione su corsa reale.

### 10.4 Sequenza di validazione completa (in ordine, non saltare passaggi)

1. **Test sintetici (questa sezione)** — tutti verdi, incluso ogni scenario del catalogo 10.2. Nessuna corsa necessaria.
2. **Dual-review** del codice (self + subagent fresco indipendente), come da standing instruction del progetto per lavoro GPS-critico.
3. **Validazione sulla corsa già disponibile** (2026-09-10) — già fatta in simulazione Python (sezione 7), da ripetere sull'implementazione Kotlin reale per conferma.
4. **Almeno una seconda corsa reale** contro Strava, in condizioni diverse dalla prima se possibile (es. percorso con più curve, o con un tratto sotto copertura/urbano denso) — questa indagine si basa su una sola corsa, non basta per dire "chiuso".

Solo dopo tutti e 4 i passaggi si può considerare il fix pronto per sostituire `location.speed` in produzione.

### 10.5 Se in futuro si affronta l'accelerometro (sezione 9)

Lo stesso approccio (traiettorie sintetiche a verità nota) si applica, ma con una complicazione: il rumore dell'accelerometro-in-tasca/fascia non è caratterizzato oggi con numeri reali come quello GPS (sezione 10.1) — andrebbe prima registrato un campione di dati accelerometro grezzi in condizioni reali (tasca, poi fascia) per costruire un modello di rumore altrettanto grounded, prima di poter scrivere test sintetici altrettanto affidabili. Se si vuole una validazione fisica indipendente dai dati sintetici, il giro di pista certificato (400m esatti, ritmo costante con metronomo) resta l'opzione più forte, perché isola la variabile "accuratezza del conteggio passi" dalla variabile "correttezza della fusione con il GPS".
