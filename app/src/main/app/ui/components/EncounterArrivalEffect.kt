package com.starglean.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.starglean.app.core.feedback.EncounterFeedback
import com.starglean.app.ui.space.DiscoveryArrival
import kotlinx.coroutines.delay

internal const val ENCOUNTER_ARRIVAL_STABILITY_MILLIS = 200L

/** A renewed location lease is the SAME encounter, never a new sensory arrival. */
internal class EncounterArrivalLatch {
    // Snapshot state, so a composition hiding a pending arrival notices when it is taken.
    private var consumed by mutableStateOf<Int?>(null)
    fun hasConsumed(encounter: Int): Boolean = consumed == encounter
    fun take(encounter: Int, readable: Boolean, active: Boolean): Boolean {
        if (!readable || !active || consumed == encounter) return false
        consumed = encounter
        return true
    }
}

/** Optional feedback belongs to the reveal, so the sound and vibration play as the diary appears. */
@Composable
internal fun rememberEncounterArrival(
    encounter: Int,
    readable: Boolean,
    active: Boolean,
    motion: Boolean,
    feedback: EncounterFeedback? = null,
): State<Float> {
    val progress = remember(feedback) { Animatable(1f) }
    val latch = remember(feedback) { EncounterArrivalLatch() }
    LaunchedEffect(feedback, encounter, readable, active, motion) {
        if (!readable || !active) {
            feedback?.stop()
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        if (latch.hasConsumed(encounter)) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        // Start an authorized but not-yet-stable new encounter at its animation origin.
        progress.snapTo(if (motion) 0f else 1f)
        // Do not consume the one-shot arrival for a transient eligibility pulse.
        // A false readable/active key cancels this delay before take() or play().
        delay(ENCOUNTER_ARRIVAL_STABILITY_MILLIS)
        if (!latch.take(encounter, readable, active)) {
            // A cancelled reveal must not leave a partly transparent diary on recovery.
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        try {
            feedback?.play()
            if (motion) progress.animateTo(1f, tween(DiscoveryArrival.TOTAL_MILLIS, easing = LinearEasing))
            else delay(DiscoveryArrival.TOTAL_MILLIS.toLong())
        } finally {
            feedback?.stop()
        }
    }
    // Hide a new readable encounter from its very first frame. The effect above only starts
    // after that frame is drawn, so on its own it briefly exposed the completed diary.
    val hidden = rememberUpdatedState(motion && readable && active && !latch.hasConsumed(encounter))
    return remember(progress) { derivedStateOf { if (hidden.value) 0f else progress.value } }
}
