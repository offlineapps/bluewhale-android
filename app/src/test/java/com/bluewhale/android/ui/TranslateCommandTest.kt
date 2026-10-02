package com.bluewhale.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bluewhale.android.ai.LlmEngine
import com.bluewhale.android.mesh.BluetoothMeshService
import com.bluewhale.android.model.BluewhaleMessage
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TranslateCommandTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val chatState = ChatState(scope = testScope)
    private val messageManager = MessageManager(state = chatState)
    private val meshService: BluetoothMeshService = mock()
    private val myPeerID = "me-peer"

    private class RecordingEngine(var reply: String = "where do we meet?") : LlmEngine {
        val prompts = mutableListOf<String>()
        var gate: CompletableDeferred<String>? = null
        override val modelPath = "/data/models/model.litertlm"
        override fun isModelInstalled() = true
        override suspend fun complete(prompt: String): String {
            prompts.add(prompt)
            return gate?.await() ?: reply
        }
        override fun close() {}
    }

    private val engine = RecordingEngine()
    private val processor = CommandProcessor(
        state = chatState,
        messageManager = messageManager,
        channelManager = ChannelManager(chatState, messageManager, DataManager(context), testScope),
        privateChatManager = PrivateChatManager(chatState, messageManager, DataManager(context), mock<NoiseSessionDelegate>()),
        llmEngine = engine,
        coroutineScope = testScope
    )
    private val sent = mutableListOf<String>()

    private fun run(command: String) = processor.processCommand(
        command, meshService, myPeerID, { content, _, _ -> sent.add(content) }, null
    )

    private fun incoming(sender: String, text: String, peer: String = "peer-$sender") = BluewhaleMessage(
        sender = sender, content = text, timestamp = Date(), senderPeerID = peer
    )

    private fun contents() = chatState.getMessagesValue().map { it.content }

    @Test
    fun `tr translates the newest message from someone else`() {
        messageManager.addMessage(incoming("alice", "bonjour"))
        messageManager.addMessage(incoming("bob", "¿dónde nos vemos?"))
        messageManager.addMessage(BluewhaleMessage(sender = "me", content = "my own text", timestamp = Date(), senderPeerID = myPeerID))

        run("/tr english")

        assertTrue(engine.prompts.single().contains("¿dónde nos vemos?"))
        assertTrue(contents().contains("bob (english): where do we meet?"))
    }

    @Test
    fun `translation is never sent to peers`() {
        messageManager.addMessage(incoming("bob", "hola"))
        run("/tr english")

        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun `the chosen language is remembered`() {
        messageManager.addMessage(incoming("bob", "hola"))
        run("/tr german")
        run("/tr")

        assertTrue(engine.prompts.all { it.contains("into german") })
    }

    @Test
    fun `tr with nothing to translate says so`() {
        messageManager.addMessage(BluewhaleMessage(sender = "system", content = "alice joined", timestamp = Date()))
        run("/tr")

        assertTrue(engine.prompts.isEmpty())
        assertTrue(contents().any { it.contains("no message from someone else") })
    }

    @Test
    fun `long press translation of a specific message`() {
        val older = incoming("alice", "guten morgen")
        messageManager.addMessage(older)
        messageManager.addMessage(incoming("bob", "hola"))

        processor.translateMessage(older)

        assertTrue(engine.prompts.single().contains("guten morgen"))
        assertTrue(contents().any { it.startsWith("alice (") && it.endsWith("): where do we meet?") })
    }

    @Test
    fun `model labels around the translation are removed`() {
        engine.reply = "Translation: \"good morning\""
        messageManager.addMessage(incoming("alice", "guten morgen"))
        run("/tr english")

        assertTrue(contents().contains("alice (english): good morning"))
    }

    @Test
    fun `translation is refused while an ai answer is running`() {
        engine.gate = CompletableDeferred()
        messageManager.addMessage(incoming("bob", "hola"))
        run("/ai what time is it")
        run("/tr english")

        assertEquals(1, engine.prompts.size)
        assertTrue(contents().any { it.startsWith("ai is still answering") })
    }

    @Test
    fun `ai stop cancels a running translation`() {
        engine.gate = CompletableDeferred()
        messageManager.addMessage(incoming("bob", "hola"))
        run("/tr english")
        run("/ai stop")
        testScope.testScheduler.runCurrent()

        assertTrue(contents().contains("ai: stopped."))
        engine.gate!!.complete("too late")
        assertTrue(contents().none { it.contains("too late") })
    }

    @Test
    fun `tr suggestion is offered`() {
        processor.updateCommandSuggestions("/t")

        assertTrue(chatState.getCommandSuggestionsValue().any { it.command == "/tr" })
    }
}
