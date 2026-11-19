package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.IrStats
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.children
import io.github.iw.durableatlas.render.svg.compose.PathData
import io.github.iw.durableatlas.render.svg.scene.SceneBuilder
import io.github.iw.durableatlas.render.svg.text.FontMetricsMeasurer
import io.github.iw.durableatlas.render.svg.text.TextFitter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where edges run and where their labels sit. */
class SvgRoutingTest {
    private fun graph(document: IrDocument, options: SvgOptions = SvgOptions()): PositionedGraph =
        SvgDiagrams.positionedGraph(SvgFormat(options).prepare(document, PageOptions()).render())

    private fun box(graph: PositionedGraph, id: String): Box =
        graph.nodes.firstOrNull { it.id == id }?.box ?: graph.regions.single { it.id == id }.box

    private fun PositionedGraph.edge(from: String, to: String, kind: String = "flow"): PositionedEdge =
        edges.single { it.from == from && it.to == to && it.kind == kind }

    private fun points(edge: PositionedEdge): List<Point> = PathData.points(edge.path)

    @Test
    fun `edges that say the same and lead to the same step meet and run on as one line with one label`() {
        val saga = graph(SvgDocuments.saga(3))
        assertEquals(1, saga.rows.size)
        val region = box(saga, "undo")
        val failures = saga.edges.filter { it.kind == "exception" }
        assertEquals(3, failures.size)
        val lines = failures.map(::points)
        // They arrive in one place, on the border of the region, over one last stretch.
        assertEquals(1, lines.map { it.last() }.distinct().size)
        assertEquals(region.x - Geometry.EDGE_END_GAP, lines.first().last().x, 0.11)
        assertEquals(listOf(lines.first().last().y), lines.map { it[it.size - 2].y }.distinct())
        // Each starts at its own step, so the line they share begins where they meet.
        assertEquals(3, lines.map { it.first() }.distinct().size)
        assertEquals(listOf("ActivityFailure"), failures.mapNotNull { it.label?.text })
    }

    @Test
    fun `edges that say different things enter a region each at a place of its own`() {
        val demo = graph(SvgDocuments.demo())
        val region = box(demo, "n04-parallel")
        val labelled = points(demo.edge("n02-fraud-check-version", "n04-parallel"))
        val plain = points(demo.edge("n03-fraud-check", "n04-parallel"))
        listOf(labelled, plain).forEach { assertEquals(region.x - Geometry.EDGE_END_GAP, it.last().x, 0.11) }
        assertTrue(abs(labelled.last().y - plain.last().y) >= 14, "the two edges enter ${abs(labelled.last().y - plain.last().y)} apart")
        // Both run in straight: the one from the step below enters level with that step.
        assertEquals(1, labelled.map { it.y }.distinct().size)
        assertEquals(1, plain.map { it.y }.distinct().size)
        assertEquals(box(demo, "n03-fraud-check").centerY, plain.last().y, 0.11)
        // The flow goes on through the middle of the region, on the line it came in on.
        assertEquals(points(demo.edge("n04-parallel", "n07-await-payment")).first().y, labelled.last().y, 0.11)

        // The same for an edge that leaves a loop early and the edge that leaves it at its end.
        val ends = graph(SvgDocuments.regionEnds())
        val early = points(ends.edge("full", "notify")).last()
        val usual = points(ends.edge("orders", "notify")).last()
        assertEquals(early.x, usual.x, 0.11)
        assertTrue(abs(early.y - usual.y) >= 14)
    }

    @Test
    fun `two edges between the same two steps run side by side, evenly around the middle`() {
        val demo = graph(SvgDocuments.demo())
        val await = box(demo, "n07-await-payment")
        val cancelled = demo.edge("n07-await-payment", "n08-compensation")
        val timeout = demo.edge("n07-await-payment", "n08-compensation", "timeout")
        val above = points(cancelled)
        val below = points(timeout)
        assertEquals(await.centerY, (above.first().y + below.first().y) / 2, 0.11)
        assertTrue(above.first().y < below.first().y)
        listOf(above, below).forEach { assertEquals(1, it.map { point -> point.y }.distinct().size) }
        // Each label is on the far side of its own line.
        assertTrue(cancelled.label!!.box.bottom <= above.first().y)
        assertTrue(timeout.label!!.box.y >= below.first().y)
    }

