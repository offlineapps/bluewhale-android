package com.bluewhale.android.services

import android.content.Context
import android.util.Log
import com.bluewhale.android.mesh.BluetoothMeshService
import com.bluewhale.android.model.ReadReceipt
import com.bluewhale.android.nostr.NostrTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes messages between BLE mesh and Nostr transports, matching iOS behavior.
 */
class MessageRouter private constructor(
    private val context: Context,
    private var mesh: BluetoothMeshService,
    private val nostr: NostrTransport
) {
    private data class QueuedMessage(
        val content: String,
        val nickname: String,
        val messageID: String,
        val enqueuedAtMs: Long
    )

    private data class ConversationRetry(
        val handshakeAttempts: Int,
        val nextHandshakeAttemptAtMs: Long
    )

    companion object {
        private const val TAG = "MessageRouter"
        private const val OUTBOX_TICK_MS = 2_000L
        // A queued message is given up on after a day, so a contact who never comes back
        // does not hold it, or its plaintext, forever.
        internal const val OUTBOX_MESSAGE_TTL_MS = 86_400_000L
        internal const val OUTBOX_MAX_PER_PEER = 100
        private val HANDSHAKE_RETRY_BACKOFF_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L)

        @Volatile private var INSTANCE: MessageRouter? = null
        internal var disableSchedulerForTesting = false
        fun tryGetInstance(): MessageRouter? = INSTANCE
        fun getInstance(context: Context, mesh: BluetoothMeshService): MessageRouter {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val nostr = NostrTransport.getInstance(context)
                    MessageRouter(context.applicationContext, mesh, nostr).also { instance ->
                        // Register for favorites changes to flush outbox
                        try {
                            com.bluewhale.android.favorites.FavoritesPersistenceService.shared.addListener(instance.favoriteListener)
                        } catch (_: Exception) {}
                        INSTANCE = instance
                    }
                }
            }
            // Always update mesh reference and sync peer ID
            instance.mesh = mesh
            instance.nostr.senderPeerID = mesh.myPeerID
            return instance
        }

        internal fun resetForTesting() {
            INSTANCE?.schedulerScope?.cancel()
            INSTANCE = null
        }
    }

    // Outbox: peerID -> queued messages, oldest first
    private val outbox = ConcurrentHashMap<String, MutableList<QueuedMessage>>()

    // Per-peer handshake retry state for queued messages
    private val retryState = ConcurrentHashMap<String, ConversationRetry>()

    private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Injectable for tests
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Called with the ID of a queued message that expired or was evicted before delivery. */
    var onMessageExpired: ((String) -> Unit)? = null

    init {
        if (!disableSchedulerForTesting) startOutboxScheduler()
    }

    // Listener for favorites changes to flush outbox when npub mapping appears/changes
    private val favoriteListener = object: com.bluewhale.android.favorites.FavoritesChangeListener {

        override fun onFavoriteChanged(noiseKeyHex: String) {
            flushOutboxFor(noiseKeyHex)
            // Also try 16-hex short id commonly used in UI if any client used that
            val shortId = noiseKeyHex.take(16)
            flushOutboxFor(shortId)
        }
        override fun onAllCleared() {
            // Nothing special; leave queued items until routing becomes possible
        }
    }

    /**
     * Drops every queued message. Used by panic wipe: the outbox outlives the mesh service
     * and is keyed by the recipient, so without this a message written before the wipe is
     * sent from the new identity as soon as that contact becomes routable again.
     */
    fun clearAll() {
        outbox.clear()
        retryState.clear()
        Log.d(TAG, "Cleared all queued outbox messages")
    }

    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String) {
        // First: if this is a geohash DM alias (nostr_<pub16>), route via Nostr using global registry
        if (com.bluewhale.android.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            Log.d(TAG, "Routing PM via Nostr (geohash) to alias ${toPeerID.take(12)}… id=${messageID.take(8)}…")
            val recipientHex = com.bluewhale.android.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                // Resolve the conversation's source geohash, so we can send from anywhere
                val sourceGeohash = com.bluewhale.android.nostr.GeohashConversationRegistry.get(toPeerID)

                // If repository knows the source geohash, pass it so NostrTransport derives the correct identity
                nostr.sendPrivateMessageGeohash(content, recipientHex, messageID, sourceGeohash)
                return
            }
        }

        val hasMesh = mesh.getPeerInfo(toPeerID)?.isConnected == true
        val hasEstablished = mesh.hasEstablishedSession(toPeerID)
        if (hasMesh && hasEstablished) {
            Log.d(TAG, "Routing PM via mesh to ${toPeerID} msg_id=${messageID.take(8)}…")
            mesh.sendPrivateMessage(content, toPeerID, recipientNickname, messageID)
        } else if (canSendViaNostr(toPeerID)) {
            Log.d(TAG, "Routing PM via Nostr to ${toPeerID.take(32)}… msg_id=${messageID.take(8)}…")
            nostr.sendPrivateMessage(content, toPeerID, recipientNickname, messageID)
        } else {
            Log.d(TAG, "Queued PM for ${toPeerID} (no mesh, no Nostr mapping) msg_id=${messageID.take(8)}…")
            enqueue(toPeerID, QueuedMessage(content, recipientNickname, messageID, clock()))
            Log.d(TAG, "Initiating noise handshake after queueing PM for ${toPeerID.take(8)}…")
            kickHandshake(toPeerID, immediate = true)
        }
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {
        if ((mesh.getPeerInfo(toPeerID)?.isConnected == true) && mesh.hasEstablishedSession(toPeerID)) {
            Log.d(TAG, "Routing READ via mesh to ${toPeerID.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            mesh.sendReadReceipt(receipt.originalMessageID, toPeerID, mesh.getPeerNicknames()[toPeerID] ?: mesh.myPeerID)
        } else {
            Log.d(TAG, "Routing READ via Nostr to ${toPeerID.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            nostr.sendReadReceipt(receipt, toPeerID)
        }
    }

    fun sendDeliveryAck(messageID: String, toPeerID: String) {
        // Mesh delivery ACKs are sent by the receiver automatically.
        // Only route via Nostr when mesh path isn't available or when this is a geohash alias
        if (com.bluewhale.android.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            val recipientHex = com.bluewhale.android.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                nostr.sendDeliveryAckGeohash(messageID, recipientHex, try { com.bluewhale.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)!! } catch (_: Exception) { return })
                return
            }
        }
        if (!((mesh.getPeerInfo(toPeerID)?.isConnected == true) && mesh.hasEstablishedSession(toPeerID))) {
            nostr.sendDeliveryAck(messageID, toPeerID)
        }
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) {
        if (mesh.getPeerInfo(toPeerID)?.isConnected == true) {
            val myNpub = try { com.bluewhale.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub } catch (_: Exception) { null }
            val content = if (isFavorite) "[FAVORITED]:${myNpub ?: ""}" else "[UNFAVORITED]:${myNpub ?: ""}"
            val nickname = mesh.getPeerNicknames()[toPeerID] ?: toPeerID
            mesh.sendPrivateMessage(content, toPeerID, nickname)
        } else {
            nostr.sendFavoriteNotification(toPeerID, isFavorite)
        }
    }

    // Flush any queued messages for a specific peerID
    fun flushOutboxFor(peerID: String) {
        val queued = outbox[peerID] ?: return
        if (queued.isEmpty()) return
        Log.d(TAG, "Flushing outbox for ${peerID.take(8)}… count=${queued.size}")
        synchronized(queued) { flushLocked(peerID, queued) }
        if (queued.isEmpty()) {
            outbox.remove(peerID, queued)
            retryState.remove(peerID)
        }
    }

    private fun flushLocked(peerID: String, queued: MutableList<QueuedMessage>) {
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val content = entry.content
            val nickname = entry.nickname
            val messageID = entry.messageID
            var hasMesh = mesh.getPeerInfo(peerID)?.isConnected == true && mesh.hasEstablishedSession(peerID)
            // If this is a noiseHex key, see if there is a connected mesh peer for this identity
            if (!hasMesh && peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                val meshPeer = resolveMeshPeerForNoiseHex(peerID)
                if (meshPeer != null && mesh.getPeerInfo(meshPeer)?.isConnected == true && mesh.hasEstablishedSession(meshPeer)) {
                    mesh.sendPrivateMessage(content, meshPeer, nickname, messageID)
                    iterator.remove()
                    continue
                }
            }
            val canNostr = canSendViaNostr(peerID)
            if (hasMesh) {
                mesh.sendPrivateMessage(content, peerID, nickname, messageID)
                iterator.remove()
            } else if (canNostr) {
                nostr.sendPrivateMessage(content, peerID, nickname, messageID)
                iterator.remove()
            }
        }
    }

    @Synchronized
    private fun enqueue(peerID: String, entry: QueuedMessage) {
        val queue = outbox.getOrPut(peerID) { mutableListOf() }
        synchronized(queue) {
            queue.add(entry)
            while (queue.size > OUTBOX_MAX_PER_PEER) {
                val evicted = queue.removeAt(0)
                Log.w(TAG, "Outbox full for ${peerID.take(8)}…; evicting oldest msg_id=${evicted.messageID.take(8)}…")
                notifyExpired(evicted.messageID)
            }
        }
    }

    private fun notifyExpired(messageID: String) {
        try { onMessageExpired?.invoke(messageID) } catch (_: Exception) { }
    }

    /**
     * Starts a Noise handshake for a peer we owe queued messages, backing off between
     * attempts. [immediate] resets the backoff (a message was just queued or the peer just
     * reappeared). While a previous attempt is inside its backoff window nothing is sent,
     * so frequent peer list updates cannot flood the peer with handshakes.
     */
    @Synchronized
    private fun kickHandshake(peerID: String, immediate: Boolean) {
        val now = clock()
        val current = retryState[peerID]
        if (current != null && now < current.nextHandshakeAttemptAtMs) return
        val attempts = if (immediate) 0 else (current?.handshakeAttempts ?: 0)
        try { mesh.initiateNoiseHandshake(peerID) } catch (_: Exception) { }
        val backoff = HANDSHAKE_RETRY_BACKOFF_MS[attempts.coerceAtMost(HANDSHAKE_RETRY_BACKOFF_MS.size - 1)]
        retryState[peerID] = ConversationRetry(attempts + 1, now + backoff)
    }

    private fun startOutboxScheduler() {
        schedulerScope.launch {
            while (isActive) {
                delay(OUTBOX_TICK_MS)
                try { tickOutbox() } catch (e: Exception) {
                    Log.w(TAG, "Outbox scheduler tick failed: ${e.message}")
                }
            }
        }
    }

    /**
     * One pass over the outbox: expire old entries, flush what can be sent, and retry the
     * handshake with backoff for peers that are connected but have no session yet. Queued
     * messages used to wait for a UI poll or a peer list change, and a lost handshake was
     * never retried, so they could sit forever while the peer stood right there.
     */
    internal fun tickOutbox(nowMs: Long = clock()) {
        outbox.keys.toList().forEach { peerID ->
            expireOldEntries(peerID, nowMs)
            val queued = outbox[peerID] ?: return@forEach
            if (queued.isEmpty()) return@forEach
            val connected = mesh.getPeerInfo(peerID)?.isConnected == true
            if ((connected && mesh.hasEstablishedSession(peerID)) || canSendViaNostr(peerID)) {
                flushOutboxFor(peerID)
            } else if (connected) {
                kickHandshake(peerID, immediate = false)
            }
        }
    }

    private fun expireOldEntries(peerID: String, nowMs: Long) {
        val queued = outbox[peerID] ?: return
        synchronized(queued) {
            val iterator = queued.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (nowMs - entry.enqueuedAtMs > OUTBOX_MESSAGE_TTL_MS) {
                    Log.w(TAG, "Expiring queued PM for ${peerID.take(8)}… msg_id=${entry.messageID.take(8)}…")
                    iterator.remove()
                    notifyExpired(entry.messageID)
                }
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(peerID, queued)
            retryState.remove(peerID)
        }
    }

    // Flush everything (rarely used)
    fun flushAllOutbox() {
        outbox.keys.toList().forEach { flushOutboxFor(it) }
    }

    private fun canSendViaNostr(peerID: String): Boolean {
        return try {
            // Full Noise key hex
            if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                val noiseKey = hexToBytes(peerID)
                val fav = com.bluewhale.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else if (peerID.length == 16 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                // Ephemeral 16-hex mesh ID: resolve via prefix match in favorites
                val fav = com.bluewhale.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else {
                false
            }
        } catch (_: Exception) { false }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun resolveMeshPeerForNoiseHex(noiseHex: String): String? {
        return try {
            mesh.getPeerNicknames().keys.firstOrNull { pid ->
                val info = mesh.getPeerInfo(pid)
                val keyHex = info?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
                keyHex != null && keyHex.equals(noiseHex, ignoreCase = true)
            }
        } catch (_: Exception) { null }
    }

    // Called when mesh peer list changes; attempt to flush any matching outbox entries
    fun onPeersUpdated(peers: List<String>) {
        peers.forEach { pid ->
            kickHandshakeIfPending(pid)
            flushOutboxFor(pid)
            val noiseHex = try {
                mesh.getPeerInfo(pid)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
            } catch (_: Exception) { null }
            noiseHex?.let { flushOutboxFor(it) }
        }
    }

    // Called when a Noise session becomes established; flush both the mesh peerID and its noiseHex alias
    fun onSessionEstablished(peerID: String) {
        retryState.remove(peerID)
        flushOutboxFor(peerID)
        val noiseHex = try {
            mesh.getPeerInfo(peerID)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) { null }
        noiseHex?.let { flushOutboxFor(it) }
    }

    /**
     * A peer (re)appeared while we still owe it queued messages and have no working
     * session: start the handshake now instead of waiting out the backoff.
     */
    private fun kickHandshakeIfPending(peerID: String) {
        val queued = outbox[peerID] ?: return
        if (queued.isEmpty()) return
        if (mesh.getPeerInfo(peerID)?.isConnected != true) return
        if (mesh.hasEstablishedSession(peerID)) return
        kickHandshake(peerID, immediate = true)
    }
}
