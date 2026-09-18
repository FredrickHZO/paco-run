# Findings in sospeso — review del 2026-09-10

Branch: `feature/paco-run-m3e` (live). Commit di riferimento al momento della review: `3a942dd`.

Nessuno di questi è ancora stato corretto. Ordine: gravità decrescente.

## Verifica sul campo (2026-09-10, corsa di prova contro Strava)

Corsa reale tracciata in parallelo da Paco Run e dall'app Strava (telefono), confrontate via export GPX:
- `paco-run-2026-09-10-1122.gpx` (5196 punti, 1 solo `reliable=false`, nessun NaN/Infinity trovato — bug #2 non manifestato in questa corsa)
- `Corsa_dell_ora_di_pranzo.gpx` da Strava (5158 punti)

Risultati:

| Fonte | Distanza | Differenza vs Strava |
|---|---|---|
| Strava | 9.669 km | — |
| Paco Run, somma haversine su tutti i fix GPS grezzi | 9.643 km | -0.28% |
| Paco Run, distanza calcolata con la logica reale di `DistanceAccumulator` (simulata in Python replicando `RunSessionStore.kt`) | 9.606 km | -0.65% |

**Conferma diretta del bug #3** (soglia jitter con accuracy dell'ancora congelata): sulla simulazione della logica reale dell'accumulatore, **2875 movimenti su ~5195 punti (oltre metà dei fix)** sono stati soppressi come "jitter", per ~6 km di spostamento cumulato ignorato durante la corsa — quasi tutto recuperato non appena la soglia veniva superata, da cui la differenza finale contenuta (-0.65%, non drammatica ma misurabile e sistematica, sempre per difetto).

Il file duplicato del bug #1 non si è manifestato in questa corsa (un solo file GPX Paco Run trovato in Download) — non è chiaro se il secondo export abbia sovrascritto il primo con lo stesso nome o se in questo caso `onDestroy()` non abbia ritriggerato l'export; da verificare quando si corregge il bug.

Script di analisi usato: `/tmp/claude-1000/-home-fredworkstation-Documents-Development-paco-run/5f5b06bc-23d2-4898-9971-6d4665167eda/scratchpad/compare_gpx.py` (temporaneo, non nel repo).

---

## 1. Export GPX duplicato ad ogni stop

**File:** `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt:191` (chiamata) e `:216` (`exportTrackAsGpx()`)
**Verdetto review:** CONFIRMED

**Problema:** `stopSession()` viene invocato **due volte** per ogni stop iniziato dall'utente, ma `trackPoints` non viene mai svuotato tra le due chiamate (viene ripulito solo in `beginTracking()`, cioè all'avvio di una sessione).

**Sequenza esatta:**
1. L'utente preme Stop → `onStartCommand(ACTION_STOP)` chiama `stopSession()` → `exportTrackAsGpx()` scrive il GPX completo su Downloads.
2. `stopSession()` termina con `stopSelf()`.
3. Android chiama `onDestroy()`, che chiama **di nuovo** `stopSession()` incondizionatamente.
4. `trackPoints` contiene ancora tutti i punti (non è stato svuotato) → `exportTrackAsGpx()` viene rieseguito, genera un secondo file quasi identico (nome calcolato da `GpxExporter.fileNameFor`, basato sul timestamp del primo punto — invariato tra le due chiamate, quindi rischio di sovrascrittura o nome duplicato a seconda di come MediaStore gestisce il conflitto).

**Effetto pratico:** ogni corsa completata lascia 2 file GPX (o 1 sovrascritto/rinominato in modo confuso) nella cartella Download, invece di 1.

**Nota per il confronto Strava di oggi:** se trovi due file GPX per la stessa corsa in Download, il contenuto dovrebbe essere identico — prendi uno qualsiasi dei due per il confronto.

---

## 2. NaN/Infinity scritti crudi nell'XML del GPX

**File:** `app/src/main/java/dev/fred/pacorun/export/GpxWriter.kt:45`
**Verdetto review:** CONFIRMED

**Problema:** i campi speed/accuracy di `TrackPoint` finiscono nell'XML senza controllo `isFinite()`.

**Causa:** in `RunSessionService.onLocation()` (righe ~155-163), `TrackPoint` viene costruito da `location.speed`/`accuracy`/`speedAccuracyMetersPerSecond` usando solo i controlli di presenza `hasSpeed()`/`hasAccuracy()`/`hasSpeedAccuracy()`. I controlli `isFinite()` che `LocationSampleMapper` applica altrove servono solo a determinare il flag `reliable`, non filtrano cosa finisce in `trackPoints`.

