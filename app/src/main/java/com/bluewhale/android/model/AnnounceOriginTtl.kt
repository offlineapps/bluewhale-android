package com.bluewhale.android.model

import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.util.AppConstants

/**
 * The TTL an announce was sent with, carried inside the signed announce payload.
 *
 * Receivers tell a direct neighbour's announce from a relayed one by its TTL: only the
 * originator's own transmission still has the TTL it was stamped with. That used to mean
 * "TTL == 7", which a device with a reduced message range never sends, so such a device
 * was never bound to its link: no direct private sends, no redundant link cleanup, no
 * initial sync. Announcing the starting TTL lets a receiver recognise it at any range.
 *
 * Only added when the TTL is below the default, so a normal announce is unchanged on the
 * wire. Announce decoders here, upstream and on iOS skip TLV types they do not know.
 */
object AnnounceOriginTtl {
    const val TLV_TYPE: UByte = 0x40u

    fun encode(ttl: UByte): ByteArray = byteArrayOf(TLV_TYPE.toByte(), 1, ttl.toByte())

    /** The origin TTL in an announce payload, or null when absent or malformed. */
    fun decode(payload: ByteArray): UByte? {
        var offset = 0
        while (offset + 2 <= payload.size) {
            val type = payload[offset].toUByte()
            val len = payload[offset + 1].toUByte().toInt()
            offset += 2
            if (offset + len > payload.size) return null
            if (type == TLV_TYPE) return if (len == 1) payload[offset].toUByte() else null
            offset += len
        }
        return null
    }

    /** True when this announce has not been relayed: it still carries its starting TTL. */
    fun arrivedUnrelayed(packet: BluewhalePacket): Boolean {
        if (packet.ttl == AppConstants.MESSAGE_TTL_HOPS) return true
        val origin = decode(packet.payload) ?: return false
        return packet.ttl == origin
    }
}
