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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AmbientShapeField(lightScheme: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ambient-field")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ambient-phase",
    )
    val radians = phase * (PI.toFloat() / 180f)
    // The blobs orbit their resting corner in a full circle each cycle (returning exactly to
    // start since cos/sin are continuous across the 360->0 restart), plus a smaller, faster
    // horizontal-only sway layered on top for a less mechanical, more organic drift.
    val orbitX = cos(radians)
    val orbitY = sin(radians)
    val horizontalSway = sin(radians * 2f)
    val colors = MaterialTheme.colorScheme
    val primaryAlpha = if (lightScheme) 0.22f else 0.08f
    val tertiaryAlpha = if (lightScheme) 0.20f else 0.07f
    val upperPath = remember {
        Morph(MaterialShapes.Oval, MaterialShapes.ClamShell).toPath(progress = 0f, startAngle = -90)
    }
    val lowerPath = MaterialShapes.ClamShell.toPath(startAngle = 24)
    val upperBounds = upperPath.getBounds()
    val lowerBounds = lowerPath.getBounds()

    Canvas(modifier.fillMaxSize().testTag("ambient-shape-field")) {
        val upperWidth = 600.dp.toPx()
        val upperHeight = 600.dp.toPx()
        val lowerWidth = 600.dp.toPx()
        val lowerHeight = 600.dp.toPx()
        val orbitRadiusX = 64.dp.toPx()
        val orbitRadiusY = 44.dp.toPx()
        val swayAmplitude = 18.dp.toPx()
        val upperCenterY = 90.dp.toPx()
        val lowerCenterOffset = -100.dp.toPx()
        fun drawAmbient(path: Path, bounds: Rect, width: Float, height: Float, center: Offset, degrees: Float, color: Color) {
            val placedPath = Path().apply {
                addPath(path)
                transform(Matrix().apply { scale(width, height) })
                translate(center - getBounds().center)
            }
            rotate(degrees, pivot = center) {
                drawPath(placedPath, color)
            }
        }
        drawAmbient(
            upperPath,
            upperBounds,
            upperWidth,
            upperHeight,
            Offset(
                100.dp.toPx() + orbitX * orbitRadiusX + horizontalSway * swayAmplitude,
                upperCenterY + orbitY * orbitRadiusY,
            ),
            0f,
            colors.primaryContainer.copy(alpha = primaryAlpha),
        )
        drawAmbient(
            lowerPath,
            lowerBounds,
            lowerWidth,
            lowerHeight,
            Offset(
                size.width - 80.dp.toPx() - orbitX * orbitRadiusX - horizontalSway * swayAmplitude,
                size.height + lowerCenterOffset - orbitY * orbitRadiusY,
            ),
            0f,
            colors.tertiaryContainer.copy(alpha = tertiaryAlpha),
        )
    }
}