    @Test
    fun `a step whose edges lead to steps of their own sits in the middle of them`() {
        val demo = graph(SvgDocuments.demo())
        val branch = box(demo, "n19-continue-as-new-suggested")
        val yes = demo.edge("n19-continue-as-new-suggested", "n20-continue-as-new")
        val no = demo.edge("n19-continue-as-new-suggested", "n21-end")
        assertEquals(branch.centerY, (box(demo, "n20-continue-as-new").centerY + box(demo, "n21-end").centerY) / 2, 0.11)
        assertTrue(box(demo, "n20-continue-as-new").centerY < branch.centerY)
        // The edges part after a common start, and each label is beside its own edge, away from the other.
        assertEquals(points(yes).take(2), points(no).take(2))
        assertTrue(yes.label!!.box.bottom <= points(yes).last().y)
        assertTrue(no.label!!.box.y >= points(no).last().y)
        // The line that leads to the branch stays straight.
        assertEquals(box(demo, "n18-returns-window").centerY, branch.centerY, 0.11)

        // Arms that meet again do so in the middle as well.
        val diamond = graph(SvgDocuments.diamond())
        val middle = (box(diamond, "courier").centerY + box(diamond, "post").centerY) / 2
        assertEquals(middle, box(diamond, "express").centerY, 0.11)
        assertEquals(middle, box(diamond, "track").centerY, 0.11)
        assertEquals(box(diamond, "start").centerY, box(diamond, "more").centerY, 0.11)
        assertEquals(box(diamond, "more").centerY, (box(diamond, "again").centerY + box(diamond, "end").centerY) / 2, 0.11)
    }

    @Test
    fun `a step that leads to the end of its region has its edge run to the border of that region`() {
        val drawn = graph(SvgDocuments.regionEnds())
        val expected = mapOf(
            "skip" to "orders", "short" to "orders", "full" to "orders",
            "urgent" to "notify", "copy" to "notify", "charged" to "undo",
        )
        for ((from, region) in expected) {
            val edge = drawn.edge(from, region)
            val around = box(drawn, region)
            val line = points(edge)
            line.forEach { assertTrue(around.grown(0.05).contains(it), "$from -> $region leaves the region at $it") }
            // Loops and compensations end at the bottom, parallel sections at the right.
            if (region == "notify") {
                assertEquals(around.right - Geometry.EDGE_END_GAP, line.last().x, 0.11)
            } else {
                assertEquals(around.bottom - Geometry.EDGE_END_GAP, line.last().y, 0.11)
            }
            assertTrue(around.contains(edge.label!!.box), "the label of $from -> $region")
            // It passes no step of the region on its way.
            val steps = drawn.nodes.filter { it.parent == region && it.id != from }.map { it.box }
            assertTrue(line.zipWithNext().none { (a, b) -> steps.any { it.intersects(Box.around(listOf(Box(a.x, a.y, 0.0, 0.0), Box(b.x, b.y, 0.0, 0.0)))!!.grown(1.0)) } })
        }
        // Each of them ends in a place of its own.
        assertEquals(3, listOf("skip", "short", "full").map { points(drawn.edge(it, "orders")).last() }.distinct().size)
    }

