package com.bluewhale.android.ai

/**
 * Uses the [preferred] engine when its model is installed, and otherwise the [legacy] one.
 *
 * Lets `.litertlm` models (LiteRT-LM) replace `.task` bundles (MediaPipe, in maintenance mode)
 * without breaking installs that already have a `.task` file in place.
 */
class SelectingLlmEngine(
    private val preferred: LlmEngine,
    private val legacy: LlmEngine
) : LlmEngine {

    /** The installed model, or where a new one should go. */
    override val modelPath: String
        get() = if (!preferred.isModelInstalled() && legacy.isModelInstalled()) legacy.modelPath else preferred.modelPath

    override fun isModelInstalled(): Boolean = preferred.isModelInstalled() || legacy.isModelInstalled()

    internal fun active(): LlmEngine? = when {
        preferred.isModelInstalled() -> preferred
        legacy.isModelInstalled() -> legacy
        else -> null
    }

    override suspend fun complete(prompt: String): String {
        val engine = active() ?: throw IllegalStateException("no model at ${preferred.modelPath}")
        return engine.complete(prompt)
    }

    override fun close() {
        try {
            preferred.close()
        } finally {
            legacy.close()
        }
    }
}
