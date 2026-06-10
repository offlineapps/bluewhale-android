package com.bitchat.android.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App text color preferences.
 */
enum class TextColorPreference(val displayName: String) {
    Green("Green"),
    Yellow("Yellow"),
    Pink("Pink"),
    LightBlue("Light Blue"),
    Orange("Orange");

    /**
     * Returns the primary color for the current preference based on theme.
     */
    fun getColor(isDark: Boolean): Color {
        return if (isDark) {
            when (this) {
                Green -> Color(0xFF39FF14)      // Bright neon green
                Yellow -> Color(0xFFFFF01F)     // Bright yellow
                Pink -> Color(0xFFFF00FF)       // Magenta/Pink
                LightBlue -> Color(0xFF00FFFF)  // Cyan
                Orange -> Color(0xFFFFAC1C)    // Bright orange
            }
        } else {
            when (this) {
                Green -> Color(0xFF008000)      // Dark green
                Yellow -> Color(0xFFB48A00)     // Golden/Dark yellow
                Pink -> Color(0xFFD81B60)       // Deep pink
                LightBlue -> Color(0xFF007AFF)  // iOS blue
                Orange -> Color(0xFFE65100)     // Burnt orange
            }
        }
    }
}

/**
 * SharedPreferences-backed manager for text color preference.
 */
object TextColorPreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_TEXT_COLOR = "text_color_preference"

    private val _textColorFlow = MutableStateFlow(TextColorPreference.Green)
    val textColorFlow: StateFlow<TextColorPreference> = _textColorFlow

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_TEXT_COLOR, TextColorPreference.Green.name)
        _textColorFlow.value = runCatching { TextColorPreference.valueOf(saved!!) }.getOrDefault(TextColorPreference.Green)
    }

    fun set(context: Context, preference: TextColorPreference) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TEXT_COLOR, preference.name).apply()
        _textColorFlow.value = preference
    }
}