    @Test
    fun `a step that leads to the end of a region further out has its edge leave the regions between and run to the border of that one`() {
        val drawn = graph(SvgDocuments.outerEnds())

        /** An edge from [from] to the end of [region], out of the regions [between], innermost first. */
        data class Ending(val from: String, val region: String, val between: List<String>, val label: String, val kind: String = "flow")
        val expected = listOf(
            // A step, and the failure of one, that go on with the next order: out of the loop of items.
            Ending("close", "orders", listOf("items"), "repeat"),
            Ending("pick", "orders", listOf("items"), "ActivityFailure", "exception"),
            // The arm of a branch that goes on with the next batch: out of two loops.
            Ending("full", "batches", listOf("items", "orders"), "yes"),
            // Out of a loop to the end of its lane, and to the end of the compensation.
            Ending("answered", "notify", listOf("calls"), "yes"),
            Ending("settled", "undo", listOf("refunds"), "yes"),
        )
        for ((from, region, between, label, kind) in expected) {
            val words = "$from -> $region"
            val edge = drawn.edge(from, region, kind)
            val around = box(drawn, region)
            val line = points(edge)
            line.forEach { assertTrue(around.grown(0.05).contains(it), "$words leaves the region at $it") }
            // Loops and compensations end at the bottom, parallel sections at the right, and the edge arrives there straight.
            val sideways = region == "notify"
            if (sideways) {
                assertEquals(around.right - Geometry.EDGE_END_GAP, line.last().x, 0.11, words)
                assertEquals(line[line.size - 2].y, line.last().y, 0.11, words)
            } else {
                assertEquals(around.bottom - Geometry.EDGE_END_GAP, line.last().y, 0.11, words)
                assertEquals(line[line.size - 2].x, line.last().x, 0.11, words)
            }
            // It starts inside every region between and ends outside it, crossing the border on the side it is headed for.
            for (inner in between.map { box(drawn, it) }) {
                assertTrue(inner.contains(line.first()), "$words starts outside a region around $from")
                val crossings = line.zipWithNext().filter { (a, b) -> inner.contains(a) != inner.contains(b) }
                assertEquals(1, crossings.size, "$words crosses a border more than once")
                val (inside, outside) = crossings.single()
                if (sideways) {
                    assertTrue(outside.x > inner.right && inside.y == outside.y, "$words leaves a region around $from at $inside to $outside")
                } else {
                    assertTrue(outside.y > inner.bottom && inside.x == outside.x, "$words leaves a region around $from at $inside to $outside")
                }
            }
            // It passes no step on its way, of whichever region.
            val steps = drawn.nodes.filter { it.id != from }.map { it.box }
            assertTrue(
                line.zipWithNext().none { (a, b) -> steps.any { it.intersects(Box.around(listOf(Box(a.x, a.y, 0.0, 0.0), Box(b.x, b.y, 0.0, 0.0)))!!.grown(1.0)) } },
                "$words runs over a step",
            )
            // Its label says which way it is, and lies in the region and on the border of none.
            assertEquals(label, edge.label?.text, words)
            val text = edge.label!!.box
            assertTrue(around.contains(text), "the label of $words")
            drawn.regions.forEach { assertTrue(it.box.contains(text) || !it.box.intersects(text), "the label of $words lies on the border of ${it.id}") }
        }
        // In a region whose steps follow each other downwards, the label is beside the step the edge starts at.
        listOf("close" to "orders", "full" to "batches").forEach { (from, region) ->
            assertEquals("items", drawn.edge(from, region).label?.inside)
        }
        assertEquals("refunds", drawn.edge("settled", "undo").label?.inside)

        // Edges that end at the same border do so each in a place of its own.
        assertEquals(2, listOf(drawn.edge("close", "orders"), drawn.edge("pick", "orders", "exception")).map { points(it).last() }.distinct().size)
        // The edges to the end of a step's own region are drawn beside them as they are without them.
        val items = box(drawn, "items")
        for (edge in listOf(drawn.edge("stock", "items"), drawn.edge("pick", "items"))) {
            points(edge).forEach { assertTrue(items.grown(0.05).contains(it), "${edge.from} -> items leaves the loop at $it") }
            assertEquals(items.bottom - Geometry.EDGE_END_GAP, points(edge).last().y, 0.11)
        }
        assertEquals(listOf("no", "repeat"), listOf(drawn.edge("stock", "items"), drawn.edge("pick", "items")).map { it.label?.text })
        // The regions lie inside each other as the document says, and nothing leads from a region to its own steps.
        assertEquals(
            mapOf("batches" to null, "orders" to "batches", "items" to "orders", "notify" to null, "calls" to "notify", "undo" to null, "refunds" to "undo"),
            drawn.regions.associate { it.id to it.parent },
        )
        assertTrue(drawn.edges.none { edge -> generateSequence(edge.to) { id -> (drawn.nodes.firstOrNull { it.id == id }?.parent ?: drawn.regions.firstOrNull { it.id == id }?.parent) }.drop(1).any { it == edge.from } })
    }

