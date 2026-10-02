package com.bluewhale.android.mesh

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Listen-only stealth mode.
 *
 * While enabled the device transmits nothing over the Bluetooth mesh: no advertising (so
 * scanners do not see a Bluewhale device), no announce (so peers do not learn the nickname or
 * keys), no messages, receipts, handshakes, sync requests or relays. It still scans and connects
 * to nearby peers as a GATT client, so their broadcasts keep arriving and can be read.
 *
 * Peers it connects to see an anonymous Bluetooth connection from a randomised address,
 * nothing more. Because it relays nothing, a stealth device is a dead end in the mesh.
 */
object StealthModePreferenceManager {

    private const val PREFS_NAME = "stealth_mode_preferences"
    private const val KEY_ENABLED = "enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var sharedPrefs: SharedPreferences? = null

    fun init(context: Context) {
        if (sharedPrefs != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs = prefs
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        sharedPrefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    fun isEnabled(): Boolean = _enabled.value

    internal fun resetForTesting() {
        sharedPrefs = null
        _enabled.value = false
    }
}
