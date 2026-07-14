package com.bluewhale.android.mesh

import com.bluewhale.android.services.meshgraph.MeshGraphService

/**
 * Estimates whether a peer sits beyond the configured mesh range, so the sender can be
 * told instead of the message vanishing without a delivery receipt.
 *
 * Only warns when the mesh graph positively places the peer farther away than the range
 * reaches. A peer missing from the graph may be reachable over Nostr or by a route the
 * graph has not learned, so unknown never warns.
 */
object MeshRangeAdvisor {

    fun hopsBetween(from: String, to: String, snapshot: MeshGraphService.GraphSnapshot): Int? {
        if (from == to) return 0

        val adjacency = mutableMapOf<String, MutableList<String>>()
        snapshot.edges.forEach { edge ->
            adjacency.getOrPut(edge.a) { mutableListOf() }.add(edge.b)
            adjacency.getOrPut(edge.b) { mutableListOf() }.add(edge.a)
        }
        if (!adjacency.containsKey(from) || !adjacency.containsKey(to)) return null

        val visited = mutableSetOf(from)
        var frontier = listOf(from)
        var hops = 0
        while (frontier.isNotEmpty()) {
            hops++
            val next = mutableListOf<String>()
            for (node in frontier) {
                for (neighbor in adjacency[node].orEmpty()) {
                    if (neighbor == to) return hops
                    if (visited.add(neighbor)) next.add(neighbor)
                }
            }
            frontier = next
        }
        return null
    }

    /**
     * A local warning to show when sending a private message, or null when the range
     * setting cannot be the reason the message goes undelivered.
     */
    fun outOfRangeWarning(
        myPeerID: String,
        peerID: String,
        rangeHops: Int = MeshRangePreferenceManager.rangeHops.value,
        snapshot: MeshGraphService.GraphSnapshot = MeshGraphService.getInstance().graphState.value
    ): String? {
        if (rangeHops >= com.bluewhale.android.util.AppConstants.MAX_RANGE_HOPS) return null
        val hops = hopsBetween(myPeerID, peerID, snapshot) ?: return null
        if (hops <= rangeHops) return null
        return "your mesh range is set to $rangeHops hops but this peer appears to be $hops hops away. the message may not reach them."
    }
}
