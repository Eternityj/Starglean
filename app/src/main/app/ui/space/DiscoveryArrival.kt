package com.starglean.app.ui.space

/**
 * One sky star approaches, dissolves into local champagne light, then yields to
 * the diary and its saved weather together. No opaque cover or hidden page cut.
 * Reduced motion bypasses this timeline in the caller.
 */
object DiscoveryArrival {
    const val APPROACH_MILLIS = 1800
    const val LIGHT_START_MILLIS = 1500
    const val LIGHT_PEAK_MILLIS = 1800
    const val REVEAL_START_MILLIS = 1770
    const val REVEAL_MILLIS = 560
    const val TOTAL_MILLIS = REVEAL_START_MILLIS + REVEAL_MILLIS
    const val MAX_LIGHT_ALPHA = .32f
    private const val STAR_FADE_START_MILLIS = 1680
    private const val STAR_FADE_MILLIS = 280

    data class Frame(
        val travel: Float,
        val x: Float,
        val y: Float,
        /** Multiplier for the selected background star's original core radius. */
        val scale: Float,
        val starAlpha: Float,
        /** A strictly local soft glow, never an opaque full-screen cover. */
        val lightAlpha: Float,
        /** Shared by the paper, reading controls and author's weather background. */
        val revealAlpha: Float,
    )

    /**
     * [originX] and [originY] allow the caller to promote an actual background star
     * into the foreground, hiding that original while this one travels. Coordinates
     * are normalized viewport positions. The arrival target is always (.5, .46).
     */
    fun sample(progress: Float, originX: Float = .74f, originY: Float = .25f): Frame {
        val elapsed = normalized(progress).toDouble() * TOTAL_MILLIS
        val approach = (elapsed / APPROACH_MILLIS).coerceIn(0.0, 1.0)
        val travel = smoothstep(approach)
        val startX = if (originX.isFinite()) originX.coerceIn(0f, 1f) else .74f
        val startY = if (originY.isFinite()) originY.coerceIn(0f, 1f) else .25f
        val starFade = smoothstep(((elapsed - STAR_FADE_START_MILLIS) / STAR_FADE_MILLIS).coerceIn(0.0, 1.0))
        val light = when {
            elapsed < LIGHT_START_MILLIS -> 0.0
            elapsed < LIGHT_PEAK_MILLIS -> smoothstep(
                ((elapsed - LIGHT_START_MILLIS) / (LIGHT_PEAK_MILLIS - LIGHT_START_MILLIS)).coerceIn(0.0, 1.0))
            else -> 1.0 - smoothstep(
                ((elapsed - LIGHT_PEAK_MILLIS) / (TOTAL_MILLIS - LIGHT_PEAK_MILLIS)).coerceIn(0.0, 1.0))
        }
        val reveal = smoothstep(((elapsed - REVEAL_START_MILLIS) / REVEAL_MILLIS).coerceIn(0.0, 1.0))
        // Inverse camera distance makes the final approach expand perceptibly
        // faster than a linear scale tween, but never reaches a singularity.
        val scale = 1.0 / (1.0 - travel * (1.0 - 1.0 / 48.0))
        return Frame(
            travel = travel.toFloat(),
            x = (startX + (.5 - startX) * travel).toFloat(),
            y = (startY + (.46 - startY) * travel).toFloat(),
            scale = scale.toFloat().coerceIn(1f, 48f),
            starAlpha = (1.0 - starFade).toFloat(),
            lightAlpha = light.toFloat() * MAX_LIGHT_ALPHA,
            revealAlpha = reveal.toFloat(),
        )
    }

    private fun normalized(progress: Float): Float =
        if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)

    private fun smoothstep(value: Double): Double = value * value * (3.0 - 2.0 * value)
}
