package com.starglean.app.core.stars

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.model.WeatherKind
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Only unique, owned cache files are used; the installed user's filesDir is never accessed. */
@RunWith(AndroidJUnit4::class)
class ConstellationStoreDeviceTest {
    private lateinit var targetContext: Context
    private lateinit var cacheRoot: File
    private lateinit var ownerToken: String
    private var ownedRoot: File? = null

    @Before fun createIsolatedTestDirectory() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        cacheRoot = targetContext.cacheDir.canonicalFile
        val created = Files.createTempDirectory(cacheRoot.toPath(), DIRECTORY_PREFIX).toFile().canonicalFile
        assertEquals(cacheRoot, created.parentFile)
        ownerToken = UUID.randomUUID().toString()
        File(created, OWNER_MARKER).writeText(ownerToken, Charsets.UTF_8)
        ownedRoot = created
        val isolated = isolatedContext()
        assertSame(isolated, isolated.applicationContext)
        assertEquals(created, isolated.filesDir)
    }

    @After fun removeOnlyThisTestsOwnedFiles() {
        val root = ownedRoot ?: return
        assertEquals(cacheRoot, root.parentFile)
        assertEquals(root, root.canonicalFile)
        assertTrue(root.name.startsWith(DIRECTORY_PREFIX))
        assertEquals(ownerToken, File(root, OWNER_MARKER).readText(Charsets.UTF_8))
        val allowed = setOf(STORE_NAME, "$STORE_NAME.new", "$STORE_NAME.bak", DIARY_NAME, "$DIARY_NAME.new", "$DIARY_NAME.bak", OWNER_MARKER)
        val children = requireNotNull(root.listFiles())
        children.forEach { child ->
            assertTrue("Refusing to remove an unexpected test file", child.name in allowed)
            assertEquals(root, child.canonicalFile.parentFile)
            assertTrue("Refusing to traverse a test subdirectory", child.isFile)
        }
        children.filter { it.name != OWNER_MARKER }.forEach { assertTrue(it.delete()) }
        assertTrue(File(root, OWNER_MARKER).delete())
        assertTrue(root.delete())
        ownedRoot = null
    }

    @Test fun firstUseDoesNotImportOldEntriesOrExampleProgress() = runBlocking {
        saveEntries(3)
        assertEquals(StarCollectionState.initial(), newStore().load())
        assertFalse(storeFile().exists())
        assertEquals(3, diaryStore().load().size)
        assertTrue(newStore().load().galleryItems().all { it.litStars == 0 && !it.shownOnHome })
    }

    @Test fun targetPlacementAndDuplicateTypeRoundTripThroughNewStoreInstances() = runBlocking {
        saveEntries(2)
        newStore().createInstance("cygnus-1")
        newStore().setTarget("cygnus-2")
        val placed = newStore().placeDiary(id(1))
        assertTrue(placed.newlyPlaced)
        assertEquals(DiaryStarPlacement(id(1), "cygnus-2", 0), placed.placement)
        assertEquals(placed.state, newStore().load())
        val second = newStore().placeDiary(id(2))
        assertEquals(id(2), second.state.diaryAt("cygnus-2", 1))
        assertEquals(2, newStore().load().galleryItems().single { it.id == "cygnus-2" }.litStars)
        assertFalse(File(requireRoot(), "$STORE_NAME.new").exists())
    }

    @Test fun retryAfterATargetChangeCannotMoveOrDuplicateTheEntry() = runBlocking {
        saveEntries(1)
        newStore().setTarget("lyra-1")
        val first = newStore().placeDiary(id(1))
        newStore().setTarget("orion-1")
        val bytes = storeFile().readBytes()
        val retry = newStore().placeDiary(id(1), "cygnus-1")
        assertEquals(first.placement, retry.placement)
        assertFalse(retry.newlyPlaced)
        assertEquals(1, retry.state.placements.size)
        assertArrayEquals(bytes, storeFile().readBytes())
    }

    @Test fun unsavedOrDemoIdentitiesCannotCreateStars() = runBlocking {
        newStore().setTarget("lyra-1")
        val bytes = storeFile().readBytes()
        expectFailure { newStore().placeDiary(id(1)) }
        expectFailure { newStore().placeDiary("demo-own-1") }
        assertTrue(newStore().load().placements.isEmpty())
        assertArrayEquals(bytes, storeFile().readBytes())
    }

    @Test fun noTargetAndFullTargetLeaveEntriesSavedAndUnplaced() = runBlocking {
        saveEntries(6)
        assertNull(newStore().placeDiary(id(1)).placement)
        assertFalse(storeFile().exists())
        newStore().setTarget("lyra-1")
        repeat(5) { newStore().placeDiary(id(it + 1)) }
        val bytes = storeFile().readBytes()
        val full = newStore().placeDiary(id(6))
        assertNull(full.placement)
        assertFalse(full.newlyPlaced)
        assertEquals(6, diaryStore().load().size)
        assertEquals(5, full.state.placements.size)
        assertTrue(full.state.homeIds.isEmpty())
        assertArrayEquals(bytes, storeFile().readBytes())
    }

    @Test fun homeSelectionIsExplicitAndCanBeClearedWithoutChangingStarsOrEntries() = runBlocking {
        saveEntries(5)
        repeat(5) { newStore().placeDiary(id(it + 1), "lyra-1") }
        val before = newStore().load()
        assertTrue(before.homeIds.isEmpty())
        val selected = newStore().setHome(setOf("lyra-1"))
        assertEquals(selected, newStore().load())
        assertTrue(selected.galleryItems().single { it.id == "lyra-1" }.shownOnHome)
        expectFailure { newStore().setHome(setOf("cygnus-1")) }
        val cleared = newStore().setHome(emptySet())
        assertEquals(before, cleared)
        assertEquals(5, diaryStore().load().size)
    }

    @Test fun concurrentSameEntryRetriesCreateExactlyOnePlacement() = runBlocking {
        saveEntries(1)
        newStore().setTarget("lyra-1")
        val results = concurrently(24) { newStore().placeDiary(id(1)) }
        assertEquals(1, results.count { it.newlyPlaced })
        assertEquals(1, results.map { it.placement }.toSet().size)
        assertEquals(1, newStore().load().placements.size)
    }

    @Test fun concurrentDifferentEntriesNeverLoseOrShareAPointAndOverflowStaysSaved() = runBlocking {
        saveEntries(15)
        newStore().setTarget("orion-1")
        val results = concurrently(15) { number -> newStore().placeDiary(id(number + 1)) }
        assertEquals(9, results.count { it.newlyPlaced })
        assertEquals(6, results.count { it.placement == null })
        val reloaded = newStore().load()
        assertEquals((0..8).toSet(), reloaded.placements.map { it.starIndex }.toSet())
        assertEquals(9, reloaded.placements.map { it.diaryId }.toSet().size)
        assertEquals(15, diaryStore().load().size)
    }

    @Test fun concurrentCreationCannotExceedFiveInstancesOrReuseAnOrdinal() = runBlocking {
        val results = concurrently(12) {
            try { newStore().createInstance("lyra-1") } catch (_: StarCollectionDataException) { null }
        }
        assertEquals(4, results.count { it != null })
        assertEquals(listOf(1, 2, 3, 4, 5), newStore().load().instances.filter { it.templateId == "lyra-1" }.map { it.ordinal })
    }

    @Test fun corruptCollectionRefusesEveryMutationButNeverModifiesTheSavedDiaryFile() = runBlocking {
        saveEntries(2)
        newStore().setTarget("lyra-1")
        newStore().placeDiary(id(1))
        val diaryBytes = diaryFile().readBytes()
        val corrupt = storeFile().readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        storeFile().writeBytes(corrupt)
        expectAllMutationsFail()
        assertArrayEquals(corrupt, storeFile().readBytes())
        assertArrayEquals(diaryBytes, diaryFile().readBytes())
        assertEquals(2, diaryStore().load().size)
    }

    @Test fun oversizedCollectionIsPreservedRatherThanReset() = runBlocking {
        saveEntries(2)
        val bytes = ByteArray(StarCollectionCodec.MAX_FILE_BYTES + 1) { 7 }
        storeFile().writeBytes(bytes)
        expectAllMutationsFail()
        assertArrayEquals(bytes, storeFile().readBytes())
        assertEquals(2, diaryStore().load().size)
    }

    @Test fun orphanPendingFileWithoutBaseIsPreservedAndNoReplacementIsWritten() = runBlocking {
        saveEntries(2)
        val bytes = byteArrayOf(0x53, 0x54, 1)
        val pending = File(requireRoot(), "$STORE_NAME.new")
        pending.writeBytes(bytes)
        expectAllMutationsFail()
        assertArrayEquals(bytes, pending.readBytes())
        assertFalse(storeFile().exists())
    }

    @Test fun ambiguousPendingFileWithBasePreservesBothVersions() = runBlocking {
        saveEntries(2)
        newStore().setTarget("lyra-1")
        val committed = storeFile().readBytes()
        val pending = File(requireRoot(), "$STORE_NAME.new")
        val unfinished = byteArrayOf(0x53, 0x54, 2)
        pending.writeBytes(unfinished)
        expectAllMutationsFail()
        assertArrayEquals(committed, storeFile().readBytes())
        assertArrayEquals(unfinished, pending.readBytes())
    }

    @Test fun missingOrCorruptDiaryLibraryCannotEraseOrExtendExistingMappings() = runBlocking {
        saveEntries(2)
        newStore().placeDiary(id(1), "lyra-1")
        val collection = storeFile().readBytes()
        val diaryBytes = diaryFile().readBytes()
        assertTrue(diaryFile().delete()) // Only this test's isolated fixture, never the user's entries.
        expectAllMutationsFail()
        assertArrayEquals(collection, storeFile().readBytes())
        diaryFile().writeBytes(byteArrayOf(0x53, 0))
        expectAllMutationsFail()
        assertArrayEquals(collection, storeFile().readBytes())
        assertArrayEquals(byteArrayOf(0x53, 0), diaryFile().readBytes())
        diaryFile().writeBytes(diaryBytes)
        assertEquals(id(1), newStore().load().diaryAt("lyra-1", 0))
    }

    @Test fun atomicRollbackLeavesCommittedMappingAndAllowsIdempotentRetry() = runBlocking {
        saveEntries(2)
        val first = newStore().placeDiary(id(1), "lyra-1")
        val committed = storeFile().readBytes()
        val atomic = AtomicFile(storeFile())
        val unfinished = atomic.startWrite()
        try { unfinished.write(byteArrayOf(0x53, 0x54)); unfinished.flush() }
        finally { atomic.failWrite(unfinished) }
        assertArrayEquals(committed, storeFile().readBytes())
        assertEquals(first.state, newStore().load())
        val retry = newStore().placeDiary(id(1), "orion-1")
        assertFalse(retry.newlyPlaced)
        assertEquals(first.placement, retry.placement)
        assertTrue(newStore().placeDiary(id(2), "lyra-1").newlyPlaced)
    }

    private suspend fun expectAllMutationsFail() {
        expectFailure { newStore().load() }
        expectFailure { newStore().setTarget("cygnus-1") }
        expectFailure { newStore().createInstance("cygnus-1") }
        expectFailure { newStore().setHome(emptySet()) }
        expectFailure { newStore().placeDiary(id(2), "lyra-1") }
    }

    private suspend fun <T> concurrently(count: Int, block: suspend (Int) -> T): List<T> = withTimeout(20_000L) {
        val start = CompletableDeferred<Unit>()
        val tasks = (0 until count).map { number -> async(Dispatchers.IO) { start.await(); block(number) } }
        start.complete(Unit)
        tasks.awaitAll()
    }

    private suspend fun saveEntries(count: Int) {
        repeat(count) { diaryStore().save(record(it + 1)) }
    }

    private fun record(number: Int) = OwnDiaryRecord(
        diary = DiaryDemo(
            id = id(number), title = "Isolated storage test", body = "A private test entry with enough text, number $number.",
            date = "2026-09-16", weatherSnapshot = DiaryWeatherSnapshot(WeatherKind.Clear, Daylight.Day, null, null, false),
            blurredPlace = "", paper = PaperStyle.WarmWhite, font = DiaryFont.Clear,
        ),
        place = null,
        savedAtMillis = 1_700_000_000_000L + number,
    )

    private fun requireRoot(): File = requireNotNull(ownedRoot)
    private fun isolatedContext(): Context = IsolatedFilesContext(targetContext, requireRoot())
    private fun newStore(): ConstellationStore = ConstellationStore(isolatedContext())
    private fun diaryStore(): OwnDiaryStore = OwnDiaryStore(isolatedContext())
    private fun storeFile(): File = File(requireRoot(), STORE_NAME)
    private fun diaryFile(): File = File(requireRoot(), DIARY_NAME)
    private fun id(number: Int) = "own-00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
    private suspend fun expectFailure(action: suspend () -> Any) {
        try { action() } catch (_: StarCollectionDataException) { return }
        throw AssertionError("Expected a safe constellation-storage failure")
    }

    private class IsolatedFilesContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
    }

    private companion object {
        const val DIRECTORY_PREFIX = "constellation-device-test-"
        const val OWNER_MARKER = ".device-test-owner"
        const val STORE_NAME = "star-collection-v1.bin"
        const val DIARY_NAME = "own-diaries-v1.bin"
    }
}
