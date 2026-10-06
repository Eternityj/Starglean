package com.starglean.app.core.media

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.starglean.app.core.library.LibraryDeletionFiles
import com.starglean.app.core.library.LibraryStorageCoordinator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class LeasedDiaryPhoto(val photo: DiaryPhoto, val ref: String)

/** Narrow seam used by the process-owned draft transaction and its fault-injection tests. */
internal interface WritingPhotoImports {
    suspend fun recoverPreviousProcess()
    suspend fun importSelected(uri: Uri): LeasedDiaryPhoto
    suspend fun attached(ref: String)
    suspend fun abandon(ref: String): Boolean
    suspend fun cleanupCandidates(refs: Set<String>): Boolean
}

/** Durable bridge between a picker result and the draft revision that will own its private copy. */
internal class PhotoImportCoordinator(
    context: Context,
    private val owner: String = PROCESS_OWNER,
) : WritingPhotoImports {
    private val appContext = context.applicationContext
    private val directory = appContext.filesDir
    private val store = AndroidPhotoStore(appContext)

    override suspend fun recoverPreviousProcess() = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            val recoveredDeletion = LibraryDeletionFiles.recoverLocked(directory)
            val leases = PhotoImportLeaseFiles.readLocked(directory)
            val stale = leases.filterValues { it != owner }.keys
            if (stale.isNotEmpty()) {
                LibraryPhotoCleanupFiles.addCandidatesLocked(directory, stale)
                PhotoImportLeaseFiles.writeLocked(directory, leases - stale)
            }
            val recoveredPhotos = LibraryPhotoCleanupFiles.recoverAfterDeletionLocked(directory)
            if (recoveredDeletion || stale.isNotEmpty() || recoveredPhotos) {
                LibraryStorageCoordinator.publishChangeLocked()
            }
        }
    }

    override suspend fun importSelected(uri: Uri): LeasedDiaryPhoto {
        val ref = "photo-${UUID.randomUUID().toString().lowercase()}"
        withContext(Dispatchers.IO) {
            LibraryStorageCoordinator.mutex.withLock {
                val leases = PhotoImportLeaseFiles.readLocked(directory)
                if (leases.size >= PhotoImportLeaseFiles.MAX_LEASES) {
                    throw PhotoStoreException("Finish the current photo import before choosing another image.")
                }
                PhotoImportLeaseFiles.writeLocked(directory, leases + (ref to owner))
            }
        }
        return try {
            LeasedDiaryPhoto(store.importSelected(uri, ref), ref)
        } catch (error: Exception) {
            withContext(NonCancellable) { abandon(ref) }
            throw error
        }
    }

    /** Call only after the exact draft containing [ref] has been durably confirmed. */
    override suspend fun attached(ref: String) = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            val leases = PhotoImportLeaseFiles.readLocked(directory)
            if (leases[ref] == owner) PhotoImportLeaseFiles.writeLocked(directory, leases - ref)
        }
    }

    /** Converts a failed/stale import into an explicit candidate before releasing its protection. */
    override suspend fun abandon(ref: String): Boolean = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            val leases = PhotoImportLeaseFiles.readLocked(directory)
            if (leases[ref] == owner) {
                LibraryPhotoCleanupFiles.addCandidatesLocked(directory, setOf(ref))
                PhotoImportLeaseFiles.writeLocked(directory, leases - ref)
            }
            LibraryPhotoCleanupFiles.recoverAfterDeletionLocked(directory)
        }
    }

    override suspend fun cleanupCandidates(refs: Set<String>) =
        LibraryPhotoCleanupStore(appContext).cleanupCandidates(refs)

    companion object { private val PROCESS_OWNER = UUID.randomUUID().toString().lowercase() }
}

/** Owns only camera files created for this feature; it never traverses another cache directory. */
internal class DiaryCameraCache(context: Context) {
    private val cacheRoot = context.applicationContext.cacheDir.canonicalFile
    private val directory = File(cacheRoot, DIRECTORY)

    fun file(token: String): File {
        requireToken(token)
        ensureDirectory()
        return File(directory, "$token.jpg")
    }

    fun delete(token: String) {
        val file = file(token).canonicalFile
        if (file.parentFile == directory.canonicalFile) file.delete()
    }

    fun cleanupStale(excludedToken: String?, nowMillis: Long = System.currentTimeMillis()) {
        excludedToken?.let(::requireToken)
        ensureDirectory()
        val cutoff = nowMillis - STALE_MILLIS
        directory.listFiles().orEmpty().forEach { file ->
            val match = TOKEN_FILE.matchEntire(file.name) ?: return@forEach
            if (match.groupValues[1] != excludedToken && file.isFile && file.lastModified() in 1..cutoff) {
                val canonical = file.canonicalFile
                if (canonical.parentFile == directory.canonicalFile) canonical.delete()
            }
        }
    }

