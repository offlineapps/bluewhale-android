package com.bluewhale.android.protocol

import com.bluewhale.android.courier.CourierEnvelope
import com.bluewhale.android.courier.CourierWire
import com.bluewhale.android.features.voice.VoiceBurstCodec
import com.bluewhale.android.features.voice.VoiceBurstPacket
import com.bluewhale.android.find.SharedPosition
import com.bluewhale.android.mesh.l2cap.L2capFraming
import com.bluewhale.android.model.AnnounceL2capPsm
import com.bluewhale.android.model.AnnounceOriginTtl
import com.bluewhale.android.model.BluewhaleFilePacket
import com.bluewhale.android.model.BluewhaleMessage
import com.bluewhale.android.model.FragmentPayload
import com.bluewhale.android.model.IdentityAnnouncement
import com.bluewhale.android.model.NoisePayload
import com.bluewhale.android.model.NoisePayloadType
import com.bluewhale.android.model.PeerStatePayload
import com.bluewhale.android.model.PrivateMessagePacket
import com.bluewhale.android.model.RequestSyncPacket
import com.bluewhale.android.services.VerificationService
import com.bluewhale.android.services.meshgraph.GossipTLV
import com.bluewhale.android.sync.GCSFilter
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.Date
import kotlin.random.Random

