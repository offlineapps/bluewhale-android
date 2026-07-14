package com.bluewhale.android.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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
        private const val DEFAULT_MAX_TOKENS = 512
    }

    // Loading the model costs seconds and hundreds of MB, so it is done once on first use.
    private var inference: LlmInference? = null
    // MediaPipe rejects overlapping generateResponse calls on one instance, and closing
    // the native handle while a generation is in flight can crash the process. The mutex
    // serializes generation; close() defers to the generation holding it.
    private val mutex = Mutex()
    @Volatile
    private var closed = false

    override val modelPath: String
        get() {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(File(base, MODEL_DIR), MODEL_FILE).absolutePath
        }

    override fun isModelInstalled(): Boolean = File(modelPath).isFile

    override suspend fun complete(prompt: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "engine is closed" }
            val engine = inference ?: load().also { inference = it }
            try {
                engine.generateResponse(prompt).trim()
            } finally {
                if (closed) closeLocked()
            }
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

    override fun close() {
        closed = true
        // If a generation is in flight it holds the mutex; it will close the handle
        // in its finally block instead
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
}
