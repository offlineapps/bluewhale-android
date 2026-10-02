package com.bluewhale.android.courier

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether this device carries other people's sealed messages (courier mode). */
object CourierPreferenceManager {

    private const val PREFS_NAME = "courier_preferences"
    private const val KEY_ENABLED = "enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var sharedPrefs: SharedPreferences? = null

    fun init(context: Context) {
        if (sharedPrefs != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs = prefs
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        sharedPrefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    fun isEnabled(): Boolean = _enabled.value
}

/** Keeps the courier store in the app's private files, written atomically. */
class FileCourierPersistence(context: Context) : CourierStore.Persistence {
    private val file = java.io.File(context.filesDir, "courier_store.bin")

    override fun load(): ByteArray? = try {
        if (file.isFile) file.readBytes() else null
    } catch (e: Exception) {
        null
    }

    override fun save(bytes: ByteArray) {
        try {
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
        } catch (_: Exception) { }
    }

    override fun delete() {
        file.delete()
    }
}
