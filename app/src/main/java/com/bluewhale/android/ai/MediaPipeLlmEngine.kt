package com.bluewhale.android.ai

import android.content.Context
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs a MediaPipe LLM Inference task bundle entirely on device.
 *
 * The model is not shipped with the app. It is read from external files dir so it can be
 * copied across without root:
 *   /sdcard/Android/data/<package>/files/models/model.task
 */
class MediaPipeLlmEngine(
    private val context: Context,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS
) : LlmEngine {

    companion object {
        private const val TAG = "MediaPipeLlmEngine"
        private const val MODEL_DIR = "models"
        private const val MODEL_FILE = "model.task"
        // Input and output share this budget; /ai now sends conversation context as well
        private const val DEFAULT_MAX_TOKENS = 1024
    }

    // Loading the model costs seconds and hundreds of MB, so it is done once on first use.
    private var inference: LlmInference? = null
    // MediaPipe rejects overlapping generations on one instance, and closing the native handle
    // while a generation is in flight can crash the process. The mutex is held from the start of
    // a generation until the native side reports it finished, which after a cancellation can be
    // later than the moment complete() returns. close() defers to whoever holds it.
    private val mutex = Mutex()
    @Volatile
    private var closed = false

    override val modelPath: String
        get() {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(File(base, MODEL_DIR), MODEL_FILE).absolutePath
        }

    override fun isModelInstalled(): Boolean = File(modelPath).isFile

    override suspend fun complete(prompt: String): String {
        // Fail fast rather than queue behind a generation the caller has already given up on
        if (!mutex.tryLock()) throw LlmBusyException()
        var stillRunning: ListenableFuture<String>? = null
        try {
            check(!closed) { "engine is closed" }
            val engine = withContext(Dispatchers.IO) { inference ?: load().also { inference = it } }
            // Not cancellable, so a cancellation cannot drop the session without closing it;
            // a pending cancellation is picked up by await() below
            val session = withContext(NonCancellable + Dispatchers.IO) {
                LlmInferenceSession.createFromOptions(
                    engine,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder().build()
                )
            }
            val future = try {
                session.addQueryChunk(prompt)
                session.generateResponseAsync()
            } catch (e: Throwable) {
                closeQuietly(session)
                throw e
            }
            try {
                return future.await().trim()
            } catch (e: CancellationException) {
                // Timed out or stopped by the user: ask the native loop to stop, and hand the
                // lock back only once it has, so the next request does not collide with it.
                Log.d(TAG, "cancelling generation")
                try {
                    session.cancelGenerateResponseAsync()
                } catch (t: Throwable) {
                    Log.w(TAG, "cancel failed: ${t.message}")
                }
                stillRunning = future
                future.addListener({
                    closeQuietly(session)
                    release()
                }, Runnable::run)
                throw e
            } finally {
                if (stillRunning == null) closeQuietly(session)
            }
        } finally {
            if (stillRunning == null) release()
        }
    }

    private fun load(): LlmInference {
        val path = modelPath
        if (!File(path).isFile) throw IllegalStateException("no model at $path")

        Log.d(TAG, "loading model from $path")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(maxTokens)
            .build()
        return LlmInference.createFromOptions(context, options)
    }

    /** Called with the mutex held, once no generation is running. */
    private fun release() {
        if (closed) closeLocked()
        mutex.unlock()
    }

    private fun closeQuietly(session: LlmInferenceSession) {
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "error closing session: ${e.message}")
        }
    }

    override fun close() {
        closed = true
        // If a generation is in flight it holds the mutex; it will close the handle
        // when it releases it instead
        if (mutex.tryLock()) {
            try {
                closeLocked()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun closeLocked() {
        try {
            inference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "error closing inference: ${e.message}")
        }
        inference = null
    }

    private suspend fun ListenableFuture<String>.await(): String =
        suspendCancellableCoroutine { cont ->
            addListener({
                try {
                    cont.resume(get())
                } catch (e: ExecutionException) {
                    cont.resumeWithException(e.cause ?: e)
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                }
            }, Runnable::run)
        }
}
