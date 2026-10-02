package com.bluewhale.android.protocol

import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.crypto.EncryptionService
import com.bluewhale.android.mesh.PeerInfo
import com.bluewhale.android.mesh.SecurityManager
import com.bluewhale.android.mesh.SecurityManagerDelegate
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.zip.Deflater

/**
 * Signatures cover a re-encoding of the packet. DEFLATE output is not canonical, and iOS
 * compresses with Apple's encoder rather than java.util.zip, so re-compressing a received
 * payload produced different bytes than the sender signed and every compressed, signed
 * packet from such a peer failed verification and was dropped.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ForeignCompressionSignatureTest {

    private val peerID = "aabbccddeeff0011"
    private val sender = ByteArray(8) { i -> peerID.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    private val keys = Ed25519KeyPairGenerator().apply {
        init(Ed25519KeyGenerationParameters(SecureRandom()))
    }.generateKeyPair()
    private val signingKey = keys.private as Ed25519PrivateKeyParameters
    private val verifyKey = keys.public as Ed25519PublicKeyParameters

    private val text = ("the north gate is open, bring water and a torch; ".repeat(8)).toByteArray()
    private val timestamp = 1_700_000_000_000L

    /** Raw DEFLATE at a level java.util.zip's default does not use: same data, other bytes. */
    private fun foreignCompress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    /** A v1 MESSAGE frame as a peer with its own encoder would build it. */
    private fun frame(compressed: ByteArray, ttl: Int, signature: ByteArray?): ByteArray {
        var flags = BinaryProtocol.Flags.IS_COMPRESSED.toInt()
        if (signature != null) flags = flags or BinaryProtocol.Flags.HAS_SIGNATURE.toInt()
        val buf = ByteBuffer.allocate(14 + 8 + 2 + compressed.size + (signature?.size ?: 0)).order(ByteOrder.BIG_ENDIAN)
        buf.put(1)
        buf.put(MessageType.MESSAGE.value.toByte())
        buf.put(ttl.toByte())
        buf.putLong(timestamp)
        buf.put(flags.toByte())
        buf.putShort((2 + compressed.size).toShort())
        buf.put(sender)
        buf.putShort(text.size.toShort())
        buf.put(compressed)
        signature?.let { buf.put(it) }
        val raw = buf.array()
        return MessagePadding.pad(raw, MessagePadding.optimalBlockSize(raw.size))
    }

    private fun signedForeignFrame(): ByteArray {
        val compressed = foreignCompress(text)
        // The preimage is the unsigned frame at TTL 0, exactly as the originator encoded it.
        val preimage = frame(compressed, ttl = 0, signature = null)
        val signature = Ed25519Signer().apply {
            init(true, signingKey)
            update(preimage, 0, preimage.size)
        }.generateSignature()
        return frame(compressed, ttl = 7, signature = signature)
    }

    private fun securityManager(): SecurityManager {
        val manager = SecurityManager(EncryptionService(ApplicationProvider.getApplicationContext()), "00000000deadbeef")
        manager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray) {}
            override fun sendHandshakeResponse(peerID: String, response: ByteArray) {}
            override fun getPeerInfo(peerID: String): PeerInfo? = PeerInfo(
                id = peerID,
                nickname = "ios peer",
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

    private fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { start ->
            needle.indices.all { haystack[start + it] == needle[it] }
        }

    @Test
    fun `the foreign encoder really produces different bytes`() {
        // Guards the premise of the other tests.
        assertFalse(foreignCompress(text).contentEquals(CompressionUtil.compress(text)!!))
    }

    @Test
    fun `a packet compressed by another encoder verifies`() {
        val packet = BinaryProtocol.decode(signedForeignFrame())
        assertNotNull(packet)
        assertArrayEquals(text, packet!!.payload)
        assertTrue(
            "a valid signature over the sender's own compressed bytes must verify",
            securityManager().validatePacket(packet, peerID)
        )
    }

    @Test
    fun `a relay forwards the originator's compressed bytes`() {
        val packet = BinaryProtocol.decode(signedForeignFrame())!!
        val relayed = packet.copy(ttl = 6u).toBinaryData()!!
        assertTrue(containsSubarray(relayed, foreignCompress(text)))

        // And the relayed copy still verifies downstream.
        assertTrue(securityManager().validatePacket(BinaryProtocol.decode(relayed)!!, peerID))
    }

    @Test
    fun `a replaced payload is compressed afresh`() {
        val packet = BinaryProtocol.decode(signedForeignFrame())!!
        val edited = packet.copy(payload = text.copyOf())
        val encoded = edited.toBinaryData()!!
        assertTrue(containsSubarray(encoded, CompressionUtil.compress(text)!!))
    }
}
