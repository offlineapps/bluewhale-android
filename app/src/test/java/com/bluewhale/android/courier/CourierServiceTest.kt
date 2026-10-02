package com.bluewhale.android.courier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alice and Bob are never in range of each other. Carol walks from Alice's group to Bob's.
 */
class CourierServiceTest {

    private var now = 1_000_000L

    private inner class Device(courierMode: Boolean) {
        val keys = CourierTestKeys()
        var courier = courierMode
        val store = CourierStore(clock = { now })
        val inbox = mutableListOf<CourierService.Delivery>()
        /** Who hears this device right now. */
        var inRange: List<Device> = emptyList()

        val service: CourierService = CourierService(
            store = store,
            myStaticPublicKey = { keys.publicKey },
            seal = { key, plaintext, prologue -> CourierSeal.seal(keys.privateKey, key, plaintext, prologue) },
            open = { sealed, prologue -> CourierSeal.open(keys.privateKey, sealed, prologue) },
            sendToNeighbours = { payload -> inRange.forEach { it.service.onPayload(payload, keys.hex) } },
            deliver = { inbox.add(it) },
            courierModeEnabled = { courier },
            clock = { now }
        )

        /** Two devices come into range: each hands the other what it carries. */
        fun meet(other: Device) {
            inRange = listOf(other)
            other.inRange = listOf(this)
            service.onNeighbour(other.keys.hex)
            other.service.onNeighbour(keys.hex)
        }

        fun leave() {
            inRange.forEach { it.inRange = emptyList() }
            inRange = emptyList()
        }
    }

    private val alice = Device(courierMode = false)
    private val bob = Device(courierMode = false)
    private val carol = Device(courierMode = true)

    @Test
    fun `a courier carries a message across a gap`() {
        alice.meet(carol)
        alice.service.send(bob.keys.publicKey, "MSG-1", "alice", "meet at the north gate")
        alice.leave()

        now += 3 * 60 * 60 * 1000L // hours later, across town
        carol.meet(bob)

        val got = bob.inbox.single()
        assertEquals("meet at the north gate", got.content)
        assertEquals("alice", got.senderNickname)
        assertEquals("MSG-1", got.messageId)
        assertTrue(got.senderStaticKey.contentEquals(alice.keys.publicKey))
    }

    @Test
    fun `the recipient's acknowledgement makes the courier drop its copy`() {
        alice.meet(carol)
        alice.service.send(bob.keys.publicKey, "MSG-1", "alice", "hello")
        alice.leave()
        assertEquals(1, carol.store.size())

        carol.meet(bob)

        assertEquals(0, carol.store.size())
    }

    @Test
    fun `a device without courier mode does not carry other people's messages`() {
        carol.courier = false
        alice.meet(carol)
        alice.service.send(bob.keys.publicKey, "MSG-1", "alice", "hello")
        alice.leave()

        assertEquals(0, carol.store.size())
    }

    @Test
    fun `the sender keeps its own envelope and hands it to later neighbours`() {
        alice.service.send(bob.keys.publicKey, "MSG-1", "alice", "hello")
        alice.meet(carol)
        alice.leave()
        carol.meet(bob)

        assertEquals(1, bob.inbox.size)
    }

    @Test
    fun `two couriers bringing the same message deliver it once`() {
        val dave = Device(courierMode = true)
        alice.inRange = listOf(carol, dave)
        alice.service.send(bob.keys.publicKey, "MSG-1", "alice", "hello")
        alice.inRange = emptyList()

        carol.meet(bob)
        carol.leave()
        dave.meet(bob)

        assertEquals(1, bob.inbox.size)
        assertEquals("dave drops it on the ack too", 0, dave.store.size())
    }

    @Test
    fun `acknowledgements travel with couriers to other carriers`() {
        val dave = Device(courierMode = true)
        alice.inRange = listOf(carol, dave)
        alice.service.send(bob.keys.publicKey, "MSG-1", "alice", "hello")
        alice.inRange = emptyList()

        carol.meet(bob)
        carol.leave()
        carol.meet(dave)

        assertEquals(0, dave.store.size())
    }

    @Test
    fun `a courier cannot read what it carries`() {
        alice.meet(carol)
        alice.service.send(bob.keys.publicKey, "MSG-1", "alice", "secret plan")
        alice.leave()

        assertTrue(carol.inbox.isEmpty())
    }

    @Test
    fun `expired messages are not delivered`() {
        alice.meet(carol)
        alice.service.send(bob.keys.publicKey, "MSG-1", "alice", "hello", lifetimeMs = 60_000)
        alice.leave()

        now += 120_000
        carol.meet(bob)

        assertTrue(bob.inbox.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized messages are refused`() {
        alice.service.send(bob.keys.publicKey, "MSG-1", "alice", "x".repeat(CourierService.MAX_CONTENT_BYTES + 1))
    }
}
