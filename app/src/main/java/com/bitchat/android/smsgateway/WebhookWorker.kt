package com.bitchat.android.smsgateway

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebhookWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_WEBHOOK_URL = "webhook_url"
        const val KEY_SENDER = "sender"
        const val KEY_MESSAGE = "message"
        const val KEY_TIMESTAMP = "timestamp"
    }

    override suspend fun doWork(): androidx.work.ListenableWorker.Result = withContext(Dispatchers.IO) {
        val webhookUrl = inputData.getString(KEY_WEBHOOK_URL)
        val sender = inputData.getString(KEY_SENDER)
        val message = inputData.getString(KEY_MESSAGE)
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())

        if (webhookUrl.isNullOrBlank() || sender.isNullOrBlank() || message.isNullOrBlank()) {
            Log.e("WebhookWorker", "Missing required input data for webhook worker")
            return@withContext androidx.work.ListenableWorker.Result.failure()
        }

        val success = WebhookForwarder.forwardSms(
            webhookUrl = webhookUrl,
            sender = sender,
            message = message,
            timestamp = timestamp
        )

        if (success) {
            androidx.work.ListenableWorker.Result.success()
        } else {
            // Android WorkManager will automatically apply exponential backoff and retry
            androidx.work.ListenableWorker.Result.retry()
        }
    }
}
