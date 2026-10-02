package com.bluewhale.android.mesh

import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.mesh.JammingDetector.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JammingDetectorTest {

    private var now = 1_000_000L
    private val detector = JammingDetector { now }

    private val supervisionTimeout = 0x08
    private val cleanDisconnect = 0

    private fun at(ms: Long) {
        now = 1_000_000L + ms
    }

    private fun level() = detector.state.value.level

    /** Three peers advertising and linked, scanning running. */
    private fun busyMesh() {
        at(0)
        detector.onScanning(true)
        listOf("a", "b", "c").forEach {
            detector.onAdvertisementSeen(it)
            detector.onLinkUp()
        }
    }

    /** Ticks every 5 s from [fromMs] to [toMs] with no advertisements. */
    private fun quiet(fromMs: Long, toMs: Long) {
        var t = fromMs
        while (t <= toMs) {
            at(t)
            detector.tick()
            t += 5_000
        }
    }

    @Test
    fun `starts clear`() {
        assertEquals(Level.CLEAR, level())
    }

    @Test
    fun `several links timing out at once is possible jamming`() {
        busyMesh()
        at(10_000); detector.onAdvertisementSeen("a")
        at(12_000); detector.onLinkLost("a", supervisionTimeout)
        at(14_000); detector.onLinkLost("b", supervisionTimeout)
        at(16_000); detector.onLinkLost("c", supervisionTimeout)

        assertEquals(Level.POSSIBLE, level())
        assertTrue(detector.state.value.reasons.single().contains("3 Bluetooth links failed"))
    }

    @Test
    fun `peers leaving cleanly is not jamming`() {
        busyMesh()
        at(12_000); detector.onLinkLost("a", cleanDisconnect)
        at(13_000); detector.onLinkLost("b", cleanDisconnect)
        at(14_000); detector.onLinkLost("c", cleanDisconnect)

        assertEquals(Level.CLEAR, level())
    }

    @Test
    fun `timeouts spread over minutes, like walking away, are not jamming`() {
        busyMesh()
        at(20_000); detector.onAdvertisementSeen("x"); detector.onLinkLost("a", supervisionTimeout)
        at(60_000); detector.onAdvertisementSeen("x"); detector.onLinkLost("b", supervisionTimeout)
        at(100_000); detector.onAdvertisementSeen("x"); detector.onLinkLost("c", supervisionTimeout)

        assertEquals(Level.CLEAR, level())
    }

    @Test
    fun `losing both of two links at once counts`() {
        at(0)
        detector.onScanning(true)
        detector.onAdvertisementSeen("a"); detector.onLinkUp()
        detector.onAdvertisementSeen("b"); detector.onLinkUp()
        at(5_000); detector.onLinkLost("a", supervisionTimeout)
        at(6_000); detector.onLinkLost("b", 147)

        assertEquals(Level.POSSIBLE, level())
    }

    @Test
    fun `a busy mesh going silent while scanning is possible jamming`() {
        busyMesh()
        quiet(5_000, 25_000)
        assertEquals("not yet: 30 s of silent scanning needed", Level.CLEAR, level())

        quiet(30_000, 35_000)
        assertEquals(Level.POSSIBLE, level())
        assertTrue(detector.state.value.reasons.single().contains("went silent"))
    }

    @Test
    fun `silence while not scanning proves nothing`() {
        busyMesh()
        at(1_000); detector.onScanning(false)
        quiet(5_000, 90_000)

        assertEquals(Level.CLEAR, level())
    }

    @Test
    fun `a quiet mesh of two devices is not enough to call silence`() {
        at(0)
        detector.onScanning(true)
        detector.onAdvertisementSeen("a")
        detector.onAdvertisementSeen("b")
        quiet(5_000, 60_000)

        assertEquals(Level.CLEAR, level())
    }

    @Test
    fun `link storm and silence together are likely jamming`() {
        busyMesh()
        at(8_000); detector.onLinkLost("a", supervisionTimeout)
        at(10_000); detector.onLinkLost("b", supervisionTimeout)
        at(12_000); detector.onLinkLost("c", supervisionTimeout)
        quiet(15_000, 35_000)

        assertEquals(Level.LIKELY, level())
        assertEquals(2, detector.state.value.reasons.size)
    }

    @Test
    fun `it clears once advertisements return and the storm ages out`() {
        busyMesh()
        at(8_000); detector.onLinkLost("a", supervisionTimeout)
        at(10_000); detector.onLinkLost("b", supervisionTimeout)
        at(12_000); detector.onLinkLost("c", supervisionTimeout)
        quiet(15_000, 35_000)
        assertEquals(Level.LIKELY, level())

        at(40_000); detector.onAdvertisementSeen("a")
        assertEquals("storm still held", Level.POSSIBLE, level())

        at(80_000); detector.onAdvertisementSeen("a")
        assertEquals(Level.CLEAR, level())
    }

    @Test
    fun `reset clears without a notice`() {
        busyMesh()
        at(8_000); detector.onLinkLost("a", supervisionTimeout)
        at(9_000); detector.onLinkLost("b", supervisionTimeout)
        at(10_000); detector.onLinkLost("c", supervisionTimeout)

        detector.reset()

        assertEquals(Level.CLEAR, level())
        assertNull(JammingDetector.Notices.forTransition(Level.POSSIBLE, detector.state.value))
    }

    @Test
    fun `notices are posted on the way up and when all clear`() {
        val likely = JammingDetector.Assessment(Level.LIKELY, listOf("3 links failed"))
        val possible = JammingDetector.Assessment(Level.POSSIBLE, listOf("went silent"))
        val clear = JammingDetector.Assessment(Level.CLEAR)

        assertTrue(JammingDetector.Notices.forTransition(Level.CLEAR, likely)!!.contains("likely bluetooth jamming"))
        assertTrue(JammingDetector.Notices.forTransition(Level.CLEAR, possible)!!.contains("possible"))
        assertNull("stepping down is not news", JammingDetector.Notices.forTransition(Level.LIKELY, possible))
        assertEquals("bluetooth looks clear again.", JammingDetector.Notices.forTransition(Level.POSSIBLE, clear))
        assertNull(JammingDetector.Notices.forTransition(Level.CLEAR, clear))
    }

    @Test
    fun `survival mode drops duty cycling and lifts it again`() {
        val power = PowerManager(ApplicationProvider.getApplicationContext())
        assertTrue("app starts in background, duty cycled", power.shouldUseDutyCycle())

        power.setJammingBoost(true)
        assertFalse(power.shouldUseDutyCycle())
        assertTrue(power.getPowerInfo().contains("PERFORMANCE"))

        power.setJammingBoost(false)
        assertTrue(power.shouldUseDutyCycle())
    }
}
