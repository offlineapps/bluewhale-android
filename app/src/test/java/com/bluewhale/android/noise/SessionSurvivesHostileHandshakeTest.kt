package com.bluewhale.android.noise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import com.bluewhale.android.noise.southernstorm.protocol.Noise

/**
 * Handshake packets carry no signature and their sender ID is only a claim, so an
 * established session must not be discarded on the strength of one arriving.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionSurvivesHostileHandshakeTest {

    private val peerID = "aabbccddeeff0011"

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

    private fun managerWithEstablishedSession(): Pair<NoiseSessionManager, NoiseSession> {
        val (localPriv, localPub) = keyPair()
        val (peerPriv, peerPub) = keyPair()
        val manager = NoiseSessionManager(localPriv, localPub)

        // The peer drives an XX handshake to completion against the manager.
        val peer = NoiseSession(peerID, true, peerPriv, peerPub)
        val m1 = peer.startHandshake()
        val m2 = manager.processHandshakeMessage(peerID, m1)!!
        val m3 = peer.processHandshakeMessage(m2)!!
        manager.processHandshakeMessage(peerID, m3)

        assertTrue("precondition: session established", manager.hasEstablishedSession(peerID))
        return manager to peer
    }

    @Test
    fun `garbage handshake does not take down a working session`() {
        val (manager, peer) = managerWithEstablishedSession()

        runCatching { manager.processHandshakeMessage(peerID, ByteArray(32) { 0x41 }) }

        assertTrue(
            "an unsigned handshake from anyone must not destroy the session",
            manager.hasEstablishedSession(peerID)
        )

        // The session still carries traffic in both directions.
        val ciphertext = peer.encrypt("still here".toByteArray())
        assertEquals("still here", String(manager.decrypt(ciphertext, peerID)))
    }

    @Test
    fun `repeated forged handshakes never take down the session`() {
        val (manager, peer) = managerWithEstablishedSession()

        repeat(20) { attempt ->
            runCatching {
                manager.processHandshakeMessage(peerID, ByteArray(32) { (attempt + it).toByte() })
            }
            assertTrue("session survived attempt $attempt", manager.hasEstablishedSession(peerID))
        }

        val ciphertext = peer.encrypt("survived".toByteArray())
        assertEquals("survived", String(manager.decrypt(ciphertext, peerID)))
    }

    @Test
    fun `an unfinished handshake leaves the session alone`() {
        val (manager, peer) = managerWithEstablishedSession()

        // A well formed first message from someone who never completes the exchange.
        val (otherPriv, otherPub) = keyPair()
        val stranger = NoiseSession(peerID, true, otherPriv, otherPub)
        manager.processHandshakeMessage(peerID, stranger.startHandshake())

        assertTrue("half finished handshake must not replace anything", manager.hasEstablishedSession(peerID))
        val ciphertext = peer.encrypt("original session".toByteArray())
        assertEquals("original session", String(manager.decrypt(ciphertext, peerID)))
    }

    @Test
    fun `a peer that lost its state can still re-handshake`() {
        val (manager, _) = managerWithEstablishedSession()

        // Same peer, fresh keys, as if it had been reinstalled.
        val (newPriv, newPub) = keyPair()
        val restarted = NoiseSession(peerID, true, newPriv, newPub)
        val m1 = restarted.startHandshake()
        val m2 = manager.processHandshakeMessage(peerID, m1)!!
        val m3 = restarted.processHandshakeMessage(m2)!!
        manager.processHandshakeMessage(peerID, m3)

        assertTrue("recovery must still work", manager.hasEstablishedSession(peerID))

        // The promoted session is the new one, so traffic flows under the new keys.
        val ciphertext = restarted.encrypt("recovered".toByteArray())
        assertEquals("recovered", String(manager.decrypt(ciphertext, peerID)))
    }

    @Test
    fun `local rekey still replaces the session deliberately`() {
        val (manager, _) = managerWithEstablishedSession()

        manager.initiateHandshake(peerID)

        assertFalse(
            "our own rekey is a local decision and does replace the session",
            manager.hasEstablishedSession(peerID)
        )
    }
}
