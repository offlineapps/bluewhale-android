package com.bitchat.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView


@Composable
fun BitchatTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val textColorPref by TextColorPreferenceManager.textColorFlow.collectAsState(initial = TextColorPreference.Green)
    
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val dynamicPrimary = textColorPref.getColor(shouldUseDark)

    val colorScheme = if (shouldUseDark) {
        darkColorScheme(
            primary = dynamicPrimary,
            onPrimary = Color.Black,
            secondary = dynamicPrimary.copy(alpha = 0.8f),
            onSecondary = Color.Black,
            background = Color.Black,
            onBackground = dynamicPrimary,
            surface = Color(0xFF111111),
            onSurface = dynamicPrimary,
            error = Color(0xFFFF5555),
            onError = Color.Black
        )
    } else {
        lightColorScheme(
            primary = dynamicPrimary,
            onPrimary = Color.White,
            secondary = dynamicPrimary.copy(alpha = 0.8f),
            onSecondary = Color.White,
            background = Color.White,
            onBackground = dynamicPrimary,
            surface = Color(0xFFF8F8F8),
            onSurface = dynamicPrimary,
            error = Color(0xFFCC0000),
            onError = Color.White
        )
    }

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
