# Ottimizzare le animazioni UI — eliminare le recomposition sprecate (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correggere le cinque posizioni, in quattro file (`AmbientShapeField.kt`, `SessionHero.kt`, `HomeScreen.kt`, `HoldToStopButton.kt`), dove un valore `State` animato viene letto a livello di corpo `@Composable` invece che dentro una lambda di fase draw/layout, forzando una recomposition completa ad ogni frame invece di un semplice redraw/relayout. Consolida anche le tre implementazioni quasi identiche di "scala e centra uno shape `Morph`-interpolato" (`AmbientShapeField`, `SessionHero`'s `HeroMorphShape`, `HomeScreen`'s `MorphShape`) in un'unica utility condivisa, `MorphGeometry.kt`.

**Branch:** nuovo branch dedicato `feature/ui-animation-performance`, creato a partire da `feature/paco-run-m3e` (live) — stesso pattern di un branch per fase già usato in questo progetto (`feature/speed-production-wiring`, `feature/motion-engine-refactor`). Questo piano non crea il branch: lo crea un task successivo, autorizzato separatamente dall'utente, seguendo `superpowers:using-git-worktrees`.

**Tech Stack:** Kotlin, Jetpack Compose (Material3 Expressive, `androidx.graphics.shapes`), JUnit4, Gradle (`./gradlew testDebugUnitTest`, `lintDebug`).

**Spec:** `docs/superpowers/specs/2026-09-13-ui-animation-performance-design.md`.

## Global Constraints

- **Nessun cambio visivo.** Stessa forma, stessa scala, stesso centro, stesso colore, stessa cadenza di animazione in ogni punto toccato — nessun parametro di `tween`/`infiniteRepeatable`/`updateTransition`/`animateFloatAsState` viene modificato. Solo QUANDO/DOVE un valore viene letto cambia, mai il valore stesso.
- **Verifica automatica: solo test JUnit4 puro JVM.** Niente Robolectric, niente test strumentati/on-device, niente screenshot o pixel-diff. Vincolo esplicito e non negoziabile dell'utente.
- **`androidx.compose.ui.graphics.Path`/`Matrix`/`Outline` non sono testabili su JVM puro** (delegano a `android.graphics.*` via JNI sul target reale; `app/build.gradle.kts` non ha un blocco `testOptions` con `unitTests.returnDefaultValues`, verificato). Qualunque funzione che li tocca (`placeMorphPath`, `MorphingShape`) è verificata per ispezione (self-review + review indipendente, Task 6), non da un test automatico. Solo la parte puramente aritmetica (`recenterTranslation`, `ambientOrbit`, `heroIdlePeakScale`, `heroBreathScale`) è unit-testata.
- **Non toccare `SpeedEstimator`/`SessionCoordinator`/logica di dominio.** Questo lavoro è puramente `ui.*`.
- **Non chiudere il gap `primarySize` di `RoutineScene`** con un `Modifier.layout{}` custom — deciso esplicitamente fuori scope (spec, sezione 5).
- **Non fare nessuna estrazione di funzione pura per `HoldToStopButton.kt`** — le sue espressioni (`coerceIn`, confronto a soglia) sono a una riga, non giustificano un test dedicato (spec, sezione 4.5/6).
- **Fermarsi al Task 6.** Nessun merge, nessuna build APK, nessuna creazione del branch/worktree dedicato senza un via libera esplicito dell'utente — stessa regola permanente di ogni fase precedente di questo progetto.

---

## File Structure

| File | Ruolo | Task |
|---|---|---|
| `app/src/main/java/dev/fred/pacorun/ui/components/MorphGeometry.kt` | Nuovo: utility condivisa (`recenterTranslation`, `placeMorphPath`, `MorphingShape`) | 1 |
| `app/src/test/java/dev/fred/pacorun/ui/components/MorphGeometryTest.kt` | Nuovo: test della sola parte pura (`recenterTranslation`) | 1 |
| `app/src/main/java/dev/fred/pacorun/ui/components/AmbientMotion.kt` | Nuovo: estrazione pura della matematica dell'orbita (`ambientOrbit`) | 2 |
| `app/src/test/java/dev/fred/pacorun/ui/components/AmbientMotionTest.kt` | Nuovo: test di `ambientOrbit` | 2 |
| `app/src/main/java/dev/fred/pacorun/ui/components/AmbientShapeField.kt` | Modifica: `phase` deferito dentro `Canvas`, path piazzati una tantum via `placeMorphPath`, `rotate` no-op rimosso | 2 |
| `app/src/main/java/dev/fred/pacorun/ui/session/HoldToStopButton.kt` | Modifica: `derivedStateOf` per il testo condizionale, `graphicsLayer` per la barra di riempimento | 3 |
| `app/src/main/java/dev/fred/pacorun/ui/session/SessionVisualState.kt` | Modifica: aggiunge `heroIdlePeakScale`/`heroBreathScale` | 4 |
| `app/src/test/java/dev/fred/pacorun/ui/session/SessionVisualStateTest.kt` | Modifica: test delle due nuove funzioni | 4 |
| `app/src/main/java/dev/fred/pacorun/ui/session/SessionHero.kt` | Modifica: `urgencyValue`/`breathPhase` deferiti dentro `graphicsLayer`; `HeroMorphShape` sostituita da `MorphingShape` | 4 |
| `app/src/main/java/dev/fred/pacorun/ui/home/HomeScreen.kt` | Modifica: `MorphShape` sostituita da `MorphingShape` (classe rimossa); `primaryX`/`primaryY`/`secondaryX`/`secondaryY` deferiti via `offset { }` lambda | 5 |

