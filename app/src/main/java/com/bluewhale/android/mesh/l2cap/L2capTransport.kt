package com.bluewhale.android.mesh.l2cap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.bluewhale.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bulk transfer over LE connection-oriented channels (L2CAP CoC, Android 10+).
 *
 * GATT moves a large file as hundreds of MTU-sized fragments, each its own write. An L2CAP
 * channel to a direct neighbour streams the whole packet with link-layer flow control instead,
 * several times faster. Only the transport changes: the packet is the same signed (and for
 * private files, Noise-encrypted) packet that would have gone over GATT, and the receiver runs it
 * through the same validation.
 *
 * Channels are insecure at the Bluetooth layer (no pairing), like the GATT link; security comes
 * from the packet itself. Anyone in range can open one, so frames are size-bounded, connections
 * are capped, and idle channels are closed.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class L2capTransport(
    private val adapter: BluetoothAdapter,
    private val scope: CoroutineScope,
    /** A complete frame arrived from the device at [address]. */
    private val onFrame: (bytes: ByteArray, address: String) -> Unit
) {
    companion object {
        private const val TAG = "L2capTransport"
        const val MAX_FRAME_BYTES = AppConstants.Fragmentation.MAX_SET_BYTES + 64 * 1024
        private const val MAX_INBOUND_CHANNELS = 4
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val ACK_TIMEOUT_MS = 20_000L
        private const val IDLE_TIMEOUT_MS = 30_000L
    }

    @Volatile
    var psm: Int? = null
        private set

    private var server: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private val inbound = AtomicInteger(0)

    /** Opens the listening channel. False when the stack or permissions do not allow it. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (server != null) return true
        return try {
            val socket = adapter.listenUsingInsecureL2capChannel()
            server = socket
            psm = socket.psm
            Log.i(TAG, "listening on PSM ${socket.psm}")
            acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(socket) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "L2CAP unavailable: ${e.message}")
            psm = null
            false
        }
    }

    fun stop() {
        psm = null
        acceptJob?.cancel()
        acceptJob = null
        closeQuietly(server)
        server = null
    }

    private fun acceptLoop(listener: BluetoothServerSocket) {
        while (server === listener) {
            val socket = try {
                listener.accept()
            } catch (e: Exception) {
                break
            }
            if (inbound.incrementAndGet() > MAX_INBOUND_CHANNELS) {
                inbound.decrementAndGet()
                closeQuietly(socket)
                continue
            }
            scope.launch(Dispatchers.IO) {
                try {
                    readLoop(socket)
                } finally {
                    inbound.decrementAndGet()
                    closeQuietly(socket)
                }
            }
        }
    }

    private suspend fun readLoop(socket: BluetoothSocket) {
        val address = socket.remoteDevice?.address ?: return
        // A channel that goes quiet is closed, so a peer cannot hold slots open forever
        val lastActivity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val watchdog = scope.launch {
            while (true) {
                delay(5_000)
                if (System.currentTimeMillis() - lastActivity.get() > IDLE_TIMEOUT_MS) {
                    closeQuietly(socket)
                    return@launch
                }
            }
        }
        try {
            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream
            while (true) {
                val frame = L2capFraming.read(input, MAX_FRAME_BYTES) ?: break
                lastActivity.set(System.currentTimeMillis())
                output.write(L2capFraming.ACK)
                output.flush()
                try {
                    onFrame(frame, address)
                } catch (e: Exception) {
                    Log.w(TAG, "frame handler failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "inbound channel from $address ended: ${e.message}")
        } finally {
            watchdog.cancel()
        }
    }

    /**
     * Sends one packet to the device at [address] on its channel [remotePsm] and waits for the
     * receiver's acknowledgement. False on any failure, so the caller can fall back to GATT.
     */
    @SuppressLint("MissingPermission")
    suspend fun send(address: String, remotePsm: Int, packet: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (packet.size > MAX_FRAME_BYTES) return@withContext false
        val socket = try {
            adapter.getRemoteDevice(address).createInsecureL2capChannel(remotePsm)
        } catch (e: Exception) {
            Log.w(TAG, "cannot create channel to $address: ${e.message}")
            return@withContext false
        }
        // BluetoothSocket blocks without timeouts; closing it from outside is the only way out
        val watchdog = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            closeQuietly(socket)
        }
        try {
            socket.connect()
            watchdog.cancel()
            val ackWatchdog = scope.launch {
                delay(ACK_TIMEOUT_MS)
                closeQuietly(socket)
            }
            try {
                L2capFraming.write(socket.outputStream, packet)
                val ack = socket.inputStream.read()
                ack == L2capFraming.ACK
            } finally {
                ackWatchdog.cancel()
            }
        } catch (e: Exception) {
            Log.w(TAG, "L2CAP send to $address failed: ${e.message}")
            false
        } finally {
            watchdog.cancel()
            closeQuietly(socket)
        }
    }

    private fun closeQuietly(c: Closeable?) {
        try {
            c?.close()
        } catch (_: Exception) {
        }
    }
}
