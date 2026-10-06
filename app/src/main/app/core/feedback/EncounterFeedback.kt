package com.starglean.app.core.feedback

interface EncounterFeedback {
    /** Returns false when feedback is already active, released, or suppressed by system/user policy. */
    fun play(): Boolean
    fun stop()
    fun release()
}