---

### Task 1: `MorphGeometry.kt` — l'utility condivisa

**Files:**
- New: `app/src/main/java/dev/fred/pacorun/ui/components/MorphGeometry.kt`
- Test: `app/src/test/java/dev/fred/pacorun/ui/components/MorphGeometryTest.kt`

**Interfaces:**
- Produces: `internal fun recenterTranslation(scaledBounds: Rect, center: Offset): Offset`, `internal fun placeMorphPath(source: Path, targetWidth: Float, targetHeight: Float, center: Offset, matrix: Matrix, into: Path): Path`, `internal class MorphingShape(morph: Morph, startAngle: Int = -90, progress: () -> Float) : Shape`.
- Consumes: `androidx.graphics.shapes.Morph` (esistente), `androidx.compose.material3.toPath` (extension `Morph.toPath(progress: Float, path: Path = Path(), startAngle: Int = 0): Path`, verificata sulla sorgente reale `material3-android:1.5.0-alpha27`, `commonMain/androidx/compose/material3/MaterialShapes.kt` riga 56 — rewinda e riscrive `path` se fornito, non ne alloca uno nuovo).

Grounding: la sequenza `rewind → addPath → Matrix().scale → transform → translate(center - getBounds().center)` è esattamente quella usata da Material3 stesso in `RoundedPolygon.toShape()` (stesso file sorgente, righe 101-121) — questa utility non inventa una tecnica nuova, riusa buffer persistenti (`matrix`, `into`) al posto di quelli freschi che Material3 alloca lì per `Matrix` (ma non sempre per `Path`, che Material3 già riusa quando la `size` non cambia).

