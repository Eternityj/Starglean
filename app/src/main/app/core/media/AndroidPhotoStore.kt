package com.starglean.app.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-private, offline photo storage. Source URIs and filenames are never retained. */
class AndroidPhotoStore(context: Context, storageDirectory: File = context.applicationContext.filesDir) : PhotoStore {
    private val appContext = context.applicationContext
    private val root = File(storageDirectory, DIRECTORY_NAME)

    override suspend fun importSelected(uri: Uri): DiaryPhoto = withContext(Dispatchers.IO) {
        processLock.withLock { importLocked(uri, "photo-${UUID.randomUUID().toString().lowercase()}") }
    }

    internal suspend fun importSelected(uri: Uri, reservedRef: String): DiaryPhoto = withContext(Dispatchers.IO) {
        processLock.withLock {
            DiaryPhotoRules.requireValidRef(reservedRef)
            importLocked(uri, reservedRef)
        }
    }

    /** Imports authenticated cloud media through the same decode/orientation/size/JPEG normalization path. */
    suspend fun importDownloaded(bytes: ByteArray, expectedContentType: String, expectedSha256: String, stableRef: String? = null): DiaryPhoto =
        withContext(Dispatchers.IO) {
            processLock.withLock {
                if (bytes.isEmpty() || bytes.size > DiaryPhotoRules.MAX_SOURCE_BYTES ||
                    expectedContentType !in setOf("image/jpeg", "image/png", "image/webp") ||
                    !expectedSha256.matches(Regex("[a-f0-9]{64}"))) failImport()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                if (digest != expectedSha256) failImport()
                val ref = stableRef ?: "photo-${UUID.randomUUID().toString().lowercase()}"
                DiaryPhotoRules.requireValidRef(ref)
                val existing = ownedFile(ref, requireExisting = false)
                if (existing.isFile) {
                    val dimensions = decodeBounds(existing)
                    return@withLock DiaryPhoto(ref, dimensions.first, dimensions.second, DiaryPhoto.MIME_TYPE, existing.length())
                }
                importLocked(ref) { source ->
                    FileOutputStream(source).use { output -> output.write(bytes); output.fd.sync() }
                }
            }
        }

    override fun open(ref: String): InputStream = FileInputStream(ownedFile(ref, requireExisting = true))

    override fun fileForDisplay(ref: String): File = ownedFile(ref, requireExisting = true)

    override fun exists(ref: String): Boolean = try {
        ownedFile(ref, requireExisting = false).isFile
    } catch (_: PhotoStoreException) {
        false
    }

    override suspend fun deleteIfUnreferenced(ref: String, referencedRefs: Set<String>): Boolean =
        withContext(Dispatchers.IO) {
            processLock.withLock {
                DiaryPhotoRules.requireValidRef(ref)
                referencedRefs.forEach(DiaryPhotoRules::requireValidRef)
                if (ref in referencedRefs) return@withLock false
                val file = ownedFile(ref, requireExisting = false)
                if (!file.exists()) return@withLock false
                if (!file.isFile || !file.delete()) {
                    throw PhotoStoreException("This unreferenced local photo could not be removed safely.")
                }
                true
            }
        }

    private fun importLocked(uri: Uri, ref: String): DiaryPhoto = importLocked(ref) { copySelectedUri(uri, it) }

