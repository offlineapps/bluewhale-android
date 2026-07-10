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
    // MediaPipe rejects overlapping generateResponse calls on one instance.
    private val mutex = Mutex()

    override val modelPath: String
        get() {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(File(base, MODEL_DIR), MODEL_FILE).absolutePath
        }

    override fun isModelInstalled(): Boolean = File(modelPath).isFile

    override suspend fun complete(prompt: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val engine = inference ?: load().also { inference = it }
            engine.generateResponse(prompt).trim()
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
        inference?.close()
        inference = null
    }
}
