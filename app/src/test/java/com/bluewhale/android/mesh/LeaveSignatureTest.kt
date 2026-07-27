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
 * A LEAVE removes the sender from the peer list of every node that sees it, and it is
 * relayed, so an unsigned one lets any device in range evict any peer mesh wide.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LeaveSignatureTest {

    private val victimPeerID = "aabbccddeeff0011"

    private val signingKeys = Ed25519KeyPairGenerator().apply {
        init(Ed25519KeyGenerationParameters(SecureRandom()))
    }.generateKeyPair()

    private val victimPrivate = signingKeys.private as Ed25519PrivateKeyParameters
    private val victimPublic = signingKeys.public as Ed25519PublicKeyParameters

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

    private fun knownVictim() = PeerInfo(
        id = victimPeerID,
        nickname = "victim",
        isConnected = true,
        isDirectConnection = true,
        noisePublicKey = ByteArray(32) { 1 },
        signingPublicKey = victimPublic.encoded,
        isVerifiedNickname = true,
        lastSeen = System.currentTimeMillis()
    )

    private fun leavePacket(signed: Boolean, timestamp: Long = System.currentTimeMillis()): BluewhalePacket {
        val packet = BluewhalePacket(
            version = 1u,
            type = MessageType.LEAVE.value,
            senderID = hexToBytes(victimPeerID),
            recipientID = null,
            timestamp = timestamp.toULong(),
            payload = ByteArray(0),
            signature = null,
            ttl = 7u
        )
        if (!signed) return packet

        val signature = Ed25519Signer().apply {
            init(true, victimPrivate)
            val data = packet.toBinaryDataForSigning()!!
            update(data, 0, data.size)
        }.generateSignature()
        return packet.copy(signature = signature)
    }

    @Test
    fun `forged unsigned leave for another peer is dropped`() {
        val manager = securityManager(knownVictim())

        assertFalse(
            "an unsigned LEAVE must not be able to evict a peer",
            manager.validatePacket(leavePacket(signed = false), victimPeerID)
        )
    }

    @Test
    fun `leave signed by someone else is dropped`() {
        val manager = securityManager(knownVictim())

        // Mallory signs with her own key while claiming the victim's peer ID.
        val mallory = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }.generateKeyPair().private as Ed25519PrivateKeyParameters

        val packet = leavePacket(signed = false)
        val forged = packet.copy(
            signature = Ed25519Signer().apply {
                init(true, mallory)
                val data = packet.toBinaryDataForSigning()!!
                update(data, 0, data.size)
            }.generateSignature()
        )

        assertFalse(
            "a LEAVE signed by the wrong key must not evict a peer",
            manager.validatePacket(forged, victimPeerID)
        )
    }

    @Test
    fun `genuine leave from a known peer is still accepted`() {
        val manager = securityManager(knownVictim())

        assertTrue(
            "a peer must still be able to announce its own departure",
            manager.validatePacket(leavePacket(signed = true), victimPeerID)
        )
    }

    @Test
    fun `leave from a peer we hold no signing key for is dropped`() {
        val manager = securityManager(knownPeer = null)

        assertFalse(
            "without a signing key there is nothing to verify against",
            manager.validatePacket(leavePacket(signed = true), victimPeerID)
        )
    }
}