    private inline fun importLocked(ref: String, populate: (File) -> Unit): DiaryPhoto {
        ensureRoot()
        val source = File(root, ".$ref.source")
        val pending = File(root, ".$ref.pending")
        val destination = ownedFile(ref, requireExisting = false)
        if (source.exists() || pending.exists() || destination.exists()) failImport()
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var scaled: Bitmap? = null
        var committed = false
        try {
            populate(source)
            val bounds = decodeBounds(source)
            val orientation = readOrientation(source)
            val sample = sampleSize(bounds.first, bounds.second)
            decoded = decodeBitmap(source, sample)
            oriented = orient(decoded, orientation)
            val normalized = scaleToLimit(oriented)
            scaled = normalized
            writeJpeg(normalized, pending)
            if (!pending.renameTo(destination)) failImport()
            val width = normalized.width
            val height = normalized.height
            val size = destination.length()
            val photo = DiaryPhoto(ref, width, height, DiaryPhoto.MIME_TYPE, size)
            committed = true
            return photo
        } catch (error: CancellationException) {
            throw error
        } catch (error: PhotoStoreException) {
            throw error
        } catch (_: OutOfMemoryError) {
            failImport()
        } catch (_: Exception) {
            failImport()
        } finally {
            if (scaled !== oriented && scaled !== decoded) scaled?.recycle()
            if (oriented !== decoded) oriented?.recycle()
            decoded?.recycle()
            source.delete()
            pending.delete()
            // A post-rename validation failure must not leave an unreferenced partial import.
            if (destination.exists() && (!committed || destination.length() !in 1..DiaryPhotoRules.MAX_STORED_BYTES)) {
                destination.delete()
            }
        }
    }

    private fun copySelectedUri(uri: Uri, target: File) {
        val input = try { appContext.contentResolver.openInputStream(uri) } catch (_: Exception) { null }
            ?: failImport()
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > DiaryPhotoRules.MAX_SOURCE_BYTES) failImport()
                    output.write(buffer, 0, count)
                }
                if (total == 0L) failImport()
                output.fd.sync()
            }
        }
    }

    private fun decodeBounds(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(file).use { BitmapFactory.decodeStream(BufferedInputStream(it), null, options) }
        val width = options.outWidth
        val height = options.outHeight
        val pixels = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 || pixels !in 1..DiaryPhotoRules.MAX_SOURCE_PIXELS ||
            options.outMimeType?.startsWith("image/") != true) failImport()
        return width to height
    }

    private fun decodeBitmap(file: File, sample: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return FileInputStream(file).use {
            BitmapFactory.decodeStream(BufferedInputStream(it), null, options)
        } ?: failImport()
    }

    private fun readOrientation(file: File): Int = try {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun orient(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun scaleToLimit(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= DiaryPhotoRules.MAX_LONG_EDGE) return source
        val ratio = DiaryPhotoRules.MAX_LONG_EDGE.toDouble() / longest.toDouble()
        val width = maxOf(1, (source.width * ratio).toInt())
        val height = maxOf(1, (source.height * ratio).toInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    /** Drawing into a fresh SDR bitmap drops source metadata and any attached HDR gainmap. */
    private fun writeJpeg(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { output ->
            // Never compress the decoded bitmap directly: recent Android versions can retain
            // HDR gainmap and XMP metadata on it even when its pixels are already opaque.
            // A fresh standard bitmap keeps only the rendered SDR pixels.
            val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(opaque).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                opaque.setHasAlpha(false)
                if (!opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) failImport()
                output.fd.sync()
            } finally {
                opaque.recycle()
            }
        }
        if (file.length() !in 1..DiaryPhotoRules.MAX_STORED_BYTES) failImport()
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > DiaryPhotoRules.MAX_LONG_EDGE && sample < longest) {
            sample *= 2
        }
        return sample
    }

    private fun ensureRoot() {
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) {
            throw PhotoStoreException("Local photo storage is unavailable. Your entry is preserved.")
        }
        val expectedParent = appContext.filesDir.canonicalFile
        if (root.canonicalFile.parentFile != expectedParent) failImport()
    }

    private fun ownedFile(ref: String, requireExisting: Boolean): File {
        DiaryPhotoRules.requireValidRef(ref)
        ensureRoot()
        val file = File(root, "$ref.jpg").canonicalFile
        if (file.parentFile != root.canonicalFile || (requireExisting && !file.isFile)) {
            throw PhotoStoreException("This local photo is unavailable.")
        }
        return file
    }

    private fun failImport(): Nothing =
        throw PhotoStoreException("That image could not be imported safely. Choose a different photo.")

    private companion object {
        const val DIRECTORY_NAME = "diary-photos-v1"
        const val JPEG_QUALITY = 88
        val processLock = Mutex()
    }
}
