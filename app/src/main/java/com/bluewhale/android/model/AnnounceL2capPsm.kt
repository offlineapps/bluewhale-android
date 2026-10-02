package com.bluewhale.android.model

/**
 * Optional announce TLV advertising the LE L2CAP channel (PSM) this device accepts large
 * transfers on. Peers that do not know the type skip it, as IdentityAnnouncement does.
 *
 * Layout: type 0x42, length 2, PSM as an unsigned 16-bit big-endian value.
 */
object AnnounceL2capPsm {
    const val TYPE: Byte = 0x42
    private const val LENGTH = 2

    fun encode(psm: Int): ByteArray {
        require(psm in 1..0xFFFF) { "PSM out of range" }
        return byteArrayOf(TYPE, LENGTH.toByte(), (psm ushr 8).toByte(), psm.toByte())
    }

    /** Scans an announce payload's TLVs for the PSM. Null when absent or malformed. */
    fun decode(payload: ByteArray): Int? {
        var offset = 0
        while (offset + 2 <= payload.size) {
            val type = payload[offset]
            val length = payload[offset + 1].toInt() and 0xFF
            offset += 2
            if (offset + length > payload.size) return null
            if (type == TYPE) {
                if (length != LENGTH) return null
                val psm = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                return psm.takeIf { it > 0 }
            }
            offset += length
        }
        return null
    }
}
