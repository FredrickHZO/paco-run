# Paco Run — Modalità Custom (design)

## Contesto e priorità

Paco Run è destinata al Play Store come biglietto da visita dell'autore: il livello di attenzione richiesto è "maniacale", non "funziona e basta". Ogni decisione successiva su questa funzionalità va misurata con questo metro.

Oggi esistono due modalità, entrambe rigide: Costante (un solo ritmo, per sempre) e Cammina-Corri (riscaldamento opzionale, intervalli corsa/cammino a tempo, più un limite di distanza opzionale — il "defaticamento" — dopo il quale la struttura si ferma e si continua a camminare liberamente). Questo documento introduce una terza modalità, **Custom**, componibile liberamente dall'utente, e semplifica le due modalità esistenti di conseguenza.

## Ambito di questo documento

**Dentro**: modello dati della routine Custom, motore/sequenziamento tra spezzoni, regole delle cue vocali, integrazione TTS, modello di salvataggio e punto di ingresso dalla Home.

**Fuori, esplicitamente rimandato**:
- Il layout visivo dell'editor Custom (come si aggiungono/riordinano/modificano gli spezzoni sullo schermo) — richiede una sessione di design dedicata.
- Un toggle "loop dell'intera routine" — voluto in futuro, non ora. Il motore va scritto in modo che aggiungerlo poi non richieda una riscrittura (vedi "Sequenziamento").
- La migrazione delle cue di Costante e Cammina-Corri dall'audio pre-registrato al TTS — voluta in futuro per uniformità di voce in tutta l'app, ma non in questo lavoro. L'integrazione TTS del Custom va comunque progettata come base riusabile per quella migrazione, non come soluzione usa-e-getta.

## Semplificazione di Costante e Cammina-Corri

Cammina-Corri perde la sezione "Defaticamento" (`RoutineEndSection`, i campi `routineKm`/`routineUnlimited` e la relativa logica in `RoutineEngine`): chi vuole quella combinazione (intervalli strutturati seguiti da una camminata libera) la ottiene componendo una routine Custom con uno spezzone Cammina-Corri seguito da uno spezzone Costante aperto. Costante resta invariata (nessun riscaldamento: si è verificato che, non avendo Costante una fase successiva più impegnativa a cui prepararsi, un riscaldamento allo stesso ritmo del resto sarebbe un'operazione senza alcun effetto osservabile).

Questa semplificazione è indipendente dal resto del documento e può essere implementata separatamente, prima o dopo il Custom.

## Modello dati

```
RoutineCustom:
  nome: String
  spezzoni: List<Spezzone>  // ordinata, almeno 1 elemento

Spezzone:
  nome: String
  tipo: COSTANTE | CAMMINA_CORRI

  // tipo == COSTANTE
  distanzaKm: Float
  ritmo: SpeedRange

  // tipo == CAMMINA_CORRI
  distanzaKm: Float          // lunghezza totale dello spezzone
  corsaSecondi: Int
  camminataSecondi: Int
  ritmoCorsa: SpeedRange
  ritmoCammino: SpeedRange
```

I parametri per tipo sono esattamente quelli già usati oggi da Costante e Cammina-Corri — nessun nuovo concetto di ritmo o durata, solo incapsulamento con nome e distanza propri dello spezzone.

## Sequenziamento

Un "direttore" avanza tra gli spezzoni in base alla distanza totale percorsa dall'inizio dello spezzone corrente:

- Per ogni spezzone tranne l'ultimo: al raggiungimento della `distanzaKm` dichiarata, si passa allo spezzone successivo.
- L'ultimo spezzone della lista è sempre aperto: la sua `distanzaKm` è puramente informativa (usata solo per la cue vocale), non fa mai scattare una transizione. Non esiste una fine automatica dell'intera routine Custom — coerente con Costante e Cammina-Corri, dove la sessione termina sempre e solo per azione manuale dell'utente.
- All'interno di ogni spezzone, il comportamento (cue di ritmo, direzione, transizioni corsa/cammino) è delegato alla stessa logica già esistente per il tipo corrispondente (`RoutineEngine` per Costante e Cammina-Corri), riavviata con i parametri dello spezzone attivo.

Requisito di implementazione: il direttore deve avanzare tramite un riferimento esplicito allo "spezzone successivo" (non un semplice controllo "sono all'ultimo indice della lista?") in modo che un futuro toggle "loop dell'intera routine" (il puntatore "successivo" dell'ultimo spezzone punta al primo invece che restare aperto) sia un cambiamento locale, non una riscrittura della macchina a stati.

## Cue vocali

All'ingresso di ogni spezzone:
1. Annuncio del nome dello spezzone.
2. Annuncio della distanza (es. "12 chilometri").
3. Solo se il tipo è CAMMINA_CORRI, **solo alla primissima volta** che si entra in quello spezzone (non ai successivi cambi corsa/cammino all'interno dello stesso spezzone): annuncio delle durate degli intervalli (es. "corri 30 secondi, cammina 1 minuto").

Dopo l'annuncio iniziale, le cue esistenti per il tipo attivo (troppo lento/veloce, cambio fase, ecc.) continuano invariate.

## Integrazione TTS

Il nome dello spezzone è testo libero scelto dall'utente: non può essere reso con clip audio pre-registrate a vocabolario chiuso come `AudioCue` oggi. Le cue del Custom (nome, distanza, durate) sono generate interamente con Android TextToSpeech, costruendo la frase completa a runtime.

Costante e Cammina-Corri **non vengono toccate**: restano sul sistema `AudioCue`/`CuePlayer` con audio pre-registrato, invariato. Il componente TTS va però isolato dietro un'interfaccia paragonabile all'attuale `AudioDucking`/`CuePlayer` (stesso trattamento di audio focus, stessa gestione di interruzione per chiamata in arrivo già costruita per `CuePlayer`), in modo da poter diventare in futuro il motore vocale anche per le altre due modalità senza una riscrittura.

## Salvataggio e ingresso da Home

Le routine Custom sono entità multiple, con nome, salvabili e selezionabili — a differenza delle preferenze attuali di Costante/Cammina-Corri, che sono un singolo oggetto in DataStore. Serve quindi un repository con operazioni di creazione, lettura, modifica ed eliminazione, chiave per routine (id) più un campo di "ultimo utilizzo" per l'ordinamento.

Dalla Home, il pulsante/pillola "Custom" apre sempre un elenco delle routine salvate, ordinato per uso più recente in cima. Da quell'elenco si crea una nuova routine, si modifica o elimina una esistente, si seleziona quella da attivare per la sessione.
