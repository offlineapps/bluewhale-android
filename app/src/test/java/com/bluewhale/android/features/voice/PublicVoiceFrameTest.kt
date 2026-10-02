package com.bluewhale.android.features.voice

import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.mesh.MessageHandler
import com.bluewhale.android.mesh.MessageHandlerDelegate
import com.bluewhale.android.mesh.PeerInfo
import com.bluewhale.android.model.RoutedPacket
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import com.bluewhale.android.protocol.SpecialRecipients
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/** Public push-to-talk frames are only taken from verified peers, fresh, and well formed. */
@RunWith(RobolectricTestRunner::class)
class PublicVoiceFrameTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val alice = "aabbccddeeff0011"
    private val delegate: MessageHandlerDelegate = mock()
    private val handler = MessageHandler("0011223344556677", context).also { it.delegate = delegate }

    private fun peer(verified: Boolean) = PeerInfo(
        id = alice,
        nickname = "alice",
        isConnected = true,
        isDirectConnection = true,
        noisePublicKey = null,
        signingPublicKey = null,
        isVerifiedNickname = verified,
        lastSeen = System.currentTimeMillis()
    )

    init {
        whenever(delegate.getBroadcastRecipient()).thenReturn(SpecialRecipients.BROADCAST)
        whenever(delegate.getPeerNickname(any())).thenReturn("alice")
    }

    @org.junit.Before
    fun setUp() = LiveVoiceManager.resetInstanceForTesting()

    @After
    fun tearDown() = LiveVoiceManager.resetInstanceForTesting()

    private fun frame(
        payload: ByteArray = start(),
        timestamp: Long = System.currentTimeMillis(),
        recipient: ByteArray? = SpecialRecipients.BROADCAST
    ) = RoutedPacket(
        BluewhalePacket(
            version = 1u,
            type = MessageType.VOICE_FRAME.value,
            senderID = ByteArray(8) { 0x11 },
            recipientID = recipient,
            timestamp = timestamp.toULong(),
            payload = payload,
            signature = null,
            ttl = 7u
        ),
        peerID = alice
    )

    private fun start() = VoiceBurstPacket.create(
        VoiceBurstPacket.makeBurstID(), 0, VoiceBurstPacket.Kind.Start(VoiceBurstCodec.AAC_LC_16K_MONO)
    )!!.encode()

    @Test
    fun `a fresh frame from a verified peer is taken`() {
        whenever(delegate.getPeerInfo(alice)).thenReturn(peer(verified = true))

        assertTrue(handler.handlePublicVoiceFrame(frame()))
    }

    @Test
    fun `frames from unverified peers are refused`() {
        whenever(delegate.getPeerInfo(alice)).thenReturn(peer(verified = false))

        assertFalse(handler.handlePublicVoiceFrame(frame()))
    }

    @Test
    fun `stale or future frames are refused`() {
        whenever(delegate.getPeerInfo(alice)).thenReturn(peer(verified = true))

        assertFalse(handler.handlePublicVoiceFrame(frame(timestamp = System.currentTimeMillis() - 60_000)))
        assertFalse(handler.handlePublicVoiceFrame(frame(timestamp = System.currentTimeMillis() + 60_000)))
    }

    @Test
    fun `addressed frames are not public voice`() {
        whenever(delegate.getPeerInfo(alice)).thenReturn(peer(verified = true))

        assertFalse(handler.handlePublicVoiceFrame(frame(recipient = ByteArray(8) { 0x22 })))
    }

    @Test
    fun `malformed payloads are refused`() {
        whenever(delegate.getPeerInfo(alice)).thenReturn(peer(verified = true))

        assertFalse(handler.handlePublicVoiceFrame(frame(payload = byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `a started burst shows as a live message`() {
        whenever(delegate.getPeerInfo(alice)).thenReturn(peer(verified = true))

        handler.handlePublicVoiceFrame(frame())

        assertTrue(LiveVoiceManager.getInstance(context).liveMessageIDs.value.isNotEmpty())
        assertTrue(LiveVoiceManager.getInstance(context).activePublicTalker.value == "alice")
    }
}
