package dev.fred.pacorun.ui.session

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import dev.fred.pacorun.routine.RoutinePhase
import dev.fred.pacorun.service.RunSessionState
import dev.fred.pacorun.ui.UiTags
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SessionHero(state: RunSessionState.Running, modifier: Modifier = Modifier) {
    val kind = heroKind(state.phase)
    val colors = MaterialTheme.colorScheme

    // Smooth, continuous growth in the last 10s even though remaining only ticks once/second.
    val urgencyValue by animateFloatAsState(
        targetValue = urgency(state.intervalRemainingMillis),
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "hero-urgency",
    )
    val idlePeakScale = 1f + urgencyValue * 0.16f

    val breathPeriodMillis = when (kind) {
        HeroKind.RUN_COUNTDOWN -> 1100
        HeroKind.WALK_COUNTDOWN -> 3400
        HeroKind.SPEED -> 2000
    }
    val infinite = rememberInfiniteTransition(label = "hero-breath")
    val breathPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(breathPeriodMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hero-breath-phase",
    )
    val breathAmplitude = 0.012f + urgencyValue * 0.04f
    val breathScale = 1f + (breathPhase - 0.5f) * 2f * breathAmplitude

    // Same technique as the Home screen's Cammina/Corri toggle: fixed, permanent Morph
    // objects (one per shape pair), never rebuilt, with `updateTransition` driving progress
    // between 0 and 1. The previous approach here built a fresh `Morph(fromKind, kind)` on
    // every transition, so the "A"/"B" roles swapped depending on direction -- sometimes
    // Morph(Oval, ClamShell), sometimes Morph(ClamShell, Oval) -- and each pairing settled at
    // a measurably different size, position, and rotation than the shape's own pure render
    // (confirmed via logging: bounds off by ~15-20%, center off by hundreds of pixels, and an
    // apparent mirror-image tilt on the ellipse). A single fixed morph per pair, animated
    // forward for one direction and backward for the other, has no role to swap, and this
    // exact pattern has never shown the problem on Home.
    val transition = updateTransition(kind, label = "hero-shape")
    val runWalkMorph = remember { Morph(MaterialShapes.Oval, MaterialShapes.ClamShell) }
    val walkSpeedMorph = remember { Morph(MaterialShapes.ClamShell, MaterialShapes.Pill) }
    val runSpeedMorph = remember { Morph(MaterialShapes.Oval, MaterialShapes.Pill) }
    val shapeSpec = tween<Float>(640, easing = FastOutSlowInEasing)
    val runWalkT by transition.animateFloat(transitionSpec = { shapeSpec }, label = "run-walk") {
        if (it == HeroKind.WALK_COUNTDOWN) 1f else 0f
    }
    val walkSpeedT by transition.animateFloat(transitionSpec = { shapeSpec }, label = "walk-speed") {
        if (it == HeroKind.SPEED) 1f else 0f
    }
    val runSpeedT by transition.animateFloat(transitionSpec = { shapeSpec }, label = "run-speed") {
        if (it == HeroKind.SPEED) 1f else 0f
    }
    // `currentState` is managed by Compose itself: it stays at the *previous* kind for the
    // whole transition and only snaps to match `kind` once every child animation above has
    // finished -- exactly the semantics needed for "from", without any custom tracking.
    val fromKind = transition.currentState
    // Each raw T above means "0 = <kind that maps to 0>, 1 = <kind that maps to 1>" for its
    // OWN fixed pair, regardless of which literal direction is currently playing -- e.g.
    // `runWalkT` runs 0->1 for RUN->WALK but 1->0 for WALK->RUN. Colors need a value that
    // always means "0 = fromKind's color, 1 = kind's color", so whichever direction runs
    // opposite to its raw T's own fixed 0/1 mapping must be inverted here.
    val colorProgress = when {
        fromKind == HeroKind.RUN_COUNTDOWN && kind == HeroKind.WALK_COUNTDOWN -> runWalkT
        fromKind == HeroKind.WALK_COUNTDOWN && kind == HeroKind.RUN_COUNTDOWN -> 1f - runWalkT
        fromKind == HeroKind.WALK_COUNTDOWN && kind == HeroKind.SPEED -> walkSpeedT
        fromKind == HeroKind.SPEED && kind == HeroKind.WALK_COUNTDOWN -> 1f - walkSpeedT
        fromKind == HeroKind.RUN_COUNTDOWN && kind == HeroKind.SPEED -> runSpeedT
        fromKind == HeroKind.SPEED && kind == HeroKind.RUN_COUNTDOWN -> 1f - runSpeedT
        else -> 1f
    }
    val shape = remember(fromKind, kind, runWalkT, walkSpeedT, runSpeedT) {
        when {
            fromKind == HeroKind.RUN_COUNTDOWN && kind == HeroKind.WALK_COUNTDOWN -> HeroMorphShape(runWalkMorph, runWalkT)
            fromKind == HeroKind.WALK_COUNTDOWN && kind == HeroKind.RUN_COUNTDOWN -> HeroMorphShape(runWalkMorph, runWalkT)
            fromKind == HeroKind.WALK_COUNTDOWN && kind == HeroKind.SPEED -> HeroMorphShape(walkSpeedMorph, walkSpeedT)
            fromKind == HeroKind.SPEED && kind == HeroKind.WALK_COUNTDOWN -> HeroMorphShape(walkSpeedMorph, walkSpeedT)
            fromKind == HeroKind.RUN_COUNTDOWN && kind == HeroKind.SPEED -> HeroMorphShape(runSpeedMorph, runSpeedT)
            fromKind == HeroKind.SPEED && kind == HeroKind.RUN_COUNTDOWN -> HeroMorphShape(runSpeedMorph, runSpeedT)
            kind == HeroKind.RUN_COUNTDOWN -> HeroMorphShape(runWalkMorph, 0f)
            kind == HeroKind.WALK_COUNTDOWN -> HeroMorphShape(runWalkMorph, 1f)
            else -> HeroMorphShape(walkSpeedMorph, 1f)
        }
    }
    val heroColor = lerp(colorFor(fromKind, colors), colorFor(kind, colors), colorProgress)
    val onHeroColor = lerp(onColorFor(fromKind, colors), onColorFor(kind, colors), colorProgress)

    // The shape doesn't jump to an arbitrary fixed "peak" size the instant it morphs -- it
    // keeps *whatever size it already had* at that exact instant (continuous, matching the
    // urgency growth that was already playing) and deflates smoothly from there to normal.
    // `remember(kind)` captures `idlePeakScale`'s value synchronously, in the very
    // composition pass where `kind` changes -- same trick used for the shape/morph race fix
    // above -- so there's no frame where a stale or default value could be read instead.
    val deflatingScale = remember(kind) { Animatable(idlePeakScale) }
    LaunchedEffect(kind) {
        deflatingScale.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }
    val currentScale = maxOf(idlePeakScale, deflatingScale.value)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        // 30dp: the smallest gap that still clears the shape's own worst-case reach above its
        // resting box -- idlePeakScale (up to 1.16x, right at a phase boundary) combined with
        // breathScale's own peak (up to ~1.05x) can grow the 240dp box by up to ~26.4dp on
        // each side; this keeps a small buffer on top of that so it can never touch the title.
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Text(
            phaseTitle(state.phase),
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
            color = heroColor,
            modifier = Modifier.testTag(UiTags.activePhase),
        )
        Box(
            modifier = Modifier
                .size(240.dp)
                .graphicsLayer {
                    scaleX = breathScale * currentScale
                    scaleY = breathScale * currentScale
                }
                .clip(shape)
                .background(heroColor),
            contentAlignment = Alignment.Center,
        ) {
            when (kind) {
                HeroKind.SPEED -> Text(
                    state.speedKmh?.let { it.decimal() } ?: "—",
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                    color = onHeroColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(UiTags.activeSpeed),
                )
                HeroKind.RUN_COUNTDOWN, HeroKind.WALK_COUNTDOWN -> Text(
                    state.intervalRemainingMillis?.let(::countdownText) ?: "--",
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                    color = onHeroColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(UiTags.activeRemaining),
                )
            }
        }
    }
}

