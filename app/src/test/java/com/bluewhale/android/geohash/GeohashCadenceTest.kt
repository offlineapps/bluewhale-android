package com.bluewhale.android.geohash

import com.bluewhale.android.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GeohashCadenceTest {

    private fun assertWithin(min: Long, max: Long, actual: Long) {
        assertTrue("interval $actual outside [$min, $max)", actual in min until max)
    }

    @Test
    fun `foreground presence stays within the randomized window`() {
        val random = Random(seed = 42)
        repeat(500) {
            assertWithin(
                AppConstants.Nostr.PRESENCE_FOREGROUND_MIN_INTERVAL_MS,
                AppConstants.Nostr.PRESENCE_FOREGROUND_MAX_INTERVAL_MS,
                GeohashCadence.presenceIntervalMs(isForeground = true, random = random)
            )
        }
    }

    @Test
    fun `background presence stays within the randomized window`() {
        val random = Random(seed = 42)
        repeat(500) {
            assertWithin(
                AppConstants.Nostr.PRESENCE_BACKGROUND_MIN_INTERVAL_MS,
                AppConstants.Nostr.PRESENCE_BACKGROUND_MAX_INTERVAL_MS,
                GeohashCadence.presenceIntervalMs(isForeground = false, random = random)
            )
        }
    }

    @Test
    fun `reduced background presence stays within the randomized window`() {
        val random = Random(seed = 42)
        repeat(500) {
            assertWithin(
                AppConstants.Nostr.PRESENCE_REDUCED_MIN_INTERVAL_MS,
                AppConstants.Nostr.PRESENCE_REDUCED_MAX_INTERVAL_MS,
                GeohashCadence.presenceIntervalMs(
                    isForeground = false,
                    reduceBackgroundActivity = true,
                    random = random
                )
            )
        }
    }

    @Test
    fun `no mode emits a fixed interval`() {
        val random = Random(seed = 7)
        listOf(
            true to false,
            false to false,
            false to true
        ).forEach { (foreground, reduced) ->
            val intervals = (1..50)
                .map {
                    GeohashCadence.presenceIntervalMs(
                        isForeground = foreground,
                        reduceBackgroundActivity = reduced,
                        random = random
                    )
                }
                .toSet()
            assertTrue(
                "fixed interval is a timing fingerprint (foreground=$foreground, reduced=$reduced)",
                intervals.size > 1
            )
        }
    }

    @Test
    fun `default background presence stays inside the participant prune window`() {
        // GeohashRepository prunes participants after 5 minutes. The default
        // background cadence must beat that or backgrounded users vanish.
        val pruneWindowMs = 5 * 60 * 1000L
        assertTrue(
            AppConstants.Nostr.PRESENCE_BACKGROUND_MAX_INTERVAL_MS < pruneWindowMs
        )
    }

    @Test
    fun `reduced background presence is past the prune window by design`() {
        val pruneWindowMs = 5 * 60 * 1000L
        assertTrue(
            AppConstants.Nostr.PRESENCE_REDUCED_MIN_INTERVAL_MS > pruneWindowMs
        )
    }

    @Test
    fun `background is slower than foreground and reduced is slowest`() {
        assertTrue(
            AppConstants.Nostr.PRESENCE_BACKGROUND_MIN_INTERVAL_MS >
                AppConstants.Nostr.PRESENCE_FOREGROUND_MAX_INTERVAL_MS
        )
        assertTrue(
            AppConstants.Nostr.PRESENCE_REDUCED_MIN_INTERVAL_MS >
                AppConstants.Nostr.PRESENCE_BACKGROUND_MAX_INTERVAL_MS
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
    }
}
