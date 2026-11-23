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
    fun `only a node that runs an activity makes requests`() {
        val request = Request(kind = RequestKind.HTTP, method = "GET", target = "https://example.com", loc = SourceLocation("Orders.kt", 3))
        val onActivity = document.copy(nodes = document.nodes.map { if (it.id == "n05") it.copy(requests = listOf(request)) else it })
        val onBranch = document.copy(nodes = document.nodes.map { if (it.id == "n03") it.copy(requests = listOf(request)) else it })

        assertEquals(emptyList(), onActivity.validate())
        assertEquals(listOf("node 'n03' has requests, which only an activity makes"), onBranch.validate())
    }

    @Test
    fun `a request says what it sends and receives as its kind allows`() {
        val http = Request(kind = RequestKind.HTTP, method = "GET", target = "https://example.com", loc = SourceLocation("Orders.kt", 3))
        val client = Request(kind = RequestKind.CLIENT, client = "ServiceClient", call = "invoke", loc = SourceLocation("Orders.kt", 4))
        fun problems(request: Request) = document.copy(nodes = document.nodes.map { if (it.id == "n05") it.copy(requests = listOf(request)) else it }).validate()
        val at = { line: Int -> "node 'n05' has a request at Orders.kt:$line that " }

        assertEquals(emptyList(), problems(http.copy(sends = Payload(PayloadKind.TYPE, "a.B", format = PayloadFormat.JACKSON, naming = "SNAKE_CASE"), receives = Payload(PayloadKind.TEXT))))
        assertEquals(emptyList(), problems(client.copy(arguments = listOf(RequestArgument("payload", 1, "a.B")), constant = "a.C.D", receives = Payload(PayloadKind.UNKNOWN))))
        assertEquals(listOf(at(3) + "sends type with a type only where it is a type"), problems(http.copy(sends = Payload(PayloadKind.TYPE))))
        assertEquals(listOf(at(3) + "receives text with a type only where it is a type"), problems(http.copy(receives = Payload(PayloadKind.TEXT, "a.B"))))
        assertEquals(listOf(at(3) + "sends gives a naming strategy without Jackson"), problems(http.copy(sends = Payload(PayloadKind.TYPE, "a.B", format = PayloadFormat.KOTLINX, naming = "SNAKE_CASE"))))
        assertEquals(listOf(at(3) + "sends what is not read"), problems(http.copy(sends = Payload(PayloadKind.NOT_READ))))
        assertEquals(listOf(at(3) + "receives nothing, which is said as not read"), problems(http.copy(receives = Payload(PayloadKind.NONE))))
        assertEquals(
            listOf(at(4) + "is a call of a client and sends a body or has parameters of a target"),
            problems(client.copy(parameters = listOf(RequestParameter("id", ParameterLocation.PATH)))),
        )
        assertEquals(listOf(at(3) + "is an HTTP request and has arguments or a constant of a client"), problems(http.copy(constant = "a.C.D")))
        assertEquals(
            listOf(at(4) + "has two arguments at one place"),
            problems(client.copy(arguments = listOf(RequestArgument("a", 1), RequestArgument("b", 1)))),
        )
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
    fun `only an overview leaves calls out, each once, for one of its reasons and with a label`() {
        val call = LeftOutCall(SourceLocation("orders/src/main/kotlin/Order.kt", 12), UnknownReasons.UNSUPPORTED, "withRequestContext")
        val overview = document.copy(detail = Detail.OVERVIEW)

        assertEquals(emptyList(), overview.validate())
        assertEquals(emptyList(), document.copy(detail = Detail.FULL).validate())
        // Two calls on one line, and one call for two reasons, are each a call of their own.
        assertEquals(
            emptyList(),
            overview.copy(leftOutCalls = listOf(call, call.copy(label = "traced"), call.copy(reason = UnknownReasons.DYNAMIC_DISPATCH))).validate(),
        )
        for (full in listOf(document, document.copy(detail = Detail.FULL))) {
            assertEquals(listOf("calls are left out, which only an overview does"), full.copy(leftOutCalls = listOf(call)).validate())
        }
        assertEquals(
            listOf("call left out 'orders/src/main/kotlin/Order.kt:12 unsupported withRequestContext' is listed twice"),
            overview.copy(leftOutCalls = listOf(call, call)).validate(),
        )
        assertEquals(
            listOf(
                "call left out at 'orders/src/main/kotlin/Order.kt:0' has no line",
                "call left out at 'orders/src/main/kotlin/Order.kt:0' does not say what is called",
                "call left out at 'orders/src/main/kotlin/Order.kt:0' has the reason 'recursion', for which no call is left out",
            ),
            overview.copy(leftOutCalls = listOf(LeftOutCall(SourceLocation("orders/src/main/kotlin/Order.kt", 0), UnknownReasons.RECURSION, " "))).validate(),
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
