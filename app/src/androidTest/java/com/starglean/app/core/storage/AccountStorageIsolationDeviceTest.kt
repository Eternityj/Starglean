package com.starglean.app.core.storage

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.starglean.app.core.draft.*
import com.starglean.app.core.media.AndroidPhotoStore
import com.starglean.app.core.memory.*
import com.starglean.app.core.model.*
import com.starglean.app.core.network.*
import com.starglean.app.core.online.*
import com.starglean.app.core.stars.ConstellationStore
import java.io.File
import java.time.Instant
import java.util.UUID
import com.starglean.app.ui.screens.WritingDraftSessions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStorageIsolationDeviceTest {
    private val accountA = "11111111-1111-4111-8111-111111111111"
    private val accountB = "22222222-2222-4222-8222-222222222222"

    @Test fun legacyClaimIsExplicitAccountIsolatedAndReloadable(): Unit = runBlocking {
        withIsolatedBase("claim") { base ->
            val legacy = File(base.filesDir, "own-diaries-v1.bin").apply { writeBytes(byteArrayOf(1, 3, 5, 7)) }
            val store = LegacyLibraryClaimStore(base)
            assertTrue(store.prepare(accountA) is AccountLibraryPreparation.ChoiceRequired)

            val a = store.claimLegacy(accountA)
            assertArrayEquals(legacy.readBytes(), File(a.filesDir, legacy.name).readBytes())
            assertTrue("original legacy evidence must remain", legacy.exists())
            val accountFiles = listOf("writing-draft-v1.bin", "star-collection-v1.bin", "diary-photos-v1/photo-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.jpg")
            accountFiles.forEachIndexed { index, path -> File(a.filesDir, path).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(index.toByte())) } }
            File(a.noBackupFilesDir, "online/pending-publication-v1.enc").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(6)) }
            File(a.cacheDir, "diary-camera/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.jpg").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(7)) }

            val b = (store.prepare(accountB) as AccountLibraryPreparation.Ready).context
            assertFalse(File(b.filesDir, legacy.name).exists())
            accountFiles.forEach { assertFalse(File(b.filesDir, it).exists()) }
            assertFalse(File(b.noBackupFilesDir, "online/pending-publication-v1.enc").exists())
            assertFalse(File(b.cacheDir, "diary-camera/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.jpg").exists())
            File(b.filesDir, "account-b-only.bin").writeBytes(byteArrayOf(9))
            assertFalse(File(a.filesDir, "account-b-only.bin").exists())
            repeat(3) {
                val reloadedB = (LegacyLibraryClaimStore(base).prepare(accountB) as AccountLibraryPreparation.Ready).context
                assertArrayEquals(byteArrayOf(9), File(reloadedB.filesDir, "account-b-only.bin").readBytes())
            }
            assertSame(WritingDraftSessions.get(a), WritingDraftSessions.get(a))
            assertNotSame(WritingDraftSessions.get(a), WritingDraftSessions.get(b))

            val reloadedA = (LegacyLibraryClaimStore(base).prepare(accountA) as AccountLibraryPreparation.Ready).context
            assertArrayEquals(byteArrayOf(1, 3, 5, 7), File(reloadedA.filesDir, legacy.name).readBytes())
            accountFiles.forEach { assertTrue(File(reloadedA.filesDir, it).exists()) }
            assertTrue(runCatching { store.claimLegacy(accountB) }.exceptionOrNull() is AccountLibraryException)
        }
    }

    @Test fun freshChoicePreservesLegacyAndInterruptedWritesFailClosed(): Unit = runBlocking {
        withIsolatedBase("fresh") { base ->
            val legacy = File(base.filesDir, "writing-draft-v1.bin").apply { writeBytes(byteArrayOf(4, 2)) }
            val store = LegacyLibraryClaimStore(base)
            val fresh = store.startFresh(accountB)
            assertFalse(File(fresh.filesDir, legacy.name).exists())
            assertArrayEquals(byteArrayOf(4, 2), legacy.readBytes())
            OwnDiaryStore(fresh).save(OwnDiaryRecord(DiaryDemo(
                "own-55555555-5555-4555-8555-555555555555", "Fresh", "Fresh account fixture", "27 Sep",
                DiaryWeatherSnapshot.Unavailable, "Not recorded", PaperStyle.WarmWhite, DiaryFont.Clear,
            ), null, 5L))
            repeat(3) {
                val reopened = (LegacyLibraryClaimStore(base).prepare(accountB) as AccountLibraryPreparation.Ready).context
                assertEquals("Fresh", OwnDiaryStore(reopened).load().single().diary.title)
            }

            File(base.filesDir, "own-diaries-v1.bin").writeBytes(byteArrayOf(8))
            val interrupted = File(base.filesDir, "own-diaries-v1.bin.new").apply { writeBytes(byteArrayOf(6)) }
            assertTrue(runCatching { store.prepare(accountA) }.exceptionOrNull() is AccountLibraryException)
            assertTrue(runCatching { store.claimLegacy(accountA) }.exceptionOrNull() is AccountLibraryException)
            assertTrue(interrupted.exists())
            assertArrayEquals(byteArrayOf(8), File(base.filesDir, "own-diaries-v1.bin").readBytes())
        }
    }

    @Test fun incompleteMigrationStagingIsNeverExposedOrDeleted(): Unit = runBlocking {
        withIsolatedBase("interrupted") { base ->
            File(base.filesDir, "own-diaries-v1.bin").writeBytes(byteArrayOf(7))
            val staging = File(base.filesDir, "account-libraries-v1/.claim-$accountA.new").apply { mkdirs() }
            File(staging, ".claim-owner").writeText(accountA)

            val failure = runCatching { LegacyLibraryClaimStore(base).claimLegacy(accountA) }.exceptionOrNull()

            assertTrue(failure is AccountLibraryException)
            assertTrue("ambiguous staging evidence must be preserved", staging.exists())
            assertFalse(File(base.filesDir, "account-libraries-v1/$accountA/own-diaries-v1.bin").exists())
        }
    }

    @Test fun completedFilesRootDoesNotCommitWhenNoBackupRootFails(): Unit = runBlocking {
        withIsolatedBase("multi-root") { base ->
            File(base.filesDir, "own-diaries-v1.bin").writeBytes(byteArrayOf(1, 2, 3))
            // Force only the second destination root to fail. The first root must already
            // have been copied and verified when copyRoot reaches this preserved conflict.
            val noBackupConflict = File(base.noBackupFilesDir, "account-libraries-v1/$accountA")
                .apply { mkdirs() }
            val sentinel = File(noBackupConflict, "do-not-delete.bin").apply { writeBytes(byteArrayOf(9)) }
            val store = LegacyLibraryClaimStore(base)

            assertTrue(runCatching { store.claimLegacy(accountA) }.exceptionOrNull() is AccountLibraryException)

            val filesCopy = File(base.filesDir, "account-libraries-v1/$accountA")
            assertTrue("the verified first-root copy is preserved", filesCopy.isDirectory)
            assertTrue(File(filesCopy, ".account-library-complete").isFile)
            assertArrayEquals(byteArrayOf(9), sentinel.readBytes())
            assertTrue(runCatching { store.prepare(accountA) }.exceptionOrNull() is AccountLibraryException)
            assertTrue(store.prepare(accountB) is AccountLibraryPreparation.ChoiceRequired)
        }
    }

    @Test fun accountDirectorySymlinkCannotAliasAnotherAccount(): Unit = runBlocking {
        withIsolatedBase("symlink") { base ->
            val store = LegacyLibraryClaimStore(base)
            val b = store.startFresh(accountB)
            File(b.filesDir, "b-private.bin").writeBytes(byteArrayOf(4))
            val aPath = File(base.filesDir, "account-libraries-v1/$accountA")
            android.system.Os.symlink(b.filesDir.path, aPath.path)

            assertTrue(runCatching { store.startFresh(accountA) }.exceptionOrNull() is AccountLibraryException)
            assertArrayEquals(byteArrayOf(4), File(b.filesDir, "b-private.bin").readBytes())
        }
    }

    @Test fun realStoresAndOpaquePhotoSurviveClaimWithoutCrossAccountExposure(): Unit = runBlocking {
        withIsolatedBase("real-codecs") { base ->
            val alias = "starglean.test.claim.${UUID.randomUUID()}"
            val source = File(base.cacheDir, "pixels.jpg")
            Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.rgb(32, 48, 72))
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                bitmap.recycle()
            }
            val photo = AndroidPhotoStore(base).importSelected(Uri.fromFile(source))
            val diaryId = "own-33333333-3333-4333-8333-333333333333"
            val diary = DiaryDemo(diaryId, "Claim fixture", "Private fixture body", "27 Sep",
                DiaryWeatherSnapshot.Unavailable, "Not recorded", PaperStyle.WarmWhite, DiaryFont.Clear, listOf(photo))
            val record = OwnDiaryRecord(diary, null, 1234L)
            OwnDiaryStore(base).save(record)
            val draft = WritingDraft("own-44444444-4444-4444-8444-444444444444", "Draft",
                "Draft body with enough private words.",
                PaperStyle.Kraft, DiaryFont.Serif, listOf(photo))
            val draftState = WritingDraftStore(base).save(0L, draft)
            val stars = ConstellationStore(base)
            stars.setTarget("cygnus-1")
            stars.placeDiary(diaryId, "cygnus-1")
            val accountUuid = UUID.fromString(accountA)
            val requestId = UUID.randomUUID()
            val request = PublishEntryRequest(requestId, "Pending", "Pending body", "warm_white", "clear",
                com.starglean.app.core.network.WeatherSnapshot(com.starglean.app.core.network.WeatherKind.Unknown,
                    com.starglean.app.core.network.Daylight.Day), visibility = EntryVisibility.Private,
                location = ReaderLocation(-37.8, 144.9, 5.0, Instant.parse("2026-09-27T00:00:00Z"), false),
                shareConfirmed = true)
            val pendingRecord = OwnDiaryRecord(DiaryDemo(draft.id, draft.title, draft.body, "27 Sep",
                DiaryWeatherSnapshot.Unavailable, "Not recorded", draft.paper, draft.font, draft.photos), null, 1235L)
            val pending = PendingPublication(FrozenPublication(accountUuid,
                UUID.fromString(draft.id.removePrefix("own-")), draftState, request, pendingRecord))
            AndroidPendingPublicationStore(base, base.noBackupFilesDir, alias).write(pending)

            val claimed = LegacyLibraryClaimStore(base, alias).claimLegacy(accountA)
            assertEquals(listOf(record), OwnDiaryStore(claimed).load())
            assertEquals(draftState, WritingDraftStore(claimed).load())
            assertNotNull(ConstellationStore(claimed).load().placementForDiary(diaryId))
            assertTrue(AndroidPhotoStore(claimed).exists(photo.ref))
            assertEquals(pending, AndroidPendingPublicationStore(claimed, claimed.noBackupFilesDir, alias).read())
            assertEquals(listOf("${photo.ref}.jpg"), File(claimed.filesDir, "diary-photos-v1")
                .listFiles().orEmpty().map(File::getName).sorted())

            val b = (LegacyLibraryClaimStore(base, alias).prepare(accountB) as AccountLibraryPreparation.Ready).context
            assertTrue(OwnDiaryStore(b).load().isEmpty())
            assertEquals(null, WritingDraftStore(b).load().draft)
            assertFalse(AndroidPhotoStore(b).exists(photo.ref))
            assertEquals(null, AndroidPendingPublicationStore(b, b.noBackupFilesDir, alias).read())
            val reloaded = (LegacyLibraryClaimStore(base, alias).prepare(accountA) as AccountLibraryPreparation.Ready).context
            assertEquals(photo.ref, OwnDiaryStore(reloaded).load().single().diary.photos.single().ref)
        }
    }

    @Test fun pendingPublicationOwnedByAnotherAccountBlocksClaimAndStaysGlobal(): Unit = runBlocking {
        withIsolatedBase("pending-owner") { base ->
            val alias = "starglean.test.claim-owner.${UUID.randomUUID()}"
            val requestId = UUID.randomUUID()
            val draft = WritingDraft("own-$requestId", "Pending", "Pending body", PaperStyle.WarmWhite, DiaryFont.Clear)
            val record = OwnDiaryRecord(DiaryDemo(draft.id, draft.title, draft.body, "27 Sep",
                DiaryWeatherSnapshot.Unavailable, "Not recorded", draft.paper, draft.font), null, 1L)
            val request = PublishEntryRequest(requestId, draft.title, draft.body, "warm_white", "clear",
                com.starglean.app.core.network.WeatherSnapshot(com.starglean.app.core.network.WeatherKind.Unknown,
                    com.starglean.app.core.network.Daylight.Day), visibility = EntryVisibility.Private,
                location = ReaderLocation(-37.8, 144.9, 5.0, Instant.parse("2026-09-27T00:00:00Z"), false),
                shareConfirmed = true)
            val pending = PendingPublication(FrozenPublication(UUID.fromString(accountA), requestId,
                WritingDraftState(1, draft), request, record))
            val pendingStore = AndroidPendingPublicationStore(base, base.noBackupFilesDir, alias)
            pendingStore.write(pending)

            assertTrue(runCatching { LegacyLibraryClaimStore(base, alias).claimLegacy(accountB) }
                .exceptionOrNull() is AccountLibraryException)
            assertEquals(pending, pendingStore.read())
            assertFalse(File(base.filesDir, "account-libraries-v1/$accountB").exists())
        }
    }

    @Test fun emptyDeviceAutoFreshPersistsAcrossWritesAndRestarts(): Unit = runBlocking {
        withIsolatedBase("auto-fresh") { base ->
            val first = (LegacyLibraryClaimStore(base).prepare(accountA) as AccountLibraryPreparation.Ready).context
            val record = OwnDiaryRecord(DiaryDemo("own-66666666-6666-4666-8666-666666666666", "Auto fresh",
                "Private auto-fresh fixture", "27 Sep", DiaryWeatherSnapshot.Unavailable, "Not recorded",
                PaperStyle.WarmWhite, DiaryFont.Clear), null, 6L)
            OwnDiaryStore(first).save(record)
            // Simulate process/storage loss after all FRESH roots were activated but before
            // the global decision became durable. Mode markers, not mutable content hashes,
            // recover this account without ever treating it as a legacy claim.
            File(base.noBackupFilesDir, "account-libraries-v1/ownership-v1.bin").delete()
            File(base.noBackupFilesDir, "account-libraries-v1/ownership-v1.bin.bak").delete()
            repeat(3) {
                val reopened = (LegacyLibraryClaimStore(base).prepare(accountA) as AccountLibraryPreparation.Ready).context
                assertEquals(listOf(record), OwnDiaryStore(reopened).load())
            }
        }
    }

    private suspend fun withIsolatedBase(name: String, block: suspend (Context) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.cacheDir, "account-isolation-$name-${System.nanoTime()}")
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        try { block(context) } finally { root.deleteRecursively() }
    }
}
