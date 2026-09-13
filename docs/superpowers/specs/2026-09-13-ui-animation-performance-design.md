# Ottimizzare le animazioni UI — eliminare le recomposition sprecate

**Branch:** `feature/paco-run-m3e` (live), da implementare su un branch dedicato `feature/ui-animation-performance`. **Data:** 2026-09-13. **Scopo:** due indagini di sola lettura, già riportate e accolte dall'utente, hanno trovato lo stesso pattern di bug Compose ripetuto in cinque componenti animati: un valore `State` animato viene letto a livello di corpo della funzione `@Composable` invece che dentro una lambda di fase draw (`Canvas { }`, `Modifier.graphicsLayer { }`), forzando una recomposition completa ad ogni frame invece di un semplice redraw. Questo documento disegna la correzione delle cinque posizioni più la consolidazione di tre implementazioni quasi identiche di "piazza uno shape `Morph`-interpolato in una box" in un'unica utility condivisa.

## 1. Il pattern comune, in breve

Compose traccia le letture di `State` che avvengono **durante l'esecuzione del corpo di un `@Composable`** e ricompone quella funzione (dall'inizio) ogni volta che uno di quei valori cambia. Letture che avvengono invece dentro certe lambda passate a modifier come `Modifier.graphicsLayer { }`, `Canvas { ... }` (una `DrawScope.() -> Unit`), `Modifier.offset { IntOffset(...) }` o dentro `Shape.createOutline(...)` **non** contano come letture del corpo — vengono eseguite in una fase successiva (layout/draw), quindi un cambiamento del valore invalida solo quella fase, non l'intera composizione. La correzione, in tutti e cinque i casi, è la stessa: spostare la lettura del valore animato dentro una di queste lambda, invece di leggerla (spesso tramite `by`) all'inizio della funzione.

**Una precisazione importante, non ovvia:** spostare la lettura non elimina SEMPRE la recomposition — solo quando quel valore non ha *anche* un altro consumatore che resta a livello di corpo. Nei casi #4 e #5 sotto, un'altra proprietà con lo stesso identico ciclo di vita (colore del testo, dimensione del box) resta necessariamente a livello di corpo per motivi indipendenti da questo lavoro — quindi il fix elimina lo spreco di allocazione/ricalcolo per-frame dentro una recomposition che comunque avviene, non la recomposition stessa. Questa distinzione è spiegata caso per caso più sotto.

## 2. Correzione dei laboratorio: `secondaryShape` in `HomeScreen.kt` NON è un bug

La prima indagine (voce 5 del riepilogo iniziale) segnalava `MaterialShapes.ClamShell.toShape(startAngle = 24)` (riga 215 di `HomeScreen.kt`) come non-`remember`ato e quindi riallocato ad ogni recomposition. **Verificato sulla sorgente reale della libreria (material3-android 1.5.0-alpha27, effettivamente risolta da questo progetto) e risulta falso:** `RoundedPolygon.toShape(startAngle)` è essa stessa una funzione `@Composable` che internamente fa `remember(this, startAngle) { object : Shape { ... } }` (vedi `androidx/compose/material3/MaterialShapes.kt` nel sources jar). Dato che `MaterialShapes.ClamShell` è un singleton stabile e `startAngle = 24` è una costante, quella `remember` interna produce sempre la stessa istanza — nessuna allocazione ripetuta, nessuna azione da fare. Stesso discorso per `MaterialShapes.ClamShell.toPath(startAngle = 24)` usato in `AmbientShapeField.kt` riga 58 (`RoundedPolygon.toPath(startAngle)`, stesso file, stesso pattern `remember`). Questa correzione sostituisce quella voce nel riepilogo originale — nessun task del piano la tocca.

## 3. L'utility condivisa: `MorphGeometry.kt`

