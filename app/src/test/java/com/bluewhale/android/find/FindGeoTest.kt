package com.bluewhale.android.find

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindGeoTest {

    // Brandenburg Gate and the Reichstag, about 280 m apart, north-north-west
    private val gateLat = 52.516275
    private val gateLon = 13.377704
    private val reichstagLat = 52.518620
    private val reichstagLon = 13.376198

    @Test
    fun `distance between two landmarks`() {
        val d = FindGeo.distanceMetres(gateLat, gateLon, reichstagLat, reichstagLon)
        assertTrue("was $d", d in 260.0..300.0)
    }

    @Test
    fun `bearing and compass names`() {
        assertEquals("north", FindGeo.compassName(FindGeo.bearingDegrees(0.0, 0.0, 1.0, 0.0)))
        assertEquals("east", FindGeo.compassName(FindGeo.bearingDegrees(0.0, 0.0, 0.0, 1.0)))
        assertEquals("south-west", FindGeo.compassName(225.0))
        assertEquals("north", FindGeo.compassName(359.0))
        assertEquals("north", FindGeo.compassName(FindGeo.bearingDegrees(gateLat, gateLon, reichstagLat, reichstagLon)))
    }

    @Test
    fun `descriptions`() {
        assertEquals("within a few metres", FindGeo.describe(3.0, 90.0))
        assertEquals("120 m east", FindGeo.describe(120.4, 90.0))
        assertEquals("2.5 km south", FindGeo.describe(2_500.0, 180.0))
    }

    @Test
    fun `position round trips and rejects nonsense`() {
        val p = SharedPosition(gateLat, gateLon, 12.5f, 1_700_000_000_000L)
        assertEquals(p, SharedPosition.decode(p.encode()))

        assertNull(SharedPosition.decode(ByteArray(3)))
        assertNull(SharedPosition.decode(SharedPosition(91.0, 0.0, 1f, 0).encode()))
        assertNull(SharedPosition.decode(SharedPosition(0.0, 181.0, 1f, 0).encode()))
        assertNull(SharedPosition.decode(p.encode().also { it[0] = 9 }))
    }

    @Test
    fun `position notice with our own fix gives distance and direction`() {
        val now = 1_700_000_100_000L
        val theirs = SharedPosition(reichstagLat, reichstagLon, 8f, now - 30_000)
        val text = FindNotices.positionShared("alice", theirs, gateLat, gateLon, now - 5_000, now)

        assertTrue(text, text.startsWith("alice shared their gps position: "))
        assertTrue(text, text.contains(" m north of you (±8 m, just now)"))
    }

    @Test
    fun `position notice without our own fix gives coordinates`() {
        val now = 1_700_000_100_000L
        val theirs = SharedPosition(reichstagLat, reichstagLon, 8f, now - 5 * 60_000)
        val text = FindNotices.positionShared("alice", theirs, null, null, null, now)

        assertTrue(text, text.contains("52.51862, 13.37620"))
        assertTrue(text, text.contains("5 min old"))
        assertTrue(text, text.contains("turn on location"))
    }

    @Test
    fun `an old own fix is not used`() {
        val now = 1_700_000_100_000L
        val theirs = SharedPosition(reichstagLat, reichstagLon, 8f, now)
        val text = FindNotices.positionShared("alice", theirs, gateLat, gateLon, now - 60 * 60_000, now)

        assertTrue(text, text.contains("turn on location"))
    }

    @Test
    fun `ring notices`() {
        assertTrue(FindNotices.ringReceived("bob", rang = true).contains("your phone rang"))
        assertTrue(FindNotices.ringReceived("bob", rang = false).contains("only favourites"))
    }
}
