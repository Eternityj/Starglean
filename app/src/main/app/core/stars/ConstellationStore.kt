package com.starglean.app.core.stars

import android.content.Context
import android.util.AtomicFile
import com.starglean.app.core.library.DiaryDeletionDataException
import com.starglean.app.core.library.LibraryDeletionFiles
import com.starglean.app.core.library.LibraryStorageCoordinator
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.media.LibraryPhotoCleanupFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Private single-process storage. Every operation reloads under the shared lock, so stale UI
 * snapshots cannot overwrite newer placements. Entry saving remains a separate first step:
 * failure here leaves OwnDiaryStore untouched, and retrying placement is idempotent.
 */
class ConstellationStore(context: Context) {
    private val directory = context.applicationContext.filesDir
    private val file = AtomicFile(File(directory, "star-collection-v1.bin"))
    private val diaries = OwnDiaryStore(context.applicationContext)

    suspend fun load(): StarCollectionState = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock { recoverLocked(); readVerifiedLocked() }
    }

    suspend fun setTarget(instanceId: String?): StarCollectionState = update { StarCollectionRules.setTarget(it, instanceId) }

    suspend fun createInstance(templateId: String): StarCollectionState = update { StarCollectionRules.createInstance(it, templateId) }

    suspend fun setHome(instanceIds: Set<String>): StarCollectionState {
        val selection = instanceIds.toSet()
        return update { StarCollectionRules.setHome(it, selection) }
    }

    /** Explicit cloud restore only. Validates every diary reference before replacing local topology. */
    suspend fun replaceFromCloud(state: StarCollectionState): StarCollectionState = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            recoverLocked()
            StarCollectionRules.validateSavedReferences(state, savedDiaryIds())
            currentCoroutineContext().ensureActive()
            if (readVerifiedLocked() != state) {
                writeLocked(state)
                LibraryStorageCoordinator.publishChangeLocked()
            }
            state
        }
    }

    suspend fun placeDiary(diaryId: String, instanceId: String? = null): StarPlacementResult = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            recoverLocked()
            if (LibraryDeletionFiles.isDeletedLocked(directory, diaryId)) {
                throw StarCollectionDataException("A deleted entry cannot be placed in a constellation. Create a new entry instead.")
            }
            val current = readLocked()
            val savedIds = savedDiaryIds()
            val result = StarCollectionRules.placeDiary(current, diaryId, savedIds, instanceId)
            currentCoroutineContext().ensureActive()
            if (result.state != current) {
                writeLocked(result.state)
                LibraryStorageCoordinator.publishChangeLocked()
            }
            result
        }
    }

    private suspend fun update(transform: (StarCollectionState) -> StarCollectionState): StarCollectionState = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            recoverLocked()
            val current = readVerifiedLocked()
            val next = transform(current)
            currentCoroutineContext().ensureActive()
            if (next != current) {
                writeLocked(next)
                LibraryStorageCoordinator.publishChangeLocked()
            }
            next
        }
    }

    private suspend fun readVerifiedLocked(): StarCollectionState = readLocked().also { state ->
        if (state.placements.isNotEmpty()) StarCollectionRules.validateSavedReferences(state, savedDiaryIds())
    }

    private suspend fun savedDiaryIds(): Set<String> = try {
        diaries.readForLibraryTransaction().map { it.diary.id }.toSet()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        throw StarCollectionDataException("Saved entries could not be read safely. Your entries and constellations are preserved; no changes were made.")
    }

    internal fun readForLibraryTransaction(): StarCollectionState = readLocked()

    private suspend fun recoverLocked() {
        try {
            val recoveredDeletion = LibraryDeletionFiles.recoverLocked(directory)
            val recoveredPhotos = LibraryPhotoCleanupFiles.recoverAfterDeletionLocked(directory)
            if (recoveredDeletion || recoveredPhotos) LibraryStorageCoordinator.publishChangeLocked()
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: DiaryDeletionDataException) { throw StarCollectionDataException(requireNotNull(error.message)) }
        catch (_: Exception) { throw StarCollectionDataException("Local deletion recovery could not be completed safely. The original files are preserved.") }
    }

    private fun writeLocked(state: StarCollectionState) {
        val bytes = StarCollectionCodec.encode(state)
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
            stream = null
            if (readLocked() != state) throw StarCollectionDataException("Constellation saving could not be confirmed. Your entry remains saved; retry its placement.")
        } catch (cancelled: CancellationException) {
            stream?.let(file::failWrite)
            throw cancelled
        } catch (error: Exception) {
            stream?.let(file::failWrite)
            if (error is StarCollectionDataException) throw error
            throw StarCollectionDataException("Could not update the constellation. Your entry remains saved in My entries; retry its placement.")
        }
    }

    private fun readLocked(): StarCollectionState {
        try {
            // AtomicFile would otherwise discard an ambiguous pending file during openRead.
            if (File(file.baseFile.path + ".new").exists()) {
                throw StarCollectionDataException("An unfinished constellation file was found. It is preserved and cannot be overwritten yet.")
            }
            file.openRead().use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > StarCollectionCodec.MAX_FILE_BYTES) {
                        throw StarCollectionDataException("The constellation file exceeds the safe size limit. The original is preserved.")
                    }
                    bytes.write(buffer, 0, count)
                }
                return StarCollectionCodec.decode(bytes.toByteArray())
            }
        } catch (_: FileNotFoundException) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
                !File(file.baseFile.path + ".new").exists()) return StarCollectionState.initial()
            throw StarCollectionDataException("The local constellation file could not be read. The original is preserved and cannot be overwritten.")
        } catch (error: StarCollectionDataException) { throw error }
        catch (_: Exception) {
            throw StarCollectionDataException("The local constellation file could not be read. The original is preserved and cannot be overwritten.")
        }
    }

}
