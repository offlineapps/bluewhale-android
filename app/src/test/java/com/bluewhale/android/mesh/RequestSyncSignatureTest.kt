package com.bluewhale.android.mesh

import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.crypto.EncryptionService
import com.bluewhale.android.model.RequestSyncPacket
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
 * Answering a REQUEST_SYNC hands over cached announcements and broadcast messages,
 * so the request has to come from a peer we hold a signing key for.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RequestSyncSignatureTest {

    private val peerID = "aabbccddeeff0011"

    private val keys = Ed25519KeyPairGenerator().apply {
        init(Ed25519KeyGenerationParameters(SecureRandom()))
    }.generateKeyPair()

    private val peerPrivate = keys.private as Ed25519PrivateKeyParameters
    private val peerPublic = keys.public as Ed25519PublicKeyParameters

    private fun hexToBytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun securityManager(knownPeer: PeerInfo?): SecurityManager {
        val service = EncryptionService(ApplicationProvider.getApplicationContext())
        val manager = SecurityManager(service, "00000000deadbeef")
        manager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray) {}
            override fun sendHandshakeResponse(peerID: String, response: ByteArray) {}
            override fun getPeerInfo(peerID: String): PeerInfo? = knownPeer
        }
        return manager
    }

    private fun knownPeer() = PeerInfo(
        id = peerID,
        nickname = "peer",
        isConnected = true,
        isDirectConnection = true,
        noisePublicKey = ByteArray(32) { 1 },
        signingPublicKey = peerPublic.encoded,
        isVerifiedNickname = true,
        lastSeen = System.currentTimeMillis()
    )

    /** An empty filter, which is what an attacker sends to be told everything. */
    private fun syncPacket(nonce: Byte): BluewhalePacket = BluewhalePacket(
        version = 1u,
        type = MessageType.REQUEST_SYNC.value,
        senderID = hexToBytes(peerID),
        recipientID = null,
        timestamp = System.currentTimeMillis().toULong(),
        payload = RequestSyncPacket(p = 1, m = 1L, data = byteArrayOf(nonce)).encode(),
        signature = null,
        ttl = 0u
    )

    private fun sign(packet: BluewhalePacket, key: Ed25519PrivateKeyParameters) = packet.copy(
        signature = Ed25519Signer().apply {
            init(true, key)
            val data = packet.toBinaryDataForSigning()!!
            update(data, 0, data.size)
        }.generateSignature()
    )

    @Test
    fun `unsigned sync request is refused`() {
        val manager = securityManager(knownPeer())

        assertFalse(
            "an unsigned request must not pull cached history",
            manager.validatePacket(syncPacket(1), peerID)
        )
    }

    @Test
    fun `sync request signed by the wrong key is refused`() {
        val manager = securityManager(knownPeer())
        val mallory = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }.generateKeyPair().private as Ed25519PrivateKeyParameters

        assertFalse(
            "a request signed by someone else must not pull cached history",
            manager.validatePacket(sign(syncPacket(2), mallory), peerID)
        )
    }

    @Test
    fun `sync request from an unknown peer is refused`() {
        val manager = securityManager(knownPeer = null)

        assertFalse(
            "without a signing key there is nothing to verify against",
            manager.validatePacket(sign(syncPacket(3), peerPrivate), peerID)
        )
    }

    @Test
    fun `genuine sync request from a known peer still works`() {
        val manager = securityManager(knownPeer())

        assertTrue(
            "normal sync between known peers must keep working",
            manager.validatePacket(sign(syncPacket(4), peerPrivate), peerID)
        )
    }
}
