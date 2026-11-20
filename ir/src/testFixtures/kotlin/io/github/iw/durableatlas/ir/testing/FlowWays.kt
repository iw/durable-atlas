package io.github.iw.durableatlas.ir.testing

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind

/**
 * The ways the flow of a document takes, with the ones its regions imply, read off the document
 * by the convention for regions alone.
 *
 * A place of the flow is a node, or one side of a region: where it is entered, and where it is
 * done. From a node the flow goes where its edges say; an edge to a region around it leads to
 * the end of that region, and a node inside a region whose edges do not say where it goes leads
 * there as well. The end of a loop is its beginning again, and the place where it is done; the
 * end of any other region is the place where it is done, unless nothing waits for the region.
 */
class FlowWays(document: IrDocument) {
    /** A place of the flow: the node [id], or for a region where it is entered ([done] false) and where it is done. */
    data class Place(val id: String, val done: Boolean = false)

    private val nodes: Map<String, IrNode> = document.nodes.associateBy { it.id }
    private val parents: Map<String, String?> = document.nodes.associate { it.id to it.parent }
    private val outgoing: Map<String, List<IrEdge>> = document.edges.filter { it.kind.isFlow }.groupBy { it.from }
    private val children: Map<String, List<String>> = document.nodes.filter { it.parent != null }.groupBy({ it.parent!! }, { it.id })

    /**
     * The children each region is entered at: those that nothing inside the region leads to. An
     * edge to a region around its source leads to the end of that region, not into it.
     */
    private val entries: Map<String, List<String>> = children.mapValues { (region, own) ->
        val led = document.edges
            .filter { it.kind.isFlow && region in around(it.from) && it.to !in around(it.from) }
            .map { it.to }
            .toSet()
        own.filter { it !in led }.sorted()
    }

    /** The regions around the node [id], innermost first. */
    fun around(id: String): List<String> {
        val seen = LinkedHashSet<String>()
        var current = parents[id]
        while (current != null && seen.add(current)) current = parents[current]
        return seen.toList()
    }

    /** The places a node, or a region, stands for. */
    fun places(id: String): List<Place> = if (nodes.getValue(id).kind.isContainer) listOf(Place(id), Place(id, done = true)) else listOf(Place(id))

    /** The places the flow goes to from [place]. */
    fun next(place: Place): List<Place> {
        val node = nodes.getValue(place.id)
        if (!node.kind.isContainer || place.done) return leave(place.id)
        val unawaited = node.kind == NodeKind.PARALLEL && node.waitsFor != null
        val inside = entries[place.id].orEmpty().map(::arrive)
        return if (unawaited || children[place.id].isNullOrEmpty()) inside + Place(place.id, done = true) else inside
    }

    /** The places reached from [starts], these included. A place that [stop] holds for is reached and not gone past. */
    fun reach(starts: Collection<Place>, stop: (Place) -> Boolean = { false }): Set<Place> {
        val seen = LinkedHashSet<Place>()
        val todo = ArrayDeque(starts)
        while (todo.isNotEmpty()) {
            val place = todo.removeLast()
            if (!seen.add(place) || stop(place)) continue
            todo += next(place)
        }
        return seen
    }

    private fun arrive(id: String): Place = Place(id)

    private fun endOf(region: String): List<Place> {
        val node = nodes.getValue(region)
        return when {
            node.kind == NodeKind.LOOP -> listOf(Place(region), Place(region, done = true))
            node.kind == NodeKind.PARALLEL && node.waitsFor != null -> emptyList()
            else -> listOf(Place(region, done = true))
        }
    }

    /** Where the flow goes from the node [id] or, for a region, once it is done. */
    private fun leave(id: String): List<Place> {
        val around = around(id)
        val own = parents[id]
        val places = ArrayList<Place>()
        var says = false
        for (edge in outgoing[id].orEmpty()) {
            if (edge.to in around) {
                places += endOf(edge.to)
                says = true
            } else {
                places += arrive(edge.to)
                if (parents[edge.to] == own || edge.kind == EdgeKind.FLOW) says = true
            }
        }
        if (own != null && !says && !nodes.getValue(id).kind.isTerminal) places += endOf(own)
        return places
    }
}
