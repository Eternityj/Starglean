package com.starglean.app.ui.screens

import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.media.DiaryPhoto
import com.starglean.app.core.draft.WritingDraft
import org.junit.Assert.assertEquals
import org.junit.Test

class WritingDraftReconciliationTest {
    private val id = "own-12345678-1234-4234-8234-123456789abc"
    private val original = DiaryDemo(id, "今天", "这篇日记已经通过本机原子写入保存。", "9月16日 · 10:30",
        DiaryWeatherSnapshot.Unavailable, "地点未记录", PaperStyle.WarmWhite, DiaryFont.Clear)

    @Test fun absentIdRemainsTheSameUncommittedDraft() {
        assertEquals(WritingDraftReconciliation.Uncommitted,
            reconcile(original.title.orEmpty(), original.body, saved = null))
    }

    @Test fun anotherDiaryCannotClearOrRenameTheCurrentDraft() {
        assertEquals(WritingDraftReconciliation.Uncommitted,
            reconcile(original.title.orEmpty(), original.body, saved = original.copy(id = "$id-other")))
    }

    @Test fun committedSameIdAndContentCanBeClearedAfterLostAcknowledgement() {
        assertEquals(WritingDraftReconciliation.ClearCommittedDraft, reconcile(original.title.orEmpty(), original.body))
    }

    @Test fun newerTitleOrBodyIsPreservedAsANewDraft() {
        assertEquals(WritingDraftReconciliation.KeepEditsWithNewId, reconcile("改过的标题", original.body))
        assertEquals(WritingDraftReconciliation.KeepEditsWithNewId, reconcile(original.title.orEmpty(), original.body + "新的一句。"))
        assertEquals(WritingDraftReconciliation.KeepEditsWithNewId, reconcile("", ""))
    }

    @Test fun paperAndFontEditsAreNotDiscardedEither() {
        assertEquals(WritingDraftReconciliation.KeepEditsWithNewId,
            reconcileWritingDraft(id, original.title.orEmpty(), original.body, PaperStyle.Midnight, original.font, original))
        assertEquals(WritingDraftReconciliation.KeepEditsWithNewId,
            reconcileWritingDraft(id, original.title.orEmpty(), original.body, original.paper, DiaryFont.Serif, original))
    }

    @Test fun blankTitleMatchesItsPersistedOptionalRepresentation() {
        assertEquals(WritingDraftReconciliation.ClearCommittedDraft,
            reconcile("   ", original.body, saved = original.copy(title = null)))
    }

    @Test fun capturedDateAndPlaceLabelsDoNotMakeAnAlreadyCommittedDraftNew() {
        assertEquals(WritingDraftReconciliation.ClearCommittedDraft,
            reconcile(original.title.orEmpty(), original.body,
                saved = original.copy(date = "9月17日 · 01:20", blurredPlace = "位置已私密保存")))
    }

    private fun reconcile(title: String, body: String, saved: DiaryDemo? = original) =
        reconcileWritingDraft(id, title, body, original.paper, original.font, saved)

    @Test fun newPhotoIsKeptWhileExactCommittedAttachmentsAreCleared() {
        val photo = DiaryPhoto("photo-00000000-0000-0000-0000-000000000001", 300, 200, sizeBytes = 2000)
        val draft = WritingDraft(id, original.title.orEmpty(), original.body, original.paper, original.font, listOf(photo))
        assertEquals(WritingDraftReconciliation.KeepEditsWithNewId,
            reconcileWritingDraft(id, draft.title, draft.body, draft.paper, draft.font, original, draft.photos))
        assertEquals(listOf(photo), draftAfterCommitted(draft, original, "own-00000000-0000-0000-0000-000000000002").photos)
        assertEquals(emptyList<DiaryPhoto>(), draftAfterCommitted(draft, original.copy(photos = draft.photos),
            "own-00000000-0000-0000-0000-000000000002").photos)
    }
}
