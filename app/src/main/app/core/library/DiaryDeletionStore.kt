package com.starglean.app.core.library

import android.content.Context
import android.util.AtomicFile
import com.starglean.app.core.memory.OwnDiaryCodec
import com.starglean.app.core.memory.OwnDiaryDataException
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.media.LibraryPhotoCleanupFiles
import com.starglean.app.core.media.PhotoCleanupDataException
import com.starglean.app.core.stars.DiaryStarPlacement
import com.starglean.app.core.stars.StarCollectionCodec
import com.starglean.app.core.stars.StarCollectionDataException
import com.starglean.app.core.stars.StarCollectionRules
import com.starglean.app.core.stars.StarCollectionState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.zip.CRC32
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DiaryDeletionResult(
    val diaryId: String,
    val deleted: Boolean,
    val placement: DiaryStarPlacement?,
    val removedFromHome: Boolean,
)

class DiaryDeletionDataException(message: String) : IllegalArgumentException(message)

/**
 * Crash-recoverable deletion of one private entry and its one star placement.
 * Photo cleanup is limited to pre-recorded candidates and verifies all draft/diary references.
 */
class DiaryDeletionStore(context: Context) {
    private val directory = context.applicationContext.filesDir

    suspend fun deleteDiary(diaryId: String): DiaryDeletionResult = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            try {
                val recoveredDeletion = LibraryDeletionFiles.recoverLocked(directory)
                val recoveredPhotos = LibraryPhotoCleanupFiles.recoverAfterDeletionLocked(directory)
                if (recoveredDeletion || recoveredPhotos) LibraryStorageCoordinator.publishChangeLocked()
                LibraryDeletionFiles.deleteLocked(directory, diaryId).also {
                    if (it.deleted) LibraryStorageCoordinator.publishChangeLocked()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: PhotoCleanupDataException) {
                throw DiaryDeletionDataException("Pending local photo cleanup could not be verified. No new entry deletion was started.")
            } catch (error: DiaryDeletionDataException) {
                throw error
            } catch (_: Exception) {
                throw DiaryDeletionDataException("The entry could not be deleted safely. Saved recovery state is preserved and will be checked before another library change.")
            }
        }
    }
}

/** Internal so both stores recover an interrupted transaction before exposing either file. */
internal object LibraryDeletionFiles {
    private const val DIARY_NAME = "own-diaries-v1.bin"
    private const val STAR_NAME = "star-collection-v1.bin"
    private const val TOMBSTONE_NAME = "deleted-diary-ids-v1.bin"
    private const val JOURNAL_NAME = "diary-delete-intent-v1.bin"
    private const val MAGIC_TOMBSTONE = 0x53544754
    private const val MAGIC_JOURNAL = 0x5354474A
    private const val VERSION = 1
    private const val HEADER_BYTES = 20
    private const val ID_BYTES = 40
    internal const val MAX_TOMBSTONES = 10_000
    private val ownId = Regex("own-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    suspend fun recoverLocked(directory: File): Boolean {
        val pending = readJournal(directory) ?: return false
        val photoRefs = readDiaries(directory).firstOrNull { it.diary.id == pending }
            ?.diary?.photos.orEmpty().mapTo(linkedSetOf()) { it.ref }
        LibraryPhotoCleanupFiles.addCandidatesLocked(directory, photoRefs)
        finishLocked(directory, pending)
        return true
    }

    fun requireNotDeletedLocked(directory: File, diaryId: String) {
        if (diaryId in readTombstones(directory)) {
            throw OwnDiaryDataException("This entry was deleted and cannot be restored by an older save retry. Create a new entry instead.")
        }
    }

    fun isDeletedLocked(directory: File, diaryId: String): Boolean = diaryId in readTombstones(directory)

    suspend fun deleteLocked(directory: File, diaryId: String): DiaryDeletionResult {
        if (!ownId.matches(diaryId)) throw DiaryDeletionDataException("This entry could not be identified safely. Reload My entries and try again.")
        val diaries = readDiaries(directory)
        val stars = readStars(directory)
        StarCollectionRules.validateSavedReferences(stars, diaries.map { it.diary.id }.toSet())
        val existing = diaries.firstOrNull { it.diary.id == diaryId }
        val placement = stars.placementForDiary(diaryId)
        if (existing == null) {
            if (placement != null) corrupt()
            return DiaryDeletionResult(diaryId, deleted = false, placement = null, removedFromHome = false)
        }
        val tombstones = readTombstones(directory)
        if (diaryId !in tombstones && tombstones.size >= MAX_TOMBSTONES) {
            throw DiaryDeletionDataException("The deleted-entry safety list is full. No entry was removed; contact support before deleting more entries.")
        }
        val removedFromHome = placement != null && placement.instanceId in stars.homeIds
        LibraryPhotoCleanupFiles.addCandidatesLocked(directory, existing.diary.photos.mapTo(linkedSetOf()) { it.ref })
        writeJournal(directory, diaryId)
        DiaryDeletionFailureInjection.check(DiaryDeletionFailurePoint.AfterIntent)
        finishLocked(directory, diaryId)
        try {
            LibraryPhotoCleanupFiles.recoverAfterDeletionLocked(directory)
        } catch (_: PhotoCleanupDataException) {
            // The candidate log was durably verified before the deletion intent. The entry
            // deletion is therefore successful; every later store entry retries cleanup.
        }
        return DiaryDeletionResult(diaryId, deleted = true, placement = placement, removedFromHome = removedFromHome)
    }