- [ ] **Step 1: Scrivere il test per `recenterTranslation` (l'unica parte pura, testabile su JVM)**

```kotlin
package dev.fred.pacorun.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class MorphGeometryTest {
    @Test
    fun recentersBoundsAlreadyAtOriginToTheGivenCenter() {
        val bounds = Rect(left = -1f, top = -1f, right = 1f, bottom = 1f) // center (0, 0)

        assertEquals(Offset(100f, 50f), recenterTranslation(bounds, Offset(100f, 50f)))
    }

    @Test
    fun recentersOffCenterBoundsBySubtractingTheirOwnCenter() {
        // Same math today's per-file `translate(center - getBounds().center)` uses.
        val bounds = Rect(left = 5f, top = 15f, right = 15f, bottom = 25f) // center (10, 20)

        assertEquals(Offset(-10f, -20f), recenterTranslation(bounds, Offset(0f, 0f)))
    }

    @Test
    fun combinesBothEffectsWhenNeitherIsZero() {
        val bounds = Rect(left = 2f, top = 4f, right = 6f, bottom = 8f) // center (4, 6)

        assertEquals(Offset(96f, 194f), recenterTranslation(bounds, Offset(100f, 200f)))
    }
}
```

- [ ] **Step 2: Eseguire il test, verificare che fallisca**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.ui.components.MorphGeometryTest"`
Expected: FAIL, "unresolved reference: recenterTranslation" (il file `MorphGeometry.kt` non esiste ancora).

- [ ] **Step 3: Creare `MorphGeometry.kt`**

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

// Pure part of the "scale, then re-center" placement math: no Path/Matrix involved, so this is
// the only piece of this file that can be unit-tested on plain JVM (see MorphGeometryTest and
// the plan's Global Constraints for why the rest cannot).
internal fun recenterTranslation(scaledBounds: Rect, center: Offset): Offset =
    center - scaledBounds.center

// Scales [source] to (targetWidth, targetHeight) and re-centers it on [center], writing into
// [into] (rewound first) via [matrix] (reset first) -- allocates no new Path/Matrix itself, so
// callers that reuse the same [matrix]/[into] across calls (see MorphingShape below) pay zero
// per-call allocation cost.
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

- [ ] **Step 4: Eseguire il test, verificare che passi**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.ui.components.MorphGeometryTest"`
Expected: PASS.

- [ ] **Step 5: Eseguire l'intera suite, confermare zero regressioni**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (nessun altro file usa ancora queste classi).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/ui/components/MorphGeometry.kt app/src/test/java/dev/fred/pacorun/ui/components/MorphGeometryTest.kt
git commit -m "feat: add shared MorphGeometry utility for Morph-shape placement"
```

---

### Task 2: `AmbientShapeField.kt` — vittoria piena

**Files:**
- New: `app/src/main/java/dev/fred/pacorun/ui/components/AmbientMotion.kt`
- Test: `app/src/test/java/dev/fred/pacorun/ui/components/AmbientMotionTest.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/ui/components/AmbientShapeField.kt`

**Interfaces:**
- Produces: `data class AmbientOrbit(val orbitX: Float, val orbitY: Float, val horizontalSway: Float)`, `fun ambientOrbit(phaseDegrees: Float): AmbientOrbit`.
- Consumes: `placeMorphPath` (Task 1, stesso package).

Oggi (`AmbientShapeField.kt`, righe attuali): `phase` (riga 36) letto con `by` a livello di corpo (riga 45), `drawAmbient` (righe 72-81) ricostruisce `Path()`+`Matrix()`+`getBounds()` da zero ad ogni frame per due shape la cui forma e scala (`600.dp` fisso) non cambiano mai — solo la posizione orbita — e avvolge ogni disegno in `rotate(degrees, pivot)` con `degrees` fissato a `0f` (righe 91, 103), un no-op puro. Nota aggiuntiva emersa rileggendo il file: il parametro `bounds: Rect` di `drawAmbient` non viene mai letto nel corpo della funzione — dead parameter, rimosso in questo stesso refactor (non è un secondo bug di classe diversa, solo pulizia incidentale sulle righe già riscritte).

- [ ] **Step 1: Scrivere il test per `ambientOrbit`**

```kotlin
package dev.fred.pacorun.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientMotionTest {
    @Test
    fun zeroPhaseIsAtRestingPosition() {
        val orbit = ambientOrbit(0f)

        assertEquals(1f, orbit.orbitX, 0.0001f)
        assertEquals(0f, orbit.orbitY, 0.0001f)
        assertEquals(0f, orbit.horizontalSway, 0.0001f)
    }

    @Test
    fun ninetyDegreesReachesTopOfOrbit() {
        val orbit = ambientOrbit(90f)

        assertEquals(0f, orbit.orbitX, 0.0001f)
        assertEquals(1f, orbit.orbitY, 0.0001f)
        assertEquals(0f, orbit.horizontalSway, 0.0001f) // sin(180deg) == 0
    }

    @Test
    fun fortyFiveDegreesIsTheSwayPeak() {
        assertEquals(1f, ambientOrbit(45f).horizontalSway, 0.0001f) // sin(90deg) == 1
    }

    @Test
    fun threeSixtyDegreesMatchesZeroExactly() {
        val zero = ambientOrbit(0f)
        val fullCircle = ambientOrbit(360f)

        assertEquals(zero.orbitX, fullCircle.orbitX, 0.0001f)
        assertEquals(zero.orbitY, fullCircle.orbitY, 0.0001f)
        assertEquals(zero.horizontalSway, fullCircle.horizontalSway, 0.0001f)
    }
}
```

- [ ] **Step 2: Eseguire il test, verificare che fallisca**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.ui.components.AmbientMotionTest"`
Expected: FAIL, "unresolved reference: ambientOrbit".

- [ ] **Step 3: Creare `AmbientMotion.kt`**

```kotlin
package dev.fred.pacorun.ui.components

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The orbit position for a phase in degrees [0, 360). The blobs orbit their resting corner in a
 * full circle each cycle (returning exactly to start since cos/sin are continuous across the
 * 360->0 restart), plus a smaller, faster horizontal-only sway layered on top for a less
 * mechanical, more organic drift.
 */
data class AmbientOrbit(val orbitX: Float, val orbitY: Float, val horizontalSway: Float)

fun ambientOrbit(phaseDegrees: Float): AmbientOrbit {
    val radians = phaseDegrees * (PI.toFloat() / 180f)
    return AmbientOrbit(
        orbitX = cos(radians),
        orbitY = sin(radians),
        horizontalSway = sin(radians * 2f),
    )
}
```

- [ ] **Step 4: Eseguire il test, verificare che passi**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.ui.components.AmbientMotionTest"`
Expected: PASS.

- [ ] **Step 5: Riscrivere `AmbientShapeField.kt`**

```kotlin
package dev.fred.pacorun.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph

private val AMBIENT_SHAPE_SIZE = 600.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AmbientShapeField(lightScheme: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ambient-field")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ambient-phase",
    )
    val colors = MaterialTheme.colorScheme
    val primaryAlpha = if (lightScheme) 0.22f else 0.08f
    val tertiaryAlpha = if (lightScheme) 0.20f else 0.07f
    val density = LocalDensity.current
    // Shape and scale never change during the animation -- only on-screen position does (the
    // orbit, computed per-frame below, inside Canvas). So the expensive part (interpolate the
    // Morph, scale to 600dp, re-center on the origin) runs exactly once here, not per frame.
    val placedUpperPath = remember(density) {
        with(density) {
            placeMorphPath(
                source = Morph(MaterialShapes.Oval, MaterialShapes.ClamShell).toPath(progress = 0f, startAngle = -90),
                targetWidth = AMBIENT_SHAPE_SIZE.toPx(),
                targetHeight = AMBIENT_SHAPE_SIZE.toPx(),
                center = Offset.Zero,
                matrix = Matrix(),
                into = Path(),
            )
        }
    }
    val placedLowerPath = remember(density) {
        with(density) {
            placeMorphPath(
                source = MaterialShapes.ClamShell.toPath(startAngle = 24),
                targetWidth = AMBIENT_SHAPE_SIZE.toPx(),
                targetHeight = AMBIENT_SHAPE_SIZE.toPx(),
                center = Offset.Zero,
                matrix = Matrix(),
                into = Path(),
            )
        }
    }

    Canvas(modifier.fillMaxSize().testTag("ambient-shape-field")) {
        val orbit = ambientOrbit(phase.value)
        val orbitRadiusX = 64.dp.toPx()
        val orbitRadiusY = 44.dp.toPx()
        val swayAmplitude = 18.dp.toPx()
        val upperCenter = Offset(
            100.dp.toPx() + orbit.orbitX * orbitRadiusX + orbit.horizontalSway * swayAmplitude,
            90.dp.toPx() + orbit.orbitY * orbitRadiusY,
        )
        val lowerCenter = Offset(
            size.width - 80.dp.toPx() - orbit.orbitX * orbitRadiusX - orbit.horizontalSway * swayAmplitude,
            size.height - 100.dp.toPx() - orbit.orbitY * orbitRadiusY,
        )
        translate(upperCenter.x, upperCenter.y) {
            drawPath(placedUpperPath, colors.primaryContainer.copy(alpha = primaryAlpha))
        }
        translate(lowerCenter.x, lowerCenter.y) {
            drawPath(placedLowerPath, colors.tertiaryContainer.copy(alpha = tertiaryAlpha))
        }
    }
}
```

Nota di equivalenza (per la review del Task 6): `upperCenter`/`lowerCenter` qui sono calcolati identici a `Offset(...)` passato a `drawAmbient` nel codice attuale (stessi termini, stesso ordine); `translate(left, top) { drawPath(placed, color) }` su un path già centrato sull'origine (`center = Offset.Zero` al momento del piazzamento) produce lo stesso pixel-per-pixel risultato di `Path().apply { ...; translate(center - getBounds().center) }` seguito da `drawPath` senza traslazione del canvas — sono due modi equivalenti di sommare lo stesso vettore, uno dentro il `Path`, l'altro nel `Canvas`.

- [ ] **Step 6: Eseguire l'intera suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Lint**

Run: `./gradlew lintDebug`
Expected: PASS (nessun import inutilizzato — `getValue`/`rotate` non sono più importati, `translate`/`LocalDensity` lo sono).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/ui/components/AmbientMotion.kt app/src/test/java/dev/fred/pacorun/ui/components/AmbientMotionTest.kt app/src/main/java/dev/fred/pacorun/ui/components/AmbientShapeField.kt
git commit -m "perf: defer AmbientShapeField's phase read into the Canvas draw phase"
```

---

### Task 3: `HoldToStopButton.kt` — vittoria piena, tecnica diversa

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/ui/session/HoldToStopButton.kt`

**Interfaces:** nessuna nuova — solo modifiche interne alla composable esistente `HoldToStopButton`. Nessuna estrazione di funzione pura (spec, sezione 4.5/6: `coerceIn`/confronto a soglia sono espressioni a una riga).

Oggi (righe attuali): `progress.value` (un `Animatable`) è letto a livello di corpo in tre punti — `progressBarRangeInfo` dentro `.semantics { }` (riga 74, già correttamente differito, nessuna modifica), `Modifier.fillMaxWidth(fraction = ...)` (riga 81), e il testo condizionale (riga 86) — questi ultimi due forzano una recomposition completa ad ogni frame per l'intera durata della pressione (~1.5s, una volta per corsa al massimo).

- [ ] **Step 1: Modificare `HoldToStopButton.kt`**

Import aggiuntivi:

```kotlin
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
```

Sostituire il blocco finale (dal `Box` interno in poi):

```kotlin
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    scaleX = progress.value.coerceIn(0f, 1f)
                }
                .background(colors.error),
        )
        // Only the pressed/not-pressed threshold matters for this text -- derivedStateOf only
        // invalidates when that derived boolean actually flips, not on every progress tick, so
        // this recomposes at most twice per press (start, and cancel if any) instead of every frame.
        val isPressed by remember { derivedStateOf { progress.value > 0f } }
        Text(
            if (isPressed) "Tieni premuto..." else "Tieni premuto per fermare",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onErrorContainer,
        )
    }
}
```

`fillMaxWidth(fraction = progress.value.coerceIn(0f, 1f))` diventa `fillMaxWidth()` pieno, con la stessa frazione applicata come `scaleX` in un `graphicsLayer` ancorato al bordo sinistro (`transformOrigin = TransformOrigin(0f, 0.5f)`) — stessa resa visiva (la barra cresce da sinistra), lettura completamente differita alla fase draw. Stessa tecnica già in uso in questo codebase per `SessionHero`/`HomeScreen` (scala via `graphicsLayer` invece di un `Modifier.size`/`fillMaxWidth` eager).

- [ ] **Step 2: Eseguire l'intera suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (nessun test esistente copre `HoldToStopButton` con test JVM puri — nessuna regressione possibile da rilevare qui, la verifica visiva è dell'utente).

