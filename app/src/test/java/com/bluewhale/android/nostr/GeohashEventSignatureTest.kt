package com.bluewhale.android.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * A relay can hand us any bytes it likes, so an event's pubkey is only meaningful
 * once its Schnorr signature has been checked against it.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GeohashEventSignatureTest {

    private fun identity(): NostrIdentity {
        val (priv, _) = NostrCrypto.generateKeyPair()
        return NostrIdentity.fromPrivateKey(priv)
    }

    private fun geohashEvent(author: NostrIdentity, content: String, geohash: String = "u4pruy") =
        NostrEvent(
            pubkey = author.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.EPHEMERAL_EVENT,
            tags = listOf(listOf("g", geohash), listOf("n", "alice")),
            content = content
        )

    @Test
    fun `genuine event passes`() {
        val alice = identity()
        val signed = geohashEvent(alice, "hello").sign(alice.privateKeyHex)

        assertTrue(signed.isValidSignature())
    }

    @Test
    fun `event attributed to another pubkey fails`() {
        val alice = identity()
        val mallory = identity()

        // Mallory signs, then swaps in Alice's pubkey to impersonate her.
        val signed = geohashEvent(mallory, "meet at the east gate").sign(mallory.privateKeyHex)
        val forged = signed.copy(pubkey = alice.publicKeyHex)

        assertFalse("an event may not claim a pubkey it was not signed by", forged.isValidSignature())
    }

    @Test
    fun `edited content fails`() {
        val alice = identity()
        val signed = geohashEvent(alice, "meet at 6").sign(alice.privateKeyHex)
        val tampered = signed.copy(content = "meet at 9")

        assertFalse("a relay may not rewrite content", tampered.isValidSignature())
    }

    @Test
    fun `edited tags fail`() {
        val alice = identity()
        val signed = geohashEvent(alice, "hello").sign(alice.privateKeyHex)
        val moved = signed.copy(tags = listOf(listOf("g", "gcpvj"), listOf("n", "alice")))

        assertFalse("a relay may not move an event to another geohash", moved.isValidSignature())
    }

    @Test
    fun `unsigned event fails`() {
        val alice = identity()
        val unsigned = geohashEvent(alice, "hello").let { it.copy(id = it.computeEventIdHex()) }

        assertFalse("an event with no signature must not be accepted", unsigned.isValidSignature())
    }

    @Test
    fun `event whose id does not match its content fails`() {
        val alice = identity()
        val signed = geohashEvent(alice, "hello").sign(alice.privateKeyHex)
        val wrongId = signed.copy(id = "00".repeat(32))

        assertFalse("the id must commit to the signed content", wrongId.isValidSignature())
    }
}
