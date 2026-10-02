package com.bluewhale.android.ai

/**
 * Offline text generation backed by a model file on the device.
 */
interface LlmEngine {

    /** Absolute path the model file is expected at. Shown to the user when it is missing. */
    val modelPath: String

    fun isModelInstalled(): Boolean

    /**
     * Runs inference. Throws if the model is missing or generation fails.
     *
     * Cancelling the calling coroutine must stop the generation, so a timed-out or abandoned
     * request does not keep the model busy. Throws [LlmBusyException] instead of queueing when
     * another generation still holds the model.
     */
    suspend fun complete(prompt: String): String

    fun close()
}

/** The model is still running (or still stopping) an earlier generation. */
class LlmBusyException : IllegalStateException("the model is still busy with an earlier request")
