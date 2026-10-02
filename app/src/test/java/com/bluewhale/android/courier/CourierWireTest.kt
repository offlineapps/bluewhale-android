package com.bluewhale.android.courier

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourierWireTest {

    private fun envelope(sealed: ByteArray = ByteArray(120) { it.toByte() }) = CourierEnvelope(
        id = ByteArray(16) { 1 },
        recipientTag = ByteArray(16) { 2 },
        priority = CourierEnvelope.Priority.URGENT,
        createdAt = 1_700_000_000_000L,
        expiresAt = 1_700_259_200_000L,
        sealed = sealed
    )

    @Test
    fun `envelope round trips`() {
        val decoded = CourierWire.decode(CourierWire.encode(envelope())) as CourierWire.Decoded.Envelope
        val e = decoded.envelope

        assertArrayEquals(envelope().id, e.id)
        assertArrayEquals(envelope().recipientTag, e.recipientTag)
        assertEquals(CourierEnvelope.Priority.URGENT, e.priority)
        assertEquals(1_700_000_000_000L, e.createdAt)
        assertEquals(1_700_259_200_000L, e.expiresAt)
        assertArrayEquals(envelope().sealed, e.sealed)
    }

    @Test
    fun `ack round trips`() {
        val ids = listOf(ByteArray(16) { 3 }, ByteArray(16) { 4 })
        val decoded = CourierWire.decode(CourierWire.encodeAck(ids)) as CourierWire.Decoded.Ack

        assertEquals(2, decoded.ids.size)
        assertArrayEquals(ids[1], decoded.ids[1])
    }

    @Test
    fun `prologue covers every header field`() {
        val base = CourierWire.prologue(envelope())
        val laterExpiry = envelope().let { CourierEnvelope(it.id, it.recipientTag, it.priority, it.createdAt, it.expiresAt + 1, it.sealed) }
        val normal = envelope().let { CourierEnvelope(it.id, it.recipientTag, CourierEnvelope.Priority.NORMAL, it.createdAt, it.expiresAt, it.sealed) }

        assertFalse(base.contentEquals(CourierWire.prologue(laterExpiry)))
        assertFalse(base.contentEquals(CourierWire.prologue(normal)))
    }

    @Test
    fun `malformed input decodes to null`() {
        val good = CourierWire.encode(envelope())

        assertNull(CourierWire.decode(ByteArray(0)))
        assertNull(CourierWire.decode(byteArrayOf(0x09)))
        assertNull("truncated", CourierWire.decode(good.copyOf(good.size - 1)))
        assertNull("trailing bytes", CourierWire.decode(good + byteArrayOf(0)))
        assertNull("unknown version", CourierWire.decode(good.copyOf().also { it[1] = 9 }))
        assertNull("unknown priority", CourierWire.decode(good.copyOf().also { it[2] = 9 }))
        assertNull("empty ack", CourierWire.decode(byteArrayOf(0x02, 0)))
    }

    @Test
    fun `recipient tag is stable, short and does not reveal the key`() {
        val key = ByteArray(32) { 9 }
        val tag = CourierWire.recipientTag(key)

        assertEquals(16, tag.size)
        assertArrayEquals(tag, CourierWire.recipientTag(key.copyOf()))
        assertTrue(!key.toHex().contains(tag.toHex()))
    }
}