**Scenario di rottura:** se lo stack GNSS del telefono riporta anche solo una volta uno speed/accuracy non finito (documentato come possibile su alcuni stack OEM), `GpxWriter` produce `<pr:speedMps>NaN</pr:speedMps>`, che è XML `xs:decimal` non valido — può rompere parser GPX rigorosi o l'importazione su Strava/altri strumenti di confronto.

**Rilevante oggi:** se il file GPX esportato da Paco Run non si apre correttamente in uno strumento di confronto, controllare prima questo.

---

## 3. Soglia di jitter con accuracy dell'ancora congelata e non aggiornata

**File:** `app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt:142`
**Verdetto review:** CONFIRMED

**Problema:** `jitterThreshold` usa l'accuratezza orizzontale della fix "ancora" (frozen anchor) così com'era nel momento in cui è stata congelata — anche se nel frattempo l'accuratezza GPS reale è migliorata.

**Scenario di rottura concreto:**
- La sessione parte subito dopo il fix GPS, con accuracy = 18m (l'ancora si congela qui).
- Il GPS migliora rapidamente a 3m mentre l'utente cammina/corre a ~1.4 m/s.
- Poiché l'ancora resta deliberatamente congelata finché il movimento resta sotto soglia, `jitterThreshold` continua a combinare l'accuracy vecchia (18m) dell'ancora con quella nuova (buona) della fix corrente: `threshold = max(2, (18+3)*0.5) = 10.5m`.
- I primi secondi di movimento reale (es. 4m, poi 8m cumulati) vengono scartati come rumore finché la somma non supera 10.5m.

**Effetto pratico:** la distanza viene sottostimata nei primi secondi di ogni sessione che parte con un fix GPS iniziale scadente — rilevante dato che la precisione è la priorità dichiarata dell'app. Nessun test esistente in `DistanceAccumulatorTest.kt` copre questo caso (ancora impostata con accuracy scarsa, poi accuracy che migliora).

**Rilevante oggi:** questo potrebbe spiegare una sottostima della distanza totale rispetto a Strava, soprattutto se la corsa di oggi è iniziata con un fix GPS debole (es. partenza da interno/sotto copertura).

---

## 4. Export GPX su Thread grezzo, rischio di perdita silenziosa

**File:** `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt:216`
**Verdetto review:** PLAUSIBLE (non confermato al 100%, ma rischio concreto)

**Problema:** l'export gira su un `Thread` semplice, senza alcuna garanzia di sopravvivenza se il processo viene terminato.

**Scenario di rottura:** `stopSession()` chiama `stopForeground(STOP_FOREGROUND_REMOVE)` e `stopSelf()` immediatamente dopo aver avviato il Thread di export. Se il sistema operativo recupera il processo (ora deprioritizzato, essendo appena terminato il foreground service) prima che la scrittura su MediaStore sia completata — plausibile sotto pressione di memoria, proprio nel momento in cui un foreground service smette di esistere — la scrittura viene persa silenziosamente: nessun file, nessun errore mostrato all'utente.

**Rilevante oggi:** se un file GPX manca del tutto per una corsa completata (non solo duplicato/malformato ma assente), questa è la causa più probabile.

---

## 0. [NUOVO, priorità alta] `location.speed` (Doppler) comprime la velocità reale — impatta direttamente `PaceEvaluator`

**Indagine completa, approcci scartati, design della soluzione scelta e piano di test: vedi `docs/superpowers/specs/2026-09-10-speed-accuracy-design.md`.** Le sezioni sotto restano come riepilogo del finding originale; per il design/fix fare riferimento a quel documento, non a questo.

**File:** `app/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt` (produce `speedKmh` da `location.speed`) → consumato direttamente da `app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt:accept()` (`sample.speedKmh`), che è ciò che decide TOO_SLOW/GOOD/TOO_FAST e genera i cue vocali.

**Scoperto tramite:** confronto sul campo 2026-09-10 (stessa corsa Strava vs Paco Run del finding sopra), non da review statica del codice.

**Problema:** la velocità "dispositivo" (`location.speed`, derivata Doppler/carrier da Android) **non** combacia bene con la velocità reale, mentre la velocità derivata dalla posizione GPS di Paco Run sì:

| Confronto (finestre da 10s, 518 campioni) | MAE | RMSE | correlazione |
|---|---|---|---|
| Posizione Paco Run (derivata) vs Strava (derivata) | 0.16 km/h | 0.21 km/h | 0.986 |
| **`location.speed` Paco Run vs Strava (derivata)** | **0.56 km/h** | **0.72 km/h** | **0.887** |

Test di sfasamento temporale (da -30s a +30s) esclude un problema di ritardo/lag: il match migliore resta a 0s.

Regressione lineare `device_speed = 0.685 * strava_speed + 1.726` → non è un bias costante, è una **compressione**: `location.speed` schiaccia i valori verso la media, sottostimando le fasi veloci (i 5 scarti peggiori sono tutti negativi, fino a -2.85 km/h durante tratti di corsa) e leggermente sovrastimando quelle lente.

**Perché è il finding più importante di tutti (dato l'uso reale dell'app):** `PaceEvaluator.accept()` usa `sample.speedKmh` — cioè esattamente questo valore compresso — per decidere se emettere "troppo lento"/"troppo veloce". Durante una fase RUN, l'app può segnalare "troppo lento" anche quando il ritmo reale è corretto, semplicemente perché il chip GPS del telefono smussa la lettura Doppler.

**Direzione di indagine per quando si riprende (non ancora implementata):** valutare se calcolare la velocità istantanea dalla derivata posizione/tempo (come `pr_pos_speed_kmh` in questa analisi, che combacia quasi perfettamente con Strava) invece di affidarsi a `location.speed`, magari con uno smoothing leggero per non reintrodurre rumore. Va verificato se questo comporti trade-off (es. più rumore a bassa velocità, dove la derivata posizione soffre di più rispetto al Doppler).

**Simulazione del fix (velocità causale da posizione, stessa finestra su Paco Run e Strava per confronto onesto):**

| Finestra trailing | MAE | Correlazione | Note |
|---|---|---|---|
| 1s | 0.833 km/h | 0.710 | troppo rumoroso, peggio di oggi |
| 2s | 0.569 km/h | 0.876 | ~uguale a oggi |
| 3s | 0.429 km/h | 0.924 | già meglio di oggi |
| **5s** | **0.298 km/h** | **0.960** | **dimezza l'errore, reattività ancora ragionevole — candidato consigliato** |
| 8s | 0.212 km/h | 0.979 | ancora meglio, più lag |
| 10s | 0.182 km/h | 0.984 | quasi perfetto ma troppo lento a reagire per i cue vocali |
| **oggi (`location.speed`)** | **0.591 km/h** | **0.865** | baseline attuale |

Implementazione concettuale: un piccolo buffer trailing di posizioni/timestamp recenti (stesso pattern già usato per `anchor`/`lastFix` in `DistanceAccumulator`), velocità = distanza totale nel buffer / tempo trascorso nel buffer. Raccomandazione preliminare: finestra 5s. Da trattare come modifica GPS-critica: TDD + dual-review prima di considerarla chiusa, verificata finora su una sola corsa.

**Dati grezzi e script:** `/tmp/claude-1000/-home-fredworkstation-Documents-Development-paco-run/5f5b06bc-23d2-4898-9971-6d4665167eda/scratchpad/compare_speed.py` (temporaneo, non nel repo — copiarlo altrove se serve prima che lo scratchpad venga ripulito).

---

## Altri findings minori dalla stessa review (non urgenti, cleanup/efficienza)

Per completezza, non serve agire ora:

5. `ActiveRunScreen.kt:130` — `phaseTextColor()` duplica la stessa mappatura colore di `SessionHero.kt`'s `colorFor()`, rischio di disallineamento futuro.
6. `SessionHero.kt:115` — la logica color/shape del morph non scala oltre i 3 `HeroKind` attuali senza raddoppiare i branch a mano.
7. `CuePlayer.kt:173` — `closeInternal()` non resetta il flag `playing`, a differenza di `stopAndDropOnFocusLoss()`; innocuo ora, rischioso per estensioni future.
8. `AmbientShapeField.kt:58` — `lowerPath`/bounds ricalcolati ad ogni frame di animazione invece di essere in `remember{}`.
9. `RunSessionService.kt:151` — nuova istanza di `LocationSampleMapper` allocata ad ogni callback GPS invece di riusarne una.