private fun colorFor(kind: HeroKind, colors: androidx.compose.material3.ColorScheme): Color = when (kind) {
    HeroKind.RUN_COUNTDOWN -> colors.primary
    HeroKind.WALK_COUNTDOWN -> colors.tertiary
    HeroKind.SPEED -> colors.secondary
}

private fun onColorFor(kind: HeroKind, colors: androidx.compose.material3.ColorScheme): Color = when (kind) {
    HeroKind.RUN_COUNTDOWN -> colors.onPrimary
    HeroKind.WALK_COUNTDOWN -> colors.onTertiary
    HeroKind.SPEED -> colors.onSecondary
}

private fun phaseTitle(phase: RoutinePhase): String = when (phase) {
    RoutinePhase.RUN -> "Corsa"
    RoutinePhase.WALK -> "Camminata"
    RoutinePhase.CONSTANT, RoutinePhase.CONSTANT_BEFORE -> "Costante"
}

private fun Float.decimal() = String.format(Locale.ITALY, "%.1f", this)

// Identical to Home's own `MorphShape` (HomeScreen.kt) -- the exact rendering technique
// already proven correct there, deliberately not reintroducing any of the extra bounds
// normalization tried (and abandoned) here earlier.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private class HeroMorphShape(morph: Morph, progress: Float) : Shape {
    private val source = morph.toPath(progress = progress.coerceIn(0f, 1f), startAngle = -90)
    private val path = Path()

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        path.rewind()
        path.addPath(source)
        path.transform(Matrix().apply { scale(size.width, size.height) })
        path.translate(size.center - path.getBounds().center)
        return Outline.Generic(path)
    }
}
