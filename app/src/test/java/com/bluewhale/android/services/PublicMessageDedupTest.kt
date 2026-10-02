package com.bluewhale.android.services

import com.bluewhale.android.model.BluewhaleMessage
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import com.bluewhale.android.sync.PacketIdUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.util.Date

/**
 * Public messages used to get a random ID on every receipt, so a packet delivered again
 * by gossip sync or over a second path was shown twice, and a re-delivered file was
 * saved to disk again.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PublicMessageDedupTest {

    @Before
    fun reset() = AppStateStore.clear()

    private fun packet(payload: String, timestamp: Long = 1_700_000_000_000L) = BluewhalePacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = ByteArray(8) { 0x0A },
        recipientID = null,
        timestamp = timestamp.toULong(),
        payload = payload.toByteArray(),
        ttl = 7u
    )

    private fun message(id: String, content: String = "hello", timestamp: Long = 1_700_000_000_000L) = BluewhaleMessage(
        id = id,
        sender = "alice",
        content = content,
        timestamp = Date(timestamp),
        senderPeerID = "0a0a0a0a0a0a0a0a"
    )

    @Test
    fun `the same packet always maps to the same message ID`() {
        // A relayed or synced copy differs only in TTL, which is not part of the ID.
        val original = packet("hello")
        val redelivered = original.copy(ttl = 0u)
        assertEquals(PacketIdUtil.computeIdHex(original), PacketIdUtil.computeIdHex(redelivered))
    }

    @Test
    fun `a re-delivered public message is shown once`() {
        val id = PacketIdUtil.computeIdHex(packet("hello")).uppercase()
        AppStateStore.addPublicMessage(message(id))
        AppStateStore.addPublicMessage(message(id))
        assertEquals(1, AppStateStore.publicMessages.value.size)
        assertTrue(AppStateStore.hasMessageId(id))
    }

    @Test
    fun `a copy under a different ID but identical content is shown once`() {
        AppStateStore.addPublicMessage(message("RANDOM-1"))
        AppStateStore.addPublicMessage(message("RANDOM-2"))
        assertEquals(1, AppStateStore.publicMessages.value.size)
    }

    @Test
    fun `distinct messages are all kept`() {
        AppStateStore.addPublicMessage(message("A", content = "one"))
        AppStateStore.addPublicMessage(message("B", content = "two"))
        AppStateStore.addPublicMessage(message("C", content = "one", timestamp = 1_700_000_000_001L))
        assertEquals(3, AppStateStore.publicMessages.value.size)
    }
}
