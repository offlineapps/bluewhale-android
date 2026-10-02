package com.bluewhale.android.mesh

import com.bluewhale.android.model.BluewhaleFilePacket
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import com.bluewhale.android.protocol.SpecialRecipients
import com.bluewhale.android.util.AppConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.util.Random

/**
 * The sender allowed 50 MB files while receivers reassembled at most 256 fragments
 * (about 116 KB), so voice notes past ~45 s and most files streamed for minutes and
 * were then silently discarded. What a sender may send must fit what a receiver keeps.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FileTransferLimitsTest {

    private val sender = ByteArray(8) { 0x11 }

    private fun filePacket(contentSize: Int, route: List<ByteArray>? = null): BluewhalePacket {
        val content = ByteArray(contentSize).also { Random(7).nextBytes(it) } // incompressible
        val payload = BluewhaleFilePacket("voice.m4a", contentSize.toLong(), "audio/mp4", content).encode()!!
        return BluewhalePacket(
            version = 2u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = sender,
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            signature = ByteArray(64) { 0x44 },
            ttl = 7u,
            route = route
        )
    }

    private fun reassemble(manager: FragmentManager, fragments: List<BluewhalePacket>): BluewhalePacket? {
        var result: BluewhalePacket? = null
        fragments.forEach { manager.handleFragment(it)?.let { packet -> result = packet } }
        return result
    }

    @Test
    fun `largest allowed file is accepted by the receiver`() {
        val packet = filePacket(AppConstants.Media.MAX_FILE_SIZE_BYTES.toInt())
        val manager = FragmentManager()

        val fragments = manager.createFragments(packet)
        assertTrue("file should be fragmented", fragments.size > 1)

        val reassembled = reassemble(manager, fragments)
        assertNotNull("a file at the send limit must reassemble on the receiver", reassembled)
        assertArrayEquals(packet.payload, reassembled!!.payload)
    }

    @Test
    fun `largest allowed file still fits when a source route shrinks fragments`() {
        val route = List(8) { i -> ByteArray(8) { i.toByte() } }
        val packet = filePacket(AppConstants.Media.MAX_FILE_SIZE_BYTES.toInt(), route)
        val manager = FragmentManager()

        val fragments = manager.createFragments(packet)
        assertTrue(
            "receivers accept at most ${AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID} fragments, got ${fragments.size}",
            fragments.size in 2..AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID
        )
        assertNotNull(reassemble(manager, fragments))
    }

    @Test
    fun `sender refuses a packet receivers would discard`() {
        val packet = filePacket(AppConstants.Fragmentation.MAX_SET_BYTES + 1)

        assertTrue(
            "a packet over the reassembly limit must not be fragmented and sent",
            FragmentManager().createFragments(packet).isEmpty()
        )
    }
}
