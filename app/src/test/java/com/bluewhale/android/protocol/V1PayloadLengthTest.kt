package com.bluewhale.android.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.util.Random

/**
 * A v1 packet states its payload length in two bytes. Encrypted private files were sent
 * as v1, so anything over 64 KiB had its length silently truncated on the wire and the
 * receiver decoded a shortened payload that could never decrypt.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class V1PayloadLengthTest {

    private fun packet(version: UByte, payload: ByteArray) = BluewhalePacket(
        version = version,
        type = MessageType.NOISE_ENCRYPTED.value,
        senderID = ByteArray(8) { 0x11 },
        recipientID = ByteArray(8) { 0x22 },
        timestamp = System.currentTimeMillis().toULong(),
        payload = payload,
        signature = ByteArray(64) { 0x33 },
        ttl = 7u
    )

    private fun randomBytes(size: Int) = ByteArray(size).also { Random(42).nextBytes(it) }

    @Test
    fun `v1 refuses a payload its length field cannot describe`() {
        // Ciphertext does not compress, so this reaches the length field as is.
        assertNull(
            "a v1 frame over 0xFFFF bytes must not be emitted with a truncated length",
            BinaryProtocol.encode(packet(1u, randomBytes(100_000)))
        )
    }

    @Test
    fun `v1 refuses a compressible payload whose original size does not fit`() {
        // Compresses well below 0xFFFF but the original size would be written in two bytes.
        assertNull(BinaryProtocol.encode(packet(1u, ByteArray(100_000) { (it % 7).toByte() })))
    }

    @Test
    fun `v1 still carries a payload at the limit`() {
        val payload = randomBytes(0xFFFF)
        val decoded = BinaryProtocol.decode(BinaryProtocol.encode(packet(1u, payload))!!)
        assertNotNull(decoded)
        assertArrayEquals(payload, decoded!!.payload)
    }

    @Test
    fun `v2 round trips a large encrypted file payload intact`() {
        val payload = randomBytes(100_000)
        val original = packet(2u, payload)

        val decoded = BinaryProtocol.decode(BinaryProtocol.encode(original)!!)

        assertNotNull(decoded)
        assertArrayEquals(payload, decoded!!.payload)
        assertArrayEquals(original.signature, decoded.signature)
    }
}
