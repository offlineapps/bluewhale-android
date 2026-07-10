package com.bluewhale.android.ai

/**
 * Offline text generation backed by a model file on the device.
 */
interface LlmEngine {

    /** Absolute path the model file is expected at. Shown to the user when it is missing. */
    val modelPath: String

    fun isModelInstalled(): Boolean

    /** Runs inference. Throws if the model is missing or generation fails. */
    suspend fun complete(prompt: String): String

    fun close()
}
