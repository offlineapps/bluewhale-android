package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.MessagePadding
import com.bitchat.android.model.FragmentPayload
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages message fragmentation and reassembly - 100% iOS Compatible
 * 
 * This implementation exactly matches iOS SimplifiedBluetoothService fragmentation:
 * - Same fragment payload structure (13-byte header + data)
 * - Same MTU thresholds and fragment sizes
 * - Same reassembly logic and timeout handling
 * - Uses new FragmentPayload model for type safety
 */
class FragmentManager {
    
    companion object {
        private const val TAG = "FragmentManager"
        // iOS values: 512 MTU threshold, 469 max fragment size (512 MTU - headers)
        private const val FRAGMENT_SIZE_THRESHOLD = com.bitchat.android.util.AppConstants.Fragmentation.FRAGMENT_SIZE_THRESHOLD // Matches iOS: if data.count > 512
        private const val MAX_FRAGMENT_SIZE = com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_SIZE        // Matches iOS: maxFragmentSize = 469 
        private const val FRAGMENT_TIMEOUT = com.bitchat.android.util.AppConstants.Fragmentation.FRAGMENT_TIMEOUT_MS     // Matches iOS: 30 seconds cleanup
        private const val CLEANUP_INTERVAL = com.bitchat.android.util.AppConstants.Fragmentation.CLEANUP_INTERVAL_MS     // 10 seconds cleanup check
    }
    
    data class FragmentSet(
        val fragmentId: String,
        val total: Int,
        val type: UByte,
        val createdAt: Long,
        val fragments: Array<ByteArray?>,
        var receivedCount: Int = 0,
        var totalBytes: Int = 0
    ) {
        fun isComplete(): Boolean = receivedCount == total
    }

    private val sets = LinkedHashMap<String, FragmentSet>(
        16, 0.75f, true // LRU (access order)
    )

    private val locks = ConcurrentHashMap<String, Any>()

    private val globalLock = Any()
    private var globalBytes: Long = 0L

    // Delegate for callbacks
    var delegate: FragmentManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Create fragments from a large packet - 100% iOS Compatible
     * Matches iOS sendFragmentedPacket() implementation exactly
     */
    fun createFragments(packet: BitchatPacket): List<BitchatPacket> {
        try {
            Log.d(TAG, "🔀 Creating fragments for packet type ${packet.type}, payload: ${packet.payload.size} bytes")
        val encoded = packet.toBinaryData()
            if (encoded == null) {
                Log.e(TAG, "❌ Failed to encode packet to binary data")
                return emptyList()
            }
            Log.d(TAG, "📦 Encoded to ${encoded.size} bytes")
        
        // Fragment the unpadded frame; each fragment will be encoded (and padded) independently - iOS fix
        val fullData = try {
                MessagePadding.unpad(encoded)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to unpad data: ${e.message}", e)
                return emptyList()
            }
            Log.d(TAG, "📏 Unpadded to ${fullData.size} bytes")
        
        // iOS logic: if data.count > 512 && packet.type != MessageType.fragment.rawValue
        if (fullData.size <= FRAGMENT_SIZE_THRESHOLD) {
            return listOf(packet) // No fragmentation needed
        }
        
        val fragments = mutableListOf<BitchatPacket>()
        
        // iOS: let fragmentID = Data((0..<8).map { _ in UInt8.random(in: 0...255) })
        val fragmentID = FragmentPayload.generateFragmentID()
        
        // iOS: stride(from: 0, to: fullData.count, by: maxFragmentSize)
        // Calculate dynamic fragment size to fit in MTU (512)
        // Packet = Header + Sender + Recipient + Route + FragmentHeader + Payload + PaddingBuffer
        val hasRoute = packet.route != null
        val version = if (hasRoute) 2 else 1
        val headerSize = if (version == 2) 15 else 13
        val senderSize = 8
        val recipientSize = if (packet.recipientID != null) 8 else 0
        // Route: 1 byte count + 8 bytes per hop
        val routeSize = if (hasRoute) (1 + (packet.route?.size ?: 0) * 8) else 0
        val fragmentHeaderSize = 13 // FragmentPayload header
        val paddingBuffer = 16 // MessagePadding.optimalBlockSize adds 16 bytes overhead

        // 512 - Overhead
        val packetOverhead = headerSize + senderSize + recipientSize + routeSize + fragmentHeaderSize + paddingBuffer
        val maxDataSize = (512 - packetOverhead).coerceAtMost(MAX_FRAGMENT_SIZE)
        
        if (maxDataSize <= 0) {
            Log.e(TAG, "❌ Calculated maxDataSize is non-positive ($maxDataSize). Route too large?")
            return emptyList()
        }

        Log.d(TAG, "📏 Dynamic fragment size: $maxDataSize (MAX: $MAX_FRAGMENT_SIZE, Overhead: $packetOverhead)")

        val fragmentChunks = stride(0, fullData.size, maxDataSize) { offset ->
            val endOffset = minOf(offset + maxDataSize, fullData.size)
            fullData.sliceArray(offset..<endOffset)
        }
        
        Log.d(TAG, "Creating ${fragmentChunks.size} fragments for ${fullData.size} byte packet (iOS compatible)")
        
        // iOS: for (index, fragment) in fragments.enumerated()
        for (index in fragmentChunks.indices) {
            val fragmentData = fragmentChunks[index]
            
            // Create iOS-compatible fragment payload
            val fragmentPayload = FragmentPayload(
                fragmentID = fragmentID,
                index = index,
                total = fragmentChunks.size,
                originalType = packet.type,
                data = fragmentData
            )
            
            // iOS: MessageType.fragment.rawValue (single fragment type)
            // Fix: Fragments must inherit source route and use v2 if routed
            val fragmentPacket = BitchatPacket(
                version = if (packet.route != null) 2u else 1u,
                type = MessageType.FRAGMENT.value,
                ttl = packet.ttl,
                senderID = packet.senderID,
                recipientID = packet.recipientID,
                timestamp = packet.timestamp,
                payload = fragmentPayload.encode(),
                route = packet.route,
                signature = null // iOS: signature: nil
            )
            
            fragments.add(fragmentPacket)
        }
        
        Log.d(TAG, "✅ Created ${fragments.size} fragments successfully")
            return fragments
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fragment creation failed: ${e.message}", e)
            Log.e(TAG, "❌ Packet type: ${packet.type}, payload: ${packet.payload.size} bytes")
            return emptyList()
        }
    }
    
