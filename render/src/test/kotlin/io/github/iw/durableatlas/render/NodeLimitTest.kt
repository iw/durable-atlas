package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import io.github.iw.durableatlas.render.mermaid.MermaidFormat
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeLimitTest {
    private val small = RenderOptions(maxNodes = 10)

    @Test
    fun `over the limit, regions become single nodes and the page says so`() {
        val prepared = MermaidFormat.prepare(TestDocuments.load(TestDocuments.FULFILMENT), PageOptions(render = small))
        val page = prepared.render()
        MermaidLint.diagramsIn(page).forEach(MermaidLint::check)
        GoldenFiles.assertMatches("collapsed/FulfilmentWorkflow.md", page)

        assertEquals(2, prepared.limit.collapsedRegions)
        assertEquals(34, prepared.limit.total)
        assertEquals(23, prepared.limit.drawn)
        assertContains(
            page,
            "> **Note:** This workflow has 34 nodes, more than the limit of 10, so its 2 regions are drawn as single " +
                "nodes (23 nodes shown). Regions are loops, parallel sections and compensations.",
        )
    }

    @Test
    fun `edges across the border of a collapsed region attach to it, edges inside are dropped`() {
        val mermaid = MermaidRenderer.render(TestDocuments.load(TestDocuments.FULFILMENT), small)
        assertContains(mermaid, "    n104(\"parallel<br/>8 nodes hidden\")\n")
        assertContains(mermaid, "    n116(\"loop#58; do … while (!delivered)<br/>3 nodes hidden\")\n")
        // from inside the parallel section to a node outside it
        assertContains(mermaid, "    n104 -->|\"CardDeclinedException\"| n122\n")
        // two exits of the loop to the same node, with different captions
        assertContains(mermaid, "    n116 -->|\"DELIVERED\"| n123\n")
        assertContains(mermaid, "    n116 -->|\"timeout\"| n123\n")
        // a handler that wakes an await inside the loop
        assertContains(mermaid, "    n210 -.->|\"carrierStatus\"| n116\n")
        listOf("n105", "n106", "n108", "n109", "n117", "n119", "n126", "_join", "_region", "repeat").forEach { assertFalse(it in mermaid, it) }
    }

    @Test
    fun `a collapsed region reports the unknown nodes it hides`() {
        val document = TestDocuments.load(TestDocuments.ODD)
        val mermaid = MermaidRenderer.render(document, RenderOptions(maxNodes = 5))
        MermaidLint.check(mermaid)
        assertContains(mermaid, "<br/>2 nodes hidden\")")
        val hidden = document.copy(nodes = document.nodes.map { if (it.id == "n5") it.copy(parent = "n2") else it })
        assertContains(MermaidRenderer.render(hidden, RenderOptions(maxNodes = 5)), "<br/>3 nodes hidden, 1 unknown\")")
    }

    @Test
    fun `a compensation is a region only when it has steps`() {
        val document = TestDocuments.load(TestDocuments.FULFILMENT)
        val mermaid = MermaidRenderer.render(document)
        assertContains(mermaid, "subgraph n106_region [\"compensation\"]\n                n106[\"compensation\"]\n                n126[\"RefundCard\"]\n")
        assertContains(mermaid, "    n106 --> n126\n")
        assertContains(mermaid, "    n125[\"compensation#58; ReleaseInventory\"]\n")
        assertFalse("n125_region" in mermaid)
        // what a compensation runs does not lead back into the flow
        assertFalse("n127 -->" in mermaid)
        assertFalse(Regex("n125\\(").containsMatchIn(MermaidRenderer.render(document, small)))
    }

    @Test
    fun `the limit itself is still within the limit`() {
        val document = TestDocuments.load(TestDocuments.FULFILMENT)
        val atLimit = MermaidRenderer.diagram(document, RenderOptions(maxNodes = document.nodes.size))
        assertFalse(atLimit.limit.exceeded)
        assertEquals(0, atLimit.limit.collapsedRegions)
        assertNull(atLimit.limit.notice())
        assertEquals(MermaidRenderer.render(document), atLimit.text)

        val over = MermaidRenderer.diagram(document, RenderOptions(maxNodes = document.nodes.size - 1))
        assertTrue(over.limit.exceeded)
        assertEquals(2, over.limit.collapsedRegions)
    }

    @Test
    fun `the default limit is 150 nodes`() {
        assertEquals(150, RenderOptions().maxNodes)
        assertFalse(MermaidRenderer.diagram(loopOf(150)).limit.exceeded)
        val over = MermaidRenderer.diagram(loopOf(151))
        assertEquals(1, over.limit.collapsedRegions)
        assertEquals(3, over.limit.drawn)
        assertEquals(
            "This workflow has 151 nodes, more than the limit of 150, so its one region is drawn as a single node " +
                "(3 nodes shown). Regions are loops, parallel sections and compensations.",
            over.limit.notice(),
        )
    }

    @Test
    fun `a large workflow without regions is drawn in full and says why`() {
        val diagram = MermaidRenderer.diagram(TestDocuments.load(TestDocuments.INVOICE), RenderOptions(maxNodes = 3))
        assertEquals(MermaidRenderer.render(TestDocuments.load(TestDocuments.INVOICE)), diagram.text)
        assertEquals(
            "This workflow has 4 nodes, more than the limit of 3, and no loops, parallel sections or other regions " +
                "that could be drawn as single nodes.",
            diagram.limit.notice(),
        )
    }

    /** start, a loop with [total] - 3 activities in it, end. */
    private fun loopOf(total: Int): IrDocument {
        val body = (1..total - 3).map { IrNode("n$it", NodeKind.ACTIVITY, name = "Step$it", parent = "loop") }
        return IrDocument(
            commit = "c0ffee",
            modelMode = ModelMode.GRADLE,
            workflowType = "Big",
            interfaceName = "com.example.Big",
            implementation = "com.example.BigImpl",
            module = ":",
            entryPoints = listOf(EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, "Big", "start")),
            nodes = listOf(IrNode("start", NodeKind.START), IrNode("loop", NodeKind.LOOP), IrNode("end", NodeKind.END)) + body,
            edges = listOf(IrEdge("start", "loop"), IrEdge("loop", "end")) + body.zipWithNext { a, b -> IrEdge(a.id, b.id) },
        )
    }
}
