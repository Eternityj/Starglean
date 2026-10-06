package com.starglean.app.core.online

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.starglean.app.core.draft.WritingDraft
import com.starglean.app.core.draft.WritingDraftState
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryCodec
import com.starglean.app.core.model.*
import com.starglean.app.core.network.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPendingPublicationStoreDeviceTest {
    @Test fun encryptedRoundTripKeepsExactDraftTokenAndCorruptionPreservesBlob() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "pending-publication-${UUID.randomUUID()}").also { assertTrue(it.mkdirs()) }
        val store = AndroidPendingPublicationStore(context, directory, "starglean.test.publication.${UUID.randomUUID()}")
        val pending = fixture()
        store.write(pending)
        val restored = store.read()
        assertEquals(pending, restored)
        assertEquals(pending.frozen.draftToken, restored?.frozen?.draftToken)
        val file = File(directory, "online/pending-publication-v1.enc")
        val corrupted = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(corrupted)
        assertThrows(PublicationStorageException::class.java) { runBlocking { store.read() } }
        assertArrayEquals(corrupted, file.readBytes())
        // Mirror the persisted-format bound. MAX_BYTES is private deliberately; this formula
        // is also a regression alarm if OwnDiaryCodec's public file bound changes.
        val pendingFileLimit = OwnDiaryCodec.MAX_FILE_BYTES + 128 * 1024
        val oversized = ByteArray(pendingFileLimit + 1) { 0x5a }
        assertEquals(pendingFileLimit + 1, oversized.size)
        file.writeBytes(oversized)
        assertThrows(PublicationStorageException::class.java) { runBlocking { store.read() } }
        assertArrayEquals(oversized, file.readBytes())
    }

    private fun fixture(): PendingPublication {
        val draftId = UUID.randomUUID(); val localId = "own-$draftId"
        val draft = WritingDraft(localId, "  exact title  ", "0123456789 body", PaperStyle.WarmWhite, DiaryFont.Clear)
        val capturedAt = Instant.parse("2026-09-27T00:00:00Z")
        val diaryWeather = DiaryWeatherSnapshot(com.starglean.app.core.model.WeatherKind.Rain,
            com.starglean.app.core.model.Daylight.Night, 12, capturedAt.toString(), false)
        val diary = DiaryDemo(localId, "  exact title  ", draft.body, "27 Sep", diaryWeather,
            "Location shared with Starglean", draft.paper, draft.font)
        val request = PublishEntryRequest(draftId, draft.title, draft.body, "warm_white", "clear",
            WeatherSnapshot(com.starglean.app.core.network.WeatherKind.Rain,
                com.starglean.app.core.network.Daylight.Night, 12, capturedAt),
            visibility = EntryVisibility.Public,
            location = ReaderLocation(-37.8, 144.9, 5.0, capturedAt, false), shareConfirmed = true)
        return PendingPublication(FrozenPublication(UUID.randomUUID(), draftId, WritingDraftState(9, draft), request,
            OwnDiaryRecord(diary, null, 1234)))
    }
}
