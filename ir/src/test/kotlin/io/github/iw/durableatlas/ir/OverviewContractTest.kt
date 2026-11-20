package io.github.iw.durableatlas.ir

import io.github.iw.durableatlas.ir.testing.FlowWays
import io.github.iw.durableatlas.ir.testing.FlowWays.Place
import io.github.iw.durableatlas.ir.testing.OverviewContract
import kotlin.test.Test
import kotlin.test.assertEquals

/** What an overview has to keep of the full document, and the ways of a flow that the comparison goes by. */
class OverviewContractTest {
    private val file = "orders/src/main/kotlin/Orders.kt"

    private fun at(line: Int) = SourceLocation(file, line)

    /**
     * A step, a call that cannot be followed, a decision whose one way holds another such call,
     * a step, a call that runs one of two classes, the end.
     */
    private val full = IrDocument(
        commit = "89e6c98d92887913cadf06b2adb97f26cde4849b",
        modelMode = ModelMode.GRADLE,
        workflowType = "Orders",
        interfaceName = "com.example.Orders",
        implementation = "com.example.OrdersImpl",
        module = ":orders",
        entryPoints = listOf(EntryPoint(id = "main", kind = EntryPointKind.WORKFLOW_METHOD, name = "Orders", start = "start")),
        nodes = listOf(
            IrNode(id = "start", kind = NodeKind.START),
            IrNode(id = "reserve", kind = NodeKind.ACTIVITY, name = "Reserve", loc = at(10)),
            IrNode(id = "describe", kind = NodeKind.UNKNOWN, reason = UnknownReasons.UNSEEN_CODE, label = "Helper.describe", candidates = listOf("com.example.Mapper.write()"), loc = at(11)),
            IrNode(id = "gift", kind = NodeKind.BRANCH, label = "order.gift", loc = at(12)),
            IrNode(id = "remember", kind = NodeKind.UNKNOWN, reason = UnknownReasons.DYNAMIC_DISPATCH, loc = at(13)),
            IrNode(id = "charge", kind = NodeKind.ACTIVITY, name = "Charge", loc = at(14)),
            IrNode(id = "tell", kind = NodeKind.UNKNOWN, reason = UnknownReasons.DYNAMIC_DISPATCH, candidates = listOf("com.example.Mail", "com.example.Sms"), loc = at(15)),
            IrNode(id = "end", kind = NodeKind.END),
        ),
        edges = listOf(
            IrEdge("start", "reserve"),
            IrEdge("reserve", "describe"),
            IrEdge("describe", "gift"),
            IrEdge("gift", "remember", label = "yes"),
            IrEdge("gift", "charge", label = "no"),
            IrEdge("remember", "charge"),
            IrEdge("charge", "tell"),
            IrEdge("tell", "end"),
        ),
    ).normalized()

    private val gone = setOf("describe", "gift", "remember")

    private val overview = full.copy(
        detail = Detail.OVERVIEW,
        nodes = full.nodes.filter { it.id !in gone }.map { if (it.id == "tell") it.copy(label = "Notifier.send") else it },
        edges = listOf(IrEdge("start", "reserve"), IrEdge("reserve", "charge"), IrEdge("charge", "tell"), IrEdge("tell", "end")),
        stats = IrStats(nodes = 5, unknown = 1),
        leftOutCalls = listOf(
            LeftOutCall(at(11), UnknownReasons.UNSEEN_CODE, "Helper.describe", listOf("com.example.Mapper.write()")),
            LeftOutCall(at(13), UnknownReasons.DYNAMIC_DISPATCH, "Repository.save"),
        ),
    ).normalized()

    private fun violations(changed: IrDocument): List<String> = OverviewContract.violations(full, changed.copy(stats = IrStats.of(changed.nodes)))

    @Test
    fun `an overview that leaves out what it may and lists it keeps the contract`() {
        assertEquals(emptyList(), overview.validate())
        assertEquals(emptyList(), OverviewContract.violations(full, overview))
        assertEquals(true, OverviewContract.differs(full, overview))
        // One that left nothing out differs in its name alone.
        val same = full.copy(detail = Detail.OVERVIEW, nodes = full.nodes.map { if (it.id == "tell") it.copy(label = "Notifier.send") else it })
        assertEquals(false, OverviewContract.differs(full, full.copy(detail = Detail.OVERVIEW)))
        assertEquals(true, OverviewContract.differs(full, same))
    }

    @Test
    fun `an overview says that it is one, and differs in nothing but its nodes, its edges and what it lists`() {
        assertEquals(listOf("the overview does not say that it is one"), violations(overview.copy(detail = null)).take(1))
        assertEquals(
            listOf("the overview differs from the full document in more than its nodes and edges"),
            violations(overview.copy(module = ":other")),
        )
        assertEquals(
            listOf("the full document says what only an overview does"),
            OverviewContract.violations(full.copy(detail = Detail.FULL), overview).take(1),
        )
    }

