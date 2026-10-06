package com.starglean.app.core.online

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudEntryLinkStoreDeviceTest {
    @Test fun linksAreAccountQualifiedAndIdempotent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val isolated = context.cacheDir.resolve("cloud-link-test-${System.nanoTime()}")
        val store = CloudEntryLinkStore(context, isolated)
        val firstAccount = UUID.randomUUID().toString()
        val secondAccount = UUID.randomUUID().toString()
        val link = CloudEntryLink(firstAccount, "own-${UUID.randomUUID()}", UUID.randomUUID())

        store.put(link); store.put(link)

        assertEquals(listOf(link), store.linksFor(firstAccount))
        assertTrue(store.linksFor(secondAccount).isEmpty())
        val file = File(isolated, "cloud-library/entry-links-v1.bin")
        val truncated = file.readBytes().copyOf(9)
        file.writeBytes(truncated)
        assertThrows(CloudLibraryDataException::class.java) { runBlocking { store.linksFor(firstAccount) } }
        assertArrayEquals(truncated, file.readBytes())
    }
}
