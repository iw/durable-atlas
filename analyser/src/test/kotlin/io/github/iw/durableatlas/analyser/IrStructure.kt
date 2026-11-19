package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.IrStats
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.Join
import io.github.iw.durableatlas.ir.ParentClosePolicy
import io.github.iw.durableatlas.ir.WaitsFor

/**
 * The structure of an IR document without its node ids, for comparing documents whose ids were
 * made in different ways: one written by hand with one made by the analyser.
 *
 * A node is known by what it is and where it comes from: its kind and its line in the source,
 * as in `activity@74`, or its kind and the entry point it belongs to if it has no place, as in
 * `end(main)`. Nodes that share all of that are numbered. Everything else about a node (name,
 * label, duration, the region it is in) and every edge is then compared between the nodes known
 * by the same handle.
 */
class IrStructure private constructor(private val document: IrDocument) {
    /** The handle of every node, by node id. */
    val handles: Map<String, String>

    /** The entry point each node belongs to, by node id; absent for a node that none reaches. */
    val owners: Map<String, String>

    init {
        owners = ownersOf(document)
        handles = handlesOf(document, owners)
    }

    /** What one document has and the other has not, or has differently. */
    data class Difference(
        val kind: Kind,
        /** The entry point whose graph the difference is in; `null` if it is in none. */
        val entryPoint: String?,
        /** The handle of the node, the edge in words, or the id of the entry point. */
        val subject: String,
        /** For [Kind.NODE_DIFFERS] and [Kind.ENTRY_POINT_DIFFERS]: the property that differs. */
        val property: String? = null,
        val expected: String? = null,
        val actual: String? = null,
    ) {
        enum class Kind {
            NODE_MISSING,
            NODE_UNEXPECTED,
            NODE_DIFFERS,
            EDGE_MISSING,
            EDGE_UNEXPECTED,
            ENTRY_POINT_MISSING,
            ENTRY_POINT_UNEXPECTED,
            ENTRY_POINT_DIFFERS,
        }

        override fun toString(): String {
            val where = entryPoint?.let { "[$it] " }.orEmpty()
            return when (kind) {
                Kind.NODE_MISSING -> "${where}missing node $subject: $expected"
                Kind.NODE_UNEXPECTED -> "${where}unexpected node $subject: $actual"
                Kind.NODE_DIFFERS -> "${where}node $subject: $property is $actual, expected $expected"
                Kind.EDGE_MISSING -> "${where}missing edge $subject"
                Kind.EDGE_UNEXPECTED -> "${where}unexpected edge $subject"
                Kind.ENTRY_POINT_MISSING -> "missing entry point $subject: $expected"
                Kind.ENTRY_POINT_UNEXPECTED -> "unexpected entry point $subject: $actual"
                Kind.ENTRY_POINT_DIFFERS -> "entry point $subject: $property is $actual, expected $expected"
            }
        }
    }

    /** The document as lines of text without ids: its entry points, nodes and edges, sorted. */
    fun describe(): List<String> =
        entryPoints().map { (id, properties) -> "entry point $id: ${words(properties)}" } +
            nodes().map { (handle, properties) -> "node $handle: ${words(properties)}".trimEnd(':', ' ') } +
            edges().keys.map { "edge $it" }

    private fun entryPoints(): Map<String, Map<String, String>> =
        document.entryPoints.sortedBy { it.id }.associate { entryPoint ->
            entryPoint.id to linkedMapOf(
                "kind" to entryPoint.kind.name,
                "name" to entryPoint.name,
                "start" to handle(entryPoint.start),
                "reads" to entryPoint.reads.sorted().joinToString(", "),
                "writes" to entryPoint.writes.sorted().joinToString(", "),
            ).filterValues { it.isNotEmpty() }
        }

