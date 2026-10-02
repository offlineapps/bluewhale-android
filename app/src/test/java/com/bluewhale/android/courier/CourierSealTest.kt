package com.bluewhale.android.courier

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CourierSealTest {

    private val alice = CourierTestKeys()
    private val bob = CourierTestKeys()
    private val eve = CourierTestKeys()
    private val prologue = "header".toByteArray()
    private val secret = "meet at the north gate at six".toByteArray()

    @Test
    fun `recipient opens it and learns the sender`() {
        val sealed = CourierSeal.seal(alice.privateKey, bob.publicKey, secret, prologue)
        val opened = CourierSeal.open(bob.privateKey, sealed, prologue)

        assertNotNull(opened)
        assertArrayEquals(secret, opened!!.plaintext)
        assertArrayEquals(alice.publicKey, opened.senderStaticKey)
    }

    @Test
    fun `nobody else can open it`() {
        val sealed = CourierSeal.seal(alice.privateKey, bob.publicKey, secret, prologue)

        assertNull(CourierSeal.open(eve.privateKey, sealed, prologue))
    }

    @Test
    fun `carriers see neither the content nor the sender key`() {
        val sealed = CourierSeal.seal(alice.privateKey, bob.publicKey, secret, prologue)

        assertFalse(sealed.toHex().contains(secret.toHex()))
        assertFalse(sealed.toHex().contains(alice.publicKey.toHex()))
        assertEquals(secret.size + CourierSeal.OVERHEAD, sealed.size)
    }

    @Test
    fun `changing the header breaks it`() {
        val sealed = CourierSeal.seal(alice.privateKey, bob.publicKey, secret, prologue)

        assertNull(CourierSeal.open(bob.privateKey, sealed, "headex".toByteArray()))
    }

    @Test
    fun `changing a byte of the sealed part breaks it`() {
        val sealed = CourierSeal.seal(alice.privateKey, bob.publicKey, secret, prologue)
        sealed[sealed.size - 20] = (sealed[sealed.size - 20].toInt() xor 1).toByte()

        assertNull(CourierSeal.open(bob.privateKey, sealed, prologue))
    }

    @Test
    fun `garbage is rejected without throwing`() {
        assertNull(CourierSeal.open(bob.privateKey, ByteArray(10), prologue))
        assertNull(CourierSeal.open(bob.privateKey, ByteArray(200) { 7 }, prologue))
    }
}