    private suspend fun finishLocked(directory: File, diaryId: String) {
        if (!ownId.matches(diaryId)) corrupt()
        val tombstones = readTombstones(directory)
        if (diaryId !in tombstones) {
            if (tombstones.size >= MAX_TOMBSTONES) {
                throw DiaryDeletionDataException("The deleted-entry safety list is full. No entry was removed; contact support before deleting more entries.")
            }
            writeTombstones(directory, tombstones + diaryId)
        }
        DiaryDeletionFailureInjection.check(DiaryDeletionFailurePoint.AfterTombstone)
        currentCoroutineContext().ensureActive()
        val stars = readStars(directory)
        val diaryIds = readDiaries(directory).map { it.diary.id }.toSet()
        // The target may already be absent during roll-forward; every unrelated reference must remain sound.
        StarCollectionRules.validateSavedReferences(stars, diaryIds + diaryId)
        val nextStars = StarCollectionRules.removeDiary(stars, diaryId).state
        if (nextStars != stars) writeStars(directory, nextStars)
        DiaryDeletionFailureInjection.check(DiaryDeletionFailurePoint.AfterStars)
        currentCoroutineContext().ensureActive()
        val diaries = readDiaries(directory)
        val nextDiaries = diaries.filterNot { it.diary.id == diaryId }
        if (nextDiaries.size != diaries.size) writeDiaries(directory, nextDiaries)
        DiaryDeletionFailureInjection.check(DiaryDeletionFailurePoint.AfterDiary)
        currentCoroutineContext().ensureActive()
        clearJournal(directory)
    }

    internal fun writeIntentForRecoveryTest(directory: File, diaryId: String) = writeJournal(directory, diaryId)

    internal fun fillTombstonesForCapacityTest(directory: File) {
        writeTombstones(directory, (0 until MAX_TOMBSTONES).map { number ->
            "own-ffffffff-ffff-ffff-ffff-${number.toString().padStart(12, '0')}"
        }.toSet())
    }

    private fun readDiaries(directory: File): List<OwnDiaryRecord> = readAtomic(
        File(directory, DIARY_NAME), OwnDiaryCodec.MAX_FILE_BYTES, emptyList(), OwnDiaryCodec::decode,
    )

    private fun readStars(directory: File): StarCollectionState = readAtomic(
        File(directory, STAR_NAME), StarCollectionCodec.MAX_FILE_BYTES, StarCollectionState.initial(), StarCollectionCodec::decode,
    )

    private fun writeDiaries(directory: File, value: List<OwnDiaryRecord>) = writeAtomic(
        File(directory, DIARY_NAME), OwnDiaryCodec.encode(value), value, OwnDiaryCodec::decode,
    )

    private fun writeStars(directory: File, value: StarCollectionState) = writeAtomic(
        File(directory, STAR_NAME), StarCollectionCodec.encode(value), value, StarCollectionCodec::decode,
    )

    private fun readTombstones(directory: File): Set<String> {
        val path = File(directory, TOMBSTONE_NAME)
        if (!path.exists() && !siblingExists(path)) return emptySet()
        return decodeIds(readBytes(path, MAX_TOMBSTONES * (ID_BYTES + 4) + HEADER_BYTES + 4), MAGIC_TOMBSTONE, MAX_TOMBSTONES).toSet()
    }

    private fun writeTombstones(directory: File, ids: Set<String>) {
        val ordered = ids.sorted()
        writeAtomic(File(directory, TOMBSTONE_NAME), encodeIds(ordered, MAGIC_TOMBSTONE), ordered) {
            decodeIds(it, MAGIC_TOMBSTONE, MAX_TOMBSTONES)
        }
    }

    private fun readJournal(directory: File): String? {
        val path = File(directory, JOURNAL_NAME)
        if (!path.exists() && !siblingExists(path)) return null
        // Envelope + payload count + ID length + the fixed-width own UUID.
        return decodeIds(readBytes(path, HEADER_BYTES + 4 + 4 + ID_BYTES), MAGIC_JOURNAL, 1).singleOrNull() ?: corrupt()
    }

