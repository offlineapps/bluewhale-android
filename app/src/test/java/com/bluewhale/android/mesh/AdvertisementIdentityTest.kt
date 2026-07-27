package com.bluewhale.android.mesh

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.security.MessageDigest

/**
 * The advertised value is broadcast continuously to anyone with a scanner, so it must
 * not be derivable from anything the device also publishes, and it must change.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AdvertisementIdentityTest {

    private val secret = ByteArray(32) { (it + 5).toByte() }
    private val period = AdvertisementIdentity.ROTATION_PERIOD_MS

    @Test
    fun `value is stable inside a window`() {
        val start = 7 * period
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val first = AdvertisementIdentity.currentId(context, start)
        val later = AdvertisementIdentity.currentId(context, start + period - 1)

        assertArrayEquals("must not change mid window", first, later)
    }

    @Test
    fun `value changes between windows`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val inWindow = AdvertisementIdentity.currentId(context, 7 * period)
        val nextWindow = AdvertisementIdentity.currentId(context, 8 * period)

        assertFalse("must not repeat across windows", inWindow.contentEquals(nextWindow))
    }

    @Test
    fun `value is eight bytes`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(8, AdvertisementIdentity.currentId(context).size)
    }

    @Test
    fun `value is not derivable from the peer id or noise key`() {
        // The old scheme was the first 8 bytes of SHA-256 over the public noise key, which
        // anyone who received an announcement could compute.
        val noiseKey = ByteArray(32) { 3 }
        val oldScheme = MessageDigest.getInstance("SHA-256").digest(noiseKey).copyOf(8)

        val rotating = AdvertisementIdentity.idForWindow(secret, 1)

        assertFalse("must not equal the published derivation", rotating.contentEquals(oldScheme))
    }

    @Test
    fun `different devices do not collide`() {
        val other = ByteArray(32) { (it + 9).toByte() }

        assertFalse(
            AdvertisementIdentity.idForWindow(secret, 1)
                .contentEquals(AdvertisementIdentity.idForWindow(other, 1))
        )
    }

    @Test
    fun `rotation deadline stays inside the period`() {
        val remaining = AdvertisementIdentity.millisUntilRotation(3 * period + 1000)

        assertEquals(period - 1000, remaining)
        assertTrue(remaining in 1..period)
    }
}
