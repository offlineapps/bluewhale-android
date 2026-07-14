package com.bluewhale.android.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationContentTrackerTest {

    private lateinit var tracker: NotificationContentTracker

    @Before
    fun setup() {
        tracker = NotificationContentTracker()
    }

    @Test
    fun `posts on first update`() {
        assertTrue(tracker.shouldPost(peerCount = 0, force = false))
    }

    @Test
    fun `skips repeated updates with an unchanged peer count`() {
        assertTrue(tracker.shouldPost(peerCount = 0, force = false))
        assertFalse(tracker.shouldPost(peerCount = 0, force = false))
        assertFalse(tracker.shouldPost(peerCount = 0, force = false))
    }

    @Test
    fun `posts when the peer count changes`() {
        assertTrue(tracker.shouldPost(peerCount = 0, force = false))
        assertTrue(tracker.shouldPost(peerCount = 2, force = false))
        assertFalse(tracker.shouldPost(peerCount = 2, force = false))
        assertTrue(tracker.shouldPost(peerCount = 0, force = false))
    }

    @Test
    fun `force posts even when the peer count is unchanged`() {
        assertTrue(tracker.shouldPost(peerCount = 3, force = false))
        assertFalse(tracker.shouldPost(peerCount = 3, force = false))
        assertTrue(tracker.shouldPost(peerCount = 3, force = true))
    }

    @Test
    fun `reset forces the next update to post`() {
        assertTrue(tracker.shouldPost(peerCount = 1, force = false))
        assertFalse(tracker.shouldPost(peerCount = 1, force = false))

        tracker.reset()

        assertTrue(tracker.shouldPost(peerCount = 1, force = false))
    }

    @Test
    fun `an idle hour of ticks posts once`() {
        var posted = 0
        repeat(120) {
            if (tracker.shouldPost(peerCount = 0, force = false)) posted++
        }
        assertTrue(posted == 1)
    }
}
