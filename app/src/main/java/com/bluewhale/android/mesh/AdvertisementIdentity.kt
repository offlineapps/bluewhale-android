package com.bluewhale.android.mesh

import android.content.Context
import com.bluewhale.android.identity.SecureIdentityStateManager
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The 8 bytes put in the BLE scan response so scanners can tell two advertisements apart.
 *
 * It must not be derived from anything public. The peer ID and the Noise key are both
 * published in announcements, so deriving from either would let anyone who has heard one
 * announcement recognise that device forever. It is an HMAC over a device-local secret
 * and the current time window instead, so it is stable long enough to be useful for
 * deduplicating a scan burst and unlinkable across windows.
 */
object AdvertisementIdentity {

    const val ROTATION_PERIOD_MS = 15 * 60 * 1000L
    private const val SECRET_KEY = "advertisement_rotation_secret"
    private const val ID_BYTES = 8

    fun currentId(context: Context, nowMs: Long = System.currentTimeMillis()): ByteArray =
        idForWindow(secret(context), nowMs / ROTATION_PERIOD_MS)

    /** Milliseconds until the current value stops being valid. */
    fun millisUntilRotation(nowMs: Long = System.currentTimeMillis()): Long =
        ROTATION_PERIOD_MS - (nowMs % ROTATION_PERIOD_MS)

    fun idForWindow(secret: ByteArray, window: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val input = ByteArray(8)
        var remaining = window
        for (i in 7 downTo 0) {
            input[i] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
        return mac.doFinal(input).copyOf(ID_BYTES)
    }

    private fun secret(context: Context): ByteArray {
        val store = SecureIdentityStateManager(context)
        store.getSecureValue(SECRET_KEY)?.let { stored ->
            runCatching { android.util.Base64.decode(stored, android.util.Base64.NO_WRAP) }
                .getOrNull()
                ?.takeIf { it.size == 32 }
                ?.let { return it }
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        store.storeSecureValue(SECRET_KEY, android.util.Base64.encodeToString(fresh, android.util.Base64.NO_WRAP))
        return fresh
    }
}
