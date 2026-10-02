package com.bluewhale.android.find

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which peer the user is looking for right now, if any. While set, RSSI is polled fast. */
object FindMode {
    private val _target = MutableStateFlow<String?>(null)
    val target: StateFlow<String?> = _target.asStateFlow()

    val isActive: Boolean get() = _target.value != null

    fun start(peerID: String) {
        _target.value = peerID
    }

    fun stop() {
        _target.value = null
    }
}
