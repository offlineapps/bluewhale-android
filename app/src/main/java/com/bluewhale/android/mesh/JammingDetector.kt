package com.bluewhale.android.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Heuristic detector for deliberate 2.4 GHz jamming of the Bluetooth mesh.
 *
 * Phones cannot measure the noise floor, so this looks for what a jammer does to the mesh and
 * what ordinary movement does not: several links dying of radio timeouts within seconds of
 * each other ("link storm"), and the mesh going silent while scanning ("silence") when it was
 * busy a minute ago. Walking away from a group loses peers one at a time over minutes, so it
 * triggers neither. Either signal alone is reported as [Level.POSSIBLE]; both close together as
 * [Level.LIKELY].
 *
 * All inputs are plain calls with an injectable clock, so the logic is testable without a radio.
 */
class JammingDetector(private val clock: () -> Long = System::currentTimeMillis) {

    enum class Level { CLEAR, POSSIBLE, LIKELY }

    data class Assessment(
        val level: Level,
        val reasons: List<String> = emptyList(),
        val since: Long = 0L,
        /** Cleared because our own radio stopped, not because the air cleared: say nothing. */
        val silentReset: Boolean = false
    )

    companion object {
        /** HCI / GATT disconnect reasons that mean the radio link failed, not that someone left. */
        val RADIO_FAILURE_STATUSES = setOf(
            0x08, // connection supervision timeout
            0x22, // LMP / LL response timeout
            0x3E, // connection failed to be established
            0x93  // GATT connection timeout (Android 147)
        )

        const val STORM_WINDOW_MS = 15_000L
        const val STORM_MIN_LINKS = 3
        /** Losing every link at once also counts, if there were at least this many. */
        const val STORM_MIN_LINKS_IF_ALL = 2

        const val BASELINE_WINDOW_MS = 120_000L
        const val SILENCE_MIN_BASELINE_DEVICES = 3
        /** Scan time without a single advertisement before the mesh counts as silent. */
        const val SILENCE_SCAN_MS = 30_000L

        /** Signals this close together make jamming likely rather than possible. */
        const val CORRELATION_WINDOW_MS = 30_000L
        /** How long a signal keeps the assessment raised once its cause stops. */
        const val HOLD_MS = 60_000L
    }

    /**
     * The chat line for a change from [previous] to [next], or null when nothing worth saying
     * changed (same level, or a step down that is not yet all clear).
     */
    object Notices {
        fun forTransition(previous: Level, next: Assessment): String? = when {
            next.level == previous || next.silentReset -> null
            next.level == Level.LIKELY ->
                "⚠ likely bluetooth jamming: ${next.reasons.joinToString("; ")}. " +
                    "radio switched to full power. move a few rooms or a street away, or get line of sight " +
                    "to the others; messages wait in the outbox and go out once links come back."
            next.level == Level.POSSIBLE && previous == Level.CLEAR ->
                "possible bluetooth interference: ${next.reasons.joinToString("; ")}."
            next.level == Level.CLEAR -> "bluetooth looks clear again."
            else -> null
        }
    }

    /** The detector fed by the live Bluetooth stack; tests make their own instances. */
    object Shared {
        val detector = JammingDetector()
    }

    private val _state = MutableStateFlow(Assessment(Level.CLEAR))
    val state: StateFlow<Assessment> = _state.asStateFlow()

    // address -> last time an advertisement was seen
    private val lastSeen = HashMap<String, Long>()
    // recent radio-failure link losses: address -> time
    private val linkFailures = ArrayDeque<Pair<String, Long>>()

    private var connectedLinks = 0
    private var scanning = false
    private var lastAccrualAt = 0L
    // Scan time accumulated since the last advertisement
    private var silentScanMs = 0L

    private var stormAt: Long? = null
    private var stormLinks = 0
    private var silenceAt: Long? = null

    @Synchronized
    fun onAdvertisementSeen(address: String) {
        val now = clock()
        accrueScanTime(now)
        lastSeen[address] = now
        silentScanMs = 0
        silenceAt = null
        evaluate(now)
    }

    @Synchronized
    fun onScanning(active: Boolean) {
        val now = clock()
        accrueScanTime(now)
        scanning = active
        lastAccrualAt = now
        evaluate(now)
    }

    @Synchronized
    fun onLinkUp() {
        connectedLinks++
    }

    /** A link went down; [status] is the GATT callback status. */
    @Synchronized
    fun onLinkLost(address: String, status: Int) {
        val now = clock()
        val linksBefore = connectedLinks
        connectedLinks = (connectedLinks - 1).coerceAtLeast(0)
        if (status in RADIO_FAILURE_STATUSES) {
            linkFailures.addLast(address to now)
            pruneFailures(now)
            val distinct = linkFailures.map { it.first }.toSet().size
            val lostEverything = connectedLinks == 0 && linksBefore > 0
            if (distinct >= STORM_MIN_LINKS || (lostEverything && distinct >= STORM_MIN_LINKS_IF_ALL)) {
                stormAt = now
                stormLinks = distinct
            }
        }
        evaluate(now)
    }

    /** The local adapter was switched off or lost: our own silence is not jamming. */
    @Synchronized
    fun reset() {
        lastSeen.clear()
        linkFailures.clear()
        connectedLinks = 0
        scanning = false
        silentScanMs = 0
        stormAt = null
        silenceAt = null
        _state.value = Assessment(Level.CLEAR, since = clock(), silentReset = true)
    }

    /** Call periodically; silence is the absence of events, so it needs time to pass. */
    @Synchronized
    fun tick() {
        val now = clock()
        accrueScanTime(now)
        evaluate(now)
    }

    private fun accrueScanTime(now: Long) {
        if (scanning) silentScanMs += (now - lastAccrualAt).coerceAtLeast(0)
        lastAccrualAt = now
    }

    private fun pruneFailures(now: Long) {
        while (linkFailures.isNotEmpty() && now - linkFailures.first().second > STORM_WINDOW_MS) {
            linkFailures.removeFirst()
        }
    }

    private fun evaluate(now: Long) {
        lastSeen.entries.removeAll { now - it.value > BASELINE_WINDOW_MS }

        // Devices that were around within the baseline window but have gone quiet
        val baselineDevices = lastSeen.count { now - it.value <= BASELINE_WINDOW_MS }
        if (silenceAt == null && silentScanMs >= SILENCE_SCAN_MS && baselineDevices >= SILENCE_MIN_BASELINE_DEVICES) {
            // When the quiet began (approximately, as scan time), to line it up with link failures
            silenceAt = now - silentScanMs
        }

        val storm = stormAt?.takeIf { now - it <= HOLD_MS }
        // Silence lasts until an advertisement arrives, or until the devices it was measured
        // against age out of the baseline (they may simply have left)
        val silence = silenceAt?.takeIf { baselineDevices >= SILENCE_MIN_BASELINE_DEVICES || now - it <= HOLD_MS }

        val reasons = buildList {
            if (storm != null) add("$stormLinks Bluetooth links failed with radio timeouts within ${STORM_WINDOW_MS / 1000}s")
            if (silence != null) add("nearby Bluewhale devices went silent at once while scanning")
        }
        val level = when {
            storm != null && silence != null && kotlin.math.abs(storm - silence) <= CORRELATION_WINDOW_MS -> Level.LIKELY
            storm != null || silence != null -> Level.POSSIBLE
            else -> Level.CLEAR
        }

        val previous = _state.value
        if (level != previous.level || reasons != previous.reasons) {
            _state.value = Assessment(level, reasons, if (level != previous.level) now else previous.since)
        }
    }
}
