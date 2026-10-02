package com.bluewhale.android.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs a LiteRT-LM model (`.litertlm`) entirely on device. LiteRT-LM is the successor to the
 * MediaPipe LLM Inference API, which Google has put in maintenance mode.
 *
 * Like [MediaPipeLlmEngine], the model is not shipped with the app:
 *   /sdcard/Android/data/<package>/files/models/model.litertlm
 */
class LiteRtLmEngine(
    private val context: Context,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS
) : LlmEngine {

    companion object {
        private const val TAG = "LiteRtLmEngine"
        private const val MODEL_DIR = "models"
        const val MODEL_FILE = "model.litertlm"
        // Input and output share this budget; matches the prompt budget of AiConversationMemory
        private const val DEFAULT_MAX_TOKENS = 1024
    }

    // Loading costs seconds and hundreds of MB, so it is done once on first use.
    private var engine: Engine? = null
    // Held from the start of a generation until the native side reports it finished, which after
    // a cancellation can be later than the moment complete() returns. close() defers to its holder.
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
        if (!mutex.tryLock()) throw LlmBusyException()
        var stillRunning = false
        try {
            check(!closed) { "engine is closed" }
            val loaded = withContext(Dispatchers.IO) { engine ?: load().also { engine = it } }
            // Not cancellable, so a cancellation cannot drop the conversation without closing it
            val conversation = withContext(NonCancellable + Dispatchers.IO) { loaded.createConversation() }

            val reply = StringBuilder()
            val result = CompletableDeferred<String>()
            // Completes on any outcome, once the native side has stopped
            val finished = CompletableDeferred<Unit>()
            try {
                conversation.sendMessageAsync(prompt, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        synchronized(reply) { reply.append(message.toString()) }
                    }

                    override fun onDone() {
                        result.complete(synchronized(reply) { reply.toString() })
                        finished.complete(Unit)
                    }

                    override fun onError(throwable: Throwable) {
                        // A native cancellation must not look like the caller being cancelled
                        result.completeExceptionally(
                            if (throwable is CancellationException) IllegalStateException("generation was cancelled", throwable)
                            else throwable
                        )
                        finished.complete(Unit)
                    }
                })
            } catch (e: Throwable) {
                closeQuietly(conversation)
                throw e
            }

            try {
                return result.await().trim()
            } catch (e: CancellationException) {
                // Timed out or stopped by the user: stop the native loop, and hand the lock
                // back only once it has stopped
                Log.d(TAG, "cancelling generation")
                try {
                    conversation.cancelProcess()
                } catch (t: Throwable) {
                    Log.w(TAG, "cancel failed: ${t.message}")
                }
                stillRunning = true
                finished.invokeOnCompletion {
                    closeQuietly(conversation)
                    release()
                }
                throw e
            } finally {
                if (!stillRunning) closeQuietly(conversation)
            }
        } finally {
            if (!stillRunning) release()
        }
    }

    private fun load(): Engine {
        val path = modelPath
        if (!File(path).isFile) throw IllegalStateException("no model at $path")

        Log.d(TAG, "loading model from $path")
        return try {
            Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = maxTokens))
                .also { it.initialize() }
        } catch (e: LinkageError) {
            // LiteRT-LM ships native code for arm64-v8a and x86_64 only
            throw IllegalStateException("LiteRT-LM is not supported on this device (${e.message})", e)
        } catch (e: ExceptionInInitializerError) {
            throw IllegalStateException("LiteRT-LM failed to start (${e.cause?.message ?: e.message})", e)
        }
    }

    /** Called with the mutex held, once no generation is running. */
    private fun release() {
        if (closed) closeLocked()
        mutex.unlock()
    }

    private fun closeQuietly(conversation: Conversation) {
        try {
            conversation.close()
        } catch (e: Exception) {
            Log.w(TAG, "error closing conversation: ${e.message}")
        }
    }

    override fun close() {
        closed = true
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
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "error closing engine: ${e.message}")
        }
        engine = null
    }
}
