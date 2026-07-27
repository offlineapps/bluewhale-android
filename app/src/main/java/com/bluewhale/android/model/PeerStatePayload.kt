package com.bluewhale.android.model

/**
 * Body of Noise payload type 0x21, the peer state a device states about itself inside an
 * established Noise session.
 *
 * Wire format, shared with bitchat and iOS so the three interoperate:
 *
 *     [version=0x01]
 *     [type=0x01][len=1..8][capability bitfield, little endian, minimal length]
 *     [type=0x02][len=32][Ed25519 signing public key]
 *
 * Unknown TLV types are skipped so newer clients can add fields. Both known fields must
 * appear exactly once.
 *
 * Capability bits currently defined:
 *     bit 8 (0x100)  private media, meaning Noise-encrypted file transfers via payload 0x20
 */
data class PeerStatePayload(
    val capabilities: Long,
    val signingPublicKey: ByteArray
) {

    fun encode(): ByteArray? {
        if (signingPublicKey.size != SIGNING_KEY_LENGTH) return null
        val capabilityBytes = encodeCapabilities(capabilities)
        if (capabilityBytes.size !in 1..8) return null

        val out = ArrayList<Byte>(3 + capabilityBytes.size + 2 + SIGNING_KEY_LENGTH)
        out.add(VERSION.toByte())
        out.add(TLV_CAPABILITIES.toByte())
        out.add(capabilityBytes.size.toByte())
        out.addAll(capabilityBytes.toList())
        out.add(TLV_SIGNING_KEY.toByte())
        out.add(SIGNING_KEY_LENGTH.toByte())
        out.addAll(signingPublicKey.toList())
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PeerStatePayload
        if (capabilities != other.capabilities) return false
        return signingPublicKey.contentEquals(other.signingPublicKey)
    }

    override fun hashCode(): Int = 31 * capabilities.hashCode() + signingPublicKey.contentHashCode()

    companion object {
        const val VERSION = 0x01
        const val CAPABILITY_PRIVATE_MEDIA = 1L shl 8

        private const val TLV_CAPABILITIES = 0x01
        private const val TLV_SIGNING_KEY = 0x02
        private const val SIGNING_KEY_LENGTH = 32

        /** Little endian, shortest representation that still holds the value. */
        fun encodeCapabilities(value: Long): ByteArray {
            val bytes = ArrayList<Byte>(8)
            var remaining = value
            do {
                bytes.add(remaining.toByte())
                remaining = remaining ushr 8
            } while (remaining != 0L)
            return bytes.toByteArray()
        }

        fun decode(data: ByteArray): PeerStatePayload? {
            if (data.isEmpty()) return null
            if ((data[0].toInt() and 0xFF) != VERSION) return null

            var offset = 1
            var capabilities: Long? = null
            var signingKey: ByteArray? = null

            while (offset < data.size) {
                if (offset + 2 > data.size) return null
                val type = data[offset].toInt() and 0xFF
                val length = data[offset + 1].toInt() and 0xFF
                offset += 2
                if (offset + length > data.size) return null
                val value = data.copyOfRange(offset, offset + length)
                offset += length

                when (type) {
                    TLV_CAPABILITIES -> {
                        // Accept any width a sender used, rather than insisting on the
                        // shortest one, so a client that pads cannot be locked out.
                        if (capabilities != null || length !in 1..8) return null
                        var raw = 0L
                        value.forEachIndexed { index, byte ->
                            raw = raw or ((byte.toLong() and 0xFF) shl (8 * index))
                        }
                        capabilities = raw
                    }

                    TLV_SIGNING_KEY -> {
                        if (signingKey != null || length != SIGNING_KEY_LENGTH) return null
                        signingKey = value
                    }

                    else -> Unit
                }
            }

            val caps = capabilities ?: return null
            val key = signingKey ?: return null
            return PeerStatePayload(caps, key)
        }
    }
}
