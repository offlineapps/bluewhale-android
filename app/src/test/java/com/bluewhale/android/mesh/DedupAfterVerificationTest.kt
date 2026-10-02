package com.bluewhale.android.mesh

import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.crypto.EncryptionService
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.security.SecureRandom

/**
 * The duplicate filter keys on timestamp, sender and a hash of the first 64 payload bytes;
 * it ignores the signature. Packets used to be recorded as seen before their signature
 * was checked, so a copy that failed verification blocked the authentic one.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DedupAfterVerificationTest {

    private val alicePeerID = "aabbccddeeff0011"

    private val keys = Ed25519KeyPairGenerator().apply {
        init(Ed25519KeyGenerationParameters(SecureRandom()))
    }.generateKeyPair()
    private val alicePrivate = keys.private as Ed25519PrivateKeyParameters
    private val alicePublic = keys.public as Ed25519PublicKeyParameters

    private fun hexToBytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun alice() = PeerInfo(
        id = alicePeerID,
        nickname = "alice",
        isConnected = true,
        isDirectConnection = true,
        noisePublicKey = ByteArray(32) { 1 },
        signingPublicKey = alicePublic.encoded,
        isVerifiedNickname = true,
        lastSeen = System.currentTimeMillis()
    )

    private fun manager(peerLookup: () -> PeerInfo?): SecurityManager {
        val manager = SecurityManager(EncryptionService(ApplicationProvider.getApplicationContext()), "00000000deadbeef")
        manager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray) {}
            override fun sendHandshakeResponse(peerID: String, response: ByteArray) {}
            override fun getPeerInfo(peerID: String): PeerInfo? = peerLookup()
        }
        return manager
    }

    private fun signedMessage(): BluewhalePacket {
        val packet = BluewhalePacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexToBytes(alicePeerID),
            recipientID = null,
            timestamp = System.currentTimeMillis().toULong(),
            payload = "meet at the north gate".toByteArray(),
            signature = null,
            ttl = 7u
        )
        val signature = Ed25519Signer().apply {
            init(true, alicePrivate)
            val data = packet.toBinaryDataForSigning()!!
            update(data, 0, data.size)
        }.generateSignature()
        return packet.copy(signature = signature)
    }

    @Test
    fun `a copy with a broken signature does not suppress the genuine packet`() {
        val manager = manager { alice() }
        val genuine = signedMessage()
        // What a hostile relay, or a corrupted link, delivers first.
        val tampered = genuine.copy(signature = ByteArray(64) { 0x5A })

        assertFalse(manager.validatePacket(tampered, alicePeerID))
        assertTrue(
            "the authentic packet must still be accepted after a forged copy",
            manager.validatePacket(genuine, alicePeerID)
        )
    }

    @Test
    fun `a packet seen before the sender's key is known is accepted once the key arrives`() {
        var known: PeerInfo? = null
        val manager = manager { known }
        val packet = signedMessage()

        assertFalse("nothing to verify against yet", manager.validatePacket(packet, alicePeerID))

        known = alice() // the announce lands
        assertTrue(
            "a later copy, e.g. via another relay or gossip sync, must be accepted",
            manager.validatePacket(packet, alicePeerID)
        )
    }

    @Test
    fun `genuine duplicates are still dropped`() {
        val manager = manager { alice() }
        val packet = signedMessage()

        assertTrue(manager.validatePacket(packet, alicePeerID))
        assertFalse(manager.validatePacket(packet, alicePeerID))
    }
}