Tre file (`AmbientShapeField.kt`, `SessionHero.kt`'s `HeroMorphShape`, `HomeScreen.kt`'s `MorphShape`) implementano a mano, in tre copie quasi identiche, la stessa sequenza: `path.rewind(); path.addPath(source); path.transform(Matrix().apply { scale(w, h) }); path.translate(center - path.getBounds().center)` — con una `Matrix()` fresca ad ogni chiamata (`HeroMorphShape`/`MorphShape`: dentro `createOutline`, chiamato ad ogni frame durante un morph) e, nel caso di `HeroMorphShape`/`MorphShape`, un'intera nuova istanza di classe (quindi una nuova interpolazione `morph.toPath(progress)`) creata ad ogni frame invece di aggiornare un'istanza persistente.

**Osservazione di grounding, non ovvia:** questa stessa identica sequenza (`Matrix()` fresca per chiamata inclusa) è **esattamente quella che Material3 stesso usa** nella propria implementazione di `RoundedPolygon.toShape()` (vedi sezione 2) — quindi allocare una `Matrix()` per chiamata non è di per sé "sbagliato" secondo lo standard della libreria; lo spreco reale e specifico di questo codebase è che **la stessa interpolazione del Morph** (`morph.toPath(progress)`, l'operazione costosa: attraversa tutte le cubic del morph) viene rifatta da zero ad ogni frame perché l'intera classe `HeroMorphShape`/`MorphShape` viene ricreata da `remember(...T...)` ad ogni tick di quel valore, invece di aggiornare un'istanza esistente e ricalcolare l'interpolazione solo quando il progresso è realmente cambiato.

**API esatta**, nuovo file `app/src/main/java/dev/fred/pacorun/ui/components/MorphGeometry.kt`:

```kotlin
package dev.fred.pacorun.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.toPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph

// Pure part of the "scale, then re-center" placement math: no Path/Matrix, unit-testable on
// plain JVM (see section 6 for why the rest of this file cannot be).
internal fun recenterTranslation(scaledBounds: Rect, center: Offset): Offset =
    center - scaledBounds.center

// Scales [source] to (targetWidth, targetHeight) and re-centers it on [center], writing into
// [into] (rewound first) via [matrix] (reset first) -- no new Path/Matrix allocated by this call.
internal fun placeMorphPath(
    source: Path,
    targetWidth: Float,
    targetHeight: Float,
    center: Offset,
    matrix: Matrix,
    into: Path,
): Path {
    into.rewind()
    into.addPath(source)
    matrix.reset()
    matrix.scale(targetWidth, targetHeight)
    into.transform(matrix)
    into.translate(recenterTranslation(into.getBounds(), center))
    return into
}

// A Shape backed by a live-morphing Morph: reuses one Matrix and one working Path across every
// createOutline call, and only re-runs the Morph's cubic interpolation (morph.toPath) when
// [progress] has actually changed since the last call. [progress] is read lazily, inside
// createOutline (layout/draw phase, not composition), so holding this instance across
// recompositions never itself forces a recomposition.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal class MorphingShape(
    private val morph: Morph,
    private val startAngle: Int = -90,
    private val progress: () -> Float,
) : Shape {
    private var cachedProgress: Float = Float.NaN
    private val sourcePath = Path()
    private val matrix = Matrix()
    private val workPath = Path()

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val currentProgress = progress().coerceIn(0f, 1f)
        if (currentProgress != cachedProgress) {
            morph.toPath(progress = currentProgress, path = sourcePath, startAngle = startAngle)
            cachedProgress = currentProgress
        }
        placeMorphPath(sourcePath, size.width, size.height, size.center, matrix, workPath)
        return Outline.Generic(workPath)
    }
}
```

**Uso ai tre call site:**
- `HeroMorphShape` (`SessionHero.kt`) → `MorphingShape(runWalkMorph) { runWalkT.value }` (etc. per gli altri due morph fissi) — sostituisce la classe privata, `runWalkT`/`walkSpeedT`/`runSpeedT` restano `State<Float>` (non più destrutturati con `by`), letti dentro la lambda.
- `MorphShape` (`HomeScreen.kt`) → `MorphingShape(primaryMorph) { morphProgress.value }` — stesso schema.
- `AmbientShapeField.kt` → caso degenere, non usa `MorphingShape` (non c'è un `Shape`/`createOutline` qui, solo un `Canvas`): usa `placeMorphPath` UNA SOLA VOLTA (in un `remember`, non ad ogni frame) per pre-calcolare il path scalato e centrato sull'origine; il piazzamento per-frame diventa un semplice `translate(left, top) { drawPath(...) }` di `DrawScope` — zero allocazioni Path/Matrix per frame, il minimo lavoro possibile dato che forma e scala non cambiano mai durante l'animazione (vedi sezione 4.1).

**Perché non un'astrazione più ampia:** non c'è un quarto caso d'uso in questo codebase, e i tre esistenti condividono esattamente questa singola operazione (scala+centra) più, per due di essi, la stessa cache "ricalcola l'interpolazione solo se il progresso cambia". Nessuna interfaccia, nessuna configurazione per varianti che non esistono — una funzione pura più una classe piccola, `internal` (usata da `ui.session` e `ui.home`, stesso modulo Gradle).

## 4. Le cinque correzioni

### 4.1 `AmbientShapeField.kt` — vittoria piena, recomposition eliminata

Oggi: `phase` (riga 36) letto con `by` a livello di corpo (riga 45, tramite `radians`/`orbitX`/`orbitY`/`horizontalSway`), e `drawAmbient` (righe 72-81) ricostruisce da zero `Path()`+`Matrix()`+`getBounds()` ad ogni frame per DUE shape la cui forma e scala (`600.dp` fisso) non cambiano mai — solo la posizione orbita. In più, `rotate(degrees, pivot)` con `degrees` fissato a `0f` (righe 91, 103) è un no-op puro.

**Fix:**
- `phase` diventa uno `State<Float>` tenuto senza `by`: `val phase = transition.animateFloat(...)`.
- Tutta la matematica dell'orbita (`radians`, `orbitX`, `orbitY`, `horizontalSway`) si sposta dentro il blocco `Canvas { }`, letta da `phase.value` lì — nessun altro consumatore esiste a livello di corpo, quindi questo elimina completamente la recomposition dovuta a `phase` (l'unica cosa che la causava).
- I due path vengono piazzati (scalati a 600dp, centrati sull'origine) UNA VOLTA con `placeMorphPath`, dentro un `remember` — non ad ogni frame.
- Il disegno per-frame diventa `translate(left, top) { drawPath(placed, color) }`, con `left`/`top` calcolati dall'orbita dentro lo stesso blocco `Canvas`.
- Il wrapper `rotate(0f, pivot) { }` viene rimosso (era un no-op).

**Estrazione testabile:** la matematica dell'orbita (pura trigonometria, nessun `Path`/`Matrix`) si estrae in una funzione pura `ambientOrbit(phaseDegrees: Float): AmbientOrbit` in un nuovo file `AmbientMotion.kt` (stesso pattern già in uso in questo codebase per `SessionVisualState.kt`, che tiene `heroKind`/`urgency`/`paceLabel` come funzioni pure accanto ai composable che le usano) — questo permette un test unitario diretto che dimostra che la formula non è cambiata nello spostamento.

### 4.2 `SessionHero.kt` righe 57-80 — `urgencyValue`/`breathPhase` — vittoria piena

`urgencyValue` (riga 57-61, un `animateFloatAsState`) e `breathPhase` (riga 69-78, un `infiniteRepeatable` continuo per tutta la durata della sessione attiva) sono letti con `by` a livello di corpo. Il loro UNICO uso, oltre a seminare `deflatingScale` una volta per cambio di `kind` (righe 146-150, che ha bisogno del valore sincrono in quel preciso istante — lettura legittima, rara, non per-frame), è dentro `Modifier.graphicsLayer { scaleX = ...; scaleY = ... }` (righe 170-173). Spostando l'intero calcolo di `breathScale`/`idlePeakScale`/`currentScale` dentro quella lambda `graphicsLayer`, nessun altro punto del corpo dipende dal loro valore per-frame — la recomposition dovuta a queste due animazioni continue viene eliminata del tutto.

**Fix:** tenere `urgencyValue`/`breathPhase` come `State<Float>` (non `by`); `deflatingScale = remember(kind) { Animatable(1f + urgencyValue.value * 0.16f) }` legge `.value` una volta, dentro `remember(kind)` (eseguito solo ai cambi di `kind`, non ad ogni frame); dentro `graphicsLayer { }`:

```kotlin
.graphicsLayer {
    val urgency = urgencyValue.value
    val breath = breathPhase.value
    val idlePeak = 1f + urgency * 0.16f
    val amplitude = 0.012f + urgency * 0.04f
    val breathScale = 1f + (breath - 0.5f) * 2f * amplitude
    val scale = maxOf(idlePeak, deflatingScale.value) * breathScale
    scaleX = scale
    scaleY = scale
}
```

**Estrazione testabile:** `heroIdlePeakScale(urgency: Float): Float` e `heroBreathScale(urgency: Float, breathPhase: Float): Float`, aggiunte a `SessionVisualState.kt` (stesso file di `urgency()`, stesso stile) — pura aritmetica Float, nessun `Path`/`Matrix`, pienamente testabile su JVM. Il test dimostra che la formula ricalcolata dentro `graphicsLayer` produce esattamente gli stessi numeri di oggi per una campionatura di `(urgency, breathPhase)`.

### 4.3 `SessionHero.kt` — `HeroMorphShape` (righe 92-136, 221-233) — vittoria parziale, onesta

Sostituita da `MorphingShape(morph) { tState.value }` (sezione 3). Elimina: `Matrix()` fresca per frame (ora riusata), `Path()` fresca per istanza (ora un `workPath` riusato), e soprattutto la **reinterpretazione completa del Morph** (`morph.toPath`) ad ogni frame anche quando il progresso non era cambiato — ora ricalcolata solo quando `progress()` differisce dall'ultimo valore memorizzato.

**Onestà sul risultato pratico:** `runWalkT`/`walkSpeedT`/`runSpeedT` restano necessariamente lette a livello di corpo per `colorProgress` (righe 115-123, che alimenta il colore del testo/box — non nello scope di questo lavoro, non un bug segnalato). Quindi **la recomposition di `SessionHero` durante una transizione di fase continua a succedere ad ogni frame indipendentemente da questo fix** — quello che il fix elimina è lo spreco (allocazioni, reinterpolazione) che accadeva DENTRO ciascuna di quelle recomposition comunque necessarie, non le recomposition stesse. Un fix reale, ma di una classe diversa da 4.1/4.2.

### 4.4 `HomeScreen.kt` — `RoutineScene`/`MorphShape` (righe 176-293, 375-387) — vittoria parziale + un pezzo pienamente chiudibile

`MorphShape` → `MorphingShape(primaryMorph) { morphProgress.value }`, stesso beneficio di 4.3. **Qui `morphProgress` (riga 181-184) non ha NESSUN altro consumatore nel file** (a differenza dei T di Hero) — quindi la sua lettura a livello di corpo sparisce completamente. Non basta comunque a fermare la recomposition di `RoutineScene`, perché `primarySize` (righe 185-188) resta un'altra proprietà della STESSA `updateTransition`, letta a livello di corpo, che da sola forza la recomposition per l'intera durata della transizione — vedi sezione 5 per l'indagine completa su questo punto.

`secondaryScale`/`secondaryAlpha` (righe 205-212) sono **già corrette** — il loro unico uso è dentro `Modifier.graphicsLayer { }` (righe 222-226) nonostante siano dichiarate con `by`: la lettura avviene quando quella lambda viene esguita (fase draw), non quando il corpo di `RoutineScene` viene eseguito. Nessuna modifica.

`secondaryShape` (riga 215): nessuna modifica — sezione 2.

### 4.5 `HoldToStopButton.kt` righe 42-91 — vittoria piena, tecnica diversa

Qui il problema non è "sposta in una lambda draw" ma "il valore per-frame ha due usi che possono essere resi non-continui":

- **Testo condizionale** (riga 86, `if (progress.value > 0f) ...`): il valore rilevante per QUESTO uso è solo la SOGLIA `progress > 0`, non il valore continuo — sostituito con `val isPressed by remember { derivedStateOf { progress.value > 0f } }`. `derivedStateOf` invalida il proprio osservatore solo quando il valore *derivato* (il booleano) cambia, non ad ogni tick di `progress` — la recomposition di `HoldToStopButton` dovuta a questo sito scende da "ogni frame per ~1.5s" a "due volte per pressione" (inizio, eventuale annullamento).
- **Barra di riempimento** (riga 81, `Modifier.fillMaxWidth(fraction = progress.value...)`): `fillMaxWidth` non ha un overload lambda-differito. Sostituito con lo stesso trucco già in uso altrove in questo codebase (Hero, Home: scala via `graphicsLayer`): il Box occupa sempre `fillMaxWidth()` pieno, e viene scalato orizzontalmente dall'origine sinistra: `Modifier.graphicsLayer { transformOrigin = TransformOrigin(0f, .5f); scaleX = progress.value.coerceIn(0f, 1f) }` — stessa resa visiva, lettura completamente differita alla fase draw.
- **`progressBarRangeInfo`** (riga 74, dentro `.semantics { }`): nessuna modifica necessaria — è già dentro una lambda differita; oggi sembrava "contagiata" solo perché gli altri due siti forzavano comunque la recomposition dell'intero componente.

Nessuna estrazione di funzione pura qui: `progress.value.coerceIn(0f, 1f)` e `progress.value > 0f` sono espressioni a una riga, non giustificano un test dedicato (vedi sezione 6).

## 5. Il caso `Modifier.offset { IntOffset(...) }` per `RoutineScene` — indagine richiesta esplicitamente

**Chiusura parziale, confermata e onesta.** `Modifier.offset` ha due overload: quello eager `offset(x: Dp, y: Dp)` (quello in uso oggi, righe 218 e 221 di `HomeScreen.kt`, per `primaryModifier`/`secondaryModifier` rispettivamente) e uno lambda-differito `offset(offset: Density.() -> IntOffset)`, valutato in fase di placement (layout), non di composizione. Sostituendo:

```kotlin
val primaryModifier = Modifier
    .size(primarySize)
    .offset { IntOffset(primaryX.value.roundToPx(), primaryY.value.roundToPx()) }
```

(con `primaryX`/`primaryY`/`secondaryX`/`secondaryY` tenuti come `State<Dp>`, non `by`), le letture di queste 4 proprietà su 8 escono dallo scope di composizione di `RoutineScene`.

**Cosa NON si chiude, e perché:** `primarySize` (un `Dp`, usato in `Modifier.size(primarySize)`) resta eager. **Non esiste, in Compose Foundation, un overload lambda-differito equivalente per `Modifier.size()`** (a differenza di `offset`) — l'unico modo per differire anche `primarySize` sarebbe sostituire `.size(primarySize)` con un `Modifier.layout { measurable, constraints -> ... }` scritto a mano che legge `primarySize.value` al momento della misura, una ristrutturazione via via più invasiva di quanto richiesto qui (un modifier di layout custom, non un overload già pronto) — esplicitamente fuori scope per questo piano, segnalato come possibile lavoro futuro se mai servisse chiudere anche questo pezzo.

**Conseguenza pratica onesta:** dato che `primarySize` anima sulla STESSA `updateTransition` di `primaryX`/`primaryY`/`secondaryX`/`secondaryY` (stessa durata, stesso `motionScheme.defaultSpatialSpec()`), la sua sola lettura eager continua a forzare la recomposition di `RoutineScene` per l'intera transizione, **indipendentemente** da quanti degli altri 4 valori vengono differiti. Il beneficio reale del fix `offset{}` non è "meno recomposition" (la frequenza resta la stessa, dettata da `primarySize`) ma **meno lavoro dentro ciascuna di quelle recomposition**: i 4 parametri Dp differiti non vengono più ricreati/diffati come argomenti del modifier chain ad ogni ricomposizione (Compose può saltare quella parte del confronto, dato che la lambda passata a `offset{}` è un riferimento stabile), lasciando solo `primarySize`+`morphProgress`-derivato (già risolto in 4.4) come vero lavoro residuo per frame.

## 6. Cosa NON si può verificare con un test unitario JVM puro, e perché — sezione da leggere prima del piano

Vincolo del progetto (nessuna eccezione): `./gradlew testDebugUnitTest` gira su JVM puro, senza Robolectric. `androidx.compose.ui.graphics.Path`/`Matrix`/`Outline` sono tipi `expect`/`actual`: sul target Android la loro implementazione reale delega a `android.graphics.Path`/`Matrix` (JNI) — su un test JVM puro, senza Robolectric, ogni chiamata a `Path()` o a un suo metodo lancia (comportamento di default di Gradle Android, `returnDefaultValues` non abilitato in questo progetto, verificato: nessun blocco `testOptions` in `app/build.gradle.kts`). **Questo significa che `placeMorphPath`/`MorphingShape` — la parte che manipola `Path`/`Matrix` — non è testabile con un test unitario JVM, categoricamente, non per scelta ma per vincolo di piattaforma.**

Cosa RESTA testabile, verificato con grounding sulle sorgenti reali:
- `androidx.compose.ui.geometry.Offset`/`Rect`/`Size` sono tipi Kotlin puri (solo `commonMain`, nessun override `androidMain`, nessuna dipendenza da `android.graphics`) — pienamente utilizzabili in un test JVM. `recenterTranslation(scaledBounds: Rect, center: Offset): Offset` è quindi testabile, ed è l'unico pezzo di `placeMorphPath` con vera "logica" (il resto è plumbing meccanico di `Path`/`Matrix`).
- `androidx.graphics.shapes.Morph`/`RoundedPolygon`/`Cubic` sono libreria multipiattaforma pura (esiste una variante `graphics-shapes-desktop`, confermato nella cache Gradle) — utilizzabile su JVM. Non è comunque rilevante qui: il refactor non cambia quali `Morph` vengono costruiti né con quale progresso vengono interpolati, solo come il `Path` risultante viene scalato/piazzato — quindi non c'è un "prima/dopo" dei `Cubic` da confrontare.
- Tutta l'aritmetica pura (orbita di `AmbientShapeField`, `heroBreathScale`/`heroIdlePeakScale`) è testabile, nessun vincolo.

**Mitigazione per la parte non testabile (`placeMorphPath`/`MorphingShape`):** l'implementazione è un adattamento minimo, quasi riga-per-riga, del pattern già spedito e in uso in produzione da Material3 stesso in `RoundedPolygon.toShape()` (sezione 2/3) — stesso ordine di operazioni (`rewind`, `addPath`, `Matrix().scale`, `transform`, `translate(center - getBounds().center)`), solo con i buffer riusati invece di riallocati. La verifica per questa parte specifica è per ispezione (self-review + review indipendente nel task finale del piano, diff riga-per-riga contro il codice attuale e contro il pattern Material3 citato), non per test automatico — e la verifica visiva finale resta, come sempre, il controllo dell'utente sul dispositivo reale.

## 7. Cosa resta invariato

- Nessun valore geometrico finale cambia: stessa forma, stessa scala, stesso centro, stesso colore, stessa cadenza di animazione (nessun parametro di `tween`/`infiniteRepeatable`/`updateTransition` viene toccato).
- `SpeedEstimator`, `SessionCoordinator`, tutta la logica di dominio: intoccati, questo lavoro è puramente `ui.*`.
- `DopplerSpeedEngine`/`KalmanSpeedEngine` e tutto il lavoro di Fase 0.5: non correlato, non toccato.

## 8. Fuori scope

- Chiudere il gap `primarySize` di `RoutineScene` con un `Modifier.layout{}` custom (sezione 5) — non richiesto, non deciso.
- Qualunque cambiamento visivo non richiesto esplicitamente (vedi memoria di progetto: nessuna modifica di parametri di stile/animazione oltre a quanto qui descritto).
- Rendere `HoldToStopButton`'s espressioni triviali in funzioni pure testate a sé (sezione 4.5/6) — non giustificato per una riga di `coerceIn`/confronto.
