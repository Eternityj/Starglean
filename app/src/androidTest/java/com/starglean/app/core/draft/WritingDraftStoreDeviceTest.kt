package com.starglean.app.core.draft

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starglean.app.core.media.DiaryPhoto
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.PaperStyle
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

/** Real AtomicFile/CAS tests in a unique cache child; never reads the user's real filesDir. */
@RunWith(AndroidJUnit4::class)
class WritingDraftStoreDeviceTest {
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
        val context = IsolatedFilesContext(targetContext, created)
        assertSame(context, context.applicationContext)
        assertEquals(created, context.filesDir)
    }

    @After fun removeOnlyThisTestsOwnedFiles() {
        val root = ownedRoot ?: return
        assertEquals(cacheRoot, root.parentFile)
        assertEquals(root, root.canonicalFile)
        assertTrue(root.name.startsWith(DIRECTORY_PREFIX))
        assertEquals(ownerToken, File(root, OWNER_MARKER).readText(Charsets.UTF_8))
        val children = requireNotNull(root.listFiles())
        val allowed = setOf(STORE_NAME, "$STORE_NAME.new", "$STORE_NAME.bak", OWNER_MARKER)
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

    @Test fun firstUseAndAnEmptyDraftRoundTripThroughRealAtomicFile() = runBlocking {
        assertEquals(WritingDraftState(0L, null), newStore().load())
        val saved = newStore().save(0L, draft(body = ""))
        assertEquals(1L, saved.revision)
        assertEquals("", saved.draft?.body)
        assertEquals(saved, newStore().load())
        assertTrue(storeFile().isFile)
        assertFalse(File(requireRoot(), "$STORE_NAME.new").exists())
    }

    @Test fun newStoreReadsUnicodeWhitespaceAndStylesExactly() = runBlocking {
        val content = draft(body = "\n  半页中文🌙，还没写完。\t").copy(title = "  星星🪐  ", paper = PaperStyle.Midnight, font = DiaryFont.Serif)
        val saved = newStore().save(0L, content)
        assertEquals(saved, newStore().load())
        assertEquals(content, newStore().load().draft)
    }

    @Test fun newStorePersistsTwoPhotoReferencesAcrossInstances() = runBlocking {
        val photos = listOf(
            DiaryPhoto("photo-00000000-0000-0000-0000-000000000001", 1200, 800, sizeBytes = 45_000),
            DiaryPhoto("photo-00000000-0000-0000-0000-000000000002", 640, 960, sizeBytes = 32_000),
        )
        val saved = newStore().save(0L, draft().copy(photos = photos))
        assertEquals(photos, newStore().load().draft!!.photos)
        assertEquals(saved, newStore().load())
    }

    @Test fun identicalCurrentSaveIsIdempotentAndDoesNotRewriteBytes() = runBlocking {
        val saved = newStore().save(0L, draft())
        val bytes = storeFile().readBytes()
        repeat(3) { assertEquals(saved, newStore().save(saved.revision, draft())) }
        assertArrayEquals(bytes, storeFile().readBytes())
    }

    @Test fun oldAsyncWriterCannotOverwriteNewerContentEvenWithANewStoreInstance() = runBlocking {
        val first = newStore().save(0L, draft(body = "旧内容"))
        val second = newStore().save(first.revision, draft(body = "新内容"))
        val bytes = storeFile().readBytes()
        expectConflict { newStore().save(first.revision, draft(body = "迟到的旧写入")) }
        assertEquals(second, newStore().load())
        assertArrayEquals(bytes, storeFile().readBytes())
    }

    @Test fun simultaneousWritersWithTheSameExpectedRevisionHaveExactlyOneWinner() = runBlocking {
        val results = withTimeout(20_000L) {
            val start = CompletableDeferred<Unit>()
            val tasks = (1..24).map { number ->
                async(Dispatchers.IO) {
                    start.await()
                    try { newStore().save(0L, draft(body = "独立测试稿 $number")) }
                    catch (_: WritingDraftConflictException) { null }
                }
            }
            start.complete(Unit)
            tasks.awaitAll()
        }
        assertEquals(1, results.count { it != null })
        assertEquals(23, results.count { it == null })
        assertEquals(results.filterNotNull().single(), newStore().load())
        assertEquals(1L, newStore().load().revision)
    }

    @Test fun clearingWritesATombstoneAndOldWritersCannotResurrectAfterReload() = runBlocking {
        val saved = newStore().save(0L, draft())
        val cleared = newStore().clearIfMatches(saved)
        assertEquals(saved.revision + 1L, cleared.revision)
        assertNull(cleared.draft)
        assertTrue(storeFile().isFile)
        assertEquals(cleared, newStore().load())
        val bytes = storeFile().readBytes()
        expectConflict { newStore().save(saved.revision, draft()) }
        expectConflict { newStore().save(0L, draft()) }
        assertArrayEquals(bytes, storeFile().readBytes())
        val next = newStore().save(cleared.revision, draft(id = OTHER_ID, body = "下一页"))
        assertEquals(cleared.revision + 1L, next.revision)
    }

    @Test fun lateSubmitCleanupPreservesSameIdNewEditsAndOtherDraftIds() = runBlocking {
        val submitted = newStore().save(0L, draft(body = "已提交的那一版"))
        val edited = newStore().save(submitted.revision, draft(body = "同一编号的新编辑"))
        val bytes = storeFile().readBytes()
        assertEquals(edited, newStore().clearIfMatches(submitted))
        // Matching only revision or only ID is never sufficient.
        assertEquals(edited, newStore().clearIfMatches(edited.copy(draft = submitted.draft)))
        assertEquals(edited, newStore().clearIfMatches(edited.copy(draft = draft(id = OTHER_ID))))
        assertArrayEquals(bytes, storeFile().readBytes())
        assertEquals(edited, newStore().load())
    }

    @Test fun corruptDraftIsPreservedAndNeitherSaveNorClearTreatsItAsEmpty() = runBlocking {
        val saved = newStore().save(0L, draft())
        val corrupted = storeFile().readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        storeFile().writeBytes(corrupted)
        expectDataFailure { newStore().load() }
        expectDataFailure { newStore().save(saved.revision, draft(body = "不能覆盖")) }
        expectDataFailure { newStore().clearIfMatches(saved) }
        assertArrayEquals(corrupted, storeFile().readBytes())
    }

    @Test fun oversizedDraftFailsClosedWithoutReplacingTheFile() = runBlocking {
        val oversized = ByteArray(WritingDraftCodec.MAX_FILE_BYTES + 1) { 7 }
        storeFile().writeBytes(oversized)
        expectDataFailure { newStore().load() }
        expectDataFailure { newStore().save(0L, draft()) }
        expectDataFailure { newStore().clearIfMatches(WritingDraftState(0L, null)) }
        assertArrayEquals(oversized, storeFile().readBytes())
    }

    @Test fun orphanNewFileWithoutBaseIsPreservedAndCannotBeOverwritten() = runBlocking {
        val pending = File(requireRoot(), "$STORE_NAME.new")
        val bytes = byteArrayOf(0x53, 0x54, 0x44, 0x46, 1)
        pending.writeBytes(bytes)
        expectDataFailure { newStore().load() }
        expectDataFailure { newStore().save(0L, draft()) }
        assertFalse(storeFile().exists())
        assertArrayEquals(bytes, pending.readBytes())
    }

    @Test fun ambiguousPendingFileWithBaseAlsoPreservesBothVersions() = runBlocking {
        val saved = newStore().save(0L, draft())
        val committed = storeFile().readBytes()
        val pending = File(requireRoot(), "$STORE_NAME.new")
        val bytes = byteArrayOf(1, 2, 3)
        pending.writeBytes(bytes)
        expectDataFailure { newStore().load() }
        expectDataFailure { newStore().save(saved.revision, draft(body = "不能覆盖")) }
        expectDataFailure { newStore().clearIfMatches(saved) }
        assertArrayEquals(committed, storeFile().readBytes())
        assertArrayEquals(bytes, pending.readBytes())
    }

    @Test fun realAtomicRollbackKeepsTheCommittedRevisionAndDraft() = runBlocking {
        val saved = newStore().save(0L, draft())
        val bytes = storeFile().readBytes()
        val atomic = AtomicFile(storeFile())
        val unfinished = atomic.startWrite()
        try {
            unfinished.write(byteArrayOf(0x53, 0x54, 0x00))
            unfinished.flush()
        } finally { atomic.failWrite(unfinished) }
        assertArrayEquals(bytes, storeFile().readBytes())
        assertEquals(saved, newStore().load())
    }

    private fun requireRoot(): File {
        val root = checkNotNull(ownedRoot)
        check(root.canonicalFile == root && root.parentFile == cacheRoot && root.name.startsWith(DIRECTORY_PREFIX))
        return root
    }

    private fun storeFile(): File = File(requireRoot(), STORE_NAME)
    private fun newStore(): WritingDraftStore = WritingDraftStore(IsolatedFilesContext(targetContext, requireRoot()))
    private fun draft(id: String = ID, body: String = "未完成") = WritingDraft(id, "", body, PaperStyle.WarmWhite, DiaryFont.Clear)

    private suspend fun expectDataFailure(action: suspend () -> Any) {
        try { action() } catch (_: WritingDraftDataException) { return }
        throw AssertionError("Expected a safe draft-storage failure")
    }

    private suspend fun expectConflict(action: suspend () -> Any) {
        try { action() } catch (_: WritingDraftConflictException) { return }
        throw AssertionError("Expected a stale-revision conflict")
    }

    private class IsolatedFilesContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
    }

    private companion object {
        const val DIRECTORY_PREFIX = "writing-draft-device-test-"
        const val OWNER_MARKER = ".device-test-owner"
        const val STORE_NAME = "writing-draft-v1.bin"
        const val ID = "own-00000000-0000-0000-0000-000000000001"
        const val OTHER_ID = "own-00000000-0000-0000-0000-000000000002"
    }
}
