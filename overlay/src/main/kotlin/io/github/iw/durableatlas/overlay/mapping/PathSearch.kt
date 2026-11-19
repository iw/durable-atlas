package io.github.iw.durableatlas.overlay.mapping

/**
 * What a search from one place found: every move it made, and which of the places looked for it
 * came to. The search passes nodes that leave no event and stops at those that do, so what it
 * reaches is what the flow can have come to next without the history showing anything between.
 */
internal class Exploration(private val arcs: List<Arc>, val reached: Set<Vertex>) {
    /** Every vertex the search came to, reached targets included. */
    val visited: Set<Vertex> get() = arcs.mapTo(LinkedHashSet()) { it.to }

    /** The moves that lie on some way from [from] to [target]. */
    fun towards(from: Vertex, target: Vertex): Set<Arc> {
        val into = arcs.groupBy { it.to }
        val kept = LinkedHashSet<Arc>()
        val seen = hashSetOf(target)
        val pending = ArrayDeque(listOf(target))
        while (pending.isNotEmpty()) {
            for (arc in into[pending.removeFirst()].orEmpty()) {
                if (kept.add(arc) && arc.from !== from && seen.add(arc.from)) pending.addLast(arc.from)
            }
        }
        // In the order the search made them, which is the order of the document.
        return arcs.filterTo(LinkedHashSet()) { it in kept }
    }

    companion object {
        /**
         * @param first the moves to begin with
         * @param targets where to stop and report
         * @param silent whether the flow can pass a vertex without an event
         * @param onward the moves that lead on from a vertex that is passed
         */
        fun run(first: List<Arc>, targets: Set<Vertex>, silent: (Vertex) -> Boolean, onward: (Vertex) -> List<Arc>): Exploration {
            val arcs = ArrayList<Arc>()
            val reached = LinkedHashSet<Vertex>()
            val seen = HashSet<Vertex>()
            val pending = ArrayDeque<Vertex>()
            fun follow(arc: Arc) {
                arcs += arc
                val to = arc.to
                when {
                    to in targets -> reached += to
                    silent(to) && seen.add(to) -> pending.addLast(to)
                }
            }
            first.forEach(::follow)
            while (pending.isNotEmpty()) onward(pending.removeFirst()).forEach(::follow)
            return Exploration(arcs, reached)
        }
    }
}

/**
 * The ways from one vertex to another, as a set of moves: usually one path, several where the
 * graph has more than one way that leaves no event.
 */
internal object Ways {
    /** Above this many moves the ways are not told apart, and nothing counts as certain. */
    private const val LIMIT = 400

    /** The moves and the vertices that every way from [from] to [target] takes. */
    fun certain(arcs: Set<Arc>, from: Vertex, target: Vertex): Pair<Set<Arc>, Set<Vertex>> {
        if (arcs.size > LIMIT) return emptySet<Arc>() to emptySet()
        if (arcs.size == 1) return arcs to emptySet()
        val sureArcs = arcs.filterTo(HashSet()) { arc -> !connects(arcs, from, target) { it !== arc } }
        val between = arcs.flatMapTo(LinkedHashSet()) { listOf(it.from, it.to) } - setOf(from, target)
        val sureVertices = between.filterTo(HashSet()) { vertex ->
            !connects(arcs, from, target) { it.from !== vertex && it.to !== vertex }
        }
        return sureArcs to sureVertices
    }

    /** One shortest way from [from] to [target], of at least one move. */
    fun shortest(arcs: Set<Arc>, from: Vertex, target: Vertex): List<Arc> {
        val out = arcs.groupBy { it.from }
        val cameBy = HashMap<Vertex, Arc>()
        val pending = ArrayDeque<Vertex>()
        for (arc in out[from].orEmpty()) {
            if (arc.to === target) return listOf(arc)
            if (arc.to !in cameBy) {
                cameBy[arc.to] = arc
                pending.addLast(arc.to)
            }
        }
        while (pending.isNotEmpty()) {
            val vertex = pending.removeFirst()
            for (arc in out[vertex].orEmpty()) {
                if (arc.to === target) {
                    val path = ArrayDeque(listOf(arc))
                    var current = vertex
                    while (true) {
                        val step = cameBy.getValue(current)
                        path.addFirst(step)
                        if (step.from === from) return path
                        current = step.from
                    }
                }
                if (arc.to !== from && arc.to !in cameBy) {
                    cameBy[arc.to] = arc
                    pending.addLast(arc.to)
                }
            }
        }
        return emptyList()
    }

    private fun connects(arcs: Set<Arc>, from: Vertex, target: Vertex, allowed: (Arc) -> Boolean): Boolean {
        val out = arcs.filter(allowed).groupBy { it.from }
        val seen = HashSet<Vertex>()
        val pending = ArrayDeque<Vertex>()
        for (arc in out[from].orEmpty()) {
            if (arc.to === target) return true
            if (seen.add(arc.to)) pending.addLast(arc.to)
        }
        while (pending.isNotEmpty()) {
            for (arc in out[pending.removeFirst()].orEmpty()) {
                if (arc.to === target) return true
                if (arc.to !== from && seen.add(arc.to)) pending.addLast(arc.to)
            }
        }
        return false
    }
}
