package com.starglean.app.ui.components

import kotlin.math.PI
import kotlin.math.sin

/** Positions describe the two stars separately; the pair never zooms as a single sticker. */
internal data class PaperEmpathyFrame(
    val alpha: Float,
    val leftX: Float,
    val leftY: Float,
    val leftLeanDegrees: Float,
    val rightX: Float,
    val rightY: Float,
    val rightLeanDegrees: Float,
    val resonanceAlpha: Float,
)

internal fun paperEmpathyFrame(progress: Float): PaperEmpathyFrame {
    val p = paperReactionProgress(progress)
    val leftArrival = paperReactionStage(p, .09f, .42f)
    val rightArrival = paperReactionStage(p, .14f, .46f)
    val lean = paperReactionStage(p, .35f, .51f)
    val settlingTime = ((p - .44f) / .16f).coerceIn(0f, 1f)
    val settle = sin(settlingTime * PI.toFloat()) * (1f - settlingTime)
    return PaperEmpathyFrame(
        alpha = paperReactionStage(p, 0f, .12f) * (1f - paperReactionStage(p, .83f, 1f)),
        leftX = 37f + 19f * leftArrival,
        leftY = 65f - 1.8f * leftArrival + .7f * settle,
        leftLeanDegrees = -11f + 17f * lean + 1.1f * settle,
        rightX = 123f - 18f * rightArrival,
        rightY = 65f + 1.4f * rightArrival - .5f * settle,
        rightLeanDegrees = 9f - 17f * lean - .9f * settle,
        resonanceAlpha = paperReactionStage(p, .46f, .57f) * (1f - paperReactionStage(p, .7f, .83f)),
    )
}
