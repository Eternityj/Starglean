package com.starglean.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.starglean.app.core.design.StargleanColors

/** Local previews share their actual timing with the screen's playback/cancellation owner. */
enum class DiaryReaction(val label: String, val durationMillis: Int = PAPER_REACTION_DURATION_MS) {
    Hug("Sending a hug", durationMillis = PAPER_APPROACHING_HUG_DURATION_MS),
    Understand("I understand"),
    Thanks("Thank you"),
    Joy("Happy for you"),
}

/** Every control uses the same pastel illustration family as its paper animation. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun DiaryReactionIcon(
    reaction: DiaryReaction,
    modifier: Modifier = Modifier,
    color: Color = StargleanColors.GoldBright,
    progress: Float? = null,
    ink: Color = StargleanColors.InkRaised,
) {
    // Keep the existing call signature; a monochrome selection tint must not recolor the art.
    PaperReactionIllustrationIcon(reaction, modifier, darkPaper = ink.luminance() < .45f)
}

/** No card, particles, caption or timer: playback is still owned by the diary screen. */
@Composable
fun DiaryPaperReaction(
    reaction: DiaryReaction,
    progress: Float,
    modifier: Modifier = Modifier,
    darkPaper: Boolean = false,
) {
    PaperReactionIllustration(reaction, progress, modifier, darkPaper)
}
