package com.starglean.app.core.library

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starglean.app.core.memory.OwnDiaryDataException
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.draft.WritingDraft
import com.starglean.app.core.draft.WritingDraftStore
import com.starglean.app.core.media.DiaryPhoto
import com.starglean.app.core.media.LibraryPhotoCleanupStore
import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.model.WeatherKind
import com.starglean.app.core.stars.ConstellationStore
import com.starglean.app.core.stars.StarCollectionDataException
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiaryDeletionStoreDeviceTest {
    private lateinit var cache: File
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun isolateFiles() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        cache = target.cacheDir.canonicalFile
        root = Files.createTempDirectory(cache.toPath(), "diary-delete-device-test-").toFile().canonicalFile
        context = IsolatedContext(target, root)
    }

    @After fun removeOnlyOwnedFixture() {
        DiaryDeletionFailureInjection.failAt = null
        assertEquals(cache, root.parentFile)
        assertTrue(root.name.startsWith("diary-delete-device-test-"))
        root.listFiles().orEmpty().forEach { file ->
            if (file.name == PHOTO_DIRECTORY) {
                assertTrue(file.isDirectory)
                file.listFiles().orEmpty().forEach { photo ->
                    assertTrue(photo.isFile)
                    assertTrue(Regex("photo-[0-9a-f-]{36}\\.jpg").matches(photo.name))
                    assertTrue(photo.delete())
                }
                assertTrue(file.delete())
            } else {
                assertTrue(file.name in ALLOWED)
                assertTrue(file.isFile)
                assertTrue(file.delete())
            }
        }
        assertTrue(root.delete())
    }

    @Test fun deleteExtinguishesOnlyItsStarWithdrawsIncompleteHomeAndBlocksResurrection() = runBlocking {
        val diaries = OwnDiaryStore(context)
        val stars = ConstellationStore(context)
        repeat(5) { diaries.save(record(it + 1)); stars.placeDiary(id(it + 1), "lyra-1") }
        stars.setHome(setOf("lyra-1"))

        val result = DiaryDeletionStore(context).deleteDiary(id(3))
        assertTrue(result.deleted)
        assertEquals(2, result.placement?.starIndex)
        assertTrue(result.removedFromHome)
        assertEquals(listOf(id(1), id(2), id(4), id(5)).toSet(), diaries.load().map { it.diary.id }.toSet())
        val state = stars.load()
        assertNull(state.diaryAt("lyra-1", 2))
        assertTrue(state.homeIds.isEmpty())
        assertEquals(setOf(0, 1, 3, 4), state.placements.map { it.starIndex }.toSet())

        expectDeletedSave { diaries.save(record(3)) }
        try {
            stars.placeDiary(id(3), "orion-1")
            throw AssertionError("A deleted identity must not be placed")
        } catch (_: StarCollectionDataException) { }
    }

    @Test fun recoveryIntentRollsForwardBeforeEitherStoreCanExposeAnIntermediateState() = runBlocking {
        val diaries = OwnDiaryStore(context)
        val stars = ConstellationStore(context)
        diaries.save(record(1)); stars.placeDiary(id(1), "orion-1")
        LibraryDeletionFiles.writeIntentForRecoveryTest(root, id(1))
        assertEquals(68L, File(root, "diary-delete-intent-v1.bin").length())

        assertTrue(diaries.load().isEmpty())
        assertTrue(stars.load().placements.isEmpty())
        assertFalse(File(root, "diary-delete-intent-v1.bin").exists())
        expectDeletedSave { diaries.save(record(1)) }
    }

    @Test fun absentDeleteIsIdempotentAndDoesNotCreateSafetyFiles() = runBlocking {
        val result = DiaryDeletionStore(context).deleteDiary(id(9))
        assertFalse(result.deleted)
        assertNull(result.placement)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun concurrentOldRetriesCannotWinAgainstACompletedDelete() = runBlocking {
        val diaries = OwnDiaryStore(context)
        val original = record(1)
        diaries.save(original)
        val deletion = async(Dispatchers.IO) { DiaryDeletionStore(context).deleteDiary(id(1)) }
        val retries = (0 until 20).map { async(Dispatchers.IO) {
            try { diaries.save(original) } catch (_: OwnDiaryDataException) { }
        } }
        deletion.await()
        retries.awaitAll()
        assertTrue(diaries.load().isEmpty())
        expectDeletedSave { diaries.save(original) }
    }

    @Test fun fullTombstoneSetRefusesDeletionWithoutEvictingAnOlderProtection() = runBlocking {
        val diaries = OwnDiaryStore(context)
        diaries.save(record(1))
        LibraryDeletionFiles.fillTombstonesForCapacityTest(root)
        val before = File(root, "deleted-diary-ids-v1.bin").readBytes()
        try {
            DiaryDeletionStore(context).deleteDiary(id(1))
            throw AssertionError("Deletion must fail closed when no tombstone can be retained")
        } catch (_: DiaryDeletionDataException) { }
        assertEquals(listOf(id(1)), diaries.load().map { it.diary.id })
        assertTrue(before.contentEquals(File(root, "deleted-diary-ids-v1.bin").readBytes()))
        assertFalse(File(root, "diary-delete-intent-v1.bin").exists())
    }

    @Test fun everyPersistedFailureBoundaryRollsForwardWithoutDanglingReferences() = runBlocking {
        val diaries = OwnDiaryStore(context)
        val stars = ConstellationStore(context)
        DiaryDeletionFailurePoint.values().forEachIndexed { index, point ->
            val number = index + 1
            diaries.save(record(number))
            stars.placeDiary(id(number), "orion-1")
            DiaryDeletionFailureInjection.failAt = point
            try {
                DiaryDeletionStore(context).deleteDiary(id(number))
                throw AssertionError("Expected injected interruption at $point")
            } catch (_: DiaryDeletionDataException) { }
            assertTrue(File(root, "diary-delete-intent-v1.bin").exists())
            DiaryDeletionFailureInjection.failAt = null
            assertFalse(diaries.load().any { it.diary.id == id(number) })
            assertNull(stars.load().placementForDiary(id(number)))
            assertFalse(File(root, "diary-delete-intent-v1.bin").exists())
            expectDeletedSave { diaries.save(record(number)) }
        }
    }

    @Test fun recoveryFailureRemainsFailClosedAndCanBeRetriedLater() = runBlocking {
        val diaries = OwnDiaryStore(context)
        val stars = ConstellationStore(context)
        diaries.save(record(1)); stars.placeDiary(id(1), "orion-1")
        LibraryDeletionFiles.writeIntentForRecoveryTest(root, id(1))
        DiaryDeletionFailureInjection.failAt = DiaryDeletionFailurePoint.AfterStars
        try {
            diaries.load()
            throw AssertionError("Expected recovery to stop at the injected boundary")
        } catch (_: OwnDiaryDataException) { }
        assertTrue(File(root, "diary-delete-intent-v1.bin").exists())
        DiaryDeletionFailureInjection.failAt = null
        assertTrue(diaries.load().isEmpty())
        assertTrue(stars.load().placements.isEmpty())
        assertFalse(File(root, "diary-delete-intent-v1.bin").exists())
    }

    @Test fun deletionRemovesAPhotoOnlyAfterNoSavedDiaryOrDraftReferencesIt() = runBlocking {
        val photo = DiaryPhoto("photo-00000000-0000-0000-0000-000000000001", 20, 20, DiaryPhoto.MIME_TYPE, 4)
        val photoRoot = File(root, PHOTO_DIRECTORY)
        assertTrue(photoRoot.mkdir())
        val ownedPhoto = File(photoRoot, "${photo.ref}.jpg")
        ownedPhoto.writeBytes(byteArrayOf(1, 2, 3, 4))
        val diaries = OwnDiaryStore(context)
        diaries.save(record(1, listOf(photo)))
        diaries.save(record(2, listOf(photo)))

        DiaryDeletionStore(context).deleteDiary(id(1))
        assertTrue("The second saved entry still owns the photo", ownedPhoto.isFile)

        val drafts = WritingDraftStore(context)
        val draft = WritingDraft(id(9), "Private fixture", "A fictional isolated draft.", PaperStyle.WarmWhite, DiaryFont.Clear, listOf(photo))
        val savedDraft = drafts.save(0L, draft)
        DiaryDeletionStore(context).deleteDiary(id(2))
        assertTrue("The durable draft still owns the photo", ownedPhoto.isFile)

        drafts.clearIfMatches(savedDraft)
        LibraryPhotoCleanupStore(context).cleanupCandidates(emptySet())
        assertFalse("The explicitly queued photo has no remaining reference", ownedPhoto.exists())
    }

    private suspend fun expectDeletedSave(block: suspend () -> Unit) {
        try { block() } catch (_: OwnDiaryDataException) { return }
        throw AssertionError("A stale save must not resurrect a deleted identity")
    }

    private fun record(number: Int, photos: List<DiaryPhoto> = emptyList()) = OwnDiaryRecord(
        DiaryDemo(id(number), "Private test", "A fictional private test entry with enough text $number.", "2026-09-27",
            DiaryWeatherSnapshot(WeatherKind.Clear, Daylight.Night, null, null, false), "", PaperStyle.WarmWhite, DiaryFont.Clear, photos),
        null, 1_800_000_000_000L + number,
    )

    private fun id(number: Int) = "own-00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = root
        override fun getApplicationContext(): Context = this
    }

    private companion object {
        val ALLOWED = setOf(
            "own-diaries-v1.bin", "own-diaries-v1.bin.bak", "own-diaries-v1.bin.new",
            "star-collection-v1.bin", "star-collection-v1.bin.bak", "star-collection-v1.bin.new",
            "deleted-diary-ids-v1.bin", "deleted-diary-ids-v1.bin.bak", "deleted-diary-ids-v1.bin.new",
            "diary-delete-intent-v1.bin", "diary-delete-intent-v1.bin.bak", "diary-delete-intent-v1.bin.new",
            "writing-draft-v1.bin", "writing-draft-v1.bin.bak", "writing-draft-v1.bin.new",
            "photo-cleanup-candidates-v1.bin", "photo-cleanup-candidates-v1.bin.bak", "photo-cleanup-candidates-v1.bin.new",
        )
        const val PHOTO_DIRECTORY = "diary-photos-v1"
    }
}
