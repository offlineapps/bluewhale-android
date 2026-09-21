package com.bluewhale.android.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PacketLengthBoundsTest {

    // v2 frame with an attacker chosen declared payload length and a small real body.
    // v2 fixed header is 16 bytes on the wire: 1+1+1+8+1+4.
    private fun v2Frame(declaredPayloadLength: Int, body: ByteArray, flags: Int = 0): ByteArray {
        val buf = ByteBuffer.allocate(16 + 8 + body.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(2)
        buf.put(0x02)
        buf.put(7)
        buf.putLong(System.currentTimeMillis())
        buf.put(flags.toByte())
        buf.putInt(declaredPayloadLength)
        buf.put(ByteArray(8) { 0x11 })
        buf.put(body)
        return buf.array()
    }

    @Test
    fun `declared length near Int MAX is rejected instead of allocated`() {
        // headerSize + sender + payloadLength once overflowed a signed Int expectedSize
        // to a negative value, which passed the size check and reached ByteArray(length).
        assertNull(BinaryProtocol.decode(v2Frame(Int.MAX_VALUE, ByteArray(0))))
        assertNull(BinaryProtocol.decode(v2Frame(Int.MAX_VALUE - 10, ByteArray(0))))
    }

    @Test
    fun `declared length with the high bit set is rejected`() {
        // 0x80000000 read as a UInt, i.e. a negative Int after narrowing.
        assertNull(BinaryProtocol.decode(v2Frame(Int.MIN_VALUE, ByteArray(0))))
    }

    @Test
    fun `declared length larger than the frame is rejected`() {
        assertNull(BinaryProtocol.decode(v2Frame(1_000_000, ByteArray(4))))
    }

    @Test
    fun `an honest v2 frame still decodes`() {
        val body = "hello mesh".toByteArray()
        val decoded = BinaryProtocol.decode(v2Frame(body.size, body))
        assertNotNull("a legitimate v2 frame must still decode", decoded)
        assertArrayEquals(body, decoded!!.payload)
    }
}
