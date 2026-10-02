package com.bluewhale.android.mesh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.model.RoutedPacket
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StealthModeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val tracker: BluetoothConnectionTracker = mock()
    private val fragments: FragmentManager = mock()
    private val broadcaster = BluetoothPacketBroadcaster(scope, tracker, fragments, "0011223344556677")

    private fun packet(type: MessageType = MessageType.ANNOUNCE) = RoutedPacket(
        BluewhalePacket(
            version = 1u,
            type = type.value,
            senderID = ByteArray(8) { 0x11 },
            recipientID = null,
            timestamp = 1_700_000_000_000uL,
            payload = "hello".toByteArray(),
            signature = null,
            ttl = 7u
        )
    )

    @Before
    fun setUp() {
        StealthModePreferenceManager.resetForTesting()
        context.getSharedPreferences("stealth_mode_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        StealthModePreferenceManager.resetForTesting()
        scope.cancel()
    }

    @Test
    fun `stealth is off by default`() {
        StealthModePreferenceManager.init(context)

        assertFalse(StealthModePreferenceManager.isEnabled())
    }

    @Test
    fun `stealth survives a restart`() {
        StealthModePreferenceManager.init(context)
        StealthModePreferenceManager.setEnabled(true)

        StealthModePreferenceManager.resetForTesting()
        StealthModePreferenceManager.init(context)

        assertTrue(StealthModePreferenceManager.isEnabled())
    }

    @Test
    fun `broadcasts are not transmitted in stealth`() {
        StealthModePreferenceManager.setEnabled(true)

        broadcaster.broadcastPacket(packet(), null, null)
        broadcaster.broadcastPacket(packet(MessageType.MESSAGE), null, null)

        verify(fragments, never()).createFragments(any())
        verifyNoInteractions(tracker)
    }

    @Test
    fun `targeted sends and relays are not transmitted in stealth`() {
        StealthModePreferenceManager.setEnabled(true)

        assertFalse(broadcaster.sendToPeer("aabbccddeeff0011", packet(MessageType.NOISE_HANDSHAKE), null, null))
        assertFalse(broadcaster.sendPacketToPeer(packet(MessageType.NOISE_ENCRYPTED), "aabbccddeeff0011", null, null))
        broadcaster.broadcastSinglePacket(packet(), null, null)

        verifyNoInteractions(tracker)
    }

    @Test
    fun `broadcasts go out again once stealth is off`() {
        StealthModePreferenceManager.setEnabled(true)
        StealthModePreferenceManager.setEnabled(false)

        broadcaster.broadcastPacket(packet(), null, null)

        verify(fragments).createFragments(any())
    }
}