    private fun writeJournal(directory: File, diaryId: String) = writeAtomic(
        File(directory, JOURNAL_NAME), encodeIds(listOf(diaryId), MAGIC_JOURNAL), listOf(diaryId),
    ) { decodeIds(it, MAGIC_JOURNAL, 1) }

    private fun clearJournal(directory: File) {
        val path = File(directory, JOURNAL_NAME)
        listOf(path, File(path.path + ".bak"), File(path.path + ".new")).forEach { candidate ->
            if (candidate.exists() && !candidate.delete()) {
                throw DiaryDeletionDataException("Deletion was completed but its recovery marker could not be cleared. It is safe to retry.")
            }
        }
        if (path.exists() || siblingExists(path)) throw DiaryDeletionDataException("Deletion recovery could not be confirmed. It is safe to retry.")
    }

    private fun <T> readAtomic(path: File, max: Int, absent: T, decode: (ByteArray) -> T): T {
        if (!path.exists() && !siblingExists(path)) return absent
        return try { decode(readBytes(path, max)) } catch (_: DiaryDeletionDataException) { throw DiaryDeletionDataException("Local library data could not be read safely. The original files are preserved; no changes were made.") }
        catch (_: Exception) { throw DiaryDeletionDataException("Local library data could not be read safely. The original files are preserved; no changes were made.") }
    }

    private fun readBytes(path: File, max: Int): ByteArray {
        if (File(path.path + ".new").exists()) corrupt()
        try {
            AtomicFile(path).openRead().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > max) corrupt()
                    out.write(buffer, 0, count)
                }
                return out.toByteArray()
            }
        } catch (_: FileNotFoundException) { corrupt() }
    }

    private fun siblingExists(path: File) = File(path.path + ".bak").exists() || File(path.path + ".new").exists()

    private fun <T> writeAtomic(path: File, bytes: ByteArray, expected: T, decode: (ByteArray) -> T) {
        val atomic = AtomicFile(path)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
            stream = null
            if (decode(readBytes(path, bytes.size)) != expected) corrupt()
        } catch (cancelled: CancellationException) {
            stream?.let(atomic::failWrite)
            throw cancelled
        } catch (error: Exception) {
            stream?.let(atomic::failWrite)
            if (error is DiaryDeletionDataException) throw error
            throw DiaryDeletionDataException("The local deletion transaction could not be written safely. Its recovery marker is preserved.")
        }
    }

    private fun encodeIds(ids: List<String>, magic: Int): ByteArray {
        if (ids.size > MAX_TOMBSTONES || ids.toSet().size != ids.size || ids.any { !ownId.matches(it) }) corrupt()
        val payload = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(ids.size)
            ids.forEach { id -> val encoded = id.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded) }
        } }.toByteArray()
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(magic); out.writeInt(VERSION); out.writeInt(payload.size)
            out.writeLong(CRC32().apply { update(payload) }.value); out.write(payload)
        } }.toByteArray()
    }

    private fun decodeIds(bytes: ByteArray, magic: Int, max: Int): List<String> = try {
        if (bytes.size < HEADER_BYTES) corrupt()
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != magic || input.readInt() != VERSION) corrupt()
        val length = input.readInt(); val checksum = input.readLong()
        if (length != bytes.size - HEADER_BYTES) corrupt()
        val payload = ByteArray(length); input.readFully(payload)
        if (CRC32().apply { update(payload) }.value != checksum) corrupt()
        val data = DataInputStream(ByteArrayInputStream(payload)); val count = data.readInt()
        if (count !in 0..max) corrupt()
        val ids = List(count) {
            val size = data.readInt(); if (size !in 1..ID_BYTES || size > data.available()) corrupt()
            ByteArray(size).also(data::readFully).toString(Charsets.UTF_8).also { if (!ownId.matches(it)) corrupt() }
        }
        if (data.available() != 0 || ids.toSet().size != ids.size) corrupt()
        ids
    } catch (error: DiaryDeletionDataException) { throw error } catch (_: Exception) { corrupt() }

    private fun corrupt(): Nothing = throw DiaryDeletionDataException(
        "Local deletion safety data could not be read. The original files are preserved. Do not clear app data.",
    )
}

internal enum class DiaryDeletionFailurePoint { AfterIntent, AfterTombstone, AfterStars, AfterDiary }

/** Deterministic process-local crash boundary used only by isolated storage tests. */
internal object DiaryDeletionFailureInjection {
    var failAt: DiaryDeletionFailurePoint? = null

    fun check(point: DiaryDeletionFailurePoint) {
        if (failAt == point) throw DiaryDeletionDataException("Injected deletion interruption.")
    }
}