    private fun ensureDirectory() {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory ||
            directory.canonicalFile.parentFile != cacheRoot) {
            throw PhotoStoreException("Camera temporary storage is unavailable.")
        }
    }

    private fun requireToken(token: String) {
        if (!TOKEN.matches(token)) throw PhotoStoreException("The camera request is invalid.")
    }

    private companion object {
        const val DIRECTORY = "diary-camera"
        const val STALE_MILLIS = 24L * 60L * 60L * 1_000L
        val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val TOKEN_FILE = Regex("(${TOKEN.pattern})\\.jpg")
    }
}

/** Internal because only the import coordinator may create or release leases. */
internal object PhotoImportLeaseFiles {
    const val MAX_LEASES = 32
    private const val NAME = "photo-import-leases-v1.bin"
    private const val MAGIC = 0x53544C53
    private const val VERSION = 1
    private const val HEADER_BYTES = 20
    private const val OWNER_BYTES = 36
    private const val MAX_BYTES = HEADER_BYTES + 4 + MAX_LEASES * (4 + DiaryPhotoRules.REF_BYTES + 4 + OWNER_BYTES)

    fun activeRefsLocked(directory: File): Set<String> = readLocked(directory).keys

    fun readLocked(directory: File): Map<String, String> {
        val atomic = AtomicFile(File(directory, NAME))
        val bytes = try {
            if (File(atomic.baseFile.path + ".new").exists()) corrupt()
            atomic.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_BYTES) corrupt()
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: FileNotFoundException) {
            if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists() &&
                !File(atomic.baseFile.path + ".new").exists()) return emptyMap()
            corrupt()
        } catch (error: PhotoStoreException) { throw error }
        catch (_: Exception) { corrupt() }
        try {
            if (bytes.size !in HEADER_BYTES..MAX_BYTES) corrupt()
            val envelope = DataInputStream(ByteArrayInputStream(bytes))
            if (envelope.readInt() != MAGIC || envelope.readInt() != VERSION) corrupt()
            val size = envelope.readInt()
            val checksum = envelope.readLong()
            if (size != bytes.size - HEADER_BYTES) corrupt()
            val payload = ByteArray(size).also { envelope.readFully(it) }
            if (CRC32().apply { update(payload) }.value != checksum) corrupt()
            val input = DataInputStream(ByteArrayInputStream(payload))
            val count = input.readInt()
            if (count !in 0..MAX_LEASES) corrupt()
            val leases = linkedMapOf<String, String>()
            repeat(count) {
                val ref = input.ascii(DiaryPhotoRules.REF_BYTES)
                val owner = input.ascii(OWNER_BYTES)
                DiaryPhotoRules.requireValidRef(ref)
                if (!OWNER_PATTERN.matches(owner) || leases.put(ref, owner) != null) corrupt()
            }
            if (input.available() != 0) corrupt()
            return leases
        } catch (error: PhotoStoreException) { throw error }
        catch (_: Exception) { corrupt() }
    }

    fun writeLocked(directory: File, leases: Map<String, String>) {
        if (leases.size > MAX_LEASES) corrupt()
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(leases.size)
                leases.toSortedMap().forEach { (ref, owner) ->
                    DiaryPhotoRules.requireValidRef(ref)
                    if (!OWNER_PATTERN.matches(owner)) corrupt()
                    output.ascii(ref)
                    output.ascii(owner)
                }
            }
        }.toByteArray()
        val encoded = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC); output.writeInt(VERSION); output.writeInt(payload.size)
                output.writeLong(CRC32().apply { update(payload) }.value); output.write(payload)
            }
        }.toByteArray()
        val atomic = AtomicFile(File(directory, NAME))
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite(); stream.write(encoded); atomic.finishWrite(stream); stream = null
            if (readLocked(directory) != leases) corrupt()
        } catch (error: Exception) {
            stream?.let(atomic::failWrite)
            if (error is PhotoStoreException) throw error
            corrupt()
        }
    }

    private fun DataOutputStream.ascii(value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        writeInt(bytes.size); write(bytes)
    }

    private fun DataInputStream.ascii(max: Int): String {
        val size = readInt()
        if (size !in 1..max || size > available()) corrupt()
        return ByteArray(size).also { readFully(it) }.toString(Charsets.US_ASCII)
    }

    private fun corrupt(): Nothing =
        throw PhotoStoreException("Photo import recovery could not be read safely. Existing photos are preserved.")

    private val OWNER_PATTERN =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}
