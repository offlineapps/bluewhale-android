package com.bluewhale.android.services

import com.bluewhale.android.mesh.BluetoothMeshService
import com.bluewhale.android.mesh.PeerInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/**
 * Queued private messages used to wait for a foreground UI poll or a peer list change, a
 * lost handshake was never retried, and nothing ever expired, so a message could sit in
 * memory forever while its recipient stood in range.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OutboxRetryTest {

    private val peer = "1111222233334444"
    private val mesh: BluetoothMeshService = mock()
    private var now = 1_000_000L
    private val expired = mutableListOf<String>()
    private lateinit var router: MessageRouter

    private fun peerInfo(connected: Boolean) = PeerInfo(
        id = peer,
        nickname = "bob",
        isConnected = connected,
        isDirectConnection = connected,
        noisePublicKey = ByteArray(32) { 1 },
        signingPublicKey = ByteArray(32) { 2 },
        isVerifiedNickname = true,
        lastSeen = now
    )

    private fun queued(): Int {
        val field = MessageRouter::class.java.getDeclaredField("outbox").apply { isAccessible = true }
        return (field.get(router) as Map<*, *>).values.sumOf { (it as List<*>).size }
    }

    @Before
    fun setUp() {
        MessageRouter.resetForTesting()
        MessageRouter.disableSchedulerForTesting = true
        whenever(mesh.myPeerID).thenReturn("aaaabbbbccccdddd")
        whenever(mesh.getPeerInfo(any())).thenReturn(peerInfo(connected = false))
        whenever(mesh.hasEstablishedSession(any())).thenReturn(false)
        router = MessageRouter.getInstance(RuntimeEnvironment.getApplication(), mesh)
        router.clock = { now }
        router.onMessageExpired = { expired += it }
    }

    @After
    fun tearDown() {
        MessageRouter.resetForTesting()
        MessageRouter.disableSchedulerForTesting = false
    }

    @Test
    fun `a queued message expires after a day and is reported`() {
        router.sendPrivate("hi", peer, "bob", "MSG-1")
        assertEquals(1, queued())

        router.tickOutbox(now + MessageRouter.OUTBOX_MESSAGE_TTL_MS + 1)

        assertEquals(0, queued())
        assertEquals(listOf("MSG-1"), expired)
    }

    @Test
    fun `the outbox is bounded per peer and evicts the oldest`() {
        repeat(MessageRouter.OUTBOX_MAX_PER_PEER + 1) { i -> router.sendPrivate("m$i", peer, "bob", "MSG-$i") }

        assertEquals(MessageRouter.OUTBOX_MAX_PER_PEER, queued())
        assertEquals(listOf("MSG-0"), expired)
    }

    @Test
    fun `a connected peer without a session gets handshake retries with backoff`() {
        whenever(mesh.getPeerInfo(any())).thenReturn(peerInfo(connected = true))

        router.sendPrivate("hi", peer, "bob", "MSG-1")
        verify(mesh, times(1)).initiateNoiseHandshake(peer)

        now += 1_000
        router.tickOutbox()
        verify(mesh, times(1)).initiateNoiseHandshake(peer) // still inside the backoff

        now += 5_000
        router.tickOutbox()
        verify(mesh, times(2)).initiateNoiseHandshake(peer)
    }

    @Test
    fun `the scheduler delivers once a session is up`() {
        whenever(mesh.getPeerInfo(any())).thenReturn(peerInfo(connected = true))
        router.sendPrivate("hi", peer, "bob", "MSG-1")

        whenever(mesh.hasEstablishedSession(any())).thenReturn(true)
        router.tickOutbox()

        verify(mesh).sendPrivateMessage(eq("hi"), eq(peer), eq("bob"), anyOrNull())
        assertEquals(0, queued())
    }
}
