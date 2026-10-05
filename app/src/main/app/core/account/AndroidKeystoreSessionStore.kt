package com.starglean.app.core.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.starglean.app.core.io.readAtMostBytes
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class AndroidKeystoreSessionStore(
    context: Context,
    private val alias: String = "starglean.auth.session.v1",
    storageDirectory: File = context.noBackupFilesDir,
) : SessionStore {
    private val file = AtomicFile(File(storageDirectory, "auth/session-v1.enc"))
    override suspend fun read(): AuthSession? = withContext(Dispatchers.IO) { STORE_MUTEX.withLock {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
        try {
            val packed = file.openRead().use { input ->
                val value = input.readAtMostBytes(MAX_FILE_BYTES + 1)
                require(value.size <= MAX_FILE_BYTES) { "Session blob is too large" }
                value
            }
            require(packed.size > NONCE_BYTES)
            val nonce = packed.copyOfRange(0, NONCE_BYTES)
            val ciphertext = packed.copyOfRange(NONCE_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, existingKey() ?: throw SessionStorageException("The device encryption key is missing"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD)
            }
            SessionJsonCodec.decode(cipher.doFinal(ciphertext))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Preserve the blob for recovery/diagnosis; never silently destroy user credentials.
            throw SessionStorageException("The saved sign-in could not be unlocked", error)
        }
    } }

    override suspend fun write(session: AuthSession) = withContext(Dispatchers.IO) { STORE_MUTEX.withLock {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            updateAAD(AAD)
        }
        val plaintext = SessionJsonCodec.encode(session)
        val encrypted = try { cipher.iv + cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        var finished = false
        try {
            output.write(encrypted)
            output.fd.sync()
            file.finishWrite(output)
            finished = true
            val persisted = file.openRead().use { it.readAtMostBytes(MAX_FILE_BYTES + 1) }
            if (!persisted.contentEquals(encrypted)) throw SessionStorageException("The saved sign-in could not be verified")
        } catch (cancelled: CancellationException) {
            if (!finished) runCatching { file.failWrite(output) }
            throw cancelled
        } catch (error: Exception) {
            if (!finished) runCatching { file.failWrite(output) }
            throw SessionStorageException("The sign-in could not be saved", error)
        } finally {
            encrypted.fill(0)
        }
    } }

    override suspend fun clear() = withContext(Dispatchers.IO) { STORE_MUTEX.withLock {
        file.delete()
        if (file.baseFile.exists()) throw SessionStorageException("The saved sign-in could not be removed")
    } }

    private fun existingKey(): SecretKey? = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        getKey(alias, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val MAX_FILE_BYTES = 256 * 1024
        val AAD = "com.starglean.app:auth-session:v1".toByteArray()
        val STORE_MUTEX = Mutex()
    }
}

class SessionStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)
