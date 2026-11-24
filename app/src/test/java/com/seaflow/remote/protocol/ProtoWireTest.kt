package com.seaflow.remote.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProtoWireTest {
    @Test
    fun varintUsesMultipleBytesWhenRequired() {
        assertArrayEquals(byteArrayOf(0x7f), ProtoWire.varint(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), ProtoWire.varint(128))
        assertArrayEquals(byteArrayOf(0xac.toByte(), 0x02), ProtoWire.varint(300))
    }

    @Test
    fun delimitedFrameRoundTripsAcrossVarintBoundary() {
        val message = ProtoWire.string(1, "SEAFLOW") + ProtoWire.bytes(2, ByteArray(140) { it.toByte() })
        val output = ByteArrayOutputStream()
        ProtoWire.writeDelimited(output, message)
        val decoded = ProtoWire.readDelimited(ByteArrayInputStream(output.toByteArray()))
        assertArrayEquals(message, decoded)
    }

    @Test
    fun parserPreservesNestedPayloadAndStatusFields() {
        val nested = ProtoWire.string(1, "atvremote")
        val message = ProtoWire.uint(1, 2) +
            ProtoWire.uint(2, 200) +
            ProtoWire.message(10, nested)
        val fields = ProtoWire.fields(message)
        assertEquals(2L, fields.first { it.number == 1 }.integer)
        assertEquals(200L, fields.first { it.number == 2 }.integer)
        val payload = fields.first { it.number == 10 }.data!!
        assertEquals("atvremote", String(ProtoWire.fields(payload).first().data!!))
    }
}
