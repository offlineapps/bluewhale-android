package com.bluewhale.android.mesh

import com.bluewhale.android.noise.NoisePeerIdentity
import com.bluewhale.android.noise.NoiseSession
import com.bluewhale.android.noise.NoiseSessionManager
import com.bluewhale.android.noise.southernstorm.protocol.Noise
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import com.bluewhale.android.model.RoutedPacket
import org.junit.Assert.assertEquals
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

    /**
     * Counts every packet that reaches the handler. The first one is held until the gate
     * opens, so the per-peer queue fills up behind it.
     */
    private class CountingDelegate(
        private val seen: java.util.concurrent.atomic.AtomicInteger,
        private val gate: java.util.concurrent.CountDownLatch
    ) : PacketProcessorDelegate {
        override fun validatePacketSecurity(packet: BluewhalePacket, peerID: String): Boolean {
            if (seen.incrementAndGet() == 1) {
                gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            return false
        }

        override fun updatePeerLastSeen(peerID: String) {}
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize(): Int = 1
        override fun getBroadcastRecipient(): ByteArray = ByteArray(8) { 0xFF.toByte() }
        override fun handleNoiseHandshake(routed: RoutedPacket): Boolean = false
        override fun handleNoiseEncrypted(routed: RoutedPacket) {}
        override fun handleAnnounce(routed: RoutedPacket) {}
        override fun handleMessage(routed: RoutedPacket) {}
        override fun handleLeave(routed: RoutedPacket) {}
        override fun handleFragment(packet: BluewhalePacket): BluewhalePacket? = null
        override fun handleRequestSync(routed: RoutedPacket) {}
        override fun sendAnnouncementToPeer(peerID: String) {}
        override fun sendCachedMessages(peerID: String) {}
        override fun relayPacket(routed: RoutedPacket) {}
        override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean = false
    }

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

    /**
     * A burst above the per-peer queue size must not be thrown away. Fragments are
     * reassembled from a complete run, so one lost packet strands the whole transfer.
     */
    @Test
    fun `a burst larger than the queue is not silently dropped`() {
        val processor = PacketProcessor("00000000deadbeef")
        val seen = java.util.concurrent.atomic.AtomicInteger()
        val gate = java.util.concurrent.CountDownLatch(1)
        processor.delegate = CountingDelegate(seen, gate)

        val burst = 700
        val peer = peerID(1)
        // Held at the first packet so the queue fills behind it.
        repeat(burst) { processor.processPacket(packetFrom(peer)) }
        gate.countDown()

        val deadline = System.currentTimeMillis() + 10_000
        while (seen.get() < burst && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertEquals("every packet in the burst must reach the handler", burst, seen.get())
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

    /** Drives a full handshake, using the peer ID the peer's static key derives to. */
    private fun establish(manager: NoiseSessionManager): String {
        val (peerPriv, peerPub) = keyPair()
        val realPeerID = NoisePeerIdentity.derivePeerID(peerPub)!!
        val peer = NoiseSession(realPeerID, true, peerPriv, peerPub)
        val m1 = peer.startHandshake()
        val m2 = manager.processHandshakeMessage(realPeerID, m1)!!
        val m3 = peer.processHandshakeMessage(m2)!!
        manager.processHandshakeMessage(realPeerID, m3)
        return realPeerID
    }

    @Test
    fun `a completed session is not evicted by later handshake floods`() {
        val (priv, pub) = keyPair()
        val manager = NoiseSessionManager(priv, pub)

        val realPeerID = establish(manager)
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

    @Test
    fun `candidate handshakes count against the handshake bound`() {
        val (priv, pub) = keyPair()
        val manager = NoiseSessionManager(priv, pub)

        // Each established peer can have a replacement handshake negotiated alongside it.
        // Those live in a separate map, so a bound that only counts the main one leaves
        // them growing freely off unauthenticated packets.
        val established = (0 until 60).map { establish(manager) }

        established.forEach { peer ->
            val (p, q) = keyPair()
            val challenger = NoiseSession(peer, true, p, q)
            runCatching { manager.processHandshakeMessage(peer, challenger.startHandshake()) }
        }

        val candidates = Regex("Candidate sessions: (\\d+)")
            .find(manager.getDebugInfo())!!
            .groupValues[1]
            .toInt()

        assertTrue("candidate count $candidates must stay bounded", candidates <= 32)

        // The bound must come out of the candidates, not the working sessions.
        assertTrue(
            "established sessions must survive",
            established.count { manager.hasEstablishedSession(it) } == established.size
        )
        manager.shutdown()
    }
}
