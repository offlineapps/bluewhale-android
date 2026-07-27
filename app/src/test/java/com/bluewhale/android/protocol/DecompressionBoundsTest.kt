package com.bluewhale.android.protocol

import com.bluewhale.android.util.AppConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.Deflater

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DecompressionBoundsTest {

    private fun rawDeflate(input: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        d.setInput(input); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** v2 frame carrying a compressed payload that declares [declaredOriginalSize]. */
    private fun compressedFrame(compressed: ByteArray, declaredOriginalSize: Int): ByteArray {
        val payloadLength = 4 + compressed.size
        // v2 fixed header is 16 bytes on the wire: 1+1+1+8+1+4
        val buf = ByteBuffer.allocate(16 + 8 + payloadLength).order(ByteOrder.BIG_ENDIAN)
        buf.put(2)
        buf.put(0x02)
        buf.put(7)
        buf.putLong(System.currentTimeMillis())
        buf.put(0x04) // IS_COMPRESSED
        buf.putInt(payloadLength)
        buf.put(ByteArray(8) { 0x11 })
        buf.putInt(declaredOriginalSize)
        buf.put(compressed)
        return buf.array()
    }

    @Test
    fun `frame declaring a two gigabyte payload is rejected instead of allocated`() {
        val rnd = Random(1)
        val incompressible = ByteArray(60_000).also { rnd.nextBytes(it) }
        val compressed = rawDeflate(incompressible)

        // Stays under the existing 50000:1 ratio guard, which is why that guard alone
        // never stopped this.
        val declared = Int.MAX_VALUE
        val ratio = declared.toDouble() / compressed.size.toDouble()
        assertTrue("PoC must stay under the ratio guard, was $ratio", ratio < 50_000.0)

        assertNull(BinaryProtocol.decode(compressedFrame(compressed, declared)))
    }

    @Test
    fun `declared size just past the ceiling is rejected`() {
        val payload = ByteArray(4096) { 0x41 }
        val compressed = rawDeflate(payload)
        val overCeiling = AppConstants.Protocol.MAX_DECOMPRESSED_BYTES + 1

        assertNull(BinaryProtocol.decode(compressedFrame(compressed, overCeiling)))
    }

    @Test
    fun `negative and zero declared sizes are rejected`() {
        val compressed = rawDeflate(ByteArray(4096) { 0x41 })

        assertNull(BinaryProtocol.decode(compressedFrame(compressed, -1)))
        assertNull(BinaryProtocol.decode(compressedFrame(compressed, 0)))
        assertNull(CompressionUtil.decompress(compressed, -1))
        assertNull(CompressionUtil.decompress(compressed, 0))
    }

    @Test
    fun `payload that expands past its declared size is rejected`() {
        val payload = ByteArray(8192) { 0x41 }
        val compressed = rawDeflate(payload)

        // Understating the size must not let the extra bytes through.
        assertNull(CompressionUtil.decompress(compressed, 100))
    }

    @Test
    fun `ordinary compressed traffic still round trips`() {
        val payload = "the quick brown fox jumps over the lazy dog. ".repeat(40).toByteArray()
        val compressed = rawDeflate(payload)

        val direct = CompressionUtil.decompress(compressed, payload.size)
        assertNotNull(direct)
        assertArrayEquals(payload, direct)

        val decoded = BinaryProtocol.decode(compressedFrame(compressed, payload.size))
        assertNotNull("a legitimate compressed frame must still decode", decoded)
        assertArrayEquals(payload, decoded!!.payload)
    }

    @Test
    fun `full size encode decode round trip is unaffected`() {
        val packet = BluewhalePacket(
            version = 2u,
            type = 0x02u,
            senderID = ByteArray(8) { 0x22 },
            recipientID = null,
            timestamp = 1234UL,
            payload = "compress me please. ".repeat(60).toByteArray(),
            signature = null,
            ttl = 5u
        )
        val encoded = packet.toBinaryData()
        assertNotNull(encoded)
        val decoded = BinaryProtocol.decode(encoded!!)
        assertNotNull(decoded)
        assertArrayEquals(packet.payload, decoded!!.payload)
        assertEquals(packet.type, decoded.type)
    }
}
