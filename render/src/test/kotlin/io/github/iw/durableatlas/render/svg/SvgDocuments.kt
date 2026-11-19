package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.TestDocuments
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** The workflow documents the SVG tests draw. */
object SvgDocuments {
    /** The worked example of the demo project. */
    val demo: Path = Path.of("..", "demo", "expected", "OrderWorkflow.ir.json")

    /** The documents the analyser's tests expect for the sample repository. */
    private val analysed: Path = Path.of("..", "analyser", "src", "test", "resources", "golden", "ir")

    /**
     * Documents in which the label of an edge once had no place: a call that may throw, or a step
     * that may fail, with a clause below it that runs a step, where what stands beside the two
     * leaves no room for the type that is caught; and the same clauses where there is room, as
     * regions, several to one call, in a lane, in a loop and in what a handler runs.
     */
    val labelled: Path = Path.of("src", "test", "resources", "ir-labels")

    fun demo(): IrDocument = IrJson.decode(Files.readString(demo))

    /** The documents of [labelled], by the name of each file. */
    fun labelled(): Map<String, IrDocument> = Files.list(labelled).use { paths ->
        paths.filter { it.name.endsWith(".ir.json") }.sorted().toList().associate { it.name.removeSuffix(".ir.json") to IrJson.decode(Files.readString(it)) }
    }

    /** Every document, under a name that tells where it comes from. */
    fun all(): Map<String, IrDocument> {
        val documents = LinkedHashMap<String, IrDocument>()
        TestDocuments.all.forEach { documents["render/$it"] = TestDocuments.load(it) }
        documents["demo/OrderWorkflow"] = demo()
        Files.walk(analysed).use { paths ->
            paths.filter { it.isRegularFile() && it.name.endsWith(".ir.json") }.sorted().forEach {
                documents["analyser/${analysed.relativize(it).toString().removeSuffix(".ir.json")}"] = IrJson.decode(Files.readString(it))
            }
        }
        documents["made/saga"] = saga(12)
        documents["made/skipping"] = skipping()
        documents["made/diamond"] = diamond()
        documents["made/region-ends"] = regionEnds()
        documents["made/outer-ends"] = outerEnds()
        documents["made/lane-jumps"] = laneJumps()
        documents["made/handler-steps"] = handlerSteps()
        documents["made/region-arms"] = regionArms()
        documents["made/region-pairs"] = regionPairs()
        documents["made/woken-loops"] = wokenLoops()
        documents["made/handler-ends"] = handlerEnds()
        documents["made/self-woken"] = selfWoken()
        documents["made/mid-lane"] = midLane()
        documents["made/long-labels-demo"] = longLabels(demo())
        documents["made/long-labels-arms"] = longLabels(regionArms())
        documents["made/long-labels-pairs"] = longLabels(regionPairs())
        labelled().forEach { (name, document) -> documents["labels/$name"] = document }
        return documents
    }

