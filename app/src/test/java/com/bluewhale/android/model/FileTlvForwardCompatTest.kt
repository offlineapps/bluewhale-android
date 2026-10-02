package com.bluewhale.android.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * A file packet carrying a TLV type this build does not know used to be dropped whole,
 * media included, while iOS skips such tags. Any optional field a newer client adds
 * would otherwise cost every older Android peer the entire file.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FileTlvForwardCompatTest {

    private val content = ByteArray(300) { (it % 251).toByte() }
    private fun encoded() = BluewhaleFilePacket("note.m4a", content.size.toLong(), "audio/mp4", content).encode()!!

    private fun unknownTlv(type: Int, value: ByteArray) =
        byteArrayOf(type.toByte(), (value.size shr 8).toByte(), value.size.toByte()) + value

    @Test
    fun `unknown TLV before the content is skipped`() {
        val withExtra = unknownTlv(0x7F, byteArrayOf(1, 2, 3)) + encoded()
        val decoded = BluewhaleFilePacket.decode(withExtra)
        assertNotNull("a file with an unknown optional field must still decode", decoded)
        assertEquals("note.m4a", decoded!!.fileName)
        assertArrayEquals(content, decoded.content)
    }

    @Test
    fun `unknown TLV after the content is skipped`() {
        val decoded = BluewhaleFilePacket.decode(encoded() + unknownTlv(0x42, ByteArray(10)))
        assertNotNull(decoded)
        assertArrayEquals(content, decoded!!.content)
    }

    @Test
    fun `many empty unknown TLVs are tolerated`() {
        var padding = ByteArray(0)
        repeat(1000) { padding += unknownTlv(0x55, ByteArray(0)) }
        val decoded = BluewhaleFilePacket.decode(padding + encoded())
        assertNotNull(decoded)
        assertArrayEquals(content, decoded!!.content)
    }

    @Test
    fun `unknown TLV whose length overruns the packet is rejected`() {
        val bad = encoded() + byteArrayOf(0x7F, 0x00, 0x20, 1, 2)
        assertNull(BluewhaleFilePacket.decode(bad))
    }

    @Test
    fun `truncated trailing TLV header is rejected`() {
        assertNull(BluewhaleFilePacket.decode(encoded() + byteArrayOf(0x7F, 0x00)))
    }
}
