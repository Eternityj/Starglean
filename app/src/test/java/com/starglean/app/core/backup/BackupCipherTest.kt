package com.starglean.app.core.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class BackupCipherTest {
    private val password get() = "a sufficiently long passphrase".toCharArray()
    private fun seal(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        BackupCipher.encrypt(output, password).use { it.write(bytes) }
    }.toByteArray()
    private fun open(bytes: ByteArray, secret: CharArray = password) = BackupCipher.decrypt(bytes.inputStream(), secret).use { it.readBytes() }
    @Test fun `round trip across chunks and final partial chunk`() {
        val plain = ByteArray(180_001) { (it % 253).toByte() }
        assertArrayEquals(plain, open(seal(plain)))
    }
    @Test fun `salt and nonce are fresh for every export`() {
        val one = seal("private memory".toByteArray()); val two = seal("private memory".toByteArray())
        assertFalse(one.contentEquals(two)); assertFalse(String(one).contains("private memory"))
    }
    @Test fun `wrong password tampering truncation and trailing data fail closed`() {
        val bytes = seal(ByteArray(90_000) { 42 })
        assertTrue(runCatching { open(bytes, "a different long password".toCharArray()) }.isFailure)
        assertTrue(runCatching { open(bytes.clone().also { it[70] = (it[70].toInt() xor 1).toByte() }) }.isFailure)
        assertTrue(runCatching { open(bytes.copyOf(bytes.size - 20)) }.isFailure)
        assertTrue(runCatching { open(bytes + byteArrayOf(1)) }.isFailure)
    }
    @Test fun `empty payload still needs authenticated ending`() {
        val bytes = seal(byteArrayOf()); assertArrayEquals(byteArrayOf(), open(bytes))
        assertTrue(runCatching { open(bytes.copyOf(32)) }.isFailure)
    }
    @Test fun `short passwords and unbounded frame lengths rejected`() {
        assertTrue(runCatching { BackupCipher.encrypt(ByteArrayOutputStream(), "short".toCharArray()) }.isFailure)
        val bytes = seal(byteArrayOf(1))
        java.nio.ByteBuffer.wrap(bytes, 32, 4).putInt(Int.MAX_VALUE)
        assertTrue(runCatching { open(bytes) }.isFailure)
    }
}