    /** A branch whose two arms are a step each and meet again, followed by a branch between two ways to end. */
    fun diamond(): IrDocument {
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("express", NodeKind.BRANCH, label = "express?"),
            IrNode("courier", NodeKind.ACTIVITY, name = "BookCourier"),
            IrNode("post", NodeKind.ACTIVITY, name = "PostParcel"),
            IrNode("track", NodeKind.ACTIVITY, name = "TrackParcel"),
            IrNode("more", NodeKind.BRANCH, label = "more parcels?"),
            IrNode("again", NodeKind.CONTINUE_AS_NEW),
            IrNode("end", NodeKind.END),
        )
        val edges = listOf(
            IrEdge("start", "express"),
            IrEdge("express", "courier", label = "yes"),
            IrEdge("express", "post", label = "no"),
            IrEdge("courier", "track"),
            IrEdge("post", "track"),
            IrEdge("track", "more"),
            IrEdge("more", "again", label = "yes"),
            IrEdge("more", "end", label = "no"),
        )
        return document("DiamondWorkflow", nodes, edges)
    }

    /**
     * Steps that lead to the end of the region around them, in every kind of region: a loop
     * whose round is cut short from two places and left early from a third, parallel lanes that
     * end at a branch, and a compensation that skips its last step.
     */
    fun regionEnds(): IrDocument {
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("orders", NodeKind.LOOP, label = "for each order"),
            IrNode("skip", NodeKind.BRANCH, label = "already shipped?", parent = "orders"),
            IrNode("pick", NodeKind.ACTIVITY, name = "PickItems", parent = "orders"),
            IrNode("short", NodeKind.BRANCH, label = "items missing?", parent = "orders"),
            IrNode("pack", NodeKind.ACTIVITY, name = "PackParcel", parent = "orders"),
            IrNode("full", NodeKind.BRANCH, label = "van full?", parent = "orders"),
            IrNode("notify", NodeKind.PARALLEL),
            IrNode("urgent", NodeKind.BRANCH, label = "urgent?", parent = "notify"),
            IrNode("page", NodeKind.ACTIVITY, name = "PageOnCall", parent = "notify"),
            IrNode("mail", NodeKind.ACTIVITY, name = "SendMail", parent = "notify"),
            IrNode("copy", NodeKind.BRANCH, label = "archive copy?", parent = "notify"),
            IrNode("archive", NodeKind.ACTIVITY, name = "ArchiveMail", parent = "notify"),
            IrNode("dispatch", NodeKind.ACTIVITY, name = "DispatchVan"),
            IrNode("undo", NodeKind.COMPENSATION),
            IrNode("recall", NodeKind.ACTIVITY, name = "RecallVan", parent = "undo"),
            IrNode("charged", NodeKind.BRANCH, label = "customer charged?", parent = "undo"),
            IrNode("refund", NodeKind.ACTIVITY, name = "RefundPayment", parent = "undo"),
            IrNode("failed", NodeKind.FAIL, name = "DispatchFailed"),
            IrNode("end", NodeKind.END),
        )
        val edges = listOf(
            IrEdge("start", "orders"),
            IrEdge("skip", "orders", label = "yes"),
            IrEdge("skip", "pick", label = "no"),
            IrEdge("pick", "short"),
            IrEdge("short", "orders", label = "yes"),
            IrEdge("short", "pack", label = "no"),
            IrEdge("pack", "full"),
            IrEdge("full", "notify", label = "yes"),
            IrEdge("full", "orders", label = "no"),
            IrEdge("orders", "notify"),
            IrEdge("urgent", "page", label = "yes"),
            IrEdge("urgent", "notify", label = "no"),
            IrEdge("mail", "copy"),
            IrEdge("copy", "archive", label = "yes"),
            IrEdge("copy", "notify", label = "no"),
            IrEdge("notify", "dispatch"),
            IrEdge("dispatch", "end"),
            IrEdge("dispatch", "undo", EdgeKind.EXCEPTION, "ActivityFailure"),
            IrEdge("recall", "charged"),
            IrEdge("charged", "refund", label = "yes"),
            IrEdge("charged", "undo", label = "no"),
            IrEdge("undo", "failed"),
        )
        return document("RegionEndsWorkflow", nodes, edges)
    }

    /**
     * Steps that lead to the end of a region further out than their own. In a loop of batches
     * around a loop of orders around a loop of items, a step and the failure of another go on
     * with the next order, and the arm of a branch with the next batch; the arm of one more
     * branch, for comparison, goes on with the next item. A loop in a lane of a parallel section
     * is left for the end of the lane, and a loop in a compensation for the end of the
     * compensation.
     */
    fun outerEnds(): IrDocument {
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("batches", NodeKind.LOOP, label = "for each batch"),
            IrNode("load", NodeKind.ACTIVITY, name = "LoadBatch", parent = "batches"),
            IrNode("orders", NodeKind.LOOP, label = "for each order", parent = "batches"),
            IrNode("reserve", NodeKind.ACTIVITY, name = "ReserveOrder", parent = "orders"),
            IrNode("items", NodeKind.LOOP, label = "for each item", parent = "orders"),
            IrNode("cancelled", NodeKind.BRANCH, label = "order cancelled?", parent = "items"),
            IrNode("close", NodeKind.ACTIVITY, name = "CloseOrder", parent = "items"),
            IrNode("full", NodeKind.BRANCH, label = "van full?", parent = "items"),
            IrNode("stock", NodeKind.BRANCH, label = "in stock?", parent = "items"),
            IrNode("pick", NodeKind.ACTIVITY, name = "PickItem", parent = "items"),
            IrNode("notify", NodeKind.PARALLEL),
            IrNode("calls", NodeKind.LOOP, label = "repeat 3 times", parent = "notify"),
            IrNode("page", NodeKind.ACTIVITY, name = "PageOnCall", parent = "calls"),
            IrNode("answered", NodeKind.BRANCH, label = "answered?", parent = "calls"),
            IrNode("mail", NodeKind.ACTIVITY, name = "SendMail", parent = "notify"),
            IrNode("dispatch", NodeKind.ACTIVITY, name = "DispatchVan"),
            IrNode("undo", NodeKind.COMPENSATION),
            IrNode("refunds", NodeKind.LOOP, label = "for each payment", parent = "undo"),
            IrNode("settled", NodeKind.BRANCH, label = "settled?", parent = "refunds"),
            IrNode("refund", NodeKind.ACTIVITY, name = "RefundPayment", parent = "refunds"),
            IrNode("failed", NodeKind.FAIL, name = "DispatchFailed"),
            IrNode("end", NodeKind.END),
        )
        val edges = listOf(
            IrEdge("start", "batches"),
            IrEdge("load", "orders"),
            IrEdge("reserve", "items"),
            IrEdge("cancelled", "close", label = "yes"),
            IrEdge("cancelled", "full", label = "no"),
            IrEdge("close", "orders"),
            IrEdge("full", "batches", label = "yes"),
            IrEdge("full", "stock", label = "no"),
            IrEdge("stock", "pick", label = "yes"),
            IrEdge("stock", "items", label = "no"),
            IrEdge("pick", "items"),
            IrEdge("pick", "orders", EdgeKind.EXCEPTION, "ActivityFailure"),
            IrEdge("batches", "notify"),
            IrEdge("page", "answered"),
            IrEdge("answered", "notify", label = "yes"),
            IrEdge("answered", "calls", label = "no"),
            IrEdge("notify", "dispatch"),
            IrEdge("dispatch", "end"),
            IrEdge("dispatch", "undo", EdgeKind.EXCEPTION, "ActivityFailure"),
            IrEdge("settled", "undo", label = "yes"),
            IrEdge("settled", "refund", label = "no"),
            IrEdge("undo", "failed"),
        )
        return document("OuterEndsWorkflow", nodes, edges)
    }

    /**
     * Steps in the lanes of parallel sections that go on with the next round of the loop around
     * the section. The lanes run from left to right and the loop downwards, so these edges leave
     * their lane to the side: one from the middle lane of three, with a lane below it in its way,
     * and two from a branch and from the step that only one of its arms runs.
     */
    fun laneJumps(): IrDocument {
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("orders", NodeKind.LOOP, label = "for each order"),
            IrNode("checks", NodeKind.PARALLEL, parent = "orders"),
            IrNode("stock", NodeKind.ACTIVITY, name = "CheckStock", parent = "checks"),
            IrNode("cancelled", NodeKind.BRANCH, label = "cancelled?", parent = "checks"),
            IrNode("pack", NodeKind.ACTIVITY, name = "PackParcel", parent = "checks"),
            IrNode("mail", NodeKind.ACTIVITY, name = "SendMail", parent = "checks"),
            IrNode("archive", NodeKind.ACTIVITY, name = "ArchiveMail", parent = "checks"),
            IrNode("alerts", NodeKind.PARALLEL, parent = "orders"),
            IrNode("urgent", NodeKind.BRANCH, label = "urgent?", parent = "alerts"),
            IrNode("page", NodeKind.ACTIVITY, name = "PageOnCall", parent = "alerts"),
            IrNode("log", NodeKind.ACTIVITY, name = "LogOrder", parent = "alerts"),
            IrNode("end", NodeKind.END),
        )
        val edges = listOf(
            IrEdge("start", "orders"),
            IrEdge("cancelled", "orders", label = "yes"),
            IrEdge("cancelled", "pack", label = "no"),
            IrEdge("mail", "archive"),
            IrEdge("checks", "alerts"),
            IrEdge("urgent", "page", label = "yes"),
            IrEdge("urgent", "orders", label = "no"),
            IrEdge("page", "orders"),
            IrEdge("orders", "end"),
        )
        return document("LaneJumpsWorkflow", nodes, edges)
    }

    /**
     * The demo workflow with a signal handler that runs steps of its own before it returns: an
     * activity and a timer. The handler still wakes the await of the main flow.
     */
    fun handlerSteps(): IrDocument {
        val demo = demo()
        val handler = "signal-cancelOrder"
        val nodes = demo.nodes + listOf(
            IrNode("$handler.notify", NodeKind.ACTIVITY, name = "NotifyWarehouse"),
            IrNode("$handler.grace", NodeKind.TIMER, duration = "PT5M", label = "grace period"),
        )
        val edges = demo.edges.filterNot { it.from == "$handler.start" } + listOf(
            IrEdge("$handler.start", "$handler.notify"),
            IrEdge("$handler.notify", "$handler.grace"),
            IrEdge("$handler.grace", "$handler.end"),
        )
        return demo.copy(nodes = nodes, edges = edges, stats = io.github.iw.durableatlas.ir.IrStats.of(nodes))
    }

    /**
     * Steps with several edges to the end of one region, in every kind of region: a branch in a
     * loop three of whose four arms end the round, a step after it whose plain way on and whose
     * failure both do, lanes that end at a branch whichever way it goes, a branch in an inner
     * loop both arms of which go on with the next round of the loop around it, and a step of a
     * compensation that ends it whether it fails or not.
     */
    fun regionArms(): IrDocument {
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("orders", NodeKind.LOOP, label = "for each order"),
            IrNode("load", NodeKind.ACTIVITY, name = "LoadOrder", parent = "orders"),
            IrNode("kind", NodeKind.BRANCH, label = "kind of order", parent = "orders"),
            IrNode("pack", NodeKind.ACTIVITY, name = "PackParcel", parent = "orders"),
            IrNode("notify", NodeKind.PARALLEL),
            IrNode("urgent", NodeKind.BRANCH, label = "urgent?", parent = "notify"),
            IrNode("mail", NodeKind.ACTIVITY, name = "SendMail", parent = "notify"),
            IrNode("copy", NodeKind.BRANCH, label = "archive copy?", parent = "notify"),
            IrNode("batches", NodeKind.LOOP, label = "for each batch"),
            IrNode("items", NodeKind.LOOP, label = "for each item", parent = "batches"),
            IrNode("weigh", NodeKind.ACTIVITY, name = "WeighItem", parent = "items"),
            IrNode("heavy", NodeKind.BRANCH, label = "too heavy?", parent = "items"),
            IrNode("dispatch", NodeKind.ACTIVITY, name = "DispatchVan"),
            IrNode("undo", NodeKind.COMPENSATION),
            IrNode("recall", NodeKind.ACTIVITY, name = "RecallVan", parent = "undo"),
            IrNode("failed", NodeKind.FAIL, name = "DispatchFailed"),
            IrNode("end", NodeKind.END),
        )
        val edges = listOf(
            IrEdge("start", "orders"),
            IrEdge("load", "kind"),
            IrEdge("kind", "orders", label = "\"express\""),
            IrEdge("kind", "orders", label = "\"standard\""),
            IrEdge("kind", "pack", label = "\"bulk\""),
            IrEdge("kind", "orders", label = "else"),
            IrEdge("pack", "orders"),
            IrEdge("pack", "orders", EdgeKind.EXCEPTION, "ActivityFailure"),
            IrEdge("orders", "notify"),
            IrEdge("urgent", "notify", label = "yes"),
            IrEdge("urgent", "notify", label = "no"),
            IrEdge("mail", "copy"),
            IrEdge("copy", "notify", label = "yes"),
            IrEdge("copy", "notify", label = "no"),
            IrEdge("notify", "batches"),
            IrEdge("weigh", "heavy"),
            IrEdge("heavy", "batches", label = "yes"),
            IrEdge("heavy", "batches", label = "no"),
            IrEdge("batches", "dispatch"),
            IrEdge("dispatch", "end"),
            IrEdge("dispatch", "undo", EdgeKind.EXCEPTION, "ActivityFailure"),
            IrEdge("recall", "undo"),
            IrEdge("recall", "undo", EdgeKind.EXCEPTION, "ActivityFailure"),
            IrEdge("undo", "failed"),
        )
        return document("RegionArmsWorkflow", nodes, edges)
    }

    /**
     * Two edges between the same two steps, wherever steps follow each other in another
     * direction than those of the main flow, and in regions: an await in a loop that leads on
     * when it is answered and when it times out, the same in a lane of a parallel section in
     * that loop, in a lane of a parallel section of the main flow, and in what a signal handler
     * runs.
     */
    fun regionPairs(): IrDocument {
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("rounds", NodeKind.LOOP, label = "for each round"),
            IrNode("ask", NodeKind.ACTIVITY, name = "AskApproval", parent = "rounds"),
            IrNode("wait", NodeKind.AWAIT, label = "approved", timeout = "PT1H", parent = "rounds"),
            IrNode("book", NodeKind.ACTIVITY, name = "BookRoom", parent = "rounds"),
            IrNode("checks", NodeKind.PARALLEL, parent = "rounds"),
            IrNode("confirm", NodeKind.AWAIT, label = "confirmed", timeout = "PT10M", parent = "checks"),
            IrNode("log", NodeKind.ACTIVITY, name = "LogRound", parent = "checks"),
            IrNode("tidy", NodeKind.ACTIVITY, name = "TidyRoom", parent = "checks"),
            IrNode("both", NodeKind.PARALLEL),
            IrNode("hold", NodeKind.AWAIT, label = "paid", timeout = "PT30M", parent = "both"),
            IrNode("ship", NodeKind.ACTIVITY, name = "ShipOrder", parent = "both"),
            IrNode("note", NodeKind.ACTIVITY, name = "NoteOrder", parent = "both"),
            IrNode("end", NodeKind.END),
            IrNode("remind.start", NodeKind.START),
            IrNode("remind.wait", NodeKind.AWAIT, label = "acknowledged", timeout = "PT5M"),
            IrNode("remind.log", NodeKind.ACTIVITY, name = "LogReminder"),
            IrNode("remind.end", NodeKind.END),
        )
        val edges = listOf(
            IrEdge("remind.start", "remind.wait"),
            IrEdge("remind.wait", "remind.log", label = "acknowledged"),
            IrEdge("remind.wait", "remind.log", EdgeKind.TIMEOUT, "timeout"),
            IrEdge("remind.log", "remind.end"),
            IrEdge("start", "rounds"),
            IrEdge("ask", "wait"),
            IrEdge("wait", "book", label = "approved"),
            IrEdge("wait", "book", EdgeKind.TIMEOUT, "timeout"),
            IrEdge("book", "checks"),
            IrEdge("confirm", "log", label = "confirmed"),
            IrEdge("confirm", "log", EdgeKind.TIMEOUT, "timeout"),
            IrEdge("rounds", "both"),
            IrEdge("hold", "ship", label = "paid"),
            IrEdge("hold", "ship", EdgeKind.TIMEOUT, "timeout"),
            IrEdge("both", "end"),
        )
        val made = document("RegionPairsWorkflow", nodes, edges)
        return made.copy(entryPoints = made.entryPoints + EntryPoint("signal-remind", EntryPointKind.SIGNAL, "remind", "remind.start"))
    }

    /**
     * [document] with every label of an edge, and of a loop, replaced by a long one that says
     * the same thing in the words of code, as a test after an await does: too long for one line.
     * Labels that were equal stay equal, so the same edges run together.
     */
    fun longLabels(document: IrDocument): IrDocument = document.copy(
        workflowType = "${document.workflowType}InFull",
        edges = document.edges.map { edge ->
            when (val label = edge.label) {
                null -> edge
                "yes", "no", "else" -> edge.copy(label = "$label, as request.options.flags.isEmpty() says")
                else -> edge.copy(label = "pendingOrders.isEmpty() and ${label.trim('"')}Reason != null")
            }
        },
        nodes = document.nodes.map { node ->
            if (node.kind == NodeKind.LOOP && node.label != null) node.copy(label = "retry ${node.label}, up to 3 attempts, for up to 1h 30m") else node
        },
    )

    /**
     * Loops that wait to be woken, as a workflow driven by signals is written. The first step of
     * the first loop is an await right below the title of the loop, and two signal handlers wake
     * it. The title of the second loop is code, and as wide as the loop; a third handler wakes the
     * await in it. Both awaits wait on conditions written as code that take two lines, and what
     * the last step of the second loop throws is caught after the loop.
     */
    fun wokenLoops(): IrDocument {
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("inbox", NodeKind.LOOP, label = "while true"),
            IrNode("wait", NodeKind.AWAIT, label = "not pendingOrders.isEmpty() or done", reads = listOf("done", "pendingOrders"), parent = "inbox"),
            IrNode("handle", NodeKind.ACTIVITY, name = "HandleOrder", parent = "inbox"),
            IrNode("replies", NodeKind.LOOP, label = "while !openReplies!!.isEmpty()"),
            IrNode("settle", NodeKind.AWAIT, label = "openReplies.none { it.isLate() } || cancelled", reads = listOf("cancelled", "openReplies"), parent = "replies"),
            IrNode("send", NodeKind.ACTIVITY, name = "SendReply", parent = "replies"),
            IrNode("report", NodeKind.ACTIVITY, name = "ReportFailure"),
            IrNode("end", NodeKind.END),
            IrNode("post.start", NodeKind.START),
            IrNode("post.end", NodeKind.END),
            IrNode("finish.start", NodeKind.START),
            IrNode("finish.end", NodeKind.END),
            IrNode("cancel.start", NodeKind.START),
            IrNode("cancel.end", NodeKind.END),
        )
        val edges = listOf(
            IrEdge("post.start", "post.end"),
            IrEdge("finish.start", "finish.end"),
            IrEdge("cancel.start", "cancel.end"),
            IrEdge("start", "inbox"),
            IrEdge("wait", "handle", label = "not pendingOrders.isEmpty()"),
            IrEdge("wait", "replies", label = "done"),
            IrEdge("settle", "send"),
            IrEdge("send", "report", EdgeKind.EXCEPTION, "ActivityFailure"),
            IrEdge("replies", "end"),
            IrEdge("report", "end"),
            IrEdge("signal-post", "wait", EdgeKind.WAKES),
            IrEdge("signal-finish", "wait", EdgeKind.WAKES),
            IrEdge("signal-cancel", "settle", EdgeKind.WAKES),
        )
        val made = document("WokenLoopsWorkflow", nodes, edges)
        return made.copy(
            entryPoints = made.entryPoints + listOf(
                EntryPoint("signal-post", EntryPointKind.SIGNAL, "post", "post.start", writes = listOf("pendingOrders")),
                EntryPoint("signal-finish", EntryPointKind.SIGNAL, "finish", "finish.start", writes = listOf("done")),
                EntryPoint("signal-cancel", EntryPointKind.SIGNAL, "cancel", "cancel.start", writes = listOf("cancelled")),
            ),
        )
    }

    /**
     * Handlers that return in more ways than by coming to their end. A signal handler skips its
     * one step on an arm of a branch. A validator lets a request pass on one arm of a branch and
     * fails on the other, and the update it belongs to returns whether its step fails or not. A
     * second signal handler waits for a lock for a time at most, returns when that is up, and
     * wakes its own wait and one of the workflow method. A third only waits, and returns when it
     * is answered and when its time is up. A query handler simply returns.
     */
    fun handlerEnds(): IrDocument {
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("wait", NodeKind.AWAIT, label = "ready", reads = listOf("ready")),
            IrNode("work", NodeKind.ACTIVITY, name = "DoWork"),
            IrNode("end", NodeKind.END),
            IrNode("skip.start", NodeKind.START),
            IrNode("skip.done", NodeKind.BRANCH, label = "already done?"),
            IrNode("skip.archive", NodeKind.ACTIVITY, name = "ArchiveOrder"),
            IrNode("skip.end", NodeKind.END),
            IrNode("check.start", NodeKind.START),
            IrNode("check.valid", NodeKind.BRANCH, label = "request.isValid()"),
            IrNode("check.fail", NodeKind.FAIL, name = "InvalidRequest"),
            IrNode("check.end", NodeKind.END),
            IrNode("book.start", NodeKind.START),
            IrNode("book.reserve", NodeKind.ACTIVITY, name = "ReserveRoom"),
            IrNode("book.end", NodeKind.END),
            IrNode("nudge.start", NodeKind.START),
            IrNode("nudge.lock", NodeKind.AWAIT, label = "lock.tryLock()", timeout = "PT10S", reads = listOf("lock")),
            IrNode("nudge.poke", NodeKind.ACTIVITY, name = "PokeWorker"),
            IrNode("nudge.end", NodeKind.END),
            IrNode("hold.start", NodeKind.START),
            IrNode("hold.wait", NodeKind.AWAIT, label = "released", timeout = "PT5S", reads = listOf("released")),
            IrNode("hold.end", NodeKind.END),
            IrNode("state.start", NodeKind.START),
            IrNode("state.end", NodeKind.END),
        )
        val edges = listOf(
            IrEdge("start", "wait"),
            IrEdge("wait", "work"),
            IrEdge("work", "end"),
            IrEdge("hold.start", "hold.wait"),
            IrEdge("hold.wait", "hold.end"),
            IrEdge("hold.wait", "hold.end", EdgeKind.TIMEOUT),
            IrEdge("skip.start", "skip.done"),
            IrEdge("skip.done", "skip.end", label = "yes"),
            IrEdge("skip.done", "skip.archive", label = "no"),
            IrEdge("skip.archive", "skip.end"),
            IrEdge("check.start", "check.valid"),
            IrEdge("check.valid", "check.end", label = "yes"),
            IrEdge("check.valid", "check.fail", label = "no"),
            IrEdge("book.start", "book.reserve"),
            IrEdge("book.reserve", "book.end"),
            IrEdge("book.reserve", "book.end", EdgeKind.EXCEPTION, "ActivityFailure"),
            IrEdge("nudge.start", "nudge.lock"),
            IrEdge("nudge.lock", "nudge.poke"),
            IrEdge("nudge.lock", "nudge.end", EdgeKind.TIMEOUT),
            IrEdge("nudge.poke", "nudge.end"),
            IrEdge("state.start", "state.end"),
            IrEdge("signal-nudge", "wait", EdgeKind.WAKES),
            IrEdge("signal-nudge", "nudge.lock", EdgeKind.WAKES),
        )
        val made = document("HandlerEndsWorkflow", nodes, edges)
        return made.copy(
            entryPoints = made.entryPoints + listOf(
                EntryPoint("signal-skip", EntryPointKind.SIGNAL, "skip", "skip.start"),
                EntryPoint("validator-book", EntryPointKind.UPDATE_VALIDATOR, "book", "check.start"),
                EntryPoint("update-book", EntryPointKind.UPDATE, "book", "book.start"),
                EntryPoint("signal-nudge", EntryPointKind.SIGNAL, "nudge", "nudge.start", reads = listOf("lock"), writes = listOf("lock", "ready")),
                EntryPoint("signal-hold", EntryPointKind.SIGNAL, "hold", "hold.start", reads = listOf("released")),
                EntryPoint("query-state", EntryPointKind.QUERY, "state", "state.start"),
            ),
        )
    }

    /**
     * A workflow without handlers whose own code ends its waits, long enough to take two rows:
     * the first step of a loop in the first row waits for a slot, and each of two lanes in the
     * second row for a permit. The `wakes` edges of all three start at the workflow method.
     */
    fun selfWoken(): IrDocument {
        val steps = (1..7).map { IrNode("a$it", NodeKind.ACTIVITY, name = "PrepareBatch$it") }
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("inbox", NodeKind.LOOP, label = "for each order"),
            IrNode("slot", NodeKind.AWAIT, label = "slots.acquire()", reads = listOf("slots"), parent = "inbox"),
            IrNode("pick", NodeKind.ACTIVITY, name = "PickOrder", parent = "inbox"),
        ) + steps + listOf(
            IrNode("lanes", NodeKind.PARALLEL),
            IrNode("first", NodeKind.AWAIT, label = "permits.acquire()", reads = listOf("permits"), parent = "lanes"),
            IrNode("pack", NodeKind.ACTIVITY, name = "PackParcel", parent = "lanes"),
            IrNode("second", NodeKind.AWAIT, label = "permits.acquire()", reads = listOf("permits"), parent = "lanes"),
            IrNode("print", NodeKind.ACTIVITY, name = "PrintLabel", parent = "lanes"),
            IrNode("end", NodeKind.END),
        )
        val path = listOf("start", "inbox") + steps.map { it.id } + listOf("lanes", "end")
        val edges = path.zipWithNext { from, to -> IrEdge(from, to) } + listOf(
            IrEdge("slot", "pick"),
            IrEdge("first", "pack"),
            IrEdge("second", "print"),
            IrEdge("main", "slot", EdgeKind.WAKES, "slots"),
            IrEdge("main", "first", EdgeKind.WAKES, "permits"),
            IrEdge("main", "second", EdgeKind.WAKES, "permits"),
        )
        return document("SelfWokenWorkflow", nodes, edges)
    }

    /**
     * Three lanes with a wait in the middle of each, a step before it and one after. Each wait
     * is ended by the workflow's own code and by a signal handler. The waits of the lower lanes
     * have steps above them and on both sides.
     */
    fun midLane(): IrDocument {
        val lanes = listOf("Truck", "Van", "Bike")
        val nodes = listOf(IrNode("start", NodeKind.START), IrNode("lanes", NodeKind.PARALLEL)) +
            lanes.flatMap { name ->
                listOf(
                    IrNode("load$name", NodeKind.ACTIVITY, name = "Load$name", parent = "lanes"),
                    IrNode("wait$name", NodeKind.AWAIT, label = "gate.lock()", reads = listOf("gate"), parent = "lanes"),
                    IrNode("move$name", NodeKind.ACTIVITY, name = "Move$name", parent = "lanes"),
                )
            } +
            listOf(IrNode("end", NodeKind.END), IrNode("go.start", NodeKind.START), IrNode("go.end", NodeKind.END))
        val edges = listOf(IrEdge("start", "lanes"), IrEdge("lanes", "end"), IrEdge("go.start", "go.end")) +
            lanes.flatMap { name ->
                listOf(
                    IrEdge("load$name", "wait$name"),
                    IrEdge("wait$name", "move$name"),
                    IrEdge("main", "wait$name", EdgeKind.WAKES, "gate"),
                    IrEdge("signal-go", "wait$name", EdgeKind.WAKES),
                )
            }
        val made = document("MidLaneWorkflow", nodes, edges)
        return made.copy(entryPoints = made.entryPoints + EntryPoint("signal-go", EntryPointKind.SIGNAL, "go", "go.start", writes = listOf("gate")))
    }

    /**
     * A loop whose round ends early: the `no` arm of the branch in its body leads to the loop
     * itself, which stands for the end of the round. The lanes of the parallel section after
     * it end the same way.
     */
    fun skipping(): IrDocument {
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("each", NodeKind.LOOP, label = "for each item"),
            IrNode("in-stock", NodeKind.BRANCH, label = "in stock?", parent = "each"),
            IrNode("ship", NodeKind.ACTIVITY, name = "ShipItem", parent = "each"),
            IrNode("both", NodeKind.PARALLEL),
            IrNode("urgent", NodeKind.BRANCH, label = "urgent?", parent = "both"),
            IrNode("page", NodeKind.ACTIVITY, name = "PageOnCall", parent = "both"),
            IrNode("mail", NodeKind.ACTIVITY, name = "SendMail", parent = "both"),
            IrNode("end", NodeKind.END),
        )
        val edges = listOf(
            IrEdge("start", "each"),
            IrEdge("in-stock", "ship", label = "yes"),
            IrEdge("in-stock", "each", label = "no"),
            IrEdge("each", "both"),
            IrEdge("urgent", "page", label = "yes"),
            IrEdge("urgent", "both", label = "no"),
            IrEdge("both", "end"),
        )
        return document("SkippingWorkflow", nodes, edges)
    }

    /**
     * A workflow of [steps] activities in a row, each of which can fail into one shared
     * compensation. No edge of its main path is the only connection between what comes before
     * it and what comes after.
     */
    fun saga(steps: Int): IrDocument {
        val activities = (1..steps).map { IrNode(id = "a%02d".format(it), kind = NodeKind.ACTIVITY, name = "ReserveResource$it") }
        val nodes = listOf(IrNode("start", NodeKind.START)) + activities + listOf(
            IrNode("undo", NodeKind.COMPENSATION),
            IrNode("undo-1", NodeKind.ACTIVITY, name = "ReleaseResources", parent = "undo"),
            IrNode("failed", NodeKind.FAIL, name = "ReservationFailed"),
            IrNode("end", NodeKind.END),
        )
        val path = listOf("start") + activities.map { it.id } + "end"
        val edges = path.zipWithNext { from, to -> IrEdge(from, to) } +
            activities.map { IrEdge(it.id, "undo", EdgeKind.EXCEPTION, "ActivityFailure") } +
            IrEdge("undo", "failed")
        return document("SagaWorkflow", nodes, edges)
    }

    private fun document(type: String, nodes: List<IrNode>, edges: List<IrEdge>): IrDocument = IrDocument(
        commit = "0000000000000000000000000000000000000000",
        modelMode = ModelMode.GRADLE,
        workflowType = type,
        interfaceName = "com.example.$type",
        implementation = "com.example.${type}Impl",
        module = ":made",
        entryPoints = listOf(EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, type, "start")),
        nodes = nodes,
        edges = edges,
    )
}
