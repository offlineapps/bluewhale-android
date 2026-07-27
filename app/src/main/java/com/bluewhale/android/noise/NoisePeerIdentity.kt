package com.bluewhale.android.noise

import java.security.MessageDigest

/**
 * A peer ID is the first 8 bytes of SHA-256 over the peer's Noise static public key,
 * which is how this device derives its own. Anything that accepts an identity from the
 * network has to check that relationship, otherwise a peer ID is only a claim.
 */
object NoisePeerIdentity {

    private val peerIDFormat = Regex("^[0-9a-fA-F]{16}$")

    fun derivePeerID(staticPublicKey: ByteArray): String? {
        if (staticPublicKey.size != 32) return null
        return MessageDigest.getInstance("SHA-256")
            .digest(staticPublicKey)
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }

    fun matchesClaimedPeerID(claimedPeerID: String, staticPublicKey: ByteArray): Boolean {
        if (!peerIDFormat.matches(claimedPeerID)) return false
        return derivePeerID(staticPublicKey)?.equals(claimedPeerID, ignoreCase = true) == true
    }
}
