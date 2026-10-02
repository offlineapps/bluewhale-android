package com.bluewhale.android.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AnnounceL2capPsmTest {

    private val identity = IdentityAnnouncement("alice", ByteArray(32) { 1 }, ByteArray(32) { 2 })

    @Test
    fun `psm round trips from an announce payload`() {
        val payload = identity.encode()!! + AnnounceL2capPsm.encode(0x0085)

        assertEquals(0x0085, AnnounceL2capPsm.decode(payload))
    }

    @Test
    fun `peers that do not know the tlv still read the identity`() {
        val payload = identity.encode()!! + AnnounceL2capPsm.encode(0x0085)
        val decoded = IdentityAnnouncement.decode(payload)

        assertNotNull(decoded)
        assertEquals("alice", decoded!!.nickname)
        assertArrayEquals(identity.noisePublicKey, decoded.noisePublicKey)
    }

    @Test
    fun `found after other trailing tlvs too`() {
        val otherTlv = byteArrayOf(0x30, 3, 9, 9, 9)
        val payload = identity.encode()!! + otherTlv + AnnounceL2capPsm.encode(0x00C1)

        assertEquals(0x00C1, AnnounceL2capPsm.decode(payload))
    }

    @Test
    fun `absent or malformed gives null`() {
        assertNull(AnnounceL2capPsm.decode(identity.encode()!!))
        assertNull("wrong length", AnnounceL2capPsm.decode(byteArrayOf(0x42, 1, 5)))
        assertNull("truncated", AnnounceL2capPsm.decode(byteArrayOf(0x42, 2, 0)))
        assertNull("zero psm", AnnounceL2capPsm.decode(byteArrayOf(0x42, 2, 0, 0)))
    }
}
