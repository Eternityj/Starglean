package com.starglean.app.core.draft

import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.PaperStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingDraftRulesTest {
    @Test fun emptyAndShortTextAreValidDrafts() {
        listOf("", " ", "今天", "🌙").forEach { text ->
            val saved = WritingDraftRules.save(WritingDraftState(0L, null), 0L, draft(body = text))
            assertEquals(text, saved.draft?.body)
            assertEquals(1L, saved.revision)
        }
    }

    @Test fun currentSnapshotCanAdvanceButAnOldWriterCannotOverwriteIt() {
        val first = WritingDraftRules.save(WritingDraftState(0L, null), 0L, draft(body = "第一版"))
        val second = WritingDraftRules.save(first, first.revision, draft(body = "第二版"))
        val stale = runCatching { WritingDraftRules.save(second, first.revision, draft(body = "旧异步结果")) }.exceptionOrNull()
        assertTrue(stale is WritingDraftConflictException)
        assertEquals("第二版", second.draft?.body)
        assertEquals(2L, second.revision)
    }

    @Test fun identicalCurrentSaveIsIdempotentButAStaleRevisionStillConflicts() {
        val current = WritingDraftState(9L, draft())
        assertSame(current, WritingDraftRules.save(current, 9L, draft()))
        assertTrue(runCatching { WritingDraftRules.save(current, 8L, draft()) }.exceptionOrNull() is WritingDraftConflictException)
    }

    @Test fun clearingOnlyTheExactSnapshotLeavesAPersistentRevisionTombstone() {
        val current = WritingDraftState(9L, draft())
        val cleared = WritingDraftRules.clearIfMatches(current, current)
        assertNull(cleared.draft)
        assertEquals(10L, cleared.revision)
        assertSame(cleared, WritingDraftRules.clearIfMatches(cleared, current))
        assertTrue(runCatching { WritingDraftRules.save(cleared, 9L, draft()) }.exceptionOrNull() is WritingDraftConflictException)
        val next = WritingDraftRules.save(cleared, 10L, draft(id = OTHER_ID))
        assertEquals(11L, next.revision)
    }

    @Test fun aSubmitAcknowledgementCannotClearNewerEditsWithTheSameId() {
        val submitted = WritingDraftState(3L, draft(body = "准备提交"))
        val edited = WritingDraftRules.save(submitted, 3L, draft(body = "提交后继续编辑"))
        assertSame(edited, WritingDraftRules.clearIfMatches(edited, submitted))
        val forgedSameRevision = edited.copy(draft = submitted.draft)
        assertSame(edited, WritingDraftRules.clearIfMatches(edited, forgedSameRevision))
        assertSame(edited, WritingDraftRules.clearIfMatches(edited, edited.copy(draft = draft(id = OTHER_ID))))
    }

    @Test fun paperAndFontChangesAlsoProtectAgainstOldSubmitAcknowledgements() {
        val original = WritingDraftState(3L, draft())
        listOf(draft().copy(paper = PaperStyle.Midnight), draft().copy(font = DiaryFont.Serif)).forEach { changed ->
            val current = WritingDraftState(3L, changed)
            assertSame(current, WritingDraftRules.clearIfMatches(current, original))
        }
    }

    @Test fun invalidIdentityLengthAndMalformedSurrogatesAreRejected() {
        val invalid = listOf<() -> WritingDraft>(
            { draft(id = "demo:sample") },
            { draft(id = "own-../../other") },
            { draft(id = "OWN-00000000-0000-0000-0000-000000000001") },
            { draft(title = "字".repeat(81)) },
            { draft(body = "字".repeat(10_001)) },
            { draft(body = "\uD800") },
            { draft(body = "\uDC00") },
            { draft(title = "a\uD800b") },
        )
        invalid.forEach { operation -> assertTrue(runCatching(operation).exceptionOrNull() is WritingDraftDataException) }
    }

    @Test fun unicodeLimitsCountCodePointsAndPreserveWhitespaceExactly() {
        val edge = draft(title = "🌙".repeat(80), body = "🪐".repeat(10_000))
        assertEquals(20_000, edge.body.length)
        val whitespace = draft(title = "  ", body = "\n　\t")
        assertEquals("  ", whitespace.title)
        assertEquals("\n　\t", whitespace.body)
    }

    @Test fun revisionZeroIsOnlyTheVirginEmptyStateAndRevisionNeverWraps() {
        assertTrue(runCatching { WritingDraftState(-1L, null) }.exceptionOrNull() is WritingDraftDataException)
        assertTrue(runCatching { WritingDraftState(0L, draft()) }.exceptionOrNull() is WritingDraftDataException)
        val last = WritingDraftState(Long.MAX_VALUE, draft())
        assertSame(last, WritingDraftRules.save(last, Long.MAX_VALUE, draft()))
        assertTrue(runCatching { WritingDraftRules.save(last, Long.MAX_VALUE, draft(body = "新内容")) }.exceptionOrNull() is WritingDraftDataException)
        assertTrue(runCatching { WritingDraftRules.clearIfMatches(last, last) }.exceptionOrNull() is WritingDraftDataException)
    }

    @Test fun debugStringsAndConflictsDoNotRevealPrivateTextOrRetainACause() {
        val content = draft(title = "私密标题", body = "私密正文")
        val state = WritingDraftState(1L, content)
        assertFalse(content.toString().contains("私密"))
        assertFalse(state.toString().contains("私密"))
        assertFalse(state.toString().contains(content.id))
        val conflict = WritingDraftConflictException()
        assertNull(conflict.cause)
        assertFalse(conflict.toString().contains(content.body))
    }

    private fun draft(id: String = ID, title: String = "", body: String = "还没写完") =
        WritingDraft(id, title, body, PaperStyle.WarmWhite, DiaryFont.Clear)

    private companion object {
        const val ID = "own-00000000-0000-0000-0000-000000000001"
        const val OTHER_ID = "own-00000000-0000-0000-0000-000000000002"
    }
}
