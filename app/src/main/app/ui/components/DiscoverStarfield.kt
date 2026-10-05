package com.starglean.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.starglean.app.ui.space.DiscoverSkyField
import kotlinx.coroutines.isActive
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Discovery-only atmosphere. Decorative: it never handles a gesture or exposes diary targets. */
@Composable
fun DiscoverStarfield(
    motionEnabled: Boolean,
    subdued: Boolean,
    modifier: Modifier = Modifier,
    departureKey: Int = 0,
    reserveDeparture: Boolean = false,
    hideDeparture: Boolean = false,
    onDepartureOrigin: (Offset) -> Unit = {},
) {
    val time = remember { mutableStateOf(0.0) }
    val reading = remember { Animatable(if (subdued) 1f else 0f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val dust = remember { createSkyDust() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var departureIndex by remember { mutableStateOf<Int?>(null) }
    var departureOrigin by remember { mutableStateOf(Offset(.74f, .25f)) }
    var capturedKey by remember { mutableStateOf(0) }
    val publishOrigin by rememberUpdatedState(onDepartureOrigin)

    LaunchedEffect(departureKey, viewport) {
        if (departureKey == 0 || capturedKey == departureKey || viewport.width == 0 || viewport.height == 0) return@LaunchedEffect
        val width = viewport.width.toFloat()
        val height = viewport.height.toFloat()
        val candidate = DiscoverSkyField.stars.mapIndexedNotNull { index, star ->
            if (star.depth < .39f) return@mapIndexedNotNull null
            val sample = DiscoverSkyField.sample(star, time.value, width, height)
            val normalized = Offset(sample.x / width, sample.y / height)
            if (normalized.x !in .55f.. .88f || normalized.y !in .16f.. .36f || sample.alpha < .25f) null
            else index to normalized
        }.minByOrNull { (_, point) ->
            val dx = point.x - .74f
            val dy = point.y - .25f
            dx * dx + dy * dy
        } ?: DiscoverSkyField.stars.mapIndexed { index, star ->
            val sample = DiscoverSkyField.sample(star, time.value, width, height)
            index to Offset(sample.x / width, sample.y / height)
        }.minBy { (_, point) -> (point - Offset(.74f, .25f)).getDistanceSquared() }
        departureIndex = candidate.first
        departureOrigin = candidate.second
        capturedKey = departureKey
        publishOrigin(departureOrigin)
    }

    LaunchedEffect(subdued, motionEnabled) {
        val target = if (subdued) 1f else 0f
        if (motionEnabled) reading.animateTo(target, tween(850)) else reading.snapTo(target)
    }
    LaunchedEffect(lifecycleOwner, motionEnabled) {
        if (!motionEnabled) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Reset the frame baseline after backgrounding, not the star positions.
            var previous = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (previous != 0L) {
                        val dt = ((now - previous) / 1_000_000_000.0).coerceIn(0.0, .05)
                        // Faster idle drift still settles to the original quiet reading speed.
                        time.value += dt * (1.0 - .98 * reading.value)
                    }
                    previous = now
                }
            }
        }
    }

    Box(modifier.fillMaxSize().onSizeChanged { viewport = it }.drawWithCache {
        // Brushes and fixed seeds are cached; animation state is read only in the draw phase.
        val base = Brush.verticalGradient(listOf(Color(0xFF020610), Color(0xFF050C18), Color(0xFF030913)))
        val edgeShade = Brush.verticalGradient(
            0f to Color(0x99020610), .17f to Color.Transparent,
            .81f to Color.Transparent, 1f to Color(0x99020610),
        )
        val veilRadius = size.width * .62f
        val blueVeil = Brush.radialGradient(
            listOf(Color(0x12476586), Color(0x06344765), Color.Transparent),
            center = Offset.Zero, radius = veilRadius,
        )
        val violetVeil = Brush.radialGradient(
            listOf(Color(0x0B655575), Color(0x04334963), Color.Transparent),
            center = Offset.Zero, radius = veilRadius,
        )
        val paints = DiscoverSkyField.stars.map { star ->
            val color = lerp(Color(0xFFCEDFF6), Color(0xFFF4DBAF), star.warmth)
            val radius = star.radiusDp.dp.toPx()
            StarPaint(
                color, radius,
                Brush.radialGradient(
                    listOf(color.copy(alpha = .17f), color.copy(alpha = .025f), Color.Transparent),
                    center = Offset.Zero, radius = radius * 11f,
                ),
            )
        }
        onDrawBehind {
            val seconds = time.value
            val retreat = reading.value
            val backgroundAlpha = 1f - .72f * retreat
            drawRect(base)

            // A faint diagonal dust band, with broad, slowly shifting pockets of haze.
            // The centre stays quiet for the approaching star and the diary paper.
            val breathX = sin(seconds * .062).toFloat() * size.width * .044f
            val breathY = sin(seconds * .038).toFloat() * size.height * .025f
            repeat(7) { index ->
                val y = -.02f + index * .17f
                val x = .13f + .68f * y + .09f * sin(index * 1.8f)
                translate(x * size.width + breathX, y * size.height + breathY) {
                    drawCircle(
                        if (index % 3 == 0) violetVeil else blueVeil,
                        radius = veilRadius, center = Offset.Zero,
                        alpha = backgroundAlpha * if (index in 2..4) .44f else .85f,
                    )
                }
            }

            val dustDriftX = (seconds * .0024 % 1.2).toFloat()
            val dustDriftY = (-seconds * .00096 % 1.2).toFloat()
            dust.forEach { grain ->
                val x = wrapSky(grain.x + dustDriftX) * size.width
                val y = wrapSky(grain.y + dustDriftY) * size.height
                val quiet = quietCentre(x / size.width, y / size.height)
                drawCircle(
                    Color(0xFFB6C6E0).copy(alpha = grain.alpha * quiet * backgroundAlpha),
                    radius = grain.radiusDp.dp.toPx(), center = Offset(x, y),
                )
            }

            DiscoverSkyField.stars.forEachIndexed { index, star ->
                if (hideDeparture && index == departureIndex) return@forEachIndexed
                val sample = DiscoverSkyField.sample(star, seconds, size.width, size.height)
                val paint = paints[index]
                val point = if (reserveDeparture && index == departureIndex) {
                    Offset(departureOrigin.x * size.width, departureOrigin.y * size.height)
                } else Offset(sample.x, sample.y)
                val alpha = sample.alpha * backgroundAlpha * quietCentre(sample.x / size.width, sample.y / size.height)
                if (alpha > .008f) {
                    if (star.depth > .50f) {
                        translate(point.x, point.y) {
                            drawCircle(paint.glow, paint.radius * 11f, Offset.Zero, alpha = alpha)
                        }
                    }
                    if (star.flare) {
                        val ray = paint.radius * 5f
                        drawLine(paint.color.copy(alpha = alpha * .20f),
                            point - Offset(ray, 0f), point + Offset(ray, 0f), .40f.dp.toPx(), StrokeCap.Round)
                        drawLine(paint.color.copy(alpha = alpha * .14f),
                            point - Offset(0f, ray * 1.35f), point + Offset(0f, ray * 1.35f), .32f.dp.toPx(), StrokeCap.Round)
                    }
                    drawCircle(paint.color.copy(alpha = alpha), paint.radius, point)
                    if (star.flare) drawCircle(Color(0xFFFFF7E9).copy(alpha = alpha * .88f), paint.radius * .5f, point)
                }
            }
            drawRect(edgeShade)
            if (retreat > 0f) drawRect(Color(0xFF02050D).copy(alpha = retreat * .18f))
        }
    })
}

private data class StarPaint(val color: Color, val radius: Float, val glow: Brush)
private data class SkyDust(val x: Float, val y: Float, val radiusDp: Float, val alpha: Float)

private fun createSkyDust(): List<SkyDust> {
    val random = Random(20260915)
    return List(580) {
        val y = random.nextFloat() * 1.2f - .1f
        val spread = (random.nextFloat() + random.nextFloat() + random.nextFloat() - 1.5f) * .25f
        SkyDust(
            x = .13f + .68f * y + .065f * sin(y * 9f) + spread,
            y = y,
            radiusDp = .16f + random.nextFloat() * .22f,
            alpha = .07f + random.nextFloat() * .17f,
        )
    }
}

private fun wrapSky(value: Float): Float = ((value + .1f) % 1.2f + 1.2f) % 1.2f - .1f

private fun quietCentre(x: Float, y: Float): Float {
    val dx = (x - .5f) / .35f
    val dy = (y - .54f) / .20f
    return 1f - .58f * exp(-(dx * dx + dy * dy) * 1.6f)
}
