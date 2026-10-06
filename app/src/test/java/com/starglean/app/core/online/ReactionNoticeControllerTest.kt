package com.starglean.app.core.online

import com.starglean.app.core.network.ReactionInbox
import com.starglean.app.core.network.ReactionKind
import com.starglean.app.core.network.ReactionSummary
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReactionNoticeControllerTest {
    private val account = "11111111-1111-4111-8111-111111111111"
    private val linkedEntry = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val unlinkedEntry = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val watermark = Instant.parse("2026-09-27T06:00:00Z")

    @Test fun `unread reactions aggregate by entry and retain local mapping`() {
        val state = aggregateReactionNotices(account, ReactionInbox("FOUND", watermark, listOf(
            ReactionSummary(linkedEntry, ReactionKind.Hug, count = 9, unread = 2),
            ReactionSummary(linkedEntry, ReactionKind.Thanks, count = 4, unread = 3),
            ReactionSummary(unlinkedEntry, ReactionKind.Joy, count = 1, unread = 1),
            ReactionSummary(unlinkedEntry, ReactionKind.Understand, count = 8, unread = 0),
        )), listOf(CloudEntryLink(account, "own-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", linkedEntry)))

        assertEquals(6, state.unreadCount)
        assertEquals(2, state.notices.size)
        val linked = state.notices.first { it.cloudEntryId == linkedEntry }
        assertEquals("own-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", linked.localDiaryId)
        assertEquals(ReactionKind.Thanks, linked.leadingKind)
        assertEquals(5, linked.unreadCount)
        assertNull(state.notices.first { it.cloudEntryId == unlinkedEntry }.localDiaryId)
    }

    @Test fun `read-only or malformed watermark produces no unread notice`() {
        val read = aggregateReactionNotices(account,
            ReactionInbox("FOUND", watermark, listOf(ReactionSummary(linkedEntry, ReactionKind.Hug, 2, 0))), emptyList())
        val noWatermark = aggregateReactionNotices(account,
            ReactionInbox("FOUND", null, listOf(ReactionSummary(linkedEntry, ReactionKind.Hug, 2, 2))), emptyList())

        assertEquals(false, read.hasUnread)
        assertEquals(false, noWatermark.hasUnread)
    }

    @Test fun `arrival animation ignores changing poll cursor but distinguishes a later response`() {
        val tracker = ReactionAnimationTracker()
        val notice = ReactionNotice(linkedEntry, "own-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            ReactionKind.Hug, 2, watermark, "hug:2")

        assertEquals(true, tracker.consume(account, notice))
        assertEquals(false, tracker.consume(account, notice))
        assertEquals(false, tracker.consume(account, notice.copy(seenThrough = watermark.plusSeconds(1))))
        assertEquals(true, tracker.consume(account, notice.copy(arrivalVersion = "hug:3", unreadCount = 3)))
        assertEquals(true, tracker.consume("44444444-4444-4444-8444-444444444444", notice))
    }

    @Test fun `one new reaction after acknowledgement is not confused with the first one`() {
        val tracker = ReactionAnimationTracker()
        fun arrival(total: Int, cursor: Instant) = aggregateReactionNotices(account,
            ReactionInbox("FOUND", cursor, listOf(ReactionSummary(linkedEntry, ReactionKind.Hug, total, 1))),
            emptyList()).notices.single()
        assertEquals(true, tracker.consume(account, arrival(1, watermark)))
        assertEquals(false, tracker.consume(account, arrival(1, watermark.plusSeconds(60))))
        assertEquals(true, tracker.consume(account, arrival(2, watermark.plusSeconds(120))))
    }

    @Test fun `older poll cannot overwrite a newer acknowledged watermark`() {
        val current = ReactionNoticeState(account, emptyList(), watermark)
        assertEquals(false, shouldApplyReactionInbox(current, account, watermark.minusSeconds(1)))
        assertEquals(true, shouldApplyReactionInbox(current, account, watermark))
        assertEquals(true, shouldApplyReactionInbox(current, account, watermark.plusSeconds(1)))
        assertEquals(true, shouldApplyReactionInbox(current, accountB, watermark.minusSeconds(1)))
    }

    private val accountB = "44444444-4444-4444-8444-444444444444"
}
