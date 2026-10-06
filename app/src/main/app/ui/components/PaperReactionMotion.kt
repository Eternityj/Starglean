package com.starglean.app.ui.components

import kotlin.math.PI
import kotlin.math.sin

/** Three seconds leaves time to read the gesture, without changing the diary's layout. */
internal const val PAPER_REACTION_DURATION_MS = 3000

internal data class PaperFlowerFrame(
    val alpha: Float,
    val landing: Float,
    val stemGrowth: Float,
    val budAlpha: Float,
    val swayDegrees: Float,
    val lift: Float,
)

internal fun paperFlowerFrame(progress: Float): PaperFlowerFrame {
    val p = paperReactionProgress(progress)
    val swayStart = paperReactionStage(p, .54f, .64f)
    val swayTime = ((p - .54f) / .46f).coerceIn(0f, 1f)
    return PaperFlowerFrame(
        alpha = paperReactionStage(p, 0f, .1f) * (1f - paperReactionStage(p, .84f, 1f)),
        landing = paperReactionStage(p, .04f, .27f),
        stemGrowth = paperReactionStage(p, .22f, .46f),
        budAlpha = 1f - paperReactionStage(p, .37f, .58f),
        swayDegrees = sin(swayTime * PI.toFloat() * 2.25f) * 3.8f * swayStart * (1f - .45f * swayTime),
        lift = paperReactionStage(p, .84f, 1f) * 3f,
    )
}

/** Each petal unfolds separately; the last petal is open before the quiet swaying hold. */
internal fun paperFlowerPetalOpening(progress: Float, index: Int): Float {
    val delay = index.coerceIn(0, 5) * .032f
    return paperReactionStage(paperReactionProgress(progress), .29f + delay, .49f + delay)
}

internal fun paperReactionProgress(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

internal fun paperReactionStage(value: Float, start: Float, end: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
