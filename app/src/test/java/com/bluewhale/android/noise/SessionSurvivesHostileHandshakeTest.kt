package com.bluewhale.android.noise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import com.bluewhale.android.noise.southernstorm.protocol.Noise

/**
 * Handshake packets carry no signature and their sender ID is only a claim, so an
 * established session must not be discarded on the strength of one arriving. A peer ID is
 * the hash of the peer's static key, so a handshake that completes under a peer ID its key
 * does not derive to is discarded, but only the attempt is.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionSurvivesHostileHandshakeTest {

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

    private class Established(
        val manager: NoiseSessionManager,
        val peer: NoiseSession,
        val peerID: String,
        val peerPriv: ByteArray,
        val peerPub: ByteArray
    )

    private fun managerWithEstablishedSession(): Established {
        val (localPriv, localPub) = keyPair()
        val (peerPriv, peerPub) = keyPair()
        val manager = NoiseSessionManager(localPriv, localPub)

        // The peer ID a peer may claim is fixed by its static key.
        val peerID = NoisePeerIdentity.derivePeerID(peerPub)!!

        // The peer drives an XX handshake to completion against the manager.
        val peer = NoiseSession(peerID, true, peerPriv, peerPub)
        val m1 = peer.startHandshake()
        val m2 = manager.processHandshakeMessage(peerID, m1)!!
        val m3 = peer.processHandshakeMessage(m2)!!
        manager.processHandshakeMessage(peerID, m3)

        assertTrue("precondition: session established", manager.hasEstablishedSession(peerID))
        return Established(manager, peer, peerID, peerPriv, peerPub)
    }

    @Test
    fun `garbage handshake does not take down a working session`() {
        val e = managerWithEstablishedSession()

        runCatching { e.manager.processHandshakeMessage(e.peerID, ByteArray(32) { 0x41 }) }

        assertTrue(
            "an unsigned handshake from anyone must not destroy the session",
            e.manager.hasEstablishedSession(e.peerID)
        )

        // The session still carries traffic in both directions.
        val ciphertext = e.peer.encrypt("still here".toByteArray())
        assertEquals("still here", String(e.manager.decrypt(ciphertext, e.peerID)))
    }

    @Test
    fun `repeated forged handshakes never take down the session`() {
        val e = managerWithEstablishedSession()

        repeat(20) { attempt ->
            runCatching {
                e.manager.processHandshakeMessage(e.peerID, ByteArray(32) { (attempt + it).toByte() })
            }
            assertTrue("session survived attempt $attempt", e.manager.hasEstablishedSession(e.peerID))
        }

        val ciphertext = e.peer.encrypt("survived".toByteArray())
        assertEquals("survived", String(e.manager.decrypt(ciphertext, e.peerID)))
    }

    @Test
    fun `an unfinished handshake leaves the session alone`() {
        val e = managerWithEstablishedSession()

        // A well formed first message from someone who never completes the exchange.
        val (otherPriv, otherPub) = keyPair()
        val stranger = NoiseSession(e.peerID, true, otherPriv, otherPub)
        e.manager.processHandshakeMessage(e.peerID, stranger.startHandshake())

        assertTrue("half finished handshake must not replace anything", e.manager.hasEstablishedSession(e.peerID))
        val ciphertext = e.peer.encrypt("original session".toByteArray())
        assertEquals("original session", String(e.manager.decrypt(ciphertext, e.peerID)))
    }

    /**
     * The case where the two fixes meet. Dropping the live session on a mismatch would
     * hand the denial of service straight back: anyone can run an XX handshake to
     * completion under someone else's peer ID, because nothing in it is signed.
     */
    @Test
    fun `a completed handshake under a mismatched peer id only loses the attempt`() {
        val e = managerWithEstablishedSession()

        val (malloryPriv, malloryPub) = keyPair()
        val mallory = NoiseSession(e.peerID, true, malloryPriv, malloryPub)
        val m1 = mallory.startHandshake()
        val m2 = e.manager.processHandshakeMessage(e.peerID, m1)!!
        val m3 = mallory.processHandshakeMessage(m2)!!

        // The handshake completes, and the manager rejects it because Mallory's static key
        // does not derive to the peer ID she claimed.
        assertNull(
            "a handshake whose static key contradicts the claimed peer ID is refused",
            e.manager.processHandshakeMessage(e.peerID, m3)
        )

        assertTrue(
            "rejecting the impostor must not take the real session with it",
            e.manager.hasEstablishedSession(e.peerID)
        )

        // The genuine peer's traffic still decrypts, so the live session was untouched.
        val ciphertext = e.peer.encrypt("untouched".toByteArray())
        assertEquals("untouched", String(e.manager.decrypt(ciphertext, e.peerID)))
    }

    @Test
    fun `a mismatched peer id is refused when there is no session to protect`() {
        val (localPriv, localPub) = keyPair()
        val manager = NoiseSessionManager(localPriv, localPub)
        val (malloryPriv, malloryPub) = keyPair()

        // A peer ID that belongs to somebody else entirely.
        val (_, victimPub) = keyPair()
        val victimPeerID = NoisePeerIdentity.derivePeerID(victimPub)!!

        val mallory = NoiseSession(victimPeerID, true, malloryPriv, malloryPub)
        val m1 = mallory.startHandshake()
        val m2 = manager.processHandshakeMessage(victimPeerID, m1)!!
        val m3 = mallory.processHandshakeMessage(m2)!!

        assertNull(manager.processHandshakeMessage(victimPeerID, m3))
        assertFalse(
            "an impostor must not end up holding the victim's peer ID",
            manager.hasEstablishedSession(victimPeerID)
        )
    }

    @Test
    fun `a peer that lost its state can still re-handshake`() {
        val e = managerWithEstablishedSession()

        // The same peer, keeping its identity key so it keeps its peer ID, starting a
        // fresh session as if it had restarted and lost the old one.
        val restarted = NoiseSession(e.peerID, true, e.peerPriv, e.peerPub)
        val m1 = restarted.startHandshake()
        val m2 = e.manager.processHandshakeMessage(e.peerID, m1)!!
        val m3 = restarted.processHandshakeMessage(m2)!!
        e.manager.processHandshakeMessage(e.peerID, m3)

        assertTrue("recovery must still work", e.manager.hasEstablishedSession(e.peerID))

        // The promoted session is the new one, so traffic flows under the new session keys.
        val ciphertext = restarted.encrypt("recovered".toByteArray())
        assertEquals("recovered", String(e.manager.decrypt(ciphertext, e.peerID)))
    }

    @Test
    fun `local rekey still replaces the session deliberately`() {
        val e = managerWithEstablishedSession()

        e.manager.initiateHandshake(e.peerID)

        assertFalse(
            "our own rekey is a local decision and does replace the session",
            e.manager.hasEstablishedSession(e.peerID)
        )
    }
}
