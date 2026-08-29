# Paco Run — Routine MVP Design

## Contesto e priorità

Questo documento estende e, in caso di conflitto, sostituisce `2026-08-29-paco-run-design.md`.

La priorità immediata è produrre un'app funzionale da installare sul Pixel 9a. La UI Material 3 Expressive, i mockup e la rifinitura grafica sono rinviati a una sessione successiva. La UI provvisoria deve essere soltanto leggibile e sufficiente per configurare, avviare e terminare una sessione.

Restano validi i vincoli precedenti: app personale, offline, nessun account, cloud, mappa, storico, statistica, pubblicità o supporto ad altri telefoni.

## Modalità

L'utente sceglie una delle due modalità prima di avviare la sessione.

### Andamento costante

Usa un solo profilo con velocità minima e massima. Il controllo del ritmo e gli avvisi continuano fino a `Termina`.

### Walk–Run

La sessione contiene fino a tre fasi:

1. andamento costante iniziale, definito da una distanza;
2. routine temporizzata con alternanza Run e Walk;
3. andamento costante finale fino a `Termina`.

La distanza iniziale può essere zero; in quel caso la routine parte immediatamente.

La routine può essere:

- senza fine, quindi alterna Run e Walk fino a `Termina`;
- limitata a una distanza configurata. Al raggiungimento della distanza, interrompe immediatamente l'intervallo attuale, annuncia `Routine end` e passa alla fase costante finale.

## Profili di velocità

Esistono tre profili configurabili:

- **Costante:** velocità minima e massima usate nelle fasi iniziale e finale;
- **Run:** velocità minima e massima usate negli intervalli di corsa;
- **Walk:** velocità minima e massima usate negli intervalli di camminata.

Un toggle `Usa le soglie costanti per Walk`, attivo per impostazione predefinita, fa usare al profilo Walk le soglie Costante. Se disattivato, l'utente inserisce minimo e massimo Walk dedicati.

Ogni profilo deve avere minimo strettamente inferiore al massimo. Le velocità non hanno limiti artificiali.

## Durate e distanze

- Durata Run: minuti e secondi configurabili, totale minimo 1 secondo.
- Durata Walk: minuti e secondi configurabili, totale minimo 1 secondo.
- Distanza costante iniziale: chilometri configurabili, minimo 0.
- Distanza della routine: chilometri positivi oppure `Senza fine`.

La routine inizia sempre con un intervallo Run. Dopo ogni Run segue Walk, poi nuovamente Run.

La distanza viene accumulata soltanto da fix GPS affidabili e consecutivi. I segmenti con fix vecchi, incerti, anomali o non affidabili non incrementano la distanza. Il servizio mantiene separatamente distanza totale della sessione e distanza percorsa dall'inizio della routine.

## Macchina degli stati

Un `RoutineEngine` puro e indipendente da Android possiede questi stati:

- `ConstantBefore`;
- `Run`;
- `Walk`;
- `ConstantAfter`.

Riceve campioni con tempo monotono, incremento di distanza affidabile e stato GPS. Produce:

- fase corrente;
- profilo di velocità attivo;
- tempo residuo dell'intervallo;
- distanza totale e distanza di routine;
- eventi vocali e beep.

Il `PaceEvaluator` esistente continua a valutare il ritmo. Quando cambia fase, riceve il nuovo profilo e azzera conferme, ripetizioni e cue pendenti appartenenti al profilo precedente.

## Guida uditiva

Restano valide le clip:

- `Too slow`;
- `Too fast`;
- `Good pace`;
- `Bad GPS`;
- `Good GPS`.

Si aggiungono:

- `Routine start` all'ingresso nel primo intervallo Run;
- `Run in ten` dieci secondi prima del passaggio da Walk a Run;
- `Walk in ten` dieci secondi prima del passaggio da Run a Walk;
- tre beep brevi negli ultimi tre secondi prima del cambio;
- `Run routine` all'inizio dei Run successivi;
- `Walk routine` all'inizio di Walk;
- `Routine end` al termine per distanza;
- `Speed up` se, al cambio fase, la velocità affidabile corrente è sotto il nuovo minimo;
- `Slow down` se, al cambio fase, la velocità affidabile corrente è sopra il nuovo massimo.

Se la velocità corrente è già nel nuovo intervallo, non viene aggiunto un cue direzionale. Il cue di fase viene pronunciato prima di `Speed up` o `Slow down`; la coda audio deve conservarli entrambi.

