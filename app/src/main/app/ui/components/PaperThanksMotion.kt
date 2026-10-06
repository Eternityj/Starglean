package com.starglean.app.ui.components

import kotlin.math.PI
import kotlin.math.sin

/** A folded sheet opens first; its ink and the small star each have their own movement. */
internal data class PaperThanksFrame(
    val alpha: Float,
    val unfolding: Float,
    val flapScaleY: Float,
    val noteOffsetY: Float,
    val noteRotation: Float,
    val starAlpha: Float,
    val starX: Float,
    val starY: Float,
    val starRotation: Float,
)

internal fun paperThanksFrame(progress: Float): PaperThanksFrame {
    val p = paperReactionProgress(progress)
    val unfold = paperReactionStage(p, .1f, .37f)
    val noteLanding = paperReactionStage(p, .02f, .24f)
    val starLanding = paperReactionStage(p, .45f, .66f)
    val settle = paperReactionStage(p, .66f, .82f)
    val settleArc = sin(settle * PI.toFloat()) * (1f - settle)
    return PaperThanksFrame(
        alpha = paperReactionStage(p, 0f, .1f) * (1f - paperReactionStage(p, .84f, 1f)),
        unfolding = unfold,
        // The upper half starts folded onto the lower half, passes edge-on, then opens.
        flapScaleY = -.84f + 1.84f * unfold,
        noteOffsetY = -8f * (1f - noteLanding),
        noteRotation = -7f + 4f * noteLanding,
        starAlpha = paperReactionStage(p, .39f, .48f),
        starX = 115f - 9f * starLanding,
        starY = 16f + 29f * starLanding - 1.2f * settleArc,
        starRotation = -21f * (1f - starLanding) + 3f * settleArc,
    )
}

internal fun paperThanksInkReveal(progress: Float, line: Int): Float {
    val delay = line.coerceIn(0, 2) * .055f
    return paperReactionStage(paperReactionProgress(progress), .34f + delay, .5f + delay)
}
