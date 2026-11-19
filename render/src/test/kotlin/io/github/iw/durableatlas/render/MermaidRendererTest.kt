package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import io.github.iw.durableatlas.render.svg.SvgDocuments
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MermaidRendererTest {
    @Test
    fun `diagrams match the golden files`() {
        for (name in TestDocuments.all) {
            val mermaid = MermaidRenderer.render(TestDocuments.load(name))
            MermaidLint.check(mermaid)
            GoldenFiles.assertMatches("mermaid/$name.mmd", mermaid)
        }
    }

    @Test
    fun `the order of nodes, edges and properties in the document does not matter`() {
        for (name in TestDocuments.all) {
            val document = TestDocuments.load(name)
            val expected = MermaidRenderer.render(document)
            repeat(5) { seed ->
                val random = Random(seed)
                val shuffled = document.copy(
                    entryPoints = document.entryPoints.shuffled(random).map { it.copy(writes = it.writes.reversed()) },
                    nodes = document.nodes.shuffled(random).map { it.copy(reads = it.reads.reversed()) },
                    edges = document.edges.shuffled(random),
                )
                assertEquals(expected, MermaidRenderer.render(shuffled), "$name, seed $seed")
            }
        }
    }

    @Test
    fun `a loop whose edges are left implicit gets an entry and a back edge`() {
        val mermaid = MermaidRenderer.render(TestDocuments.load(TestDocuments.ORDER))
        assertContains(mermaid, "    n04 --> n05\n")
        assertContains(mermaid, "    n06 -->|\"repeat\"| n04\n")
    }

    @Test
    fun `a loop that spells out its edges gets none added`() {
        val mermaid = MermaidRenderer.render(TestDocuments.load(TestDocuments.FULFILMENT))
        assertEquals(1, Regex("(?m)^ +n116 --> n117$").findAll(mermaid).count())
        assertContains(mermaid, "    n119 -->|\"repeat\"| n116\n")
        assertFalse("n117 -->|\"repeat\"| n116" in mermaid)
    }

    @Test
    fun `a parallel section has one lane per branch between its fork and its join`() {
        val mermaid = MermaidRenderer.render(TestDocuments.load(TestDocuments.FULFILMENT))
        assertEquals(3, Regex("subgraph n104_lane\\d ").findAll(mermaid).count())
        assertContains(mermaid, "    n103 --> n104\n")
        listOf("n105", "n107", "n108").forEach { assertContains(mermaid, "    n104 --> $it\n") }
        listOf("n106", "n107", "n108").forEach { assertContains(mermaid, "    $it --> n104_join\n") }
        assertContains(mermaid, "    n104_join --> n111\n")
    }

    @Test
    fun `an edge to the end of a region further out leads to the head of that loop or the join of that section`() {
        val document = SvgDocuments.outerEnds()
        val mermaid = MermaidRenderer.render(document)
        MermaidLint.check(mermaid)
        GoldenFiles.assertMatches("mermaid/OuterEndsWorkflow.mmd", mermaid)

        // A step and the failure of another go on with the next order, the arm of a branch with the next batch.
        assertContains(mermaid, "    n_close -->|\"repeat\"| n_orders\n")
        assertContains(mermaid, "    n_pick -->|\"ActivityFailure\"| n_orders\n")
        assertContains(mermaid, "    n_full -->|\"yes\"| n_batches\n")
        // Out of a loop to the join of the section its lane is in, and to the compensation it is a step of.
        assertContains(mermaid, "    n_answered -->|\"yes\"| notify_join\n")
        assertContains(mermaid, "    n_settled -->|\"yes\"| n_undo\n")
        // The regions that such an edge leaves on its way are still left as they are without it.
        assertContains(mermaid, "    n_items -->|\"repeat\"| n_orders\n")
        assertContains(mermaid, "    n_orders -->|\"repeat\"| n_batches\n")
        assertContains(mermaid, "    n_calls --> notify_join\n")
        // A step that says where it goes gets no way back to the head of its own loop added.
        assertFalse("n_close -->|\"repeat\"| n_items" in mermaid)
        assertFalse("n_full -->|\"repeat\"| n_items" in mermaid)
        assertEquals(1, mermaid.lines().count { it.trim() == "n_pick -->|\"repeat\"| n_items" })

        // Over the limit the regions are single nodes, and what happens inside them is not drawn.
        val collapsed = MermaidRenderer.render(document, RenderOptions(maxNodes = 10))
        MermaidLint.check(collapsed)
        assertEquals(
            listOf("n_start --> n_batches", "n_batches --> notify", "notify --> n_dispatch", "n_dispatch --> n_end", "n_dispatch -->|\"ActivityFailure\"| n_undo", "n_undo --> n_failed"),
            collapsed.lines().map { it.trim() }.filter { "-->" in it },
        )
    }

    @Test
    fun `the edges with which the analyser ends a region further out are drawn`() {
        val documents = SvgDocuments.all().filterKeys { it.startsWith("analyser/ends/") }
        assertEquals(7, documents.size)
        for ((name, document) in documents) {
            val mermaid = MermaidRenderer.render(document)
            MermaidLint.check(mermaid)
            val nodes = document.nodes.associateBy { it.id }
            fun regionsAround(id: String): List<String> = generateSequence(nodes.getValue(id).parent) { nodes.getValue(it).parent }.toList()
            val outer = document.edges.filter { it.to in regionsAround(it.from).drop(1) }
            assertTrue(outer.isNotEmpty(), name)
            for (edge in outer) {
                val region = nodes.getValue(edge.to)
                val target = if (region.kind == NodeKind.PARALLEL) "${edge.to}_join" else edge.to
                val label = edge.label ?: "repeat".takeIf { region.kind == NodeKind.LOOP }
                val arrow = if (label == null) "-->" else "-->|\"$label\"|"
                assertContains(mermaid, "    ${edge.from} $arrow $target\n", message = name)
            }
            // A region that is the last thing in the round of a loop still leads back to the head of that loop.
            for (region in document.nodes.filter { it.kind.isContainer && it.parent != null && document.edges.none { edge -> edge.from == it.id } }) {
                val parent = nodes.getValue(region.parent!!)
                val from = if (region.kind == NodeKind.PARALLEL) "${region.id}_join" else region.id
                when (parent.kind) {
                    NodeKind.LOOP -> assertContains(mermaid, "    $from -->|\"repeat\"| ${parent.id}\n", message = name)
                    NodeKind.PARALLEL -> assertContains(mermaid, "    $from --> ${parent.id}_join\n", message = name)
                    else -> Unit
                }
            }
        }
    }

    @Test
    fun `an inconsistent document is refused`() {
        val document = TestDocuments.load(TestDocuments.INVOICE)
        val broken = document.copy(edges = document.edges + IrEdge("n3", "missing"))
        val failure = assertFailsWith<IllegalArgumentException> { MermaidRenderer.render(broken) }
        assertContains(failure.message.orEmpty(), "InvoiceWorkflow")
        assertContains(failure.message.orEmpty(), "missing")
    }

    @Test
    fun `nodes that nothing reaches are still drawn`() {
        val document = TestDocuments.load(TestDocuments.INVOICE)
        val stray = IrNode("n9", NodeKind.ACTIVITY, name = "Stray")
        val mermaid = MermaidRenderer.render(document.copy(nodes = document.nodes + stray, stats = document.stats.copy(nodes = 5)))
        MermaidLint.check(mermaid)
        assertContains(mermaid, "    n9[\"Stray\"]\n")
    }

    @Test
    fun `a document without entry points or nodes still gives a diagram`() {
        val empty = IrDocument(
            commit = "c0ffee",
            modelMode = ModelMode.GRADLE,
            workflowType = "Empty",
            interfaceName = "com.example.Empty",
            implementation = "com.example.EmptyImpl",
            module = ":",
            entryPoints = emptyList(),
            nodes = emptyList(),
            edges = emptyList(),
        )
        assertEquals("flowchart TD\n", MermaidRenderer.render(empty))
    }

    @Test
    fun `a long chain does not exhaust the stack`() {
        val count = 20_000
        val nodes = (0 until count).map { IrNode("n$it", if (it == 0) NodeKind.START else NodeKind.ACTIVITY, name = "A$it") }
        val document = IrDocument(
            commit = "c0ffee",
            modelMode = ModelMode.GRADLE,
            workflowType = "Chain",
            interfaceName = "com.example.Chain",
            implementation = "com.example.ChainImpl",
            module = ":",
            entryPoints = listOf(EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, "Chain", "n0")),
            nodes = nodes,
            edges = (1 until count).map { IrEdge("n${it - 1}", "n$it", EdgeKind.FLOW) },
        )
        val mermaid = MermaidRenderer.render(document, RenderOptions(maxNodes = count))
        assertTrue(mermaid.lineSequence().count() > 2 * count - 1)
    }
}