Dopo il cambio continuano i normali avvisi di ritmo: conferma predefinita dopo due letture attendibili, ripetizione ogni 5 secondi e `Good pace` una volta al rientro.

Per intervalli di durata pari o inferiore a 10 secondi, il preavviso `in ten` viene omesso. I beep vengono emessi soltanto per secondi finali realmente disponibili e mai prima dell'annuncio di inizio della fase corrente.

## GPS debole

Durante GPS debole:

- il timer Run/Walk continua usando tempo monotono;
- la distanza non avanza;
- gli avvisi di velocità vengono sospesi;
- preavvisi, beep e annunci di cambio fase temporali continuano;
- `Bad GPS` e `Good GPS` rispettano il toggle già definito.

Se la distanza finale viene superata dal primo fix affidabile dopo un periodo debole, la routine termina immediatamente al ricevimento di quel fix.

## UI provvisoria

La UI può usare componenti Compose standard senza lavoro grafico dedicato. Deve contenere:

- selezione `Andamento costante` / `Walk–Run`;
- minimo e massimo Costante;
- distanza iniziale;
- durata Run in minuti e secondi;
- minimo e massimo Run;
- durata Walk in minuti e secondi;
- toggle Walk uguale a Costante;
- minimo e massimo Walk quando il toggle è spento;
- toggle routine senza fine;
- distanza della routine quando non è senza fine;
- impostazioni audio/GPS già approvate;
- `Avvia` e `Termina`.

La schermata attiva mostra almeno fase, velocità, tempo residuo, distanza e azione `Termina`. Nessun lavoro specifico Material 3 Expressive è richiesto in questo incremento.

## Architettura

- `RoutineEngine`: macchina degli stati pura, temporizzazione, distanze e cue di fase.
- `PaceEvaluator`: controllo del ritmo contro il profilo attivo.
- `RunSessionService`: unico proprietario durante la sessione di GPS, motori e coda audio.
- `CuePlayer`: riproduzione offline ordinata, senza perdere cue simultanei.
- DataStore: conserva esclusivamente configurazione della modalità e dei profili.
- Compose: UI provvisoria per configurazione e stato.

Non vengono creati servizi separati per timer e GPS. Non vengono introdotti database, backend o astrazioni per funzionalità future.

## Batteria

Resta una sola richiesta GPS ad alta precisione a circa 1 Hz durante la sessione. Timer e state machine non richiedono polling aggiuntivo ad alta frequenza: gli eventi temporali sono calcolati dal tempo monotono e da un ticker leggero al secondo. Tutte le risorse vengono rilasciate a `Termina`.

## Verifica e accettazione urgente

### Test automatici

- modalità costante senza transizioni;
- distanza iniziale zero e positiva;
- primo annuncio `Routine start`;
- alternanza Run/Walk con durate differenti;
- preavviso a 10 secondi e tre beep finali;
- intervalli di durata inferiore o uguale a 10 secondi;
- scelta corretta dei tre profili;
- toggle Walk uguale a Costante;
- routine senza fine;
- fine per distanza nel mezzo di Run o Walk;
- passaggio immediato a Costante e `Routine end`;
- timer attivo e distanza/ritmo sospesi con GPS debole;
- cue direzionali al cambio fase;
- ordine e conservazione di cue simultanei;
- azzeramento corretto del `PaceEvaluator` al cambio profilo.

### Emulatore Pixel 9a

- configurazione completa con UI provvisoria;
- sessioni accelerate con distanze GPS simulate;
- alternanza e audio a schermo spento;
- notifica e azione `Termina`;
- build e installazione dell'APK debug.

### Pixel 9a reale

Prima dell'uso effettivo si esegue una breve prova all'aperto per verificare acquisizione GPS, distanza, transizioni audio, convivenza con la musica e continuità a schermo spento. Una prova deve eseguire contemporaneamente Paco Run, l'app fitness abituale e il player musicale, verificando consumo, continuità dei due tracking e assenza di conflitti audio bloccanti. L'APK iniziale è una build personale di test, non un rilascio pubblico.

## Fuori ambito di questo incremento

- Material 3 Expressive e mockup definitivi;
- statistiche e storico;
- mappe e percorso;
- calibrazione per telefoni diversi dal Pixel 9a;
- pubblicazione su Google Play.
