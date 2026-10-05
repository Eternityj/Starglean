package com.starglean.app.core.media

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPhotoDownloadDeviceTest {
    @Test fun downloadedPhotoIsDigestCheckedNormalizedAndIdempotent(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheRoot = target.cacheDir.canonicalFile
        val testRoot = Files.createTempDirectory(cacheRoot.toPath(), PREFIX).toFile().canonicalFile
        assertEquals(cacheRoot, testRoot.parentFile)
        val marker = UUID.randomUUID().toString()
        File(testRoot, MARKER).writeText(marker)
        val filesRoot = File(testRoot, "files").apply { check(mkdir()) }.canonicalFile
        val store = AndroidPhotoStore(IsolatedFilesContext(target, filesRoot))
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                Bitmap.createBitmap(24, 18, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(android.graphics.Color.BLUE)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    bitmap.recycle()
                }
                output.toByteArray()
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val ref = "photo-${UUID.randomUUID()}"

            val first = store.importDownloaded(bytes, "image/png", digest, ref)
            val retry = store.importDownloaded(bytes, "image/png", digest, ref)

            assertEquals(first, retry)
            assertEquals(DiaryPhoto.MIME_TYPE, first.mimeType)
            assertTrue(store.exists(ref))
            val normalized = store.fileForDisplay(ref).canonicalFile
            assertEquals(File(filesRoot, "diary-photos-v1").canonicalFile, normalized.parentFile)
            val beforeRejectedRetry = normalized.readBytes()
            assertTrue(runCatching { store.importDownloaded(bytes, "image/png", "0".repeat(64), ref) }.isFailure)
            assertTrue(beforeRejectedRetry.contentEquals(normalized.readBytes()))
            assertTrue(store.deleteIfUnreferenced(ref, emptySet()))
        } finally {
            assertEquals(cacheRoot, testRoot.parentFile)
            assertEquals(marker, File(testRoot, MARKER).readText())
            testRoot.walkBottomUp().forEach { file ->
                assertTrue(file == testRoot || file.toPath().startsWith(testRoot.toPath()))
                assertTrue(file.delete())
            }
        }
    }

    private class IsolatedFilesContext(base: Context, private val isolatedFiles: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedFiles
        override fun getCacheDir(): File = requireNotNull(isolatedFiles.parentFile)
    }

    private companion object {
        const val PREFIX = "photo-download-test-"
        const val MARKER = ".owner"
    }
}
