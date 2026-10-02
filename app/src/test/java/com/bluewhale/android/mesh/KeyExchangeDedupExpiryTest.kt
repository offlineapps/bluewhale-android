package com.bluewhale.android.mesh

import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.crypto.EncryptionService
import com.bluewhale.android.model.RoutedPacket
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * Handshake dedup entries were only trimmed once there were 1,000 of them, so the same
 * handshake message arriving again after a failed attempt was ignored indefinitely.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class KeyExchangeDedupExpiryTest {

    private val me = "00000000deadbeef"
    private val peer = "aabbccddeeff0011"

    private fun hexToBytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun handshake(payload: ByteArray) = RoutedPacket(
        BluewhalePacket(
            version = 1u,
            type = MessageType.NOISE_HANDSHAKE.value,
            senderID = hexToBytes(peer),
            recipientID = hexToBytes(me),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u
        ),
        peer
    )

    private fun exchangeSeen(manager: SecurityManager): Boolean {
        val field = SecurityManager::class.java.getDeclaredField("processedKeyExchanges").apply { isAccessible = true }
        return (field.get(manager) as Set<*>).isNotEmpty()
    }

    @Test
    fun `handshake dedup entries expire after a minute`() = runBlocking {
        val manager = SecurityManager(EncryptionService(ApplicationProvider.getApplicationContext()), me)
        manager.handleNoiseHandshake(handshake(ByteArray(32) { 3 }))

        manager.cleanupOldData(System.currentTimeMillis() + 61_000L)

        assertFalse("an old handshake entry must not block a later retry", exchangeSeen(manager))
    }
}
