package com.bluewhale.android.find

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.mesh.BluetoothMeshService
import com.bluewhale.android.mesh.RssiPollScheduler
import com.bluewhale.android.ui.ChannelManager
import com.bluewhale.android.ui.ChatState
import com.bluewhale.android.ui.CommandProcessor
import com.bluewhale.android.ui.DataManager
import com.bluewhale.android.ui.MessageManager
import com.bluewhale.android.ui.NoiseSessionDelegate
import com.bluewhale.android.ui.PrivateChatManager
import com.bluewhale.android.util.AppConstants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FindCommandTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val chatState = ChatState(scope = scope)
    private val messageManager = MessageManager(state = chatState)
    private val mesh: BluetoothMeshService = mock()
    private val processor = CommandProcessor(
        chatState,
        messageManager,
        ChannelManager(chatState, messageManager, DataManager(context), scope),
        PrivateChatManager(chatState, messageManager, DataManager(context), mock<NoiseSessionDelegate>()),
        null,
        scope
    )

    @After
    fun tearDown() = FindMode.stop()

    private fun run(command: String) = processor.processCommand(command, mesh, "me", { _, _, _ -> })

    @Test
    fun `find by nickname opens the finder for that peer`() {
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf("aabbccddeeff0011" to "alice"))

        run("/find @alice")

        assertEquals("aabbccddeeff0011", FindMode.target.value)
    }

    @Test
    fun `find with an unknown name says so`() {
        whenever(mesh.getPeerNicknames()).thenReturn(emptyMap())

        run("/find bob")

        assertNull(FindMode.target.value)
        assertTrue(chatState.getMessagesValue().any { it.content.contains("nobody called bob") })
    }

    @Test
    fun `find without a name uses the open private chat`() {
        chatState.setSelectedPrivateChatPeer("aabbccddeeff0011")

        run("/find")

        assertEquals("aabbccddeeff0011", FindMode.target.value)
    }

    @Test
    fun `rssi is read fast only while finding`() {
        assertEquals(AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS, RssiPollScheduler.nextDelayMs(2, finding = false))
        assertEquals(RssiPollScheduler.FIND_INTERVAL_MS, RssiPollScheduler.nextDelayMs(2, finding = true))
        assertEquals("nothing to read without client links", AppConstants.Mesh.RSSI_IDLE_INTERVAL_MS, RssiPollScheduler.nextDelayMs(0, finding = true))
    }
}
