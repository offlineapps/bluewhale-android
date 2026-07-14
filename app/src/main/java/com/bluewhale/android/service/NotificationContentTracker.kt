package com.bluewhale.android.service

/**
 * Tracks the last notification content that was posted so the foreground service
 * can skip re-posting an identical notification on every refresh tick.
 */
class NotificationContentTracker {

    private var lastPeerCount: Int = UNSET

    fun shouldPost(peerCount: Int, force: Boolean): Boolean {
        if (!force && peerCount == lastPeerCount) return false
        lastPeerCount = peerCount
        return true
    }

    fun reset() {
        lastPeerCount = UNSET
    }

    companion object {
        private const val UNSET = -1
    }
}
