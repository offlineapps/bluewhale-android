package com.bluewhale.android.mesh

import com.bluewhale.android.services.meshgraph.MeshGraphService.GraphEdge
import com.bluewhale.android.services.meshgraph.MeshGraphService.GraphNode
import com.bluewhale.android.services.meshgraph.MeshGraphService.GraphSnapshot
import com.bluewhale.android.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MeshRangeAdvisorTest {

    private fun snapshot(vararg edges: Pair<String, String>): GraphSnapshot {
        val nodes = edges.flatMap { listOf(it.first, it.second) }.distinct().map { GraphNode(it, null) }
        return GraphSnapshot(nodes, edges.map { GraphEdge(it.first, it.second, isConfirmed = true) })
    }

    // a - b - c - d
    private val chain = snapshot("a" to "b", "b" to "c", "c" to "d")

    @Test
    fun `counts hops along a chain`() {
        assertEquals(1, MeshRangeAdvisor.hopsBetween("a", "b", chain))
        assertEquals(2, MeshRangeAdvisor.hopsBetween("a", "c", chain))
        assertEquals(3, MeshRangeAdvisor.hopsBetween("a", "d", chain))
        assertEquals(0, MeshRangeAdvisor.hopsBetween("a", "a", chain))
    }

    @Test
    fun `takes the shortest path when several exist`() {
        // a - b - c - d plus a direct a - d shortcut
        val looped = snapshot("a" to "b", "b" to "c", "c" to "d", "a" to "d")
        assertEquals(1, MeshRangeAdvisor.hopsBetween("a", "d", looped))
    }

    @Test
    fun `unknown peers have no distance`() {
        assertNull(MeshRangeAdvisor.hopsBetween("a", "nobody", chain))
        assertNull(MeshRangeAdvisor.hopsBetween("a", "b", snapshot("x" to "y")))
    }

    @Test
    fun `warns when the peer is positively beyond range`() {
        assertNotNull(MeshRangeAdvisor.outOfRangeWarning("a", "d", rangeHops = 2, snapshot = chain))
    }

    @Test
    fun `does not warn when the peer is within range`() {
        assertNull(MeshRangeAdvisor.outOfRangeWarning("a", "c", rangeHops = 2, snapshot = chain))
        assertNull(MeshRangeAdvisor.outOfRangeWarning("a", "b", rangeHops = 1, snapshot = chain))
    }

    @Test
    fun `does not warn about peers the graph does not know`() {
        // Might be reachable over nostr or an unlearned route; silence beats a false alarm
        assertNull(MeshRangeAdvisor.outOfRangeWarning("a", "stranger", rangeHops = 1, snapshot = chain))
    }

    @Test
    fun `never warns at the default full range`() {
        assertNull(
            MeshRangeAdvisor.outOfRangeWarning(
                "a", "d",
                rangeHops = AppConstants.MAX_RANGE_HOPS,
                snapshot = chain
            )
        )
    }
}
