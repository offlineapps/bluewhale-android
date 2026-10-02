package com.bluewhale.android.mesh.l2cap

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream

/**
 * Framing on an L2CAP channel: each packet is a 4-byte big-endian length then the encoded
 * packet. The receiver answers every complete frame with [ACK], so the sender knows the whole
 * packet arrived before it closes the channel or gives up and falls back to GATT.
 */
object L2capFraming {
    const val ACK: Int = 0x06

    fun write(out: OutputStream, packet: ByteArray) {
        val n = packet.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(packet)
        out.flush()
    }

    /**
     * Reads one frame. Returns null at a clean end of stream between frames; throws on a frame
     * larger than [maxBytes] (a peer trying to exhaust memory) or a stream cut mid-frame.
     */
    fun read(input: DataInputStream, maxBytes: Int): ByteArray? {
        val length = try {
            input.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (length <= 0 || length > maxBytes) throw IOException("frame of $length bytes refused")
        return ByteArray(length).also { input.readFully(it) }
    }
}
