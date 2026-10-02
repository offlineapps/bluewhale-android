package com.bluewhale.android.courier

import com.bluewhale.android.noise.southernstorm.protocol.Noise

/** A Curve25519 static identity, as NoiseEncryptionService holds it. */
class CourierTestKeys {
    val privateKey = ByteArray(32)
    val publicKey = ByteArray(32)

    init {
        Noise.createDH("25519").apply {
            generateKeyPair()
            getPrivateKey(privateKey, 0)
            getPublicKey(publicKey, 0)
        }
    }

    val hex: String get() = publicKey.toHex()
}
