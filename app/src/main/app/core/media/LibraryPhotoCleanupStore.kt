package com.starglean.app.core.media

import android.content.Context
import android.util.AtomicFile
import com.starglean.app.core.draft.WritingDraftCodec
import com.starglean.app.core.library.DiaryDeletionDataException
import com.starglean.app.core.library.LibraryDeletionFiles
import com.starglean.app.core.library.LibraryStorageCoordinator
import com.starglean.app.core.memory.OwnDiaryCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PhotoCleanupDataException(message: String) : IllegalArgumentException(message)

/**
 * Schedules cleanup only for explicit attachment-removal candidates. The caller must first flush
 * the new draft/diary state. A candidate still referenced anywhere is retained for a later retry.
 */
class LibraryPhotoCleanupStore(context: Context) {
    private val directory = context.applicationContext.filesDir

    suspend fun cleanupCandidates(candidateRefs: Set<String>): Boolean = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            try {
                val recoveredDeletion = LibraryDeletionFiles.recoverLocked(directory)
                LibraryPhotoCleanupFiles.addCandidatesLocked(directory, candidateRefs)
                val recoveredPhotos = LibraryPhotoCleanupFiles.recoverAfterDeletionLocked(directory)
                if (recoveredDeletion || recoveredPhotos) LibraryStorageCoordinator.publishChangeLocked()
                recoveredPhotos
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PhotoCleanupDataException) {
                throw error
            } catch (error: DiaryDeletionDataException) {
                throw PhotoCleanupDataException(requireNotNull(error.message))
            } catch (_: Exception) {
                throw PhotoCleanupDataException("Unused local photos could not be removed safely. Existing photos are preserved.")
            }
        }
    }
}

/** Caller must hold [LibraryStorageCoordinator.mutex]. Never scans the photo directory. */
internal object LibraryPhotoCleanupFiles {
    private const val PENDING_NAME = "photo-cleanup-candidates-v1.bin"
    private const val DRAFT_NAME = "writing-draft-v1.bin"
    private const val DIARY_NAME = "own-diaries-v1.bin"
    private const val PHOTO_DIRECTORY = "diary-photos-v1"
    private const val MAGIC = 0x53545043
    private const val VERSION = 1
    private const val HEADER_BYTES = 20
    private const val MAX_PENDING = 4_096
    private const val MAX_FILE_BYTES = HEADER_BYTES + 4 + MAX_PENDING * (4 + DiaryPhotoRules.REF_BYTES)

    /** Must be called before writing a diary-deletion intent, so a crash cannot orphan its photos. */
    fun addCandidatesLocked(directory: File, refs: Set<String>) {
        refs.forEach(DiaryPhotoRules::requireValidRef)
        if (refs.isEmpty()) return
        val next = readPending(directory) + refs
        if (next.size > MAX_PENDING) {
            throw PhotoCleanupDataException("Too many photo deletions are waiting. No additional photo was removed.")
        }
        writePending(directory, next)
    }

    /**
     * Run only after diary deletion recovery. Reads both persisted reference sources directly,
     * fail-closes on ambiguity/corruption, and removes only IDs already present in the pending log.
     */
    fun recoverAfterDeletionLocked(directory: File): Boolean {
        val pending = readPending(directory)
        if (pending.isEmpty()) return false
        val referenced = readDraftRefs(directory) + readDiaryRefs(directory) +
            PhotoImportLeaseFiles.activeRefsLocked(directory)
        val removable = pending - referenced
        removable.forEach { deleteOwnedPhoto(directory, it) }
        writePending(directory, pending intersect referenced)
        return removable.isNotEmpty()
    }

    /** A completed backup import no longer needs orphan cleanup for its now-durable references. */
    fun forgetRetainedCandidatesLocked(directory: File, refs: Set<String>) {
        if (refs.isEmpty()) return
        val retained = readDiaryRefs(directory)
        require(refs.all { it in retained })
        writePending(directory, readPending(directory) - refs)
    }

    private fun readDraftRefs(directory: File): Set<String> {
        val bytes = readAtomic(directory, DRAFT_NAME, WritingDraftCodec.MAX_FILE_BYTES) ?: return emptySet()
        return try { WritingDraftCodec.decode(bytes).draft?.photos.orEmpty().mapTo(linkedSetOf()) { it.ref } }
        catch (_: Exception) { corrupt() }
    }

