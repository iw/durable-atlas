package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import io.github.iw.durableatlas.render.svg.SvgDocuments
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The edges between a region and its steps that a document leaves unsaid, as the Mermaid
 * diagram draws them: into each step that nothing else in the region leads to, and back to the
 * head of the loop or on to the join of the section from each step that has no edge saying where
 * it goes. An edge to another step of the region or to the end of a region says so, and so does a
 * flow edge to a node outside the region. An exception or timeout edge to a node outside is one
 * more way on from the step and takes nothing away.
 */
class ImpliedEdgesTest {
    /** The edges of the diagram of [document], as they are written. Each is written once. */
    private fun edges(document: IrDocument, options: RenderOptions = RenderOptions()): Set<String> {
        val mermaid = MermaidRenderer.render(document, options)
        MermaidLint.check(mermaid)
        val lines = mermaid.lines().map { it.trim() }.filter { "-->" in it }
        assertEquals(lines.size, lines.toSet().size, "an edge is drawn twice")
        // Neither the order of the document nor that of its edges decides what is added.
        repeat(3) { seed ->
            val random = Random(seed)
            val shuffled = document.copy(nodes = document.nodes.shuffled(random), edges = document.edges.shuffled(random))
            assertEquals(mermaid, MermaidRenderer.render(shuffled, options), "seed $seed")
        }
        return lines.toSet()
    }

    @Test
    fun `a step that can time out of its loop still leads back to the head when it does not`() {
        // repeat 3 times { remind; wait an hour for the answer }; on a timeout the loop is left for good.
        val reminders = document(
            "Reminders",
            listOf(
                IrNode("start", NodeKind.START),
                IrNode("tries", NodeKind.LOOP, label = "repeat 3 times"),
                IrNode("remind", NodeKind.ACTIVITY, name = "SendReminder", parent = "tries"),
                IrNode("answer", NodeKind.AWAIT, reads = listOf("answered"), timeout = "PT1H", parent = "tries"),
                IrNode("escalate", NodeKind.ACTIVITY, name = "Escalate"),
                IrNode("end", NodeKind.END),
            ),
            listOf(
                IrEdge("start", "tries"),
                IrEdge("remind", "answer"),
                IrEdge("answer", "escalate", EdgeKind.TIMEOUT),
                IrEdge("tries", "end"),
                IrEdge("escalate", "end"),
            ),
        )
        assertEquals(
            setOf(
                "n_start --> n_tries",
                "n_tries --> n_remind",
                "n_remind --> n_answer",
                "n_answer -->|\"timeout\"| n_escalate",
                "n_answer -->|\"repeat\"| n_tries",
                "n_tries --> n_end",
                "n_escalate --> n_end",
            ),
            edges(reminders),
        )
    }

    @Test
    fun `a step whose flow edge leaves the region goes nowhere else`() {
        // for each shelf { scan; if (found) { notify; break } }; report
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("shelves", NodeKind.LOOP, label = "for each shelf"),
            IrNode("scan", NodeKind.ACTIVITY, name = "ScanShelf", parent = "shelves"),
            IrNode("found", NodeKind.BRANCH, label = "item found?", parent = "shelves"),
            IrNode("tell", NodeKind.ACTIVITY, name = "NotifyBuyer", parent = "shelves"),
            IrNode("report", NodeKind.ACTIVITY, name = "ReportItem"),
            IrNode("end", NodeKind.END),
        )
        val breaking = document(
            "ShelfSearch",
            nodes,
            listOf(
                IrEdge("start", "shelves"),
                IrEdge("scan", "found"),
                IrEdge("found", "tell", label = "yes"),
                IrEdge("found", "shelves", label = "no"),
                IrEdge("tell", "report"),
                IrEdge("shelves", "report"),
                IrEdge("report", "end"),
            ),
        )
        val drawn = setOf(
            "n_start --> n_shelves",
            "n_shelves --> n_scan",
            "n_scan --> n_found",
            "n_found -->|\"yes\"| n_tell",
            "n_found -->|\"no\"| n_shelves",
            // The one edge of the step: no way back to the head beside it.
            "n_tell --> n_report",
            "n_shelves --> n_report",
            "n_report --> n_end",
        )
        assertEquals(drawn, edges(breaking))

