package com.bluewhale.android.courier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourierStoreTest {

    private var now = 1_000_000L
    private val hour = 60 * 60 * 1000L

    private class MemoryPersistence : CourierStore.Persistence {
        var bytes: ByteArray? = null
        override fun load() = bytes
        override fun save(bytes: ByteArray) { this.bytes = bytes }
        override fun delete() { bytes = null }
    }

    private var nextId = 0
    private fun envelope(
        priority: CourierEnvelope.Priority = CourierEnvelope.Priority.NORMAL,
        lifetime: Long = 72 * hour,
        createdAt: Long = now
    ) = CourierEnvelope(
        id = ByteArray(16).also { it[0] = (++nextId).toByte(); it[1] = (nextId shr 8).toByte() },
        recipientTag = ByteArray(16) { 5 },
        priority = priority,
        createdAt = createdAt,
        expiresAt = createdAt + lifetime,
        sealed = ByteArray(100)
    )

    private fun store(capacity: Int = 10, persistence: CourierStore.Persistence? = null) =
        CourierStore(clock = { now }, capacity = capacity, persistence = persistence)

    @Test
    fun `accepts once and refuses duplicates`() {
        val s = store()
        val e = envelope()

        assertTrue(s.accept(e))
        assertFalse(s.accept(e))
        assertEquals(1, s.size())
    }

    @Test
    fun `expired envelopes are refused and dropped`() {
        val s = store()
        assertFalse(s.accept(envelope(lifetime = 0)))

        s.accept(envelope(lifetime = hour))
        now += 2 * hour
        assertEquals(0, s.size())
    }

    @Test
    fun `delivered envelopes are dropped and not taken again`() {
        val s = store()
        val e = envelope()
        s.accept(e)

        s.markDelivered(listOf(e.id))

        assertEquals(0, s.size())
        assertFalse(s.accept(e))
        assertTrue(s.isDelivered(e.idHex))
    }

    @Test
    fun `when full the oldest normal envelope makes room`() {
        val s = store(capacity = 2)
        val old = envelope(createdAt = now - hour)
        val newer = envelope()
        s.accept(old); s.accept(newer)

        assertTrue(s.accept(envelope()))
        assertFalse(s.contains(old.idHex))
        assertTrue(s.contains(newer.idHex))
    }

    @Test
    fun `urgent envelopes outlast normal ones and are not pushed out by them`() {
        val s = store(capacity = 1)
        val urgent = envelope(priority = CourierEnvelope.Priority.URGENT)
        s.accept(urgent)

        assertFalse(s.accept(envelope()))
        assertTrue(s.contains(urgent.idHex))
    }

    @Test
    fun `our own envelopes are never evicted for someone else's`() {
        val s = store(capacity = 1)
        val mine = envelope()
        s.accept(mine, own = true)

        assertFalse(s.accept(envelope(priority = CourierEnvelope.Priority.URGENT)))
        assertTrue(s.contains(mine.idHex))
    }

    @Test
    fun `each neighbour gets each envelope once, urgent first`() {
        val s = store()
        val normal = envelope(createdAt = now - hour)
        val urgent = envelope(priority = CourierEnvelope.Priority.URGENT)
        s.accept(normal); s.accept(urgent)

        val first = s.pendingFor("bob", includeCarried = true)
        assertEquals(listOf(urgent.idHex, normal.idHex), first.map { it.idHex })

        s.markHandedTo("bob", first)
        assertTrue(s.pendingFor("bob", includeCarried = true).isEmpty())
        assertEquals(2, s.pendingFor("carol", includeCarried = true).size)
    }

    @Test
    fun `envelopes are not handed back to the neighbour they came from`() {
        val s = store()
        s.accept(envelope(), from = "carol")

        assertTrue(s.pendingFor("carol", includeCarried = true).isEmpty())
    }

    @Test
    fun `without courier mode only our own envelopes are handed on`() {
        val s = store()
        val mine = envelope()
        s.accept(mine, own = true)
        s.accept(envelope())

        assertEquals(listOf(mine.idHex), s.pendingFor("bob", includeCarried = false).map { it.idHex })
    }

    @Test
    fun `survives a restart, and clear wipes the file`() {
        val disk = MemoryPersistence()
        val first = store(persistence = disk)
        val carried = envelope()
        val delivered = envelope()
        first.accept(carried, own = true)
        first.accept(delivered)
        first.markDelivered(listOf(delivered.id))

        val second = store(persistence = disk)
        assertTrue(second.contains(carried.idHex))
        assertTrue(second.isDelivered(delivered.idHex))
        assertEquals("own flag survives", 1, second.pendingFor("x", includeCarried = false).size)

        second.clear()
        assertEquals(null, disk.bytes)
    }

    @Test
    fun `a damaged file starts an empty store`() {
        val disk = MemoryPersistence().apply { bytes = byteArrayOf(1, 0, 0, 0, 5, 1) }

        assertEquals(0, store(persistence = disk).size())
    }
}