    private fun readDiaryRefs(directory: File): Set<String> {
        val bytes = readAtomic(directory, DIARY_NAME, OwnDiaryCodec.MAX_FILE_BYTES) ?: return emptySet()
        return try { OwnDiaryCodec.decode(bytes).flatMapTo(linkedSetOf()) { record -> record.diary.photos.map { it.ref } } }
        catch (_: Exception) { corrupt() }
    }

    private fun readAtomic(directory: File, name: String, maxBytes: Int): ByteArray? {
        val base = File(directory, name)
        if (File(base.path + ".new").exists()) corrupt()
        val atomic = AtomicFile(base)
        return try {
            atomic.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) corrupt()
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: FileNotFoundException) {
            if (!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()) null
            else corrupt()
        } catch (error: PhotoCleanupDataException) {
            throw error
        } catch (_: Exception) {
            corrupt()
        }
    }

    private fun readPending(directory: File): Set<String> {
        val bytes = readAtomic(directory, PENDING_NAME, MAX_FILE_BYTES) ?: return emptySet()
        try {
            if (bytes.size !in HEADER_BYTES..MAX_FILE_BYTES) corrupt()
            val envelope = DataInputStream(ByteArrayInputStream(bytes))
            if (envelope.readInt() != MAGIC || envelope.readInt() != VERSION) corrupt()
            val length = envelope.readInt()
            val checksum = envelope.readLong()
            if (length != bytes.size - HEADER_BYTES) corrupt()
            val payload = ByteArray(length).also { envelope.readFully(it) }
            if (CRC32().apply { update(payload) }.value != checksum) corrupt()
            val input = DataInputStream(ByteArrayInputStream(payload))
            val count = input.readInt()
            if (count !in 0..MAX_PENDING) corrupt()
            val result = LinkedHashSet<String>(count)
            repeat(count) {
                val size = input.readInt()
                if (size !in 1..DiaryPhotoRules.REF_BYTES || size > input.available()) corrupt()
                val encoded = ByteArray(size).also { input.readFully(it) }
                val ref = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded)).toString()
                DiaryPhotoRules.requireValidRef(ref)
                if (!result.add(ref)) corrupt()
            }
            if (input.available() != 0) corrupt()
            return result
        } catch (error: PhotoCleanupDataException) {
            throw error
        } catch (_: Exception) {
            corrupt()
        }
    }

    private fun writePending(directory: File, refs: Set<String>) {
        if (refs.size > MAX_PENDING) corrupt()
        refs.forEach(DiaryPhotoRules::requireValidRef)
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(refs.size)
                refs.sorted().forEach { ref ->
                    val encoded = ref.toByteArray(Charsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
            }
        }.toByteArray()
        val encoded = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(payload.size)
                output.writeLong(CRC32().apply { update(payload) }.value)
                output.write(payload)
            }
        }.toByteArray()
        val atomic = AtomicFile(File(directory, PENDING_NAME))
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(encoded)
            atomic.finishWrite(stream)
            stream = null
            if (readPending(directory) != refs) corrupt()
        } catch (error: Exception) {
            stream?.let(atomic::failWrite)
            if (error is PhotoCleanupDataException) throw error
            throw PhotoCleanupDataException("Photo cleanup could not be recorded safely. No additional photo was removed.")
        }
    }

    private fun deleteOwnedPhoto(directory: File, ref: String) {
        DiaryPhotoRules.requireValidRef(ref)
        val ownedDirectory = directory.canonicalFile
        val root = File(directory, PHOTO_DIRECTORY).canonicalFile
        if (root.parentFile != ownedDirectory) corrupt()
        // Exact names derived from the durable ref cover both the final JPEG and a hard-killed
        // import's private intermediates without scanning the directory.
        listOf("$ref.jpg", "$ref.jpg.new", "$ref.jpg.bak", ".$ref.source", ".$ref.pending").forEach { name ->
            val file = File(root, name).canonicalFile
            if (file.parentFile != root) corrupt()
            if (file.exists() && (!file.isFile || !file.delete())) {
                throw PhotoCleanupDataException("An unused local photo could not be removed safely. It will be retried later.")
            }
        }
    }

    private fun corrupt(): Nothing = throw PhotoCleanupDataException(
        "Photo cleanup state could not be read safely. Existing photos are preserved.",
    )
}