    @Test
    fun `every node of an overview is the node of the full document, and one that could not be read names its call`() {
        val renamed = overview.copy(nodes = overview.nodes.map { if (it.id == "charge") it.copy(name = "Pay") else it })
        assertEquals(
            listOf("activity Pay at $file:14 [charge] is not as in the full document: activity Charge at $file:14 [charge]"),
            violations(renamed),
        )
        val added = overview.copy(nodes = overview.nodes + IrNode(id = "audit", kind = NodeKind.ACTIVITY, name = "Audit", loc = at(16)), edges = overview.edges + IrEdge("tell", "audit"))
        assertEquals("activity Audit at $file:16 [audit] is not in the full document", violations(added).first())
        val unnamed = overview.copy(nodes = overview.nodes.map { if (it.id == "tell") it.copy(label = null) else it })
        assertEquals(listOf("unknown dynamic-dispatch at $file:15 [tell] does not name its call"), violations(unnamed))
    }

    @Test
    fun `a step is never gone, and neither is a call behind which a step is certain`() {
        val without = overview.copy(nodes = overview.nodes.filter { it.id != "charge" }, edges = listOf(IrEdge("start", "reserve"), IrEdge("reserve", "tell"), IrEdge("tell", "end")))
        assertEquals("activity Charge at $file:14 [charge] is gone", violations(without).first())
        val untold = overview.copy(nodes = overview.nodes.filter { it.id != "tell" }, edges = listOf(IrEdge("start", "reserve"), IrEdge("reserve", "charge"), IrEdge("charge", "end")))
        assertEquals("unknown dynamic-dispatch at $file:15 [tell] is gone, and a step is certain behind it", violations(untold).first())
    }

    @Test
    fun `what is gone is listed as left out, and nothing else is`() {
        assertEquals(
            listOf("unknown dynamic-dispatch at $file:13 [remember] is gone and not listed as left out"),
            violations(overview.copy(leftOutCalls = overview.leftOutCalls.filter { it.reason == UnknownReasons.UNSEEN_CODE })),
        )
        val more = overview.copy(leftOutCalls = overview.leftOutCalls + LeftOutCall(at(14), UnknownReasons.UNSUPPORTED, "traced"))
        assertEquals(listOf("unsupported traced at $file:14 is listed as left out, and no such node is gone"), violations(more))
    }

    @Test
    fun `no way leads from a step to the next that the full document does not have, and every step is still reached`() {
        // Past the first step: in the full document nothing gets to the second without it.
        val shortcut = overview.copy(edges = overview.edges + IrEdge("start", "charge"))
        assertEquals(
            listOf("a way from start [start] to activity Charge at $file:14 [charge] that the full document does not have"),
            violations(shortcut),
        )
        val cut = overview.copy(edges = overview.edges.filter { it.from != "reserve" })
        assertEquals(
            listOf(
                "activity Charge at $file:14 [charge] is reached from main in the full document and not in the overview",
                "end [end] is reached from main in the full document and not in the overview",
            ),
            violations(cut).sorted(),
        )
    }

    @Test
    fun `a region that is gone held no step, and an edge that is no part of the flow stays between the nodes that are left`() {
        val nested = SampleDocuments.nestedRegions
        // The loop of calls is gone, and the activity it holds is not.
        val emptied = nested.copy(detail = Detail.OVERVIEW, nodes = nested.nodes.filter { it.id != "calls" }.map { if (it.parent == "calls") it.copy(parent = "notify") else it })
        assertEquals(
            listOf("loop repeat 3 times [calls] is gone and held 2 node(s) that are still there"),
            OverviewContract.violations(nested, emptied.copy(stats = IrStats.of(emptied.nodes), edges = emptied.edges.filter { it.to != "calls" })).filter { "is gone" in it },
        )
        val order = SampleDocuments.orderWorkflow.normalized()
        val woken = order.copy(detail = Detail.OVERVIEW, edges = order.edges.filter { it.kind != EdgeKind.WAKES })
        assertEquals(
            listOf("unknown dynamic-dispatch at orders/workflows/src/main/kotlin/com/example/orders/OrderWorkflowImpl.kt:61 [n06] does not name its call", "edge sig-cancel -> n02 (WAKES) of the full document is missing"),
            OverviewContract.violations(order, woken),
        )
    }

    @Test
    fun `the ways of a flow are its edges and what its regions imply`() {
        val ways = FlowWays(SampleDocuments.nestedRegions)
        fun next(id: String, done: Boolean = false): Set<Place> = ways.next(Place(id, done)).toSet()

        // A region is entered at the children nothing inside it leads to: the loop of batches at the loop of orders.
        assertEquals(setOf(Place("orders")), next("batches"))
        assertEquals(setOf(Place("cancelled")), next("items"))
        // An edge to a region around the node leads to the end of it: of a loop, to its beginning and to where it is done.
        assertEquals(setOf(Place("orders"), Place("orders", done = true), Place("full")), next("cancelled"))
        // A node whose edges do not say where it goes leads to the end of its own region.
        assertEquals(setOf(Place("items"), Place("items", done = true)), next("pick"))
        // So does a region inside a region, once it is done.
        assertEquals(setOf(Place("orders"), Place("orders", done = true)), next("items", done = true))
        // The lanes of a parallel region are entered together, and each ends where the region is done.
        assertEquals(setOf(Place("calls"), Place("mail")), next("notify"))
        assertEquals(setOf(Place("notify", done = true), Place("undo")), next("mail"))
        assertEquals(setOf(Place("end")), next("notify", done = true))
        // What ends the flow leads nowhere.
        assertEquals(emptySet(), next("failed"))
        assertEquals(SampleDocuments.nestedRegions.nodes.map { it.id }.toSet(), ways.reach(listOf(Place("start"))).map { it.id }.toSet())
    }
}
