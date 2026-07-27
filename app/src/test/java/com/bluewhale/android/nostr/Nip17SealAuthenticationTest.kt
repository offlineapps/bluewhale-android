package com.bluewhale.android.nostr

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * A NIP-17 rumor is unsigned, so its `pubkey` field is only meaningful if the
 * seal around it was signed by that same key. Without that check any sender can
 * have a message attributed to somebody else.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class Nip17SealAuthenticationTest {

    private val gson = Gson()

    private fun identity(): NostrIdentity {
        val (priv, _) = NostrCrypto.generateKeyPair()
        return NostrIdentity.fromPrivateKey(priv)
    }

    /**
     * Builds a gift wrap for [recipient] whose seal is signed by [sealer] but whose
     * rumor names [claimedAuthor] as the author.
     */
    private fun forgedGiftWrap(
        recipient: NostrIdentity,
        sealer: NostrIdentity,
        claimedAuthor: String,
        content: String
    ): NostrEvent {
        val rumorBase = NostrEvent(
            pubkey = claimedAuthor,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.DIRECT_MESSAGE,
            tags = listOf(listOf("p", recipient.publicKeyHex)),
            content = content
        )
        val rumor = rumorBase.copy(id = rumorBase.computeEventIdHex())

        val seal = NostrEvent(
            pubkey = sealer.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.SEAL,
            tags = emptyList(),
            content = NostrCrypto.encryptNIP44(
                plaintext = gson.toJson(rumor),
                recipientPublicKeyHex = recipient.publicKeyHex,
                senderPrivateKeyHex = sealer.privateKeyHex
            )
        ).sign(sealer.privateKeyHex)

        val (wrapPriv, wrapPub) = NostrCrypto.generateKeyPair()
        return NostrEvent(
            pubkey = wrapPub,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.GIFT_WRAP,
            tags = listOf(listOf("p", recipient.publicKeyHex)),
            content = NostrCrypto.encryptNIP44(
                plaintext = gson.toJson(seal),
                recipientPublicKeyHex = recipient.publicKeyHex,
                senderPrivateKeyHex = wrapPriv
            )
        ).sign(wrapPriv)
    }

    @Test
    fun `genuine message decrypts and reports its real sender`() {
        val alice = identity()
        val bob = identity()

        val wraps = NostrProtocol.createPrivateMessage("hello bob", bob.publicKeyHex, alice)
        val result = NostrProtocol.decryptPrivateMessage(wraps.first(), bob)

        assertNotNull("genuine NIP-17 message must still decrypt", result)
        assertEquals("hello bob", result!!.first)
        assertEquals(alice.publicKeyHex, result.second)
    }

    @Test
    fun `message whose rumor claims another author is rejected`() {
        val alice = identity()
        val bob = identity()
        val mallory = identity()

        // Mallory seals with her own key but writes Alice's pubkey into the rumor.
        val forged = forgedGiftWrap(
            recipient = bob,
            sealer = mallory,
            claimedAuthor = alice.publicKeyHex,
            content = "transfer the funds, it is fine"
        )

        assertNull(
            "a rumor authored by someone the seal was not signed by must be dropped",
            NostrProtocol.decryptPrivateMessage(forged, bob)
        )
    }

    @Test
    fun `self consistent message from mallory is still accepted as mallory`() {
        val bob = identity()
        val mallory = identity()

        val honest = forgedGiftWrap(
            recipient = bob,
            sealer = mallory,
            claimedAuthor = mallory.publicKeyHex,
            content = "hi from mallory"
        )
        val result = NostrProtocol.decryptPrivateMessage(honest, bob)

        assertNotNull(result)
        assertEquals(mallory.publicKeyHex, result!!.second)
    }
}