/**
 * Every decoder here reads bytes that any device in Bluetooth range can send. A decoder that
 * throws instead of returning null can take down the coroutine or thread that called it, and
 * on the main thread that is the whole app, so each one is fed random bytes and mutated valid
 * encodings and must only ever return a value or null.
 *
 * Seeds are fixed so a failure reproduces, and the failing input is printed in hex. The nightly
 * fuzz workflow runs the same cases at a larger scale with a new seed each night:
 * `./gradlew testDebugUnitTest --tests '*FuzzTest' -PfuzzScale=100 -PfuzzSeed=1234`.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PeerInputFuzzTest {

    private val scale = System.getProperty("bluewhale.fuzzScale")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    private val seedOffset = System.getProperty("bluewhale.fuzzSeed")?.toLongOrNull() ?: 0L

    private fun random(base: Long) = Random(base xor seedOffset)

    private class Decoder(
        val name: String,
        /** Exceptions the decoder documents as its way of rejecting input. */
        val allowed: Set<Class<out Throwable>> = emptySet(),
        val decode: (ByteArray) -> Any?
    )

    private val decoders = listOf(
        Decoder("BinaryProtocol.decode") { BinaryProtocol.decode(it) },
        Decoder("MessagePadding.unpad") { MessagePadding.unpad(it) },
        Decoder("CompressionUtil.decompress") { bytes ->
            // The original size comes from the sender too
            if (bytes.size < 2) null
            else CompressionUtil.decompress(bytes.copyOfRange(2, bytes.size), ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF))
        },
        Decoder("BluewhaleMessage.fromBinaryPayload") { BluewhaleMessage.fromBinaryPayload(it) },
        Decoder("NoisePayload.decode") { NoisePayload.decode(it) },
        Decoder("PrivateMessagePacket.decode") { PrivateMessagePacket.decode(it) },
        Decoder("IdentityAnnouncement.decode") { IdentityAnnouncement.decode(it) },
        Decoder("AnnounceOriginTtl.decode") { AnnounceOriginTtl.decode(it) },
        Decoder("AnnounceL2capPsm.decode") { AnnounceL2capPsm.decode(it) },
        Decoder("GossipTLV.decodeNeighbors") { GossipTLV.decodeNeighborsFromAnnouncementPayload(it) },
        Decoder("PeerStatePayload.decode") { PeerStatePayload.decode(it) },
        Decoder("FragmentPayload.decode") { FragmentPayload.decode(it) },
        Decoder("RequestSyncPacket.decode") { RequestSyncPacket.decode(it) },
        Decoder("GCSFilter.decodeToSortedSet") { bytes ->
            // As GossipSyncManager does: parameters from the request, then the filter bits
            RequestSyncPacket.decode(bytes)?.let { GCSFilter.decodeToSortedSet(it.p, it.m, it.data) }
            if (bytes.size >= 2) GCSFilter.decodeToSortedSet((bytes[0].toInt() and 0xFF).coerceAtLeast(1), (bytes[1].toLong() and 0xFF) + 1, bytes)
            else null
        },
        Decoder("BluewhaleFilePacket.decode") { BluewhaleFilePacket.decode(it) },
        Decoder("VoiceBurstPacket.decode") { VoiceBurstPacket.decode(it) },
        Decoder("CourierWire.decode") { CourierWire.decode(it) },
        Decoder("SharedPosition.decode") { SharedPosition.decode(it) },
        Decoder("VerificationService.parseVerifyChallenge") { VerificationService.parseVerifyChallenge(it) },
        Decoder("VerificationService.parseVerifyResponse") { VerificationService.parseVerifyResponse(it) },
        Decoder(
            "L2capFraming.read",
            // A frame that is too large or cut short is refused with an IOException, which
            // ends that one channel
            allowed = setOf(IOException::class.java)
        ) { bytes ->
            val input = DataInputStream(ByteArrayInputStream(bytes))
            while (L2capFraming.read(input, 1024) != null) Unit
        }
    )

    private val senderID = ByteArray(8) { (it + 1).toByte() }
    private val key32 = ByteArray(32) { (it * 7).toByte() }

    /** Valid encodings of every format, so mutations reach past the first length check. */
    private fun seeds(): List<ByteArray> {
        val message = BluewhaleMessage(
            id = "ABCDEF01-2345-6789-ABCD-EF0123456789",
            sender = "alice",
            content = "hello mesh",
            timestamp = Date(1_700_000_000_000L),
            originalSender = "bob",
            senderPeerID = "0102030405060708",
            mentions = listOf("carol", "dave"),
            channel = "#general"
        ).toBinaryPayload()!!
        val packet = BluewhalePacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = senderID,
            recipientID = ByteArray(8) { 9 },
            timestamp = 1_700_000_000_000uL,
            payload = message,
            signature = ByteArray(64) { 3 },
            ttl = 7u
        )
        val packetV2 = packet.copy(
            version = 2u,
            payload = ByteArray(600) { (it % 13).toByte() },
            route = listOf(ByteArray(8) { 4 }, ByteArray(8) { 5 })
        )
        val announce = IdentityAnnouncement("alice", key32, key32).encode()!! +
            AnnounceOriginTtl.encode(3u) + AnnounceL2capPsm.encode(0x0081) +
            GossipTLV.encodeNeighbors(listOf("0102030405060708", "1112131415161718"))
        val envelope = CourierEnvelope(
            ByteArray(CourierWire.ID_SIZE) { 1 }, ByteArray(CourierWire.TAG_SIZE) { 2 },
            CourierEnvelope.Priority.URGENT, 1_000L, 2_000L, ByteArray(80) { 6 }
        )
        val burstID = ByteArray(8) { 7 }
        val compressible = packet.copy(payload = "mesh ".repeat(80).toByteArray())
        val verifyFields = byteArrayOf(0x01, 64) + ByteArray(64) { 0x61 } + byteArrayOf(0x02, 16) + ByteArray(16) { 7 }
        return listOfNotNull(
            BinaryProtocol.encode(packet, padding = false),
            BinaryProtocol.encode(packet, padding = true),
            BinaryProtocol.encode(packetV2, padding = false),
            BinaryProtocol.encode(compressible, padding = false),
            CompressionUtil.compress(ByteArray(400) { (it % 7).toByte() })?.let { byteArrayOf(0x01, 0x90.toByte()) + it },
            message,
            NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, PrivateMessagePacket("id-1", "hi").encode()!!).encode(),
            PrivateMessagePacket("id-1", "hi there").encode(),
            announce,
            PeerStatePayload(0x1234L, key32).encode(),
            FragmentPayload(ByteArray(8) { 1 }, 2, 5, MessageType.MESSAGE.value, ByteArray(100) { 8 }).encode(),
            RequestSyncPacket(16, 1L shl 20, ByteArray(40) { 0x5A }).encode(),
            BluewhaleFilePacket("note.m4a", 300, "audio/mp4", ByteArray(300) { 2 }).encode(),
            VoiceBurstPacket.create(burstID, 0, VoiceBurstPacket.Kind.Start(VoiceBurstCodec.AAC_LC_16K_MONO))?.encode(),
            VoiceBurstPacket.create(burstID, 1, VoiceBurstPacket.Kind.Frames(listOf(ByteArray(40) { 1 }, ByteArray(9) { 2 })))?.encode(),
            VoiceBurstPacket.create(burstID, 2, VoiceBurstPacket.Kind.End(1, 1_500L))?.encode(),
            CourierWire.encode(envelope),
            CourierWire.encodeAck(listOf(ByteArray(CourierWire.ID_SIZE) { 3 })),
            SharedPosition(51.5, -0.12, 8f, 1_700_000_000_000L).encode(),
            byteArrayOf(0, 0, 0, 4, 1, 2, 3, 4, 0, 0, 0, 2, 9, 9),
            verifyFields,
            verifyFields + byteArrayOf(0x03, 64) + ByteArray(64) { 5 }
        )
    }

    /** Byte values that sit on the edges of signed and unsigned length fields. */
    private val edgeBytes = byteArrayOf(0x00, 0x01, 0x7E, 0x7F, 0x80.toByte(), 0x81.toByte(), 0xFE.toByte(), 0xFF.toByte())

    private fun mutate(seed: ByteArray, random: Random): ByteArray {
        if (seed.isEmpty()) return random.nextBytes(random.nextInt(1, 64))
        val out = seed.copyOf()
        when (random.nextInt(7)) {
            0 -> repeat(random.nextInt(1, 4)) {
                val i = random.nextInt(out.size)
                out[i] = (out[i].toInt() xor (1 shl random.nextInt(8))).toByte()
            }
            1 -> repeat(random.nextInt(1, 4)) { out[random.nextInt(out.size)] = edgeBytes[random.nextInt(edgeBytes.size)] }
            2 -> return out.copyOf(random.nextInt(out.size))
            3 -> return out + random.nextBytes(random.nextInt(1, 64))
            4 -> {
                val start = random.nextInt(out.size)
                return out.copyOfRange(0, start) + out.copyOfRange((start + random.nextInt(1, 16)).coerceAtMost(out.size), out.size)
            }
            5 -> {
                val i = random.nextInt(out.size)
                return out.copyOfRange(0, i) + random.nextBytes(random.nextInt(1, 8)) + out.copyOfRange(i, out.size)
            }
            else -> repeat(random.nextInt(1, 8)) { out[random.nextInt(out.size)] = random.nextInt().toByte() }
        }
        return out
    }

    private fun check(decoder: Decoder, input: ByteArray) {
        try {
            decoder.decode(input)
        } catch (t: Throwable) {
            if (decoder.allowed.any { it.isInstance(t) }) return
            val hex = input.joinToString("") { "%02x".format(it) }
            fail("${decoder.name} threw ${t::class.java.name}: ${t.message} (fuzzSeed=$seedOffset)\ninput (${input.size} bytes): $hex")
        }
    }

    @Test
    fun `decoders never throw on random bytes`() {
        val random = random(0x6A3D)
        for (decoder in decoders) {
            for (size in 0..64) check(decoder, random.nextBytes(size))
            repeat(400 * scale) { check(decoder, random.nextBytes(random.nextInt(0, 1_200))) }
        }
    }

    @Test
    fun `decoders never throw on mutated valid encodings`() {
        val random = random(0x51EED)
        val corpus = seeds()
        for (decoder in decoders) {
            for (seed in corpus) {
                check(decoder, seed)
                repeat(150 * scale) { check(decoder, mutate(seed, random)) }
            }
        }
    }

    @Test
    fun `decoders never throw on every truncation of valid encodings`() {
        for (decoder in decoders) {
            for (seed in seeds()) {
                for (length in 0 until seed.size) check(decoder, seed.copyOf(length))
            }
        }
    }

    @Test
    fun `decoders never throw when any single byte takes an edge value`() {
        for (decoder in decoders) {
            for (seed in seeds().filter { it.size <= 160 }) {
                for (i in seed.indices) {
                    for (edge in edgeBytes) {
                        val input = seed.copyOf()
                        input[i] = edge
                        check(decoder, input)
                    }
                }
            }
        }
    }
}
