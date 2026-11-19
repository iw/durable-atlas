package io.github.iw.durableatlas.overlay.coverage

import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.overlay.mapping.EdgeRef
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.mapping.Hits

/** Adds the traces of single executions up to the coverage of their workflow type. */
object CoverageBuilder {
    /**
     * @param mapper the mapper that made [traces]
     * @param traces any number of executions, in any order
     */
    fun build(mapper: ExecutionMapper, traces: List<ExecutionTrace>): WorkflowCoverage {
        val document = mapper.document
        val ordered = traces.sortedBy { it.history.source }
        val unrecorded = document.entryPoints.filter { it.kind == EntryPointKind.QUERY }.map { it.id }.toSet()
        val handlers = document.entryPoints.map { it.id }.toSet()
        fun recorded(node: String): Boolean = mapper.owners[node] !in unrecorded

        fun <K> total(key: K, hits: (ExecutionTrace) -> Map<K, Hits>): Triple<Int, Int, Int> {
            val found = ordered.mapNotNull { hits(it)[key] }
            return Triple(found.sumOf { it.count }, found.sumOf { it.uncertain }, found.size)
        }

        val nodes = document.nodes.map { node ->
            val (hits, uncertain, executions) = total(node.id) { it.nodes }
            NodeCoverage(node.id, node.kind, node.name, recorded(node.id), hits, uncertain, executions)
        }
        val written = document.edges.map { EdgeRef(it) to false }
        val edges = (written + mapper.impliedEdges.map { it to true }).map { (edge, implied) ->
            val (hits, uncertain, executions) = total(edge) { it.edges }
            // A `wakes` edge starts at a handler, every other edge at a node.
            val shown = if (edge.from in handlers) edge.from !in unrecorded else recorded(edge.from)
            EdgeCoverage(edge.from, edge.to, edge.kind, edge.label, implied, shown, hits, uncertain, executions)
        }
        val entryPoints = document.entryPoints.map { entryPoint ->
            val (hits, _, executions) = total(entryPoint.id) { it.entryPoints }
            EntryPointCoverage(entryPoint.id, entryPoint.kind, entryPoint.name, entryPoint.id !in unrecorded, hits, executions)
        }
        val histories = ordered.map { trace ->
            HistoryCoverage(
                file = trace.history.source,
                workflowId = trace.history.workflowId,
                runId = trace.history.runId,
                status = trace.status.text,
                closedAt = trace.closedAt,
                events = counts(trace),
            )
        }
        return WorkflowCoverage(
            workflowType = document.workflowType,
            commit = document.commit,
            executions = ordered.size,
            recognised = ordered.flatMap { trace -> trace.recognised.map { OutsideEvent(trace.history.source, it.eventId, it.eventType) } },
            events = histories.fold(EventCounts(0, 0, 0)) { sum, history -> sum + history.events },
            entryPoints = entryPoints,
            nodes = nodes,
            edges = edges,
            neverHit = nodes.filter { it.recorded && it.hits == 0 }.map { it.id },
            closes = ordered.groupBy { it.status.text to it.closedAt }
                .map { (close, executions) -> CloseCoverage(close.first, close.second, executions.size) }
                .sortedWith(compareBy({ it.status }, { it.nodes.joinToString(" ") })),
            ambiguous = ordered.flatMap { trace ->
                trace.ambiguous.map { match ->
                    AmbiguousEvent(
                        history = trace.history.source,
                        eventId = match.eventId,
                        eventType = match.eventType,
                        name = match.name,
                        doubt = match.doubt.text,
                        nodes = match.nodes,
                        edges = match.edges.map { EdgeName(it.from, it.to, it.kind, it.label) },
                    )
                }
            },
            unmapped = ordered.flatMap { trace ->
                trace.unmapped.map { UnmappedStep(trace.history.source, it.eventId, it.eventType, it.name, it.likelySources) }
            },
            histories = histories,
        )
    }

    fun counts(trace: ExecutionTrace): EventCounts =
        EventCounts(trace.mapped, trace.ambiguousEvents, trace.unmapped.size, trace.recognised.size)
}
