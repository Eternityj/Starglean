package com.starglean.app.core.online

import android.content.Context
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ConstellationCloudPreference(val accountId: String, val enabled: Boolean, val version: Long)

class ConstellationCloudPreferenceStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "cloud-library/constellation-sync-v1.bin"))

    suspend fun get(accountId: String): ConstellationCloudPreference = io {
        requireUuid(accountId)
        readLocked().firstOrNull { it.accountId == accountId } ?: ConstellationCloudPreference(accountId, false, 0)
    }

    suspend fun put(value: ConstellationCloudPreference) = io {
        requireUuid(value.accountId); if (value.version < 0) corrupt()
        val next = readLocked().filterNot { it.accountId == value.accountId } + value
        writeLocked(next)
    }

    private suspend fun <T> io(block: () -> T) = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    private fun readLocked(): List<ConstellationCloudPreference> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        try {
            file.openRead().use { input -> DataInputStream(input).use { data ->
                if (data.readInt() != MAGIC || data.readInt() != 1) corrupt()
                val count = data.readInt(); if (count !in 0..1_000) corrupt()
                return List(count) { ConstellationCloudPreference(data.readUTF(), data.readBoolean(), data.readLong()) }
                    .also { values -> if (data.available() != 0 || values.map { item -> item.accountId }.toSet().size != values.size ||
                        values.any { runCatching { requireUuid(it.accountId); if (it.version < 0) corrupt() }.isFailure }) corrupt() }
            } }
        } catch (error: ConstellationSyncException) { throw error } catch (_: Exception) { corrupt() }
    }
    private fun writeLocked(values: List<ConstellationCloudPreference>) {
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try {
            DataOutputStream(stream).let { out -> out.writeInt(MAGIC); out.writeInt(1); out.writeInt(values.size); values.forEach {
                out.writeUTF(it.accountId); out.writeBoolean(it.enabled); out.writeLong(it.version)
            }; out.flush() }
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (_: Exception) { runCatching { file.failWrite(stream) }; throw ConstellationSyncException("Cloud sync preference could not be saved.") }
        if (readLocked() != values) throw ConstellationSyncException("Cloud sync preference saving could not be confirmed.")
    }
    private fun requireUuid(value: String) { if (runCatching { UUID.fromString(value) }.isFailure) corrupt() }
    private fun corrupt(): Nothing = throw ConstellationSyncException("Cloud sync preferences could not be read safely; local constellations were preserved.")
    private companion object { const val MAGIC = 0x53435359; val mutex = Mutex() }
}
