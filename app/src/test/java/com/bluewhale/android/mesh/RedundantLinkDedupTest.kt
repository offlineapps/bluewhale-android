package com.bluewhale.android.mesh

import android.bluetooth.BluetoothAdapter
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * A device's advertised value rotates and is not derived from anything it publishes, so a
 * scanner cannot recognise an address belonging to a peer it already holds a link to.
 * The duplicate that follows has to be caught once the peer announces on both links.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RedundantLinkDedupTest {

    private val peerID = "aabbccddeeff0011"

    private fun tracker() = BluetoothConnectionTracker(
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        PowerManager(ApplicationProvider.getApplicationContext())
    )

    private fun device(address: String) =
        BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)

    private fun link(
        tracker: BluetoothConnectionTracker,
        address: String,
        isClient: Boolean,
        connectedAt: Long
    ) {
        tracker.addDeviceConnection(
            address,
            BluetoothConnectionTracker.DeviceConnection(
                device = device(address),
                isClient = isClient,
                connectedAt = connectedAt,
                peerID = peerID
            )
        )
        tracker.addressPeerMap[address] = peerID
    }

    @Test
    fun `a single link is never redundant`() {
        val tracker = tracker()
        link(tracker, "AA:BB:CC:DD:EE:01", isClient = false, connectedAt = 1000)

        assertTrue(tracker.redundantAddressesForPeer(peerID).isEmpty())
    }

    @Test
    fun `the link we dialled is dropped and the inbound one kept`() {
        val tracker = tracker()
        val inbound = "AA:BB:CC:DD:EE:01"
        val outbound = "AA:BB:CC:DD:EE:02"

        // The peer connected to us, then advertised from a second address that scanning
        // could not recognise, so we dialled it as well.
        link(tracker, inbound, isClient = false, connectedAt = 1000)
        link(tracker, outbound, isClient = true, connectedAt = 2000)

        assertEquals(listOf(outbound), tracker.redundantAddressesForPeer(peerID))
    }

    @Test
    fun `between two dialled links the newer one is dropped`() {
        val tracker = tracker()
        val older = "AA:BB:CC:DD:EE:01"
        val newer = "AA:BB:CC:DD:EE:02"

        link(tracker, older, isClient = true, connectedAt = 1000)
        link(tracker, newer, isClient = true, connectedAt = 2000)

        assertEquals(listOf(newer), tracker.redundantAddressesForPeer(peerID))
    }

    /**
     * The choice must not depend on which announce arrived last, or the two links take
     * turns tearing each other down.
     */
    @Test
    fun `the same link is chosen no matter which side announced last`() {
        val inbound = "AA:BB:CC:DD:EE:01"
        val outbound = "AA:BB:CC:DD:EE:02"

        val a = tracker()
        link(a, inbound, isClient = false, connectedAt = 1000)
        link(a, outbound, isClient = true, connectedAt = 2000)

        val b = tracker()
        link(b, outbound, isClient = true, connectedAt = 2000)
        link(b, inbound, isClient = false, connectedAt = 1000)

        assertEquals(a.redundantAddressesForPeer(peerID), b.redundantAddressesForPeer(peerID))
        assertEquals(listOf(outbound), b.redundantAddressesForPeer(peerID))
    }

    @Test
    fun `an address that is mapped but not connected is not reported`() {
        val tracker = tracker()
        link(tracker, "AA:BB:CC:DD:EE:01", isClient = false, connectedAt = 1000)
        // Bound to the peer by an earlier announce, but the link is gone.
        tracker.addressPeerMap["AA:BB:CC:DD:EE:02"] = peerID

        assertTrue(tracker.redundantAddressesForPeer(peerID).isEmpty())
    }

    @Test
    fun `links to different peers are left alone`() {
        val tracker = tracker()
        link(tracker, "AA:BB:CC:DD:EE:01", isClient = false, connectedAt = 1000)
        val other = "AA:BB:CC:DD:EE:02"
        tracker.addDeviceConnection(
            other,
            BluetoothConnectionTracker.DeviceConnection(
                device = device(other),
                isClient = true,
                connectedAt = 2000,
                peerID = "1122334455667788"
            )
        )
        tracker.addressPeerMap[other] = "1122334455667788"

        assertTrue(tracker.redundantAddressesForPeer(peerID).isEmpty())
        assertTrue(tracker.redundantAddressesForPeer("1122334455667788").isEmpty())
    }
}
