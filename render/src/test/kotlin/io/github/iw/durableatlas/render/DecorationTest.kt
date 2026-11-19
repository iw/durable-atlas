package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import io.github.iw.durableatlas.render.mermaid.MermaidFormat
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a history overlay will do with the renderer: grey out, count and highlight. */
class DecorationTest {
    private val document = TestDocuments.load(TestDocuments.ORDER)

    /** Hit counts as a coverage overlay would have them; nodes that are missing were never reached. */
    private val coverage = object : DiagramDecorator {
        private val nodeHits = mapOf(
            "n00" to 5, "n01" to 5, "n02" to 5, "n03" to 4, "n04" to 3, "n05" to 7, "n06" to 7, "n07" to 3,
            "n08" to 3, "n09" to 3, "n12" to 1, "n30" to 4, "n31" to 4, "n60" to 2, "n61" to 2, "n50" to 2,
            "n51" to 2, "n52" to 2,
        )
        private val entryPointHits = mapOf("sig-paid" to 4, "upd-address" to 2, "val-address" to 2)

        override fun node(node: IrNode): Decoration = hits(nodeHits[node.id])

        override fun edge(edge: IrEdge): Decoration? {
            val from = nodeHits[edge.from] ?: entryPointHits[edge.from]
            val to = nodeHits[edge.to]
            return if (from == null || to == null) Decoration(Emphasis.DIMMED) else null
        }

        override fun entryPoint(entryPoint: EntryPoint): Decoration = hits(entryPointHits[entryPoint.id])

        private fun hits(count: Int?) = if (count == null) Decoration(Emphasis.DIMMED) else Decoration(note = "×$count")
    }

    /** The path of one execution: paid in time, two items shipped. */
    private val path = object : DiagramDecorator {
        private val taken = listOf("n00", "n01", "n02", "n03", "n04", "n05", "n06", "n07", "n08", "n09", "n30", "n31")

        override fun node(node: IrNode): Decoration? = Decoration(Emphasis.HIGHLIGHTED).takeIf { node.id in taken }

        override fun edge(edge: IrEdge): Decoration? =
            Decoration(Emphasis.HIGHLIGHTED).takeIf {
                (edge.from in taken || edge.from == "sig-paid") && edge.to in taken && edge.label != "cancelled"
            }

        override fun entryPoint(entryPoint: EntryPoint): Decoration? =
            Decoration(Emphasis.HIGHLIGHTED, note = "received").takeIf { entryPoint.id == "sig-paid" }
    }

    @Test
    fun `a coverage overlay greys out what was never hit and adds hit counts`() {
        val mermaid = MermaidRenderer.render(document, decorator = coverage)
        MermaidLint.check(mermaid)
        GoldenFiles.assertMatches("decorated/OrderWorkflow.coverage.mmd", mermaid)
    }

    @Test
    fun `an execution overlay highlights the path taken`() {
        val mermaid = MermaidRenderer.render(document, decorator = path)
        MermaidLint.check(mermaid)
        GoldenFiles.assertMatches("decorated/OrderWorkflow.path.mmd", mermaid)
    }

    @Test
    fun `an overlay adds to the page header`() {
        val overlay = DiagramOverlay(
            decorator = coverage,
            title = "OrderWorkflow: coverage",
            notices = listOf("Hit counts from 5 executions; *grey* was never reached."),
            facts = listOf(PageFact("Executions", "5"), PageFact("Unmapped events", "0")),
        )
        val page = MermaidFormat.prepare(document, PageOptions(indexFile = "index.md")).render(overlay)
        MermaidLint.diagramsIn(page).forEach(MermaidLint::check)
        GoldenFiles.assertMatches("decorated/OrderWorkflow.coverage.md", page)
    }

    @Test
    fun `decorations never change which statements are written or their order`() {
        val prepared = MermaidFormat.prepare(document)
        val plain = statements(prepared.render())
        for (decorator in listOf(coverage, path)) {
            assertEquals(plain, statements(prepared.render(DiagramOverlay(decorator))))
        }
    }

    /** The diagram reduced to its nodes, groups and edges, without labels and styling. */
    private fun statements(page: String): List<String> =
        MermaidLint.diagramsIn(page).single().lines()
            .map { it.trim() }
            .filterNot { it.startsWith("classDef ") || it.startsWith("class ") || it.startsWith("style ") || it.startsWith("linkStyle ") }
            .map { it.replace(Regex("\"[^\"]*\""), "\"\"").replace("|\"\"|", "") }
}
