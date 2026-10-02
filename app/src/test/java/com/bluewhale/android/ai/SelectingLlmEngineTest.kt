package com.bluewhale.android.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SelectingLlmEngineTest {

    private class FakeEngine(override val modelPath: String, var installed: Boolean) : LlmEngine {
        var calls = 0
        var closed = false
        override fun isModelInstalled() = installed
        override suspend fun complete(prompt: String): String {
            calls++
            return "from $modelPath"
        }
        override fun close() {
            closed = true
        }
    }

    private val litert = FakeEngine("/models/model.litertlm", installed = false)
    private val task = FakeEngine("/models/model.task", installed = false)
    private val engine = SelectingLlmEngine(preferred = litert, legacy = task)

    @Test
    fun `nothing installed points the user at the litertlm path`() {
        assertFalse(engine.isModelInstalled())
        assertEquals("/models/model.litertlm", engine.modelPath)
        assertNull(engine.active())
    }

    @Test
    fun `an existing task install keeps working`() = runBlocking {
        task.installed = true

        assertTrue(engine.isModelInstalled())
        assertEquals("/models/model.task", engine.modelPath)
        assertEquals("from /models/model.task", engine.complete("hi"))
        assertEquals(0, litert.calls)
    }

    @Test
    fun `a litertlm model wins over a task bundle`() = runBlocking {
        task.installed = true
        litert.installed = true

        assertSame(litert, engine.active())
        assertEquals("/models/model.litertlm", engine.modelPath)
        assertEquals("from /models/model.litertlm", engine.complete("hi"))
        assertEquals(0, task.calls)
    }

    @Test
    fun `complete without a model fails with the path`() = runBlocking {
        try {
            engine.complete("hi")
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("/models/model.litertlm"))
        }
    }

    @Test
    fun `close closes both engines`() {
        engine.close()

        assertTrue(litert.closed)
        assertTrue(task.closed)
    }
}
