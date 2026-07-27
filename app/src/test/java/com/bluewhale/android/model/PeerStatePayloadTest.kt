package com.bluewhale.android.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Payload 0x21 is shared with bitchat and iOS, so the bytes matter more than the
 * implementation. These lock the documented wire format down.
 */
class PeerStatePayloadTest {

    private val signingKey = ByteArray(32) { (it + 3).toByte() }

    @Test
    fun `encodes the documented layout`() {
        val encoded = PeerStatePayload(PeerStatePayload.CAPABILITY_PRIVATE_MEDIA, signingKey).encode()!!

        assertEquals(0x01, encoded[0].toInt() and 0xFF)
        assertEquals(0x01, encoded[1].toInt() and 0xFF)
        assertEquals(2, encoded[2].toInt() and 0xFF)
        // 0x100 little endian, shortest form
        assertEquals(0x00, encoded[3].toInt() and 0xFF)
        assertEquals(0x01, encoded[4].toInt() and 0xFF)
        assertEquals(0x02, encoded[5].toInt() and 0xFF)
        assertEquals(32, encoded[6].toInt() and 0xFF)
        assertArrayEquals(signingKey, encoded.copyOfRange(7, 39))
        assertEquals(39, encoded.size)
    }

    @Test
    fun `round trips`() {
        val original = PeerStatePayload(PeerStatePayload.CAPABILITY_PRIVATE_MEDIA, signingKey)
        assertEquals(original, PeerStatePayload.decode(original.encode()!!))
    }

    @Test
    fun `skips unknown tlv types so newer clients still parse`() {
        val withExtra = byteArrayOf(
            0x01,
            0x01, 0x02, 0x00, 0x01,
            0x7F, 0x03, 0x0A, 0x0B, 0x0C,
            0x02, 32
        ) + signingKey
        val decoded = PeerStatePayload.decode(withExtra)
        assertEquals(PeerStatePayload.CAPABILITY_PRIVATE_MEDIA, decoded?.capabilities)
        assertArrayEquals(signingKey, decoded?.signingPublicKey)
    }

    @Test
    fun `accepts a padded capability field rather than locking the sender out`() {
        val padded = byteArrayOf(0x01, 0x01, 0x04, 0x00, 0x01, 0x00, 0x00, 0x02, 32) + signingKey
        assertEquals(PeerStatePayload.CAPABILITY_PRIVATE_MEDIA, PeerStatePayload.decode(padded)?.capabilities)
    }

    @Test
    fun `rejects wrong version, short key and duplicate fields`() {
        assertNull(PeerStatePayload.decode(byteArrayOf(0x02, 0x02, 32) + signingKey))
        assertNull(PeerStatePayload.decode(byteArrayOf(0x01, 0x01, 0x01, 0x00, 0x02, 8) + ByteArray(8)))
        val duplicate = byteArrayOf(0x01, 0x01, 0x01, 0x00, 0x02, 32) + signingKey + byteArrayOf(0x02, 32) + signingKey
        assertNull(PeerStatePayload.decode(duplicate))
    }

    @Test
    fun `requires both known fields`() {
        assertNull(PeerStatePayload.decode(byteArrayOf(0x01, 0x02, 32) + signingKey))
        assertNull(PeerStatePayload.decode(byteArrayOf(0x01, 0x01, 0x01, 0x00)))
    }
}