- [ ] **Step 3: Lint**

Run: `./gradlew lintDebug`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/ui/session/HoldToStopButton.kt
git commit -m "perf: defer HoldToStopButton's progress reads into layout/draw phase"
```

---

### Task 4: `SessionHero.kt` — vittoria piena (`urgencyValue`/`breathPhase`) + vittoria parziale onesta (`HeroMorphShape`)

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/ui/session/SessionVisualState.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/ui/session/SessionVisualStateTest.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/ui/session/SessionHero.kt`

**Interfaces:**
- Produces: `fun heroIdlePeakScale(urgency: Float): Float`, `fun heroBreathScale(urgency: Float, breathPhase: Float): Float` (aggiunte a `SessionVisualState.kt`, stesso stile di `urgency()`/`paceLabel()` già lì).
- Consumes: `MorphingShape` (Task 1).

Oggi (`SessionHero.kt`, righe attuali): `urgencyValue` (57-61) e `breathPhase` (69-78) sono letti con `by` a livello di corpo; il loro unico uso — oltre a seminare `deflatingScale` una volta per cambio di `kind` (146-150, lettura sincrona legittima) — è dentro `Modifier.graphicsLayer { scaleX = ...; scaleY = ... }` (170-173). `HeroMorphShape` (92-136 per la selezione/costruzione, 218-233 per la classe) viene interamente ricostruita — quindi il `Morph` reinterpolato da zero — ad ogni frame in cui uno dei tre `*T` cambia, perché `remember(fromKind, kind, runWalkT, walkSpeedT, runSpeedT)` la include tra le chiavi. `runWalkT`/`walkSpeedT`/`runSpeedT` restano necessariamente lette a livello di corpo per `colorProgress` (115-123, fuori scope) — quindi la recomposition di `SessionHero` durante una transizione di fase continua ad avvenire ad ogni frame indipendentemente da questo fix; quello che il fix elimina è lo spreco (allocazioni, reinterpolazione) dentro ciascuna di quelle recomposition comunque necessarie, non le recomposition stesse.

