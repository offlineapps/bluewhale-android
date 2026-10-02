package com.bluewhale.android.mesh

import com.bluewhale.android.model.FragmentPayload
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import com.bluewhale.android.protocol.SpecialRecipients
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.util.Random

/**
 * Fragments are unsigned and their IDs travel in the clear. Sets used to be keyed by the
 * fragment ID alone, a later copy of an index replaced the earlier one, and any metadata
 * mismatch discarded the whole set, so anyone in range who saw a fragment could cancel
 * or corrupt the transfer it belonged to.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FragmentPoisoningTest {

    private val alice = ByteArray(8) { 0x0A }
    private val mallory = ByteArray(8) { 0x0B }

    private fun original(): BluewhalePacket = BluewhalePacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = alice,
        recipientID = SpecialRecipients.BROADCAST,
        timestamp = System.currentTimeMillis().toULong(),
        payload = ByteArray(1500).also { Random(3).nextBytes(it) },
        ttl = 7u
    )

    private fun forged(of: BluewhalePacket, sender: ByteArray = of.senderID, edit: (FragmentPayload) -> FragmentPayload) =
        of.copy(senderID = sender, payload = edit(FragmentPayload.decode(of.payload)!!).encode())

    private fun deliver(manager: FragmentManager, fragments: List<BluewhalePacket>): BluewhalePacket? {
        var result: BluewhalePacket? = null
        fragments.forEach { manager.handleFragment(it)?.let { packet -> result = packet } }
        return result
    }

    @Test
    fun `a later copy of a fragment cannot overwrite the one already received`() {
        val manager = FragmentManager()
        val packet = original()
        val fragments = manager.createFragments(packet)
        assertTrue(fragments.size >= 3)

        manager.handleFragment(fragments[0])
        // Same sender, ID and index, junk data.
        manager.handleFragment(forged(fragments[0]) { it.copy(data = ByteArray(it.data.size) { 0x7F }) })

        val reassembled = deliver(manager, fragments.drop(1))
        assertNotNull(reassembled)
        assertArrayEquals(packet.payload, reassembled!!.payload)
    }

    @Test
    fun `a fragment with mismatched metadata does not cancel the transfer`() {
        val manager = FragmentManager()
        val packet = original()
        val fragments = manager.createFragments(packet)

        manager.handleFragment(fragments[0])
        manager.handleFragment(forged(fragments[1]) { it.copy(total = it.total + 1) })

        val reassembled = deliver(manager, fragments.drop(1))
        assertNotNull("the genuine transfer must still complete", reassembled)
        assertArrayEquals(packet.payload, reassembled!!.payload)
    }

    @Test
    fun `another sender reusing the fragment ID does not touch the set`() {
        val manager = FragmentManager()
        val packet = original()
        val fragments = manager.createFragments(packet)

        manager.handleFragment(fragments[0])
        manager.handleFragment(forged(fragments[1], sender = mallory) { it.copy(data = ByteArray(it.data.size)) })
        manager.handleFragment(forged(fragments[2], sender = mallory) { it.copy(total = 2) })

        val reassembled = deliver(manager, fragments.drop(1))
        assertNotNull(reassembled)
        assertArrayEquals(packet.payload, reassembled!!.payload)
    }
}
