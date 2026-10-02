package com.bluewhale.android.mesh

import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.crypto.EncryptionService
import com.bluewhale.android.model.AnnounceOriginTtl
import com.bluewhale.android.model.IdentityAnnouncement
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import com.bluewhale.android.services.meshgraph.GossipTLV
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.security.SecureRandom

/**
 * A device with a reduced message range announces with a TTL below 7, and receivers only
 * recognised a direct neighbour's announce by TTL 7. Such a device was never bound to its
 * link, and a repeated announce from it on a new connection was dropped as a duplicate.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RangeLimitedDirectNeighbourTest {

    private val peerID = "aabbccddeeff0011"
    private val keys = Ed25519KeyPairGenerator().apply {
        init(Ed25519KeyGenerationParameters(SecureRandom()))
    }.generateKeyPair()
    private val signingKey = keys.private as Ed25519PrivateKeyParameters
    private val verifyKey = keys.public as Ed25519PublicKeyParameters

    private fun hexToBytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun announcePayload(originTtl: UByte?): ByteArray {
        val base = IdentityAnnouncement("alice", ByteArray(32) { 7 }, verifyKey.encoded).encode()!!
        val withNeighbours = base + GossipTLV.encodeNeighbors(listOf("1122334455667788"))
        return if (originTtl == null) withNeighbours else withNeighbours + AnnounceOriginTtl.encode(originTtl)
    }

    private fun announce(ttl: UByte, originTtl: UByte?): BluewhalePacket {
        val packet = BluewhalePacket(
            version = 1u,
            type = MessageType.ANNOUNCE.value,
            senderID = hexToBytes(peerID),
            recipientID = null,
            timestamp = 1_700_000_000_000L.toULong(),
            payload = announcePayload(originTtl),
            signature = null,
            ttl = ttl
        )
        val signature = Ed25519Signer().apply {
            init(true, signingKey)
            val data = packet.toBinaryDataForSigning()!!
            update(data, 0, data.size)
        }.generateSignature()
        return packet.copy(signature = signature)
    }

    private fun securityManager(): SecurityManager {
        val manager = SecurityManager(EncryptionService(ApplicationProvider.getApplicationContext()), "00000000deadbeef")
        manager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray) {}
            override fun sendHandshakeResponse(peerID: String, response: ByteArray) {}
            override fun getPeerInfo(peerID: String): PeerInfo? = null
        }
        return manager
    }

    @Test
    fun `origin TTL round trips and other announce fields still decode`() {
        val payload = announcePayload(2u)
        assertEquals(2u.toUByte(), AnnounceOriginTtl.decode(payload))
        assertNotNull("the identity decoder must skip the new field", IdentityAnnouncement.decode(payload))
        assertEquals(listOf("1122334455667788"), GossipTLV.decodeNeighborsFromAnnouncementPayload(payload))
        assertNull(AnnounceOriginTtl.decode(announcePayload(null)))
    }

    @Test
    fun `direct announces are recognised at any range and relayed ones are not`() {
        assertTrue("default range, unrelayed", AnnounceOriginTtl.arrivedUnrelayed(announce(7u, null)))
        assertFalse("default range, relayed once", AnnounceOriginTtl.arrivedUnrelayed(announce(6u, null)))
        assertTrue("range 3, unrelayed", AnnounceOriginTtl.arrivedUnrelayed(announce(2u, 2u)))
        assertFalse("range 3, relayed once", AnnounceOriginTtl.arrivedUnrelayed(announce(1u, 2u)))
        assertTrue("range 1, unrelayed", AnnounceOriginTtl.arrivedUnrelayed(announce(0u, 0u)))
    }

    @Test
    fun `a repeated announce from a range-limited neighbour is accepted for rebinding`() {
        val manager = securityManager()
        val direct = announce(2u, 2u)
        assertTrue(manager.validatePacket(direct, peerID))
        assertTrue(
            "the same announce on a new link must get through to bind the link",
            manager.validatePacket(direct, peerID)
        )
        assertFalse("a relayed copy is still a duplicate", manager.validatePacket(direct.copy(ttl = 1u), peerID))
    }
}
