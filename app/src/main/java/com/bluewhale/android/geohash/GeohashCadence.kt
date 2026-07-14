package com.bluewhale.android.geohash

import com.bluewhale.android.util.AppConstants
import kotlin.random.Random

/**
 * Cadence for the periodic geohash work driven by GeohashViewModel.
 * Both loops keep running while the app is backgrounded, so they slow down
 * rather than waking the radio at their foreground rate.
 */
object GeohashCadence {

    fun presenceIntervalMs(isForeground: Boolean, random: Random = Random.Default): Long {
        return if (isForeground) {
            random.nextLong(
                AppConstants.Nostr.PRESENCE_FOREGROUND_MIN_INTERVAL_MS,
                AppConstants.Nostr.PRESENCE_FOREGROUND_MAX_INTERVAL_MS
            )
        } else {
            AppConstants.Nostr.PRESENCE_BACKGROUND_INTERVAL_MS
        }
    }

    fun participantsIntervalMs(isForeground: Boolean): Long {
        return if (isForeground) {
            AppConstants.Nostr.PARTICIPANTS_REFRESH_INTERVAL_MS
        } else {
            AppConstants.Nostr.PARTICIPANTS_IDLE_INTERVAL_MS
        }
    }
}
