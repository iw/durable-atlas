package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.overlay.diagram.OverlayDirectory
import io.github.iw.durableatlas.overlay.diagram.OverlayOptions
import io.github.iw.durableatlas.overlay.diagram.OverlayText
import io.github.iw.durableatlas.overlay.mapping.EdgeRef
import io.github.iw.durableatlas.overlay.mapping.FlowGraph
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Graphs
import io.github.iw.durableatlas.render.Decoration
import io.github.iw.durableatlas.render.DiagramDecorator
import io.github.iw.durableatlas.render.DiagramFormats
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The edges a document leaves unsaid between a region and its steps are counted by the coverage
 * and drawn by the Mermaid diagram. Both have to find the same ones, or a count has no edge to
 * stand on, or an edge never gets a count.
 */
class ImpliedEdgeDiagramTest {
    @TempDir
    lateinit var dir: Path

    /** The edges the Mermaid diagram of [document] draws that the document does not hold. */
    private fun added(document: IrDocument): Set<EdgeRef> {
        val asked = LinkedHashSet<IrEdge>()
        val recording = object : DiagramDecorator {
            override fun edge(edge: IrEdge): Decoration? {
                asked += edge
                return null
            }
        }
        MermaidRenderer.render(document, decorator = recording)
        assertTrue(asked.containsAll(document.edges), document.workflowType)
        return (asked - document.edges.toSet()).mapTo(LinkedHashSet(), ::EdgeRef)
    }

    private fun documents(directory: Path): List<IrDocument> =
        directory.listDirectoryEntries("*.ir.json").sortedBy { it.name }.map { IrJson.decode(Files.readString(it)) }

    @Test
    fun `the diagram adds the edges the walk over a history takes as implied, and no others`() {
        val analysed = documents(Fixtures.sampleIr) + documents(Fixtures.regionEndIr) + documents(Fixtures.regionExitIr) +
            documents(Fixtures.constructIr) + documents(Fixtures.patternIr) + documents(Fixtures.silentIr) +
            documents(Fixtures.openIr) + documents(Fixtures.optionIr) + Fixtures.demo
        assertEquals(30 + 7 + 10 + 12 + 16 + 10 + 13 + 9 + 1, analysed.size)
        val made = listOf(
            // A step that can fail into a compensation outside its loop, a lane that can, and a return from an inner loop.
            Graphs("Leaving").node("orders", NodeKind.LOOP).activity("A", parent = "orders")
                .node("items", NodeKind.LOOP, parent = "orders").node("damaged", NodeKind.BRANCH, parent = "items").activity("B", parent = "items")
                .node("both", NodeKind.PARALLEL).activity("C", parent = "both").activity("D", parent = "both").activity("E", parent = "both")
                .node("undo", NodeKind.COMPENSATION).activity("F", parent = "undo").activity("G", parent = "undo")
                .node("failed", NodeKind.FAIL).node("end", NodeKind.END)
                .flow("start", "orders", "both", "end").flow("A", "items").flow("D", "E").flow("undo", "failed")
                .edge("damaged", "end", label = "yes").edge("damaged", "B", label = "no")
                .edge("B", "undo", EdgeKind.EXCEPTION).edge("C", "undo", EdgeKind.EXCEPTION).edge("E", "undo", EdgeKind.EXCEPTION)
                .edge("G", "failed", EdgeKind.EXCEPTION)
                .build(),
            // Steps whose flow edge leaves their region for good, from a loop and from a lane; one of them can fail as well.
            Graphs("Final").node("orders", NodeKind.LOOP).activity("A", parent = "orders").activity("B", parent = "orders")
                .node("both", NodeKind.PARALLEL).activity("C", parent = "both").activity("D", parent = "both")
                .activity("after").node("failed", NodeKind.FAIL).node("end", NodeKind.END)
                .flow("start", "orders", "both", "after", "end").flow("A", "B", "after").flow("C", "end")
                .edge("B", "failed", EdgeKind.EXCEPTION).edge("C", "failed", EdgeKind.EXCEPTION)
                .build(),
            // Edges a document should not have: from outside a loop to a step of it, and from a step into a loop beside it.
            Graphs("Irregular").node("orders", NodeKind.LOOP).activity("A", parent = "orders")
                .node("items", NodeKind.LOOP, parent = "orders").activity("B", parent = "items").activity("C", parent = "items")
                .activity("D").node("end", NodeKind.END)
                .flow("start", "orders", "end").flow("start", "D", "A").flow("A", "C").flow("B", "C")
                .build(),
        )
        var implied = 0
        for (document in analysed + made) {
            val graph = FlowGraph(document)
            assertEquals(graph.implied.toSet(), added(document), document.workflowType)
            assertEquals(graph.implied.size, graph.implied.toSet().size, document.workflowType)
            implied += graph.implied.size
        }
        // The check is not empty-handed.
        assertTrue(implied > 100, implied.toString())

        // What the first of the made graphs is there for.
        val leaving = added(made[0])
        assertContains(leaving, EdgeRef("B", "items"))
        assertContains(leaving, EdgeRef("items", "orders"))
        assertContains(leaving, EdgeRef("C", "both"))
        assertContains(leaving, EdgeRef("E", "both"))
        assertEquals(setOf(EdgeRef("undo", "F"), EdgeRef("undo", "G")), leaving.filterTo(HashSet()) { "undo" in listOf(it.from, it.to) })
        // And the second: the steps that leave say so themselves, the lane beside them still joins.
        assertEquals(setOf(EdgeRef("orders", "A"), EdgeRef("both", "C"), EdgeRef("both", "D"), EdgeRef("D", "both")), added(made[1]))
    }

