package com.bluewhale.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluewhale.android.core.ui.component.sheet.BluewhaleBottomSheet
import com.bluewhale.android.find.ProximityEstimator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Warmer/colder finder for one peer, from the Bluetooth signal of the direct link. Pulses the
 * phone faster as the signal gets stronger, so it can be used without looking at the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindFriendSheet(peerID: String, viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val estimator = remember(peerID) { ProximityEstimator() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isDirect by remember { mutableStateOf(false) }
    var hapticsOn by remember { mutableStateOf(true) }
    val nickname = remember(peerID) { viewModel.meshService.getPeerNicknames()[peerID] ?: peerID.take(8) }

    // Sample the link RSSI; FindMode makes the radio read it every 400 ms meanwhile
    LaunchedEffect(peerID) {
        while (isActive) {
            val info = viewModel.meshService.getPeerInfo(peerID)
            isDirect = info?.isConnected == true && info.isDirectConnection
            if (isDirect) {
                viewModel.meshService.getPeerRSSI()[peerID]?.let { estimator.add(it, System.currentTimeMillis()) }
            }
            now = System.currentTimeMillis()
            delay(300)
        }
    }
    LaunchedEffect(peerID, hapticsOn) {
        while (isActive && hapticsOn) {
            val interval = estimator.pulseIntervalMs(System.currentTimeMillis())
            if (interval == null || !isDirect) {
                delay(500)
            } else {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                delay(interval)
            }
        }
    }

    BluewhaleBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("find @$nickname", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)

            val zone = estimator.zone(now)
            val trend = estimator.trend(now)
            val (headline, colour) = when {
                !isDirect -> "not in direct bluetooth range" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                zone == ProximityEstimator.Zone.VERY_CLOSE -> "right here" to Color(0xFFE53935)
                zone == ProximityEstimator.Zone.NEAR -> "very near" to Color(0xFFFB8C00)
                zone == ProximityEstimator.Zone.NEARBY -> "nearby" to Color(0xFFFDD835)
                zone == ProximityEstimator.Zone.FAR -> "in range, still far" to Color(0xFF1E88E5)
                else -> "listening…" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            }
            Text(headline, color = colour, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 28.sp)

            if (isDirect) {
                val trendText = when (trend) {
                    ProximityEstimator.Trend.WARMER -> "▲ warmer"
                    ProximityEstimator.Trend.COLDER -> "▼ colder"
                    ProximityEstimator.Trend.STEADY -> "● steady"
                    ProximityEstimator.Trend.UNKNOWN -> "walk a few steps"
                }
                Text(trendText, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                val metres = estimator.distanceMetres()
                val rssi = estimator.smoothed
                if (metres != null && rssi != null) {
                    Text(
                        "about ${if (metres < 1) "<1" else metres.roundToInt().toString()} m · ${rssi.roundToInt()} dBm",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                Text(
                    "they are on the mesh but not linked to this phone. move around until they are, or use gps below.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.ringPeer(peerID) }) {
                    Text("ring their phone", fontFamily = FontFamily.Monospace)
                }
                OutlinedButton(onClick = { viewModel.sharePositionWith(peerID) }) {
                    Text("send my gps", fontFamily = FontFamily.Monospace)
                }
            }
            OutlinedButton(
                onClick = { hapticsOn = !hapticsOn },
                colors = ButtonDefaults.outlinedButtonColors()
            ) {
                Text(if (hapticsOn) "pulses on" else "pulses off", fontFamily = FontFamily.Monospace)
            }
            Text(
                "signal strength is a rough guide: bodies and walls weaken it. follow warmer and colder.",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}
