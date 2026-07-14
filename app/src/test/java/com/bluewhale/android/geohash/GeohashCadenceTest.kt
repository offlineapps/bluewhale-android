package com.bluewhale.android.geohash

import com.bluewhale.android.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GeohashCadenceTest {

    @Test
    fun `foreground presence stays within the randomized window`() {
        val random = Random(seed = 42)
        repeat(500) {
            val interval = GeohashCadence.presenceIntervalMs(isForeground = true, random = random)
            assertTrue(
                "interval $interval out of range",
                interval >= AppConstants.Nostr.PRESENCE_FOREGROUND_MIN_INTERVAL_MS &&
                    interval < AppConstants.Nostr.PRESENCE_FOREGROUND_MAX_INTERVAL_MS
            )
        }
    }

    @Test
    fun `foreground presence is randomized rather than fixed`() {
        val random = Random(seed = 7)
        val intervals = (1..50)
            .map { GeohashCadence.presenceIntervalMs(isForeground = true, random = random) }
            .toSet()
        assertTrue("expected decorrelated intervals", intervals.size > 1)
    }

    @Test
    fun `background presence uses the throttled interval`() {
        assertEquals(
            AppConstants.Nostr.PRESENCE_BACKGROUND_INTERVAL_MS,
            GeohashCadence.presenceIntervalMs(isForeground = false)
        )
    }

    @Test
    fun `background presence is far less frequent than foreground`() {
        val foregroundWorstCase = AppConstants.Nostr.PRESENCE_FOREGROUND_MAX_INTERVAL_MS
        assertTrue(
            GeohashCadence.presenceIntervalMs(isForeground = false) > foregroundWorstCase * 5
        )
    }

    @Test
    fun `participants refresh slows down in the background`() {
        assertEquals(
            AppConstants.Nostr.PARTICIPANTS_REFRESH_INTERVAL_MS,
            GeohashCadence.participantsIntervalMs(isForeground = true)
        )
        assertEquals(
            AppConstants.Nostr.PARTICIPANTS_IDLE_INTERVAL_MS,
            GeohashCadence.participantsIntervalMs(isForeground = false)
        )
        assertTrue(
            GeohashCadence.participantsIntervalMs(isForeground = false) >
                GeohashCadence.participantsIntervalMs(isForeground = true)
        )
    }
}
