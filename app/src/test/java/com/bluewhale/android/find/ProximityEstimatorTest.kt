package com.bluewhale.android.find

import com.bluewhale.android.find.ProximityEstimator.Trend
import com.bluewhale.android.find.ProximityEstimator.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityEstimatorTest {

    private val estimator = ProximityEstimator()

    /** Feeds [rssi] every 400 ms from [fromMs] for [forMs]. */
    private fun feed(rssi: Int, fromMs: Long, forMs: Long): Long {
        var t = fromMs
        while (t < fromMs + forMs) {
            estimator.add(rssi, t)
            t += 400
        }
        return t
    }

    @Test
    fun `nothing heard is unknown`() {
        assertEquals(Zone.UNKNOWN, estimator.zone(0))
        assertEquals(Trend.UNKNOWN, estimator.trend(0))
        assertNull(estimator.pulseIntervalMs(0))
    }

    @Test
    fun `strong signal is very close, weak is far`() {
        val t = feed(-50, 0, 5_000)
        assertEquals(Zone.VERY_CLOSE, estimator.zone(t))

        estimator.reset()
        val t2 = feed(-90, 0, 5_000)
        assertEquals(Zone.FAR, estimator.zone(t2))
    }

    @Test
    fun `walking towards them is warmer, away is colder`() {
        var t = feed(-85, 0, 4_000)
        t = feed(-75, t, 4_000)
        assertEquals(Trend.WARMER, estimator.trend(t))

        t = feed(-90, t, 4_000)
        assertEquals(Trend.COLDER, estimator.trend(t))
    }

    @Test
    fun `small wobbles are steady`() {
        var t = 0L
        repeat(30) {
            estimator.add(if (it % 2 == 0) -70 else -72, t)
            t += 400
        }
        assertEquals(Trend.STEADY, estimator.trend(t))
    }

    @Test
    fun `smoothing damps a single outlier`() {
        val t = feed(-80, 0, 5_000)
        estimator.add(-45, t)

        assertTrue("one spike does not jump to very close", estimator.zone(t) != Zone.VERY_CLOSE)
    }

    @Test
    fun `pulses speed up as the signal strengthens`() {
        val t = feed(-90, 0, 5_000)
        val far = estimator.pulseIntervalMs(t)!!
        estimator.reset()
        val t2 = feed(-50, 0, 5_000)
        val near = estimator.pulseIntervalMs(t2)!!

        assertTrue("near $near should be faster than far $far", near < far)
    }

    @Test
    fun `readings stop counting when they stop arriving`() {
        val t = feed(-60, 0, 2_000)
        assertEquals(Zone.UNKNOWN, estimator.zone(t + ProximityEstimator.STALE_AFTER_MS + 1))
    }

    @Test
    fun `impossible readings are ignored`() {
        estimator.add(0, 0)
        estimator.add(-127, 0)
        estimator.add(20, 0)

        assertNull(estimator.smoothed)
    }
}
