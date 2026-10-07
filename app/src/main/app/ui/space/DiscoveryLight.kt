package com.starglean.app.ui.space

/** Close-range light becomes dispersed dust rather than a persistent bright bead. */
object DiscoveryLight {
    data class Frame(val coreAlpha: Float, val mistMultiplier: Float, val exposureAlpha: Float)

    fun sample(travel: Float, depth: Float): Frame {
        val t = if (travel.isFinite()) travel.coerceIn(0f, 1f) else 0f
        val d = if (depth.isFinite()) depth.coerceIn(0f, 1f) else 0f
        val dissolve = ((t - .52f) / .40f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        return Frame(coreAlpha = .82f * (1f - dissolve), mistMultiplier = 2f / 3f,
            exposureAlpha = .16f * d * d)
    }
}
