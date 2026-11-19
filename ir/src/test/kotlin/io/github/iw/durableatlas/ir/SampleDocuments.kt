package io.github.iw.durableatlas.ir

/** Documents shared by the tests of this module. */
object SampleDocuments {
    private const val SOURCE = "orders/workflows/src/main/kotlin/com/example/orders/OrderWorkflowImpl.kt"

    /**
     * An order workflow with a signal-gated await, a loop, an unresolved call and a signal handler.
     * Lists are deliberately not in canonical order.
     */
    val orderWorkflow: IrDocument = IrDocument(
        commit = "3f786850e387550fdab836ed7e6dc881de23001b",
        modelMode = ModelMode.GRADLE,
        workflowType = "OrderWorkflow",
        interfaceName = "com.example.orders.OrderWorkflow",
        implementation = "com.example.orders.OrderWorkflowImpl",
        module = ":orders:workflows",
        entryPoints = listOf(
            EntryPoint(
                id = "sig-cancel",
                kind = EntryPointKind.SIGNAL,
                name = "cancelOrder",
                start = "n20",
                writes = listOf("cancelled"),
            ),
            EntryPoint(id = "main", kind = EntryPointKind.WORKFLOW_METHOD, name = "OrderWorkflow", start = "n00"),
        ),
        nodes = listOf(
            IrNode(id = "n21", kind = NodeKind.END),
            IrNode(id = "n20", kind = NodeKind.START),
            IrNode(id = "n09", kind = NodeKind.END),
            IrNode(
                id = "n06",
                kind = NodeKind.UNKNOWN,
                reason = UnknownReasons.DYNAMIC_DISPATCH,
                candidates = listOf("com.example.orders.SmsNotifier", "com.example.orders.EmailNotifier"),
                loc = SourceLocation(SOURCE, 61),
            ),
            IrNode(id = "n05", kind = NodeKind.ACTIVITY, name = "ShipItem", parent = "n04", loc = SourceLocation(SOURCE, 56)),
            IrNode(id = "n04", kind = NodeKind.LOOP, label = "for each line item", loc = SourceLocation(SOURCE, 55)),
            IrNode(id = "n03", kind = NodeKind.BRANCH, loc = SourceLocation(SOURCE, 51)),
            IrNode(
                id = "n02",
                kind = NodeKind.AWAIT,
                timeout = "PT30M",
                reads = listOf("paid", "cancelled"),
                loc = SourceLocation(SOURCE, 47),
            ),
            IrNode(
                id = "n01",
                kind = NodeKind.ACTIVITY,
                name = "ReserveStock",
                taskQueue = "orders",
                loc = SourceLocation(SOURCE, 42),
            ),
            IrNode(id = "n00", kind = NodeKind.START),
        ),
        edges = listOf(
            IrEdge(from = "sig-cancel", to = "n02", kind = EdgeKind.WAKES),
            IrEdge(from = "n20", to = "n21"),
            IrEdge(from = "n06", to = "n09"),
            IrEdge(from = "n04", to = "n06"),
            IrEdge(from = "n03", to = "n09", label = "cancelled"),
            IrEdge(from = "n03", to = "n04", label = "paid"),
            IrEdge(from = "n02", to = "n09", kind = EdgeKind.TIMEOUT),
            IrEdge(from = "n02", to = "n03"),
            IrEdge(from = "n01", to = "n02"),
            IrEdge(from = "n00", to = "n01"),
        ),
    )

    /** One node of every kind, each with the properties that kind uses. */
    val everyNodeKind: IrDocument = run {
        val nodes = NodeKind.entries.mapIndexed { index, kind ->
            val base = IrNode(id = "k%02d".format(index), kind = kind, loc = SourceLocation("src/Sample.kt", index + 1))
            when (kind) {
                NodeKind.ACTIVITY -> base.copy(name = "Charge", taskQueue = "payments", timeout = "PT10S")
                NodeKind.LOCAL_ACTIVITY -> base.copy(name = "Validate")
                NodeKind.CHILD_WORKFLOW -> base.copy(
                    name = "ShipmentWorkflow",
                    interfaceName = "com.example.shipping.ShipmentWorkflow",
                    taskQueue = "shipping",
                    waitsFor = WaitsFor.START,
                    parentClosePolicy = ParentClosePolicy.ABANDON,
                )
                NodeKind.SIGNAL_EXTERNAL -> base.copy(name = "notify")
                NodeKind.NEXUS_OPERATION -> base.copy(name = "reserve", service = "InventoryService")
                NodeKind.TIMER -> base.copy(duration = "PT1H")
                NodeKind.AWAIT -> base.copy(reads = listOf("approved"), timeout = "P1D")
                NodeKind.VERSION_GATE -> base.copy(changeId = "add-fraud-check")
                NodeKind.LOOP -> base.copy(label = "retry on failure", retry = true)
                NodeKind.COMPENSATION -> base.copy(label = "refund")
                NodeKind.FAIL -> base.copy(name = "PaymentDeclined")
                NodeKind.UNKNOWN -> base.copy(reason = UnknownReasons.DEPTH_LIMIT)
                NodeKind.PARALLEL -> base.copy(join = Join.FIRST)
                NodeKind.CANCEL_SCOPE -> base.copy(label = "scope")
                NodeKind.CANCEL_EXTERNAL -> base.copy(name = "ShipmentWorkflow")
                NodeKind.UPSERT_SEARCH_ATTRIBUTES -> base.copy(keys = listOf("Stage", "Owner"))
                NodeKind.UPSERT_MEMO -> base.copy(keys = listOf("note"), otherKeys = true)
                else -> base
            }
        }
        IrDocument(
            commit = "89e6c98d92887913cadf06b2adb97f26cde4849b",
            modelMode = ModelMode.DEGRADED,
            workflowType = "EveryKind",
            interfaceName = "com.example.EveryKind",
            implementation = "com.example.EveryKindImpl",
            module = ":sample",
            entryPoints = listOf(
                EntryPoint(id = "main", kind = EntryPointKind.WORKFLOW_METHOD, name = "EveryKind", start = "k00"),
            ),
            nodes = nodes,
            edges = EdgeKind.entries.mapIndexed { index, kind ->
                // What cancels is the node where a scope is cancelled, and no other.
                val from = if (kind == EdgeKind.CANCELS) nodes.single { it.kind == NodeKind.CANCEL_SCOPE } else nodes[index]
                IrEdge(from = from.id, to = nodes[index + 1].id, kind = kind, label = "edge $index")
            },
        )
    }

