package com.bitchat.android.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Receiver that listens for incoming SMS messages and triggers the WebhookForwarder
 * if the SMS Gateway feature is enabled.
 */
class SmsBroadcastReceiver : BroadcastReceiver() {
    private val TAG = "SmsBroadcastReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION && 
            action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            return
        }
        
        // 1. Check if the gateway feature is enabled globally
        if (!SmsGatewayPreferenceManager.isEnabled(context)) {
            Log.v(TAG, "SMS received but Gateway is disabled. Ignoring.")
            return
        }
        
        // 2. Ensure a webhook URL is configured
        val webhookUrl = SmsGatewayPreferenceManager.getWebhookUrl(context)
        if (webhookUrl.isBlank()) {
            Log.w(TAG, "Gateway enabled but no Webhook URL configured. Ignoring SMS.")
            return
        }
        
        Log.i(TAG, "Received SMS, preparing to forward to Webhook...")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Received SMS. Forwarding to webhook.", Toast.LENGTH_SHORT).show()
        }
        
        try {
            // 3. Extract SMS messages from the intent
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            
            // Reconstruct the message body (multipart SMS)
            var sender = ""
            var timestamp = 0L
            val messageBodyBuilder = StringBuilder()
            
            for (smsMessage in messages) {
                if (smsMessage != null) {
                    if (sender.isEmpty()) {
                        sender = smsMessage.originatingAddress ?: "Unknown"
                        timestamp = smsMessage.timestampMillis
                    }
                    messageBodyBuilder.append(smsMessage.messageBody)
                }
            }
            
            val fullMessage = messageBodyBuilder.toString()
            if (fullMessage.isEmpty()) {
                Log.w(TAG, "SMS message body is empty, not forwarding.")
                return
            }
            
            // 4. Enqueue background work for reliable delivery
            val inputData = Data.Builder()
                .putString(WebhookWorker.KEY_WEBHOOK_URL, webhookUrl)
                .putString(WebhookWorker.KEY_SENDER, sender)
                .putString(WebhookWorker.KEY_MESSAGE, fullMessage)
                .putLong(WebhookWorker.KEY_TIMESTAMP, timestamp)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<WebhookWorker>()
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            Log.e(TAG, "Error processing incoming SMS", e)
        }
    }
}
