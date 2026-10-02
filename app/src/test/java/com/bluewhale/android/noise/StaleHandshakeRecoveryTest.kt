package com.bluewhale.android.noise

import com.bluewhale.android.noise.southernstorm.protocol.Noise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * A handshake whose reply or final message is lost left a half open session behind with no
 * timeout. The peer's next attempt was fed into it, threw, and cost a whole extra round
 * before a third attempt could succeed.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class StaleHandshakeRecoveryTest {

    private fun keyPair(): Pair<ByteArray, ByteArray> {
        val dh = Noise.createDH("25519")
        dh.generateKeyPair()
        val priv = ByteArray(32)
        val pub = ByteArray(32)
        dh.getPrivateKey(priv, 0)
        dh.getPublicKey(pub, 0)
        dh.destroy()
        return priv to pub
    }

    private class Side(val manager: NoiseSessionManager, val peerID: String)

    /** Two managers that know each other's peer IDs, optionally with b's clock shifted. */
    private fun pair(bClockOffsetMs: () -> Long = { 0L }): Pair<Side, Side> {
        val (aPriv, aPub) = keyPair()
        val (bPriv, bPub) = keyPair()
        val a = NoiseSessionManager(aPriv, aPub)
        val b = NoiseSessionManager(bPriv, bPub) { System.currentTimeMillis() + bClockOffsetMs() }
        return Side(a, NoisePeerIdentity.derivePeerID(aPub)!!) to Side(b, NoisePeerIdentity.derivePeerID(bPub)!!)
    }

    private fun completeHandshake(initiator: Side, responder: Side) {
        val m1 = initiator.manager.initiateHandshake(responder.peerID)
        val m2 = responder.manager.processHandshakeMessage(initiator.peerID, m1)!!
        val m3 = initiator.manager.processHandshakeMessage(responder.peerID, m2)!!
        responder.manager.processHandshakeMessage(initiator.peerID, m3)
    }

    private fun assertWorks(a: Side, b: Side) {
        assertTrue(a.manager.hasEstablishedSession(b.peerID))
        assertTrue(b.manager.hasEstablishedSession(a.peerID))
        val ciphertext = a.manager.encrypt("hello".toByteArray(), b.peerID)
        assertEquals("hello", String(b.manager.decrypt(ciphertext, a.peerID)))
    }

    @Test
    fun `a retry succeeds after the responder's reply was lost`() {
        val (a, b) = pair()
        val m1 = a.manager.initiateHandshake(b.peerID)
        assertNotNull(b.manager.processHandshakeMessage(a.peerID, m1)) // reply lost in transit

        // a starts over; b must not choke on the new first message.
        completeHandshake(a, b)
        assertWorks(a, b)
    }

    @Test
    fun `a retry succeeds after the initiator's final message was lost`() {
        val (a, b) = pair()
        val m1 = a.manager.initiateHandshake(b.peerID)
        val m2 = b.manager.processHandshakeMessage(a.peerID, m1)!!
        a.manager.processHandshakeMessage(b.peerID, m2) // final message lost; b stays half open

        completeHandshake(a, b)
        assertWorks(a, b)
    }

    @Test
    fun `a stale initiator attempt yields to the peer starting over`() {
        var offset = 0L
        val (a, b) = pair { offset }
        // b started a handshake toward a whose first message never arrived.
        b.manager.initiateHandshake(a.peerID)
        offset = 60_000L

        // Later a initiates; b's dead attempt must not swallow it.
        completeHandshake(a, b)
        assertWorks(a, b)
    }
}
