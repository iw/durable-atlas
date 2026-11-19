package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.IrStructure.Difference
import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.SourceLocation
import io.github.iw.durableatlas.ir.validate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Comparing IR documents by their structure, whatever their node ids are. */
class IrStructureTest {
    private fun at(line: Int) = SourceLocation("app/Flow.kt", line)

    /**
     * A small workflow: an await with a timeout, a loop around two steps after it, and a signal
     * handler that wakes the await. Ids are [prefix] and a number.
     */
    private fun document(
        prefix: String,
        handler: Boolean = true,
        change: (List<IrNode>, List<IrEdge>) -> Pair<List<IrNode>, List<IrEdge>> = { n, e -> n to e },
    ): IrDocument {
        fun id(number: Int) = "$prefix$number"
        val handlerNodes = setOf(id(6), id(7))
        val nodes = listOf(
            IrNode(id(0), NodeKind.START, loc = at(10)),
            IrNode(id(1), NodeKind.AWAIT, timeout = "PT1M", reads = listOf("done"), label = "done", loc = at(11)),
            IrNode(id(2), NodeKind.LOOP, label = "for each item", loc = at(12)),
            IrNode(id(3), NodeKind.ACTIVITY, name = "Pack", parent = id(2), loc = at(13)),
            IrNode(id(4), NodeKind.ACTIVITY, name = "Send", parent = id(2), loc = at(14)),
            IrNode(id(5), NodeKind.END),
            IrNode(id(6), NodeKind.START, loc = at(20)),
            IrNode(id(7), NodeKind.END),
        ).filter { handler || it.id !in handlerNodes }
        val edges = listOf(
            IrEdge(id(0), id(1)),
            IrEdge(id(1), id(2), label = "done"),
            IrEdge(id(1), id(5), EdgeKind.TIMEOUT),
            IrEdge(id(3), id(4)),
            IrEdge(id(2), id(5)),
            IrEdge(id(6), id(7)),
            IrEdge("signal-finish", id(1), EdgeKind.WAKES),
        ).filter { handler || (it.from !in handlerNodes && it.kind != EdgeKind.WAKES) }
        val (changedNodes, changedEdges) = change(nodes, edges)
        return IrDocument(
            commit = FIXED_COMMIT,
            modelMode = ModelMode.DEGRADED,
            workflowType = "Packing",
            interfaceName = "app.Packing",
            implementation = "app.PackingImpl",
            module = ":",
            entryPoints = listOfNotNull(
                EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, "Packing", id(0)),
                EntryPoint("signal-finish", EntryPointKind.SIGNAL, "finish", id(6), writes = listOf("done")).takeIf { handler },
            ),
            nodes = changedNodes,
            edges = changedEdges,
        ).also { assertEquals(emptyList(), it.validate()) }
    }

    @Test
    fun `a document is described without ids, by the kind and the line of each node`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Packing, start start@10",
                "entry point signal-finish: kind SIGNAL, name finish, start start@20, writes done",
                "node activity@13: name Pack, parent loop@12",
                "node activity@14: name Send, parent loop@12",
                "node await@11: timeout PT1M, reads done, label done",
                // A node without a place is told by the entry point it belongs to.
                "node end(main)",
                "node end(signal-finish)",
                "node loop@12: label for each item",
                "node start@10",
                "node start@20",
                "edge activity@13 -> activity@14",
                "edge await@11 -(timeout)-> end(main)",
                "edge await@11 -> loop@12 [done]",
                "edge entry point signal-finish -(wakes)-> await@11",
                "edge loop@12 -> end(main)",
                "edge start@10 -> await@11",
                "edge start@20 -> end(signal-finish)",
            ),
            IrStructure.of(document("n")).describe(),
        )
    }

    @Test
    fun `documents that differ only in their ids do not differ`() {
        assertEquals(emptyList(), IrStructure.differences(document("n"), document("other-")))
        assertEquals(IrStructure.of(document("n")).describe(), IrStructure.of(document("zz")).describe())
    }

    @Test
    fun `a property that differs is named, with the node and the entry point it belongs to`() {
        val relabelled = document("x") { nodes, edges ->
            nodes.map {
                when (it.kind) {
                    NodeKind.LOOP -> it.copy(label = "for each parcel")
                    NodeKind.AWAIT -> it.copy(timeout = null)
                    else -> it
                }
            } to edges.filter { it.kind != EdgeKind.TIMEOUT }
        }

        assertEquals(
            listOf(
                Difference(Difference.Kind.NODE_DIFFERS, "main", "await@11", "timeout", expected = "PT1M", actual = null),
                Difference(Difference.Kind.NODE_DIFFERS, "main", "loop@12", "label", expected = "for each item", actual = "for each parcel"),
                Difference(Difference.Kind.EDGE_MISSING, "main", "await@11 -(timeout)-> end(main)"),
            ),
            IrStructure.differences(document("n"), relabelled),
        )
        assertEquals(
            "[main] node loop@12: label is for each parcel, expected for each item",
            IrStructure.differences(document("n"), relabelled)[1].toString(),
        )
    }

    @Test
    fun `nodes and edges that only one document has are listed, a node's region and an edge's label included`() {
        val changed = document("x") { nodes, edges ->
            val pack = nodes.single { it.name == "Pack" }
            val send = nodes.single { it.name == "Send" }
            val weigh = IrNode("x-weigh", NodeKind.ACTIVITY, name = "Weigh", parent = pack.parent, loc = at(15))
            // One more step in the loop, the label of an edge changed, and a step moved out of the loop.
            (nodes.map { if (it == pack) it.copy(parent = null) else it } + weigh) to
                (edges.map { if (it.label == "done") it.copy(label = "finished") else it } + IrEdge(send.id, weigh.id))
        }

        assertEquals(
            listOf(
                "[main] node activity@13: parent is null, expected loop@12",
                "[main] unexpected node activity@15: name Weigh, parent loop@12",
                "[main] unexpected edge activity@14 -> activity@15",
                "[main] missing edge await@11 -> loop@12 [done]",
                "[main] unexpected edge await@11 -> loop@12 [finished]",
            ),
            IrStructure.differences(document("n"), changed).map { it.toString() },
        )
    }

    @Test
    fun `what belongs to a handler is told from what belongs to the workflow method`() {
        val differences = IrStructure.differences(document("n"), document("x", handler = false))

        assertEquals(setOf("signal-finish"), differences.map { it.entryPoint }.toSet())
        assertEquals(
            listOf(
                "missing entry point signal-finish: kind SIGNAL, name finish, start start@20, writes done",
                "[signal-finish] missing node end(signal-finish): ",
                "[signal-finish] missing node start@20: ",
                // An edge that wakes an await belongs to the handler it starts at.
                "[signal-finish] missing edge entry point signal-finish -(wakes)-> await@11",
                "[signal-finish] missing edge start@20 -> end(signal-finish)",
            ),
            differences.map { it.toString() },
        )
    }

    @Test
    fun `nodes of one kind on one line are told apart by what is around them`() {
        // The same step twice on a line, once after the await and once after the loop.
        fun twice(prefix: String, firstId: String, secondId: String) = document(prefix) { nodes, edges ->
            val await = nodes.single { it.kind == NodeKind.AWAIT }
            val loop = nodes.single { it.kind == NodeKind.LOOP }
            val afterAwait = IrNode(firstId, NodeKind.TIMER, duration = "PT1S", loc = at(30))
            val afterLoop = IrNode(secondId, NodeKind.TIMER, duration = "PT1S", loc = at(30))
            (nodes + afterAwait + afterLoop) to (edges + IrEdge(await.id, afterAwait.id) + IrEdge(loop.id, afterLoop.id))
        }
        val one = twice("n", "a", "b")
        // The ids sort the other way round.
        val other = twice("x", "z", "y")

        assertEquals(emptyList(), IrStructure.differences(one, other))
        assertEquals(setOf("timer@30#1", "timer@30#2"), IrStructure.of(one).handles.values.filter { it.startsWith("timer") }.toSet())
        assertEquals(IrStructure.of(one).handles.getValue("a"), IrStructure.of(other).handles.getValue("z"))
    }

    @Test
    fun `a document without some of its nodes leads past them, on the edges that led to them`() {
        val document = document("n")
        val await = document.nodes.single { it.kind == NodeKind.AWAIT }
        val pack = document.nodes.single { it.name == "Pack" }

        val without = IrStructure.without(document, setOf(await.id, pack.id))

        assertEquals(emptyList(), without.validate())
        assertEquals(
            listOf(
                // What entered the await now enters what the await led to, and what woke it wakes that.
                "edge entry point signal-finish -(wakes)-> end(main)",
                "edge entry point signal-finish -(wakes)-> loop@12",
                "edge loop@12 -> end(main)",
                "edge start@10 -> end(main)",
                "edge start@10 -> loop@12",
                "edge start@20 -> end(signal-finish)",
            ),
            IrStructure.of(without).describe().filter { it.startsWith("edge ") },
        )
        // The first step of the loop had no edge into it, so the step after it is the first now.
        assertEquals(listOf("activity@14", "loop@12"), IrStructure.of(without).handles.values.filter { "@1" in it && "start" !in it }.sorted())
    }
}
