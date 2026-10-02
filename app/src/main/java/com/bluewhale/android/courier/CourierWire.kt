package com.bluewhale.android.courier

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Wire format of courier packets (MessageType.COURIER payloads), sent to direct neighbours only.
 *
 * Envelope:
 *   kind(1)=0x01 | version(1)=1 | priority(1) | id(16) | recipientTag(16)
 *   | createdAt(8, ms) | expiresAt(8, ms) | sealedLength(2) | sealed
 *
 * Acknowledgement (the recipient got these envelopes; carriers may drop them):
 *   kind(1)=0x02 | count(1) | id(16) * count
 *
 * Everything before sealedLength is the Noise prologue of the sealed part, so carriers
 * cannot change it without breaking decryption.
 */
object CourierWire {

    const val KIND_ENVELOPE: Byte = 0x01
    const val KIND_ACK: Byte = 0x02
    const val VERSION: Byte = 1
    const val ID_SIZE = 16
    const val TAG_SIZE = 16
    const val MAX_SEALED_BYTES = 4_096
    const val MAX_ACKS_PER_PACKET = 64
    private const val HEADER_SIZE = 1 + 1 + 1 + ID_SIZE + TAG_SIZE + 8 + 8
    private val PROLOGUE_LABEL = "bluewhale-courier-v1".toByteArray()
    private val TAG_LABEL = "bluewhale-courier-tag-v1".toByteArray()

    /** Opaque recipient tag: carriers can match it against keys they know, nothing more. */
    fun recipientTag(staticPublicKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(TAG_LABEL)
            update(staticPublicKey)
            digest()
        }.copyOf(TAG_SIZE)

    fun prologue(envelope: CourierEnvelope): ByteArray = PROLOGUE_LABEL + header(envelope)

    private fun header(e: CourierEnvelope): ByteArray = ByteBuffer.allocate(HEADER_SIZE).apply {
        put(KIND_ENVELOPE)
        put(VERSION)
        put(e.priority.code)
        put(e.id)
        put(e.recipientTag)
        putLong(e.createdAt)
        putLong(e.expiresAt)
    }.array()

    fun encode(e: CourierEnvelope): ByteArray {
        require(e.sealed.size <= MAX_SEALED_BYTES) { "sealed part too large" }
        return header(e) + ByteBuffer.allocate(2).putShort(e.sealed.size.toShort()).array() + e.sealed
    }

    fun encodeAck(ids: List<ByteArray>): ByteArray {
        require(ids.size in 1..MAX_ACKS_PER_PACKET)
        return ByteBuffer.allocate(2 + ids.size * ID_SIZE).apply {
            put(KIND_ACK)
            put(ids.size.toByte())
            ids.forEach { require(it.size == ID_SIZE); put(it) }
        }.array()
    }

    sealed class Decoded {
        data class Envelope(val envelope: CourierEnvelope) : Decoded()
        data class Ack(val ids: List<ByteArray>) : Decoded()
    }

    /** Returns null for anything malformed or from an unknown version. */
    fun decode(bytes: ByteArray): Decoded? = try {
        val buf = ByteBuffer.wrap(bytes)
        when (buf.get()) {
            KIND_ENVELOPE -> {
                if (buf.get() != VERSION) null else {
                    val priority = CourierEnvelope.Priority.fromCode(buf.get())
                    val id = ByteArray(ID_SIZE).also { buf.get(it) }
                    val tag = ByteArray(TAG_SIZE).also { buf.get(it) }
                    val createdAt = buf.long
                    val expiresAt = buf.long
                    val length = buf.short.toInt() and 0xFFFF
                    if (priority == null || length > MAX_SEALED_BYTES || length != buf.remaining()) null
                    else Decoded.Envelope(
                        CourierEnvelope(id, tag, priority, createdAt, expiresAt, ByteArray(length).also { buf.get(it) })
                    )
                }
            }
            KIND_ACK -> {
                val count = buf.get().toInt() and 0xFF
                if (count == 0 || count > MAX_ACKS_PER_PACKET || buf.remaining() != count * ID_SIZE) null
                else Decoded.Ack(List(count) { ByteArray(ID_SIZE).also { buf.get(it) } })
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/** A sealed message being carried towards its recipient. */
class CourierEnvelope(
    val id: ByteArray,
    val recipientTag: ByteArray,
    val priority: Priority,
    val createdAt: Long,
    val expiresAt: Long,
    val sealed: ByteArray
) {
    enum class Priority(val code: Byte) {
        NORMAL(0), URGENT(1);

        companion object {
            fun fromCode(code: Byte) = values().firstOrNull { it.code == code }
        }
    }

    val idHex: String get() = id.toHex()

    fun isExpired(now: Long) = now >= expiresAt
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
