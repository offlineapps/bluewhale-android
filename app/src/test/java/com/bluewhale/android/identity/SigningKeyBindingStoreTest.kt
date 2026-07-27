package com.bluewhale.android.identity

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * The signing key binding store is written straight from ANNOUNCE traffic, which is
 * unauthenticated, so it has to survive a peer in range announcing whatever it likes.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SigningKeyBindingStoreTest {

    private fun manager() =
        SecureIdentityStateManager(ApplicationProvider.getApplicationContext())

    private fun key(seed: Int) = "%064x".format(java.math.BigInteger.valueOf(seed.toLong()))

    @Test
    fun `repeated contested announces do not extend the contest`() {
        val mgr = manager()
        val noiseKey = key(1)

        mgr.recordAnnouncedSigningKey(noiseKey, key(100))
        val firstContest = mgr.recordAnnouncedSigningKey(noiseKey, key(200))
        assertEquals(true, firstContest?.contested)

        // The contest has to be timed from when it was first detected. If each further
        // announce re-stamped it, anyone could hold a peer unverified forever by
        // announcing on an interval shorter than the timeout.
        Thread.sleep(5)
        mgr.recordAnnouncedSigningKey(noiseKey, key(300))
        Thread.sleep(5)
        val later = mgr.recordAnnouncedSigningKey(noiseKey, key(400))

        assertEquals(true, later?.contested)
        assertEquals(
            "contest start must not move when the binding is restated",
            firstContest?.recordedAtMs,
            later?.recordedAtMs
        )
        assertEquals(
            firstContest?.recordedAtMs,
            mgr.getSigningKeyBinding(noiseKey)?.recordedAtMs
        )
    }

    @Test
    fun `an announce matching the stored key leaves the binding untouched`() {
        val mgr = manager()
        val noiseKey = key(2)

        val first = mgr.recordAnnouncedSigningKey(noiseKey, key(100))
        Thread.sleep(5)
        val repeat = mgr.recordAnnouncedSigningKey(noiseKey, key(100))

        assertEquals(false, repeat?.contested)
        assertEquals(first?.recordedAtMs, repeat?.recordedAtMs)
    }

    @Test
    fun `a proven signing key still wins after a contest`() {
        val mgr = manager()
        val noiseKey = key(3)

        mgr.recordAnnouncedSigningKey(noiseKey, key(100))
        mgr.recordAnnouncedSigningKey(noiseKey, key(200))
        assertEquals(true, mgr.getSigningKeyBinding(noiseKey)?.contested)

        mgr.recordAuthenticatedSigningKey(noiseKey, key(300))

        val settled = mgr.getSigningKeyBinding(noiseKey)
        assertEquals(true, settled?.authenticated)
        assertEquals(false, settled?.contested)
        assertEquals(key(300), settled?.signingKeyHex)
    }

    @Test
    fun `announced bindings are capped and never evict a proven one`() {
        val mgr = manager()
        val provenNoiseKey = key(9999)
        mgr.recordAuthenticatedSigningKey(provenNoiseKey, key(1))

        // Every announce mints an entry, so without a cap this set grows for as long as
        // someone keeps sending them, and each announce rescans and rewrites all of it.
        repeat(700) { mgr.recordAnnouncedSigningKey(key(it + 1), key(it + 1)) }

        val proven = mgr.getSigningKeyBinding(provenNoiseKey)
        assertNotNull("a signing key proven over a session must not be evicted", proven)
        assertEquals(true, proven?.authenticated)

        // 512 total, one of which is the proven binding. Which of the announced ones are
        // dropped depends on arrival order, but the count is what bounds the store.
        val surviving = (1..700).count { mgr.getSigningKeyBinding(key(it)) != null }
        assertEquals("announced bindings must be capped", 511, surviving)
        assertTrue("the cap must not evict everything", surviving > 0)
    }
}
