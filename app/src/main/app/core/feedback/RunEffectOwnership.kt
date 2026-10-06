package com.starglean.app.core.feedback

/** Pure run-token ownership; callers serialize these operations with their lifecycle lock. */
internal class RunEffectOwnership {
    private var owner: Long? = null

    fun started(run: Long) { owner = run }

    fun takeForCancellation(expectedRun: Long? = null): Boolean {
        val current = owner ?: return false
        if (expectedRun != null && current != expectedRun) return false
        owner = null
        return true
    }
}

