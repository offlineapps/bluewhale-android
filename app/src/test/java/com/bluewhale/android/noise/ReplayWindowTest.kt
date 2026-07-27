package com.bluewhale.android.noise

import com.bluewhale.android.noise.southernstorm.protocol.Noise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * Transport messages carry their nonce, so replay protection is the sliding window and
 * nothing else. Every mesh packet passes through relays and BLE is a broadcast medium, so
 * any peer can capture a ciphertext and send it back.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ReplayWindowTest {

    private fun staticKeyPair(): Pair<ByteArray, ByteArray> {
        val dh = Noise.createDH("25519")
        dh.generateKeyPair()
        val priv = ByteArray(32)
        val pub = ByteArray(32)
        dh.getPrivateKey(priv, 0)
        dh.getPublicKey(pub, 0)
        dh.destroy()
        return priv to pub
    }

    /** Two sessions that have completed an XX handshake with each other. */
    private fun handshakePair(): Pair<NoiseSession, NoiseSession> {
        val (aPriv, aPub) = staticKeyPair()
        val (bPriv, bPub) = staticKeyPair()
        val initiator = NoiseSession("peerB", true, aPriv, aPub)
        val responder = NoiseSession("peerA", false, bPriv, bPub)

        val m1 = initiator.startHandshake()
        val m2 = responder.processHandshakeMessage(m1)!!
        val m3 = initiator.processHandshakeMessage(m2)!!
        responder.processHandshakeMessage(m3)

        assertTrue(initiator.isEstablished())
        assertTrue(responder.isEstablished())
        return initiator to responder
    }

    private fun assertRejected(receiver: NoiseSession, ciphertext: ByteArray, message: String) {
        assertThrows(message, Exception::class.java) { receiver.decrypt(ciphertext) }
    }

    @Test
    fun `replaying a delivered message is rejected`() {
        val (sender, receiver) = handshakePair()
        val first = sender.encrypt("first message".toByteArray())
        val second = sender.encrypt("second message".toByteArray())

        assertEquals("first message", String(receiver.decrypt(first)))
        assertEquals("second message", String(receiver.decrypt(second)))

        assertRejected(receiver, first, "replay of nonce 0 after nonce 1 must be rejected")
        assertRejected(receiver, second, "replay of the highest nonce must be rejected")
    }

    @Test
    fun `replay is rejected across gaps that are not byte aligned`() {
        // The previous implementation shifted the window the wrong way, so any advance
        // that was not a multiple of 8 discarded the history it had just recorded.
        for (gap in 1..17) {
            val (sender, receiver) = handshakePair()
            val messages = (0..gap).map { sender.encrypt("m$it".toByteArray()) }

            receiver.decrypt(messages[0])
            receiver.decrypt(messages[gap])

            assertRejected(receiver, messages[0], "replay of nonce 0 after a gap of $gap must be rejected")
            assertRejected(receiver, messages[gap], "replay of nonce $gap must be rejected")
        }
    }

    @Test
    fun `out of order delivery inside the window is still accepted once`() {
        val (sender, receiver) = handshakePair()
        val messages = (0..40).map { sender.encrypt("m$it".toByteArray()) }

        // Arrive newest first, which the mesh does whenever relays reorder.
        assertEquals("m40", String(receiver.decrypt(messages[40])))
        assertEquals("m7", String(receiver.decrypt(messages[7])))
        assertEquals("m0", String(receiver.decrypt(messages[0])))
        assertEquals("m23", String(receiver.decrypt(messages[23])))

        // Each of them only once.
        assertRejected(receiver, messages[7], "nonce 7 was already delivered")
        assertRejected(receiver, messages[0], "nonce 0 was already delivered")
        assertRejected(receiver, messages[23], "nonce 23 was already delivered")

        // Untouched nonces inside the window still arrive.
        assertEquals("m12", String(receiver.decrypt(messages[12])))
    }

    @Test
    fun `history survives many small advances`() {
        val (sender, receiver) = handshakePair()
        val messages = (0..300).map { sender.encrypt("m$it".toByteArray()) }

        for (i in 0..300) {
            assertEquals("m$i", String(receiver.decrypt(messages[i])))
        }
        // Everything still inside the 1024 entry window must be remembered.
        for (i in 0..300) {
            assertRejected(receiver, messages[i], "nonce $i was already delivered")
        }
    }

    @Test
    fun `nonce older than the window is rejected`() {
        val (sender, receiver) = handshakePair()
        val old = sender.encrypt("old".toByteArray())
        // Push the window past the first message.
        val recent = (1..1100).map { sender.encrypt("m$it".toByteArray()) }
        receiver.decrypt(recent.last())

        assertRejected(receiver, old, "a nonce more than 1024 behind must be rejected")
    }
}
