package io.github.iw.durableatlas.ir.testing

import io.github.iw.durableatlas.ir.IrDocument

/**
 * The convention for edges between a region and what lies inside it, which
 * [io.github.iw.durableatlas.ir.validate] leaves alone because documents written before the convention
 * can still be read.
 *
 * One kind of edge joins a region and a node inside it: from the node to the region, which may
 * be the node's own or one further out, for the way to the end of that region. An edge the other
 * way round, from a region to a node inside it, is against the convention: that a region begins
 * with its first children is implied. So is an edge that leads into a region from outside it to
 * anything but the region's own node.
 */
object RegionEdges {
    /** One message for every edge of [document] that is against the convention, in the order of its edges. */
    fun violations(document: IrDocument): List<String> {
        val parents = document.nodes.associate { it.id to it.parent }
        fun regionsAround(id: String): List<String> {
            val seen = LinkedHashSet<String>()
            var current = parents[id]
            while (current != null && seen.add(current)) current = parents[current]
            return seen.toList()
        }

        val problems = ArrayList<String>()
        // A handler wakes an await wherever the await is, and a cancel point cancels a step wherever
        // that is: neither edge is a way the flow takes.
        for (edge in document.edges.filter { it.kind.isFlow }) {
            val around = regionsAround(edge.from)
            val words = "edge '${edge.from}' -> '${edge.to}'"
            when {
                edge.from in regionsAround(edge.to) -> problems += "$words leads from a region to a node inside it"
                edge.to in around -> Unit
                // Only a way out leads from inside a region to a node that is not in the same region.
                parents[edge.to].let { it != null && it !in around } -> problems += "$words leads into a region from outside it"
            }
        }
        return problems
    }
}
