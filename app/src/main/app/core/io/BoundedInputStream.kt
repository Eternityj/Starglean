package com.starglean.app.core.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads no more than [limit] bytes using APIs available on every supported Android version.
 * Callers that need oversize detection should pass their maximum accepted size plus one.
 */
fun InputStream.readAtMostBytes(limit: Int): ByteArray {
    require(limit >= 0) { "limit must not be negative" }
    if (limit == 0) return ByteArray(0)

    val output = ByteArrayOutputStream(minOf(limit, BUFFER_BYTES))
    val buffer = ByteArray(minOf(limit, BUFFER_BYTES))
    var total = 0
    while (total < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - total))
        when {
            count < 0 -> break
            count > 0 -> {
                output.write(buffer, 0, count)
                total += count
            }
            else -> {
                // Although InputStream's blocking contract normally forbids a zero-length
                // result for a non-empty request, defensive progress avoids spinning on a
                // broken/custom stream.
                val byte = read()
                if (byte < 0) break
                output.write(byte)
                total++
            }
        }
    }
    return output.toByteArray()
}

private const val BUFFER_BYTES = 8 * 1024
