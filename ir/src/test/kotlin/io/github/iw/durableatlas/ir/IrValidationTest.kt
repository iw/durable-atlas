package io.github.iw.durableatlas.ir

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class IrValidationTest {
    private val document = SampleDocuments.orderWorkflow

    @Test
    fun `sample documents are consistent`() {
        assertEquals(emptyList(), document.validate())
        assertEquals(emptyList(), SampleDocuments.everyNodeKind.validate())
        assertEquals(emptyList(), SampleDocuments.nestedRegions.validate())
    }

    @Test
    fun `an edge may lead to any region around its source, and the convention for regions is not checked here`() {
        val nested = SampleDocuments.nestedRegions
        // To the node's own region, to the one around that, and to the outermost of three.
        val toEnds = listOf(IrEdge("pick", "items"), IrEdge("pick", "orders"), IrEdge("pick", "batches", EdgeKind.EXCEPTION, "ActivityFailure"))
        assertEquals(emptyList(), nested.copy(edges = nested.edges + toEnds).validate())

        // Documents from before the convention lead from a region to its children, and stay readable.
        assertEquals(emptyList(), nested.copy(edges = nested.edges + IrEdge("items", "cancelled")).validate())
    }

    @Test
    fun `duplicate ids are reported`() {
        val duplicated = document.copy(
            nodes = document.nodes + IrNode("n01", NodeKind.TIMER) + IrNode("main", NodeKind.TIMER),
            entryPoints = document.entryPoints + document.entryPoints.first(),
            stats = IrStats(nodes = 12, unknown = 1),
        )

        assertEquals(
            listOf(
                "duplicate node id 'n01'",
                "duplicate entry point id 'sig-cancel'",
                "id 'main' is used by both a node and an entry point",
            ),
            duplicated.validate(),
        )
    }

    @Test
    fun `dangling references are reported`() {
        val dangling = document.copy(
            entryPoints = listOf(
                EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, "OrderWorkflow", start = "missing"),
                EntryPoint("sig-cancel", EntryPointKind.SIGNAL, "cancelOrder", start = "n01"),
            ),
            edges = listOf(IrEdge("n00", "nowhere"), IrEdge("nowhere", "n01"), IrEdge("n01", "sig-cancel")),
        )

        assertEquals(
            listOf(
                "entry point 'main' starts at missing node 'missing'",
                "entry point 'sig-cancel' starts at 'n01', which is not a start node",
                "edge 'n00' -> 'nowhere' ends at a missing node",
                "edge 'nowhere' -> 'n01' starts at a missing node or entry point",
                "edge 'n01' -> 'sig-cancel' ends at a missing node",
            ),
            dangling.validate(),
        )
    }

    @Test
    fun `parents must be existing containers without cycles`() {
        val nodes = listOf(
            IrNode("a", NodeKind.LOOP, parent = "b"),
            IrNode("b", NodeKind.PARALLEL, parent = "a"),
            IrNode("c", NodeKind.ACTIVITY, name = "C", parent = "gone"),
            IrNode("d", NodeKind.ACTIVITY, name = "D", parent = "c"),
            IrNode("e", NodeKind.ACTIVITY, name = "E", parent = "a"),
        )
        val broken = document.copy(entryPoints = emptyList(), nodes = nodes, edges = emptyList(), stats = IrStats.of(nodes))

        assertEquals(
            listOf(
                "node 'a' is its own ancestor",
                "node 'b' is its own ancestor",
                "node 'c' has missing parent 'gone'",
                "node 'd' has parent 'c', which is not a container",
            ),
            broken.validate(),
        )
    }

    @Test
    fun `stats must agree with the nodes`() {
        val problems = document.copy(stats = IrStats(nodes = 3, unknown = 0)).validate()

        assertContains(problems.single(), "stats are IrStats(nodes=3, unknown=0)")
    }

    @Test
    fun `only an upsert writes keys, and only a loop is a retry`() {
        val nodes = document.nodes
        val keyed = document.copy(nodes = nodes.map { if (it.id == "n01") it.copy(keys = listOf("Stage")) else it })
        val retried = document.copy(nodes = nodes.map { if (it.id == "n01") it.copy(retry = true) else it })

        assertEquals(listOf("node 'n01' has keys, which only an upsert of search attributes or of the memo writes"), keyed.validate())
        assertEquals(listOf("node 'n01' is marked as a retry, which only a loop can be"), retried.validate())
    }

    @Test
    fun `only a parallel region says which lanes are waited for, and only an upsert with names that it writes more`() {
        val nodes = document.nodes
        val joined = document.copy(nodes = nodes.map { if (it.id == "n01") it.copy(join = Join.FIRST) else it })
        val more = document.copy(nodes = nodes.map { if (it.id == "n01") it.copy(otherKeys = true) else it })
        val region = IrNode("n30", NodeKind.PARALLEL, join = Join.FIRST, waitsFor = WaitsFor.NOTHING)
        val both = document.copy(nodes = nodes + region, edges = document.edges + IrEdge("n06", "n30"), stats = IrStats.of(nodes + region))

        assertEquals(listOf("node 'n01' says which lanes are waited for, which only a parallel region can"), joined.validate())
        assertEquals(listOf("node 'n01' is said to write more than its keys, and has none"), more.validate())
        assertEquals(listOf("node 'n30' says which lanes are waited for, and that none is"), both.validate())
        assertEquals(emptyList(), both.copy(nodes = nodes + region.copy(waitsFor = null)).validate())
    }

    @Test
    fun `only a document of a degraded model skips calls, each once and with its text`() {
        val call = SkippedCall(SourceLocation("orders/src/main/kotlin/Logs.kt", 9), "log.info { message }")
        val degraded = document.copy(modelMode = ModelMode.DEGRADED)

        assertEquals(emptyList(), degraded.copy(skippedCalls = listOf(call, call.copy(text = "log.atInfo()"))).validate())
        assertEquals(
            listOf("calls are skipped, which only a document of a degraded model does"),
            document.copy(skippedCalls = listOf(call)).validate(),
        )
        assertEquals(
            listOf("skipped call 'orders/src/main/kotlin/Logs.kt:9 log.info { message }' is listed twice"),
            degraded.copy(skippedCalls = listOf(call, call)).validate(),
        )
        assertEquals(
            listOf("skipped call at 'orders/src/main/kotlin/Logs.kt:0' has no line", "skipped call at 'orders/src/main/kotlin/Logs.kt:0' has no text"),
            degraded.copy(skippedCalls = listOf(SkippedCall(SourceLocation("orders/src/main/kotlin/Logs.kt", 0), " "))).validate(),
        )
    }

    @Test
    fun `a node for a call behind which something cannot be followed names the call and what cannot be followed`() {
        val unseen = IrNode(
            id = "n30",
            kind = NodeKind.UNKNOWN,
            reason = UnknownReasons.UNSEEN_CODE,
            label = "Events.enriched",
            candidates = listOf("com.example.orders.Enricher.enrich(com.example.orders.Event)"),
        )
        fun with(node: IrNode): IrDocument =
            document.copy(nodes = document.nodes + node, edges = document.edges + IrEdge("n06", "n30"), stats = IrStats.of(document.nodes + node))
        val problem = "node 'n30' stands for a call behind which something cannot be followed, and does not name the call or what cannot be followed"

        assertEquals(emptyList(), with(unseen).validate())
        assertEquals(listOf(problem), with(unseen.copy(label = null)).validate())
        assertEquals(listOf(problem), with(unseen.copy(candidates = emptyList())).validate())
        // A call that the code which is drawn makes itself needs neither: its place says which call it is.
        assertEquals(emptyList(), with(unseen.copy(reason = UnknownReasons.DYNAMIC_DISPATCH, label = null, candidates = emptyList())).validate())
    }

    @Test
    fun `only the node where a scope is cancelled cancels anything`() {
        val cancelling = document.copy(edges = document.edges + IrEdge("n01", "n05", EdgeKind.CANCELS))

        assertEquals(listOf("cancels edge 'n01' -> 'n05' does not start at a cancelScope node"), cancelling.validate())
    }
}
