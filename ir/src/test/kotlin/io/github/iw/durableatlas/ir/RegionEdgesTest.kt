package io.github.iw.durableatlas.ir

import io.github.iw.durableatlas.ir.testing.RegionEdges
import kotlin.test.Test
import kotlin.test.assertEquals

/** The convention for edges between a region and what lies inside it. */
class RegionEdgesTest {
    private val nested = SampleDocuments.nestedRegions

    private fun with(vararg edges: IrEdge): IrDocument = nested.copy(edges = nested.edges + edges)

    @Test
    fun `a node may lead to the end of its own region and of every region further out`() {
        assertEquals(emptyList(), RegionEdges.violations(nested))

        // One, two and three regions out, as flow and as the way a failure takes.
        val toEnds = with(
            IrEdge("pick", "items"),
            IrEdge("pick", "orders", label = "skipped"),
            IrEdge("pick", "batches", EdgeKind.EXCEPTION, "ActivityFailure"),
            // A region is a node of the region around it, and may lead to the end of that one too.
            IrEdge("items", "orders"),
            IrEdge("items", "batches", EdgeKind.EXCEPTION, "ActivityFailure"),
            IrEdge("refund", "undo"),
            IrEdge("page", "notify", EdgeKind.EXCEPTION, "ActivityFailure"),
        )
        assertEquals(emptyList(), RegionEdges.violations(toEnds))
    }

    @Test
    fun `no edge leads from a region to a node inside it, however deep`() {
        val broken = with(IrEdge("items", "cancelled"), IrEdge("batches", "pick", label = "first"), IrEdge("notify", "page"))

        assertEquals(
            listOf(
                "edge 'items' -> 'cancelled' leads from a region to a node inside it",
                "edge 'batches' -> 'pick' leads from a region to a node inside it",
                "edge 'notify' -> 'page' leads from a region to a node inside it",
            ),
            RegionEdges.violations(broken),
        )
    }

    @Test
    fun `an edge that leaves a region ends at a node around it, not inside another region`() {
        // Out of the loops to a step after them, to another region, and to a node of a region further out.
        val leaving = with(IrEdge("pick", "end"), IrEdge("pick", "notify"), IrEdge("pick", "items"), IrEdge("full", "orders"))
        assertEquals(emptyList(), RegionEdges.violations(leaving))

        val entering = with(IrEdge("start", "orders"), IrEdge("pick", "page"), IrEdge("mail", "refund", EdgeKind.EXCEPTION, "ActivityFailure"))
        assertEquals(
            listOf(
                "edge 'start' -> 'orders' leads into a region from outside it",
                "edge 'pick' -> 'page' leads into a region from outside it",
                "edge 'mail' -> 'refund' leads into a region from outside it",
            ),
            RegionEdges.violations(entering),
        )
    }

    @Test
    fun `a handler wakes an await wherever it is`() {
        val waiting = nested.copy(
            entryPoints = nested.entryPoints + EntryPoint("signal-go", EntryPointKind.SIGNAL, "go", start = "go.start", writes = listOf("ready")),
            nodes = nested.nodes + IrNode("go.start", NodeKind.START) + IrNode("wait", NodeKind.AWAIT, reads = listOf("ready"), parent = "items"),
            edges = nested.edges + IrEdge("signal-go", "wait", EdgeKind.WAKES),
            stats = IrStats.of(nested.nodes + IrNode("go.start", NodeKind.START) + IrNode("wait", NodeKind.AWAIT)),
        )

        assertEquals(emptyList(), waiting.validate())
        assertEquals(emptyList(), RegionEdges.violations(waiting))
    }

    @Test
    fun `a cancel point cancels a step wherever it is, also one of a region it stands in itself`() {
        val stop = IrNode("stop", NodeKind.CANCEL_SCOPE, label = "scope", parent = "items")
        val cancelling = nested.copy(
            nodes = nested.nodes + stop,
            // Into another region, and to a region around the cancel point: neither is a way the flow takes.
            edges = nested.edges + IrEdge("stop", "page", EdgeKind.CANCELS) + IrEdge("stop", "orders", EdgeKind.CANCELS),
            stats = IrStats.of(nested.nodes + stop),
        )

        assertEquals(emptyList(), cancelling.validate())
        assertEquals(emptyList(), RegionEdges.violations(cancelling))
    }
}
