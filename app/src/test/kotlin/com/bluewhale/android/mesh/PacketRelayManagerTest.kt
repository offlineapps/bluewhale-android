
package com.bluewhale.android.mesh

import com.bluewhale.android.model.RoutedPacket
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import com.bluewhale.android.util.toHexString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class PacketRelayManagerTest {

    private lateinit var packetRelayManager: PacketRelayManager
    private val delegate: PacketRelayManagerDelegate = mock()

    private val myPeerID = "1111111111111111"
    private val otherPeerID = "2222222222222222"
    private val nextHopPeerID = "3333333333333333"
    private val finalRecipientID = "4444444444444444"

    @Before
    fun setUp() {
        packetRelayManager = PacketRelayManager(myPeerID)
        packetRelayManager.delegate = delegate
        whenever(delegate.getNetworkSize()).thenReturn(10)
        whenever(delegate.getBroadcastRecipient()).thenReturn(byteArrayOf(0,0,0,0,0,0,0,0))
    }

    private fun createPacket(route: List<ByteArray>?, recipient: String? = null, ttl: UByte = 5u): BluewhalePacket {
        return BluewhalePacket(
            type = MessageType.MESSAGE.value,
            senderID = hexStringToPeerBytes(otherPeerID),
            recipientID = recipient?.let { hexStringToPeerBytes(it) },
            timestamp = System.currentTimeMillis().toULong(),
            payload = "hello".toByteArray(),
            ttl = ttl,
            route = route
        )
    }

    @Test
    fun `packet with duplicate hops is dropped`() = runTest {
        val route = listOf(
            hexStringToPeerBytes(nextHopPeerID),
            hexStringToPeerBytes(nextHopPeerID)
        )
        val packet = createPacket(route)
        val routedPacket = RoutedPacket(packet, otherPeerID)

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate, never()).sendToPeer(any(), any())
        verify(delegate, never()).broadcastPacket(any())
    }

    @Test
    fun `valid source-routed packet is relayed to next hop`() = runTest {
        val route = listOf(
            hexStringToPeerBytes(myPeerID),
            hexStringToPeerBytes(nextHopPeerID)
        )
        val packet = createPacket(route, finalRecipientID)
        val routedPacket = RoutedPacket(packet, otherPeerID)
        whenever(delegate.sendToPeer(any(), any())).thenReturn(true)

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate).sendToPeer(org.mockito.kotlin.eq(nextHopPeerID), any())
        verify(delegate, never()).broadcastPacket(any())
    }

    @Test
    fun `last hop does not relay further`() = runTest {
        val route = listOf(
            hexStringToPeerBytes(myPeerID)
        )
        val packet = createPacket(route, finalRecipientID)
        val routedPacket = RoutedPacket(packet, otherPeerID)
        whenever(delegate.sendToPeer(any(), any())).thenReturn(true)

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate).sendToPeer(org.mockito.kotlin.eq(finalRecipientID), any())
        verify(delegate, never()).broadcastPacket(any())
    }
    
    @Test
    fun `packet with empty route is broadcast`() = runTest {
        val packet = createPacket(null)
        val routedPacket = RoutedPacket(packet, otherPeerID)

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate, never()).sendToPeer(any(), any())
        verify(delegate).broadcastPacket(any())
    }

    @Test
    fun `packet sent with a range of two hops is relayed once`() = runTest {
        val packet = createPacket(null, ttl = 1u)
        val routedPacket = RoutedPacket(packet, otherPeerID)
        val relayed = argumentCaptor<RoutedPacket>()

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate).broadcastPacket(relayed.capture())
        assertEquals(0u.toUByte(), relayed.firstValue.packet.ttl)
    }

    @Test
    fun `packet that reached the end of its range is not relayed further`() = runTest {
        val packet = createPacket(null, ttl = 0u)
        val routedPacket = RoutedPacket(packet, otherPeerID)

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate, never()).broadcastPacket(any())
        verify(delegate, never()).sendToPeer(any(), any())
    }

    @Test
    fun `our own range does not shorten packets we relay for other peers`() = runTest {
        MeshRangePreferenceManager.resetForTesting()
        MeshRangePreferenceManager.setRangeHops(1)
        val packet = createPacket(null, ttl = 5u)
        val routedPacket = RoutedPacket(packet, otherPeerID)
        val relayed = argumentCaptor<RoutedPacket>()

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate).broadcastPacket(relayed.capture())
        assertEquals(4u.toUByte(), relayed.firstValue.packet.ttl)
    }

    private fun hexStringToPeerBytes(hex: String): ByteArray {
        val result = ByteArray(8)
        var idx = 0
        var out = 0
        while (idx + 1 < hex.length && out < 8) {
            val b = hex.substring(idx, idx + 2).toIntOrNull(16)?.toByte() ?: 0
            result[out++] = b
            idx += 2
        }
        return result
    }
}
