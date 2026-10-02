package com.bluewhale.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.bluewhale.android.model.RoutedPacket
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.os.Build
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Android allows one outstanding GATT operation per link. Writes used to be fired back to back,
 * so the second failed or replaced the first. They now wait for the completion callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class GattSendQueueTest {

    private val address = "AA:BB:CC:DD:EE:01"
    private val peerID = "aabbccddeeff0011"
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val gatt: BluetoothGatt = mock()
    private val device: BluetoothDevice = mock()
    private val characteristic = BluetoothGattCharacteristic(
        UUID.randomUUID(),
        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    )
    private val tracker: BluetoothConnectionTracker = mock()

    init {
        whenever(device.address).thenReturn(address)
        whenever(tracker.getSubscribedDevices()).thenReturn(emptyList())
        whenever(tracker.getConnectedDevices()).thenReturn(
            mapOf(address to BluetoothConnectionTracker.DeviceConnection(device, gatt, characteristic, isClient = true))
        )
        whenever(tracker.addressPeerMap).thenReturn(ConcurrentHashMap(mapOf(address to peerID)))
    }

    private val broadcaster = BluetoothPacketBroadcaster(scope, tracker, null, "0011223344556677")

    @After
    fun tearDown() = scope.cancel()

    private fun packet(text: String) = RoutedPacket(
        BluewhalePacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = ByteArray(8) { 1 },
            recipientID = null,
            timestamp = 1_700_000_000_000uL,
            payload = text.toByteArray(),
            signature = null,
            ttl = 7u
        )
    )

    private fun writes() = argumentCaptor<ByteArray>().also {
        verify(gatt, org.mockito.kotlin.atLeast(0)).writeCharacteristic(any(), it.capture(), any())
    }.allValues

    @Test
    fun `a second write waits for the first to complete`() {
        assertTrue(broadcaster.sendToPeer(peerID, packet("one"), null, null))
        assertTrue(broadcaster.sendToPeer(peerID, packet("two"), null, null))

        assertTrue("only one write in flight", writes().size == 1)

        broadcaster.onGattClientWriteComplete(address, BluetoothGatt.GATT_SUCCESS)
        assertTrue(writes().size == 2)
        assertTrue(String(writes()[1]).contains("two"))
    }

    @Test
    fun `writes go out without response`() {
        broadcaster.sendToPeer(peerID, packet("one"), null, null)

        verify(gatt).writeCharacteristic(eq(characteristic), any(), eq(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE))
    }

    @Test
    fun `a failed write is retried before the next one`() {
        broadcaster.sendToPeer(peerID, packet("one"), null, null)
        broadcaster.sendToPeer(peerID, packet("two"), null, null)

        broadcaster.onGattClientWriteComplete(address, BluetoothGatt.GATT_FAILURE)
        Thread.sleep(100) // retry delay

        val sent = writes().map { String(it) }
        assertTrue(sent.size == 2)
        assertTrue("retried the same packet", sent[1].contains("one"))
    }

    @Test
    fun `a disconnect drops what was queued for the link`() {
        broadcaster.sendToPeer(peerID, packet("one"), null, null)
        broadcaster.sendToPeer(peerID, packet("two"), null, null)

        broadcaster.onLinkDisconnected(address)
        broadcaster.onGattClientWriteComplete(address, BluetoothGatt.GATT_SUCCESS)

        verify(gatt, times(1)).writeCharacteristic(any(), any(), any())
    }
}
