package com.bluewhale.android.mesh

import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.model.IdentityAnnouncement
import com.bluewhale.android.services.meshgraph.GossipTLV
import com.bluewhale.android.ui.debug.DebugPreferenceManager
import com.bluewhale.android.ui.debug.DebugSettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * The neighbour list in an announce tells the whole mesh which people are physically
 * next to each other, so it must not be sent unless it has been turned on.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class NeighborGossipTest {

    @Before
    fun setup() {
        DebugPreferenceManager.init(ApplicationProvider.getApplicationContext())
    }

    private fun announcementPayload(neighbors: List<String>): ByteArray {
        val base = IdentityAnnouncement("me", ByteArray(32) { 1 }, ByteArray(32) { 2 }).encode()!!
        return base + GossipTLV.encodeNeighbors(neighbors)
    }

    @Test
    fun `gossip is off by default`() {
        assertEquals(false, DebugPreferenceManager.getGossipNeighborsEnabled())
    }

    @Test
    fun `an announce without the tlv exposes no neighbours`() {
        val payload = IdentityAnnouncement("me", ByteArray(32) { 1 }, ByteArray(32) { 2 }).encode()!!

        assertNull(
            "no neighbour TLV means nothing to read",
            GossipTLV.decodeNeighborsFromAnnouncementPayload(payload)
        )
    }

    @Test
    fun `an announce with the tlv exposes the neighbour list`() {
        val neighbors = listOf("aabbccddeeff0011", "1122334455667788")
        val decoded = GossipTLV.decodeNeighborsFromAnnouncementPayload(announcementPayload(neighbors))

        assertNotNull(decoded)
        assertEquals(neighbors, decoded)
    }

    @Test
    fun `enabling the setting is what turns it on`() {
        DebugPreferenceManager.setGossipNeighborsEnabled(true)
        assertEquals(true, DebugPreferenceManager.getGossipNeighborsEnabled())

        DebugPreferenceManager.setGossipNeighborsEnabled(false)
        assertEquals(false, DebugPreferenceManager.getGossipNeighborsEnabled())
    }

    /**
     * The switch in the debug sheet drives DebugSettingsManager, which is what has to
     * reach the stored preference. Without that link the setting is off for good and the
     * mesh graph, source routing and the range warning stay dead with no way to enable it.
     */
    @Test
    fun `the debug settings manager is what the sheet can toggle`() {
        val manager = DebugSettingsManager.getInstance()

        manager.setGossipNeighborsEnabled(true)
        assertEquals("state the switch renders from", true, manager.gossipNeighborsEnabled.value)
        assertEquals(
            "the value the mesh service reads when building an announce",
            true,
            DebugPreferenceManager.getGossipNeighborsEnabled()
        )

        manager.setGossipNeighborsEnabled(false)
        assertEquals(false, manager.gossipNeighborsEnabled.value)
        assertEquals(false, DebugPreferenceManager.getGossipNeighborsEnabled())
    }
}
