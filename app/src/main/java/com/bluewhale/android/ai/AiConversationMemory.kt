package com.bluewhale.android.ai

/**
 * Per-conversation context for /ai, kept on this device only.
 *
 * Small on-device models have a shared input+output token budget (about 1k tokens), so the
 * prompt is built newest-first and stops adding older material once [maxPromptChars] is used.
 */
class AiConversationMemory(
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
    private val maxPromptChars: Int = DEFAULT_MAX_PROMPT_CHARS,
    private val maxChatLineChars: Int = DEFAULT_MAX_CHAT_LINE_CHARS
) {

    companion object {
        const val DEFAULT_MAX_TURNS = 6
        // Roughly 400-500 tokens, leaving the rest of a 1024 token window for the answer
        const val DEFAULT_MAX_PROMPT_CHARS = 1_800
        const val DEFAULT_MAX_CHAT_LINE_CHARS = 240
        const val MAX_CHAT_LINES = 10

        internal const val PREAMBLE =
            "You are a helpful assistant running offline inside a chat app. Answer briefly and plainly."
    }

    data class Turn(val prompt: String, val reply: String)

    /** A line from the chat the question was asked in, oldest first. */
    data class ChatLine(val sender: String, val text: String)

    private val turns = mutableMapOf<String, ArrayDeque<Turn>>()

    @Synchronized
    fun record(conversationKey: String, prompt: String, reply: String) {
        val history = turns.getOrPut(conversationKey) { ArrayDeque() }
        history.addLast(Turn(prompt, reply))
        while (history.size > maxTurns) history.removeFirst()
    }

    @Synchronized
    fun lastTurn(conversationKey: String): Turn? = turns[conversationKey]?.lastOrNull()

    @Synchronized
    fun history(conversationKey: String): List<Turn> = turns[conversationKey]?.toList().orEmpty()

    @Synchronized
    fun clear(conversationKey: String) {
        turns.remove(conversationKey)
    }

    @Synchronized
    fun clearAll() {
        turns.clear()
    }

    /**
     * Builds the model input: preamble, recent chat lines, earlier /ai turns, then the question.
     * The question is always included; older chat lines and turns are dropped first when over budget.
     */
    @Synchronized
    fun buildPrompt(conversationKey: String, question: String, recentChat: List<ChatLine> = emptyList()): String {
        val tail = "user: $question\nassistant:"
        // The preamble is followed by a blank line
        var budget = maxPromptChars - PREAMBLE.length - 2 - tail.length

        // Earlier turns matter more than background chat, so they get the budget first
        val turnBlocks = ArrayDeque<String>()
        for (turn in history(conversationKey).asReversed()) {
            val block = "user: ${turn.prompt}\nassistant: ${turn.reply}\n"
            if (block.length > budget) break
            turnBlocks.addFirst(block)
            budget -= block.length
        }

        val chatBlock = ArrayDeque<String>()
        val chatHeader = "Recent messages in this chat:\n"
        // The chat block is followed by a blank line
        if (recentChat.isNotEmpty() && budget > chatHeader.length + 1) {
            budget -= chatHeader.length + 1
            for (line in recentChat.takeLast(MAX_CHAT_LINES).asReversed()) {
                val text = line.text.replace('\n', ' ').let {
                    if (it.length > maxChatLineChars) it.take(maxChatLineChars) + "…" else it
                }
                val entry = "${line.sender}: $text\n"
                if (entry.length > budget) break
                chatBlock.addFirst(entry)
                budget -= entry.length
            }
        }

        return buildString {
            append(PREAMBLE).append("\n\n")
            if (chatBlock.isNotEmpty()) {
                append(chatHeader)
                chatBlock.forEach { append(it) }
                append('\n')
            }
            turnBlocks.forEach { append(it) }
            append(tail)
        }
    }
}
