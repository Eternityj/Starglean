package com.starglean.app.core.draft

import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.media.DiaryPhoto
import com.starglean.app.core.media.DiaryPhotoRules

/** Editable local text only: never holds weather, location, visibility or example provenance. */
data class WritingDraft(
    val id: String,
    val title: String,
    val body: String,
    val paper: PaperStyle,
    val font: DiaryFont,
    val photos: List<DiaryPhoto> = emptyList(),
) {
    init { WritingDraftRules.validate(this) }
    override fun toString(): String = "WritingDraft(content=<private>)"
}

/** Revision belongs to the file, not the diary; a cleared file keeps its revision tombstone. */
data class WritingDraftState(val revision: Long, val draft: WritingDraft?) {
    init {
        if (revision < 0L || (revision == 0L && draft != null)) {
            throw WritingDraftDataException("The draft version is invalid. Your existing draft has not been overwritten.")
        }
    }
    override fun toString(): String = "WritingDraftState(revision=$revision, hasDraft=${draft != null})"
}

class WritingDraftDataException(message: String) : IllegalArgumentException(message)

/** No conflicting text, platform cause or location is retained in this error. */
class WritingDraftConflictException : IllegalStateException("A newer draft exists. This older version has not overwritten it. Reload to review it.")

/** Shared pure policy; drafts intentionally have no minimum meaningful-character count. */
object WritingDraftRules {
    const val MAX_TITLE_CODE_POINTS = 80
    const val MAX_BODY_CODE_POINTS = 10_000
    private val idPattern = Regex("own-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun validate(draft: WritingDraft) {
        if (!idPattern.matches(draft.id)) throw WritingDraftDataException("The draft ID is invalid. Your existing draft has not been overwritten.")
        validateText(draft.title, MAX_TITLE_CODE_POINTS)
        validateText(draft.body, MAX_BODY_CODE_POINTS)
        try { DiaryPhotoRules.validateAttachments(draft.photos) }
        catch (_: IllegalArgumentException) {
            throw WritingDraftDataException("The draft photo attachments are invalid. Your existing draft has not been overwritten.")
        }
    }

    fun save(current: WritingDraftState, expectedRevision: Long, draft: WritingDraft): WritingDraftState {
        validate(draft)
        if (expectedRevision != current.revision) throw WritingDraftConflictException()
        if (current.draft == draft) return current
        return WritingDraftState(nextRevision(current.revision), draft)
    }

    /** Full equality prevents an old submit acknowledgement from clearing newer edits with the same ID. */
    fun clearIfMatches(current: WritingDraftState, expected: WritingDraftState): WritingDraftState {
        if (expected.draft == null || current != expected) return current
        return WritingDraftState(nextRevision(current.revision), null)
    }

    private fun nextRevision(revision: Long): Long {
        if (revision == Long.MAX_VALUE) throw WritingDraftDataException("The draft version limit has been reached. Your existing draft is preserved.")
        return revision + 1L
    }

    private fun validateText(value: String, maxCodePoints: Int) {
        if (value.length > maxCodePoints * 2 || value.codePointCount(0, value.length) > maxCodePoints) {
            throw WritingDraftDataException("This draft exceeds the current size limit. Your existing draft is preserved.")
        }
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        throw WritingDraftDataException("This draft contains invalid text encoding. Your existing draft is preserved.")
                    }
                    index += 2
                }
                Character.isLowSurrogate(character) -> throw WritingDraftDataException("This draft contains invalid text encoding. Your existing draft is preserved.")
                else -> index++
            }
        }
    }
}
