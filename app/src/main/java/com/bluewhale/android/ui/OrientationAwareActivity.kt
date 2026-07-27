package com.bluewhale.android.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.bluewhale.android.utils.DeviceUtils

/**
 * Base activity that automatically sets orientation based on device type.
 * Tablets can rotate to landscape, phones are locked to portrait.
 */
abstract class OrientationAwareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blockScreenCapture()
        setOrientationBasedOnDeviceType()
    }

    /**
     * Keeps window contents out of screenshots, screen recordings, the recent apps
     * thumbnail and non-secure external displays. Applied here so every screen is
     * covered rather than whichever activity remembered to ask.
     */
    private fun blockScreenCapture() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    private fun setOrientationBasedOnDeviceType() {
        requestedOrientation = if (DeviceUtils.isTablet(this)) {
            // Allow all orientations on tablets
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            // Lock to portrait on phones
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}
