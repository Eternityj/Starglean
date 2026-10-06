package com.starglean.app.core.draft

import android.content.Context
import android.util.AtomicFile
import com.starglean.app.core.library.DiaryDeletionDataException
import com.starglean.app.core.library.LibraryDeletionFiles
import com.starglean.app.core.library.LibraryStorageCoordinator
import com.starglean.app.core.media.LibraryPhotoCleanupFiles
import com.starglean.app.core.media.PhotoCleanupDataException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Private single-draft store. All instances share one in-process lock; no cross-process writers. */
class WritingDraftStore(context: Context) {
    private val directory = context.applicationContext.filesDir
    private val file = AtomicFile(File(directory, "writing-draft-v1.bin"))

    suspend fun load(): WritingDraftState = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock { recoverLocked(); readLocked() }
    }

    suspend fun save(expectedRevision: Long, draft: WritingDraft): WritingDraftState = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            recoverLocked()
            val current = readLocked()
            val next = WritingDraftRules.save(current, expectedRevision, draft)
            if (next != current) writeLocked(next)
            next
        }
    }

    /** A successful diary submission may only clear the exact persisted draft snapshot it used. */
    suspend fun clearIfMatches(expected: WritingDraftState): WritingDraftState = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            recoverLocked()
            val current = readLocked()
            val next = WritingDraftRules.clearIfMatches(current, expected)
            if (next != current) writeLocked(next)
            next
        }
    }

    private fun writeLocked(state: WritingDraftState) {
        val bytes = WritingDraftCodec.encode(state)
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
            stream = null
            if (readLocked() != state) throw WritingDraftDataException("Draft saving could not be confirmed. Keep a copy of your text and try again.")
        } catch (cancelled: CancellationException) {
            stream?.let(file::failWrite)
            throw cancelled
        } catch (error: Exception) {
            stream?.let(file::failWrite)
            if (error is WritingDraftDataException) throw error
            throw WritingDraftDataException("Could not save the draft. Your previous draft was not deliberately cleared. Keep a copy of your text and try again.")
        }
    }

    private fun readLocked(): WritingDraftState {
        try {
            // Do not let AtomicFile.openRead silently delete an orphaned pending file.
            // Even with an existing base, preserve the ambiguous files for recovery.
            if (File(file.baseFile.path + ".new").exists()) {
                throw WritingDraftDataException("An unfinished draft file was found. It is preserved and cannot be overwritten yet.")
            }
            file.openRead().use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > WritingDraftCodec.MAX_FILE_BYTES) {
                        throw WritingDraftDataException("The local draft exceeds the safe size limit. The original is preserved.")
                    }
                    bytes.write(buffer, 0, count)
                }
                return WritingDraftCodec.decode(bytes.toByteArray())
            }
        } catch (_: FileNotFoundException) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
                !File(file.baseFile.path + ".new").exists()) return WritingDraftState(0L, null)
            throw WritingDraftDataException("The local draft could not be read. The original is preserved and cannot be overwritten.")
        } catch (error: WritingDraftDataException) {
            throw error
        } catch (_: Exception) {
            throw WritingDraftDataException("The local draft could not be read. The original is preserved and cannot be overwritten.")
        }
    }

    private suspend fun recoverLocked() {
        try {
            val recoveredDeletion = LibraryDeletionFiles.recoverLocked(directory)
            val recoveredPhotos = LibraryPhotoCleanupFiles.recoverAfterDeletionLocked(directory)
            if (recoveredDeletion || recoveredPhotos) LibraryStorageCoordinator.publishChangeLocked()
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: DiaryDeletionDataException) { throw WritingDraftDataException(requireNotNull(error.message)) }
        catch (error: PhotoCleanupDataException) { throw WritingDraftDataException(requireNotNull(error.message)) }
        catch (_: Exception) {
            throw WritingDraftDataException("Local deletion recovery could not be completed safely. The draft is preserved.")
        }
    }
}
