package com.starglean.app.core.backup

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import com.starglean.app.core.library.LibraryDeletionFiles
import com.starglean.app.core.library.LibraryStorageCoordinator
import com.starglean.app.core.media.AndroidPhotoStore
import com.starglean.app.core.media.DiaryPhotoRules
import com.starglean.app.core.media.LibraryPhotoCleanupFiles
import com.starglean.app.core.memory.*
import java.io.*
import java.security.MessageDigest
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

class BackupPreview internal constructor(internal val directory: File, val records: List<OwnDiaryRecord>, val photoCount: Int) {
    override fun toString() = "BackupPreview(entries=${records.size}, photos=$photoCount)"
}
data class BackupImportResult(val added: Int, val alreadyPresent: Int, val skippedDeleted: Int)

/** Offline only. Tokens, publishing journals, permission choices and cloud links never enter the format. */
class LocalBackupStore(context: Context) {
    private val app = context.applicationContext
    private val diaries = OwnDiaryStore(app)
    private val photos = AndroidPhotoStore(app)

    suspend fun export(uri: Uri, password: CharArray): Int = withContext(Dispatchers.IO) {
        try {
            BackupCipher.validatePassword(password)
            // Recover pending library transactions before taking one consistent snapshot.
            diaries.load()
            LibraryStorageCoordinator.mutex.withLock {
                val records = diaries.readForLibraryTransaction()
                val manifest = OwnDiaryCodec.encode(records)
                val attachments = records.flatMap { it.diary.photos }.distinctBy { it.ref }
                val total = manifest.size.toLong() + attachments.sumOf { it.sizeBytes }
                require(total + 1024L * 1024 <= BackupCipher.MAX_BYTES) { "The backup exceeds 4 GB. Export was not started." }
                attachments.forEach { check(photos.fileForDisplay(it.ref).length() == it.sizeBytes) }
                val destination = app.contentResolver.openOutputStream(uri, "wt") ?: error("The backup destination cannot be opened.")
                destination.use { raw -> DataOutputStream(BackupCipher.encrypt(raw, password)).use { output ->
                    output.writeInt(manifest.size); output.write(manifest); output.writeInt(attachments.size)
                    attachments.forEach { photo ->
                        currentCoroutineContext().ensureActive()
                        output.writeUTF(photo.ref); output.writeLong(photo.sizeBytes)
                        photos.open(photo.ref).use { copyExactly(it, output, photo.sizeBytes) }
                    }
                } }
                records.size
            }
        } finally { password.fill('\u0000') }
    }

