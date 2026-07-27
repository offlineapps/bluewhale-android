package com.bluewhale.android.mesh

import com.bluewhale.android.noise.NoiseSession
import com.bluewhale.android.noise.NoiseSessionManager
import com.bluewhale.android.noise.southernstorm.protocol.Noise
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import com.bluewhale.android.model.RoutedPacket
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * Peer IDs are attacker chosen until a handshake completes, so anything keyed by one
 * has to be bounded.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PerPeerStateBoundsTest {

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

    private fun peerID(i: Int) = "%016x".format(i.toLong())

    private fun packetFrom(peer: String) = RoutedPacket(
        BluewhalePacket(
            version = 1u,
            type = MessageType.ANNOUNCE.value,
            senderID = ByteArray(8) { 0 },
            recipientID = null,
            timestamp = System.currentTimeMillis().toULong(),
            payload = ByteArray(4),
            signature = null,
            ttl = 1u
        ),
        peer,
        null
    )

    @Test
    fun `flooding distinct peer ids does not grow actors without bound`() {
        val processor = PacketProcessor("00000000deadbeef")
        processor.delegate = null

        repeat(5000) { processor.processPacket(packetFrom(peerID(it))) }

        val actorCount = Regex("Active Peer Actors: (\\d+)")
            .find(processor.getDebugInfo())!!
            .groupValues[1]
            .toInt()

        assertTrue("actor count $actorCount must stay bounded", actorCount <= 256)
        processor.shutdown()
    }

    @Test
    fun `flooding handshakes does not grow sessions without bound`() {
        val (priv, pub) = keyPair()
        val manager = NoiseSessionManager(priv, pub)

        // Each is a well formed first handshake message from a different claimed peer.
        repeat(500) { i ->
            val (peerPriv, peerPub) = keyPair()
            val peer = NoiseSession(peerID(i), true, peerPriv, peerPub)
            runCatching { manager.processHandshakeMessage(peerID(i), peer.startHandshake()) }
        }

        val sessionCount = Regex("Active sessions: (\\d+)")
            .find(manager.getDebugInfo())!!
            .groupValues[1]
            .toInt()

        assertTrue("session count $sessionCount must stay bounded", sessionCount <= 32)
        manager.shutdown()
    }

    @Test
    fun `a completed session is not evicted by later handshake floods`() {
        val (priv, pub) = keyPair()
        val manager = NoiseSessionManager(priv, pub)

        val (peerPriv, peerPub) = keyPair()
        val realPeerID = peerID(9999)
        val peer = NoiseSession(realPeerID, true, peerPriv, peerPub)
        val m1 = peer.startHandshake()
        val m2 = manager.processHandshakeMessage(realPeerID, m1)!!
        val m3 = peer.processHandshakeMessage(m2)!!
        manager.processHandshakeMessage(realPeerID, m3)
        assertTrue("precondition: established", manager.hasEstablishedSession(realPeerID))

        repeat(500) { i ->
            val (p, q) = keyPair()
            val flood = NoiseSession(peerID(i), true, p, q)
            runCatching { manager.processHandshakeMessage(peerID(i), flood.startHandshake()) }
        }

        assertTrue(
            "an established session must survive a handshake flood",
            manager.hasEstablishedSession(realPeerID)
        )
        manager.shutdown()
    }
}
