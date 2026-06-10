package com.bluewhale.android.smsgateway

import android.util.Log
import com.bluewhale.android.net.OkHttpProvider
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Handles forwarding received SMS messages to the configured Webhook URL
 */
object WebhookForwarder {
    private const val TAG = "WebhookForwarder"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    
    /**
     * Forwards an SMS message to the webhook URL via HTTP POST
     * @param webhookUrl The URL to POST to
     * @param sender The phone number of the SMS sender
     * @param message The body of the SMS
     * @param timestamp The time the SMS was received/sent
     */
    suspend fun forwardSms(
        webhookUrl: String,
        sender: String,
        message: String,
        timestamp: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) {
            Log.w(TAG, "Cannot forward SMS: Webhook URL is empty")
            return@withContext false
        }
        // Require HTTPS so SMS contents are never forwarded in cleartext.
        if (!webhookUrl.startsWith("https://", ignoreCase = true)) {
            Log.w(TAG, "Refusing to forward SMS: Webhook URL must use https")
            return@withContext false
        }

        try {
            // Create JSON payload
            val json = JsonObject().apply {
                addProperty("sender", sender)
                addProperty("message", message)
                addProperty("timestamp", timestamp)
            }
            
            val requestBody = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            
            // Build request
            val request = Request.Builder()
                .url(webhookUrl)
                .post(requestBody)
                .addHeader("User-Agent", "Bitchat")
                .build()
                
            Log.d(TAG, "Forwarding SMS from $sender to webhook: $webhookUrl")
            
            // Execute request using the app's shared OkHttpClient
            val client = OkHttpProvider.httpClient()
            val isSuccess = client.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                if (success) {
                    Log.i(TAG, "Successfully forwarded SMS to webhook. HTTP ${response.code}")
                } else {
                    Log.w(TAG, "Failed to forward SMS to webhook. HTTP ${response.code}: ${response.message}")
                }
                success
            }
            return@withContext isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Exception forwarding SMS to webhook: ${e.message}", e)
            return@withContext false
        }
    }
}