- [ ] **Step 1: Scrivere i test per `heroIdlePeakScale`/`heroBreathScale`**

Aggiungere a `SessionVisualStateTest.kt`, dentro la classe esistente:

```kotlin
    @Test
    fun heroIdlePeakScaleGrowsLinearlyWithUrgency() {
        assertEquals(1f, heroIdlePeakScale(0f), 0.0001f)
        assertEquals(1.08f, heroIdlePeakScale(0.5f), 0.0001f)
        assertEquals(1.16f, heroIdlePeakScale(1f), 0.0001f)
    }

    @Test
    fun heroBreathScaleOscillatesAroundOneWithUrgencyScaledAmplitude() {
        // Midpoint of the breath cycle: no oscillation regardless of urgency.
        assertEquals(1f, heroBreathScale(0f, 0.5f), 0.0001f)
        assertEquals(1f, heroBreathScale(1f, 0.5f), 0.0001f)
        // Peak of the breath cycle (breathPhase = 1f): full amplitude added.
        assertEquals(1.012f, heroBreathScale(0f, 1f), 0.0001f)
        assertEquals(1.052f, heroBreathScale(1f, 1f), 0.0001f)
        // Trough of the breath cycle (breathPhase = 0f): full amplitude subtracted.
        assertEquals(0.988f, heroBreathScale(0f, 0f), 0.0001f)
    }
```

- [ ] **Step 2: Eseguire il test, verificare che fallisca**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.ui.session.SessionVisualStateTest"`
Expected: FAIL, "unresolved reference: heroIdlePeakScale".

- [ ] **Step 3: Aggiungere le funzioni a `SessionVisualState.kt`**

```kotlin
/** 1 at rest, growing linearly to 1.16 as [urgency] approaches 1 (the last 10s of an interval). */
fun heroIdlePeakScale(urgency: Float): Float = 1f + urgency * 0.16f

/** A small oscillation around 1, amplitude growing with [urgency], driven by [breathPhase] in [0, 1]. */
fun heroBreathScale(urgency: Float, breathPhase: Float): Float {
    val amplitude = 0.012f + urgency * 0.04f
    return 1f + (breathPhase - 0.5f) * 2f * amplitude
}
```

- [ ] **Step 4: Eseguire il test, verificare che passi**

Run: `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.ui.session.SessionVisualStateTest"`
Expected: PASS.

- [ ] **Step 5: Applicare il fix `urgencyValue`/`breathPhase` in `SessionHero.kt`**

Sostituire (righe 57-61):

```kotlin
    val urgencyValue = animateFloatAsState(
        targetValue = urgency(state.intervalRemainingMillis),
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "hero-urgency",
    )
```

Rimuovere la riga 62 (`val idlePeakScale = 1f + urgencyValue * 0.16f`) — calcolata ora solo dove serve.

