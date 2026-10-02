package com.bluewhale.android.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import kotlin.random.Random

/**
 * Verify challenges and responses arrive from any peer with a Noise session, and are parsed in a
 * coroutine on the main thread with no exception handler: a parser that throws crashes the app.
 * Length bytes of 0x80 and above used to be read as negative numbers and made copyOfRange throw.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class VerifyPayloadParsingTest {

    private fun tlv(type: Int, value: ByteArray) = byteArrayOf(type.toByte(), value.size.toByte()) + value

    private val noiseHex = "ab".repeat(32)

    @Test
    fun `a length byte of 0x80 or more does not throw`() {
        for (length in 0x80..0xFF) {
            val header = byteArrayOf(0x01, length.toByte(), 0x41, 0x42)
            assertNull(VerificationService.parseVerifyChallenge(header))
            assertNull(VerificationService.parseVerifyResponse(header))

            val nonceField = tlv(0x01, noiseHex.toByteArray()) + byteArrayOf(0x02, length.toByte(), 1, 2, 3)
            assertNull(VerificationService.parseVerifyChallenge(nonceField))
            assertNull(VerificationService.parseVerifyResponse(nonceField))

            val sigField = tlv(0x01, noiseHex.toByteArray()) + tlv(0x02, ByteArray(16)) +
                byteArrayOf(0x03, length.toByte(), 9, 9)
            assertNull(VerificationService.parseVerifyResponse(sigField))
        }
    }

    @Test
    fun `fields of 128 to 255 bytes are read as unsigned lengths`() {
        val nonce = ByteArray(200) { it.toByte() }
        val signature = ByteArray(255) { (255 - it).toByte() }

        val challenge = VerificationService.parseVerifyChallenge(tlv(0x01, noiseHex.toByteArray()) + tlv(0x02, nonce))
        assertNotNull(challenge)
        assertEquals(noiseHex, challenge!!.first)
        assertArrayEquals(nonce, challenge.second)

        val response = VerificationService.parseVerifyResponse(
            tlv(0x01, noiseHex.toByteArray()) + tlv(0x02, nonce) + tlv(0x03, signature)
        )
        assertNotNull(response)
        assertEquals(noiseHex, response!!.noiseKeyHex)
        assertArrayEquals(nonce, response.nonceA)
        assertArrayEquals(signature, response.signature)
    }

    @Test
    fun `a normal challenge and response still parse`() {
        val nonce = ByteArray(16) { 7 }
        val signature = ByteArray(64) { 5 }
        assertNotNull(VerificationService.parseVerifyChallenge(tlv(0x01, noiseHex.toByteArray()) + tlv(0x02, nonce)))
        assertNotNull(
            VerificationService.parseVerifyResponse(tlv(0x01, noiseHex.toByteArray()) + tlv(0x02, nonce) + tlv(0x03, signature))
        )
    }

    @Test
    fun `random and truncated payloads never throw`() {
        val random = Random(0xBEEF)
        val valid = tlv(0x01, noiseHex.toByteArray()) + tlv(0x02, ByteArray(16) { 7 }) + tlv(0x03, ByteArray(64) { 5 })
        val inputs = buildList {
            for (length in 0..valid.size) add(valid.copyOf(length))
            repeat(2_000) { add(random.nextBytes(random.nextInt(0, 400))) }
            repeat(2_000) {
                val mutated = valid.copyOf()
                repeat(random.nextInt(1, 4)) { mutated[random.nextInt(mutated.size)] = random.nextInt().toByte() }
                add(mutated)
            }
        }
        for (input in inputs) {
            try {
                VerificationService.parseVerifyChallenge(input)
                VerificationService.parseVerifyResponse(input)
            } catch (t: Throwable) {
                fail("verify parser threw ${t::class.java.simpleName} on ${input.joinToString("") { "%02x".format(it) }}")
            }
        }
    }
}