    suspend fun inspect(uri: Uri, password: CharArray): BackupPreview {
        var prepared: BackupPreview? = null
        try { return withContext(Dispatchers.IO) {
        BackupCipher.validatePassword(password)
        val stage = BackupScratch.create(app)
        try {
            val source = app.contentResolver.openInputStream(uri) ?: error("The backup cannot be opened.")
            source.use { raw -> DataInputStream(BackupCipher.decrypt(raw, password)).use { input ->
                val size = input.readInt(); require(size in 20..OwnDiaryCodec.MAX_FILE_BYTES)
                val records = OwnDiaryCodec.decode(ByteArray(size).also { input.readFully(it) })
                val attachments = records.flatMap { it.diary.photos }
                val byRef = attachments.associateBy { it.ref }
                require(attachments.all { byRef[it.ref] == it })
                val count = input.readInt(); require(count == byRef.size && count in 0..OwnDiaryRules.MAX_RECORDS * 2)
                val seen = mutableSetOf<String>(); var total = size.toLong()
                repeat(count) {
                    currentCoroutineContext().ensureActive()
                    val ref = input.readUTF(); DiaryPhotoRules.requireValidRef(ref)
                    require(seen.add(ref))
                    val photo = byRef[ref] ?: error("Unexpected photo in backup.")
                    val length = input.readLong(); require(length == photo.sizeBytes && length in 1..DiaryPhotoRules.MAX_STORED_BYTES)
                    total += length; require(total <= BackupCipher.MAX_BYTES)
                    val file = File(stage, "$ref.jpg")
                    FileOutputStream(file).use { copyExactly(input, it, length, requireEnd = false); it.fd.sync() }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    require(bounds.outMimeType == "image/jpeg" && bounds.outWidth == photo.width && bounds.outHeight == photo.height)
                }
                // Authenticates the final frame before exposing any preview or modifying the library.
                require(input.read() == -1)
                BackupPreview(stage, records, count).also { prepared = it }
            } }
        } catch (error: Exception) { BackupScratch.discard(stage); throw error }
        } } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { prepared?.let { discard(it) } }
            throw error
        } finally { password.fill('\u0000') }
    }

    suspend fun import(preview: BackupPreview): BackupImportResult = withContext(Dispatchers.IO) {
        requireOwned(preview.directory)
        diaries.load()
        LibraryStorageCoordinator.mutex.withLock {
            val existing = diaries.readForLibraryTransaction()
            val old = existing.associateBy { it.diary.id }
            var same = 0; var deleted = 0
            val adding = preview.records.filter { record ->
                when {
                    LibraryDeletionFiles.isDeletedLocked(app.filesDir, record.diary.id) -> { deleted++; false }
                    old.containsKey(record.diary.id) -> {
                        check(old[record.diary.id] == record) { "An entry has the same identity but different content. Nothing was imported." }
                        same++; false
                    }
                    else -> true
                }
            }
            // Capacity, IDs, encoding and all attachments are verified before any existing file changes.
            val combined = existing + adding
            val bytes = OwnDiaryCodec.encode(combined)
            val media = adding.flatMap { it.diary.photos }.distinctBy { it.ref }
            val root = File(app.filesDir, "diary-photos-v1")
            check(root.isDirectory || root.mkdirs())
            media.forEach { photo ->
                val source = File(preview.directory, "${photo.ref}.jpg")
                check(source.isFile && source.length() == photo.sizeBytes)
                val target = File(root, "${photo.ref}.jpg")
                if (target.exists()) check(digest(source).contentEquals(digest(target))) { "A photo identity conflicts. Nothing was imported." }
            }
            val newRefs = media.filter { !File(root, "${it.ref}.jpg").exists() }.mapTo(linkedSetOf()) { it.ref }
            // Reuse the durable cleanup journal: a killed import cannot strand unreferenced photos.
            LibraryPhotoCleanupFiles.addCandidatesLocked(app.filesDir, newRefs)
            media.forEach { photo ->
                currentCoroutineContext().ensureActive()
                val target = File(root, "${photo.ref}.jpg")
                if (!target.exists()) {
                    val atomic = AtomicFile(target); val stream = atomic.startWrite()
                    try { File(preview.directory, "${photo.ref}.jpg").inputStream().use { copyExactly(it, stream, photo.sizeBytes) }
                        stream.fd.sync(); atomic.finishWrite(stream)
                    } catch (error: Exception) { atomic.failWrite(stream); throw error }
                    check(digest(target).contentEquals(digest(File(preview.directory, "${photo.ref}.jpg"))))
                }
            }
            // Photos are durable first. One atomic manifest commit makes all imported records visible together.
            if (adding.isNotEmpty()) diaries.commitBackupMergeLocked(existing, combined, bytes)
            LibraryPhotoCleanupFiles.forgetRetainedCandidatesLocked(app.filesDir, newRefs)
            // The manifest is committed. A cache cleanup failure must not misreport the import itself.
            runCatching { BackupScratch.discard(preview.directory) }
            BackupImportResult(adding.size, same, deleted)
        }
    }

    suspend fun discard(preview: BackupPreview) = withContext(Dispatchers.IO) {
        requireOwned(preview.directory); BackupScratch.discard(preview.directory)
    }

    private fun requireOwned(file: File) {
        require(file.parentFile?.canonicalFile == app.cacheDir.canonicalFile && file.name.startsWith("backup-review-"))
        require(file.canonicalFile == file.absoluteFile)
    }
    private fun digest(file: File): ByteArray = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest()
    }
    private suspend fun copyExactly(input: InputStream, output: OutputStream, length: Long, requireEnd: Boolean = true) {
        val buffer = ByteArray(8192); var remaining = length
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(count > 0) { "The backup or photo is incomplete." }
            output.write(buffer, 0, count); remaining -= count
        }
        if (requireEnd) check(input.read() == -1) { "Photo size changed during backup." }
    }
}

/** Only our exact cache namespace is cleaned; active previews are protected until their owner closes. */
object BackupScratch {
    private val active = mutableSetOf<String>()
    @Synchronized internal fun create(context: Context): File {
        cleanup(context)
        return Files.createTempDirectory(context.applicationContext.cacheDir.canonicalFile.toPath(), "backup-review-")
            .toFile().also { active += it.canonicalPath }
    }
    @Synchronized fun cleanup(context: Context) {
        val root = context.applicationContext.cacheDir.canonicalFile
        root.listFiles().orEmpty().filter { it.name.matches(Regex("backup-review-[0-9]+")) &&
            it.canonicalFile == it.absoluteFile && it.parentFile?.canonicalFile == root && it.canonicalPath !in active }
            .forEach { it.deleteRecursively() }
    }
    @Synchronized internal fun discard(file: File) {
        if (file.deleteRecursively()) active -= file.canonicalPath
        else { active -= file.canonicalPath; error("Temporary backup files will be cleaned on the next visit.") }
    }
}
