package com.starglean.app.core.discovery

import com.starglean.app.core.network.DiaryContent
import java.util.Collections
import java.util.UUID

/** A fully received encounter kept only in this process until the reader closes it. */
data class RetainedEncounterSnapshot<Photo>(
    val accountId: String,
    val sessionId: UUID,
    val content: DiaryContent,
    val photos: List<Photo>,
) {
    override fun toString(): String = "RetainedEncounterSnapshot(content=redacted)"
}

/** Account-bound memory for one already opened encounter. It performs no location or network work. */
class RetainedEncounter<Photo> {
    private var snapshot: RetainedEncounterSnapshot<Photo>? = null

    /** An incomplete entry never replaces a complete one already being shown. */
    fun retain(accountId: String, sessionId: UUID, content: DiaryContent, photos: List<Photo>): Boolean {
        if (accountId.isBlank() || content.media.size != photos.size) return false
        snapshot = RetainedEncounterSnapshot(
            accountId = accountId,
            sessionId = sessionId,
            content = content.copy(media = immutableCopy(content.media)),
            photos = immutableCopy(photos),
        )
        return true
    }

    fun forAccount(accountId: String?): RetainedEncounterSnapshot<Photo>? =
        snapshot?.takeIf { accountId != null && it.accountId == accountId }

    /** A sign-out or account switch must discard previously displayed stranger content. */
    fun onIdentityChanged(accountId: String?) {
        if (snapshot?.accountId != accountId) clear()
    }

    fun clear() { snapshot = null }

    private fun <T> immutableCopy(items: List<T>): List<T> =
        Collections.unmodifiableList(ArrayList(items))
}
