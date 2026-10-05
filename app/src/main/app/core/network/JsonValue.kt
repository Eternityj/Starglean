package com.starglean.app.core.network

import java.lang.StringBuilder

internal object JsonValue {
    fun encodeObject(value: Map<String, Any?>): String = encode(value)

    @Suppress("UNCHECKED_CAST")
    fun parseObject(source: String): Map<String, Any?> {
        val parser = Parser(source)
        val value = parser.value()
        parser.finish()
        return value as? Map<String, Any?> ?: throw ProtocolException("Expected a JSON object")
    }

    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
            append('"')
        }
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> value.takeIf { it.isFinite() }?.toString() ?: throw ProtocolException("Non-finite number")
        is Double -> value.takeIf { it.isFinite() }?.toString() ?: throw ProtocolException("Non-finite number")
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            require(key is String) { "JSON object keys must be strings" }
            "${encode(key)}:${encode(item)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { encode(it) }
        else -> throw ProtocolException("Unsupported JSON value")
    }

    private class Parser(private val source: String) {
        private var index = 0
        fun finish() { whitespace(); if (index != source.length) fail() }
        fun value(): Any? {
            whitespace()
            if (index >= source.length) fail()
            return when (source[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> numberValue()
            }
        }
        private fun objectValue(): Map<String, Any?> {
            index++
            val result = linkedMapOf<String, Any?>()
            whitespace()
            if (take('}')) return result
            while (true) {
                whitespace(); if (index >= source.length || source[index] != '"') fail()
                val key = stringValue()
                whitespace(); expect(':')
                result[key] = value()
                whitespace()
                if (take('}')) return result
                expect(',')
            }
        }
        private fun arrayValue(): List<Any?> {
            index++
            val result = mutableListOf<Any?>()
            whitespace()
            if (take(']')) return result
            while (true) {
                result += value(); whitespace()
                if (take(']')) return result
                expect(',')
            }
        }
        private fun stringValue(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val ch = source[index++]
                when (ch) {
                    '"' -> return result.toString()
                    '\\' -> {
                        if (index >= source.length) fail()
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                if (index + 4 > source.length) fail()
                                result.append(source.substring(index, index + 4).toIntOrNull(16)?.toChar() ?: fail())
                                index += 4
                            }
                            else -> fail()
                        }
                    }
                    else -> if (ch.code < 0x20) fail() else result.append(ch)
                }
            }
            fail()
        }
        private fun numberValue(): Number {
            val start = index
            if (take('-') && index >= source.length) fail()
            if (take('0')) {
                if (index < source.length && source[index].isDigit()) fail()
            } else {
                if (index >= source.length || source[index] !in '1'..'9') fail()
                while (index < source.length && source[index].isDigit()) index++
            }
            var decimal = false
            if (take('.')) {
                decimal = true
                if (index >= source.length || !source[index].isDigit()) fail()
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && source[index] in "eE") {
                decimal = true; index++
                if (index < source.length && source[index] in "+-") index++
                if (index >= source.length || !source[index].isDigit()) fail()
                while (index < source.length && source[index].isDigit()) index++
            }
            val raw = source.substring(start, index)
            return if (decimal) raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail()
            else raw.toLongOrNull() ?: fail()
        }
        private fun <T> literal(text: String, value: T): T {
            if (!source.regionMatches(index, text, 0, text.length)) fail()
            index += text.length
            return value
        }
        private fun whitespace() { while (index < source.length && source[index] in " \n\r\t") index++ }
        private fun take(ch: Char): Boolean = index < source.length && source[index] == ch && run { index++; true }
        private fun expect(ch: Char) { if (!take(ch)) fail() }
        private fun fail(): Nothing = throw ProtocolException("Malformed JSON")
    }
}

class ProtocolException(message: String) : Exception(message)
