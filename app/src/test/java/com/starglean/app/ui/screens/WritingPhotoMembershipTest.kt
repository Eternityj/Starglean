package com.starglean.app.ui.screens

import com.starglean.app.core.draft.WritingDraft
import com.starglean.app.core.draft.WritingDraftRules
import com.starglean.app.core.draft.WritingDraftState
import com.starglean.app.core.media.DiaryPhoto
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.PaperStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

class WritingPhotoMembershipTest {
    @Test fun lateTextFrameCannotDropCurrentPhotoMembership() {
        val photo = DiaryPhoto(
            "photo-00000000-0000-0000-0000-000000000001", 100, 80, sizeBytes = 500,
        )
        val original = WritingDraft(ID, "", "before", PaperStyle.WarmWhite, DiaryFont.Clear, listOf(photo))
        val persistence = MemoryPersistence(WritingDraftState(1L, original))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = WritingDraftSession(persistence, scope)
            // Simulates a callback rendered before the photo appeared.
            session.edit(original.copy(body = "latest keyboard text", photos = emptyList()))
            assertEquals("latest keyboard text", persistence.state.draft!!.body)
            assertEquals(listOf(photo), persistence.state.draft!!.photos)
            assertEquals(listOf(photo), session.state.value.draft!!.photos)
        } finally {
            scope.cancel()
        }
    }

    @Test fun lateTextFrameCannotReviveRemovedPhotoMembership() {
        val photo = DiaryPhoto(
            "photo-00000000-0000-0000-0000-000000000002", 100, 80, sizeBytes = 500,
        )
        val current = WritingDraft(ID, "", "before", PaperStyle.WarmWhite, DiaryFont.Clear)
        val persistence = MemoryPersistence(WritingDraftState(1L, current))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = WritingDraftSession(persistence, scope)
            session.edit(current.copy(body = "new text from old frame", photos = listOf(photo)))
            assertEquals("new text from old frame", persistence.state.draft!!.body)
            assertEquals(emptyList<DiaryPhoto>(), persistence.state.draft!!.photos)
        } finally {
            scope.cancel()
        }
    }

    private class MemoryPersistence(initial: WritingDraftState) : WritingDraftPersistence {
        var state = initial
        override suspend fun load() = state
        override suspend fun save(expectedRevision: Long, draft: WritingDraft): WritingDraftState =
            WritingDraftRules.save(state, expectedRevision, draft).also { state = it }
        override suspend fun clearIfMatches(expected: WritingDraftState): WritingDraftState =
            WritingDraftRules.clearIfMatches(state, expected).also { state = it }
    }

    private companion object { const val ID = "own-00000000-0000-0000-0000-000000000001" }
}
