package com.starglean.app.core.backup

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, bounded-memory authenticated stream; the authenticated empty final frame is mandatory. */
object BackupCipher {
    private val magic = "STGLBK01".toByteArray(Charsets.US_ASCII)
    private const val CHUNK = 65_536
    private const val ROUNDS = 600_000
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024
    fun validatePassword(password: CharArray) {
        require(password.size in 12..256) { "Use a backup password of 12–256 characters." }
    }

    private fun key(password: CharArray, header: ByteArray): SecretKeySpec {
        validatePassword(password)
        val spec = PBEKeySpec(password, header.copyOfRange(8, 24), ROUNDS, 256)
        val encoded = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        return try { SecretKeySpec(encoded, "AES") } finally { encoded.fill(0) }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, header: ByteArray, index: Int): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            val nonce = ByteBuffer.allocate(12).put(header, 24, 8).putInt(index).array()
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD(header); updateAAD(ByteBuffer.allocate(4).putInt(index).array())
        }

    fun encrypt(output: OutputStream, password: CharArray): OutputStream {
        val header = ByteArray(32).also { SecureRandom().nextBytes(it); magic.copyInto(it) }
        val key = key(password, header)
        val data = DataOutputStream(output)
        data.write(header)
        return object : OutputStream() {
            val buffer = ByteArray(CHUNK)
            var used = 0; var index = 0; var total = 0L; var closed = false
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                check(!closed); require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                require(total + length <= MAX_BYTES) { "The backup exceeds the 4 GB safety limit." }
                total += length
                var position = offset; var remaining = length
                while (remaining > 0) {
                    val count = minOf(remaining, CHUNK - used)
                    bytes.copyInto(buffer, used, position, position + count)
                    used += count; position += count; remaining -= count
                    if (used == CHUNK) frame()
                }
            }
            private fun frame() {
                val sealed = cipher(Cipher.ENCRYPT_MODE, key, header, index++).doFinal(buffer, 0, used)
                data.writeInt(sealed.size); data.write(sealed); buffer.fill(0); used = 0
            }
            override fun flush() { check(!closed); if (used > 0) frame(); data.flush() }
            override fun close() {
                if (closed) return
                try { flush(); frame(); data.flush() } finally { closed = true; buffer.fill(0); data.close() }
            }
        }
    }

    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val header = ByteArray(32).also { data.readFully(it) }
        require(header.copyOfRange(0, 8).contentEquals(magic)) { "This is not a supported Starglean backup." }
        val key = key(password, header)
        return object : InputStream() {
            var buffer = ByteArray(0); var position = 0; var index = 0; var total = 0L; var ended = false
            private fun availableFrame(): Boolean {
                if (position < buffer.size) return true
                if (ended) return false
                buffer.fill(0)
                val size = data.readInt() // EOF before a verified final frame is always an error.
                require(size in 16..CHUNK + 16) { "Invalid backup frame." }
                val sealed = ByteArray(size).also { data.readFully(it) }
                buffer = cipher(Cipher.DECRYPT_MODE, key, header, index++).doFinal(sealed)
                position = 0; total += buffer.size
                require(total <= MAX_BYTES) { "The backup exceeds the safety limit." }
                if (buffer.isEmpty()) {
                    require(data.read() == -1) { "Unexpected data after the backup." }
                    ended = true; return false
                }
                return true
            }
            override fun read(): Int = if (availableFrame()) buffer[position++].toInt() and 255 else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                if (length == 0) return 0
                if (!availableFrame()) return -1
                val count = minOf(length, buffer.size - position)
                buffer.copyInto(bytes, offset, position, position + count); position += count
                return count
            }
            override fun close() { buffer.fill(0); data.close() }
        }
    }
}
