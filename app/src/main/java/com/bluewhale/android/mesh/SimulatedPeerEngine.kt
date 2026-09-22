package com.bluewhale.android.mesh

import com.bluewhale.android.model.IdentityAnnouncement
import com.bluewhale.android.model.RoutedPacket
import com.bluewhale.android.protocol.BluewhalePacket
import com.bluewhale.android.protocol.MessageType
import com.bluewhale.android.protocol.SpecialRecipients
import com.bluewhale.android.util.AppConstants
import com.bluewhale.android.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A make believe peer with its own keys. It signs the same announce and broadcast
 * packets a real device would, so injected traffic passes the mesh through the normal
 * validation, dedup, and reassembly paths. Injected packets carry ttl 0 so the relay
 * layer never forwards them, which keeps this traffic on the device and off the radio.
 */
class SimulatedPeer(val nickname: String) {

    private val noisePublicKey: ByteArray = ByteArray(32).also { random.nextBytes(it) }
    private val signingPriv: Ed25519PrivateKeyParameters
    private val signingPub: Ed25519PublicKeyParameters

    val peerIDBytes: ByteArray = MessageDigest.getInstance("SHA-256").digest(noisePublicKey).copyOf(8)
    val peerID: String = peerIDBytes.toHexString()
    val signingPublicKey: ByteArray get() = signingPub.encoded

    init {
        val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(random)) }
        val kp = gen.generateKeyPair()
        signingPriv = kp.private as Ed25519PrivateKeyParameters
        signingPub = kp.public as Ed25519PublicKeyParameters
    }

    fun announce(now: Long): BluewhalePacket {
        val tlv = IdentityAnnouncement(nickname, noisePublicKey, signingPub.encoded).encode()
            ?: return unsignedFallback(now)
        return sign(
            BluewhalePacket(
                version = 1u,
                type = MessageType.ANNOUNCE.value,
                senderID = peerIDBytes,
                recipientID = null,
                timestamp = now.toULong(),
                payload = tlv,
                signature = null,
                ttl = AppConstants.SYNC_TTL_HOPS
            )
        )
    }

    fun broadcast(text: String, now: Long): BluewhalePacket = sign(
        BluewhalePacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = peerIDBytes,
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = now.toULong(),
            payload = text.toByteArray(Charsets.UTF_8),
            signature = null,
            ttl = AppConstants.SYNC_TTL_HOPS
        )
    )

    private fun sign(unsigned: BluewhalePacket): BluewhalePacket {
        val data = unsigned.toBinaryDataForSigning() ?: return unsigned
        val signer = Ed25519Signer().apply {
            init(true, signingPriv)
            update(data, 0, data.size)
        }
        return unsigned.copy(signature = signer.generateSignature())
    }

    private fun unsignedFallback(now: Long): BluewhalePacket = BluewhalePacket(
        version = 1u,
        type = MessageType.ANNOUNCE.value,
        senderID = peerIDBytes,
        recipientID = null,
        timestamp = now.toULong(),
        payload = ByteArray(0),
        signature = null,
        ttl = AppConstants.SYNC_TTL_HOPS
    )

    companion object {
        private val random = SecureRandom()
    }
}

/**
 * Drives a set of [SimulatedPeer]s on a timer and feeds their packets into the local
 * pipeline through [inject]. A debug only toy for exercising the mesh from one device.
 */
class SimulatedPeerEngine(
    private val scope: CoroutineScope,
    private val inject: (BluewhalePacket, String) -> Unit
) {
    private var job: Job? = null

    private val _injectedCount = MutableStateFlow(0)
    val injectedCount: StateFlow<Int> = _injectedCount.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Synchronized
    fun start(peerCount: Int, intervalMs: Long, stress: Boolean) {
        stop()
        val count = peerCount.coerceIn(1, MAX_PEERS)
        val interval = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        val peers = List(count) { SimulatedPeer("sim-${it + 1}") }
        _injectedCount.value = 0
        _running.value = true
        job = scope.launch {
            peers.forEach { emit(it.announce(System.currentTimeMillis()), it.peerID) }
            var tick = 0
            while (isActive) {
                val now = System.currentTimeMillis()
                peers.forEach { peer ->
                    if (tick % REANNOUNCE_EVERY == 0) emit(peer.announce(now), peer.peerID)
                    val msg = peer.broadcast("sim message #$tick from ${peer.nickname}", now)
                    emit(msg, peer.peerID)
                    if (stress) {
                        emit(msg, peer.peerID)
                        val ghost = SimulatedPeer("ghost-$tick")
                        emit(ghost.announce(now), ghost.peerID)
                    }
                }
                tick++
                delay(interval)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
    }

    private fun emit(packet: BluewhalePacket, peerID: String) {
        try {
            inject(packet, peerID)
            _injectedCount.value += 1
        } catch (_: Exception) {
        }
    }

    companion object {
        const val MAX_PEERS = 32
        const val MIN_INTERVAL_MS = 200L
        const val MAX_INTERVAL_MS = 10_000L
        private const val REANNOUNCE_EVERY = 20
    }
}
