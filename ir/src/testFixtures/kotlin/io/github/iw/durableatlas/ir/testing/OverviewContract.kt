package io.github.iw.durableatlas.ir.testing

import io.github.iw.durableatlas.ir.Detail
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.UnknownReasons

/**
 * What an overview has to keep of the full document of the same sources, which no schema can
 * say: the two are compared.
 *
 * - Every node of the overview is a node of the full document, with the same id and the same
 *   fields; an `unknown` node may name its call where the full document's does not.
 * - The steps, every node that is neither `unknown` nor `branch`, are the same in both. Only a
 *   region that held no step is gone with what it held.
 * - An `unknown` node that is gone is one behind which a step is only possible, and is listed
 *   as left out; nothing else is listed.
 * - No way leads from one step to the next in the overview that the full document does not
 *   have, through nodes that are no steps of the overview.
 * - Every step that the full document reaches from an entry point, the overview reaches too.
 * - The edges that are no part of the flow are those of the full document between the nodes
 *   that are left.
 */
object OverviewContract {
    /** One message for everything in [overview] that breaks the contract with [full]; empty if nothing does. */
    fun violations(full: IrDocument, overview: IrDocument): List<String> {
        val problems = ArrayList<String>()
        val before = full.nodes.associateBy { it.id }
        val after = overview.nodes.associateBy { it.id }
        if (overview.detail != Detail.OVERVIEW) problems += "the overview does not say that it is one"
        if (full.detail != null || full.leftOutCalls.isNotEmpty()) problems += "the full document says what only an overview does"
        if (full.copy(detail = overview.detail, nodes = overview.nodes, edges = overview.edges, stats = overview.stats, leftOutCalls = overview.leftOutCalls) != overview) {
            problems += "the overview differs from the full document in more than its nodes and edges"
        }

        for (node in overview.nodes) {
            val same = before[node.id]
            when {
                same == null -> problems += "${node.words()} is not in the full document"
                node.kind == NodeKind.UNKNOWN && node.label == null -> problems += "${node.words()} does not name its call"
                node != same && !(node.kind == NodeKind.UNKNOWN && same.label == null && node.copy(label = null) == same) ->
                    problems += "${node.words()} is not as in the full document: ${same.words()}"
            }
        }

        val ways = FlowWays(full)
        val gone = full.nodes.filter { it.id !in after }
        for (node in gone) {
            when {
                node.kind == NodeKind.BRANCH -> Unit
                node.kind == NodeKind.UNKNOWN -> if (!node.isLeftOut()) problems += "${node.words()} is gone, and a step is certain behind it"
                node.kind.isContainer -> {
                    val held = full.nodes.count { it.id in after && node.id in ways.around(it.id) }
                    if (held > 0) problems += "${node.words()} is gone and held $held node(s) that are still there"
                }
                else -> problems += "${node.words()} is gone"
            }
        }

        // What is listed as left out is what is gone, by place and reason.
        val listed = overview.leftOutCalls.map { Triple(it.loc, it.reason, it.label) }
        val missing = gone.filter { it.kind == NodeKind.UNKNOWN && it.loc != null }
        for (node in missing) {
            if (listed.none { it.first == node.loc && it.second == node.reason && (node.label == null || it.third == node.label) }) {
                problems += "${node.words()} is gone and not listed as left out"
            }
        }
        for (call in overview.leftOutCalls) {
            if (missing.none { it.loc == call.loc && it.reason == call.reason }) {
                problems += "${call.reason} ${call.label} at ${call.loc.file}:${call.loc.line} is listed as left out, and no such node is gone"
            }
        }

        val others = overview.edges.filter { !it.kind.isFlow }.toSet()
        val entryPoints = overview.entryPoints.map { it.id }.toSet()
        for (edge in others - full.edges.toSet()) problems += "edge ${edge.from} -> ${edge.to} (${edge.kind}) is not in the full document"
        for (edge in full.edges.filter { !it.kind.isFlow && (it.from in after || it.from in entryPoints) && it.to in after }) {
            if (edge !in others) problems += "edge ${edge.from} -> ${edge.to} (${edge.kind}) of the full document is missing"
        }

        val steps = overview.nodes.filter { it.isStep() }.map { it.id }.toSet()
        val left = FlowWays(overview)
        fun following(of: FlowWays, place: FlowWays.Place): Set<FlowWays.Place> =
            of.next(place).flatMap { first -> of.reach(listOf(first)) { it.id in steps } }.filter { it.id in steps }.toSet()
        // A step that the full document lacks was named above: its ways are not looked at.
        for (step in steps.filter { it in before }) {
            for (place in left.places(step)) {
                for (new in following(left, place) - following(ways, place)) {
                    problems += "a way from ${after.getValue(step).words()} to ${after.getValue(new.id).words()} that the full document does not have"
                }
            }
        }
        for (entryPoint in full.entryPoints) {
            val start = listOf(FlowWays.Place(entryPoint.start))
            val reached = if (entryPoint.start in after) left.reach(start).map { it.id }.toSet() else emptySet()
            for (id in ways.reach(start).map { it.id }.toSet()) {
                if (id in steps && id !in reached) problems += "${after.getValue(id).words()} is reached from ${entryPoint.id} in the full document and not in the overview"
            }
        }
        return problems
    }

    /** Whether the overview has anything but its name that the full document has not: a node, an edge or a label. */
    fun differs(full: IrDocument, overview: IrDocument): Boolean = overview.copy(detail = null, leftOutCalls = emptyList()) != full

    /** Whether the node is neither `unknown` nor `branch`: what a diagram of either detail shows. */
    private fun IrNode.isStep(): Boolean = kind != NodeKind.UNKNOWN && kind != NodeKind.BRANCH

    /** Whether the node is one that an overview leaves out: a step is only possible behind the call. */
    private fun IrNode.isLeftOut(): Boolean =
        reason == UnknownReasons.UNSUPPORTED || reason == UnknownReasons.UNSEEN_CODE || (reason == UnknownReasons.DYNAMIC_DISPATCH && candidates.isEmpty())

    private fun IrNode.words(): String =
        listOfNotNull(kind.name.lowercase(), reason, name ?: label, loc?.let { "at ${it.file}:${it.line}" }, "[$id]").joinToString(" ")
}
