package com.bluewhale.android.geohash

import com.bluewhale.android.util.AppConstants
import kotlin.random.Random

/**
 * Cadence for the periodic geohash work driven by GeohashViewModel.
 * Both loops keep running while the app is backgrounded, so they slow down
 * rather than waking the radio at their foreground rate.
 *
 * Presence intervals are always drawn from a range. A fixed period would give
 * an observer a stable clock to fingerprint the device by, and to group the
 * separate per-geohash identities that one device publishes under.
 */
object GeohashCadence {

    fun presenceIntervalMs(
        isForeground: Boolean,
        reduceBackgroundActivity: Boolean = false,
        random: Random = Random.Default
    ): Long {
        val (min, max) = when {
            isForeground -> AppConstants.Nostr.PRESENCE_FOREGROUND_MIN_INTERVAL_MS to
                AppConstants.Nostr.PRESENCE_FOREGROUND_MAX_INTERVAL_MS

            reduceBackgroundActivity -> AppConstants.Nostr.PRESENCE_REDUCED_MIN_INTERVAL_MS to
                AppConstants.Nostr.PRESENCE_REDUCED_MAX_INTERVAL_MS

            else -> AppConstants.Nostr.PRESENCE_BACKGROUND_MIN_INTERVAL_MS to
                AppConstants.Nostr.PRESENCE_BACKGROUND_MAX_INTERVAL_MS
        }
        return random.nextLong(min, max)
    }

    fun participantsIntervalMs(isForeground: Boolean): Long {
        return if (isForeground) {
            AppConstants.Nostr.PARTICIPANTS_REFRESH_INTERVAL_MS
        } else {
            AppConstants.Nostr.PARTICIPANTS_IDLE_INTERVAL_MS
        }
    }
}
