package com.starglean.app.core.online

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.starglean.app.core.memory.OwnDiaryCodec
import com.starglean.app.core.draft.WritingDraftCodec
import com.starglean.app.core.io.readAtMostBytes
import com.starglean.app.core.network.*
import java.io.*
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypted, atomic publication fence. Photo bytes remain in the private photo store. */
class AndroidPendingPublicationStore(
    context: Context,
    storageDirectory: File = context.noBackupFilesDir,
    private val keyAlias: String = ALIAS,
) : PendingPublicationStore {
    private val file = AtomicFile(File(storageDirectory, "online/pending-publication-v1.enc"))

    override suspend fun read(): PendingPublication? = withContext(Dispatchers.IO) { mutex.withLock { readLocked() } }

    private fun readLocked(): PendingPublication? {
        if (!file.baseFile.exists()) return null
        return try {
            val packed = file.openRead().use { it.readAtMostBytes(MAX_BYTES + 1) }
            require(packed.size in (NONCE_BYTES + 1)..MAX_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(false) ?: error("Missing publication key"), GCMParameterSpec(128, packed.copyOfRange(0, NONCE_BYTES)))
                updateAAD(AAD)
            }
            decode(cipher.doFinal(packed.copyOfRange(NONCE_BYTES, packed.size)))
        } catch (_: Exception) { throw PublicationStorageException("The pending publication could not be verified. It was preserved.") }
    }

    override suspend fun write(value: PendingPublication) = withContext(Dispatchers.IO) { mutex.withLock {
        val plaintext = encode(value)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(true)) ; updateAAD(AAD) }
        val encrypted = try { cipher.iv + cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        require(encrypted.size <= MAX_BYTES)
        file.baseFile.parentFile?.mkdirs()
        var output = file.startWrite()
        try {
            output.write(encrypted); output.fd.sync(); file.finishWrite(output)
            output = null
            if (readLocked() != value) throw PublicationStorageException("Pending publication verification failed.")
        } catch (error: Exception) {
            output?.let { file.failWrite(it) }
            throw if (error is PublicationStorageException) error else PublicationStorageException("The publication could not be fenced durably.")
        } finally { encrypted.fill(0) }
    } }

    override suspend fun clear(expectedRequestId: UUID) = withContext(Dispatchers.IO) { mutex.withLock {
        val current = readLocked() ?: return@withLock
        if (current.frozen.request.requestId != expectedRequestId) throw PublicationStorageException("Pending publication identity changed.")
        file.delete()
        if (file.baseFile.exists()) throw PublicationStorageException("Pending publication could not be cleared.")
    } }

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()); generateKey()
        }
    }

    private fun encode(value: PendingPublication): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
        val frozen = value.frozen; val request = frozen.request
        out.writeInt(VERSION); out.writeUTF(frozen.accountId.toString()); out.writeUTF(frozen.draftId.toString())
        val draftToken = WritingDraftCodec.encode(frozen.draftToken); out.writeInt(draftToken.size); out.write(draftToken)
        out.writeUTF(request.requestId.toString()); out.writeUTF(request.title); out.writeUTF(request.body)
        out.writeUTF(request.paperStyle); out.writeUTF(request.fontStyle); out.writeUTF(request.weather.kind.name); out.writeUTF(request.weather.daylight.name)
        out.writeBoolean(request.weather.temperatureCelsius != null); request.weather.temperatureCelsius?.let(out::writeInt)
        out.writeBoolean(request.weather.capturedAt != null); request.weather.capturedAt?.let { out.writeUTF(it.toString()) }
        out.writeUTF(request.visibility.name); out.writeDouble(request.location.latitude); out.writeDouble(request.location.longitude)
        out.writeDouble(request.location.accuracyMeters); out.writeUTF(request.location.capturedAt.toString()); out.writeBoolean(request.location.isMock)
        out.writeInt(request.consentVersion); out.writeBoolean(request.shareConfirmed); out.writeInt(request.mediaManifest.size)
        request.mediaManifest.forEach { out.writeUTF(it.id.toString()); out.writeUTF(it.contentType); out.writeInt(it.bytes); out.writeUTF(it.sha256) }
        val record = OwnDiaryCodec.encode(listOf(frozen.localRecord)); out.writeInt(record.size); out.write(record)
        out.writeBoolean(value.entryId != null); value.entryId?.let { out.writeUTF(it.toString()) }
        out.writeInt(value.finalizedMediaIds.size); value.finalizedMediaIds.sortedBy(UUID::toString).forEach { out.writeUTF(it.toString()) }
    } }.toByteArray()

    private fun decode(bytes: ByteArray): PendingPublication = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == VERSION)
        val account = UUID.fromString(input.readUTF()); val draft = UUID.fromString(input.readUTF())
        val draftBytes = input.readInt().also { require(it in 1..OwnDiaryCodec.MAX_FILE_BYTES) }
        val draftPayload = ByteArray(draftBytes).also { input.readFully(it) }
        val draftToken = WritingDraftCodec.decode(draftPayload)
        val requestId = UUID.fromString(input.readUTF()); val title = input.readUTF(); val body = input.readUTF()
        val paper = input.readUTF(); val font = input.readUTF(); val weatherKind = WeatherKind.valueOf(input.readUTF()); val daylight = Daylight.valueOf(input.readUTF())
        val temperature = if (input.readBoolean()) input.readInt() else null; val weatherAt = if (input.readBoolean()) Instant.parse(input.readUTF()) else null
        val visibility = EntryVisibility.valueOf(input.readUTF()); val location = ReaderLocation(input.readDouble(), input.readDouble(), input.readDouble(), Instant.parse(input.readUTF()), input.readBoolean())
        val consent = input.readInt(); val confirmed = input.readBoolean(); val mediaCount = input.readInt().also { require(it in 0..2) }
        val media = (0 until mediaCount).map { MediaManifestItem(UUID.fromString(input.readUTF()), input.readUTF(), input.readInt(), input.readUTF()) }
        val recordSize = input.readInt().also { require(it in 1..OwnDiaryCodec.MAX_FILE_BYTES) }
        val recordPayload = ByteArray(recordSize).also { input.readFully(it) }
        val record = OwnDiaryCodec.decode(recordPayload).single()
        val entry = if (input.readBoolean()) UUID.fromString(input.readUTF()) else null
        val finalizedCount = input.readInt().also { require(it in 0..2) }; val finalized = (0 until finalizedCount).map { UUID.fromString(input.readUTF()) }.toSet()
        require(input.read() == -1)
        val request = PublishEntryRequest(requestId, title, body, paper, font, WeatherSnapshot(weatherKind, daylight, temperature, weatherAt), media,
            visibility, location, consent, confirmed)
        PendingPublication(FrozenPublication(account, draft, draftToken, request, record), entry, finalized)
    }

    private companion object {
        val mutex = Mutex(); const val VERSION = 1; const val MAX_BYTES = OwnDiaryCodec.MAX_FILE_BYTES + 128 * 1024
        const val NONCE_BYTES = 12; const val TRANSFORMATION = "AES/GCM/NoPadding"; const val ALIAS = "starglean.online.publication.v1"
        val AAD = "starglean-online-publication-v1".toByteArray()
    }
}

class PublicationStorageException(message: String) : Exception(message)
