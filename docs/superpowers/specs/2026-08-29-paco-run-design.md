# Paco Run — Design MVP

## Obiettivo

Paco Run è un'app Android personale per mantenere una velocità di corsa leggera e costante. Durante una sessione confronta la velocità stimata dal Pixel 9a con un intervallo configurabile e riproduce avvisi vocali molto brevi nelle cuffie.

L'app è progettata esclusivamente per un Google Pixel 9a. Non include account, mappe, cloud, pubblicità, statistiche o storico delle corse.

## Piattaforma e principi tecnici

- App Android nativa in Kotlin.
- Target primario: Google Pixel 9a.
- Compile e target SDK 37.
- Jetpack Compose e librerie AndroidX/Google correnti.
- Material 3 `1.5.0-alpha27`, per usare le API Material 3 Expressive più recenti disponibili al momento della progettazione.
- Le dipendenze ufficiali utili sono preferite a implementazioni personalizzate. La riduzione delle dipendenze non deve compromettere Material 3 Expressive, accuratezza, affidabilità o manutenibilità.
- Tutti i dati restano sul dispositivo.

## Funzioni incluse

### Intervallo di velocità

L'utente imposta separatamente velocità minima e massima in km/h, senza limiti artificiali. La sessione può partire solo se il minimo è strettamente inferiore al massimo.

Le preferenze vengono conservate localmente fra gli utilizzi. Non vengono conservati percorso, posizioni, durata, velocità o altri dati delle corse.

### Acquisizione della velocità

Durante una sessione, un foreground service di tipo `location` richiede aggiornamenti ad alta precisione tramite Google Play Services Location:

- intervallo desiderato: 1 secondo;
- nessun batching intenzionale;
- uso della velocità fornita da `Location` quando disponibile;
- controllo di `hasSpeedAccuracy()` e dell'incertezza dichiarata;
- scarto di letture vecchie, palesemente anomale o non sufficientemente affidabili.

La soglia concreta che definisce una lettura affidabile rimane un parametro tecnico calibrabile sul Pixel 9a. Una lettura incerta non modifica lo stato del ritmo.

### Stati e avvisi

Gli stati della sessione sono:

- sotto il minimo;
- nel range;
- sopra il massimo;
- GPS debole.

Una transizione a troppo lento o troppo veloce richiede per impostazione predefinita due letture attendibili consecutive. Con aggiornamenti a circa 1 Hz, il primo avviso arriva normalmente entro 1–2 secondi. Il numero di letture è configurabile.

Gli avvisi sono clip preregistrate, brevi e pronunciate velocemente ma chiaramente:

- `Too slow`;
- `Too fast`;
- `Good pace`;
- `Bad GPS`;
- `Good GPS`.

`Too slow` e `Too fast` vengono ripetuti ogni 5 secondi finché la condizione persiste; l'intervallo è configurabile. `Good pace` viene riprodotto una sola volta dopo il rientro stabile nel range.

L'opzione per gli avvisi GPS è disattivata per impostazione predefinita. Se attiva, `Bad GPS` viene pronunciato una sola volta all'inizio di un periodo inaffidabile e `Good GPS` una sola volta al ripristino. Gli avvisi di ritmo sono sospesi mentre il GPS è inaffidabile.

### Audio e musica

Le clip sono incluse nell'app e funzionano offline. L'app richiede audio focus transitorio con ducking, così la musica viene abbassata brevemente senza essere messa in pausa quando il player musicale rispetta la richiesta.

Android e l'app musicale controllano l'intensità effettiva del ducking. Paco Run espone invece un controllo del volume delle proprie clip, con valore predefinito leggero.

### Funzionamento a schermo spento

La sessione continua tramite un foreground service e una notifica persistente con azione `Termina`. Il tracking esiste esclusivamente durante una sessione esplicitamente avviata e viene chiuso immediatamente al termine.

Non viene introdotto un wake lock esplicito a meno che le prove sul Pixel 9a dimostrino che sia necessario per la continuità del tracking.

Se il processo viene terminato forzatamente o il telefono viene riavviato, la sessione finisce. L'MVP non tenta di recuperarla.

## Interfaccia

L'interfaccia è in italiano; soltanto le clip audio usano le frasi inglesi definite sopra. Il nome visualizzato è **Paco Run**.

