package dev.fred.pacorun.ui.home

import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toPath
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import dev.fred.pacorun.location.LocationReadiness
import dev.fred.pacorun.settings.RoutineMode
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.ui.UiTags
import dev.fred.pacorun.ui.components.AmbientShapeField
import dev.fred.pacorun.ui.components.PacoRunScaffold
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onModeChange: (RoutineMode) -> Unit,
) {
    val buttonHeight = ButtonDefaults.MediumContainerHeight
    val colors = MaterialTheme.colorScheme
    val lightScheme = colors.surface.luminance() > 0.5f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (lightScheme) colors.surfaceContainerHigh else colors.surfaceContainer)
            .testTag("home-scene-background"),
    ) {
        AmbientShapeField(lightScheme)
        PacoRunScaffold(
            title = null,
            status = null,
            primaryAction = null,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                HomeHeader(state.locationReadiness)
                RoutineSummary(
                    preferences = state.preferences,
                    onModeChange = onModeChange,
                    modifier = Modifier.weight(1f),
                )
                HomeActions(
                    buttonHeight = buttonHeight,
                    enabled = state.preferences.isValid,
                    onStart = onStart,
                    onEdit = onEdit,
                )
            }
        }
    }
}

@Composable
private fun HomeHeader(readiness: LocationReadiness) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Paco Run", style = MaterialTheme.typography.titleLargeEmphasized)
        AssistChip(
            onClick = {},
            label = { Text(readiness.label) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            border = null,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeActions(
    buttonHeight: Dp,
    enabled: Boolean,
    onStart: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Button(
            onClick = onStart,
            shapes = ButtonDefaults.shapesFor(buttonHeight),
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = buttonHeight)
                .testTag(UiTags.start),
            contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight),
        ) {
            Text("Avvia", style = ButtonDefaults.textStyleFor(buttonHeight))
        }
        TextButton(
            onClick = onEdit,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.editRoutine),
        ) { Text("Modifica routine") }
    }
}

