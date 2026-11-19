package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import io.github.iw.durableatlas.render.svg.SvgDocuments
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Mermaid diagrams of the documents the analyser's tests expect for workflows that take steps
 * while their object is made, register handlers as they run, compensate side by side and retry.
 */
class ConstructsDiagramsTest {
    private val documents: Map<String, IrDocument> =
        SvgDocuments.all().filterKeys { it.startsWith("analyser/constructs/") }.mapKeys { it.key.substringAfterLast('/') }

    private fun IrDocument.step(name: String): String = nodes.single { it.name == name }.id

    @Test
    fun `every document is drawn as a diagram that parses`() {
        assertEquals(12, documents.size)
        for (document in documents.values) MermaidLint.check(MermaidRenderer.render(document))
    }

    @Test
    fun `a compensation that runs side by side holds a fork with a lane for each of its steps`() {
        val document = documents.getValue("SideBySide")
        val mermaid = MermaidRenderer.render(document)
        val compensation = document.nodes.single { it.kind == NodeKind.COMPENSATION }.id
        val fork = document.nodes.single { it.kind == NodeKind.PARALLEL }.id

        assertContains(mermaid, "    $compensation --> $fork\n")
        assertEquals(3, Regex("subgraph ${fork}_lane\\d").findAll(mermaid).count())
        for (last in listOf("CleanUp", "Undo", "Release")) assertContains(mermaid, "    ${document.step(last)} --> ${fork}_join\n")
        assertContains(mermaid, "    ${document.step("Report")} --> ${document.step("Undo")}\n")
        // The lanes lie inside the compensation, which fails as one.
        assertContains(mermaid, "    $compensation --> ${document.nodes.single { it.kind == NodeKind.FAIL }.id}\n")
        assertEquals(1, mermaid.lines().count { it.trim().startsWith("$compensation -->") && fork !in it })
    }

    @Test
    fun `a retry is a loop that is called what it is, and its last step goes round`() {
        val document = documents.getValue("Retries")
        val mermaid = MermaidRenderer.render(document)
        val loops = document.nodes.filter { it.kind == NodeKind.LOOP }

        assertEquals(listOf("retry on failure", "retry on failure, up to 3 attempts, for up to 10m"), loops.mapNotNull { it.label }.sorted())
        for (loop in loops) assertContains(mermaid, "subgraph ${loop.id}_region [\"${loop.label}\"]")
        val work = document.nodes.single { it.name == "Work" }
        assertContains(mermaid, "    ${work.id} -->|\"repeat\"| ${work.parent}\n")
        // Nothing leads past what is retried: the start has one edge, into the first loop.
        assertEquals(1, mermaid.lines().count { it.trim().startsWith("${document.entryPoints.first().start} -->") })
    }

    @Test
    fun `a handler for every name is drawn with the other handlers and says what it takes`() {
        val fallback = MermaidRenderer.render(documents.getValue("dynamic-Fallback"))
        assertContains(fallback, "subgraph h_dynamic_signal [\"signal (any name) · writes#58; note\"]")
        assertContains(fallback, "subgraph h_dynamic_query [\"query (any name) · reads#58; note\"]")

        val listeners = MermaidRenderer.render(documents.getValue("Listeners"))
        assertContains(listeners, "subgraph h_dynamic_update [\"update (any name) · reads#58; extra · writes#58; extra\"]")
        assertContains(listeners, "subgraph h_update_resume [\"update + validator resume · writes#58; panel\"]")
    }

    @Test
    fun `the edges out of an await carry what the test after it asks for`() {
        val document = documents.getValue("QueueLoop")
        val mermaid = MermaidRenderer.render(document)
        val await = document.nodes.single { it.kind == NodeKind.AWAIT }.id

        val end = document.edges.single { it.from == await && it.label == "queue.isEmpty() and done" }.to
        assertEquals(NodeKind.END, document.nodes.single { it.id == end }.kind)
        assertContains(mermaid, "    $await -->|\"not queue.isEmpty()\"| ${document.step("Work")}\n")
        assertContains(mermaid, "    $await -->|\"queue.isEmpty() and done\"| $end\n")
    }
}
