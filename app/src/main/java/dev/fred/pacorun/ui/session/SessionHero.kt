package dev.fred.pacorun.ui.session

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import dev.fred.pacorun.routine.RoutinePhase
import dev.fred.pacorun.service.RunSessionState
import dev.fred.pacorun.ui.UiTags
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SessionHero(state: RunSessionState.Running, modifier: Modifier = Modifier) {
    val kind = heroKind(state.phase)
    val colors = MaterialTheme.colorScheme
    val motionScheme = MaterialTheme.motionScheme
    val urgencyValue = urgency(state.intervalRemainingMillis)

    val breathPeriodMillis = when (kind) {
        HeroKind.RUN_COUNTDOWN -> 650
        HeroKind.WALK_COUNTDOWN -> 2600
        HeroKind.SPEED -> 1400
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
    val breathAmplitude = 0.04f + urgencyValue * 0.12f
    val breathScale = 1f + (breathPhase - 0.5f) * 2f * breathAmplitude
    val urgencyScale = 1f + urgencyValue * 0.18f

    val targetPolygon = polygonFor(kind)
    var lastPolygon by remember { mutableStateOf(targetPolygon) }
    val transition = updateTransition(kind, label = "hero-kind")
    val morphProgress by transition.animateFloat(
        transitionSpec = { motionScheme.defaultSpatialSpec() },
        label = "hero-morph-progress",
    ) { if (it == kind) 1f else 0f }
    LaunchedEffect(transition.targetState, transition.isRunning) {
        if (!transition.isRunning) lastPolygon = polygonFor(transition.targetState)
    }
    val morph = remember(lastPolygon, targetPolygon) { Morph(lastPolygon, targetPolygon) }
    val shape = remember(morph, morphProgress) { HeroMorphShape(morph, morphProgress) }

    val heroColor = when (kind) {
        HeroKind.RUN_COUNTDOWN -> colors.primary
        HeroKind.WALK_COUNTDOWN -> colors.tertiary
        HeroKind.SPEED -> colors.secondary
    }
    val onHeroColor = when (kind) {
        HeroKind.RUN_COUNTDOWN -> colors.onPrimary
        HeroKind.WALK_COUNTDOWN -> colors.onTertiary
        HeroKind.SPEED -> colors.onSecondary
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            phaseTitle(state.phase),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = heroColor,
            modifier = Modifier.testTag(UiTags.activePhase),
        )
        Box(
            modifier = Modifier
                .size(240.dp)
                .graphicsLayer {
                    scaleX = breathScale * urgencyScale
                    scaleY = breathScale * urgencyScale
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun polygonFor(kind: HeroKind): RoundedPolygon = when (kind) {
    HeroKind.RUN_COUNTDOWN -> MaterialShapes.Oval
    HeroKind.WALK_COUNTDOWN -> MaterialShapes.Cookie6Sided
    HeroKind.SPEED -> MaterialShapes.Pill
}

private fun phaseTitle(phase: RoutinePhase): String = when (phase) {
    RoutinePhase.RUN -> "Corsa"
    RoutinePhase.WALK -> "Camminata"
    RoutinePhase.CONSTANT, RoutinePhase.CONSTANT_BEFORE, RoutinePhase.CONSTANT_AFTER -> "Costante"
}

private fun Float.decimal() = String.format(Locale.ITALY, "%.1f", this)

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
