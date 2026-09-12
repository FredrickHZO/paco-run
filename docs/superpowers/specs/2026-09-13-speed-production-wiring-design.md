# Fase 0.5 — Collegare SpeedEstimator alla produzione (velocità reale)

**Branch:** `feature/paco-run-m3e` (live), da implementare su un branch dedicato a parte (Fase 0.5). **Data:** 2026-09-13. **Scopo:** finora `SpeedEstimator` è stato validato due volte su corse reali (riduzione errore -59% e -52% rispetto a `location.speed`) ma solo in parallelo diagnostico — il suo output non ha mai influenzato un vero ciclo di cue/stato. Questo documento disegna come collegarlo per davvero, seguendo lo stesso pattern interfaccia+adattatore già usato per `SessionRoutineEngine`/`SessionPaceEvaluator`/`SessionDistanceEngine` (quest'ultimo appena introdotto nella Fase 0).

Questo è il secondo di una serie di refactor "un'astrazione alla volta" — dopo la distanza (Fase 0), ora la velocità. Stesso stile, stessa disciplina.

## 1. Cosa cambia, e per chi

La velocità calcolata da `SessionCoordinator` non alimenta solo i cue vocali — alimenta **tre consumatori**:
1. `PaceEvaluator` (i cue "troppo lento"/"troppo veloce")
2. `RunSessionState.speedKmh` (il numero mostrato a schermo)
3. `RoutineInput.currentReliableSpeedKmh` (input al motore delle routine)

Passando da `location.speed` a `SpeedEstimator`, tutti e tre cambiano fonte insieme — comportamento voluto, non un effetto collaterale da limitare.

## 2. L'interfaccia: `SessionSpeedEngine`

```kotlin
interface SessionSpeedEngine {
    fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double
    fun breakChain(): Double
}
```

**Perché questa firma e non altre (già discusso e scartato):**
- **Non prende `android.location.Location` grezzo.** Il progetto ha già la regola (vista in `LocationSampleMapper`, che offre una seconda `map()` a soli parametri primitivi proprio per questo) di tenere i tipi Android fuori da qualunque interfaccia di dominio testabile su JVM normale, senza Robolectric. Prendere `Location` qui romperebbe quella regola per la prima volta in questo codice.
- **Non nasconde il dato Doppler dentro `LocationPosition`.** `LocationPosition` rappresenta una posizione, non una velocità — infilarci un campo `speedMps` solo per far tornare una firma sarebbe un'astrazione forzata.
- **Prende `sample: LocationSample` intero** (non un singolo campo estratto) perché è già un tipo di dominio esistente, già puro, già testato — nessun tipo nuovo da inventare. Il motore Doppler userà solo `sample.speedKmh`, ignorando il resto.
- **`reliable` esplicito**, non implicito dentro `sample`, per coerenza con `SessionDistanceEngine.accept(position, reliable)` — e perché il giudizio di affidabilità che `SessionCoordinator` passa (`actualReliable`) non è sempre identico a `sample.reliable` da solo (richiede anche `position != null`).

## 3. Le due implementazioni

**`DopplerSpeedEngine`** (nuovo, piccolo file o classe privata) — il comportamento di oggi, portato dietro l'interfaccia:
```kotlin
class DopplerSpeedEngine : SessionSpeedEngine {
    override fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double =
        sample.speedKmh.toDouble()
    override fun breakChain(): Double = 0.0
}
```
Nessuna "catena" da rompere per un dato Doppler istantaneo — `breakChain()` è un no-op che restituisce 0.0 solo per rispettare la firma.

**`KalmanSpeedEngine`** (adattatore, stesso pattern di `RoutineEngineAdapter`/`PaceEvaluatorAdapter` — la terza volta che questo progetto usa questo identico pattern, non uno nuovo):
```kotlin
class KalmanSpeedEngine(private val speedEstimator: SpeedEstimator = SpeedEstimator()) : SessionSpeedEngine {
    override fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double =
        speedEstimator.accept(position, reliable)
    override fun breakChain(): Double = speedEstimator.breakChain()
}
```

## 4. Sincronizzazione con `SessionDistanceEngine` (già in `SessionCoordinator`)

`speedEngine.accept(...)` e `speedEngine.breakChain()` vanno chiamati **negli stessi identici punti** dove oggi vengono chiamati `distanceEngine.accept(...)`/`distanceEngine.breakChain()` — stesso `actualReliable`, stesso `enteredOutage`. Non si inventa un secondo giudizio di affidabilità da conciliare: i due motori restano sincronizzati perché condividono lo stesso segnale già esistente e già collaudato.

**Correzione importante (trovata rileggendo con più attenzione, non presente nella prima stesura):** questo NON basta per la velocità, a differenza della distanza. Oggi `SessionCoordinator` ha un terzo caso, oltre a "fix affidabile" e "outage" — la finestra di grazia `timerRetainsFreshGps` (nessun nuovo fix, GPS considerato ancora "fresco" per qualche secondo), in cui riusa **l'ultimo campione affidabile** (`latestReliableSample`) invece di azzerare tutto di colpo. Per la distanza questo non è un problema (0 metri aggiunti in quella finestra è corretto). Per la velocità sarebbe un problema: senza gestirlo esplicitamente, in quella finestra il valore andrebbe preso da `latestReliableSample.speedKmh` — che oggi è il campo Doppler grezzo, non l'ultimo output del motore Kalman. Risultato: proprio nei momenti di "grazia", il numero mostrato/usato per i cue tornerebbe silenziosamente al vecchio Doppler invece di restare sul valore Kalman più recente.

**Fix:** `SessionCoordinator` deve tenere un proprio `latestEngineSpeedKmh: Double` (nome indicativo), aggiornato ogni volta che `speedEngine.accept(...)` viene chiamato con successo, e riusato al posto di `latestReliableSample.speedKmh` durante `timerRetainsFreshGps`. Va azzerato/reimpostato negli stessi punti in cui oggi si azzera `latestReliableSample` (entrata in outage, ecc.), per restare coerente con lo stesso ciclo di vita.

## 5. Consolidamento del `SpeedEstimator` diagnostico — OBBLIGATORIO in questa fase, non rimandabile

**Corretto due volte ora — la prima proposta (leggere da `RunSessionState.speedKmh`) era sbagliata, ma "rimandarlo" era sbagliato anche peggio.** Motivo: se le due istanze (diagnostica e di produzione) restano separate, ricevono input diversi (quella diagnostica gira su ogni fix, quella di produzione solo sui fix che `SessionCoordinator` considera affidabili) — con input diversi, i due calcoli Kalman **divergono nel tempo**, anche partendo dallo stesso algoritmo. Il file GPX smetterebbe di rappresentare quello che l'app usa davvero per i cue, **falsando esattamente le 5 corse di validazione della sezione 7** — che devono validare il comportamento reale, non un calcolo parallelo scollegato.

**Soluzione (pulita, non quella scartata prima):**
- Una sola istanza di `SpeedEstimator`, condivisa tra produzione e diagnostica.
- `SessionCoordinator` guadagna un parametro opzionale nel costruttore, stesso stile già in uso per `onCues`/`publish`: `onSpeedComputed: (Double?) -> Unit = {}` — invocato ogni volta che `speedEngine.accept(...)` viene effettivamente chiamato con successo (quindi con lo stesso identico valore usato per pace/stato/routine), non invocato altrimenti.
- `RunSessionService` passa questo callback per catturare il valore e scriverlo nel `TrackPoint` del GPX, al posto del secondo `SpeedEstimator` indipendente che ha oggi.

**Conseguenza esplicita da accettare, non un difetto:** il campo `pr:speedEstimatorKmh` nel GPX, da questa fase in poi, sarà presente solo nei fix che `SessionCoordinator` considera affidabili — non più su ogni fix come nella versione solo-diagnostica di oggi. È corretto: da qui in poi il file deve rispecchiare esattamente cosa l'app ha usato dal vivo, non un calcolo scollegato che gira sempre.

**Limite noto, accettato deliberatamente (2026-09-13), da ricordare quando si guarderà a un test con GPS degradato:** il gate anti-falso-allarme (crollo di accuratezza RELATIVO, es. raddoppio rispetto al recente) scatta di solito ben sotto la soglia che rende un fix del tutto "inaffidabile" (fissata a 20m assoluti) — quindi uno scenario di GPS "ballerino" ma non completamente perso (città, sotto alberi) resta pienamente visibile nel GPX, il motore continua a essere chiamato e il dato continua a essere scritto. **Quello che invece NON sarà più visibile nel file: i momenti di GPS completamente perso** (inaffidabile del tutto) — lì il motore va in pausa, nessuna riga scritta, a differenza di oggi dove la versione diagnostica scriveva comunque qualcosa anche in quei momenti. Deciso di non aggiungere un segnale minimo per quei momenti (costo/beneficio non ritenuto necessario) — se un giorno servirà davvero analizzare un'interruzione GPS totale nel dettaglio dal file esportato, questo limite andrà rivisto.

## 6. Il motore di default — la vera decisione di questa fase

`SessionCoordinator`'s costruttore: `private val speedEngine: SessionSpeedEngine = KalmanSpeedEngine()` — **`KalmanSpeedEngine` diventa il default**, non `DopplerSpeedEngine`. Questo è il punto: lo scopo dichiarato di questa fase è collegare `SpeedEstimator` alla produzione per davvero, non solo renderlo collegabile. Da qui in poi, ogni corsa userà il nuovo calcolo per i cue dal vivo.

## 7. `DopplerSpeedEngine` non è codice morto — è una rete di sicurezza temporanea, con criterio di rimozione esplicito

`DopplerSpeedEngine` resta nel codice dopo questa fase, selezionabile (basta cambiare quale implementazione viene passata a `SessionCoordinator`), per un motivo preciso: `SpeedEstimator` è stato validato su due corse reali finora, entrambe nello stesso tipo di ambiente (campagna aperta, spesso lo stesso percorso — l'utente non ha modo di variare molto le condizioni). Se in produzione emergesse qualcosa di inatteso, poter tornare al comportamento di oggi cambiando un parametro (non resuscitando codice da git sotto pressione) è una rete di sicurezza reale, non un residuo dimenticato.

**Criterio di rimozione, esplicito:** dopo **5 corse reali aggiuntive** con `KalmanSpeedEngine` attivo come motore vero (non diagnostico), l'utente decide esplicitamente se rimuovere `DopplerSpeedEngine` o tenerlo ancora — non è una rimozione automatica al raggiungimento del numero, è un punto di rivalutazione concordato. Tracciare questo conteggio (es. in `docs/superpowers/specs/2026-09-11-precision-roadmap.md`, sezione stato delle decisioni) man mano che le corse vengono validate.

## 8. Cosa resta da testare (il gap di integrazione mai chiuso finora)

`SpeedEstimator` è testato benissimo da solo, e le sue corse reali confermano l'algoritmo — ma **mai integrato dentro la macchina a stati di `SessionCoordinator`** (outage/conferma ritmo/routine). Il piano di implementazione dovrà includere:
- Test unitari in stile `SessionCoordinatorTest.kt` con `KalmanSpeedEngine` collegato, che verificano il comportamento su outage/recovery (il motore si resetta in sincrono con la distanza) e sulla conferma del cambio ritmo (`confirmationSamples`) con valori realistici.
- **Test dedicato per la finestra di grazia (`timerRetainsFreshGps`, sezione 4):** un tick senza nuovo fix subito dopo un fix Kalman valido deve riportare `latestEngineSpeedKmh` (il valore Kalman), non il vecchio campo Doppler di `latestReliableSample` — questo è il bug trovato in revisione, va coperto esplicitamente da un test che lo avrebbe fatto fallire prima della correzione.
- Un replay di sanità di almeno una delle due corse reali già disponibili attraverso il vero `SessionCoordinator` integrato (non solo `SpeedEstimator` da solo) — per confermare che i cue emessi con la nuova fonte di velocità abbiano senso, non solo che il numero grezzo sia più preciso.

## 9. Fuori scope per questa fase

- Rimozione di `DopplerSpeedEngine` (rimandata al criterio della sezione 7).
- Qualunque lavoro della zona GPS (Fase 1, branch `feature/gps-precision`) o inerziale (Fase 2).
- G12 (Doppler grezzo per satellite) — nome simile a "Doppler" ma concetto diverso (dati satellitari grezzi, non `location.speed`), non correlato a `DopplerSpeedEngine` di questo documento.
