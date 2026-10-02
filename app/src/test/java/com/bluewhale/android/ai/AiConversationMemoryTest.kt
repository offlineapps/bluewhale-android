package com.bluewhale.android.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConversationMemoryTest {

    private fun line(sender: String, text: String) = AiConversationMemory.ChatLine(sender, text)

    @Test
    fun `prompt without history ends with the question`() {
        val prompt = AiConversationMemory().buildPrompt("mesh", "hello")

        assertTrue(prompt.startsWith(AiConversationMemory.PREAMBLE))
        assertTrue(prompt.endsWith("user: hello\nassistant:"))
    }

    @Test
    fun `earlier turns appear oldest first before the question`() {
        val memory = AiConversationMemory()
        memory.record("mesh", "q1", "a1")
        memory.record("mesh", "q2", "a2")

        val prompt = memory.buildPrompt("mesh", "q3")

        val i1 = prompt.indexOf("user: q1\nassistant: a1")
        val i2 = prompt.indexOf("user: q2\nassistant: a2")
        val i3 = prompt.indexOf("user: q3\nassistant:")
        assertTrue(i1 in 0 until i2)
        assertTrue(i2 < i3)
    }

    @Test
    fun `only the most recent turns are kept`() {
        val memory = AiConversationMemory(maxTurns = 2)
        memory.record("mesh", "q1", "a1")
        memory.record("mesh", "q2", "a2")
        memory.record("mesh", "q3", "a3")

        assertEquals(listOf("q2", "q3"), memory.history("mesh").map { it.prompt })
    }

    @Test
    fun `conversations do not share context`() {
        val memory = AiConversationMemory()
        memory.record("pm:bob", "about bob", "x")

        assertFalse(memory.buildPrompt("mesh", "hi").contains("about bob"))
        assertNull(memory.lastTurn("mesh"))
    }

    @Test
    fun `prompt stays within budget by dropping the oldest material`() {
        val memory = AiConversationMemory(maxPromptChars = 400)
        repeat(6) { memory.record("mesh", "question $it " + "x".repeat(40), "answer $it " + "y".repeat(40)) }
        val chat = (0 until 10).map { line("alice", "message $it " + "z".repeat(60)) }

        val prompt = memory.buildPrompt("mesh", "latest", chat)

        assertTrue("was ${prompt.length}", prompt.length <= 400)
        assertTrue(prompt.endsWith("user: latest\nassistant:"))
        assertTrue("newest turn is kept", prompt.contains("question 5"))
        assertFalse("oldest turn is dropped", prompt.contains("question 0"))
    }

    @Test
    fun `chat context never pushes the prompt over budget`() {
        val chat = (0 until 10).map { line("alice", "message $it " + "z".repeat(60)) }
        for (budget in 150..600 step 7) {
            val prompt = AiConversationMemory(maxPromptChars = budget).buildPrompt("mesh", "q", chat)
            assertTrue("budget $budget, was ${prompt.length}", prompt.length <= budget)
        }
    }

    @Test
    fun `question is kept even when it alone exceeds the budget`() {
        val question = "q".repeat(500)
        val prompt = AiConversationMemory(maxPromptChars = 300).buildPrompt("mesh", question)

        assertTrue(prompt.endsWith("user: $question\nassistant:"))
    }

    @Test
    fun `recent chat lines keep their order and long ones are cut`() {
        val memory = AiConversationMemory(maxChatLineChars = 20)
        val prompt = memory.buildPrompt(
            "mesh",
            "summarise",
            listOf(line("alice", "first"), line("bob", "second line\nwith a newline that goes on and on"))
        )

        val a = prompt.indexOf("alice: first")
        val b = prompt.indexOf("bob: second line with a n…\n")
        assertTrue(a >= 0)
        assertTrue(b > a)
        assertTrue(b < prompt.indexOf("user: summarise"))
    }

    @Test
    fun `at most the last ten chat lines are used`() {
        val chat = (0 until 15).map { line("alice", "m$it") }
        val prompt = AiConversationMemory().buildPrompt("mesh", "q", chat)

        assertFalse(prompt.contains("alice: m4\n"))
        assertTrue(prompt.contains("alice: m5\n"))
        assertTrue(prompt.contains("alice: m14\n"))
    }

    @Test
    fun `clear and clearAll forget turns`() {
        val memory = AiConversationMemory()
        memory.record("a", "q", "r")
        memory.record("b", "q", "r")

        memory.clear("a")
        assertNull(memory.lastTurn("a"))
        assertEquals("r", memory.lastTurn("b")?.reply)

        memory.clearAll()
        assertNull(memory.lastTurn("b"))
    }
}
