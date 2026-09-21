package com.bluewhale.android.mesh

import com.bluewhale.android.model.IdentityAnnouncement
import com.bluewhale.android.noise.NoisePeerIdentity
import com.bluewhale.android.protocol.BinaryProtocol
import com.bluewhale.android.protocol.MessageType
import com.bluewhale.android.protocol.SpecialRecipients
import com.bluewhale.android.util.toHexString
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SimulatedPeerEngineTest {

    private fun verify(signature: ByteArray?, data: ByteArray, publicKey: ByteArray): Boolean {
        if (signature == null) return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(data, 0, data.size)
        return verifier.verifySignature(signature)
    }

    // Round trips through the wire encoder the way a received packet would arrive.
    private fun wire(packet: com.bluewhale.android.protocol.BluewhalePacket) =
        BinaryProtocol.decode(BinaryProtocol.encode(packet)!!)!!

    @Test
    fun `announce binds its peer id and self verifies`() {
        val peer = SimulatedPeer("sim-1")
        val decoded = wire(peer.announce(System.currentTimeMillis()))

        assertEquals(MessageType.ANNOUNCE.value, decoded.type)
        val ann = IdentityAnnouncement.decode(decoded.payload)
        assertNotNull("announce payload must decode", ann)
        assertTrue(
            "peer id must be derived from the announced noise key",
            NoisePeerIdentity.matchesClaimedPeerID(decoded.senderID.toHexString(), ann!!.noisePublicKey)
        )
        assertTrue(
            "announce must verify against its own signing key",
            verify(decoded.signature, decoded.toBinaryDataForSigning()!!, ann.signingPublicKey)
        )
    }

    @Test
    fun `broadcast verifies against the peer signing key`() {
        val peer = SimulatedPeer("sim-2")
        val decoded = wire(peer.broadcast("hello mesh", System.currentTimeMillis()))

        assertEquals(MessageType.MESSAGE.value, decoded.type)
        assertArrayEquals(SpecialRecipients.BROADCAST, decoded.recipientID)
        assertArrayEquals("hello mesh".toByteArray(), decoded.payload)
        assertTrue(
            "broadcast must verify against the peer signing key",
            verify(decoded.signature, decoded.toBinaryDataForSigning()!!, peer.signingPublicKey)
        )
    }

    @Test
    fun `distinct peers get distinct ids`() {
        val a = SimulatedPeer("a")
        val b = SimulatedPeer("b")
        assertNotEquals(a.peerID, b.peerID)
        assertEquals(16, a.peerID.length)
    }
}
