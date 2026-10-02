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
 * Duplicate detection keyed on a 32-bit hash of at most the first 64 payload bytes, so
 * two different messages from one peer in the same millisecond that shared that prefix
 * were treated as one and the second was silently dropped.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PacketIdentityDedupTest {

    private val peerID = "aabbccddeeff0011"
    private val keys = Ed25519KeyPairGenerator().apply {
        init(Ed25519KeyGenerationParameters(SecureRandom()))
    }.generateKeyPair()
    private val signingKey = keys.private as Ed25519PrivateKeyParameters
    private val verifyKey = keys.public as Ed25519PublicKeyParameters

    private fun hexToBytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun manager(): SecurityManager {
        val manager = SecurityManager(EncryptionService(ApplicationProvider.getApplicationContext()), "00000000deadbeef")
        manager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray) {}
            override fun sendHandshakeResponse(peerID: String, response: ByteArray) {}
            override fun getPeerInfo(peerID: String): PeerInfo? = PeerInfo(
                id = peerID,
                nickname = "alice",
                isConnected = true,
                isDirectConnection = true,
                noisePublicKey = ByteArray(32) { 1 },
                signingPublicKey = verifyKey.encoded,
                isVerifiedNickname = true,
                lastSeen = System.currentTimeMillis()
            )
        }
        return manager
    }

    private fun signed(payload: ByteArray, timestamp: Long): BluewhalePacket {
        val packet = BluewhalePacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexToBytes(peerID),
            recipientID = null,
            timestamp = timestamp.toULong(),
            payload = payload,
            signature = null,
            ttl = 7u
        )
        val signature = Ed25519Signer().apply {
            init(true, signingKey)
            val data = packet.toBinaryDataForSigning()!!
            update(data, 0, data.size)
        }.generateSignature()
        return packet.copy(signature = signature)
    }

    @Test
    fun `two messages sharing a long prefix in the same millisecond are both accepted`() {
        val manager = manager()
        val prefix = "x".repeat(64)
        val now = System.currentTimeMillis()

        assertTrue(manager.validatePacket(signed("${prefix}first".toByteArray(), now), peerID))
        assertTrue(
            "a different message must not be mistaken for a duplicate",
            manager.validatePacket(signed("${prefix}second".toByteArray(), now), peerID)
        )
    }

    @Test
    fun `a relayed copy with a lower TTL is still a duplicate`() {
        val manager = manager()
        val packet = signed("hello".toByteArray(), System.currentTimeMillis())

        assertTrue(manager.validatePacket(packet, peerID))
        assertFalse(manager.validatePacket(packet.copy(ttl = 3u), peerID))
    }
}
