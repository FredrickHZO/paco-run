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

## 5. Consolidamento del `SpeedEstimator` diagnostico

Oggi `RunSessionService` mantiene un `SpeedEstimator` separato, usato solo per scrivere `pr:speedEstimatorKmh` nel GPX. Con `KalmanSpeedEngine` dentro `SessionCoordinator`, avremmo due istanze indipendenti che calcolano la stessa cosa in parallelo — spreco e rischio (piccolo) di disallineamento. **Consolidare in una sola istanza**: `RunSessionService` costruisce un `KalmanSpeedEngine`, lo passa a `SessionCoordinator`, e legge il valore per il GPX da `RunSessionState.speedKmh` (già pubblicato dopo ogni fix) invece di mantenere un secondo calcolo a parte.

## 6. Il motore di default — la vera decisione di questa fase

`SessionCoordinator`'s costruttore: `private val speedEngine: SessionSpeedEngine = KalmanSpeedEngine()` — **`KalmanSpeedEngine` diventa il default**, non `DopplerSpeedEngine`. Questo è il punto: lo scopo dichiarato di questa fase è collegare `SpeedEstimator` alla produzione per davvero, non solo renderlo collegabile. Da qui in poi, ogni corsa userà il nuovo calcolo per i cue dal vivo.

## 7. `DopplerSpeedEngine` non è codice morto — è una rete di sicurezza temporanea, con criterio di rimozione esplicito

`DopplerSpeedEngine` resta nel codice dopo questa fase, selezionabile (basta cambiare quale implementazione viene passata a `SessionCoordinator`), per un motivo preciso: `SpeedEstimator` è stato validato su due corse reali finora, entrambe nello stesso tipo di ambiente (campagna aperta, spesso lo stesso percorso — l'utente non ha modo di variare molto le condizioni). Se in produzione emergesse qualcosa di inatteso, poter tornare al comportamento di oggi cambiando un parametro (non resuscitando codice da git sotto pressione) è una rete di sicurezza reale, non un residuo dimenticato.

**Criterio di rimozione, esplicito:** dopo **5 corse reali aggiuntive** con `KalmanSpeedEngine` attivo come motore vero (non diagnostico), l'utente decide esplicitamente se rimuovere `DopplerSpeedEngine` o tenerlo ancora — non è una rimozione automatica al raggiungimento del numero, è un punto di rivalutazione concordato. Tracciare questo conteggio (es. in `docs/superpowers/specs/2026-09-11-precision-roadmap.md`, sezione stato delle decisioni) man mano che le corse vengono validate.

## 8. Cosa resta da testare (il gap di integrazione mai chiuso finora)

`SpeedEstimator` è testato benissimo da solo, e le sue corse reali confermano l'algoritmo — ma **mai integrato dentro la macchina a stati di `SessionCoordinator`** (outage/conferma ritmo/routine). Il piano di implementazione dovrà includere:
- Test unitari in stile `SessionCoordinatorTest.kt` con `KalmanSpeedEngine` collegato, che verificano il comportamento su outage/recovery (il motore si resetta in sincrono con la distanza) e sulla conferma del cambio ritmo (`confirmationSamples`) con valori realistici.
- Un replay di sanità di almeno una delle due corse reali già disponibili attraverso il vero `SessionCoordinator` integrato (non solo `SpeedEstimator` da solo) — per confermare che i cue emessi con la nuova fonte di velocità abbiano senso, non solo che il numero grezzo sia più preciso.

## 9. Fuori scope per questa fase

- Rimozione di `DopplerSpeedEngine` (rimandata al criterio della sezione 7).
- Qualunque lavoro della zona GPS (Fase 1, branch `feature/gps-precision`) o inerziale (Fase 2).
- G12 (Doppler grezzo per satellite) — nome simile a "Doppler" ma concetto diverso (dati satellitari grezzi, non `location.speed`), non correlato a `DopplerSpeedEngine` di questo documento.
