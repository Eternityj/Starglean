package com.starglean.app.core.discovery

import com.starglean.app.core.network.DiaryContent
import com.starglean.app.core.network.MediaDescriptor
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedEncounterTest {
    private val session = UUID.randomUUID()
    private val descriptor = MediaDescriptor(UUID.randomUUID(), 0, "image/jpeg", 10, "abc", 1, 1)

    @Test fun completeOpenedEncounterSurvivesOrdinaryPauseWithoutAnExpiry() {
        val retained = RetainedEncounter<String>()
        assertTrue(retained.retain("reader-a", session, content(), listOf("verified photo")))

        // A pause or another app being foregrounded does not change this memory-only value.
        assertEquals(session, retained.forAccount("reader-a")?.sessionId)
        assertEquals("verified photo", retained.forAccount("reader-a")?.photos?.single())
        assertTrue(retained.forAccount("reader-a").toString().contains("redacted"))
        assertFalse(retained.forAccount("reader-a").toString().contains("private body"))
    }

    @Test fun accountSwitchAndSignOutDiscardTheSnapshot() {
        val retained = RetainedEncounter<String>()
        retained.retain("reader-a", session, content(), listOf("photo"))
        assertNull(retained.forAccount("reader-b"))
        retained.onIdentityChanged("reader-a")
        assertNotNull(retained.forAccount("reader-a"))

        retained.onIdentityChanged("reader-b")
        assertNull(retained.forAccount("reader-a"))
        retained.retain("reader-b", session, content(), listOf("photo"))
        retained.onIdentityChanged(null)
        assertNull(retained.forAccount("reader-b"))
    }

    @Test fun incompletePhotosOrBlankAccountCannotReplaceTheVisibleEncounter() {
        val retained = RetainedEncounter<String>()
        retained.retain("reader-a", session, content(), listOf("photo"))
        val existing = retained.forAccount("reader-a")

        assertFalse(retained.retain("reader-a", UUID.randomUUID(), content(), emptyList()))
        assertFalse(retained.retain("  ", UUID.randomUUID(), content(), listOf("photo")))
        assertEquals(existing, retained.forAccount("reader-a"))
    }

    @Test fun snapshotOwnsItsListsAndManualCloseClearsIt() {
        val retained = RetainedEncounter<String>()
        val media = mutableListOf(descriptor)
        val photos = mutableListOf("photo")
        retained.retain("reader-a", session, content(media), photos)
        media.clear()
        photos.clear()

        val snapshot = retained.forAccount("reader-a")!!
        assertEquals(1, snapshot.content.media.size)
        assertEquals(listOf("photo"), snapshot.photos)
        retained.clear()
        assertNull(retained.forAccount("reader-a"))
    }

    private fun content(media: List<MediaDescriptor> = listOf(descriptor)) =
        DiaryContent("private title", "private body", "kraft", "serif", null, media)
}