    /** What is compared of each node, by its handle; properties that are not set are left out. */
    private fun nodes(): Map<String, Map<String, String>> =
        document.nodes.associate { node ->
            handle(node.id) to linkedMapOf(
                "name" to node.name,
                "interface" to node.interfaceName,
                "service" to node.service,
                "taskQueue" to node.taskQueue,
                "duration" to node.duration,
                "timeout" to node.timeout,
                "waitsFor" to node.waitsFor?.let { WaitsFor.serializer().descriptor.getElementName(it.ordinal) },
                "join" to node.join?.let { Join.serializer().descriptor.getElementName(it.ordinal) },
                "parentClosePolicy" to node.parentClosePolicy?.let { ParentClosePolicy.serializer().descriptor.getElementName(it.ordinal) },
                "reads" to node.reads.sorted().joinToString(", "),
                "keys" to node.keys.joinToString(", "),
                "otherKeys" to "true".takeIf { node.otherKeys },
                "changeId" to node.changeId,
                "label" to node.label,
                "retry" to "true".takeIf { node.retry },
                "reason" to node.reason,
                "candidates" to node.candidates.sorted().joinToString(", "),
                "parent" to node.parent?.let(::handle),
            ).filterValues { !it.isNullOrEmpty() }.mapValues { it.value.orEmpty() }
        }.toSortedMap()

    /** Every edge in words, with the entry point it belongs to. */
    private fun edges(): Map<String, String?> =
        document.edges.associate { edge ->
            // An edge that wakes an await belongs to the handler it starts at.
            // And one that cancels a step belongs to the graph of the place that cancels.
            val owner = when (edge.kind) {
                EdgeKind.WAKES -> owners[edge.from] ?: edge.from
                EdgeKind.CANCELS -> owners[edge.from]
                else -> owners[edge.to] ?: owners[edge.from]
            }
            words(edge) to owner
        }.toSortedMap()

    private fun words(edge: IrEdge): String {
        val arrow = when (edge.kind) {
            EdgeKind.FLOW -> "->"
            else -> "-(${edge.kind.name.lowercase()})->"
        }
        return "${handle(edge.from)} $arrow ${handle(edge.to)}" + edge.label?.let { " [$it]" }.orEmpty()
    }

    private fun words(properties: Map<String, String>): String = properties.entries.joinToString(", ") { "${it.key} ${it.value}" }

    private fun handle(id: String): String = handles[id] ?: "entry point $id"

