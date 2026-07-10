package com.bluewhale.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.ai.LlmEngine
import com.bluewhale.android.mesh.BluetoothMeshService
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private class FakeLlmEngine(
        private val installed: Boolean = true,
        private val reply: String = "hello from the model",
        private val failure: Exception? = null
    ) : LlmEngine {
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

    @Test
    fun `ai command sends the reply to the conversation`() {
        val engine = FakeLlmEngine(reply = "42")
        val handled = run(processorWith(engine), "/ai what is six times seven")

        assertTrue(handled)
        assertEquals("what is six times seven", engine.receivedPrompt)
        assertEquals(listOf("[ai] \"what is six times seven\": 42"), sent)
    }

    @Test
    fun `sent reply is marked as machine generated`() {
        run(processorWith(FakeLlmEngine(reply = "42")), "/ai hello")

        assertTrue(sent.single().startsWith("[ai] "))
    }

    @Test
    fun `ai command echoes the reply locally as well as sending it`() {
        run(processorWith(FakeLlmEngine(reply = "42")), "/ai hello")

        assertTrue(messageContents().contains("[ai] \"hello\": 42"))
    }

    @Test
    fun `ai command without a prompt shows usage`() {
        val engine = FakeLlmEngine()
        run(processorWith(engine), "/ai")

        assertEquals(listOf("usage: /ai <prompt>"), messageContents())
        assertEquals(null, engine.receivedPrompt)
    }

    @Test
    fun `ai command with only whitespace shows usage`() {
        val engine = FakeLlmEngine()
        run(processorWith(engine), "/ai    ")

        assertEquals(listOf("usage: /ai <prompt>"), messageContents())
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

        assertEquals("summarise the last message for me", engine.receivedPrompt)
    }

    @Test
    fun `ai suggestion is offered when typing the command prefix`() {
        processorWith(FakeLlmEngine()).updateCommandSuggestions("/a")

        val suggestions = chatState.getCommandSuggestionsValue().map { it.command }
        assertTrue(suggestions.contains("/ai"))
    }
}
