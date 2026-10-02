package com.bluewhale.android.find

import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Rings and buzzes the phone briefly so a friend nearby can find it. Uses the notification
 * sound, so a phone on silent only vibrates.
 */
object FindRinger {
    private const val TAG = "FindRinger"
    private const val RING_MS = 4_000L

    fun ring(context: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            ringtone?.play()
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { ringtone?.stop() } }, RING_MS)
        } catch (e: Exception) {
            Log.w(TAG, "could not play ring: ${e.message}")
        }
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400, 200, 800), -1))
        } catch (e: Exception) {
            Log.w(TAG, "could not vibrate: ${e.message}")
        }
    }
}