    /**
     * Handle incoming fragment - 100% iOS Compatible
     * Hardened with memory limits and LRU eviction.
     */
    fun handleFragment(packet: BitchatPacket): BitchatPacket? {
        if (packet.payload.size < FragmentPayload.HEADER_SIZE) {
            Log.w(TAG, "Fragment packet too small: ${packet.payload.size}")
            return null
        }

        try {
            val fragmentPayload = FragmentPayload.decode(packet.payload)
            if (fragmentPayload == null || !fragmentPayload.isValid()) {
                Log.w(TAG, "Invalid fragment payload")
                return null
            }

            val fragmentIDString = fragmentPayload.getFragmentIDString()
            val limits = com.bitchat.android.util.AppConstants.Fragmentation

            if (fragmentPayload.total > limits.MAX_FRAGMENTS_PER_ID) {
                Log.w(TAG, "Rejecting fragment with excessive total count: ${fragmentPayload.total}")
                return null
            }

            val lock = lockFor(fragmentIDString)

            synchronized(lock) {
                val now = System.currentTimeMillis()

                var set = synchronized(globalLock) { sets[fragmentIDString] }

                if (set == null) {
                    synchronized(globalLock) {
                        evictIfNeeded()
                        if (sets.size >= limits.MAX_ACTIVE_SETS) return null
                    }

                    set = FragmentSet(
                        fragmentId = fragmentIDString,
                        total = fragmentPayload.total,
                        type = fragmentPayload.originalType,
                        createdAt = now,
                        fragments = arrayOfNulls(fragmentPayload.total)
                    )

                    synchronized(globalLock) {
                        sets[fragmentIDString] = set
                    }
                    Log.d(TAG, "New fragment set created: $fragmentIDString (total: ${set.total})")
                } else {
                    if (set.total != fragmentPayload.total ||
                        set.type != fragmentPayload.originalType
                    ) {
                        Log.w(TAG, "Rejecting fragment due to metadata mismatch for $fragmentIDString")
                        removeSet(fragmentIDString)
                        return null
                    }
                }

                val existing = set.fragments[fragmentPayload.index]
                val oldSize = existing?.size ?: 0
                val newSize = fragmentPayload.data.size

                val newTotalSizeOfSet = set.totalBytes - oldSize + newSize

                if (newTotalSizeOfSet > limits.MAX_SET_BYTES) {
                    Log.w(TAG, "Rejecting fragment set $fragmentIDString for exceeding per-set limit")
                    removeSet(fragmentIDString)
                    return null
                }

                val delta = (newSize - oldSize).toLong()

                synchronized(globalLock) {
                    if (globalBytes + delta > limits.MAX_GLOBAL_BYTES) {
                        evictUntil(delta)
                        if (globalBytes + delta > limits.MAX_GLOBAL_BYTES) {
                            Log.e(TAG, "Failed to evict enough space for fragment")
                            return null
                        }
                    }
                    globalBytes += delta
                }

                if (existing == null) {
                    set.receivedCount++
                }

                set.fragments[fragmentPayload.index] = fragmentPayload.data
                set.totalBytes = newTotalSizeOfSet

                if (set.isComplete()) {
                    Log.d(TAG, "All fragments received for $fragmentIDString, reassembling...")
                    val reassembledPacket = reassemble(set)
                    removeSet(fragmentIDString)
                    return reassembledPacket
                } else {
                    Log.d(TAG, "Fragment ${fragmentPayload.index} stored, have ${set.receivedCount}/${set.total} fragments for $fragmentIDString")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling fragment: ${e.message}", e)
        }

        return null
    }

    private fun lockFor(id: String): Any {
        return locks.computeIfAbsent(id) { Any() }
    }

    private fun removeSet(id: String) {
        val lock = lockFor(id)

        synchronized(lock) {
            val removed: FragmentSet?

            synchronized(globalLock) {
                removed = sets.remove(id)
                locks.remove(id)
            }

            if (removed != null) {
                synchronized(globalLock) {
                    globalBytes = (globalBytes - removed.totalBytes).coerceAtLeast(0)
                }
            }
        }
    }

    private fun reassemble(set: FragmentSet): BitchatPacket? {
        val buffer = ByteArray(set.totalBytes)

        var offset = 0
        for (i in 0 until set.total) {
            val fragment = set.fragments[i] ?: run {
                Log.e(TAG, "Missing fragment at index $i during reassembly")
                return null
            }
            System.arraycopy(fragment, 0, buffer, offset, fragment.size)
            offset += fragment.size
        }

        val packet = BitchatPacket.fromBinaryData(buffer)
        if (packet == null) {
            Log.e(TAG, "Failed to decode reassembled packet")
            return null
        }
        
        // Suppress re-broadcast of the reassembled packet by zeroing TTL.
        return packet.copy(ttl = 0u.toUByte())
    }

    private fun evictIfNeeded() {
        val maxActive = com.bitchat.android.util.AppConstants.Fragmentation.MAX_ACTIVE_SETS
        synchronized(globalLock) {
            while (sets.size >= maxActive) {
                if (!evictOne()) break
            }
        }
    }

    private fun evictUntil(required: Long) {
        val maxGlobal = com.bitchat.android.util.AppConstants.Fragmentation.MAX_GLOBAL_BYTES
        synchronized(globalLock) {
            while (globalBytes + required > maxGlobal) {
                if (!evictOne()) break
            }
        }
    }

    private fun evictOne(): Boolean {
        synchronized(globalLock) {
            val iterator = sets.entries.iterator()
            if (!iterator.hasNext()) return false

            val entry = iterator.next()
            val id = entry.key
            val set = entry.value
            
            iterator.remove()
            globalBytes = (globalBytes - set.totalBytes).coerceAtLeast(0)
            locks.remove(id)

            Log.d(TAG, "Evicted fragment set $id to free ${set.totalBytes} bytes")
            return true
        }
    }
    
    /**
     * Helper function to match iOS stride functionality
     * stride(from: 0, to: fullData.count, by: maxFragmentSize)
     */
    private fun <T> stride(from: Int, to: Int, by: Int, transform: (Int) -> T): List<T> {
        val result = mutableListOf<T>()
        var current = from
        while (current < to) {
            result.add(transform(current))
            current += by
        }
        return result
    }
    
    /**
     * iOS cleanup - exactly matching performCleanup() implementation
     * Clean old fragments (> 30 seconds old)
     */
    private fun cleanupOldFragments() {
        val now = System.currentTimeMillis()
        val cutoff = now - FRAGMENT_TIMEOUT
        
        val oldFragments = synchronized(globalLock) {
            sets.filter { it.value.createdAt < cutoff }.map { it.key }
        }
        
        for (fragmentID in oldFragments) {
            removeSet(fragmentID)
        }
        
        if (oldFragments.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${oldFragments.size} old fragment sets (iOS compatible)")
        }
    }
    
    /**
     * Get debug information - matches iOS debugging
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Fragment Manager Debug Info (iOS Compatible) ===")
            synchronized(globalLock) {
                appendLine("Active Fragment Sets: ${sets.size}")
                appendLine("Global Bytes Saved: $globalBytes bytes")
                appendLine("Fragment Size Threshold: $FRAGMENT_SIZE_THRESHOLD bytes")
                appendLine("Max Fragment Size: $MAX_FRAGMENT_SIZE bytes")
                
                sets.forEach { (fragmentID, set) ->
                    val ageSeconds = (System.currentTimeMillis() - set.createdAt) / 1000
                    appendLine("  - $fragmentID: ${set.receivedCount}/${set.total} fragments, type: ${set.type}, age: ${ageSeconds}s, bytes: ${set.totalBytes}")
                }
            }
        }
    }
    
    /**
     * Start periodic cleanup of old fragments - matches iOS maintenance timer
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupOldFragments()
            }
        }
    }
    
    /**
     * Clear all fragments
     */
    fun clearAllFragments() {
        synchronized(globalLock) {
            sets.clear()
            locks.clear()
            globalBytes = 0
        }
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllFragments()
    }
}

/**
 * Delegate interface for fragment manager callbacks
 */
interface FragmentManagerDelegate {
    fun onPacketReassembled(packet: BitchatPacket)
}