@Composable
private fun RoutineSummary(
    preferences: RunPreferences,
    onModeChange: (RoutineMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ModeSelector(preferences.mode, onModeChange)
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            RoutineScene(preferences)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RoutineScene(preferences: RunPreferences) {
    val motionScheme = MaterialTheme.motionScheme
    val transition = updateTransition(preferences.mode, label = "routine-scene")
    val morphProgress by transition.animateFloat(
        transitionSpec = { motionScheme.defaultSpatialSpec() },
        label = "primary-shape-morph",
    ) { if (it == RoutineMode.CONSTANT) 1f else 0f }
    val primarySize by transition.animateDp(
        transitionSpec = { motionScheme.defaultSpatialSpec() },
        label = "primary-shape-size",
    ) { if (it == RoutineMode.CONSTANT) 320.dp else 222.dp }
    val primaryX by transition.animateDp(
        transitionSpec = { motionScheme.defaultSpatialSpec() },
        label = "primary-shape-x",
    ) { if (it == RoutineMode.CONSTANT) 0.dp else (-78).dp }
    val primaryY by transition.animateDp(
        transitionSpec = { motionScheme.defaultSpatialSpec() },
        label = "primary-shape-y",
    ) { if (it == RoutineMode.CONSTANT) 8.dp else (-52).dp }
    val secondaryX by transition.animateDp(
        transitionSpec = { motionScheme.defaultSpatialSpec() },
        label = "secondary-shape-x",
    ) { if (it == RoutineMode.CONSTANT) 0.dp else 78.dp }
    val secondaryY by transition.animateDp(
        transitionSpec = { motionScheme.defaultSpatialSpec() },
        label = "secondary-shape-y",
    ) { if (it == RoutineMode.CONSTANT) 8.dp else 52.dp }
    val secondaryScale by transition.animateFloat(
        transitionSpec = { motionScheme.defaultSpatialSpec() },
        label = "secondary-shape-scale",
    ) { if (it == RoutineMode.CONSTANT) 0.55f else 1f }
    val secondaryAlpha by transition.animateFloat(
        transitionSpec = { motionScheme.fastEffectsSpec() },
        label = "secondary-shape-alpha",
    ) { if (it == RoutineMode.CONSTANT) 0f else 1f }
    val primaryMorph = remember { Morph(MaterialShapes.Oval, MaterialShapes.ClamShell) }
    val primaryShape = remember(primaryMorph, morphProgress) { MorphShape(primaryMorph, morphProgress) }
    val secondaryShape = MaterialShapes.ClamShell.toShape(startAngle = 24)
    val walkRunVisible = transition.currentState == RoutineMode.WALK_RUN ||
        transition.targetState == RoutineMode.WALK_RUN
    val primaryModifier = Modifier.size(primarySize).offset(primaryX, primaryY)
    val secondaryModifier = Modifier
        .size(224.dp)
        .offset(secondaryX, secondaryY)
        .graphicsLayer {
            alpha = secondaryAlpha
            scaleX = secondaryScale
            scaleY = secondaryScale
        }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .testTag(if (preferences.mode == RoutineMode.CONSTANT) "constant-scene" else "walk-run-scene"),
        contentAlignment = Alignment.Center,
    ) {
        if (walkRunVisible) {
            Text(
                "${preferences.initialConstantKm.number()} km iniziali · " +
                    if (preferences.routineUnlimited) "Senza fine" else "Fino a ${preferences.routineKm.number()} km",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.TopCenter).offset(y = (-12).dp),
                textAlign = TextAlign.Center,
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().testTag("phase-backdrop-layer"),
            contentAlignment = Alignment.Center,
        ) {
            PhaseBackdrop(
                shape = primaryShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.86f),
                tag = if (preferences.mode == RoutineMode.CONSTANT) "constant-phase-shape" else "run-phase-shape",
                modifier = primaryModifier,
            )
            if (walkRunVisible) {
                PhaseBackdrop(
                    shape = secondaryShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.78f),
                    tag = "walk-phase-shape",
                    modifier = secondaryModifier,
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxSize().testTag("phase-content-layer"),
            contentAlignment = Alignment.Center,
        ) {
            PhaseContent(
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                tag = if (preferences.mode == RoutineMode.CONSTANT) "constant-phase-content" else "run-phase-content",
                modifier = primaryModifier,
            ) {
                if (preferences.mode == RoutineMode.CONSTANT) {
                    ConstantTypography(preferences.constantRange.label)
                } else {
                    PhaseTypography("Corsa", preferences.runSeconds, preferences.runRange.label)
                }
            }
            if (walkRunVisible) {
                PhaseContent(
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    tag = "walk-phase-content",
                    modifier = secondaryModifier,
                ) {
                    PhaseTypography(
                        "Camminata",
                        preferences.walkSeconds,
                        preferences.walkRange.label,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhaseBackdrop(shape: Shape, color: Color, tag: String, modifier: Modifier) {
    Box(modifier.clip(shape).background(color).testTag(tag))
}

@Composable
private fun PhaseContent(
    color: Color,
    tag: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.testTag(tag),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides color, content = content)
        }
    }
}

@Composable
private fun ConstantTypography(pace: String) {
    Text(
        "Andatura costante",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        textAlign = TextAlign.Center,
    )
    Text(
        pace,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 16.sp,
            maxFontSize = MaterialTheme.typography.headlineLarge.fontSize,
            stepSize = 1.sp,
        ),
        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun PhaseTypography(label: String, seconds: Int, pace: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        textAlign = TextAlign.Center,
    )
    Text(
        seconds.duration(),
        autoSize = TextAutoSize.StepBased(
            minFontSize = 20.sp,
            maxFontSize = MaterialTheme.typography.headlineLarge.fontSize,
            stepSize = 1.sp,
        ),
        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
    )
    Text(
        pace,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 12.sp,
            maxFontSize = MaterialTheme.typography.bodyLarge.fontSize,
            stepSize = 1.sp,
        ),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private class MorphShape(morph: Morph, progress: Float) : Shape {
    private val source = morph.toPath(progress = progress, startAngle = -90)
    private val path = Path()

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        path.rewind()
        path.addPath(source)
        path.transform(Matrix().apply { scale(size.width, size.height) })
        path.translate(size.center - path.getBounds().center)
        return Outline.Generic(path)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModeSelector(mode: RoutineMode, onModeChange: (RoutineMode) -> Unit) {
    ButtonGroup(
        overflowIndicator = {},
        modifier = Modifier.fillMaxWidth(),
    ) {
        toggleableItem(
            checked = mode == RoutineMode.CONSTANT,
            label = "Costante",
            onCheckedChange = { if (it) onModeChange(RoutineMode.CONSTANT) },
            weight = 1f,
        )
        toggleableItem(
            checked = mode == RoutineMode.WALK_RUN,
            label = "Cammina–Corri",
            onCheckedChange = { if (it) onModeChange(RoutineMode.WALK_RUN) },
            weight = 1f,
        )
    }
}

private val LocationReadiness.label: String
    get() = when (this) {
        LocationReadiness.READY -> "GPS pronto"
        LocationReadiness.LOCATION_OFF -> "Posizione disattivata"
        LocationReadiness.PERMISSION_NEEDED -> "Autorizzazione necessaria"
        LocationReadiness.APPROXIMATE_ONLY -> "Posizione approssimativa"
    }

private val dev.fred.pacorun.settings.SpeedRange.label: String
    get() = "${minKmh.number()}–${maxKmh.number()} km/h"

private fun Float.number() = String.format(Locale.ITALY, "%.1f", this).removeSuffix(",0")

private fun Int.duration() = String.format(Locale.US, "%d:%02d", this / 60, this.mod(60))
