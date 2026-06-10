package com.bitchat.android.smsgateway

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages preferences for the SMS Webhook Gateway feature
 */
object SmsGatewayPreferenceManager {
    private const val PREFS_NAME = "sms_gateway_prefs"
    private const val KEY_GATEWAY_ENABLED = "gateway_enabled"
    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_GATEWAY_NAME = "gateway_name"
    private const val KEY_PHONE_NUMBER = "phone_number"
    private const val KEY_PUBLISH_TO_NOSTR = "publish_to_nostr"
    
    // In-memory state flows for UI reactivity
    private val _isGatewayEnabled = MutableStateFlow(false)
    val isGatewayEnabled: StateFlow<Boolean> = _isGatewayEnabled.asStateFlow()
    
    private val _webhookUrl = MutableStateFlow("")
    val webhookUrl: StateFlow<String> = _webhookUrl.asStateFlow()
    
    private val _gatewayName = MutableStateFlow("")
    val gatewayName: StateFlow<String> = _gatewayName.asStateFlow()
    
    private val _phoneNumber = MutableStateFlow("")
    val phoneNumber: StateFlow<String> = _phoneNumber.asStateFlow()
    
    private val _publishToNostr = MutableStateFlow(false)
    val publishToNostr: StateFlow<Boolean> = _publishToNostr.asStateFlow()
    
    /**
     * Initialize from SharedPreferences
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isGatewayEnabled.value = prefs.getBoolean(KEY_GATEWAY_ENABLED, false)
        _webhookUrl.value = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        _gatewayName.value = prefs.getString(KEY_GATEWAY_NAME, "") ?: ""
        _phoneNumber.value = prefs.getString(KEY_PHONE_NUMBER, "") ?: ""
        _publishToNostr.value = prefs.getBoolean(KEY_PUBLISH_TO_NOSTR, false)
    }
    
    fun setGatewayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_GATEWAY_ENABLED, enabled)
        }
        _isGatewayEnabled.value = enabled
    }
    
    fun setWebhookUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_WEBHOOK_URL, url)
        }
        _webhookUrl.value = url
    }
    
    fun setGatewayName(context: Context, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_GATEWAY_NAME, name)
        }
        _gatewayName.value = name
    }
    
    fun setPhoneNumber(context: Context, number: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_PHONE_NUMBER, number)
        }
        _phoneNumber.value = number
    }
    
    fun setPublishToNostr(context: Context, publish: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_PUBLISH_TO_NOSTR, publish)
        }
        _publishToNostr.value = publish
    }
    
    /**
     * Non-flow getters for broadcast receiver synchronous access
     */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GATEWAY_ENABLED, false)
    }
    
    fun getWebhookUrl(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WEBHOOK_URL, "") ?: ""
    }
}
