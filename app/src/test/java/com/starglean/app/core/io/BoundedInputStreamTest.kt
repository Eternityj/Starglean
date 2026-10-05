package com.starglean.app.core.io

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedInputStreamTest {
    @Test fun `eof returns an empty array`() {
        assertArrayEquals(ByteArray(0), ByteArrayInputStream(ByteArray(0)).readAtMostBytes(8))
    }

    @Test fun `zero limit does not touch the stream`() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int { reads++; return 1 }
        }

        assertArrayEquals(ByteArray(0), input.readAtMostBytes(0))
        assertEquals(0, reads)
    }

    @Test fun `negative limit is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(byteArrayOf(1)).readAtMostBytes(-1)
        }
    }

    @Test fun `short reads are accumulated through eof`() {
        val source = byteArrayOf(1, 2, 3, 4, 5)
        val input = object : InputStream() {
            var offset = 0
            override fun read(): Int = if (offset < source.size) source[offset++].toInt() and 0xff else -1
            override fun read(target: ByteArray, off: Int, len: Int): Int {
                if (offset >= source.size) return -1
                target[off] = source[offset++]
                return 1
            }
        }

        assertArrayEquals(source, input.readAtMostBytes(20))
    }

    @Test fun `zero-returning bulk read still makes progress`() {
        val source = byteArrayOf(9, 8, 7)
        val input = object : InputStream() {
            var offset = 0
            var returnZero = true
            override fun read(): Int = if (offset < source.size) source[offset++].toInt() and 0xff else -1
            override fun read(target: ByteArray, off: Int, len: Int): Int {
                if (returnZero) { returnZero = false; return 0 }
                return super.read(target, off, len)
            }
        }

        assertArrayEquals(source, input.readAtMostBytes(10))
    }

    @Test fun `zero-returning bulk read followed by eof terminates empty`() {
        val input = object : InputStream() {
            override fun read(): Int = -1
            override fun read(target: ByteArray, off: Int, len: Int): Int = 0
        }

        assertArrayEquals(ByteArray(0), input.readAtMostBytes(10))
    }

    @Test fun `limit preserves one-byte oversize sentinel`() {
        val source = ByteArray(11) { it.toByte() }
        var consumed = 0
        val input = object : InputStream() {
            override fun read(): Int = if (consumed < source.size) source[consumed++].toInt() and 0xff else -1
            override fun read(target: ByteArray, off: Int, len: Int): Int {
                if (consumed >= source.size) return -1
                val count = minOf(len, source.size - consumed)
                source.copyInto(target, off, consumed, consumed + count)
                consumed += count
                return count
            }
        }
        val result = input.readAtMostBytes(6)

        assertArrayEquals(source.copyOf(6), result)
        assertEquals(6, result.size)
        assertEquals("reader must not consume beyond the requested cap", 6, consumed)
    }

    @Test fun `io exception is propagated`() {
        val failure = IOException("read failed")
        val input = object : InputStream() {
            override fun read(): Int = throw failure
            override fun read(target: ByteArray, off: Int, len: Int): Int = throw failure
        }

        val thrown = assertThrows(IOException::class.java) { input.readAtMostBytes(5) }
        assertEquals(failure, thrown)
    }
}
