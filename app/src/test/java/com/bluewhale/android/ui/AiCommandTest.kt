package com.bluewhale.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.ai.LlmEngine
import com.bluewhale.android.mesh.BluetoothMeshService
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import com.bluewhale.android.ai.LlmBusyException
import com.bluewhale.android.model.BluewhaleMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AiCommandTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val chatState = ChatState(scope = testScope)
    private val messageManager = MessageManager(state = chatState)
    private val meshService: BluetoothMeshService = mock()

    /** Completes only when the test releases it, so the test can act mid-generation. */
    private class GatedLlmEngine : LlmEngine {
        var gate = CompletableDeferred<String>()
        var calls = 0
        var cancelled = 0
        val prompts = mutableListOf<String>()
        override val modelPath = "/data/models/model.task"
        override fun isModelInstalled() = true
        override suspend fun complete(prompt: String): String {
            calls++
            prompts.add(prompt)
            try {
                return gate.await()
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
        }
        override fun close() {}
    }

    private class FakeLlmEngine(
        private val installed: Boolean = true,
        private val reply: String = "hello from the model",
        private val failure: Exception? = null
    ) : LlmEngine {
        /** The full model input, including context. */
        var receivedPrompt: String? = null

        override val modelPath = "/data/models/model.task"
        override fun isModelInstalled() = installed
        override suspend fun complete(prompt: String): String {
            receivedPrompt = prompt
            failure?.let { throw it }
            return reply
        }
        override fun close() {}
    }

    private fun processorWith(engine: LlmEngine?): CommandProcessor = CommandProcessor(
        state = chatState,
        messageManager = messageManager,
        channelManager = ChannelManager(
            state = chatState,
            messageManager = messageManager,
            dataManager = DataManager(context = context),
            coroutineScope = testScope
        ),
        privateChatManager = PrivateChatManager(
            state = chatState,
            messageManager = messageManager,
            dataManager = DataManager(context = context),
            noiseSessionDelegate = mock<NoiseSessionDelegate>()
        ),
        llmEngine = engine,
        coroutineScope = testScope
    )

    /** Content passed to onSendMessage, i.e. what would actually go out over the mesh. */
    private val sent = mutableListOf<String>()

    private fun run(processor: CommandProcessor, command: String): Boolean =
        processor.processCommand(
            command = command,
            meshService = meshService,
            myPeerID = "peer-id",
            onSendMessage = { content, _, _ -> sent.add(content) },
            viewModel = null
        )

    private fun messageContents(): List<String> = chatState.getMessagesValue().map { it.content }

    /** The user's question is the last "user:" line of the model input. */
    private fun question(prompt: String?): String? =
        prompt?.lines()?.lastOrNull { it.startsWith("user: ") }?.removePrefix("user: ")

    private val usage = "usage: /ai <prompt> | /ai share | /ai stop | /ai reset"

    @Test
    fun `ai answer is shown locally and not sent to peers`() {
        val engine = FakeLlmEngine(reply = "42")
        val handled = run(processorWith(engine), "/ai what is six times seven")

        assertTrue(handled)
        assertEquals("what is six times seven", question(engine.receivedPrompt))
        assertEquals(emptyList<String>(), sent)
        assertTrue(messageContents().contains("ai: 42"))
        assertTrue(messageContents().contains(CommandProcessor.AI_PRIVATE_HINT))
    }

    @Test
    fun `ai answer is a local system message, not one under the user's nickname`() {
        run(processorWith(FakeLlmEngine(reply = "42")), "/ai hello")

        val answer = chatState.getMessagesValue().single { it.content == "ai: 42" }
        assertEquals("system", answer.sender)
    }

    @Test
    fun `ai share sends the last answer, marked as machine generated`() {
        val processor = processorWith(FakeLlmEngine(reply = "42"))
        run(processor, "/ai what is six times seven")
        run(processor, "/ai share")

        assertEquals(listOf("[ai] \"what is six times seven\": 42"), sent)
        assertTrue(messageContents().contains("[ai] \"what is six times seven\": 42"))
    }

    @Test
    fun `ai share with nothing to share sends nothing`() {
        run(processorWith(FakeLlmEngine()), "/ai share")

        assertEquals(emptyList<String>(), sent)
        assertTrue(messageContents().single().contains("nothing to share"))
    }

    @Test
    fun `ai share only shares answers from the conversation it is typed in`() {
        val processor = processorWith(FakeLlmEngine(reply = "secret"))
        chatState.setSelectedPrivateChatPeer("bob")
        run(processor, "/ai draft a reply to bob")

        chatState.setSelectedPrivateChatPeer(null)
        run(processor, "/ai share")

        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun `ai command without a prompt shows usage`() {
        val engine = FakeLlmEngine()
        run(processorWith(engine), "/ai")

        assertEquals(listOf(usage), messageContents())
        assertEquals(null, engine.receivedPrompt)
    }

    @Test
    fun `ai command with only whitespace shows usage`() {
        val engine = FakeLlmEngine()
        run(processorWith(engine), "/ai    ")

        assertEquals(listOf(usage), messageContents())
        assertEquals(null, engine.receivedPrompt)
    }

    @Test
    fun `ai command without an installed model explains how to install one`() {
        val engine = FakeLlmEngine(installed = false)
        run(processorWith(engine), "/ai hello")

        val message = messageContents().single()
        assertTrue(message.startsWith("no offline model installed"))
        assertTrue(message.contains("/data/models/model.task"))
        assertEquals(null, engine.receivedPrompt)
    }

    @Test
    fun `ai command without an engine explains how to install one`() {
        run(processorWith(null), "/ai hello")

        assertTrue(messageContents().single().startsWith("no offline model installed"))
    }

    @Test
    fun `ai command surfaces inference failures instead of crashing`() {
        val engine = FakeLlmEngine(failure = IllegalStateException("out of memory"))
        run(processorWith(engine), "/ai hello")

        assertEquals(listOf("ai: thinking…", "ai failed: out of memory"), messageContents())
    }

    @Test
    fun `inference failures are never sent to peers`() {
        val engine = FakeLlmEngine(failure = IllegalStateException("out of memory"))
        run(processorWith(engine), "/ai hello")

        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun `progress and usage messages are never sent to peers`() {
        run(processorWith(FakeLlmEngine(installed = false)), "/ai hello")
        run(processorWith(FakeLlmEngine()), "/ai")

        assertEquals(emptyList<String>(), sent)
        assertTrue(messageContents().any { it.startsWith("no offline model installed") })
    }

    @Test
    fun `empty responses are never sent to peers`() {
        run(processorWith(FakeLlmEngine(reply = "   ")), "/ai hello")

        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun `ai command reports an empty response`() {
        val engine = FakeLlmEngine(reply = "   ")
        run(processorWith(engine), "/ai hello")

        assertTrue(messageContents().contains("ai returned an empty response."))
    }

    @Test
    fun `ai command keeps multi word prompts intact`() {
        val engine = FakeLlmEngine()
        run(processorWith(engine), "/ai summarise the last message for me")

        assertEquals("summarise the last message for me", question(engine.receivedPrompt))
    }

    @Test
    fun `reply goes to the conversation the command was typed in, not the one open when it finishes`() {
        val engine = GatedLlmEngine()
        run(processorWith(engine), "/ai hello")

        // User opens a private chat with bob while the model is still thinking
        chatState.setSelectedPrivateChatPeer("bob")
        engine.gate.complete("late reply")

        assertTrue(messageContents().contains("ai: late reply"))
        val bobMessages = chatState.getPrivateChatsValue()["bob"].orEmpty().map { it.content }
        assertTrue(bobMessages.none { it.contains("late reply") })
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `failure lands in the conversation the command was typed in`() {
        val engine = GatedLlmEngine()
        run(processorWith(engine), "/ai hello")

        chatState.setSelectedPrivateChatPeer("bob")
        engine.gate.completeExceptionally(RuntimeException("boom"))

        assertTrue(messageContents().any { it.contains("ai failed: boom") })
        assertTrue(chatState.getPrivateChatsValue()["bob"].orEmpty().none { it.content.contains("boom") })
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `inference that never returns times out with a local message`() {
        val engine = GatedLlmEngine()
        run(processorWith(engine), "/ai hello")

        testScope.testScheduler.advanceTimeBy(181_000)
        testScope.testScheduler.runCurrent()

        assertTrue(messageContents().any { it.contains("ai timed out") })
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a timed out generation is cancelled instead of left running`() {
        val engine = GatedLlmEngine()
        run(processorWith(engine), "/ai hello")

        testScope.testScheduler.advanceTimeBy(181_000)
        testScope.testScheduler.runCurrent()

        assertEquals(1, engine.cancelled)
    }

    @Test
    fun `the next question runs after a timeout`() {
        val engine = GatedLlmEngine()
        val processor = processorWith(engine)
        run(processor, "/ai first")
        testScope.testScheduler.advanceTimeBy(181_000)
        testScope.testScheduler.runCurrent()

        engine.gate = CompletableDeferred()
        run(processor, "/ai second")
        engine.gate.complete("second answer")

        assertEquals(2, engine.calls)
        assertTrue(messageContents().contains("ai: second answer"))
    }

    @Test
    fun `a question asked while the model is busy is refused, not queued`() {
        val engine = GatedLlmEngine()
        val processor = processorWith(engine)
        run(processor, "/ai first")
        run(processor, "/ai second")

        assertEquals(1, engine.calls)
        assertTrue(messageContents().any { it.startsWith("ai is still answering") })
    }

    @Test
    fun `ai stop cancels the running question`() {
        val engine = GatedLlmEngine()
        val processor = processorWith(engine)
        run(processor, "/ai first")
        run(processor, "/ai stop")
        testScope.testScheduler.runCurrent()

        assertEquals(1, engine.cancelled)
        assertTrue(messageContents().contains("ai: stopped."))

        engine.gate = CompletableDeferred()
        run(processor, "/ai second")
        engine.gate.complete("ok")
        assertTrue(messageContents().contains("ai: ok"))
    }

    @Test
    fun `an engine still stopping an earlier request is reported, not shown as a failure`() {
        val engine = object : LlmEngine {
            override val modelPath = "/data/models/model.task"
            override fun isModelInstalled() = true
            override suspend fun complete(prompt: String): String = throw LlmBusyException()
            override fun close() {}
        }
        run(processorWith(engine), "/ai hello")

        assertTrue(messageContents().any { it.contains("still stopping the previous request") })
        assertTrue(messageContents().none { it.startsWith("ai failed") })
    }

    @Test
    fun `follow up questions carry the earlier turn`() {
        val engine = GatedLlmEngine()
        val processor = processorWith(engine)
        run(processor, "/ai what is a spring tide")
        engine.gate.complete("a tide at new and full moon")

        engine.gate = CompletableDeferred()
        run(processor, "/ai when is the next one")

        val second = engine.prompts.last()
        assertTrue(second.contains("user: what is a spring tide\nassistant: a tide at new and full moon"))
        assertEquals("when is the next one", question(second))
    }

    @Test
    fun `context is kept per conversation`() {
        val engine = GatedLlmEngine()
        val processor = processorWith(engine)
        run(processor, "/ai mesh question")
        engine.gate.complete("mesh answer")

        chatState.setSelectedPrivateChatPeer("bob")
        engine.gate = CompletableDeferred()
        run(processor, "/ai bob question")

        assertFalse(engine.prompts.last().contains("mesh question"))
    }

    @Test
    fun `ai reset forgets the conversation`() {
        val engine = GatedLlmEngine()
        val processor = processorWith(engine)
        run(processor, "/ai first")
        engine.gate.complete("one")
        run(processor, "/ai reset")

        engine.gate = CompletableDeferred()
        run(processor, "/ai second")

        assertFalse(engine.prompts.last().contains("first"))
        engine.gate.complete("two")
        run(processor, "/ai share")
        assertEquals(listOf("[ai] \"second\": two"), sent)
    }

    @Test
    fun `clearAiMemory forgets every conversation`() {
        val engine = GatedLlmEngine()
        val processor = processorWith(engine)
        run(processor, "/ai first")
        engine.gate.complete("one")

        processor.clearAiMemory()
        run(processor, "/ai share")

        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun `recent chat messages are given to the model, system lines are not`() {
        messageManager.addMessage(BluewhaleMessage(sender = "alice", content = "meet at the north gate at six", timestamp = java.util.Date(), isRelay = false))
        messageManager.addMessage(BluewhaleMessage(sender = "system", content = "alice joined", timestamp = java.util.Date(), isRelay = false))
        val engine = FakeLlmEngine()
        run(processorWith(engine), "/ai summarise this")

        val prompt = engine.receivedPrompt!!
        assertTrue(prompt.contains("alice: meet at the north gate at six"))
        assertFalse(prompt.contains("alice joined"))
    }

    @Test
    fun `ai suggestion is offered when typing the command prefix`() {
        processorWith(FakeLlmEngine()).updateCommandSuggestions("/a")

        val suggestions = chatState.getCommandSuggestionsValue().map { it.command }
        assertTrue(suggestions.contains("/ai"))
    }
}