    /**
     * Regions inside regions, and every kind of edge to the end of one: a loop of batches around
     * a loop of orders around a loop of items, a parallel section with a loop in a lane, and a
     * compensation with a loop in its chain.
     *
     * - `stock -no-> items`: to the end of the node's own region, the next item.
     * - `cancelled -yes-> orders`: to the end of the round of the loop around it, the next order.
     * - `full -yes-> batches`: past two loops, to the next batch.
     * - `answered -yes-> notify`: out of a loop to the end of its lane.
     * - `settled -yes-> undo`: out of a loop to the end of the chain of the compensation.
     */
    val nestedRegions: IrDocument = run {
        val nodes = listOf(
            IrNode(id = "start", kind = NodeKind.START),
            IrNode(id = "batches", kind = NodeKind.LOOP, label = "for each batch"),
            IrNode(id = "orders", kind = NodeKind.LOOP, label = "for each order", parent = "batches"),
            IrNode(id = "items", kind = NodeKind.LOOP, label = "for each item", parent = "orders"),
            IrNode(id = "cancelled", kind = NodeKind.BRANCH, label = "order cancelled?", parent = "items"),
            IrNode(id = "full", kind = NodeKind.BRANCH, label = "van full?", parent = "items"),
            IrNode(id = "stock", kind = NodeKind.BRANCH, label = "in stock?", parent = "items"),
            IrNode(id = "pick", kind = NodeKind.ACTIVITY, name = "PickItem", parent = "items"),
            IrNode(id = "notify", kind = NodeKind.PARALLEL),
            IrNode(id = "calls", kind = NodeKind.LOOP, label = "repeat 3 times", parent = "notify"),
            IrNode(id = "page", kind = NodeKind.ACTIVITY, name = "PageOnCall", parent = "calls"),
            IrNode(id = "answered", kind = NodeKind.BRANCH, label = "answered?", parent = "calls"),
            IrNode(id = "mail", kind = NodeKind.ACTIVITY, name = "SendMail", parent = "notify"),
            IrNode(id = "undo", kind = NodeKind.COMPENSATION),
            IrNode(id = "refunds", kind = NodeKind.LOOP, label = "for each payment", parent = "undo"),
            IrNode(id = "settled", kind = NodeKind.BRANCH, label = "settled?", parent = "refunds"),
            IrNode(id = "refund", kind = NodeKind.ACTIVITY, name = "RefundPayment", parent = "refunds"),
            IrNode(id = "failed", kind = NodeKind.FAIL, name = "DispatchFailed"),
            IrNode(id = "end", kind = NodeKind.END),
        )
        IrDocument(
            commit = "89e6c98d92887913cadf06b2adb97f26cde4849b",
            modelMode = ModelMode.GRADLE,
            workflowType = "NestedRegions",
            interfaceName = "com.example.NestedRegions",
            implementation = "com.example.NestedRegionsImpl",
            module = ":sample",
            entryPoints = listOf(
                EntryPoint(id = "main", kind = EntryPointKind.WORKFLOW_METHOD, name = "NestedRegions", start = "start"),
            ),
            nodes = nodes,
            edges = listOf(
                IrEdge(from = "start", to = "batches"),
                IrEdge(from = "cancelled", to = "orders", label = "yes"),
                IrEdge(from = "cancelled", to = "full", label = "no"),
                IrEdge(from = "full", to = "batches", label = "yes"),
                IrEdge(from = "full", to = "stock", label = "no"),
                IrEdge(from = "stock", to = "pick", label = "yes"),
                IrEdge(from = "stock", to = "items", label = "no"),
                IrEdge(from = "batches", to = "notify"),
                IrEdge(from = "page", to = "answered"),
                IrEdge(from = "answered", to = "notify", label = "yes"),
                IrEdge(from = "answered", to = "calls", label = "no"),
                IrEdge(from = "notify", to = "end"),
                IrEdge(from = "mail", to = "undo", kind = EdgeKind.EXCEPTION, label = "ActivityFailure"),
                IrEdge(from = "settled", to = "undo", label = "yes"),
                IrEdge(from = "settled", to = "refund", label = "no"),
                IrEdge(from = "undo", to = "failed"),
            ),
        )
    }
}
