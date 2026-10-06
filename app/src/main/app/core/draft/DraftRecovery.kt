package com.starglean.app.core.draft

import android.content.Context
import android.util.AtomicFile
import com.starglean.app.core.library.LibraryStorageCoordinator
import com.starglean.app.core.media.AndroidPhotoStore
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DraftRecoveryCandidate(val source: String, val state: WritingDraftState)
data class DraftRecoverySnapshot(val fingerprint: String, val candidates: List<DraftRecoveryCandidate>, val foundFiles: Int)

/** Explicit recovery only. Inspection never invokes AtomicFile.openRead or discards pending bytes. */
class DraftRecovery(context: Context) {
    private val directory = context.applicationContext.filesDir
    private val photos = AndroidPhotoStore(context)
    private val base = File(directory, "writing-draft-v1.bin")
    private fun originals() = listOf(base, File(base.path + ".bak"), File(base.path + ".new"))

    suspend fun inspect(): DraftRecoverySnapshot = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock { snapshot(readOriginals()) }
    }

    /** Originals and their photos are copied and verified before any file is replaced. */
    suspend fun recover(expected: DraftRecoverySnapshot, source: String?): WritingDraftState = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            val bytes = readOriginals()
            val actual = snapshot(bytes)
            check(actual.fingerprint == expected.fingerprint) { "Draft files changed. Review the available versions again." }
            val chosen = source?.let { name -> actual.candidates.singleOrNull { it.source == name }
                ?: error("That draft version is no longer available.") }
            val revision = actual.candidates.maxOfOrNull { it.state.revision } ?: 0L
            check(revision < Long.MAX_VALUE) { "The draft version limit was reached. Originals were preserved." }
            val next = WritingDraftState(revision + 1, chosen?.state?.draft?.copy(id = "own-${UUID.randomUUID()}"))
            val archive = File(directory, "draft-recovery/${actual.fingerprint}")
            check(archive.isDirectory || archive.mkdirs()) { "Recovery needs free storage. Nothing was replaced." }
            bytes.forEach { (name, data) -> preserve(File(archive, name), data) }
            actual.candidates.flatMap { it.state.draft?.photos.orEmpty() }.distinctBy { it.ref }.forEach { photo ->
                if (photos.exists(photo.ref)) {
                    val file = photos.fileForDisplay(photo.ref)
                    check(file.length() in 1..com.starglean.app.core.media.DiaryPhotoRules.MAX_STORED_BYTES)
                    preserve(File(archive, "${photo.ref}.jpg"), file.readBytes())
                }
            }
            // A recovered draft must never promise missing attachments.
            check(next.draft?.photos.orEmpty().all { photos.exists(it.ref) }) {
                "A photo is missing from this version. Originals were preserved; choose another version."
            }
            originals().drop(1).forEach { check(!it.exists() || it.delete()) { "Recovery could not prepare the draft. Originals remain archived." } }
            val atomic = AtomicFile(base)
            val stream = atomic.startWrite()
            try {
                stream.write(WritingDraftCodec.encode(next)); stream.fd.sync(); atomic.finishWrite(stream)
            } catch (error: Exception) { atomic.failWrite(stream); throw error }
            check(WritingDraftCodec.decode(base.readBytes()) == next) { "Recovery could not be confirmed. Keep the originals." }
            next
        }
    }

    private fun readOriginals(): Map<String, ByteArray> = originals().filter { it.exists() }.associate { file ->
        check(file.isFile && file.length() in 0..WritingDraftCodec.MAX_FILE_BYTES.toLong()) {
            "A draft file is too large or unreadable. Nothing was changed; contact support."
        }
        file.name to file.readBytes()
    }

    private fun snapshot(bytes: Map<String, ByteArray>): DraftRecoverySnapshot {
        val digest = MessageDigest.getInstance("SHA-256")
        bytes.toSortedMap().forEach { (name, data) ->
            digest.update(name.toByteArray()); digest.update(0.toByte()); digest.update(data)
        }
        return DraftRecoverySnapshot(digest.digest().joinToString("") { "%02x".format(it) },
            bytes.mapNotNull { (name, data) -> runCatching { DraftRecoveryCandidate(name, WritingDraftCodec.decode(data)) }.getOrNull() }, bytes.size)
    }

    private fun preserve(target: File, bytes: ByteArray) {
        if (target.exists()) { check(target.readBytes().contentEquals(bytes)); return }
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try { stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
        check(target.readBytes().contentEquals(bytes)) { "Originals could not be archived. Nothing was replaced." }
    }
}
