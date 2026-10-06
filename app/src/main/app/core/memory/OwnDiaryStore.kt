package com.starglean.app.core.memory

import android.content.Context
import android.util.AtomicFile
import com.starglean.app.core.library.DiaryDeletionDataException
import com.starglean.app.core.library.LibraryDeletionFiles
import com.starglean.app.core.library.LibraryStorageCoordinator
import com.starglean.app.core.media.LibraryPhotoCleanupFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-private local records only. No weather coordinates, network calls or sample imports. */
class OwnDiaryStore(context: Context) {
    private val directory = context.applicationContext.filesDir
    private val file = AtomicFile(File(directory, "own-diaries-v1.bin"))

    suspend fun load(): List<OwnDiaryRecord> = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            recoverLocked()
            readLocked().sortedByDescending { it.savedAtMillis }
        }
    }

    suspend fun save(record: OwnDiaryRecord): Unit = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            recoverLocked()
            LibraryDeletionFiles.requireNotDeletedLocked(directory, record.diary.id)
            // Fail closed: a read/decode failure must never be mistaken for an empty collection.
            val previous = readLocked()
            val next = OwnDiaryRules.append(previous, record)
            if (next === previous) return@withLock
            val bytes = try { OwnDiaryCodec.encode(next) } catch (error: OwnDiaryDataException) {
                throw error
            } catch (_: Exception) {
                throw OwnDiaryDataException("This entry could not be saved. Your preview is still here. Review the text and try again.")
            }
            var stream: FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(bytes)
                file.finishWrite(stream)
                stream = null
                // AtomicFile may internally suppress a final rename/fsync error. Verify the
                // entire collection rather than claiming success merely because finish returned.
                if (readLocked() != next) throw OwnDiaryDataException("Saving could not be confirmed. Your preview is still here. Please try again.")
                LibraryStorageCoordinator.publishChangeLocked()
            } catch (error: CancellationException) {
                stream?.let(file::failWrite)
                throw error
            } catch (error: Exception) {
                stream?.let(file::failWrite)
                if (error is OwnDiaryDataException) throw error
                throw OwnDiaryDataException("Could not save on this device. Your entries and preview are preserved. Check available storage and try again.")
            }
        }
    }

    internal fun readForLibraryTransaction(): List<OwnDiaryRecord> = readLocked()

    /** Caller holds the shared library mutex. An import is additive, never an overwrite or resurrection. */
    internal fun commitBackupMergeLocked(previous: List<OwnDiaryRecord>, next: List<OwnDiaryRecord>, bytes: ByteArray) {
        check(LibraryStorageCoordinator.mutex.isLocked)
        check(readLocked() == previous && next.take(previous.size) == previous && OwnDiaryCodec.decode(bytes) == next)
        next.drop(previous.size).forEach { LibraryDeletionFiles.requireNotDeletedLocked(directory, it.diary.id) }
        val stream = file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        check(readLocked() == next) { "Backup import could not be confirmed. Retry the same backup." }
        LibraryStorageCoordinator.publishChangeLocked()
    }

    private suspend fun recoverLocked() {
        try {
            val recoveredDeletion = LibraryDeletionFiles.recoverLocked(directory)
            val recoveredPhotos = LibraryPhotoCleanupFiles.recoverAfterDeletionLocked(directory)
            if (recoveredDeletion || recoveredPhotos) LibraryStorageCoordinator.publishChangeLocked()
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: DiaryDeletionDataException) { throw OwnDiaryDataException(requireNotNull(error.message)) }
        catch (_: Exception) { throw OwnDiaryDataException("Local deletion recovery could not be completed safely. The original files are preserved.") }
    }

    private fun readLocked(): List<OwnDiaryRecord> {
        try {
            file.openRead().use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > OwnDiaryCodec.MAX_FILE_BYTES) {
                        throw OwnDiaryDataException("The diary file exceeds the safe read limit. The original file is preserved.")
                    }
                    bytes.write(buffer, 0, count)
                }
                return OwnDiaryCodec.decode(bytes.toByteArray())
            }
        } catch (_: FileNotFoundException) {
            // Absence on first use is the only case that legitimately means an empty library.
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
                !File(file.baseFile.path + ".new").exists()) return emptyList()
            throw OwnDiaryDataException("Local entries could not be read. The original file is preserved; new entries cannot be added yet.")
        } catch (error: OwnDiaryDataException) {
            throw error
        } catch (_: Exception) {
            throw OwnDiaryDataException("Local entries could not be read. The original file is preserved; new entries cannot be added yet.")
        }
    }

}
