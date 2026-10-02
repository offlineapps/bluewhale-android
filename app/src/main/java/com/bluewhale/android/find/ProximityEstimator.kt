package com.bluewhale.android.find

import kotlin.math.pow

/**
 * Turns a noisy stream of Bluetooth RSSI readings for one peer into something a person can walk
 * by: a smoothed signal, a rough distance band, warmer/colder, and a Geiger-counter pulse rate.
 *
 * RSSI to distance is crude (bodies, walls and phone orientation move it by 10 dB or more), so
 * the UI leads with the trend and the zone, not metres.
 */
class ProximityEstimator(
    private val smoothing: Double = 0.35,
    /** Typical BLE phone RSSI at 1 m. */
    private val rssiAtOneMetre: Double = -59.0,
    /** 2 in free space, nearer 3 among people. */
    private val pathLossExponent: Double = 2.7
) {
    enum class Zone { VERY_CLOSE, NEAR, NEARBY, FAR, UNKNOWN }
    enum class Trend { WARMER, COLDER, STEADY, UNKNOWN }

    companion object {
        const val TREND_WINDOW_MS = 3_000L
        const val TREND_THRESHOLD_DB = 2.0
        const val STALE_AFTER_MS = 6_000L
        private const val STRONG_RSSI = -45.0
        private const val WEAK_RSSI = -95.0
        private const val FASTEST_PULSE_MS = 120L
        private const val SLOWEST_PULSE_MS = 1_500L
    }

    private data class Sample(val atMs: Long, val smoothed: Double)

    private val history = ArrayDeque<Sample>()
    var smoothed: Double? = null
        private set
    private var lastSampleAt: Long = 0

    fun add(rssi: Int, atMs: Long) {
        // Readings above 0 or at the floor are radio errors, not signal
        if (rssi >= 0 || rssi <= -127) return
        val next = smoothed?.let { it + smoothing * (rssi - it) } ?: rssi.toDouble()
        smoothed = next
        lastSampleAt = atMs
        history.addLast(Sample(atMs, next))
        while (history.size > 2 && atMs - history.first().atMs > TREND_WINDOW_MS * 2) history.removeFirst()
    }

    fun isStale(nowMs: Long): Boolean = smoothed == null || nowMs - lastSampleAt > STALE_AFTER_MS

    /** Log-distance path loss estimate, in metres. */
    fun distanceMetres(): Double? = smoothed?.let { 10.0.pow((rssiAtOneMetre - it) / (10 * pathLossExponent)) }

    fun zone(nowMs: Long): Zone {
        if (isStale(nowMs)) return Zone.UNKNOWN
        val d = distanceMetres() ?: return Zone.UNKNOWN
        return when {
            d < 1.0 -> Zone.VERY_CLOSE
            d < 3.0 -> Zone.NEAR
            d < 10.0 -> Zone.NEARBY
            else -> Zone.FAR
        }
    }

    /** Compares the smoothed signal now with about [TREND_WINDOW_MS] ago. */
    fun trend(nowMs: Long): Trend {
        if (isStale(nowMs)) return Trend.UNKNOWN
        val current = history.lastOrNull() ?: return Trend.UNKNOWN
        val past = history.lastOrNull { current.atMs - it.atMs >= TREND_WINDOW_MS } ?: return Trend.UNKNOWN
        val delta = current.smoothed - past.smoothed
        return when {
            delta >= TREND_THRESHOLD_DB -> Trend.WARMER
            delta <= -TREND_THRESHOLD_DB -> Trend.COLDER
            else -> Trend.STEADY
        }
    }

    /** Time between haptic pulses: faster as the signal gets stronger. Null when unknown. */
    fun pulseIntervalMs(nowMs: Long): Long? {
        if (isStale(nowMs)) return null
        val s = smoothed ?: return null
        val strength = ((s - WEAK_RSSI) / (STRONG_RSSI - WEAK_RSSI)).coerceIn(0.0, 1.0)
        return (SLOWEST_PULSE_MS - strength * (SLOWEST_PULSE_MS - FASTEST_PULSE_MS)).toLong()
    }

    fun reset() {
        history.clear()
        smoothed = null
        lastSampleAt = 0
    }
}
