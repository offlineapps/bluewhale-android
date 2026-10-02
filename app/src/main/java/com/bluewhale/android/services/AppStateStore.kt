package com.bluewhale.android.services

import com.bluewhale.android.model.BluewhaleMessage
import com.bluewhale.android.model.DeliveryStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide in-memory state store that survives Activity recreation.
 * The foreground Mesh service updates this store; UI subscribes/hydrates from it.
 */
object AppStateStore {
    // Global de-dup set by message id to avoid duplicate keys in Compose lists
    private val seenMessageIds = mutableSetOf<String>()
    // Content key for public messages, so a copy that arrives under a different ID (an
    // older peer that still assigns random IDs) is not shown twice either.
    private val seenPublicMessageKeys = mutableSetOf<String>()
    // Connected peer IDs (mesh ephemeral IDs)
    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers: StateFlow<List<String>> = _peers.asStateFlow()

    // Public mesh timeline messages (non-channel)
    private val _publicMessages = MutableStateFlow<List<BluewhaleMessage>>(emptyList())
    val publicMessages: StateFlow<List<BluewhaleMessage>> = _publicMessages.asStateFlow()

    // Private messages by peerID
    private val _privateMessages = MutableStateFlow<Map<String, List<BluewhaleMessage>>>(emptyMap())
    val privateMessages: StateFlow<Map<String, List<BluewhaleMessage>>> = _privateMessages.asStateFlow()

    // Channel messages by channel name
    private val _channelMessages = MutableStateFlow<Map<String, List<BluewhaleMessage>>>(emptyMap())
    val channelMessages: StateFlow<Map<String, List<BluewhaleMessage>>> = _channelMessages.asStateFlow()

    fun setPeers(ids: List<String>) {
        _peers.value = ids
    }

    fun addPublicMessage(msg: BluewhaleMessage) {
        synchronized(this) {
            val publicKey = publicMessageKey(msg)
            if (seenMessageIds.contains(msg.id) || seenPublicMessageKeys.contains(publicKey)) return
            seenMessageIds.add(msg.id)
            seenPublicMessageKeys.add(publicKey)
            _publicMessages.value = _publicMessages.value + msg
        }
    }

    fun hasMessageId(id: String): Boolean = synchronized(this) { seenMessageIds.contains(id) }

    private fun publicMessageKey(msg: BluewhaleMessage): String {
        val sender = msg.senderPeerID ?: msg.sender
        return listOf(
            sender,
            msg.timestamp.time.toString(),
            msg.type.name,
            msg.channel ?: "",
            msg.content
        ).joinToString("\u001F")
    }

    fun addPrivateMessage(peerID: String, msg: BluewhaleMessage) {
        synchronized(this) {
            if (seenMessageIds.contains(msg.id)) return
            seenMessageIds.add(msg.id)
            val map = _privateMessages.value.toMutableMap()
            val list = (map[peerID] ?: emptyList()) + msg
            map[peerID] = list
            _privateMessages.value = map
        }
    }

    private fun statusPriority(status: DeliveryStatus?): Int = when (status) {
        null -> 0
        is DeliveryStatus.Sending -> 1
        is DeliveryStatus.Sent -> 2
        is DeliveryStatus.PartiallyDelivered -> 3
        is DeliveryStatus.Delivered -> 4
        is DeliveryStatus.Read -> 5
        is DeliveryStatus.Failed -> 0
    }

    fun updatePrivateMessageStatus(messageID: String, status: DeliveryStatus) {
        synchronized(this) {
            val map = _privateMessages.value.toMutableMap()
            var changed = false
            map.keys.toList().forEach { peer ->
                val list = map[peer]?.toMutableList() ?: mutableListOf()
                val idx = list.indexOfFirst { it.id == messageID }
                if (idx >= 0) {
                    val current = list[idx].deliveryStatus
                    // Do not downgrade (e.g., Read -> Delivered)
                    if (statusPriority(status) >= statusPriority(current)) {
                        list[idx] = list[idx].copy(deliveryStatus = status)
                        map[peer] = list
                        changed = true
                    }
                }
            }
            if (changed) {
                _privateMessages.value = map
            }
        }
    }

    fun addChannelMessage(channel: String, msg: BluewhaleMessage) {
        synchronized(this) {
            if (seenMessageIds.contains(msg.id)) return
            seenMessageIds.add(msg.id)
            val map = _channelMessages.value.toMutableMap()
            val list = (map[channel] ?: emptyList()) + msg
            map[channel] = list
            _channelMessages.value = map
        }
    }

    // Clear all in-memory state (used for full app shutdown)
    fun clear() {
        synchronized(this) {
            seenMessageIds.clear()
            seenPublicMessageKeys.clear()
            _peers.value = emptyList()
            _publicMessages.value = emptyList()
            _privateMessages.value = emptyMap()
            _channelMessages.value = emptyMap()
        }
    }
}