    @Test
    fun `every implied edge of the sample repository is on its coverage diagram with its count`() {
        val out = dir.resolve("out")
        val summary = OverlayDirectory.write(Fixtures.sampleIr, Fixtures.sampleHistories, out, OverlayOptions(formats = listOf(DiagramFormats.MERMAID)))

        var counted = 0
        for (workflow in summary.workflows) {
            val document = Fixtures.sample(workflow.irFile.removeSuffix(".ir.json"))
            val nodes = document.nodes.associateBy { it.id }
            val page = Files.readString(out.resolve(workflow.files.single { it.format == DiagramFormats.MERMAID.name }.file))
            for (edge in workflow.coverage.edges.filter { it.implied && it.recorded }) {
                // Out of a step to the end of its region, or else into a step from its region. A parallel
                // section is left at its join, and its lanes end there.
                val region = nodes.getValue(edge.to).takeIf { nodes.getValue(edge.from).parent == it.id }
                val source = if (region != null && nodes.getValue(edge.from).kind == NodeKind.PARALLEL) "${edge.from}_join" else edge.from
                val target = if (region?.kind == NodeKind.PARALLEL) "${edge.to}_join" else edge.to
                val words = listOfNotNull(
                    "repeat".takeIf { region?.kind == NodeKind.LOOP },
                    OverlayText.count(edge.hits, edge.uncertain).takeIf { edge.hits > 0 },
                )
                val arrow = if (words.isEmpty()) "-->" else "-->|\"${words.joinToString(" · ")}\"|"
                assertContains(page, "    $source $arrow $target\n", message = workflow.workflowType)
                counted++
            }
        }
        assertTrue(counted > 30, counted.toString())

        // The last step of the order's loop can fail into the compensation outside it; five rounds went on from it.
        val order = summary.workflows.single { it.workflowType == "OrderWorkflow" }
        val document = Fixtures.sample("OrderWorkflow")
        val loop = document.nodes.single { it.kind == NodeKind.LOOP }
        val reserve = document.nodes.single { it.parent == loop.id && it.name == "ReserveStock" }
        val back = order.coverage.edges.single { it.from == reserve.id && it.to == loop.id }
        assertTrue(back.implied)
        assertEquals(5, back.hits)
        val coverage = Files.readString(out.resolve("OrderWorkflow.coverage.md"))
        assertContains(coverage, "    ${reserve.id} -->|\"repeat · ×5\"| ${loop.id}\n")
        // The execution that was paid took it twice, and it is on its path.
        val paid = Files.readString(out.resolve("executions/OrderWorkflow.md"))
        val edges = paid.lines().map { it.trim() }.filter { "-->" in it }
        val position = edges.indexOf("${reserve.id} -->|\"repeat · ×2\"| ${loop.id}")
        assertTrue(position >= 0, "the edge is not in the diagram")
        val highlighted = paid.lines().single { it.trim().startsWith("linkStyle") && it.trim().endsWith("stroke:#1d4ed8,stroke-width:3px") }
        assertContains(highlighted.trim().removePrefix("linkStyle ").substringBefore(' ').split(','), position.toString())
    }
}
