package com.bluewhale.android.courier

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom

/**
 * Courier mode: store-and-carry delivery of sealed private messages between parts of the mesh
 * that are never connected at the same time.
 *
 * A message for a contact who is out of reach is sealed to their static Noise key
 * ([CourierSeal]) and handed to every direct neighbour. Neighbours with courier mode on keep it
 * and hand it on to the neighbours they meet later, until it reaches the recipient or expires.
 * The recipient answers with an acknowledgement that carriers pass on so copies get dropped.
 *
 * Couriers learn an opaque recipient tag, the expiry and the priority. Not the sender, not the
 * content.
 *
 * Transport and identity are injected, so the protocol runs without a radio in tests.
 */
class CourierService(
    private val store: CourierStore,
    private val myStaticPublicKey: () -> ByteArray,
    private val seal: (recipientKey: ByteArray, plaintext: ByteArray, prologue: ByteArray) -> ByteArray,
    private val open: (sealed: ByteArray, prologue: ByteArray) -> CourierSeal.Opened?,
    /** Sends a COURIER payload to direct neighbours only (TTL 0). */
    private val sendToNeighbours: (payload: ByteArray) -> Unit,
    private val deliver: (Delivery) -> Unit,
    private val courierModeEnabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom()
) {
    companion object {
        const val DEFAULT_LIFETIME_MS = 72 * 60 * 60 * 1000L
        const val MAX_CONTENT_BYTES = 2_000
        /** Envelopes handed to one neighbour per encounter, so a big store does not hog a link. */
        const val MAX_PER_ENCOUNTER = 50
    }

    /** A courier message that reached us. */
    data class Delivery(
        val senderStaticKey: ByteArray,
        val messageId: String,
        val senderNickname: String,
        val content: String,
        val sentAt: Long
    )

    /** Seals [content] for [recipientStaticKey] and hands it to whoever is in range now. */
    fun send(
        recipientStaticKey: ByteArray,
        messageId: String,
        senderNickname: String,
        content: String,
        priority: CourierEnvelope.Priority = CourierEnvelope.Priority.NORMAL,
        lifetimeMs: Long = DEFAULT_LIFETIME_MS
    ): CourierEnvelope {
        val body = content.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_CONTENT_BYTES) { "message too long for a courier (max $MAX_CONTENT_BYTES bytes)" }
        val now = clock()
        val unsealed = CourierEnvelope(
            id = ByteArray(CourierWire.ID_SIZE).also { random.nextBytes(it) },
            recipientTag = CourierWire.recipientTag(recipientStaticKey),
            priority = priority,
            createdAt = now,
            expiresAt = now + lifetimeMs,
            sealed = ByteArray(0)
        )
        val plaintext = encodeInner(messageId, senderNickname, now, body)
        val envelope = CourierEnvelope(
            unsealed.id, unsealed.recipientTag, unsealed.priority, unsealed.createdAt, unsealed.expiresAt,
            seal(recipientStaticKey, plaintext, CourierWire.prologue(unsealed))
        )
        store.accept(envelope, own = true)
        sendToNeighbours(CourierWire.encode(envelope))
        return envelope
    }

    /** A direct neighbour with static key [neighbourKey] (hex) is in range: hand over what we carry. */
    fun onNeighbour(neighbourKey: String) {
        val carry = courierModeEnabled()
        val batch = store.pendingFor(neighbourKey, includeCarried = carry).take(MAX_PER_ENCOUNTER)
        batch.forEach { sendToNeighbours(CourierWire.encode(it)) }
        store.markHandedTo(neighbourKey, batch)
        if (carry) {
            store.deliveredIds(CourierWire.MAX_ACKS_PER_PACKET).takeIf { it.isNotEmpty() }?.let {
                sendToNeighbours(CourierWire.encodeAck(it))
            }
        }
    }

    /** A COURIER payload arrived from the neighbour with static key [fromKey] (hex), if known. */
    fun onPayload(payload: ByteArray, fromKey: String?) {
        when (val decoded = CourierWire.decode(payload)) {
            is CourierWire.Decoded.Envelope -> onEnvelope(decoded.envelope, fromKey)
            is CourierWire.Decoded.Ack -> store.markDelivered(decoded.ids)
            null -> Unit
        }
    }

    private fun onEnvelope(envelope: CourierEnvelope, fromKey: String?) {
        if (envelope.isExpired(clock())) return
        if (envelope.recipientTag.contentEquals(CourierWire.recipientTag(myStaticPublicKey()))) {
            receive(envelope)
        } else if (courierModeEnabled()) {
            store.accept(envelope, own = false, from = fromKey)
        }
    }

    private fun receive(envelope: CourierEnvelope) {
        // Several couriers may bring the same envelope; deliver once, acknowledge every time
        val firstTime = !store.isDelivered(envelope.idHex)
        if (firstTime) {
            val opened = open(envelope.sealed, CourierWire.prologue(envelope)) ?: return
            val inner = decodeInner(opened.plaintext) ?: return
            deliver(
                Delivery(
                    senderStaticKey = opened.senderStaticKey,
                    messageId = inner.messageId,
                    senderNickname = inner.nickname,
                    content = inner.content,
                    sentAt = inner.sentAt
                )
            )
        }
        store.markDelivered(listOf(envelope.id))
        sendToNeighbours(CourierWire.encodeAck(listOf(envelope.id)))
    }

    fun carriedCount(): Int = store.size()

    fun clear() = store.clear()

    // MARK: - Inner plaintext: messageId, nickname, sentAt, content

    private class Inner(val messageId: String, val nickname: String, val sentAt: Long, val content: String)

    private fun encodeInner(messageId: String, nickname: String, sentAt: Long, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeUTF(messageId)
            d.writeUTF(nickname.take(64))
            d.writeLong(sentAt)
            d.writeShort(body.size)
            d.write(body)
        }
        return out.toByteArray()
    }

    private fun decodeInner(bytes: ByteArray): Inner? = try {
        DataInputStream(bytes.inputStream()).use { d ->
            val id = d.readUTF()
            val nick = d.readUTF()
            val sentAt = d.readLong()
            val body = ByteArray(d.readUnsignedShort()).also { d.readFully(it) }
            Inner(id, nick, sentAt, String(body, Charsets.UTF_8))
        }
    } catch (e: Exception) {
        null
    }
}
