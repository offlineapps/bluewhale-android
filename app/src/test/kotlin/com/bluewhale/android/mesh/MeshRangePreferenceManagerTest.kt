package com.bluewhale.android.mesh

import com.bluewhale.android.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MeshRangePreferenceManagerTest {

    @Before
    fun setUp() {
        MeshRangePreferenceManager.resetForTesting()
    }

    @Test
    fun `default range keeps the historical message ttl`() {
        assertEquals(AppConstants.MAX_RANGE_HOPS, MeshRangePreferenceManager.rangeHops.value)
        assertEquals(AppConstants.MESSAGE_TTL_HOPS, MeshRangePreferenceManager.currentTtl())
    }

    @Test
    fun `range of one hop never gets relayed`() {
        MeshRangePreferenceManager.setRangeHops(1)

        assertEquals(0u.toUByte(), MeshRangePreferenceManager.currentTtl())
    }

    @Test
    fun `range of two hops is relayed once`() {
        MeshRangePreferenceManager.setRangeHops(2)

        assertEquals(1u.toUByte(), MeshRangePreferenceManager.currentTtl())
    }

    @Test
    fun `range is clamped to the supported hops`() {
        MeshRangePreferenceManager.setRangeHops(0)
        assertEquals(AppConstants.MIN_RANGE_HOPS, MeshRangePreferenceManager.rangeHops.value)

        MeshRangePreferenceManager.setRangeHops(99)
        assertEquals(AppConstants.MAX_RANGE_HOPS, MeshRangePreferenceManager.rangeHops.value)
    }

    @Test
    fun `limitTtl lowers a packet to the configured range`() {
        MeshRangePreferenceManager.setRangeHops(2)

        assertEquals(1u.toUByte(), MeshRangePreferenceManager.limitTtl(AppConstants.MESSAGE_TTL_HOPS))
    }

    @Test
    fun `limitTtl never raises a packet that asks for fewer hops`() {
        MeshRangePreferenceManager.setRangeHops(AppConstants.MAX_RANGE_HOPS)

        assertEquals(AppConstants.SYNC_TTL_HOPS, MeshRangePreferenceManager.limitTtl(AppConstants.SYNC_TTL_HOPS))
    }
}