    @Test
    fun `an edge that passes the border of a region on its way further out does not run on as the edge of that region`() {
        // for each order { Prepare; for each item { PickItem; go on with the next order }; CloseOrder }
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("orders", NodeKind.LOOP, label = "for each order"),
            IrNode("prepare", NodeKind.ACTIVITY, name = "Prepare", parent = "orders"),
            IrNode("items", NodeKind.LOOP, label = "for each item", parent = "orders"),
            IrNode("pick", NodeKind.ACTIVITY, name = "PickItem", parent = "items"),
            IrNode("close", NodeKind.ACTIVITY, name = "CloseOrder", parent = "orders"),
            IrNode("end", NodeKind.END),
        )
        val edges = listOf(IrEdge("start", "orders"), IrEdge("orders", "end"), IrEdge("prepare", "items"), IrEdge("items", "close"), IrEdge("pick", "orders"))
        val drawn = graph(SvgDocuments.skipping().copy(nodes = nodes, edges = edges, stats = IrStats.of(nodes)))

        val inner = box(drawn, "items")
        val jump = points(drawn.edge("pick", "orders"))
        val onward = points(drawn.edge("items", "close"))
        // The jump goes straight down through the bottom of the inner loop, where the loop's own edge would start.
        assertEquals(1, jump.map { it.x }.distinct().size)
        assertEquals(box(drawn, "orders").bottom - Geometry.EDGE_END_GAP, jump.last().y, 0.11)
        assertEquals(inner.bottom, onward.first().y, 0.11)
        assertTrue(abs(onward.first().x - jump.first().x) >= 14, "the two leave the loop ${abs(onward.first().x - jump.first().x)} apart")
        // The edge into the loop moved with the one out of it, so the line through the loop is straight.
        assertEquals(onward.first().x, points(drawn.edge("prepare", "items")).last().x, 0.11)
        assertEquals("repeat", drawn.edge("pick", "orders").label?.text)
    }

    @Test
    fun `an edge that leaves a lane sideways for the end of the loop around it goes around the lanes in its way`() {
        val drawn = graph(SvgDocuments.laneJumps())
        val loop = box(drawn, "orders")
        val cards = drawn.nodes.associate { it.id to it.box }
        fun runsOver(edge: PositionedEdge): List<String> = cards.filter { (id, card) ->
            id != edge.from && points(edge).zipWithNext().any { (a, b) -> card.intersects(Box.around(listOf(Box(a.x, a.y, 0.0, 0.0), Box(b.x, b.y, 0.0, 0.0)))!!.grown(1.0)) }
        }.keys.toList()

        // A lane lies below the branch. The edge leaves the branch downwards, as the loop flows, and turns aside before that lane.
        val jump = drawn.edge("cancelled", "orders")
        val line = points(jump)
        val branch = cards.getValue("cancelled")
        assertEquals(branch.bottom, line.first().y, 0.11)
        assertTrue(line.first().x > branch.x && line.first().x < branch.right)
        val below = drawn.nodes.filter { it.parent == "checks" && it.box.y > branch.bottom && it.box.x < line.first().x && it.box.right > line.first().x }
        assertEquals(1, below.size, "no lane lies below the branch")
        assertEquals(emptyList(), runsOver(jump))
        assertTrue(line[1].y < below.single().box.y, "the edge turns only at ${line[1]}")
        assertEquals(loop.bottom - Geometry.EDGE_END_GAP, line.last().y, 0.11)
        line.forEach { assertTrue(loop.grown(0.05).contains(it), "the edge leaves the loop at $it") }
        assertEquals("yes", jump.label?.text)
        assertEquals("orders", jump.label?.inside)

        // A branch and the step that one of its arms runs both lead to the end of the round: each edge starts at its own card.
        val arm = drawn.edge("urgent", "orders")
        val step = drawn.edge("page", "orders")
        for ((edge, card) in listOf(arm to cards.getValue("urgent"), step to cards.getValue("page"))) {
            val start = points(edge).first()
            assertEquals(card.bottom, start.y, 0.11)
            assertTrue(start.x > card.x && start.x < card.right, "${edge.from} -> orders starts at $start")
            assertEquals(emptyList(), runsOver(edge))
            assertEquals(loop.bottom - Geometry.EDGE_END_GAP, points(edge).last().y, 0.11)
        }
        assertEquals(listOf("no", "repeat"), listOf(arm, step).map { it.label?.text })
        // The two cards stand side by side, as the arm between them says.
        assertEquals(cards.getValue("urgent").centerY, cards.getValue("page").centerY, 0.11)
    }

    @Test
    fun `the label of an edge that ends at the border of a region right after its step stays inside the region`() {
        // Both arms of a branch end the lane, and nothing follows the branch in it.
        val base = SvgDocuments.skipping()
        val nodes = base.nodes.filter { it.id != "page" }
        val document = base.copy(
            nodes = nodes,
            edges = base.edges.filter { it.to != "page" } + IrEdge("urgent", "both", label = "yes"),
            stats = IrStats.of(nodes),
        )
        val drawn = graph(document)
        val section = box(drawn, "both")
        val arms = drawn.edges.filter { it.from == "urgent" && it.to == "both" }
        assertEquals(setOf("yes", "no"), arms.map { it.label?.text }.toSet())
        arms.mapNotNull { it.label }.forEach { assertTrue(section.contains(it.box), "'${it.text}' lies on the border of the section") }
        assertEquals(2, arms.map { points(it).last() }.distinct().size)
    }

    @Test
    fun `edges from one step to the end of a region are each labelled beside their own line`() {
        /** The edges that lead from a step to the end of a region around it, by step and region. */
        fun arms(drawn: PositionedGraph): Map<Pair<String, String>, List<PositionedEdge>> {
            val parents = drawn.nodes.associate { it.id to it.parent } + drawn.regions.associate { it.id to it.parent }
            return drawn.edges.filter { edge -> generateSequence(parents[edge.from]) { parents[it] }.any { it == edge.to } }.groupBy { it.from to it.to }
        }

        fun check(name: String, drawn: PositionedGraph, expected: Map<Pair<String, String>, Set<String>>) {
            val arms = arms(drawn)
            assertEquals(expected, arms.mapValues { (_, edges) -> edges.mapNotNull { it.label?.text }.toSet() }, name)
            for ((ends, edges) in arms) {
                val words = "$name: ${ends.first} -> ${ends.second}"
                val around = box(drawn, ends.second)
                // They part on the way and end each in a place of its own.
                assertEquals(edges.size, edges.map { points(it).last() }.distinct().size, words)
                for (edge in edges) {
                    val label = edge.label ?: continue
                    assertTrue(around.contains(label.box), "$words: '${label.text}' lies outside the region")
                    assertTrue(drawn.nodes.none { it.box.intersects(label.box) }, "$words: '${label.text}' lies on a card")
                    // The label is right beside its own line, and nearer to it than to the lines of the other arms.
                    val own = LabelPlacement.distance(edge)
                    assertTrue(own <= 10.0, "$words: '${label.text}' is $own from its line")
                    edges.filter { it !== edge }.forEach { other ->
                        assertTrue(LabelPlacement.distance(other, label.box) > own, "$words: '${label.text}' is nearer to another arm")
                    }
                }
            }
        }

        val made = graph(SvgDocuments.regionArms())
        check(
            "made/region-arms",
            made,
            mapOf(
                ("kind" to "orders") to setOf("\"express\"", "\"standard\"", "else"),
                ("pack" to "orders") to setOf("repeat", "ActivityFailure"),
                ("urgent" to "notify") to setOf("yes", "no"),
                ("copy" to "notify") to setOf("yes", "no"),
                ("heavy" to "batches") to setOf("yes", "no"),
                // The end of a compensation is no round: the plain way there says nothing.
                ("recall" to "undo") to setOf("ActivityFailure"),
            ),
        )
        // Arms that go on with the next round of a loop further out are labelled beside the branch, in its own loop.
        made.edges.filter { it.from == "heavy" }.forEach { assertEquals("items", it.label?.inside) }

        // The same in the documents the analyser gives for the arms of a `when`, for `continue` and for a failure that is passed over.
        val analysed = SvgDocuments.all()
        for ((name, labels) in listOf("WhenArms" to setOf("\"skip\"", "else"), "BranchContinue" to setOf("yes", "no"), "CaughtFailure" to setOf("repeat", "ActivityFailure"))) {
            val drawn = graph(analysed.getValue("analyser/exits/$name"))
            check("analyser/exits/$name", drawn, arms(drawn).mapValues { labels })
            assertEquals(1, arms(drawn).size, name)
        }
    }

    @Test
    fun `two edges between the same two steps of a region run side by side and are labelled on their outer sides`() {
        val drawn = graph(SvgDocuments.regionPairs())
        // In a loop, and in what a handler runs, the steps follow each other downwards: both edges run straight
        // down, and the labels are level with each other, to the left of the left edge and to the right of the right one.
        for ((from, to) in listOf("wait" to "book", "remind.wait" to "remind.log")) {
            val answered = drawn.edge(from, to)
            val late = drawn.edge(from, to, "timeout")
            listOf(answered, late).forEach { assertEquals(1, points(it).map { point -> point.x }.distinct().size, "$from -> $to turns") }
            assertEquals(28.0, points(late).first().x - points(answered).first().x, 0.11)
            assertEquals(box(drawn, from).centerX, (points(answered).first().x + points(late).first().x) / 2, 0.11)
            val left = answered.label!!.box
            val right = late.label!!.box
            assertTrue(left.right <= points(answered).first().x && left.right > points(answered).first().x - 10)
            assertTrue(right.x >= points(late).first().x && right.x < points(late).first().x + 10)
            assertEquals(left.y, right.y, 0.11)
        }
        listOf(drawn.edge("wait", "book"), drawn.edge("wait", "book", "timeout")).forEach { assertEquals("rounds", it.label?.inside) }
        drawn.edges.filter { it.from == "remind.wait" }.mapNotNull { it.label }.forEach { assertTrue(drawn.band!!.contains(it.box), "'${it.text}' leaves the band") }

        // In a lane they follow each other from left to right: both run straight across, labelled above the upper and below the lower.
        for ((from, to) in listOf("confirm" to "log", "hold" to "ship")) {
            val upper = drawn.edge(from, to)
            val lower = drawn.edge(from, to, "timeout")
            listOf(upper, lower).forEach { assertEquals(1, points(it).map { point -> point.y }.distinct().size, "$from -> $to turns") }
            assertTrue(points(upper).first().y < points(lower).first().y)
            assertTrue(upper.label!!.box.bottom <= points(upper).first().y)
            assertTrue(lower.label!!.box.y >= points(lower).first().y)
        }
    }

    @Test
    fun `the edge from a handler to what it wakes runs past the steps the handler runs`() {
        val drawn = graph(SvgDocuments.handlerSteps())
        val band = drawn.band!!
        val card = drawn.nodes.single { it.id == "signal-cancelOrder" }
        val steps = listOf("signal-cancelOrder.notify", "signal-cancelOrder.grace").map { box(drawn, it) }
        assertTrue(card.handler)
        steps.forEach { assertTrue(band.contains(it) && it.y > card.box.bottom) }
        assertTrue(steps[0].bottom < steps[1].y)
        assertEquals("flow", drawn.edge("signal-cancelOrder", "signal-cancelOrder.notify").kind)
        assertEquals("flow", drawn.edge("signal-cancelOrder.notify", "signal-cancelOrder.grace").kind)
        // Where the handler returns is not drawn.
        assertTrue(drawn.edges.none { it.from == "signal-cancelOrder.grace" })

        val wake = drawn.edge("signal-cancelOrder", "n07-await-payment", "wakes")
        val line = points(wake)
        assertTrue(line.none { point -> steps.any { it.grown(2.0).contains(point) } }, "the edge runs over a step of the handler")
        val await = box(drawn, "n07-await-payment")
        assertEquals(await.y - Geometry.EDGE_END_GAP, line.last().y, 0.11)
        // The label of the two edges that wake the await lies on no card.
        val label = drawn.edges.filter { it.kind == "wakes" }.mapNotNull { it.label }.single()
        assertTrue(drawn.nodes.none { it.box.intersects(label.box) })
    }

    @Test
    fun `an edge that wakes a step right below the title of its loop passes beside the title, or comes in from the side`() {
        val drawn = graph(SvgDocuments.wokenLoops())
        fun title(id: String): Box {
            val region = drawn.regions.single { it.id == id }
            return Geometry.header(region.box, SceneBuilder.headerWidth(TextFitter(FontMetricsMeasurer), region.title))
        }

        // A short title leaves the right of the card free: both edges arrive there, from above, apart from each other.
        val wait = box(drawn, "wait")
        val short = title("inbox")
        assertTrue(short.x < wait.centerX && wait.centerX < short.right, "the title lies above the middle of the card")
        val arrivals = listOf("signal-post", "signal-finish").map { points(drawn.edge(it, "wait", "wakes")).last() }
        arrivals.forEach {
            assertEquals(wait.y - Geometry.EDGE_END_GAP, it.y, 0.11)
            assertTrue(it.x > short.right + 8 && it.x < wait.right - 10, "arrives at ${it.x}, the title ends at ${short.right}")
        }
        assertTrue(abs(arrivals[0].x - arrivals[1].x) >= 24)

        // A title as wide as the card leaves no room: the edge goes down beside the loop and into the side of the card.
        val settle = box(drawn, "settle")
        val long = title("replies")
        assertTrue(long.x < settle.x + 24 && long.right > settle.right - 24, "the title covers the card")
        val end = points(drawn.edge("signal-cancel", "settle", "wakes")).last()
        assertEquals(settle.centerY, end.y, 0.11)
        assertEquals(settle.x - Geometry.EDGE_END_GAP, end.x, 0.11)

        assertEquals(emptyList(), LabelPlacement.headerProblems(drawn))
        // The words of the titles are code and plain words: only plain words are set in capitals.
        assertEquals(listOf("LOOP · WHILE TRUE", "LOOP · while !openReplies!!.isEmpty()"), drawn.regions.map { it.title })
    }

    @Test
    fun `no label lies on a card or on another label`() {
        for ((name, document) in SvgDocuments.all().filterKeys { it.startsWith("made/") || it.startsWith("render/") || it.startsWith("demo/") }) {
            val drawn = graph(document)
            val labels = drawn.edges.mapNotNull { it.label }
            for (label in labels) {
                drawn.nodes.forEach { assertTrue(!it.box.intersects(label.box), "$name: '${label.text}' lies on '${it.id}'") }
                labels.filter { it !== label }.forEach { assertTrue(!it.box.intersects(label.box), "$name: '${label.text}' lies on '${it.text}'") }
            }
        }
    }

    @Test
    fun `rows are balanced where the flow can be broken in more than one place`() {
        // Ten steps of about the same width, a little more than a row holds: five and five, not nine and one.
        val steps = (1..10).map { "a%02d".format(it) }
        val document = SvgDocuments.saga(10).let { saga ->
            saga.copy(
                nodes = saga.nodes.filter { it.id in steps || it.id == "start" || it.id == "end" },
                edges = saga.edges.filter { it.to != "undo" && it.from != "undo" },
                stats = IrStats.of(saga.nodes.filter { it.id in steps || it.id == "start" || it.id == "end" }),
            )
        }
        val rows = graph(document).rows
        assertEquals(2, rows.size)
        rows.forEach { assertTrue(it.width <= SvgOptions.DEFAULT_WRAP_WIDTH) }
        assertTrue(abs(rows[0].width - rows[1].width) < 400, "rows of ${rows.map { it.width }}")

        // A limit just below the width of a row moves a step down; it does not add a row to the demo.
        val demo = graph(SvgDocuments.demo(), SvgOptions(wrapWidth = 1690))
        assertEquals(2, demo.rows.size)
        demo.rows.forEach { assertTrue(it.width <= 1690, "row of ${it.width}") }
    }

    @Test
    fun `the legend fills its rows evenly`() {
        for ((name, document) in SvgDocuments.all()) {
            val dom = SvgDom(SvgFormat().prepare(document, PageOptions()).render())
            val lines = dom.legendEntries.groupBy { entry -> entry.children().single { it.localName == "text" }.getAttribute("y") }.values.map { it.size }
            if (lines.isEmpty()) continue
            // No row has more entries than the first, and the last is short of it by less than there are rows.
            assertTrue(lines.all { it <= lines.first() }, "$name: $lines")
            assertTrue(lines.last() > lines.first() - lines.size, "$name: $lines")
        }
    }
}
