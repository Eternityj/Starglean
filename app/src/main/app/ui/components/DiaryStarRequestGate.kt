package com.starglean.app.ui.components

/** UI-thread ownership shared by refresh and placement; cancelled IO can acknowledge late. */
internal class DiaryStarRequestGate {
    private var sequence = 0L
    private var owner: Long? = null
    val busy: Boolean get() = owner != null

    fun tryBegin(): Long? = if (busy) null else replace()

    /** A foreground refresh supersedes all work from the previous visible session. */
    fun replace(): Long = (++sequence).also { owner = it }

    fun owns(token: Long): Boolean = owner == token

    fun finish(token: Long): Boolean {
        if (!owns(token)) return false
        owner = null
        return true
    }

    fun invalidate() { owner = null }
}