Sostituire (righe 70-78, `breathPhase`):

```kotlin
    val breathPhase = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(breathPeriodMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hero-breath-phase",
    )
```

Rimuovere le righe 79-80 (`breathAmplitude`, `breathScale`) — calcolate ora solo dentro `graphicsLayer`.

Sostituire (righe 146-149, seed di `deflatingScale`):

```kotlin
    val deflatingScale = remember(kind) { Animatable(heroIdlePeakScale(urgencyValue.value)) }
    LaunchedEffect(kind) {
        deflatingScale.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }
```

Rimuovere la riga 150 originale (`val currentScale = maxOf(idlePeakScale, deflatingScale.value)`) — calcolata ora dentro `graphicsLayer`.

Sostituire il `Box` (righe 167-176):

```kotlin
        Box(
            modifier = Modifier
                .size(240.dp)
                .graphicsLayer {
                    val urgency = urgencyValue.value
                    val breathScale = heroBreathScale(urgency, breathPhase.value)
                    val scale = maxOf(heroIdlePeakScale(urgency), deflatingScale.value) * breathScale
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(heroColor),
            contentAlignment = Alignment.Center,
        ) {
```

- [ ] **Step 6: Eseguire l'intera suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Sostituire `HeroMorphShape` con `MorphingShape`**

Aggiungere import: `import dev.fred.pacorun.ui.components.MorphingShape`.

Sostituire il blocco `val shape = remember(fromKind, kind, runWalkT, walkSpeedT, runSpeedT) { ... }` (righe 124-136):

```kotlin
    // MorphingShape reads its progress lazily (inside createOutline, draw phase) via the lambda
    // below -- so this remember only needs fromKind/kind as keys, not the live *T floats: the
    // shape instance now persists for the whole transition instead of being rebuilt (and its
    // Morph re-interpolated from scratch) every single frame one of the *T values ticks.
    val shape = remember(fromKind, kind) {
        when {
            fromKind == HeroKind.RUN_COUNTDOWN && kind == HeroKind.WALK_COUNTDOWN -> MorphingShape(runWalkMorph) { runWalkT }
            fromKind == HeroKind.WALK_COUNTDOWN && kind == HeroKind.RUN_COUNTDOWN -> MorphingShape(runWalkMorph) { runWalkT }
            fromKind == HeroKind.WALK_COUNTDOWN && kind == HeroKind.SPEED -> MorphingShape(walkSpeedMorph) { walkSpeedT }
            fromKind == HeroKind.SPEED && kind == HeroKind.WALK_COUNTDOWN -> MorphingShape(walkSpeedMorph) { walkSpeedT }
            fromKind == HeroKind.RUN_COUNTDOWN && kind == HeroKind.SPEED -> MorphingShape(runSpeedMorph) { runSpeedT }
            fromKind == HeroKind.SPEED && kind == HeroKind.RUN_COUNTDOWN -> MorphingShape(runSpeedMorph) { runSpeedT }
            kind == HeroKind.RUN_COUNTDOWN -> MorphingShape(runWalkMorph) { 0f }
            kind == HeroKind.WALK_COUNTDOWN -> MorphingShape(runWalkMorph) { 1f }
            else -> MorphingShape(walkSpeedMorph) { 1f }
        }
    }
```

`runWalkT`/`walkSpeedT`/`runSpeedT` restano dichiarati con `by` (righe 97-105, invariate) per `colorProgress` — la lambda `{ runWalkT }` referenzia una proprietà delegata: ogni riferimento a `runWalkT` rigenera una chiamata a `getValue()` nel punto in cui il codice viene effettivamente eseguito, quindi la lettura dentro `createOutline` (fase draw) è tracciata come lettura di draw, non di composizione — stesso principio della sezione 1 della spec.

Rimuovere il blocco righe 218-233 (il commento esplicativo alle righe 218-220 e la classe privata `HeroMorphShape` alle righe 221-233) — entrambi ora orfani, sostituiti da `MorphingShape` (Task 1).

- [ ] **Step 8: Eseguire l'intera suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Lint**

Run: `./gradlew lintDebug`
Expected: PASS (import `Matrix`/`Outline`/`Path`/`Shape`/`Density`/`LayoutDirection`/`Size`/`center` restano usati altrove nel file — solo `getValue` per `urgencyValue`/`breathPhase` va verificato: resta comunque usato per `runWalkT` etc., quindi nessun import da rimuovere qui).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/ui/session/SessionVisualState.kt app/src/test/java/dev/fred/pacorun/ui/session/SessionVisualStateTest.kt app/src/main/java/dev/fred/pacorun/ui/session/SessionHero.kt
git commit -m "perf: defer SessionHero's breath/urgency reads and share MorphingShape for HeroMorphShape"
```

---

### Task 5: `HomeScreen.kt` — `RoutineScene`/`MorphShape` — vittoria parziale + il pezzo `offset{}` pienamente chiudibile

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/ui/home/HomeScreen.kt`