La UI usa autentici componenti, forme, tipografia e motion Material 3 Expressive. Deve risultare curata all'apertura e alla chiusura, senza effetti continui o decorazioni che consumino batteria durante la corsa.

### Home

- Nome dell'app e stato GPS.
- Controlli grandi per velocità minima e massima.
- Azione primaria `Avvia corsa`.
- Accesso secondario alle impostazioni.

### Sessione

- Velocità corrente molto leggibile in km/h.
- Intervallo configurato.
- Stato corrente: troppo lento, ritmo corretto, troppo veloce o GPS debole.
- Durata corrente, non persistita.
- Grande azione `Termina`.

### Impostazioni

- Abilitazione degli avvisi GPS.
- Volume delle clip.
- Dynamic color.
- Intervallo fra le ripetizioni, default 5 secondi.
- Numero di letture consecutive, default 2.

Il dynamic color del Pixel è attivo per impostazione predefinita. Può essere disabilitato in favore di una palette fissa dell'app.

## Architettura

- Una sola Activity Compose.
- Navigation Compose per home, sessione e impostazioni.
- ViewModel e StateFlow per lo stato osservabile della UI.
- Foreground service per durata della sessione, posizione e coordinamento audio.
- Componente di valutazione del ritmo, indipendente da Android, che trasforma letture validate in stati ed eventi vocali.
- DataStore Preferences per la sola configurazione.
- Google Play Services Location per le letture.
- Coroutines e Flow per elaborazione e propagazione dello stato.
- Clip locali e API Android per riproduzione e audio focus.

Non sono previsti database, backend, dependency injection, repository generici o astrazioni speculative per statistiche future. L'architettura visiva non deve però precludere l'aggiunta successiva di una sezione statistiche.

## Errori e casi limite

- Prima della sessione, `Avvia corsa` resta disabilitato finché manca una posizione sufficientemente affidabile.
- Se il GPS si degrada durante la sessione, gli avvisi di ritmo vengono sospesi.
- Le letture anomale vengono ignorate senza produrre transizioni.
- Se audio focus o ducking non sono disponibili, il tracking continua e la clip viene riprodotta quando possibile.
- Rimuovere l'app dalla schermata recenti non deve interrompere il foreground service.
- Il minimo maggiore o uguale al massimo impedisce l'avvio e mostra un errore chiaro.

## Batteria

Il principale consumo è il tracking ad alta precisione a 1 Hz. Per contenerlo:

- nessun accesso alla posizione fuori da una sessione o dalla breve acquisizione iniziale;
- nessuna rete, mappa o scrittura continua;
- nessun aggiornamento UI necessario a schermo spento;
- rilascio immediato di location callback, audio e servizio al termine;
- misurazione reale sul Pixel 9a prima di considerare completato l'MVP.

## Verifica e criteri di accettazione

### Test automatici

- Transizioni sotto/dentro/sopra il range.
- Conferma dopo il numero configurato di letture.
- Ripetizione degli avvisi ogni 5 secondi.
- `Good pace` una sola volta al rientro.
- Periodi GPS deboli e relativi avvisi opzionali.
- Scarto di letture inaffidabili e picchi anomali.

### Emulatore Pixel 9a

- Layout, tema dinamico e palette fissa.
- Permessi di posizione e notifiche.
- Servizio e notifica con schermo spento.
- Riproduzione audio e controllo volume.
- Percorsi GPS simulati con velocità note e cambi di soglia.

### Pixel 9a reale

- Precisione e ritardo degli avvisi all'aperto.
- Continuità a schermo spento.
- Convivenza con l'app musicale usata durante la corsa.
- Frequenza dei falsi avvisi in un percorso reale.
- Consumo di batteria durante almeno una sessione rappresentativa.

L'MVP è accettato quando riconosce correttamente le transizioni simulate, continua a schermo spento e completa una prova reale senza falsi avvisi frequenti o consumo anomalo.

## Fuori ambito

- Account e autenticazione.
- Cloud o rete.
- Mappe e registrazione del percorso.
- Storico e statistiche.
- Condivisione o social.
- Supporto e calibrazione per dispositivi diversi dal Pixel 9a.
