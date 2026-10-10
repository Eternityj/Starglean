package com.starglean.app.core.media

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Gainmap
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.starglean.app.core.draft.WritingDraft
import com.starglean.app.core.draft.WritingDraftState
import com.starglean.app.core.draft.WritingDraftStore
import com.starglean.app.core.library.LibraryStorageCoordinator
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.ui.screens.WritingDraftPersistence
import com.starglean.app.ui.screens.WritingDraftSession
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses generated pixels in a uniquely-owned cache tree; it never reads the user's gallery. */
@RunWith(AndroidJUnit4::class)
class AndroidPhotoStoreDeviceTest {
    private lateinit var cacheRoot: File
    private lateinit var testRoot: File
    private lateinit var filesRoot: File
    private lateinit var sourceRoot: File
    private lateinit var marker: String
    private lateinit var isolatedContext: Context
    private lateinit var store: AndroidPhotoStore

    @Before fun setUpOwnedCache() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        cacheRoot = target.cacheDir.canonicalFile
        testRoot = Files.createTempDirectory(cacheRoot.toPath(), PREFIX).toFile().canonicalFile
        assertEquals(cacheRoot, testRoot.parentFile)
        marker = UUID.randomUUID().toString()
        File(testRoot, MARKER).writeText(marker)
        filesRoot = File(testRoot, "files").apply { check(mkdir()) }.canonicalFile
        sourceRoot = File(testRoot, "sources").apply { check(mkdir()) }.canonicalFile
        isolatedContext = IsolatedFilesContext(target, filesRoot)
        store = AndroidPhotoStore(isolatedContext)
    }

    @After fun removeOnlyOwnedCache() {
        assertEquals(cacheRoot, testRoot.parentFile)
        assertTrue(testRoot.name.startsWith(PREFIX))
        assertEquals(marker, File(testRoot, MARKER).readText())
        testRoot.walkBottomUp().forEach { file ->
            assertTrue(file == testRoot || file.toPath().startsWith(testRoot.toPath()))
            assertTrue(file.delete())
        }
    }

    @Test fun importsScalesReencodesAndDoesNotRetainSourceMetadata(): Unit = runBlocking {
        val source = File(sourceRoot, "synthetic-private-name.jpg")
        writeJpeg(source, 2600, 1300)
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,48/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "S")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "144/1,54/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            saveAttributes()
        }

        val imported = store.importSelected(Uri.fromFile(source))

        assertTrue(imported.height > imported.width)
        assertEquals(2.0, imported.height.toDouble() / imported.width.toDouble(), 0.02)
        assertTrue(maxOf(imported.width, imported.height) <= DiaryPhotoRules.MAX_LONG_EDGE)
        assertEquals(DiaryPhoto.MIME_TYPE, imported.mimeType)
        assertTrue(imported.sizeBytes in 1..DiaryPhotoRules.MAX_STORED_BYTES)
        assertFalse(imported.ref.contains(source.name))
        assertTrue(store.exists(imported.ref))
        store.open(imported.ref).use { input ->
            val bitmap = requireNotNull(BitmapFactory.decodeStream(input))
            assertEquals(imported.width, bitmap.width)
            assertEquals(imported.height, bitmap.height)
            bitmap.recycle()
        }
        ExifInterface(store.fileForDisplay(imported.ref).absolutePath).also { exif ->
            assertEquals(ExifInterface.ORIENTATION_UNDEFINED,
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
            assertEquals(null, exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertEquals(null, exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        }
    }

    @Test @SdkSuppress(minSdkVersion = 34)
    fun importsGainmapJpegAsSdrWithoutApp1OrGainmap(): Unit = runBlocking {
        val source = File(sourceRoot, "synthetic-gainmap.jpg")
        val base = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(70, 120, 180))
        }
        val gainmapPixels = Bitmap.createBitmap(1000, 750, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GRAY)
        }
        try {
            base.setGainmap(Gainmap(gainmapPixels).apply {
                setRatioMin(1f, 1f, 1f)
                setRatioMax(2f, 2f, 2f)
                setGamma(1f, 1f, 1f)
                setEpsilonSdr(0f, 0f, 0f)
                setEpsilonHdr(0f, 0f, 0f)
                setDisplayRatioForFullHdr(2f)
            })
            FileOutputStream(source).use { output ->
                assertTrue(base.compress(Bitmap.CompressFormat.JPEG, 92, output))
            }
        } finally {
            base.recycle()
            gainmapPixels.recycle()
        }

        assertTrue("The generated source must contain the gainmap APP1 marker", jpegHasMarker(source.readBytes(), 0xe1))
        val decodedSource = requireNotNull(BitmapFactory.decodeFile(source.absolutePath))
        val referencePixel: Int
        try {
            assertTrue("The generated JPEG must decode with its gainmap", decodedSource.hasGainmap())
            assertFalse("An opaque HDR JPEG must exercise the previous direct-compress path", decodedSource.hasAlpha())
            referencePixel = decodedSource.getPixel(decodedSource.width / 2, decodedSource.height / 2)
        } finally {
            decodedSource.recycle()
        }

        val imported = store.importSelected(Uri.fromFile(source))
        val output = store.fileForDisplay(imported.ref)
        val outputBytes = output.readBytes()
        assertFalse("Normalized output must not contain APP1 metadata", jpegHasMarker(outputBytes, 0xe1))
        assertEquals(2000, imported.width)
        assertEquals(1500, imported.height)
        assertTrue(imported.sizeBytes in 1..DiaryPhotoRules.MAX_STORED_BYTES)
        assertEquals(output.length(), imported.sizeBytes)

        val decodedOutput = requireNotNull(BitmapFactory.decodeByteArray(outputBytes, 0, outputBytes.size))
        try {
            assertFalse("Normalized output must not retain the gainmap", decodedOutput.hasGainmap())
            assertFalse("Normalized JPEG output must be opaque", decodedOutput.hasAlpha())
            assertEquals(imported.width, decodedOutput.width)
            assertEquals(imported.height, decodedOutput.height)
            val center = decodedOutput.getPixel(decodedOutput.width / 2, decodedOutput.height / 2)
            assertEquals(Color.red(referencePixel).toDouble(), Color.red(center).toDouble(), 5.0)
            assertEquals(Color.green(referencePixel).toDouble(), Color.green(center).toDouble(), 5.0)
            assertEquals(Color.blue(referencePixel).toDouble(), Color.blue(center).toDouble(), 5.0)
        } finally {
            decodedOutput.recycle()
        }
    }

    @Test fun rejectsMalformedInputAndCleansOnlyItsOwnTemporaryFiles(): Unit = runBlocking {
        val source = File(sourceRoot, "not-an-image.bin").apply { writeBytes(ByteArray(1024) { 7 }) }
        val before = sourceRoot.listFiles()!!.toSet()
        try {
            store.importSelected(Uri.fromFile(source))
            throw AssertionError("Malformed input was accepted")
        } catch (error: PhotoStoreException) {
            assertFalse(error.message.orEmpty().contains(source.absolutePath))
            assertFalse(error.message.orEmpty().contains(source.name))
        }
        assertEquals(before, sourceRoot.listFiles()!!.toSet())
        val owned = File(filesRoot, "diary-photos-v1").listFiles().orEmpty()
        assertTrue(owned.isEmpty())
    }

    @Test fun deleteRequiresAnExplicitlyUnreferencedOwnedId(): Unit = runBlocking {
        val source = File(sourceRoot, "pixels.jpg")
        writeJpeg(source, 32, 24)
        val imported = store.importSelected(Uri.fromFile(source))

        assertFalse(store.deleteIfUnreferenced(imported.ref, setOf(imported.ref)))
        assertTrue(store.exists(imported.ref))
        assertTrue(store.deleteIfUnreferenced(imported.ref, emptySet()))
        assertFalse(store.exists(imported.ref))
        assertFalse(store.deleteIfUnreferenced(imported.ref, emptySet()))
    }

    @Test fun rejectsInputOverByteLimitWithoutTouchingTheSource(): Unit = runBlocking {
        val source = File(sourceRoot, "oversized.bin")
        RandomAccessFile(source, "rw").use { it.setLength(DiaryPhotoRules.MAX_SOURCE_BYTES + 1L) }
        val originalSize = source.length()
        try {
            store.importSelected(Uri.fromFile(source))
            throw AssertionError("Oversized input was accepted")
        } catch (_: PhotoStoreException) {
            // Expected: no details from the source are exposed by the public error.
        }
        assertEquals(originalSize, source.length())
        assertTrue(File(filesRoot, "diary-photos-v1").listFiles().orEmpty().isEmpty())
    }

    @Test fun importsCreateDifferentOpaqueIdentities(): Unit = runBlocking {
        val source = File(sourceRoot, "same.jpg")
        writeJpeg(source, 20, 10)
        val first = store.importSelected(Uri.fromFile(source))
        val second = store.importSelected(Uri.fromFile(source))
        assertNotEquals(first.ref, second.ref)
    }

    @Test fun candidateCleanupWaitsUntilThePersistedDraftReleasesThePhoto(): Unit = runBlocking {
        val source = File(sourceRoot, "attached.jpg")
        writeJpeg(source, 40, 30)
        val imported = store.importSelected(Uri.fromFile(source))
        val draftStore = WritingDraftStore(isolatedContext)
        val saved = draftStore.save(0L, WritingDraft(
            "own-00000000-0000-0000-0000-000000000001", "", "draft", PaperStyle.WarmWhite,
            DiaryFont.Clear, listOf(imported),
        ))

        LibraryPhotoCleanupStore(isolatedContext).cleanupCandidates(setOf(imported.ref))
        assertTrue(store.exists(imported.ref))
        draftStore.clearIfMatches(saved)
        LibraryPhotoCleanupStore(isolatedContext).cleanupCandidates(emptySet())
        assertFalse(store.exists(imported.ref))
    }

    @Test fun cleanupRejectsASymlinkedPhotoDirectoryWithoutDeletingItsTarget(): Unit = runBlocking {
        val ref = "photo-00000000-0000-0000-0000-000000000099"
        val outside = File(sourceRoot, "$ref.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val link = File(filesRoot, "diary-photos-v1").toPath()
        Files.createSymbolicLink(link, sourceRoot.toPath())
        try {
            try {
                LibraryPhotoCleanupStore(isolatedContext).cleanupCandidates(setOf(ref))
                throw AssertionError("Symlinked storage was accepted")
            } catch (_: PhotoCleanupDataException) {
                // Fail closed: the external target and the pending retry record are preserved.
            }
            assertTrue(outside.isFile)
            assertArrayEquals(byteArrayOf(1, 2, 3), outside.readBytes())
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test fun activeImportLeaseBlocksCleanupUntilItIsAbandoned(): Unit = runBlocking {
        val source = File(sourceRoot, "leased.jpg")
        writeJpeg(source, 48, 32)
        val coordinator = PhotoImportCoordinator(isolatedContext, "10000000-0000-0000-0000-000000000001")
        val leased = coordinator.importSelected(Uri.fromFile(source))
        LibraryPhotoCleanupStore(isolatedContext).cleanupCandidates(setOf(leased.ref))
        assertTrue(store.exists(leased.ref))
        coordinator.abandon(leased.ref)
        assertFalse(store.exists(leased.ref))
    }

    @Test fun nextProcessRecoversAnUnboundImportedFileWithoutScanningTheDirectory(): Unit = runBlocking {
        val source = File(sourceRoot, "crash-before-attach.jpg")
        writeJpeg(source, 48, 32)
        val first = PhotoImportCoordinator(isolatedContext, "20000000-0000-0000-0000-000000000001")
        val leased = first.importSelected(Uri.fromFile(source))
        val interruptedSource = File(filesRoot, "diary-photos-v1/.${leased.ref}.source").apply {
            writeBytes(byteArrayOf(9, 8, 7))
        }
        assertTrue(store.exists(leased.ref))

        PhotoImportCoordinator(isolatedContext, "20000000-0000-0000-0000-000000000002")
            .recoverPreviousProcess()
        assertFalse(store.exists(leased.ref))
        assertFalse(interruptedSource.exists())
    }

    @Test fun restartKeepsALeasedPhotoThatTheDurableDraftAlreadyReferences(): Unit = runBlocking {
        val source = File(sourceRoot, "crash-after-draft.jpg")
        writeJpeg(source, 48, 32)
        val first = PhotoImportCoordinator(isolatedContext, "30000000-0000-0000-0000-000000000001")
        val leased = first.importSelected(Uri.fromFile(source))
        val draftStore = WritingDraftStore(isolatedContext)
        val saved = draftStore.save(0L, WritingDraft(
            "own-00000000-0000-0000-0000-000000000003", "", "draft", PaperStyle.WarmWhite,
            DiaryFont.Clear, listOf(leased.photo),
        ))

        PhotoImportCoordinator(isolatedContext, "30000000-0000-0000-0000-000000000002")
            .recoverPreviousProcess()
        assertTrue(store.exists(leased.ref))
        draftStore.clearIfMatches(saved)
        LibraryPhotoCleanupStore(isolatedContext).cleanupCandidates(emptySet())
        assertFalse(store.exists(leased.ref))
    }

    @Test fun lostDraftAcknowledgementKeepsUiDiskFileAndLeaseStateConsistent(): Unit = runBlocking {
        val source = File(sourceRoot, "lost-photo-ack.jpg")
        writeJpeg(source, 48, 32)
        val draftStore = WritingDraftStore(isolatedContext)
        val persistence = object : WritingDraftPersistence {
            var loseNextAcknowledgement = true
            override suspend fun load(): WritingDraftState = draftStore.load()
            override suspend fun save(expectedRevision: Long, draft: WritingDraft): WritingDraftState {
                val committed = draftStore.save(expectedRevision, draft)
                if (loseNextAcknowledgement) {
                    loseNextAcknowledgement = false
                    error("synthetic acknowledgement loss")
                }
                return committed
            }
            override suspend fun clearIfMatches(expected: WritingDraftState): WritingDraftState =
                draftStore.clearIfMatches(expected)
        }
        val owner = "50000000-0000-0000-0000-000000000001"
        val coordinator = PhotoImportCoordinator(isolatedContext, owner)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val id = "own-00000000-0000-0000-0000-000000000005"
            val session = WritingDraftSession(persistence, scope, coordinator,
                createDraft = { WritingDraft(id, "", "", PaperStyle.WarmWhite, DiaryFont.Clear) })
            // Real coordinator recovery switches to IO. Do not mistake the initial idle state
            // for a completed import or ask the session to import before its draft is readable.
            withTimeout(2_000) { session.state.first { it.loaded } }
            session.importPhoto(Uri.fromFile(source), id)
            withTimeout(2_000) {
                session.state.first { !it.photoImporting && it.draft?.photos?.size == 1 }
            }

            val uiPhoto = session.state.value.draft?.photos?.single()
            val diskPhoto = draftStore.load().draft?.photos?.single()
            assertEquals(diskPhoto, uiPhoto)
            assertTrue(store.exists(requireNotNull(diskPhoto).ref))
            assertFalse(session.state.value.conflict)
            assertEquals(null, session.state.value.error)
            assertEquals(null, session.state.value.photoMessage)
            val activeLeases = LibraryStorageCoordinator.mutex.withLock {
                PhotoImportLeaseFiles.activeRefsLocked(filesRoot)
            }
            assertFalse("A reconciled attachment must release its import lease", diskPhoto.ref in activeLeases)
        } finally {
            scope.cancel()
        }
    }

    @Test fun cameraCleanupDeletesOnlyStaleOwnedNamesAndExcludesThePendingToken() {
        val cache = DiaryCameraCache(isolatedContext)
        val staleToken = "40000000-0000-0000-0000-000000000001"
        val pendingToken = "40000000-0000-0000-0000-000000000002"
        val stale = cache.file(staleToken).apply { writeBytes(byteArrayOf(1)) }
        val pending = cache.file(pendingToken).apply { writeBytes(byteArrayOf(2)) }
        val unrelated = File(stale.parentFile, "keep-user-cache.txt").apply { writeBytes(byteArrayOf(3)) }
        val old = FileTime.fromMillis(1_000L)
        Files.setLastModifiedTime(stale.toPath(), old)
        Files.setLastModifiedTime(pending.toPath(), old)

        cache.cleanupStale(pendingToken, nowMillis = 2L * 24L * 60L * 60L * 1_000L)
        assertFalse(stale.exists())
        assertTrue(pending.exists())
        assertTrue(unrelated.exists())
    }

    private fun writeJpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(70, 100, 160))
        FileOutputStream(file).use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) }
        bitmap.recycle()
    }

    private fun jpegHasMarker(bytes: ByteArray, expectedMarker: Int): Boolean {
        if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) return false
        var offset = 2
        while (offset < bytes.size) {
            if (bytes[offset++] != 0xff.toByte()) return false
            while (offset < bytes.size && bytes[offset] == 0xff.toByte()) offset++
            if (offset >= bytes.size) return false
            val marker = bytes[offset++].toInt() and 0xff
            if (marker == 0xda || marker == 0xd9) return false
            if (marker == 0x01 || marker in 0xd0..0xd8) continue
            if (offset + 2 > bytes.size) return false
            val segmentLength = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
            if (segmentLength < 2 || offset + segmentLength > bytes.size) return false
            if (marker == expectedMarker) return true
            offset += segmentLength
        }
        return false
    }

    private class IsolatedFilesContext(base: Context, private val isolatedFiles: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedFiles
        override fun getCacheDir(): File = requireNotNull(isolatedFiles.parentFile)
    }

    private companion object {
        const val PREFIX = "photo-store-test-"
        const val MARKER = ".owner"
    }
}
