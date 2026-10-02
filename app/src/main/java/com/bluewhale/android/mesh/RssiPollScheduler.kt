package com.bluewhale.android.mesh

import com.bluewhale.android.util.AppConstants

/**
 * Chooses how long the RSSI monitoring loop sleeps between passes.
 * With no client connections there is nothing to read, so the loop backs
 * off instead of waking every few seconds.
 */
object RssiPollScheduler {

    /** While someone is being looked for, RSSI is read this often so the finder feels live. */
    const val FIND_INTERVAL_MS = 400L

    fun nextDelayMs(clientConnectionCount: Int, finding: Boolean = false): Long {
        return when {
            clientConnectionCount > 0 && finding -> FIND_INTERVAL_MS
            clientConnectionCount > 0 -> AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS
            else -> AppConstants.Mesh.RSSI_IDLE_INTERVAL_MS
        }
    }
}
