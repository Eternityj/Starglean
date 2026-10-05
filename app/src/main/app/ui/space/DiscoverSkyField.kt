package com.starglean.app.ui.space

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A quiet, non-interactive starfield for Discover, independent of Android drawing.
 * Positions and twinkle are analytical functions of time rather than frame counts:
 * pausing at time zero is static, and changing frame rate cannot change the path.
 */
object DiscoverSkyField {
    enum class Layer { FAR, MID, NEAR }

    data class Star(
        val layer: Layer,
        /** Initial coordinates in the padded field, normalized to [0, 1). */
        val x: Float,
        val y: Float,
        val depth: Float,
        val radiusDp: Float,
        val alpha: Float,
        /** Palette mix: 0 is cool silver-blue; 1 is warm champagne. */
        val warmth: Float,
        val phase: Float,
        val periodSeconds: Float,
        val flare: Boolean,
    )

    data class Sample(val x: Float, val y: Float, val alpha: Float)

    val stars: List<Star> = createStars()

    /**
     * [width] and [height] are viewport units, normally physical pixels. Radius is
     * kept separately in dp so the drawing layer can apply the device density.
     * All layers drift right and slightly upward; nearby stars move faster.
     * Wrapping happens only outside the viewport, while the star is fully faded.
     */
    fun sample(star: Star, elapsedSeconds: Double, width: Float, height: Float): Sample {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) {
            return Sample(0f, 0f, 0f)
        }
        if (!star.x.isFinite() || !star.y.isFinite() || !star.depth.isFinite() ||
            !star.alpha.isFinite() || !star.phase.isFinite() ||
            !star.periodSeconds.isFinite() || star.periodSeconds <= 0f
        ) {
            return Sample(0f, 0f, 0f)
        }
        val w = width.toDouble()
        val h = height.toDouble()
        val shortSide = min(w, h)
        val padding = shortSide * PADDING_FRACTION
        val spanX = w + padding * 2.0
        val spanY = h + padding * 2.0
        val elapsed = if (elapsedSeconds.isFinite()) elapsedSeconds.coerceAtLeast(0.0) else 0.0
        val speed = shortSide * speedFraction(star.depth)

        // Reduce time before multiplication. This stays bounded even after a very
        // long-running session and avoids precision loss from accumulating frames.
        fun position(initial: Float, span: Double, velocity: Double): Double {
            val cycle = span / kotlin.math.abs(velocity)
            val travel = (elapsed % cycle) * velocity
            return positiveModulo(initial.toDouble() * span + travel, span) - padding
        }
        val x = position(star.x, spanX, speed * DIRECTION_X)
        val y = position(star.y, spanY, speed * DIRECTION_Y)
        val visibility = edgeFade(x, w, padding) * edgeFade(y, h, padding)
        val period = star.periodSeconds.toDouble()
        val phase = (elapsed % period) / period * 2.0 * PI + star.phase
        // More legible breathing without blinking off: the nearest stars retain >56% light.
        val amplitude = .12 + .10 * star.depth.coerceIn(0f, 1f)
        val twinkle = 1.0 - amplitude + amplitude * sin(phase)
        val alpha = (star.alpha.coerceIn(0f, 1f) * visibility * twinkle).coerceIn(0.0, 1.0)
        // Viewports cannot normally approach Float.MAX_VALUE, but malformed
        // dimensions still must not deliver infinities to a drawing API.
        val safeX = x.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
        val safeY = y.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
        return Sample(safeX, safeY, alpha.toFloat())
    }

    internal fun createStars(): List<Star> {
        val random = Random(0x44534B59)
        val result = ArrayList<Star>(392)
        val layers = listOf(Layer.FAR to 248, Layer.MID to 112, Layer.NEAR to 32)

        fun between(low: Float, high: Float): Float = low + random.nextFloat() * (high - low)

        layers.forEach { (layer, count) ->
            repeat(count) { index ->
                val depth = when (layer) {
                    Layer.FAR -> between(.10f, .27f)
                    Layer.MID -> between(.39f, .65f)
                    Layer.NEAR -> between(.79f, .99f)
                }
                val radius = when (layer) {
                    Layer.FAR -> between(.34f, .64f)
                    Layer.MID -> between(.54f, .96f)
                    Layer.NEAR -> between(.94f, 1.48f)
                }
                val opacity = when (layer) {
                    Layer.FAR -> between(.22f, .45f)
                    Layer.MID -> between(.36f, .63f)
                    Layer.NEAR -> between(.58f, .83f)
                }
                result += Star(
                    layer = layer,
                    x = random.nextFloat(),
                    y = random.nextFloat(),
                    depth = depth,
                    radiusDp = radius,
                    alpha = opacity,
                    warmth = if (random.nextFloat() < .18f) between(.12f, .35f) else between(.50f, .93f),
                    phase = between(0f, (2.0 * PI).toFloat()),
                    periodSeconds = between(3.6f, 8f),
                    flare = layer == Layer.NEAR && index % 7 == 0,
                )
            }
        }
        return result.toList()
    }

    /** Screen-short-side fractions per second: distant ~.3%, nearby ~1.2% (3× V1). */
    private fun speedFraction(depth: Float): Double =
        .0021 + .0120 * depth.coerceIn(0f, 1f).toDouble().pow(1.7)

    private fun edgeFade(position: Double, extent: Double, padding: Double): Double {
        val distanceFromWrap = min(position + padding, extent + padding - position)
        val t = (distanceFromWrap / (padding * 1.6)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun positiveModulo(value: Double, span: Double): Double = ((value % span) + span) % span

    private const val PADDING_FRACTION = .065
    private const val DIRECTION_X = .924
    private const val DIRECTION_Y = -.382
}
