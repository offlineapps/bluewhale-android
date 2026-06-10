package com.bitchat.android.mesh

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.model.FragmentPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

@RunWith(RobolectricTestRunner::class)
class FragmentManagerTest {

    private lateinit var fragmentManager: FragmentManager
    private val senderID = "1122334455667788"
    private val recipientID = "8877665544332211"

    @Before
    fun setup() {
        fragmentManager = FragmentManager()
    }

    @Test
    fun `test fragmentation without route`() {
        // Create a large payload (e.g., 1000 bytes)
        val payload = ByteArray(1000)
        Random().nextBytes(payload)

        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = null
        )

        val fragments = fragmentManager.createFragments(packet)

        assertTrue("Should create multiple fragments", fragments.size > 1)
        
        // Verify each fragment fits in MTU (512)
        for (fragment in fragments) {
            val encodedSize = fragment.toBinaryData()?.size ?: 0
            assertTrue("Fragment encoded size should be <= 512, was $encodedSize", encodedSize <= 512)
            
            // Inspect the payload data size
            val fragmentPayload = FragmentPayload.decode(fragment.payload)
            assertNotNull(fragmentPayload)
        }
    }

    @Test
    fun `test fragmentation with route`() {
        // Create a large payload
        val payload = ByteArray(1000)
        Random().nextBytes(payload)
        
        // Create a fake route (3 hops)
        val route = listOf(
            hexStringToByteArray("AABBCCDDEEFF0011"),
            hexStringToByteArray("1100FFEEDDCCBBAA"),
            hexStringToByteArray("1234567890ABCDEF")
        )

        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = route
        )

        val fragments = fragmentManager.createFragments(packet)

        assertTrue("Should create multiple fragments", fragments.size > 1)

        // Verify fragments retain the route and version 2
        for (fragment in fragments) {
            assertEquals("Fragment version should be 2", 2u.toUByte(), fragment.version)
            assertEquals("Fragment should have the route", route.size, fragment.route?.size)
            
            val encodedSize = fragment.toBinaryData()?.size ?: 0
            assertTrue("Fragment encoded size should be <= 512, was $encodedSize", encodedSize <= 512)
        }
    }
    
    @Test
    fun `test fragmentation size difference with and without route`() {
        // This test specifically checks if the dynamic calculation logic works 
        // by observing that fragments with routes carry less data payload per fragment
        
        val payload = ByteArray(2000) // Large enough to ensure full fragments
        Random().nextBytes(payload)
        
        // 1. Without route
        val packetNoRoute = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = null
        )
        val fragmentsNoRoute = fragmentManager.createFragments(packetNoRoute)
        val firstFragPayloadNoRoute = FragmentPayload.decode(fragmentsNoRoute[0].payload)
        val dataSizeNoRoute = firstFragPayloadNoRoute?.data?.size ?: 0
        
        // 2. With large route (e.g., 5 hops)
        val route = List(5) { hexStringToByteArray("000000000000000$it") }
        val packetWithRoute = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = route
        )
        val fragmentsWithRoute = fragmentManager.createFragments(packetWithRoute)
        val firstFragPayloadWithRoute = FragmentPayload.decode(fragmentsWithRoute[0].payload)
        val dataSizeWithRoute = firstFragPayloadWithRoute?.data?.size ?: 0
        
        println("Data size without route: $dataSizeNoRoute")
        println("Data size with route: $dataSizeWithRoute")
        
        assertTrue("Data payload should be smaller with route", dataSizeWithRoute < dataSizeNoRoute)
        
        // Rough verification of the math:
        // 5 hops * 8 bytes = 40 bytes extra.
        // Plus v2 header overhead differences.
        // The difference should be roughly 40+ bytes.
        assertTrue("Difference should be significant", (dataSizeNoRoute - dataSizeWithRoute) >= 40)
    }

    @Test
    fun `test reassembly`() {
        val originalPayload = ByteArray(1500)
        Random().nextBytes(originalPayload)
        
        val originalPacket = BitchatPacket(
            version = 1u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = originalPayload,
            ttl = 7u
        )
        
        val fragments = fragmentManager.createFragments(originalPacket)
        
        var reassembledPacket: BitchatPacket? = null
        for (fragment in fragments) {
            val result = fragmentManager.handleFragment(fragment)
            if (result != null) {
                reassembledPacket = result
            }
        }
        
        assertNotNull("Should have reassembled packet", reassembledPacket)
        assertEquals("Type should match", originalPacket.type, reassembledPacket!!.type)
        assertEquals("Payload size should match", originalPacket.payload.size, reassembledPacket.payload.size)
        assertTrue("Payload content should match", originalPacket.payload.contentEquals(reassembledPacket.payload))
    }

    @Test
    fun `test max active sets eviction`() {
        val limits = com.bitchat.android.util.AppConstants.Fragmentation
        val maxSets = limits.MAX_ACTIVE_SETS
        
        // Create fragments for maxSets + 1 different messages
        val packets = (0..maxSets).map { i ->
            val id = ByteArray(8)
            Random().nextBytes(id)
            FragmentPayload(
                fragmentID = id,
                index = 0,
                total = 2,
                originalType = MessageType.MESSAGE.value,
                data = ByteArray(10)
            )
        }

        // Add first packet to manager
        val firstFragID = packets[0].getFragmentIDString()
        fragmentManager.handleFragment(createBitchatPacketFromFragment(packets[0]))
        
        // Add all others
        for (i in 1..maxSets) {
            fragmentManager.handleFragment(createBitchatPacketFromFragment(packets[i]))
        }

        // The first one should have been evicted (LRU)
        val debugInfo = fragmentManager.getDebugInfo()
        assertTrue("First fragment ID should not be in debug info after eviction", !debugInfo.contains(firstFragID))
        assertTrue("Debug info should show max active sets", debugInfo.contains("Active Fragment Sets: $maxSets"))
    }

    @Test
    fun `test global bytes eviction`() {
        val limits = com.bitchat.android.util.AppConstants.Fragmentation
        val maxGlobalBytes = limits.MAX_GLOBAL_BYTES
        
        // Create fragments that together exceed maxGlobalBytes
        // Each fragment is 1MB, limit is 4MB.
        val dataSize = 1_000_000
        val payload1 = createLargeFragmentPayload(dataSize, "ID000001")
        val payload2 = createLargeFragmentPayload(dataSize, "ID000002")
        val payload3 = createLargeFragmentPayload(dataSize, "ID000003")
        val payload4 = createLargeFragmentPayload(dataSize, "ID000004")
        val payload5 = createLargeFragmentPayload(dataSize, "ID000005") // This should trigger eviction

        fragmentManager.handleFragment(createBitchatPacketFromFragment(payload1))
        fragmentManager.handleFragment(createBitchatPacketFromFragment(payload2))
        fragmentManager.handleFragment(createBitchatPacketFromFragment(payload3))
        fragmentManager.handleFragment(createBitchatPacketFromFragment(payload4))
        
        assertTrue("Should have 4 sets", fragmentManager.getDebugInfo().contains("Active Fragment Sets: 4"))
        
        fragmentManager.handleFragment(createBitchatPacketFromFragment(payload5))
        
        val debugInfo = fragmentManager.getDebugInfo()
        assertTrue("Should still have 4 or fewer sets after eviction", !debugInfo.contains("Active Fragment Sets: 5"))
        assertTrue("First ID should be evicted", !debugInfo.contains("ID000001"))
    }

    @Test
    fun `test max set bytes rejection`() {
        val limits = com.bitchat.android.util.AppConstants.Fragmentation
        val maxSetBytes = limits.MAX_SET_BYTES
        
        // Create a fragment that exceeds maxSetBytes (e.g. 2MB)
        val oversizedData = ByteArray(maxSetBytes + 100)
        val payload = FragmentPayload(
            fragmentID = "TOOLARGE".toByteArray(),
            index = 0,
            total = 1,
            originalType = MessageType.MESSAGE.value,
            data = oversizedData
        )
        
        val result = fragmentManager.handleFragment(createBitchatPacketFromFragment(payload))
        assertEquals("Should reject oversized fragment set", null, result)
        assertTrue("Should not be in maps", !fragmentManager.getDebugInfo().contains("TOOLARGE"))
    }

    @Test
    fun `test max fragments per id rejection`() {
        val limits = com.bitchat.android.util.AppConstants.Fragmentation
        val maxFragments = limits.MAX_FRAGMENTS_PER_ID
        
        val payload = FragmentPayload(
            fragmentID = "MANYFRAG".toByteArray(),
            index = 0,
            total = maxFragments + 1,
            originalType = MessageType.MESSAGE.value,
            data = ByteArray(10)
        )
        
        val result = fragmentManager.handleFragment(createBitchatPacketFromFragment(payload))
        assertEquals("Should reject message with too many fragments", null, result)
    }

    private fun createBitchatPacketFromFragment(payload: FragmentPayload): BitchatPacket {
        return BitchatPacket(
            version = 1u,
            type = MessageType.FRAGMENT.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload.encode(),
            ttl = 7u
        )
    }

    private fun createLargeFragmentPayload(size: Int, idString: String): FragmentPayload {
        val id = idString.toByteArray()
        val data = ByteArray(size)
        return FragmentPayload(
            fragmentID = id,
            index = 0,
            total = 2, // 2 fragments so it's not immediately reassembled
            originalType = MessageType.MESSAGE.value,
            data = data
        )
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8)
        for (i in 0 until 8) {
            val byteStr = hexString.substring(i * 2, i * 2 + 2)
            result[i] = byteStr.toInt(16).toByte()
        }
        return result
    }
}
