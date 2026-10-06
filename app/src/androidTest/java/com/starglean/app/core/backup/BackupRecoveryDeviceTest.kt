package com.starglean.app.core.backup

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starglean.app.core.draft.*
import com.starglean.app.core.library.DiaryDeletionStore
import com.starglean.app.core.media.AndroidPhotoStore
import com.starglean.app.core.memory.*
import com.starglean.app.core.model.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real crypto/storage tests in generated private directories. No accounts, network or device location. */
@RunWith(AndroidJUnit4::class)
class BackupRecoveryDeviceTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    @Before fun before() { root = Files.createTempDirectory(target.cacheDir.toPath(), "backup-device-test-").toFile() }
    @After fun after() {
        assertEquals(target.cacheDir.canonicalFile, root.canonicalFile.parentFile)
        assertTrue(root.name.startsWith("backup-device-test-")); assertTrue(root.deleteRecursively())
    }
    private fun context(name: String): Context = object : ContextWrapper(target) {
        private val own = File(root, name).apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(own, "files").apply { mkdirs() }
        override fun getCacheDir() = File(own, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(own, "private").apply { mkdirs() }
    }
    private fun secret() = "backup-test-password-2026".toCharArray()
    private fun record() = OwnDiaryRecord(DiaryDemo("own-${UUID.randomUUID()}", "A saved memory",
        "A verified backup preserves this exact private writing.", "28 Sep", DiaryWeatherSnapshot.Unavailable,
        "No location saved", PaperStyle.WarmWhite, DiaryFont.Clear), null, 1_790_553_600_000L)

    @Test fun encryptedPhotoRoundTripIsAtomicIdempotentAndRespectsDeletion() = runBlocking {
        val source = context("source"); val destination = context("destination")
        val jpeg = File(root, "fixture.jpg")
        Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.rgb(90, 130, 120))
            jpeg.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }; bitmap.recycle()
        }
        val photo = AndroidPhotoStore(source).importSelected(Uri.fromFile(jpeg))
        val original = record().let { it.copy(diary = it.diary.copy(photos = listOf(photo))) }
        OwnDiaryStore(source).save(original)
        val backup = Uri.fromFile(File(root, "roundtrip.starglean"))
        assertEquals(1, LocalBackupStore(source).export(backup, secret()))
        assertFalse(File(root, "roundtrip.starglean").readText().contains(original.diary.body))
        val store = LocalBackupStore(destination)
        assertTrue(runCatching { store.inspect(backup, "incorrect-password-2026".toCharArray()) }.isFailure)
        assertTrue(OwnDiaryStore(destination).load().isEmpty())
        val preview = store.inspect(backup, secret())
        assertEquals(1, preview.photoCount); assertTrue(OwnDiaryStore(destination).load().isEmpty())
        assertEquals(BackupImportResult(1, 0, 0), store.import(preview))
        assertEquals(listOf(original), OwnDiaryStore(destination).load())
        assertTrue(AndroidPhotoStore(destination).exists(photo.ref))
        assertFalse(preview.directory.exists())
        assertEquals(BackupImportResult(0, 1, 0), store.import(store.inspect(backup, secret())))
        DiaryDeletionStore(destination).deleteDiary(original.diary.id)
        assertEquals(BackupImportResult(0, 0, 1), store.import(store.inspect(backup, secret())))
        assertTrue(OwnDiaryStore(destination).load().isEmpty())
    }

    @Test fun invalidPasswordDoesNotTruncateDestinationAndFailedImportPreservesOldLibrary() = runBlocking {
        val source = context("source"); val destination = context("destination")
        val original = record(); OwnDiaryStore(source).save(original)
        val file = File(root, "existing.starglean").apply { writeText("do not truncate") }
        assertTrue(runCatching { LocalBackupStore(source).export(Uri.fromFile(file), "short".toCharArray()) }.isFailure)
        assertEquals("do not truncate", file.readText())
        LocalBackupStore(source).export(Uri.fromFile(file), secret())
        val changed = original.copy(diary = original.diary.copy(body = "An existing modified record must never be overwritten."))
        OwnDiaryStore(destination).save(changed)
        val targetStore = LocalBackupStore(destination)
        val preview = targetStore.inspect(Uri.fromFile(file), secret())
        assertTrue(runCatching { targetStore.import(preview) }.isFailure)
        assertEquals(listOf(changed), OwnDiaryStore(destination).load()); targetStore.discard(preview)
        file.writeBytes(file.readBytes().dropLast(20).toByteArray())
        assertTrue(runCatching { targetStore.inspect(Uri.fromFile(file), secret()) }.isFailure)
        assertEquals(listOf(changed), OwnDiaryStore(destination).load())
    }

    @Test fun interruptedDraftCanBeReviewedRecoveredAndOldWriterIsRejected() = runBlocking {
        val own = context("draft"); val store = WritingDraftStore(own)
        val draft = WritingDraft("own-${UUID.randomUUID()}", "Earlier", "Original draft text", PaperStyle.WarmWhite, DiaryFont.Clear)
        val first = store.save(0, draft)
        val pending = WritingDraftState(first.revision + 1, draft.copy(title = "Recovered", body = "The more recent interrupted draft"))
        val pendingBytes = WritingDraftCodec.encode(pending)
        File(own.filesDir, "writing-draft-v1.bin.new").writeBytes(pendingBytes)
        assertTrue(runCatching { store.load() }.isFailure)
        val recovery = DraftRecovery(own); val snapshot = recovery.inspect()
        assertEquals(2, snapshot.candidates.size)
        val result = recovery.recover(snapshot, "writing-draft-v1.bin.new")
        assertEquals("Recovered", store.load().draft!!.title)
        assertNotEquals(draft.id, result.draft!!.id)
        assertTrue(runCatching { store.save(first.revision, draft) }.isFailure)
        assertArrayEquals(pendingBytes, File(own.filesDir, "draft-recovery/${snapshot.fingerprint}/writing-draft-v1.bin.new").readBytes())
        assertTrue(runCatching { recovery.recover(snapshot, "writing-draft-v1.bin") }.isFailure)
    }

    @Test fun staleDecryptedPreviewCacheIsCleanedWithoutTouchingActiveOrOtherFiles() = runBlocking {
        val own = context("cleanup")
        val stale = File(own.cacheDir, "backup-review-123456").apply { mkdirs() }
        File(stale, "private.jpg").writeText("fixture")
        val unrelated = File(own.cacheDir, "unrelated").apply { writeText("keep") }
        BackupScratch.cleanup(own)
        assertFalse(stale.exists()); assertTrue(unrelated.exists())
    }
}
