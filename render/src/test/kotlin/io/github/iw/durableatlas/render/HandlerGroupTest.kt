package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.IrStats
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HandlerGroupTest {
    private val order = MermaidRenderer.render(TestDocuments.load(TestDocuments.ORDER))

    @Test
    fun `an update and its validator share one handler box`() {
        assertContains(order, "subgraph h_upd_address [\"update + validator changeAddress · reads#58; status · writes#58; address\"]")
        assertFalse("h_val_address" in order)
        val box = order.substringAfter("subgraph h_upd_address").substringBefore("        end\n")
        assertEquals(
            listOf(
                "n60([\"start<br/>validator\"])",
                "n61([\"end\"])",
                "n50([\"start<br/>update\"])",
                "n51[\"ValidateAddress\"]",
                "n52([\"end\"])",
            ),
            box.lines().drop(1).map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `every other handler has a box of its own, inside the handlers box`() {
        val titles = Regex("(?m)^        subgraph \\w+ \\[\"(.*)\"]$").findAll(order).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "signal cancelOrder · writes#58; cancelled",
                "signal paymentReceived · writes#58; paid",
                "query getStatus · reads#58; status",
                "update + validator changeAddress · reads#58; status · writes#58; address",
            ),
            titles,
        )
        assertContains(order, "    subgraph handlers [\"handlers\"]\n")
    }

    @Test
    fun `an update without validator and a validator without update stay on their own`() {
        val document = TestDocuments.load(TestDocuments.INVOICE)
        val extended = document.copy(
            entryPoints = document.entryPoints + listOf(
                EntryPoint("upd", EntryPointKind.UPDATE, "rename", "n10"),
                EntryPoint("val", EntryPointKind.UPDATE_VALIDATOR, "other", "n20"),
            ),
            nodes = document.nodes + listOf(
                IrNode("n10", NodeKind.START),
                IrNode("n11", NodeKind.END),
                IrNode("n20", NodeKind.START),
                IrNode("n21", NodeKind.END),
            ),
            edges = document.edges + listOf(IrEdge("n10", "n11"), IrEdge("n20", "n21")),
            stats = IrStats(nodes = 8, unknown = 1),
        )
        val mermaid = MermaidRenderer.render(extended)
        MermaidLint.check(mermaid)
        assertContains(mermaid, "subgraph h_upd [\"update rename\"]")
        assertContains(mermaid, "subgraph h_val [\"update validator other\"]")
        assertContains(mermaid, "n10([\"start\"])")
        assertContains(mermaid, "n20([\"start\"])")
    }
}
