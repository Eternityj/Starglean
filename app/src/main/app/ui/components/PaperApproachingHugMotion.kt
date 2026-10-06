package com.starglean.app.ui.components

import kotlin.math.PI
import kotlin.math.sin

/** Leaves room for the visible walk-in, arm movement, and a full quiet second together. */
internal const val PAPER_APPROACHING_HUG_DURATION_MS = 3600

internal data class PaperApproachingHugFrame(
    val alpha: Float,
    val approach: Float,
    val reach: Float,
    val embrace: Float,
    val squeeze: Float,
    val swayDegrees: Float,
    val settleY: Float,
)

/**
 * Four independent phases let the renderer move each body and both pairs of arms.
 * The embrace stays closed during the fade; only the small squeeze relaxes.
 */
internal fun paperApproachingHugFrame(progress: Float): PaperApproachingHugFrame {
    val p = paperReactionProgress(progress)
    val squeeze = paperReactionStage(p, .50f, .60f) * (1f - paperReactionStage(p, .76f, .90f))
    val contactTime = ((p - .48f) / .14f).coerceIn(0f, 1f)
    val settle = sin(contactTime * PI.toFloat()) * (1f - contactTime)
    val swayTime = ((p - .52f) / .34f).coerceIn(0f, 1f)
    val sway = sin(swayTime * PI.toFloat() * 2f) * sin(swayTime * PI.toFloat()) * .7f
    return PaperApproachingHugFrame(
        alpha = paperReactionStage(p, 0f, .09f) * (1f - paperReactionStage(p, .86f, 1f)),
        approach = paperReactionStage(p, .07f, .37f),
        reach = paperReactionStage(p, .10f, .32f),
        embrace = paperReactionStage(p, .30f, .52f),
        squeeze = squeeze,
        swayDegrees = sway,
        settleY = 1.2f * squeeze + .38f * settle,
    )
}
