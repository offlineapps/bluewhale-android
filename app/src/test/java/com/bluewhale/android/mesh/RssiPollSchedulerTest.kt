package com.bluewhale.android.mesh

import com.bluewhale.android.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssiPollSchedulerTest {

    @Test
    fun `polls at the normal interval while clients are connected`() {
        assertEquals(
            AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS,
            RssiPollScheduler.nextDelayMs(clientConnectionCount = 1)
        )
        assertEquals(
            AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS,
            RssiPollScheduler.nextDelayMs(clientConnectionCount = 8)
        )
    }

    @Test
    fun `backs off when no clients are connected`() {
        assertEquals(
            AppConstants.Mesh.RSSI_IDLE_INTERVAL_MS,
            RssiPollScheduler.nextDelayMs(clientConnectionCount = 0)
        )
    }

    @Test
    fun `idle interval is longer than the active interval`() {
        assertTrue(
            AppConstants.Mesh.RSSI_IDLE_INTERVAL_MS > AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS
        )
    }
}