**Interfaces:** nessuna nuova — usa `MorphingShape` (Task 1).

Oggi (righe attuali): `MorphShape` (375-387) viene ricostruita ad ogni frame in cui `morphProgress` cambia (`remember(primaryMorph, morphProgress)`, riga 214) — stesso spreco di `HeroMorphShape`. `morphProgress` (181-184) non ha altro consumatore nel file, quindi la sua lettura a livello di corpo sparirà del tutto. `primarySize`/`primaryX`/`primaryY`/`secondaryX`/`secondaryY` (185-204) sono letti a livello di corpo e alimentano `Modifier.size()`/`.offset(x, y)` (eager, riga 218 per `primaryModifier`, riga 221 per `secondaryModifier`) — `primaryX`/`primaryY`/`secondaryX`/`secondaryY` hanno un overload lambda-differito (`Modifier.offset(offset: Density.() -> IntOffset)`), `primarySize` no (nessun `Modifier.size` lambda-differito esiste in Compose Foundation — chiudere anche questo richiederebbe un `Modifier.layout{}` custom, esplicitamente fuori scope). `secondaryScale`/`secondaryAlpha` (205-212) sono già dentro `graphicsLayer` (222-226) — nessuna modifica. `secondaryShape` (215) è già internamente `remember`ata da `RoundedPolygon.toShape()` — nessuna modifica (spec, sezione 2).

- [ ] **Step 1: Aggiungere import**

```kotlin
import androidx.compose.ui.unit.IntOffset
import dev.fred.pacorun.ui.components.MorphingShape
```

Rimuovere gli import diventati inutilizzati dopo la rimozione di `MorphShape` (Step 3): `androidx.compose.ui.geometry.Size`, `androidx.compose.ui.geometry.center`, `androidx.compose.ui.graphics.Matrix`, `androidx.compose.ui.graphics.Outline`, `androidx.compose.ui.graphics.Path`, `androidx.compose.ui.unit.Density`, `androidx.compose.ui.unit.LayoutDirection` (nessuno di questi è usato altrove nel file — `Shape` resta, usato da `PhaseBackdrop`).

- [ ] **Step 2: Deferire `morphProgress`/`primaryX`/`primaryY`/`secondaryX`/`secondaryY`, sostituire `MorphShape`**

Sostituire (righe 181-184, `morphProgress`):

```kotlin
    val morphProgress = transition.animateFloat(
        transitionSpec = { motionScheme.defaultSpatialSpec() },
        label = "primary-shape-morph",
    ) { if (it == RoutineMode.CONSTANT) 1f else 0f }
```

Sostituire (righe 189-204, `primaryX`/`primaryY`/`secondaryX`/`secondaryY` — `primarySize`, righe 185-188, resta invariato con `by`):

```kotlin
    val primaryX = transition.animateDp(
        transitionSpec = { motionScheme.defaultSpatialSpec() },
        label = "primary-shape-x",
    ) { if (it == RoutineMode.CONSTANT) 0.dp else (-78).dp }
    val primaryY = transition.animateDp(
        transitionSpec = { motionScheme.defaultSpatialSpec() },
        label = "primary-shape-y",
    ) { if (it == RoutineMode.CONSTANT) 8.dp else (-52).dp }
    val secondaryX = transition.animateDp(
        transitionSpec = { motionScheme.defaultSpatialSpec() },
        label = "secondary-shape-x",
    ) { if (it == RoutineMode.CONSTANT) 0.dp else 78.dp }
    val secondaryY = transition.animateDp(
        transitionSpec = { motionScheme.defaultSpatialSpec() },
        label = "secondary-shape-y",
    ) { if (it == RoutineMode.CONSTANT) 8.dp else 52.dp }
```

Sostituire (riga 214, `primaryShape`):

```kotlin
    val primaryShape = remember(primaryMorph) { MorphingShape(primaryMorph) { morphProgress.value } }
```

Sostituire (righe 218-226, i due modifier):

```kotlin
    val primaryModifier = Modifier
        .size(primarySize)
        .offset { IntOffset(primaryX.value.roundToPx(), primaryY.value.roundToPx()) }
    val secondaryModifier = Modifier
        .size(224.dp)
        .offset { IntOffset(secondaryX.value.roundToPx(), secondaryY.value.roundToPx()) }
        .graphicsLayer {
            alpha = secondaryAlpha
            scaleX = secondaryScale
            scaleY = secondaryScale
        }
```

`secondaryShape` (riga 215, `val secondaryShape = MaterialShapes.ClamShell.toShape(startAngle = 24)`) resta esattamente invariata — nessun `remember` esterno aggiunto (sarebbe ridondante, `toShape()` lo fa già internamente, spec sezione 2).

Nota onesta (da riportare identica nel commit/PR): `primarySize` resta letto a livello di corpo (nessun overload lambda-differito per `Modifier.size` in Compose Foundation) — quindi `RoutineScene` continua a ricomporre per l'intera durata della transizione di modalità, alla stessa cadenza di oggi. Il beneficio di questo step non è "meno recomposition" ma meno lavoro dentro ciascuna: `morphProgress` esce completamente dallo scope di composizione (nessun consumatore rimasto a livello di corpo), e i 4 parametri Dp deferiti non vengono più ricreati/diffati come argomenti eager del modifier chain ad ogni ricomposizione.

