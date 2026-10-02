package com.bluewhale.android.services

import com.bluewhale.android.mesh.BluetoothMeshService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/**
 * The outbox outlives the mesh service a panic wipe recreates, and is keyed by recipient.
 * Without clearing it, a message written before the wipe would be sent from the new
 * identity as soon as that contact became routable again, linking the two.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PanicClearsOutboxTest {

    // Not hex, so neither the mesh nor the Nostr route applies and the message is queued.
    private val unreachablePeer = "offline-peer"

    private fun queuedCount(router: MessageRouter): Int {
        val field = MessageRouter::class.java.getDeclaredField("outbox").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val outbox = field.get(router) as Map<String, List<*>>
        return outbox.values.sumOf { it.size }
    }

    @Test
    fun `clearAll drops queued messages so they are never sent later`() {
        val mesh: BluetoothMeshService = mock()
        whenever(mesh.myPeerID).thenReturn("1111222233334444")
        whenever(mesh.getPeerInfo(any())).thenReturn(null)
        whenever(mesh.hasEstablishedSession(any())).thenReturn(false)

        val router = MessageRouter.getInstance(RuntimeEnvironment.getApplication(), mesh)
        router.clearAll()
        router.sendPrivate("written before the wipe", unreachablePeer, "bob", "MSG-1")
        assertEquals(1, queuedCount(router))

        router.clearAll()
        assertEquals(0, queuedCount(router))

        // Even if a route appears later, nothing from before the wipe goes out.
        whenever(mesh.hasEstablishedSession(any())).thenReturn(true)
        router.onPeersUpdated(listOf(unreachablePeer))
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
        assertEquals(0, queuedCount(router))
    }
}