        // The same with a `return` in place of the `break`, and where the step can fail into a step outside as well.
        val returning = breaking.copy(
            edges = breaking.edges.filter { it.from != "tell" } +
                IrEdge("tell", "end") + IrEdge("tell", "report", EdgeKind.EXCEPTION, "ActivityFailure"),
        )
        assertEquals(drawn - "n_tell --> n_report" + "n_tell --> n_end" + "n_tell -->|\"ActivityFailure\"| n_report", edges(returning))

        // A branch is a step like any other: the arm that leaves is said, so the arm that stays has to be said too.
        val unsaid = document(
            "ShelfSearch",
            nodes.filter { it.id != "tell" },
            listOf(
                IrEdge("start", "shelves"),
                IrEdge("scan", "found"),
                IrEdge("found", "report", label = "yes"),
                IrEdge("shelves", "report"),
                IrEdge("report", "end"),
            ),
        )
        assertEquals(
            setOf("n_start --> n_shelves", "n_shelves --> n_scan", "n_scan --> n_found", "n_found -->|\"yes\"| n_report", "n_shelves --> n_report", "n_report --> n_end"),
            edges(unsaid),
        )

        // In a lane: the step that ends the workflow does not lead to the join, the other lane does.
        val lanes = document(
            "Lanes",
            listOf(
                IrNode("start", NodeKind.START),
                IrNode("both", NodeKind.PARALLEL),
                IrNode("mail", NodeKind.ACTIVITY, name = "SendMail", parent = "both"),
                IrNode("text", NodeKind.ACTIVITY, name = "SendText", parent = "both"),
                IrNode("stop", NodeKind.END),
                IrNode("log", NodeKind.ACTIVITY, name = "LogAll"),
            ),
            listOf(IrEdge("start", "both"), IrEdge("text", "stop"), IrEdge("both", "log"), IrEdge("log", "stop")),
        )
        assertEquals(
            setOf(
                "n_start --> n_both",
                "n_both --> n_mail",
                "n_both --> n_text",
                "n_mail --> n_both_join",
                "n_text --> n_stop",
                "n_both_join --> n_log",
                "n_log --> n_stop",
            ),
            edges(lanes),
        )
    }

    @Test
    fun `the last step of a loop leads back to the head when its failure is handled outside the loop`() {
        val document = document(
            "Reservations",
            listOf(
                IrNode("start", NodeKind.START),
                IrNode("lines", NodeKind.LOOP, label = "for each line"),
                IrNode("check", NodeKind.LOCAL_ACTIVITY, name = "CheckAvailability", parent = "lines"),
                IrNode("reserve", NodeKind.ACTIVITY, name = "ReserveStock", parent = "lines"),
                IrNode("charge", NodeKind.ACTIVITY, name = "ChargePayment"),
                IrNode("undo", NodeKind.COMPENSATION),
                IrNode("release", NodeKind.ACTIVITY, name = "ReleaseStock", parent = "undo"),
                IrNode("failed", NodeKind.FAIL, name = "OrderFailed"),
                IrNode("end", NodeKind.END),
            ),
            listOf(
                IrEdge("start", "lines"),
                IrEdge("check", "reserve"),
                IrEdge("check", "undo", EdgeKind.EXCEPTION, "ActivityFailure"),
                IrEdge("reserve", "undo", EdgeKind.EXCEPTION, "ActivityFailure"),
                IrEdge("lines", "charge"),
                IrEdge("charge", "end"),
                IrEdge("charge", "undo", EdgeKind.EXCEPTION, "ActivityFailure"),
                IrEdge("undo", "failed"),
            ),
        )
        assertEquals(
            setOf(
                "n_start --> n_lines",
                "n_lines --> n_check",
                "n_check --> n_reserve",
                "n_check -->|\"ActivityFailure\"| n_undo",
                "n_reserve -->|\"repeat\"| n_lines",
                "n_reserve -->|\"ActivityFailure\"| n_undo",
                "n_lines --> n_charge",
                "n_charge --> n_end",
                "n_charge -->|\"ActivityFailure\"| n_undo",
                "n_undo --> n_release",
                "n_undo --> n_failed",
            ),
            edges(document),
        )
    }

    @Test
    fun `the last step of a lane leads to the join when it also has an edge out of the section`() {
        val document = document(
            "Notifications",
            listOf(
                IrNode("start", NodeKind.START),
                IrNode("both", NodeKind.PARALLEL),
                IrNode("mail", NodeKind.ACTIVITY, name = "SendMail", parent = "both"),
                IrNode("text", NodeKind.ACTIVITY, name = "SendText", parent = "both"),
                IrNode("log", NodeKind.ACTIVITY, name = "LogText", parent = "both"),
                IrNode("undo", NodeKind.COMPENSATION),
                IrNode("recall", NodeKind.ACTIVITY, name = "RecallMail", parent = "undo"),
                IrNode("failed", NodeKind.FAIL, name = "NotifyFailed"),
                IrNode("end", NodeKind.END),
            ),
            listOf(
                IrEdge("start", "both"),
                IrEdge("mail", "undo", EdgeKind.EXCEPTION, "ActivityFailure"),
                IrEdge("text", "log"),
                IrEdge("text", "undo", EdgeKind.EXCEPTION, "ActivityFailure"),
                IrEdge("log", "undo", EdgeKind.EXCEPTION, "ActivityFailure"),
                IrEdge("both", "end"),
                IrEdge("undo", "failed"),
            ),
        )
        val mermaid = MermaidRenderer.render(document)
        // The edges to the compensation outside do not make one lane of the two.
        assertEquals(2, Regex("subgraph n_both_lane\\d ").findAll(mermaid).count())
        assertEquals(
            setOf(
                "n_start --> n_both",
                "n_both --> n_mail",
                "n_both --> n_text",
                "n_mail --> n_both_join",
                "n_mail -->|\"ActivityFailure\"| n_undo",
                "n_text --> n_log",
                "n_text -->|\"ActivityFailure\"| n_undo",
                "n_log --> n_both_join",
                "n_log -->|\"ActivityFailure\"| n_undo",
                "n_both_join --> n_end",
                "n_undo --> n_recall",
                "n_undo --> n_failed",
            ),
            edges(document),
        )
    }

    @Test
    fun `a return from the inner of two loops takes away neither the way back of the next step nor that of the inner loop`() {
        // for each order { load; for each item { if (damaged) { refund; return }; pack } }
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("orders", NodeKind.LOOP, label = "for each order"),
            IrNode("load", NodeKind.ACTIVITY, name = "LoadOrder", parent = "orders"),
            IrNode("items", NodeKind.LOOP, label = "for each item", parent = "orders"),
            IrNode("damaged", NodeKind.BRANCH, label = "damaged?", parent = "items"),
            IrNode("pack", NodeKind.ACTIVITY, name = "PackItem", parent = "items"),
            IrNode("refund", NodeKind.ACTIVITY, name = "RefundOrder"),
            IrNode("end", NodeKind.END),
        )
        val returning = document(
            "Packing",
            nodes,
            listOf(
                IrEdge("start", "orders"),
                IrEdge("load", "items"),
                IrEdge("damaged", "refund", label = "yes"),
                IrEdge("damaged", "pack", label = "no"),
                IrEdge("refund", "end"),
                IrEdge("orders", "end"),
            ),
        )
        val drawn = setOf(
            "n_start --> n_orders",
            "n_orders --> n_load",
            "n_load --> n_items",
            "n_items --> n_damaged",
            "n_damaged -->|\"yes\"| n_refund",
            "n_damaged -->|\"no\"| n_pack",
            "n_pack -->|\"repeat\"| n_items",
            "n_items -->|\"repeat\"| n_orders",
            "n_refund --> n_end",
            "n_orders --> n_end",
        )
        assertEquals(drawn, edges(returning))

        // The same where the branch is the last of its round: its other arm is written, to the end of the round.
        val last = document(
            "Packing",
            nodes.filter { it.id != "pack" },
            returning.edges.filter { it.to != "pack" } + IrEdge("damaged", "items", label = "no"),
        )
        assertEquals(
            drawn - "n_damaged -->|\"no\"| n_pack" - "n_pack -->|\"repeat\"| n_items" + "n_damaged -->|\"no\"| n_items",
            edges(last),
        )
    }

    @Test
    fun `a step that says where it goes among the regions around it gets no edge added`() {
        // To the next step, to the end of its own round, to the end of a round further out: each is the way on.
        val document = document(
            "Batches",
            listOf(
                IrNode("start", NodeKind.START),
                IrNode("batches", NodeKind.LOOP, label = "for each batch"),
                IrNode("orders", NodeKind.LOOP, label = "for each order", parent = "batches"),
                IrNode("paid", NodeKind.BRANCH, label = "paid?", parent = "orders"),
                IrNode("ship", NodeKind.ACTIVITY, name = "ShipOrder", parent = "orders"),
                IrNode("full", NodeKind.BRANCH, label = "van full?", parent = "orders"),
                IrNode("end", NodeKind.END),
            ),
            listOf(
                IrEdge("start", "batches"),
                IrEdge("paid", "ship", label = "yes"),
                IrEdge("paid", "orders", label = "no"),
                IrEdge("ship", "full"),
                IrEdge("ship", "batches", EdgeKind.EXCEPTION, "ActivityFailure"),
                IrEdge("full", "batches", label = "yes"),
                IrEdge("full", "end", label = "stop"),
                IrEdge("batches", "end"),
            ),
        )
        assertEquals(
            setOf(
                "n_start --> n_batches",
                "n_batches --> n_orders",
                "n_orders --> n_paid",
                "n_paid -->|\"yes\"| n_ship",
                "n_paid -->|\"no\"| n_orders",
                "n_ship --> n_full",
                "n_ship -->|\"ActivityFailure\"| n_batches",
                "n_full -->|\"yes\"| n_batches",
                "n_full -->|\"stop\"| n_end",
                "n_orders -->|\"repeat\"| n_batches",
                "n_batches --> n_end",
            ),
            edges(document),
        )
    }

    @Test
    fun `the steps of a compensation are entered and get no way back, whatever leaves them`() {
        val document = document(
            "Refunds",
            listOf(
                IrNode("start", NodeKind.START),
                IrNode("charge", NodeKind.ACTIVITY, name = "ChargePayment"),
                IrNode("undo", NodeKind.COMPENSATION),
                IrNode("payments", NodeKind.LOOP, label = "for each payment", parent = "undo"),
                IrNode("refund", NodeKind.ACTIVITY, name = "RefundPayment", parent = "payments"),
                IrNode("release", NodeKind.ACTIVITY, name = "ReleaseStock", parent = "undo"),
                IrNode("alert", NodeKind.ACTIVITY, name = "AlertOperator"),
                IrNode("failed", NodeKind.FAIL, name = "OrderFailed"),
                IrNode("end", NodeKind.END),
            ),
            listOf(
                IrEdge("start", "charge"),
                IrEdge("charge", "end"),
                IrEdge("charge", "undo", EdgeKind.EXCEPTION, "ActivityFailure"),
                IrEdge("refund", "alert", EdgeKind.EXCEPTION, "ActivityFailure"),
                IrEdge("payments", "release"),
                IrEdge("release", "alert", EdgeKind.EXCEPTION, "ActivityFailure"),
                IrEdge("undo", "failed"),
                IrEdge("alert", "failed"),
            ),
        )
        assertEquals(
            setOf(
                "n_start --> n_charge",
                "n_charge --> n_end",
                "n_charge -->|\"ActivityFailure\"| n_undo",
                "n_undo --> n_payments",
                "n_payments --> n_refund",
                // The loop inside the compensation is a loop like any other.
                "n_refund -->|\"repeat\"| n_payments",
                "n_refund -->|\"ActivityFailure\"| n_alert",
                "n_payments --> n_release",
                "n_release -->|\"ActivityFailure\"| n_alert",
                "n_undo --> n_failed",
                "n_alert --> n_failed",
            ),
            edges(document),
        )
    }

    @Test
    fun `a step is entered from its region unless an edge leads to it from elsewhere in the region`() {
        // for each order { check; for each item { pick; if (missing) break }; close }
        val document = document(
            "Entries",
            listOf(
                IrNode("start", NodeKind.START),
                IrNode("orders", NodeKind.LOOP, label = "for each order"),
                IrNode("check", NodeKind.ACTIVITY, name = "CheckOrder", parent = "orders"),
                IrNode("items", NodeKind.LOOP, label = "for each item", parent = "orders"),
                IrNode("pick", NodeKind.ACTIVITY, name = "PickItem", parent = "items"),
                IrNode("short", NodeKind.BRANCH, label = "missing?", parent = "items"),
                IrNode("close", NodeKind.ACTIVITY, name = "CloseOrder", parent = "orders"),
                IrNode("end", NodeKind.END),
            ),
            listOf(
                IrEdge("start", "orders"),
                IrEdge("check", "items"),
                IrEdge("pick", "short"),
                IrEdge("short", "close", label = "yes"),
                IrEdge("short", "items", label = "no"),
                IrEdge("items", "close"),
                IrEdge("orders", "end"),
            ),
        )
        assertEquals(
            setOf(
                "n_start --> n_orders",
                // Only the first step of each loop: the others are led to, one of them from inside the inner loop too.
                "n_orders --> n_check",
                "n_check --> n_items",
                "n_items --> n_pick",
                "n_pick --> n_short",
                "n_short -->|\"yes\"| n_close",
                "n_short -->|\"no\"| n_items",
                "n_items --> n_close",
                "n_close -->|\"repeat\"| n_orders",
                "n_orders --> n_end",
            ),
            edges(document),
        )
    }

    @Test
    fun `over the node limit nothing is added`() {
        val document = SvgDocuments.all().getValue("analyser/OrderWorkflow")
        val collapsed = edges(document, RenderOptions(maxNodes = 10))
        val nodes = document.nodes.associateBy { it.id }
        // Every edge that is drawn joins two nodes of the top level.
        for (line in collapsed) {
            val ends = line.split(Regex(" -(?:\\.-|-)>(?:\\|\"[^\"]*\"\\|)? "))
            assertEquals(2, ends.size, line)
            ends.forEach { assertEquals(null, nodes.getValue(it).parent, line) }
        }
    }

    @Test
    fun `the loop and the lanes of the sample order workflow are closed`() {
        val documents = SvgDocuments.all()

        val order = documents.getValue("analyser/OrderWorkflow")
        val drawn = edges(order)
        val lines = order.nodes.single { it.kind == NodeKind.LOOP }
        assertEquals("for each line", lines.label)
        // The last step of a round can fail into the compensation outside the loop, and else begins the next round.
        val reserve = order.nodes.single { it.parent == lines.id && it.name == "ReserveStock" }
        assertEquals(listOf(EdgeKind.EXCEPTION), order.edges.filter { it.from == reserve.id }.map { it.kind })
        assertContains(drawn, "${reserve.id} -->|\"repeat\"| ${lines.id}")
        assertEquals(1, drawn.count { it.endsWith("-->|\"repeat\"| ${lines.id}") })
        // So do both lanes of the parallel section, which end where its join is.
        val section = order.nodes.single { it.kind == NodeKind.PARALLEL }
        val lanes = order.nodes.filter { it.parent == section.id }
        assertEquals(listOf("NotifyEmail", "NotifySms"), lanes.mapNotNull { it.name }.sorted())
        lanes.forEach { assertContains(drawn, "${it.id} --> ${section.id}_join") }
        assertEquals(2, drawn.count { it.endsWith(" ${section.id}_join") })

        val references = documents.getValue("analyser/MethodReferenceWorkflow")
        val fork = references.nodes.single { it.kind == NodeKind.PARALLEL }
        val steps = references.nodes.filter { it.parent == fork.id }
        assertEquals(2, steps.size)
        steps.forEach { assertContains(edges(references), "${it.id} --> ${fork.id}_join") }
    }

    @Test
    fun `the last steps of analysed loops and lanes go round, or on, as their source says`() {
        val documents = SvgDocuments.all().filterKeys { it.startsWith("analyser/exits/") }.mapKeys { it.key.substringAfterLast('/') }
        // What is drawn to the head of a loop or to the join of a section from inside it, as `from -[caption]-> to`.
        val expected = mapOf(
            // if (last) break, return or continue as the last thing in the round: the arm that stays is drawn as written.
            "BranchBreak" to setOf("branch@22 -[no]-> loop@20"),
            "BranchReturn" to setOf("branch@22 -[no]-> loop@20"),
            "BranchContinue" to setOf("branch@22 -[yes]-> loop@20", "branch@22 -[no]-> loop@20"),
            // The arm of the `when` that runs a step ends with it, and the step goes round unsaid.
            "WhenArms" to setOf("branch@22 -[#quot;skip#quot;]-> loop@20", "branch@22 -[else]-> loop@20", "Work -[repeat]-> loop@20"),
            // A step with a `break`, a `return` or a `throw` after it never goes round.
            "NotifyBreak" to setOf("branch@22 -[no]-> loop@20"),
            "StepReturn" to emptySet(),
            "NotifyThrow" to setOf("branch@22 -[no]-> loop@20"),
            // A step goes round whether its failure is passed over in the loop or handled outside it.
            "CaughtFailure" to setOf("Work -[repeat]-> loop@20", "Work -[ActivityFailure]-> loop@20", "Prepare -[repeat]-> loop@27"),
            "AwaitTimeout" to setOf("await@27 -[answered]-> loop@25"),
            // The step that breaks out of the loop from beside a lane does not join; the lane and the other arm do.
            "LaneBreak" to setOf("Check --> join@21", "branch@23 -[no]-> join@21", "join@21 -[repeat]-> loop@20"),
        )
        assertEquals(expected.keys, documents.keys)
        val edge = Regex("(\\w+) -->(?:\\|\"([^\"]*)\"\\|)? (\\w+)")
        for ((name, document) in documents) {
            val nodes = document.nodes.associateBy { it.id }
            fun words(id: String): String {
                val node = nodes[id] ?: return "join@${nodes.getValue(id.removeSuffix("_join")).loc?.line}"
                return node.name ?: "${node.kind.name.lowercase()}@${node.loc?.line}"
            }
            val closing = edges(document).mapNotNull { line ->
                val (from, caption, to) = edge.matchEntire(line)?.destructured ?: return@mapNotNull null
                val region = nodes[to.removeSuffix("_join")]?.takeIf { it.kind.isContainer } ?: return@mapNotNull null
                val inside = generateSequence(nodes.getValue(from.removeSuffix("_join")).parent) { nodes.getValue(it).parent }.contains(region.id)
                if (!inside) return@mapNotNull null
                words(from) + (if (caption.isEmpty()) " --> " else " -[$caption]-> ") + words(to)
            }
            assertEquals(expected.getValue(name), closing.toSet(), name)
        }
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
