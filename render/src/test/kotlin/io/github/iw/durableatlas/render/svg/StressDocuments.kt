package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.svg.engine.EngineGraph
import io.github.iw.durableatlas.render.svg.engine.EngineLayout
import io.github.iw.durableatlas.render.svg.engine.EngineNode
import io.github.iw.durableatlas.render.svg.engine.LayoutEngine

/** Workflow documents of the shapes that take long to arrange, at whatever size a test asks for. */
object StressDocuments {
    /** Steps one after the other. */
    fun chain(steps: Int): IrDocument {
        val activities = activities(steps)
        return document("ChainWorkflow", activities, path(activities))
    }

    /** Steps in a `try`: each can fail, and every one of the [clauses] catches what any of them fails with. */
    fun tried(steps: Int, clauses: Int = 1): IrDocument {
        val activities = activities(steps)
        val handlers = (1..clauses).map { IrNode("c%02d".format(it), NodeKind.ACTIVITY, name = "HandleFailure$it") }
        val caught = activities.flatMap { step -> handlers.mapIndexed { index, handler -> IrEdge(step.id, handler.id, EdgeKind.EXCEPTION, "Failure${index + 1}") } }
        return document("TriedWorkflow", activities + handlers, path(activities) + caught + handlers.map { IrEdge(it.id, "end") })
    }

    /** `if … else if … else`: every arm is two steps, after which the workflow is done. */
    fun ifChain(arms: Int): IrDocument {
        val branches = (1..arms).map { IrNode("b%03d".format(it), NodeKind.BRANCH, label = "kind == $it?") }
        val steps = (1..arms + 1).flatMap { arm -> listOf(1, 2).map { IrNode("a%03d-%d".format(arm, it), NodeKind.ACTIVITY, name = "Handle${arm}Part$it") } }
        val edges = ArrayList<IrEdge>()
        edges += IrEdge("start", branches.first().id)
        branches.forEachIndexed { index, branch ->
            val arm = index + 1
            edges += IrEdge(branch.id, "a%03d-1".format(arm), label = "yes")
            edges += IrEdge(branch.id, branches.getOrNull(index + 1)?.id ?: "a%03d-1".format(arms + 1), label = "no")
        }
        for (arm in 1..arms + 1) {
            edges += IrEdge("a%03d-1".format(arm), "a%03d-2".format(arm))
            edges += IrEdge("a%03d-2".format(arm), "end")
        }
        return document("IfChainWorkflow", branches + steps, edges)
    }

    /** Checks one after the other, each followed by a step: where a check finds something missing, the workflow is done. */
    fun returns(checks: Int): IrDocument {
        val branches = (1..checks).map { IrNode("b%03d".format(it), NodeKind.BRANCH, label = "part $it missing?") }
        val steps = (1..checks).map { IrNode("a%03d".format(it), NodeKind.ACTIVITY, name = "HandlePart$it") }
        val edges = ArrayList<IrEdge>()
        edges += IrEdge("start", branches.first().id)
        for (index in 0 until checks) {
            edges += IrEdge(branches[index].id, "end", label = "yes")
            edges += IrEdge(branches[index].id, steps[index].id, label = "no")
            edges += IrEdge(steps[index].id, branches.getOrNull(index + 1)?.id ?: "end")
        }
        return document("ReturnsWorkflow", branches + steps, edges)
    }

    /** Loops one after the other, each around a few steps. */
    fun loops(count: Int, steps: Int): IrDocument {
        val loops = (1..count).map { IrNode("l%02d".format(it), NodeKind.LOOP, label = "for each item of batch $it") }
        val bodies = loops.map { loop -> (1..steps).map { IrNode("${loop.id}-%d".format(it), NodeKind.ACTIVITY, name = "Handle${loop.id.drop(1)}Step$it", parent = loop.id) } }
        return document("LoopsWorkflow", loops + bodies.flatten(), path(loops) + bodies.flatMap { body -> body.zipWithNext { from, to -> IrEdge(from.id, to.id) } })
    }

    private fun activities(count: Int): List<IrNode> = (1..count).map { IrNode("a%04d".format(it), NodeKind.ACTIVITY, name = "RunStep$it") }

    /** The edges from the start through [steps] to the end. */
    private fun path(steps: List<IrNode>): List<IrEdge> = (listOf("start") + steps.map { it.id } + "end").zipWithNext { from, to -> IrEdge(from, to) }

    private fun document(type: String, nodes: List<IrNode>, edges: List<IrEdge>): IrDocument = IrDocument(
        commit = "0000000000000000000000000000000000000000",
        modelMode = ModelMode.GRADLE,
        workflowType = type,
        interfaceName = "com.example.$type",
        implementation = "com.example.${type}Impl",
        module = ":made",
        entryPoints = listOf(EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, type, "start")),
        nodes = listOf(IrNode("start", NodeKind.START)) + nodes + IrNode("end", NodeKind.END),
        edges = edges,
    )
}

/**
 * A layout engine that keeps count of the graphs it is handed, each by the number of its nodes at all levels.
 *
 * @param largest the most nodes a graph may hold. A larger one fails the test where it is handed over, before it is
 *   laid out: such graphs are what takes minutes, and a test must not take them to find that out
 */
internal class CountingEngine(private val engine: LayoutEngine, private val largest: Int = Int.MAX_VALUE) : LayoutEngine {
    val graphs = ArrayList<Int>()

    override fun layout(graph: EngineGraph): EngineLayout {
        val nodes = count(graph.nodes)
        if (nodes > largest) throw AssertionError("a graph of $nodes nodes was handed to the layout engine, more than $largest")
        graphs += nodes
        return engine.layout(graph)
    }

    private fun count(nodes: List<EngineNode>): Int = nodes.sumOf { 1 + count(it.children) }
}
