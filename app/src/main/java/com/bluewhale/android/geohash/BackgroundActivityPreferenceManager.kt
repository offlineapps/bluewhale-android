package com.bluewhale.android.geohash

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When enabled, the geohash presence heartbeat slows down far enough while the
 * app is backgrounded that other clients prune the participant entry, so the
 * user stops appearing in geohash participant lists until they open the app.
 * Off by default to preserve the existing visibility behaviour.
 */
object BackgroundActivityPreferenceManager {

    private const val PREFS_NAME = "background_activity_preferences"
    private const val KEY_REDUCED = "reduce_background_activity"
    private const val DEFAULT_REDUCED = false

    private val _reduced = MutableStateFlow(DEFAULT_REDUCED)
    val reduced: StateFlow<Boolean> = _reduced.asStateFlow()

    private lateinit var sharedPrefs: SharedPreferences
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _reduced.value = sharedPrefs.getBoolean(KEY_REDUCED, DEFAULT_REDUCED)
        isInitialized = true
    }

    fun setReduced(enabled: Boolean) {
        _reduced.value = enabled
        if (isInitialized) {
            sharedPrefs.edit().putBoolean(KEY_REDUCED, enabled).apply()
        }
    }

    fun isReduced(): Boolean = _reduced.value
}