- [ ] **Step 3: Rimuovere la classe `MorphShape` (righe 375-387)**

- [ ] **Step 4: Eseguire l'intera suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Lint**

Run: `./gradlew lintDebug`
Expected: PASS (nessun import inutilizzato residuo).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/fred/pacorun/ui/home/HomeScreen.kt
git commit -m "perf: defer RoutineScene's morph/offset reads and share MorphingShape for MorphShape"
```

---

### Task 6: Verifica finale e dual-review

**Files:** nessuno — checkpoint.

- [ ] **Step 1: Suite completa da zero**

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Entrambi devono essere puliti.

- [ ] **Step 2: Self-review**

Rileggere il diff completo dei commit di questo piano (`git log -p` sui 5 commit, o `git diff <primo-commit-di-questa-fase>^..HEAD`) e confermare:
- Nessun file toccato oltre a quelli elencati in "File Structure".
- Nessun parametro di `tween`/`infiniteRepeatable`/`updateTransition`/`animateFloatAsState` è cambiato in nessuno dei 4 file (grep dei valori numerici — `900`, `30_000`, `1100`/`3400`/`2000`, `640`, `320`, `200`, `1_500L` — contro il file pre-refactor).
- `MorphGeometry.kt`'s `placeMorphPath`/`MorphingShape` riproducono esattamente la sequenza `rewind → addPath → Matrix().scale → transform → translate(center - getBounds().center)` usata oggi in tutti e tre i call site originari (diff riga-per-riga contro `git show <commit-pre-Task-1> -- app/src/main/java/dev/fred/pacorun/ui/components/AmbientShapeField.kt app/src/main/java/dev/fred/pacorun/ui/session/SessionHero.kt app/src/main/java/dev/fred/pacorun/ui/home/HomeScreen.kt` e contro `RoundedPolygon.toShape()` in `material3-android:1.5.0-alpha27`).
- `HeroMorphShape`/`MorphShape` non esistono più nel codice (`grep -rn "HeroMorphShape\|class MorphShape" app/src/main`).
- `HoldToStopButton.kt`'s barra di riempimento (`graphicsLayer { scaleX = ... }`) produce la stessa larghezza visibile di `fillMaxWidth(fraction = ...)` per ogni valore di `progress` in `[0, 1]` (verifica algebrica: un `Box` a larghezza piena scalato di `f` sull'asse X con origine a sinistra occupa visivamente `f * larghezza-piena`, identico a `fillMaxWidth(fraction = f)`).

- [ ] **Step 3: Review indipendente (subagente fresco, senza contesto pregresso)**

Chiedere al subagente di confermare specificamente:
- (a) In `SessionHero.kt`, la lambda `{ runWalkT }`/`{ walkSpeedT }`/`{ runSpeedT }` passata a `MorphingShape` legge davvero il valore corrente ad ogni chiamata di `createOutline` (non un valore congelato al momento della creazione della lambda) — verificabile leggendo la dichiarazione `by transition.animateFloat(...)` di quelle tre proprietà e confermando che restano `by`-delegate (non variabili locali plain) dopo il refactor.
- (b) In `HomeScreen.kt`, `primarySize` è l'unico dei parametri della `updateTransition` di `RoutineScene` rimasto a livello di corpo dopo il refactor — nessun altro `by`/lettura non deferita è rimasto per errore (`grep -n "by transition\." app/src/main/java/dev/fred/pacorun/ui/home/HomeScreen.kt` deve mostrare solo `primarySize`, `secondaryScale`, `secondaryAlpha`).
- (c) `AmbientShapeField.kt`'s nuovo `translate(upperCenter.x, upperCenter.y) { drawPath(...) }` usa esattamente gli stessi termini (`100.dp.toPx() + orbit.orbitX * orbitRadiusX + ...`) del codice pre-refactor, senza segni invertiti o termini mancanti.
- (d) Nessun file fuori dalla "File Structure" di questo piano è stato toccato (`git diff --stat <primo-commit>^..HEAD`).

- [ ] **Step 4: Riepilogo all'utente**

Riportare un riepilogo all'utente e fermarsi qui. Non procedere alla creazione del branch/worktree dedicato (`feature/ui-animation-performance`), al merge, o a qualunque altro lavoro senza un via libera esplicito — stessa regola permanente di ogni fase precedente di questo progetto. La verifica visiva finale (che l'app renda esattamente come prima su schermo) resta, come sempre, a carico dell'utente sul dispositivo reale — non è compito di questo piano automatizzarla.

**Esplicitamente fuori scope per questo piano (non farle):** chiudere il gap `primarySize` di `RoutineScene` con un `Modifier.layout{}` custom; qualunque cambio visivo non richiesto esplicitamente; estrarre funzioni pure per le espressioni triviali di `HoldToStopButton.kt`.
