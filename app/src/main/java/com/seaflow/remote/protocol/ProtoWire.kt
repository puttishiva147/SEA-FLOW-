package com.seaflow.remote.protocol

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Minimal protobuf wire-format support for Android TV Remote v2 envelopes. */
internal object ProtoWire {
    fun varint(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        while ((v and -128L) != 0L) {
            out.write(((v and 127L) or 128L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    fun uint(field: Int, value: Long): ByteArray =
        varint((field shl 3).toLong()) + varint(value)

    fun bytes(field: Int, value: ByteArray): ByteArray =
        varint(((field shl 3) or 2).toLong()) + varint(value.size.toLong()) + value

    fun string(field: Int, value: String): ByteArray = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, body: ByteArray): ByteArray = bytes(field, body)

    fun readVarint(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("Unexpected end of protobuf stream")
            result = result or ((b and 0x7f).toLong() shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Malformed protobuf varint")
    }

    fun readDelimited(input: InputStream, maxBytes: Int = 1 shl 20): ByteArray {
        val size = readVarint(input).toInt()
        require(size in 1..maxBytes) { "Invalid protobuf frame size: $size" }
        val data = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(data, offset, size - offset)
            if (count < 0) throw java.io.EOFException("Truncated protobuf frame")
            offset += count
        }
        return data
    }

    fun writeDelimited(output: OutputStream, message: ByteArray) {
        output.write(varint(message.size.toLong()))
        output.write(message)
        output.flush()
    }

    data class Field(val number: Int, val wireType: Int, val integer: Long?, val data: ByteArray?)

    fun fields(bytes: ByteArray): List<Field> {
        val result = mutableListOf<Field>()
        var i = 0
        fun read(): Long {
            var v = 0L
            var shift = 0
            while (shift < 64 && i < bytes.size) {
                val b = bytes[i++].toInt() and 255
                v = v or ((b and 127).toLong() shl shift)
                if ((b and 128) == 0) return v
                shift += 7
            }
            throw IllegalArgumentException("Malformed protobuf")
        }
        while (i < bytes.size) {
            val tag = read().toInt()
            val number = tag ushr 3
            val wire = tag and 7
            require(number > 0) { "Invalid protobuf field" }
            when (wire) {
                0 -> result += Field(number, wire, read(), null)
                1 -> { require(i + 8 <= bytes.size); result += Field(number, wire, null, bytes.copyOfRange(i, i + 8)); i += 8 }
                2 -> {
                    val length = read().toInt()
                    require(length >= 0 && i + length <= bytes.size)
                    result += Field(number, wire, null, bytes.copyOfRange(i, i + length)); i += length
                }
                5 -> { require(i + 4 <= bytes.size); result += Field(number, wire, null, bytes.copyOfRange(i, i + 4)); i += 4 }
                else -> throw IllegalArgumentException("Unsupported protobuf wire type: $wire")
            }
        }
        return result
    }
}
