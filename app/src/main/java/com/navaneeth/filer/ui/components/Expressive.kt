package com.navaneeth.filer.ui.components

import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Material 3 Expressive building blocks: large numbers, playful shapes, muted colour families,
// wavy progress and simple charts. Used by the Storage screens.

// region Type

/**
 * Display styles. Values that are the point of a card ("224 MB") are bold, in one size across
 * cards ([Number]); page titles are bold; supporting headings stay regular, as in the M3 scale.
 */
object ExpressiveType {
    val Hero = TextStyle(fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold)
    val Big = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
    val Number = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
    val LargeTitle = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
    val Section = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    val Overline = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
}

// endregion

// region Colour

/**
 * Tonal colour roles. BLUE, TEAL and SKY are one family now (kinds are told apart by shape, not
 * hue); SLATE is neutral; GREEN (safe, kept, free) and AMBER (stale, filling up) mark a state, so
 * use them only for that. Everything is derived from the theme's scheme, so it works with Filer
 * blue and with phone colours alike, and containers stay muted (no bright pastel fills).
 */
enum class Accent { BLUE, TEAL, SKY, GREEN, AMBER, SLATE }

/** [strong] fills icon shapes and buttons on a [container]. */
@Immutable
data class AccentColors(val container: Color, val onContainer: Color, val strong: Color, val onStrong: Color)

@Composable
fun Accent.colors(): AccentColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    // A container is the strong colour mixed well into the page surface: a quiet tint, never a pastel.
    fun tonal(strong: Color, onStrong: Color) = AccentColors(lerp(strong, scheme.surface, CONTAINER_MIX), scheme.onSurface, strong, onStrong)
    return when (this) {
        Accent.BLUE, Accent.TEAL, Accent.SKY -> tonal(scheme.secondary, scheme.onSecondary)
        Accent.SLATE -> AccentColors(scheme.surfaceContainerHighest, scheme.onSurface, scheme.onSurfaceVariant, scheme.surface)
        Accent.GREEN -> if (dark) tonal(Color(0xFF8FD1A6), Color(0xFF06391A)) else tonal(Color(0xFF2E6B48), Color.White)
        Accent.AMBER -> if (dark) tonal(Color(0xFFE8B66E), Color(0xFF482900)) else tonal(Color(0xFF8A5A1A), Color.White)
    }
}

/** How far an accent's container is mixed into the surface (higher = quieter). */
private const val CONTAINER_MIX = 0.84f

// endregion

// region Shapes

/** A circle with [lobes] soft bumps around its edge ("cookie" and "flower" in M3 Expressive). */
class ScallopShape(private val lobes: Int, private val depth: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = minOf(size.width, size.height) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val path = Path()
        for (step in 0..POINTS) {
            val angle = step.toFloat() / POINTS * 2f * PI.toFloat()
            val r = radius * (1f - depth + depth * cos(lobes * angle))
            val x = center.x + r * cos(angle)
            val y = center.y + r * sin(angle)
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }

    private companion object {
        const val POINTS = 120
    }
}

object ExpressiveShapes {
    val Cookie = ScallopShape(lobes = 9, depth = 0.09f)
    val Flower = ScallopShape(lobes = 6, depth = 0.14f)
    val Squircle = RoundedCornerShape(30)
    val Pill = RoundedCornerShape(50)
}

/** An icon centred on a filled shape. */
@Composable
fun ShapedIcon(
    icon: ImageVector,
    shape: Shape,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = size / 2
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
    }
}

// endregion

// region Progress and charts

/**
 * A wavy progress line: a moving wave up to [progress], a flat track after it. With a null
 * [progress] the wave fills the whole width (work of unknown length).
 */
