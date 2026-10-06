package com.starglean.app.core.online

import android.content.Context
import android.util.AtomicFile
import com.starglean.app.core.io.readAtMostBytes
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CloudEntryLink(val accountId: String, val localDiaryId: String, val cloudEntryId: UUID)

/** Account-qualified identity links only. Diary content is never cached here. */
class CloudEntryLinkStore(context: Context, storageDirectory: File = context.noBackupFilesDir) {
    private val file = AtomicFile(File(storageDirectory, "cloud-library/entry-links-v1.bin"))

    suspend fun linksFor(accountId: String): List<CloudEntryLink> = io {
        requireAccount(accountId); readLocked().filter { it.accountId == accountId }
    }

    suspend fun put(link: CloudEntryLink) = io {
        requireAccount(link.accountId); requireLocalId(link.localDiaryId)
        val previous = readLocked()
        val conflict = previous.any { it.accountId == link.accountId &&
            (it.localDiaryId == link.localDiaryId || it.cloudEntryId == link.cloudEntryId) && it != link }
        if (conflict) throw CloudLibraryDataException("This cloud entry is already linked to a different local memory.")
        if (link !in previous) writeLocked(previous + link)
    }

    suspend fun remove(accountId: String, cloudEntryId: UUID) = io {
        requireAccount(accountId)
        val previous = readLocked()
        val next = previous.filterNot { it.accountId == accountId && it.cloudEntryId == cloudEntryId }
        if (next != previous) writeLocked(next)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun readLocked(): List<CloudEntryLink> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        return try {
            file.openRead().use { input ->
                val bytes = input.readAtMostBytes(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) corrupt()
                DataInputStream(bytes.inputStream()).use { header ->
                    if (header.readInt() != MAGIC || header.readInt() != VERSION) corrupt()
                    val length = header.readInt(); val checksum = header.readLong()
                    if (length != header.available() || length !in 4..MAX_BYTES) corrupt()
                    val payload = ByteArray(length); header.readFully(payload)
                    if (CRC32().apply { update(payload) }.value != checksum) corrupt()
                    DataInputStream(payload.inputStream()).use { data ->
                    val count = data.readInt()
                    if (count !in 0..MAX_LINKS) corrupt()
                    List(count) { CloudEntryLink(data.readUTF(), data.readUTF(), UUID.fromString(data.readUTF())) }
                        .also { links ->
                            if (data.available() != 0 || links.toSet().size != links.size ||
                                links.any { runCatching { requireAccount(it.accountId); requireLocalId(it.localDiaryId) }.isFailure } ||
                                links.groupBy { it.accountId }.values.any { accountLinks ->
                                    accountLinks.map { it.localDiaryId }.toSet().size != accountLinks.size ||
                                        accountLinks.map { it.cloudEntryId }.toSet().size != accountLinks.size
                                }) corrupt()
                        }
                    }
                }
            }
        } catch (error: CloudLibraryDataException) { throw error }
        catch (_: Exception) { corrupt() }
    }

    private fun writeLocked(value: List<CloudEntryLink>) {
        if (value.size > MAX_LINKS) throw CloudLibraryDataException("The cloud entry link limit has been reached.")
        val payload = ByteArrayOutputStream().also { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(value.size)
                value.forEach { link -> out.writeUTF(link.accountId); out.writeUTF(link.localDiaryId); out.writeUTF(link.cloudEntryId.toString()) }
            }
        }.toByteArray()
        val bytes = ByteArrayOutputStream().also { raw -> DataOutputStream(raw).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(payload.size)
            out.writeLong(CRC32().apply { update(payload) }.value); out.write(payload)
        } }.toByteArray()
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
        catch (error: Exception) { runCatching { file.failWrite(stream) }; throw CloudLibraryDataException("Cloud entry links could not be saved.") }
        if (readLocked() != value) throw CloudLibraryDataException("Cloud entry link saving could not be confirmed.")
    }

    private fun requireAccount(value: String) {
        if (runCatching { UUID.fromString(value) }.isFailure) throw CloudLibraryDataException("The signed-in account could not be identified.")
    }
    private fun requireLocalId(value: String) {
        if (!value.matches(Regex("own-[0-9a-f-]{36}"))) throw CloudLibraryDataException("The local entry could not be identified.")
    }
    private fun corrupt(): Nothing = throw CloudLibraryDataException("Cloud entry links could not be read safely; the original file is preserved.")

    private companion object { val mutex = Mutex(); const val MAGIC = 0x53434C4B; const val VERSION = 1; const val MAX_LINKS = 10_000; const val MAX_BYTES = 2 * 1024 * 1024 }
}

class CloudLibraryDataException(message: String) : Exception(message)
