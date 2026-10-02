package com.bluewhale.android.mesh.l2cap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.util.Random

class L2capFramingTest {

    private fun framed(vararg packets: ByteArray): DataInputStream {
        val out = ByteArrayOutputStream()
        packets.forEach { L2capFraming.write(out, it) }
        return DataInputStream(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun `frames come back whole and in order`() {
        val big = ByteArray(300_000).also { Random(1).nextBytes(it) }
        val small = byteArrayOf(1, 2, 3)
        val input = framed(big, small)

        assertArrayEquals(big, L2capFraming.read(input, 1_000_000))
        assertArrayEquals(small, L2capFraming.read(input, 1_000_000))
        assertNull("clean end between frames", L2capFraming.read(input, 1_000_000))
    }

    @Test(expected = IOException::class)
    fun `an oversized frame is refused before allocating it`() {
        L2capFraming.read(framed(ByteArray(2_000)), 1_000)
    }

    @Test(expected = IOException::class)
    fun `a negative length is refused`() {
        L2capFraming.read(DataInputStream(ByteArrayInputStream(byteArrayOf(-1, -1, -1, -1))), 1_000)
    }

    @Test(expected = EOFException::class)
    fun `a frame cut short is an error, not a short packet`() {
        val out = ByteArrayOutputStream()
        L2capFraming.write(out, ByteArray(100))
        val cut = out.toByteArray().copyOf(50)
        L2capFraming.read(DataInputStream(ByteArrayInputStream(cut)), 1_000)
    }
}
