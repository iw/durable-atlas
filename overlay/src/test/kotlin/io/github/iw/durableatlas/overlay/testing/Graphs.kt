package io.github.iw.durableatlas.overlay.testing

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind

/**
 * Builds small workflow documents for tests. The workflow method starts at the node `start`,
 * which is always there; ids are whatever the test calls its nodes.
 */
class Graphs(private val workflowType: String = "W") {
    private val nodes = arrayListOf(IrNode("start", NodeKind.START))
    private val edges = ArrayList<IrEdge>()
    private val entryPoints = arrayListOf(EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, workflowType, "start"))

    fun node(id: String, kind: NodeKind, name: String? = null, parent: String? = null, build: (IrNode) -> IrNode = { it }): Graphs {
        nodes += build(IrNode(id, kind, name = name, parent = parent))
        return this
    }

    fun activity(id: String, name: String = id, parent: String? = null): Graphs = node(id, NodeKind.ACTIVITY, name, parent)

    /** Edges along [ids], each to the next. */
    fun flow(vararg ids: String): Graphs {
        ids.toList().zipWithNext().forEach { (from, to) -> edges += IrEdge(from, to) }
        return this
    }

    fun edge(from: String, to: String, kind: EdgeKind = EdgeKind.FLOW, label: String? = null): Graphs {
        edges += IrEdge(from, to, kind, label)
        return this
    }

    /** A handler `<id>` whose graph starts at the node `<id>.start`, which is added. */
    fun handler(id: String, kind: EntryPointKind, name: String, writes: List<String> = emptyList()): Graphs {
        nodes += IrNode("$id.start", NodeKind.START)
        entryPoints += EntryPoint(id, kind, name, "$id.start", writes = writes)
        return this
    }

    fun build(): IrDocument = IrDocument(
        commit = "0".repeat(40),
        modelMode = ModelMode.GRADLE,
        workflowType = workflowType,
        interfaceName = "com.example.$workflowType",
        implementation = "com.example.${workflowType}Impl",
        module = ":workflows",
        entryPoints = entryPoints,
        nodes = nodes,
        edges = edges,
    )
}