    companion object {
        fun of(document: IrDocument): IrStructure = IrStructure(document)

        /**
         * What [actual] has differently from [expected], ids apart. Every difference is one
         * line: a node or an edge only one of them has, or a property of a node both have.
         */
        fun differences(expected: IrDocument, actual: IrDocument): List<Difference> {
            val wanted = of(expected)
            val found = of(actual)
            val differences = ArrayList<Difference>()

            val wantedEntryPoints = wanted.entryPoints()
            val foundEntryPoints = found.entryPoints()
            for (id in (wantedEntryPoints.keys + foundEntryPoints.keys).sorted()) {
                val want = wantedEntryPoints[id]
                val have = foundEntryPoints[id]
                when {
                    have == null -> differences += Difference(Difference.Kind.ENTRY_POINT_MISSING, id, id, expected = wanted.words(want!!))
                    want == null -> differences += Difference(Difference.Kind.ENTRY_POINT_UNEXPECTED, id, id, actual = found.words(have))
                    else -> for (property in (want.keys + have.keys).distinct()) {
                        if (want[property] != have[property]) {
                            differences += Difference(Difference.Kind.ENTRY_POINT_DIFFERS, id, id, property, want[property], have[property])
                        }
                    }
                }
            }

            val wantedOwners = wanted.document.nodes.associate { wanted.handle(it.id) to wanted.owners[it.id] }
            val foundOwners = found.document.nodes.associate { found.handle(it.id) to found.owners[it.id] }
            val wantedNodes = wanted.nodes()
            val foundNodes = found.nodes()
            for (handle in (wantedNodes.keys + foundNodes.keys).sorted()) {
                val want = wantedNodes[handle]
                val have = foundNodes[handle]
                val owner = wantedOwners[handle] ?: foundOwners[handle]
                when {
                    have == null -> differences += Difference(Difference.Kind.NODE_MISSING, owner, handle, expected = wanted.words(want!!))
                    want == null -> differences += Difference(Difference.Kind.NODE_UNEXPECTED, owner, handle, actual = found.words(have))
                    else -> for (property in (want.keys + have.keys).distinct()) {
                        if (want[property] != have[property]) {
                            differences += Difference(Difference.Kind.NODE_DIFFERS, owner, handle, property, want[property], have[property])
                        }
                    }
                }
            }

            val wantedEdges = wanted.edges()
            val foundEdges = found.edges()
            for (edge in (wantedEdges.keys + foundEdges.keys).sorted()) {
                when {
                    edge !in foundEdges -> differences += Difference(Difference.Kind.EDGE_MISSING, wantedEdges[edge], edge)
                    edge !in wantedEdges -> differences += Difference(Difference.Kind.EDGE_UNEXPECTED, foundEdges[edge], edge)
                }
            }
            return differences
        }

        /**
         * The document without the nodes [removed], as if they had never been steps: what led to
         * a removed node leads to what the node led to, on the edge that led to the node.
         */
        fun without(document: IrDocument, removed: Set<String>): IrDocument {
            var edges = document.edges
            for (id in removed) {
                val into = edges.filter { it.to == id && it.from != id }
                val out = edges.filter { it.from == id && it.to != id }
                val bridged = into.flatMap { incoming -> out.map { incoming.copy(to = it.to) } }
                edges = (edges.filter { it.from != id && it.to != id } + bridged).distinct()
            }
            val nodes = document.nodes.filter { it.id !in removed }
            return document.copy(nodes = nodes, edges = edges, stats = IrStats.of(nodes))
        }

        /** The entry point that reaches each node: over its edges, and from a region to what the region holds. */
        private fun ownersOf(document: IrDocument): Map<String, String> {
            val next = HashMap<String, MutableList<String>>()
            for (edge in document.edges) if (edge.kind.isFlow) next.getOrPut(edge.from) { ArrayList() } += edge.to
            for (node in document.nodes) node.parent?.let { next.getOrPut(it) { ArrayList() } += node.id }
            val owners = LinkedHashMap<String, String>()
            // The workflow method comes first, so a node that several entry points reach belongs to it.
            for (entryPoint in document.entryPoints.sortedWith(compareBy({ it.kind }, { it.id }))) {
                val queue = ArrayDeque(listOf(entryPoint.start))
                while (queue.isNotEmpty()) {
                    val id = queue.removeFirst()
                    if (owners.putIfAbsent(id, entryPoint.id) != null) continue
                    queue += next[id].orEmpty()
                }
            }
            return owners
        }

        private fun handlesOf(document: IrDocument, owners: Map<String, String>): Map<String, String> {
            val files = document.nodes.mapNotNull { it.loc?.file }.distinct()
            fun base(node: IrNode): String {
                val kind = NodeKind.serializer().descriptor.getElementName(node.kind.ordinal)
                val loc = node.loc
                return when {
                    loc == null -> "$kind(${owners[node.id] ?: "unreached"})"
                    files.size == 1 -> "$kind@${loc.line}"
                    else -> "$kind@${loc.file.substringAfterLast('/')}:${loc.line}"
                }
            }
            val bases = document.nodes.associate { it.id to base(it) }

            // Nodes that are the same thing at the same place are told apart by what is around them.
            fun surroundings(node: IrNode): String {
                val before = document.edges.filter { it.to == node.id }.map { "${bases[it.from] ?: it.from} ${it.kind} ${it.label}" }.sorted()
                val after = document.edges.filter { it.from == node.id }.map { "${bases[it.to]} ${it.kind} ${it.label}" }.sorted()
                return "${node.name} ${node.label} ${node.parent?.let(bases::get)} $before $after"
            }
            val handles = HashMap<String, String>()
            for ((base, nodes) in document.nodes.groupBy { bases.getValue(it.id) }) {
                if (nodes.size == 1) {
                    handles[nodes.single().id] = base
                } else {
                    nodes.sortedBy(::surroundings).forEachIndexed { index, node -> handles[node.id] = "$base#${index + 1}" }
                }
            }
            return handles
        }
    }
}
