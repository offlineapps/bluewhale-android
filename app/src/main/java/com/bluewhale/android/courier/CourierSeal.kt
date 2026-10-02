package com.bluewhale.android.courier

import com.bluewhale.android.noise.southernstorm.protocol.HandshakeState

/**
 * One-shot end-to-end encryption for courier envelopes, with no round trip: the recipient may be
 * hours and several carriers away.
 *
 * Uses the one-way Noise pattern X (`-> e, es, s, ss`) with the same primitives as the live
 * Noise XX sessions. The sender's static key travels encrypted, so carriers do not learn who
 * wrote the envelope, and the `ss` term authenticates the sender to the recipient. The envelope
 * header is the prologue, so a carrier that alters expiry, priority or the recipient tag makes
 * the envelope undecryptable.
 *
 * Like every one-way pattern, X has no forward secrecy against later theft of the recipient's
 * static key, and replays are possible; the envelope ID handles duplicates.
 */
object CourierSeal {

    private const val PROTOCOL = "Noise_X_25519_ChaChaPoly_SHA256"
    private const val KEY_SIZE = 32
    // e (32) + encrypted s (32 + 16) + payload tag (16)
    const val OVERHEAD = 32 + 48 + 16

    class Opened(val senderStaticKey: ByteArray, val plaintext: ByteArray)

    fun seal(
        senderStaticPrivateKey: ByteArray,
        recipientStaticPublicKey: ByteArray,
        plaintext: ByteArray,
        prologue: ByteArray
    ): ByteArray {
        require(recipientStaticPublicKey.size == KEY_SIZE) { "recipient key must be $KEY_SIZE bytes" }
        val hs = HandshakeState(PROTOCOL, HandshakeState.INITIATOR)
        try {
            hs.setPrologue(prologue, 0, prologue.size)
            hs.localKeyPair.setPrivateKey(senderStaticPrivateKey, 0)
            hs.remotePublicKey.setPublicKey(recipientStaticPublicKey, 0)
            hs.start()
            val out = ByteArray(plaintext.size + OVERHEAD)
            val written = hs.writeMessage(out, 0, plaintext, 0, plaintext.size)
            return out.copyOf(written)
        } finally {
            hs.destroy()
        }
    }

    /** Returns null when the envelope is not for this key, or was altered. */
    fun open(recipientStaticPrivateKey: ByteArray, sealed: ByteArray, prologue: ByteArray): Opened? {
        if (sealed.size < OVERHEAD) return null
        val hs = HandshakeState(PROTOCOL, HandshakeState.RESPONDER)
        return try {
            hs.setPrologue(prologue, 0, prologue.size)
            hs.localKeyPair.setPrivateKey(recipientStaticPrivateKey, 0)
            hs.start()
            val out = ByteArray(sealed.size)
            val read = hs.readMessage(sealed, 0, sealed.size, out, 0)
            val sender = ByteArray(KEY_SIZE)
            hs.remotePublicKey.getPublicKey(sender, 0)
            Opened(sender, out.copyOf(read))
        } catch (e: Exception) {
            null
        } finally {
            hs.destroy()
        }
    }
}
