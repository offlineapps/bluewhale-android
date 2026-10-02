package com.bluewhale.android.courier

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Envelopes this device carries, plus the IDs of envelopes known to be delivered.
 *
 * Bounded: when full, expired envelopes go first, then the oldest normal-priority ones, and
 * urgent ones last. Which neighbour already got which envelope is kept in memory only, keyed
 * by the neighbour's static key so a rotated peer ID does not cause a resend.
 */
class CourierStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val persistence: Persistence? = null
) {
    companion object {
        const val DEFAULT_CAPACITY = 300
        /** Delivered IDs are remembered this long past their envelope's expiry. */
        private const val ACK_GRACE_MS = 60 * 60 * 1000L
    }

    /** Where the store survives restarts; null keeps it in memory only. */
    interface Persistence {
        fun load(): ByteArray?
        fun save(bytes: ByteArray)
        fun delete()
    }

    private class Entry(val envelope: CourierEnvelope, val own: Boolean)

    private val entries = LinkedHashMap<String, Entry>()
    // envelope id hex -> forget after
    private val delivered = HashMap<String, Long>()
    // neighbour key -> envelope ids already handed over (or received from them)
    private val handedTo = HashMap<String, MutableSet<String>>()

    init {
        persistence?.load()?.let { restore(it) }
    }

    @Synchronized
    fun size(): Int {
        prune()
        return entries.size
    }

    @Synchronized
    fun contains(idHex: String) = entries.containsKey(idHex)

    @Synchronized
    fun isDelivered(idHex: String) = delivered.containsKey(idHex)

    /**
     * Takes an envelope to carry. [own] marks envelopes this device wrote, which it carries
     * even when courier mode is off. Returns false for duplicates, expired or delivered ones.
     */
    @Synchronized
    fun accept(envelope: CourierEnvelope, own: Boolean = false, from: String? = null): Boolean {
        val now = clock()
        val key = envelope.idHex
        if (envelope.isExpired(now) || delivered.containsKey(key) || entries.containsKey(key)) {
            from?.let { handedTo.getOrPut(it) { mutableSetOf() }.add(key) }
            return false
        }
        prune()
        if (entries.size >= capacity && !makeRoomFor(envelope)) return false
        entries[key] = Entry(envelope, own)
        from?.let { handedTo.getOrPut(it) { mutableSetOf() }.add(key) }
        save()
        return true
    }

    /** The recipient has these: stop carrying them and refuse them from now on. */
    @Synchronized
    fun markDelivered(ids: List<ByteArray>) {
        val now = clock()
        var changed = false
        ids.forEach { id ->
            val key = id.toHex()
            val expiry = entries.remove(key)?.envelope?.expiresAt
            if (expiry != null) changed = true
            if (!delivered.containsKey(key)) {
                delivered[key] = (expiry ?: now + 3 * 24 * 60 * 60 * 1000L) + ACK_GRACE_MS
                changed = true
            }
        }
        if (changed) save()
    }

    /** Envelopes not yet given to [neighbourKey]; with [includeCarried] false, only our own. */
    @Synchronized
    fun pendingFor(neighbourKey: String, includeCarried: Boolean): List<CourierEnvelope> {
        prune()
        val done = handedTo[neighbourKey].orEmpty()
        return entries.values
            .filter { (includeCarried || it.own) && it.envelope.idHex !in done }
            .sortedWith(compareByDescending<Entry> { it.envelope.priority.code }.thenBy { it.envelope.createdAt })
            .map { it.envelope }
    }

    @Synchronized
    fun markHandedTo(neighbourKey: String, envelopes: List<CourierEnvelope>) {
        handedTo.getOrPut(neighbourKey) { mutableSetOf() }.addAll(envelopes.map { it.idHex })
    }

    /** Delivered IDs worth telling a neighbour about, newest first. */
    @Synchronized
    fun deliveredIds(limit: Int): List<ByteArray> {
        prune()
        return delivered.entries.sortedByDescending { it.value }.take(limit).map { hexToBytes(it.key) }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        delivered.clear()
        handedTo.clear()
        persistence?.delete()
    }

    private fun makeRoomFor(incoming: CourierEnvelope): Boolean {
        // Never drop our own messages to make room for someone else's
        val victim = entries.values
            .filter { !it.own && it.envelope.priority.code <= incoming.priority.code }
            .minWithOrNull(compareBy<Entry> { it.envelope.priority.code }.thenBy { it.envelope.createdAt })
            ?: return false
        entries.remove(victim.envelope.idHex)
        return true
    }

    private fun prune() {
        val now = clock()
        val before = entries.size + delivered.size
        entries.values.removeAll { it.envelope.isExpired(now) }
        delivered.values.removeAll { it <= now }
        if (entries.size + delivered.size != before) save()
    }

    // MARK: - Persistence

    private fun save() {
        val p = persistence ?: return
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeByte(1)
            d.writeInt(entries.size)
            entries.values.forEach { e ->
                val bytes = CourierWire.encode(e.envelope)
                d.writeBoolean(e.own)
                d.writeInt(bytes.size)
                d.write(bytes)
            }
            d.writeInt(delivered.size)
            delivered.forEach { (id, until) ->
                d.write(hexToBytes(id))
                d.writeLong(until)
            }
        }
        p.save(out.toByteArray())
    }

    private fun restore(bytes: ByteArray) {
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                if (d.readByte().toInt() != 1) return
                repeat(d.readInt()) {
                    val own = d.readBoolean()
                    val raw = ByteArray(d.readInt()).also { d.readFully(it) }
                    val decoded = CourierWire.decode(raw) as? CourierWire.Decoded.Envelope ?: return@repeat
                    entries[decoded.envelope.idHex] = Entry(decoded.envelope, own)
                }
                repeat(d.readInt()) {
                    val id = ByteArray(CourierWire.ID_SIZE).also { d.readFully(it) }
                    delivered[id.toHex()] = d.readLong()
                }
            }
        } catch (e: Exception) {
            // A damaged store is not worth crashing over; start empty
            entries.clear()
            delivered.clear()
        }
        prune()
    }

    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
