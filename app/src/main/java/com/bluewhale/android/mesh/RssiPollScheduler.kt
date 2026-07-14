package com.bluewhale.android.mesh

import com.bluewhale.android.util.AppConstants

/**
 * Chooses how long the RSSI monitoring loop sleeps between passes.
 * With no client connections there is nothing to read, so the loop backs
 * off instead of waking every few seconds.
 */
object RssiPollScheduler {

    fun nextDelayMs(clientConnectionCount: Int): Long {
        return if (clientConnectionCount > 0) {
            AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS
        } else {
            AppConstants.Mesh.RSSI_IDLE_INTERVAL_MS
        }
    }
}
