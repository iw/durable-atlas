package io.github.iw.durableatlas.overlay.testing

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.overlay.mapping.EdgeRef
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.Hits

/**
 * Writes hit counts with names a person can check against a history, for documents whose node
 * ids are hashes: a node is called by its kind and what it runs, as in `activity ReserveStock`.
 * Where that is not unique, the line it stands on is added (`activity NotifyEmail@49`), and then
 * the region it lies in. The start and end of a handler say which handler (`end of signal-approve`).
 */
class Profiles(private val mapper: ExecutionMapper) {
    private val document: IrDocument = mapper.document
    private val names: Map<String, String>

    init {
        val main = document.entryPoints.first().id
        fun plain(node: IrNode): String {
            val what = node.name ?: node.changeId ?: node.label
            val owner = mapper.owners[node.id]
            return when {
                (node.kind == NodeKind.START || node.kind == NodeKind.END) && owner != null && owner != main -> "${kind(node)} of $owner"
                what != null -> "${kind(node)} $what"
                else -> kind(node)
            }
        }
        val plain = document.nodes.associate { it.id to plain(it) }
        val located = unique(plain) { node, name -> "$name@${node.loc?.line ?: node.id}" }
        names = unique(located) { node, name -> "$name in ${node.parent?.let(located::get) ?: node.id}" }
        check(names.values.toSet().size == names.size) { "Node names are not unique: ${names.values.sorted()}" }
    }

    /** [names] with those that several nodes share made [longer]. */
    private fun unique(names: Map<String, String>, longer: (IrNode, String) -> String): Map<String, String> {
        val shared = names.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return document.nodes.associate { node ->
            val name = names.getValue(node.id)
            node.id to if (name in shared) longer(node, name) else name
        }
    }

    private fun kind(node: IrNode): String = node.kind.name.lowercase().split('_')
        .mapIndexed { index, word -> if (index == 0) word else word.replaceFirstChar(Char::uppercaseChar) }
        .joinToString("")

    fun name(id: String): String = names[id] ?: id

    /** The nodes that were hit, in the order of the source, as `name ×count`; `?` marks hits that are not certain. */
    fun nodes(hits: Map<String, Hits>): String =
        document.nodes
            .filter { it.id in hits }
            .sortedWith(compareBy<IrNode>({ it.loc == null }, { it.loc?.file }, { it.loc?.line }, { name(it.id) }))
            .joinToString("\n") { "${name(it.id)} ${count(hits.getValue(it.id))}" }

    /** The edges that were taken, sorted, as `from -> to [kind] [label] ×count`. */
    fun edges(hits: Map<EdgeRef, Hits>): String =
        hits.entries.map { (edge, count) ->
            val kind = if (edge.kind == EdgeKind.FLOW) "" else " ${edge.kind.name.lowercase()}"
            val label = edge.label?.let { " '$it'" }.orEmpty()
            "${name(edge.from)} -> ${name(edge.to)}$kind$label ${count(count)}"
        }.sorted().joinToString("\n")

    private fun count(hits: Hits): String = "×${hits.count}" + if (hits.uncertain > 0) " (${hits.uncertain}?)" else ""
}
