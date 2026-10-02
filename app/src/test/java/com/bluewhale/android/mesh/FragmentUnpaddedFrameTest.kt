package com.bluewhale.android.mesh

import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import com.bluewhale.android.protocol.SpecialRecipients
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.util.Random

/**
 * Frames over ~2 KB are never padded, but createFragments ran unpad() on them anyway.
 * When the last signature byte was 0x01 (1 in 256 packets), or the frame ended in any
 * other valid PKCS#7 pattern, real bytes were stripped and the receiver could not decode
 * the reassembled packet.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FragmentUnpaddedFrameTest {

    private fun largeSignedPacket(signatureTail: ByteArray): BluewhalePacket {
        val signature = ByteArray(64).also { Random(11).nextBytes(it) }
        signatureTail.copyInto(signature, destinationOffset = signature.size - signatureTail.size)
        return BluewhalePacket(
            version = 2u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = ByteArray(8) { 0x21 },
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = System.currentTimeMillis().toULong(),
            payload = ByteArray(5000).also { Random(5).nextBytes(it) }, // > 2 KB, incompressible
            signature = signature,
            ttl = 7u
        )
    }

    private fun roundTrip(packet: BluewhalePacket): BluewhalePacket? {
        val manager = FragmentManager()
        var result: BluewhalePacket? = null
        manager.createFragments(packet).forEach { manager.handleFragment(it)?.let { p -> result = p } }
        return result
    }

    private fun assertSurvives(tail: ByteArray) {
        val packet = largeSignedPacket(tail)
        val reassembled = roundTrip(packet)
        assertNotNull("frame ending in ${tail.toList()} must reassemble", reassembled)
        assertArrayEquals(packet.payload, reassembled!!.payload)
        assertArrayEquals(packet.signature, reassembled.signature)
    }

    @Test
    fun `frame whose signature ends in 0x01 survives fragmentation`() = assertSurvives(byteArrayOf(0x01))

    @Test
    fun `frame ending in a two byte pad pattern survives fragmentation`() = assertSurvives(byteArrayOf(0x02, 0x02))

    @Test
    fun `small padded frames still fragment and reassemble`() {
        val packet = largeSignedPacket(byteArrayOf(0x01)).copy(payload = ByteArray(900).also { Random(9).nextBytes(it) })
        val reassembled = roundTrip(packet)
        assertNotNull(reassembled)
        assertArrayEquals(packet.payload, reassembled!!.payload)
    }
}
