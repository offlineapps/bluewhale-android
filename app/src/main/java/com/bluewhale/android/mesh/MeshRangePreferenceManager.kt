package com.bluewhale.android.mesh

import android.content.Context
import android.content.SharedPreferences
import com.bluewhale.android.util.AppConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages how far packets originated by this device travel through the mesh.
 *
 * Range is expressed in hops: range 1 keeps packets within direct Bluetooth range,
 * range 2 lets one relay pass them on, and so on. The default range keeps the TTL
 * of 7 that every packet used before this setting existed.
 *
 * Packets relayed on behalf of other peers keep their own TTL and are never limited.
 */
object MeshRangePreferenceManager {

    private const val PREFS_NAME = "mesh_range_preferences"
    private const val KEY_RANGE_HOPS = "range_hops"

    private val _rangeHops = MutableStateFlow(AppConstants.MAX_RANGE_HOPS)
    val rangeHops: StateFlow<Int> = _rangeHops.asStateFlow()

    private var sharedPrefs: SharedPreferences? = null

    fun init(context: Context) {
        if (sharedPrefs != null) return

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs = prefs
        _rangeHops.value = clamp(prefs.getInt(KEY_RANGE_HOPS, AppConstants.MAX_RANGE_HOPS))
    }

    fun setRangeHops(hops: Int) {
        val clamped = clamp(hops)
        _rangeHops.value = clamped
        sharedPrefs?.edit()?.putInt(KEY_RANGE_HOPS, clamped)?.apply()
    }

    /**
     * TTL to stamp on a packet we originate.
     */
    fun currentTtl(): UByte = (_rangeHops.value - 1).toUByte()

    /**
     * Lower a TTL to the configured range, never raise it: packets that already ask for
     * fewer hops than the range (sync packets are neighbor-only) keep their own TTL.
     */
    fun limitTtl(ttl: UByte): UByte = minOf(ttl, currentTtl())

    private fun clamp(hops: Int): Int =
        hops.coerceIn(AppConstants.MIN_RANGE_HOPS, AppConstants.MAX_RANGE_HOPS)

    internal fun resetForTesting() {
        sharedPrefs = null
        _rangeHops.value = AppConstants.MAX_RANGE_HOPS
    }
}
