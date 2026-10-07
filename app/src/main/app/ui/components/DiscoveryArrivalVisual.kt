package com.starglean.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.starglean.app.ui.space.DiscoveryArrival
import com.starglean.app.ui.space.DiscoveryLight
import com.starglean.app.ui.space.DiscoveryStardust
import kotlin.math.pow
import kotlin.math.sin

/** The home constellation material at close range: one pearl core, irregular mist and fine dust. */
@Composable
fun DiscoveryFlyingStar(progress: () -> Float, origin: Offset, modifier: Modifier = Modifier) {
    // Unit-space brushes are reused and transformed at draw time, not rebuilt for every mote/frame.
    val coreBrush = remember {
        Brush.radialGradient(
            0f to Color(0xFFFFFBF0),
            .09f to Color(0xF2FFFBF0),
            .23f to Color(0xA8FCEEDA),
            .46f to Color(0x35EFDBBB),
            .72f to Color(0x08D7C9BB),
            1f to Color.Transparent,
            center = Offset.Zero, radius = 1f,
        )
    }
    val mistBrush = remember { arrivalMist(Color(0xFFEFDCD4)) }
    val coolMistBrush = remember { arrivalMist(Color(0xFF98ACCE)) }
    val bloomBrush = remember { arrivalMist(Color(0xFFFFE7BE)) }
    val dustBrush = remember { arrivalMist(Color(0xFFF5DBB8)) }
    Canvas(modifier.fillMaxSize()) {
        val frame = DiscoveryArrival.sample(progress(), origin.x, origin.y)
        if (frame.starAlpha <= 0f) return@Canvas
        val point = Offset(size.width * frame.x, size.height * frame.y)
        val alpha = frame.starAlpha
        val depth = ((frame.scale - 1f) / 47f).coerceIn(0f, 1f)
        val light = DiscoveryLight.sample(frame.travel, depth)
        val emerging = ((frame.travel - .08f) / .65f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        // Keep the bright nucleus small. The surrounding volume, not a solid disc, fills the lens.
        val core = .90f.dp.toPx() * frame.scale.pow(.46f)
        val cloud = 12f.dp.toPx() * frame.scale.pow(.74f)

        // Unequal, off-centre pockets leave gaps in the veil: no concentric halo, rays or ring.
        arrivalMistPockets.forEachIndexed { index, pocket ->
            val drift = sin(frame.travel * 1.7f + index * 2.1f) * .025f * frame.travel
            arrivalGlow(
                brush = if (index % 3 == 0) coolMistBrush else mistBrush,
                radius = cloud * pocket.radius,
                at = point + Offset((pocket.x + drift) * cloud, pocket.y * cloud),
                alpha = pocket.opacity * emerging * alpha * light.mistMultiplier,
                aspect = Offset(pocket.stretch, 1f),
            )
        }

        // Each grain has depth: the cloud opens toward the viewer while pinpoints stay delicate.
        DiscoveryStardust.motes.forEach { mote ->
            val perspective = 1f / (1f - mote.z * frame.travel * .55f)
            val offset = Offset(mote.x * cloud * perspective, mote.y * cloud * perspective)
            val position = point + offset
            val shimmer = .9f + .1f * sin(frame.travel * 3f + mote.x * 12f + mote.z * 8f)
            val opacity = mote.intensity * emerging * alpha * shimmer
            val radius = mote.radius.dp.toPx() * (.68f + .78f * depth) * perspective
            if (mote.glint) {
                val color = lerp(Color(0xFFD5DDEB), Color(0xFFFFE7C3), mote.warmth)
                arrivalGlow(dustBrush, radius * 5f, position, opacity * .22f)
                drawCircle(color.copy(alpha = opacity), radius, position)
            } else {
                val defocus = 1f + mote.z.coerceAtLeast(0f) * depth * 3f
                arrivalGlow(dustBrush, radius * 3.5f * defocus, position, opacity * .76f)
            }
        }

        // The final soft exposure grows out of the same material as the local champagne light.
        arrivalGlow(bloomBrush, cloud * 1.25f, point, light.exposureAlpha * alpha,
            aspect = Offset(1.12f, .86f))
        arrivalGlow(mistBrush, core * 13f, point, .12f * alpha * light.coreAlpha)
        arrivalGlow(coreBrush, core * 3.8f, point, alpha * light.coreAlpha)
    }
}

private data class ArrivalMistPocket(
    val x: Float, val y: Float, val radius: Float, val stretch: Float, val opacity: Float,
)

private val arrivalMistPockets = listOf(
    ArrivalMistPocket(-.40f, .10f, .42f, 1.4f, .09f),
    ArrivalMistPocket(.20f, -.18f, .54f, 1.1f, .10f),
    ArrivalMistPocket(-.10f, -.09f, .28f, 1.7f, .12f),
    ArrivalMistPocket(.47f, .19f, .30f, 1.3f, .065f),
    ArrivalMistPocket(-.24f, .28f, .38f, 1.2f, .08f),
    ArrivalMistPocket(.02f, .18f, .36f, 1.4f, .10f),
    ArrivalMistPocket(.09f, -.43f, .28f, 1.2f, .06f),
    ArrivalMistPocket(-.58f, -.22f, .26f, 1.5f, .05f),
)

private fun arrivalMist(color: Color): Brush = Brush.radialGradient(
    0f to color,
    .22f to color.copy(alpha = .63f),
    .47f to color.copy(alpha = .20f),
    .72f to color.copy(alpha = .025f),
    1f to Color.Transparent,
    center = Offset.Zero, radius = 1f,
)

private fun DrawScope.arrivalGlow(
    brush: Brush,
    radius: Float,
    at: Offset,
    alpha: Float,
    aspect: Offset = Offset(1f, 1f),
) {
    if (alpha <= .001f || radius <= 0f) return
    withTransform({
        translate(at.x, at.y)
        scale(radius * aspect.x, radius * aspect.y, pivot = Offset.Zero)
    }) {
        drawCircle(brush, radius = 1f, center = Offset.Zero, alpha = alpha.coerceIn(0f, 1f))
    }
}

/** Content-local light: transparent at the edges, never drawn over the bottom navigation. */
@Composable
fun DiscoveryArrivalLight(progress: () -> Float, modifier: Modifier = Modifier) {
    val brush = remember {
        Brush.radialGradient(
            0f to Color(0xFFF3DEC2),
            .18f to Color(0xBFF0DAC0),
            .42f to Color(0x59DECEBE),
            .7f to Color(0x12C1BEC4),
            1f to Color.Transparent,
            center = Offset.Zero, radius = 1f,
        )
    }
    Canvas(modifier.fillMaxSize().pointerInput(Unit) {
        // Block only the arriving reading surface. Navigation and system Back remain available.
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }) {
        val frame = DiscoveryArrival.sample(progress())
        arrivalGlow(brush, size.minDimension * .49f,
            Offset(size.width * .5f, size.height * .46f), frame.lightAlpha,
            aspect = Offset(1f, .88f))
    }
}
