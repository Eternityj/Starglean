package com.starglean.app.core.storage

import android.content.Context
import android.util.AtomicFile
import com.starglean.app.core.io.readAtMostBytes
import com.starglean.app.core.library.LibraryStorageCoordinator
import com.starglean.app.core.online.AndroidPendingPublicationStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface AccountLibraryPreparation {
    data class Ready(val context: AccountStorageContext) : AccountLibraryPreparation
    data object ChoiceRequired : AccountLibraryPreparation
}

/** Keeps the legacy device library intact while exposing only a completed account copy. */
class LegacyLibraryClaimStore(
    context: Context,
    private val pendingKeyAlias: String = "starglean.online.publication.v1",
) {
    private val base = baseStorageContext(context)
    private val decision by lazy { AtomicFile(File(accountRoot(base.noBackupFilesDir), "ownership-v1.bin")) }

    suspend fun prepare(accountId: String): AccountLibraryPreparation = io {
        val id = normalizeAccountId(accountId)
        val state = reconcileDecision(readDecision())
        if (state.claimed == id) {
            if (!isActivated(id)) throw AccountLibraryException("The claimed private library is unavailable; no other account data was opened.")
            return@io AccountLibraryPreparation.Ready(AccountStorageContext.create(base, id))
        }
        if (state.claimed != null && state.claimed != id) return@io readyFresh(id, state)
        if (id in state.fresh) {
            if (!isFreshActivated(id)) fail()
            return@io AccountLibraryPreparation.Ready(AccountStorageContext.create(base, id))
        }
        if (isCompleted(id)) {
            if (state.claimed == null) writeDecision(state.copy(claimed = id))
            return@io AccountLibraryPreparation.Ready(AccountStorageContext.create(base, id))
        }
        if (hasMigrationArtifacts(id)) {
            throw AccountLibraryException("An incomplete account library copy is preserved; the private library remains locked.")
        }
        rejectInterruptedAtomicWrites()
        if (!legacyExists()) return@io readyFresh(id, state)
        AccountLibraryPreparation.ChoiceRequired
    }

    suspend fun startFresh(accountId: String): AccountStorageContext = io {
        val id = normalizeAccountId(accountId)
        val state = reconcileDecision(readDecision())
        if (state.claimed == id) {
            if (!isActivated(id)) fail()
            return@io AccountStorageContext.create(base, id)
        }
        initializeEmpty(id)
        writeDecision(state.copy(fresh = state.fresh + id))
        AccountStorageContext.create(base, id)
    }

    suspend fun claimLegacy(accountId: String): AccountStorageContext = io {
        val id = normalizeAccountId(accountId)
        val state = reconcileDecision(readDecision())
        if (state.claimed != null && state.claimed != id) throw AccountLibraryException("This device library already belongs to another account.")
        if (id in state.fresh && state.claimed == null) throw AccountLibraryException("This account already chose a separate empty library; legacy files were preserved.")
        rejectInterruptedAtomicWrites()
        validatePendingOwner(id)
        validateLegacyLimits()
        copyRoot(base.filesDir, accountFiles(id), FILE_ITEMS, id)
        copyRoot(base.noBackupFilesDir, accountNoBackup(id), NO_BACKUP_ITEMS, id)
        initializeCache(id)
        if (!isCompleted(id)) throw AccountLibraryException("The private library copy could not be verified.")
        writeDecision(state.copy(claimed = id, fresh = state.fresh - id))
        AccountStorageContext.create(base, id)
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        LibraryStorageCoordinator.mutex.withLock {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: AccountLibraryException) { throw error }
            catch (error: Exception) { throw AccountLibraryException("Private library preparation failed; original files were preserved.", error) }
        }
    }

    private fun readyFresh(id: String, state: Decision): AccountLibraryPreparation.Ready {
        initializeEmpty(id)
        if (id !in state.fresh) writeDecision(state.copy(fresh = state.fresh + id))
        return AccountLibraryPreparation.Ready(AccountStorageContext.create(base, id))
    }

    private fun initializeEmpty(id: String) {
        listOf(accountFiles(id), accountNoBackup(id), accountCache(id)).forEach { directory ->
            if (Files.isSymbolicLink(directory.toPath()) || (!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) fail()
            if (File(directory, COMPLETE).exists()) {
                if (!freshActivated(directory, id)) fail()
                return@forEach
            }
            if (directory.listFiles().orEmpty().isNotEmpty()) fail()
            writeCompletion(directory, id, MODE_FRESH)
        }
    }

    private fun initializeCache(id: String) {
        val directory = accountCache(id)
        if (Files.isSymbolicLink(directory.toPath()) || (!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) fail()
        if (File(directory, COMPLETE).exists()) {
            if (!freshActivated(directory, id)) fail()
        } else {
            if (directory.listFiles().orEmpty().isNotEmpty()) fail()
            writeCompletion(directory, id, MODE_FRESH)
        }
    }

    private fun legacyExists(): Boolean = FILE_ITEMS.any { File(base.filesDir, it).exists() || File(base.filesDir, "$it.bak").exists() } ||
        NO_BACKUP_ITEMS.any { File(base.noBackupFilesDir, it).exists() || File(base.noBackupFilesDir, "$it.bak").exists() }

    private fun rejectInterruptedAtomicWrites() {
        (FILE_ITEMS.map { File(base.filesDir, "$it.new") } + NO_BACKUP_ITEMS.map { File(base.noBackupFilesDir, "$it.new") })
            .firstOrNull(File::exists)?.let { throw AccountLibraryException("An unfinished legacy library write must be recovered before claiming it.") }
    }

    private fun copyRoot(source: File, destination: File, items: List<String>, id: String) {
        if (completed(destination, id)) return
        if (destination.exists()) throw AccountLibraryException("An incomplete account library copy is preserved for recovery.")
        val root = destination.parentFile ?: fail()
        if (!root.exists() && !root.mkdirs()) fail()
        val staging = File(root, ".claim-$id.new")
        if (staging.exists()) {
            if (!completed(staging, id)) throw AccountLibraryException("An incomplete library copy is preserved; no account library was opened.")
            if (!staging.renameTo(destination) || !completed(destination, id)) fail()
            return
        }
        if (!staging.mkdirs()) fail()
        File(staging, OWNER).writeVerified(id)
        items.forEach { relative ->
            copyKnown(File(source, relative), File(staging, relative))
            copyKnown(File(source, "$relative.bak"), File(staging, "$relative.bak"))
        }
        writeCompletion(staging, id, MODE_CLAIM)
        if (!staging.renameTo(destination)) throw AccountLibraryException("The completed private library could not be activated.")
        fsyncDirectory(root)
        if (!completed(destination, id)) fail()
    }

    private fun copyKnown(source: File, target: File) {
        if (!source.exists()) return
        if (Files.isSymbolicLink(source.toPath())) throw AccountLibraryException("Linked legacy files cannot be claimed safely.")
        if (source.isDirectory) {
            if (source.name != "diary-photos-v1") throw AccountLibraryException("An unexpected legacy directory was preserved.")
            if (!target.mkdirs() && !target.isDirectory) fail()
            source.listFiles()?.sortedBy(File::getName)?.forEach { photo ->
                if (!photo.isFile || Files.isSymbolicLink(photo.toPath()) || !PHOTO.matches(photo.name)) {
                    throw AccountLibraryException("The legacy photo library contains an unexpected item.")
                }
                copyFile(photo, File(target, photo.name))
            } ?: fail()
        } else if (source.isFile) copyFile(source, target) else fail()
    }

    private fun copyFile(source: File, target: File) {
        if (source.length() !in 0..MAX_SINGLE_FILE) throw AccountLibraryException("A legacy library file is too large to claim safely.")
        target.parentFile?.mkdirs()
        source.inputStream().use { input -> FileOutputStream(target).use { output ->
            val buffer = ByteArray(8192); var total = 0L
            while (true) { val count = input.read(buffer); if (count < 0) break
                total += count; if (total > MAX_SINGLE_FILE) fail(); output.write(buffer, 0, count) }
            output.fd.sync()
        } }
        if (source.length() != target.length() || !digest(source).contentEquals(digest(target))) fail()
    }

    private fun isCompleted(id: String) = completed(accountFiles(id), id) && completed(accountNoBackup(id), id)
    private fun isActivated(id: String) = modeActivated(accountFiles(id), id, MODE_CLAIM) &&
        modeActivated(accountNoBackup(id), id, MODE_CLAIM)
    private fun isFreshActivated(id: String) = freshActivated(accountFiles(id), id) &&
        freshActivated(accountNoBackup(id), id) && freshActivated(accountCache(id), id)
    private fun modeActivated(directory: File, id: String, mode: String): Boolean = try {
        val lines = File(directory, COMPLETE).takeIf { directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
            it.isFile && !Files.isSymbolicLink(it.toPath()) && it.length() <= MAX_MANIFEST_BYTES }
            ?.bufferedReader()?.use { reader -> listOf(reader.readLine(), reader.readLine()) }.orEmpty()
        lines.getOrNull(0) == id && lines.getOrNull(1) == mode
    } catch (_: Exception) { false }
    private fun freshActivated(directory: File, id: String): Boolean = try {
        val lines = File(directory, COMPLETE).takeIf { directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
            it.isFile && !Files.isSymbolicLink(it.toPath()) && it.length() <= MAX_MANIFEST_BYTES }
            ?.readLines().orEmpty()
        lines.getOrNull(0) == id && lines.getOrNull(1) == MODE_FRESH
    } catch (_: Exception) { false }
    private fun completed(directory: File, id: String): Boolean {
        return try {
            if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return false
            val manifest = File(directory, COMPLETE).takeIf {
                it.isFile && !Files.isSymbolicLink(it.toPath()) && it.length() <= MAX_MANIFEST_BYTES
            } ?: return false
            val lines = manifest.readLines()
            if (lines.getOrNull(0) != id || lines.getOrNull(1) != MODE_CLAIM) return false
            val expected = lines.drop(2).associate { line ->
                val fields = line.split('|'); if (fields.size != 3) return false
                fields[0] to (fields[1].toLong() to fields[2])
            }
            val actual = directory.walkTopDown().filter(File::isFile)
                .filterNot { it.name in setOf(OWNER, COMPLETE) }.take(MAX_FILES + 1).toList()
            if (actual.size > MAX_FILES) return false
            if (actual.map { it.relativeTo(directory).invariantSeparatorsPath }.toSet() != expected.keys) return false
            actual.all { file ->
                !Files.isSymbolicLink(file.toPath()) && expected[file.relativeTo(directory).invariantSeparatorsPath] ==
                    (file.length() to digest(file).toHex())
            }
        } catch (_: Exception) { false }
    }
    private fun writeCompletion(directory: File, id: String, mode: String) {
        val files = directory.walkTopDown().filter(File::isFile).filterNot { it.name in setOf(OWNER, COMPLETE) }.toList()
        if (files.size > MAX_FILES || files.sumOf(File::length) > MAX_TOTAL_BYTES) {
            throw AccountLibraryException("The legacy library is too large to claim safely.")
        }
        val entries = files.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.joinToString("\n") { file ->
                "${file.relativeTo(directory).invariantSeparatorsPath}|${file.length()}|${digest(file).toHex()}"
            }
        File(directory, COMPLETE).writeVerified("$id\n$mode" + if (entries.isEmpty()) "" else "\n$entries")
        directory.walkBottomUp().filter(File::isDirectory).forEach(::fsyncDirectory)
    }

    private fun accountFiles(id: String) = accountDirectory(base.filesDir, id)
    private fun accountNoBackup(id: String) = accountDirectory(base.noBackupFilesDir, id)
    private fun accountCache(id: String) = accountDirectory(base.cacheDir, id)
    private fun accountDirectory(parent: File, id: String): File {
        val root = accountRoot(parent)
        val raw = File(root, id).absoluteFile
        if (Files.isSymbolicLink(raw.toPath())) fail()
        return raw.canonicalFile.also { if (it != raw || it.parentFile != root) fail() }
    }
    private fun accountRoot(parent: File): File {
        val canonicalParent = parent.canonicalFile
        val raw = File(canonicalParent, "account-libraries-v1").absoluteFile
        if (Files.isSymbolicLink(raw.toPath())) fail()
        return raw.canonicalFile.also {
            if (it != raw || it.parentFile != canonicalParent) fail()
        }
    }
    private fun hasMigrationArtifacts(id: String): Boolean = listOf(
        accountFiles(id), accountNoBackup(id), accountCache(id),
        File(accountFiles(id).parentFile, ".claim-$id.new"),
        File(accountNoBackup(id).parentFile, ".claim-$id.new"),
    ).any(File::exists)

    private data class Decision(val claimed: String?, val fresh: Set<String>)
    private fun reconcileDecision(state: Decision): Decision {
        if (state.claimed != null) return state
        val filesRoot = File(base.filesDir, "account-libraries-v1")
        val candidateIds = filesRoot.listFiles().orEmpty().asSequence().filter(File::isDirectory)
            .mapNotNull { runCatching { normalizeAccountId(it.name) }.getOrNull() }
            .toSet()
        val recoveredFresh = candidateIds.filter { isFreshActivated(it) }.toSet()
        var reconciled = state.copy(fresh = state.fresh + recoveredFresh)
        if (reconciled != state) writeDecision(reconciled)
        val completedIds = candidateIds.filter { isCompleted(it) && it !in reconciled.fresh }.toSet()
        if (completedIds.isEmpty()) return reconciled
        if (completedIds.size != 1) throw AccountLibraryException("Account library ownership is ambiguous; no library was opened.")
        return reconciled.copy(claimed = completedIds.single()).also(::writeDecision)
    }
    private fun readDecision(): Decision {
        if (File(decision.baseFile.path + ".new").exists()) throw AccountLibraryException("An unfinished account-library decision was preserved.")
        if (!decision.baseFile.exists() && !File(decision.baseFile.path + ".bak").exists()) return Decision(null, emptySet())
        return try { decision.openRead().use { input ->
            val bytes = input.readAtMostBytes(MAX_DECISION_BYTES + 1); if (bytes.size > MAX_DECISION_BYTES) fail()
            DataInputStream(bytes.inputStream()).use { data ->
                if (data.readInt() != MAGIC || data.readInt() != VERSION) fail()
                val claimed = data.readUTF().takeIf(String::isNotEmpty)?.let(::normalizeAccountId)
                val count = data.readInt(); if (count !in 0..MAX_ACCOUNTS) fail()
                val fresh = List(count) { normalizeAccountId(data.readUTF()) }.toSet()
                if (fresh.size != count || data.available() != 0) fail()
                Decision(claimed, fresh)
            }
        } } catch (error: AccountLibraryException) { throw error } catch (_: Exception) { fail() }
    }

    private fun writeDecision(value: Decision) {
        decision.baseFile.parentFile?.mkdirs()
        val bytes = java.io.ByteArrayOutputStream().also { raw -> DataOutputStream(raw).use { data ->
            data.writeInt(MAGIC); data.writeInt(VERSION); data.writeUTF(value.claimed.orEmpty())
            data.writeInt(value.fresh.size); value.fresh.sorted().forEach(data::writeUTF)
        } }.toByteArray()
        val output = decision.startWrite()
        try { output.write(bytes); output.fd.sync(); decision.finishWrite(output); fsyncDirectory(decision.baseFile.parentFile!!) }
        catch (error: Exception) { runCatching { decision.failWrite(output) }; throw error }
    }

    private fun File.writeVerified(value: String) {
        if (value.toByteArray().size > MAX_MANIFEST_BYTES) fail()
        parentFile?.mkdirs(); FileOutputStream(this).use { it.write(value.toByteArray()); it.fd.sync() }
        if (!isFile || length() > MAX_MANIFEST_BYTES || readText() != value) fail()
    }
    private fun File.readTextOrNull() = try { if (isFile && length() <= 256) readText() else null } catch (_: Exception) { null }
    private fun digest(file: File): ByteArray = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
        digest.digest()
    }
    private fun validateLegacyLimits() {
        var count = 0
        var total = 0L
        fun add(file: File) {
            if (!file.exists()) return
            if (Files.isSymbolicLink(file.toPath())) fail()
            val files = if (file.isDirectory) file.listFiles()?.toList() ?: fail() else listOf(file)
            files.forEach { child ->
                if (!child.isFile || Files.isSymbolicLink(child.toPath())) fail()
                if (child.length() !in 0..MAX_SINGLE_FILE) throw AccountLibraryException("A legacy library file is too large to claim safely.")
                count++; total += child.length()
                if (count > MAX_FILES || total > MAX_TOTAL_BYTES) throw AccountLibraryException("The legacy library is too large to claim safely.")
            }
        }
        FILE_ITEMS.forEach { add(File(base.filesDir, it)); add(File(base.filesDir, "$it.bak")) }
        NO_BACKUP_ITEMS.forEach { add(File(base.noBackupFilesDir, it)); add(File(base.noBackupFilesDir, "$it.bak")) }
    }
    private suspend fun validatePendingOwner(id: String) {
        val pending = File(base.noBackupFilesDir, NO_BACKUP_ITEMS.single())
        val backup = File(pending.path + ".bak")
        if (!pending.exists() && !backup.exists()) return
        if (!pending.isFile || backup.exists()) {
            throw AccountLibraryException("A pending cloud save must be recovered with its original account before claiming this library.")
        }
        val publication = try { AndroidPendingPublicationStore(base, base.noBackupFilesDir, pendingKeyAlias).read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
            ?: throw AccountLibraryException("A pending cloud save could not be verified; use its original account before claiming this library.")
        if (publication.frozen.accountId.toString() != id) {
            throw AccountLibraryException("A pending cloud save belongs to another account. Sign in to that account before claiming this library.")
        }
    }
    private fun fsyncDirectory(directory: File) {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) fail()
        val descriptor = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
    }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun fail(): Nothing = throw AccountLibraryException("Private library state is ambiguous; original files were preserved.")

    private companion object {
        const val MAGIC = 0x53414C4D; const val VERSION = 1; const val MAX_ACCOUNTS = 100; const val MAX_DECISION_BYTES = 8192
        const val MAX_SINGLE_FILE = 32L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
        const val MAX_FILES = 10_000
        const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
        const val OWNER = ".claim-owner"; const val COMPLETE = ".account-library-complete"
        const val MODE_FRESH = "FRESH"; const val MODE_CLAIM = "CLAIM"
        val PHOTO = Regex("photo-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
        val FILE_ITEMS = listOf("own-diaries-v1.bin", "star-collection-v1.bin", "writing-draft-v1.bin",
            "deleted-diary-ids-v1.bin", "diary-delete-intent-v1.bin", "photo-import-leases-v1.bin",
            "photo-cleanup-candidates-v1.bin", "diary-photos-v1")
        val NO_BACKUP_ITEMS = listOf("online/pending-publication-v1.enc")
    }
}