@Composable
fun WavyProgressIndicator(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
    thickness: Dp = 5.dp
) {
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "wavePhase"
    )
    Canvas(modifier.height(14.dp)) {
        val stroke = thickness.toPx()
        val midY = size.height / 2
        val amplitude = size.height / 2 - stroke / 2
        val waveEnd = size.width * (progress ?: 1f).coerceIn(0f, 1f)
        val gap = if (progress == null || waveEnd >= size.width) 0f else stroke * 2

        if (waveEnd + gap < size.width) {
            drawLine(trackColor, Offset(waveEnd + gap, midY), Offset(size.width - stroke / 2, midY), stroke, StrokeCap.Round)
        }
        if (waveEnd > stroke) {
            val wave = Path()
            var x = stroke / 2
            wave.moveTo(x, midY + amplitude * sin(x / WAVELENGTH_PX + phase))
            while (x < waveEnd) {
                x += 2f
                wave.lineTo(x, midY + amplitude * sin(x / WAVELENGTH_PX + phase))
            }
            drawPath(wave, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

private const val WAVELENGTH_PX = 9f

/**
 * Expressive loading indicator: a scalloped shape that turns and morphs between the cookie and
 * flower outlines, on a soft circular container. For waits of unknown length (a first scan).
 */
@Composable
fun ExpressiveLoader(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
) {
    val transition = rememberInfiniteTransition(label = "loader")
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "loaderTurn"
    )
    val morph by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "loaderMorph"
    )
    Canvas(modifier.size(size)) {
        drawCircle(containerColor)
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val radius = this.size.minDimension * 0.36f
        val start = turn * PI.toFloat() / 180f
        val shape = Path()
        for (step in 0..LOADER_POINTS) {
            val angle = step.toFloat() / LOADER_POINTS * 2f * PI.toFloat()
            // Blend the two outlines point by point so the change is smooth.
            val cookie = 1f - 0.09f + 0.09f * cos(9 * angle)
            val flower = 1f - 0.16f + 0.16f * cos(6 * angle)
            val r = radius * (cookie + (flower - cookie) * morph)
            val x = center.x + r * cos(angle + start)
            val y = center.y + r * sin(angle + start)
            if (step == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
        }
        shape.close()
        drawPath(shape, color)
    }
}

private const val LOADER_POINTS = 96

@Immutable
data class RingSegment(val fraction: Float, val color: Color)

/** A donut chart with rounded, separated segments that sweep in when first shown. */
@Composable
fun UsageRing(
    segments: List<RingSegment>,
    modifier: Modifier = Modifier,
    thickness: Dp = 28.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest
) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) { sweep.animateTo(1f, tween(900)) }

    Canvas(modifier) {
        val stroke = thickness.toPx()
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val arcSize = Size(diameter, diameter)
        // A round cap reaches past the arc's end; leave room for both caps plus a small gap.
        val capDegrees = (stroke / 2) / (diameter / 2) * (180f / PI.toFloat())
        val gapDegrees = 4f

        drawArc(trackColor, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        var start = -90f
        for (segment in segments) {
            val total = segment.fraction * 360f * sweep.value
            val visible = total - gapDegrees - 2 * capDegrees
            if (visible > 0f) {
                drawArc(segment.color, start + capDegrees + gapDegrees / 2, visible, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            start += total
        }
    }
}

/** A horizontal bar split into rounded, separated parts sized by [weights]. */
@Composable
fun SplitBar(weights: List<Pair<Float, Color>>, modifier: Modifier = Modifier, height: Dp = 24.dp) {
    Row(modifier.height(height).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        weights.filter { it.first > 0f }.forEach { (weight, color) ->
            Box(
                Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(height / 3))
                    .background(color)
            )
        }
    }
}

/** Small bar chart: one bar per value, the last one picked out in [highlight]. */
@Composable
fun MiniBars(values: List<Long>, color: Color, highlight: Color, modifier: Modifier = Modifier) {
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { index, value ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight((value.toFloat() / max).coerceAtLeast(0.08f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (index == values.lastIndex) highlight else color)
            )
        }
    }
}

/** A thick rounded progress bar for sizes ("DCIM 38 GB"). */
@Composable
fun SizeBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .clip(RoundedCornerShape(height / 2))
                    .background(color)
            )
        }
    }
}

/** A small coloured square, as a chart legend key. */
@Composable
fun LegendKey(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Spacer(
        modifier
            .width(size)
            .height(size)
            .clip(RoundedCornerShape(size / 3))
            .background(color)
    )
}

// endregion
