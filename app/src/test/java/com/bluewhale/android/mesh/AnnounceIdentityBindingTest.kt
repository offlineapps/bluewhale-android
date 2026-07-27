package com.bluewhale.android.mesh

import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.model.BluewhaleMessage
import com.bluewhale.android.model.IdentityAnnouncement
import com.bluewhale.android.model.RoutedPacket
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * An ANNOUNCE may only claim the peer ID that its own announced Noise key
 * hashes to. Without that binding any peer can take over another peer's ID
 * by announcing it with self-generated keys.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AnnounceIdentityBindingTest {

    private class RecordingDelegate : MessageHandlerDelegate {
        var acceptedPeerID: String? = null
        var acceptedNickname: String? = null

        override fun updatePeerInfo(
            peerID: String,
            nickname: String,
            noisePublicKey: ByteArray,
            signingPublicKey: ByteArray,
            isVerified: Boolean
        ): Boolean {
            acceptedPeerID = peerID
            acceptedNickname = nickname
            return true
        }

        override fun verifyEd25519Signature(
            signature: ByteArray,
            data: ByteArray,
            publicKey: ByteArray
        ): Boolean = try {
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(data, 0, data.size)
            }.verifySignature(signature)
        } catch (e: Exception) {
            false
        }

        override fun addOrUpdatePeer(peerID: String, nickname: String) = true
        override fun removePeer(peerID: String) {}
        override fun updatePeerNickname(peerID: String, nickname: String) {}
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize(): Int = 1
        override fun getMyNickname(): String? = "me"
        override fun getPeerInfo(peerID: String): PeerInfo? = null
        override fun sendPacket(packet: BluewhalePacket) {}
        override fun relayPacket(routed: RoutedPacket) {}
        override fun getBroadcastRecipient(): ByteArray = ByteArray(8) { 0xFF.toByte() }
        override fun verifySignature(packet: BluewhalePacket, peerID: String) = true
        override fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? = null
        override fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? = null
        override fun hasNoiseSession(peerID: String) = false
        override fun initiateNoiseHandshake(peerID: String) {}
        override fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray? = null
        override fun updatePeerIDBinding(
            newPeerID: String,
            nickname: String,
            publicKey: ByteArray,
            previousPeerID: String?
        ) {}
        override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
        override fun onMessageReceived(message: BluewhaleMessage) {}
        override fun onChannelLeave(channel: String, fromPeer: String) {}
        override fun onDeliveryAckReceived(messageID: String, peerID: String) {}
        override fun onReadReceiptReceived(messageID: String, peerID: String) {}
        override fun onVerifyChallengeReceived(peerID: String, payload: ByteArray, timestampMs: Long) {}
        override fun onVerifyResponseReceived(peerID: String, payload: ByteArray, timestampMs: Long) {}
    }

    private fun derivePeerID(noiseKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(noiseKey)
            .take(8).joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Builds a correctly self-signed announce that claims [claimedPeerID]. */
    private fun signedAnnounce(claimedPeerID: String, noiseKey: ByteArray): BluewhalePacket {
        val keyGen = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }
        val pair = keyGen.generateKeyPair()
        val signPriv = pair.private as Ed25519PrivateKeyParameters
        val signPub = pair.public as Ed25519PublicKeyParameters

        val payload = IdentityAnnouncement("mallory", noiseKey, signPub.encoded).encode()!!
        val packet = BluewhalePacket(
            version = 1u,
            type = MessageType.ANNOUNCE.value,
            senderID = hexToBytes(claimedPeerID),
            recipientID = null,
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            signature = null,
            ttl = 7u
        )
        val signature = Ed25519Signer().apply {
            init(true, signPriv)
            val data = packet.toBinaryDataForSigning()!!
            update(data, 0, data.size)
        }.generateSignature()
        return packet.copy(signature = signature)
    }

    private fun handle(packet: BluewhalePacket, fromPeerID: String): RecordingDelegate {
        val delegate = RecordingDelegate()
        val handler = MessageHandler("00000000deadbeef", ApplicationProvider.getApplicationContext())
        handler.delegate = delegate
        runBlocking { handler.handleAnnounce(RoutedPacket(packet, fromPeerID, null)) }
        return delegate
    }

    @Test
    fun `announce bound to its own noise key is accepted`() {
        val noiseKey = ByteArray(32) { (it + 1).toByte() }
        val peerID = derivePeerID(noiseKey)

        val delegate = handle(signedAnnounce(peerID, noiseKey), peerID)

        assertEquals(peerID, delegate.acceptedPeerID)
        assertEquals("mallory", delegate.acceptedNickname)
    }

    @Test
    fun `announce claiming another peers id is rejected`() {
        val victimNoiseKey = ByteArray(32) { (it + 1).toByte() }
        val victimPeerID = derivePeerID(victimNoiseKey)

        // Mallory keeps her own Noise key but claims the victim's peer ID, and
        // signs the announce with her own signing key so the signature is valid.
        val malloryNoiseKey = ByteArray(32) { (it + 99).toByte() }
        val delegate = handle(signedAnnounce(victimPeerID, malloryNoiseKey), victimPeerID)

        assertNull("spoofed announce must not update peer state", delegate.acceptedPeerID)
    }
}
