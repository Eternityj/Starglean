package com.starglean.app.core.online

import com.starglean.app.core.network.Daylight
import com.starglean.app.core.network.OwnEntriesCursor
import com.starglean.app.core.network.OwnEntry
import com.starglean.app.core.network.OwnerAnchor
import com.starglean.app.core.network.WeatherKind
import com.starglean.app.core.network.WeatherSnapshot
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLibraryPagingTest {
    @Test fun `first page replaces stale entries and retains cursor only for a full page`() {
        val old = entry(99)
        val page = (1..10).map(::entry)
        val cursor = OwnEntriesCursor(page.last().createdAt, page.last().entryId)

        val update = mergeCloudEntryPage(listOf(old), page, cursor, reset = true)

        assertEquals(page, update.entries)
        assertEquals(cursor, update.nextCursor)
        assertFalse(old in update.entries)
    }

    @Test fun `short first page is terminal even if server provides its last item cursor`() {
        val page = (1..3).map(::entry)

        val update = mergeCloudEntryPage(emptyList(), page,
            OwnEntriesCursor(page.last().createdAt, page.last().entryId), reset = true)

        assertEquals(page, update.entries)
        assertNull(update.nextCursor)
    }

    @Test fun `next page appends in order without duplicating boundary entry`() {
        val first = (1..10).map(::entry)
        val second = listOf(first.last()) + (11..19).map(::entry)
        val cursor = OwnEntriesCursor(second.last().createdAt, second.last().entryId)

        val update = mergeCloudEntryPage(first, second, cursor, reset = false)

        assertEquals((1..19).map(::entry), update.entries)
        assertEquals(cursor, update.nextCursor)
        assertTrue(update.entries.map { it.entryId }.toSet().size == update.entries.size)
    }

    @Test fun `repeated cursor or duplicate full page terminates pagination`() {
        val first = (1..10).map(::entry)
        val cursor = OwnEntriesCursor(first.last().createdAt, first.last().entryId)

        val update = mergeCloudEntryPage(first, first, cursor, reset = false, requestedCursor = cursor)

        assertEquals(first, update.entries)
        assertNull(update.nextCursor)
    }

    private fun entry(number: Int): OwnEntry {
        val id = UUID(0L, number.toLong())
        val createdAt = Instant.parse("2026-09-01T00:00:00Z").minusSeconds(number.toLong())
        return OwnEntry(UUID(1L, number.toLong()), id, "Diary $number", "A sufficiently complete diary body.", createdAt,
            "private", "private", "warm_white", "clear",
            WeatherSnapshot(WeatherKind.Clear, Daylight.Day, 20, createdAt),
            OwnerAnchor(-37.8, 144.9, 8.0, createdAt), emptyList())
    }
}
