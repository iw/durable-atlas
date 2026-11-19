package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.ids
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.title
import io.github.iw.durableatlas.render.svg.compose.PathData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SVG diagrams of the documents the analyser's tests expect for workflows that take steps while
 * their object is made, register handlers as they run, compensate side by side and retry.
 */
class SvgConstructsTest {
    private val documents: Map<String, IrDocument> =
        SvgDocuments.all().filterKeys { it.startsWith("analyser/constructs/") }.mapKeys { it.key.substringAfterLast('/') }

    private fun draw(name: String): String = SvgFormat().prepare(documents.getValue(name), PageOptions()).render()

    private fun IrDocument.step(name: String): String = nodes.single { it.name == name }.id

    @Test
    fun `every document is drawn with its labels in good places and no edge through a title`() {
        assertEquals(12, documents.size)
        for (name in documents.keys) {
            val graph = SvgDiagrams.positionedGraph(draw(name))
            assertEquals(emptyList(), LabelPlacement.problems(graph) + LabelPlacement.headerProblems(graph), name)
        }
    }

    @Test
    fun `a compensation that runs side by side holds a parallel section with a lane for each of its steps`() {
        val document = documents.getValue("SideBySide")
        val graph = SvgDiagrams.positionedGraph(draw("SideBySide"))
        val compensation = graph.regions.single { it.kind == StyleKinds.COMPENSATION }
        val parallel = graph.regions.single { it.kind == StyleKinds.PARALLEL }
        assertEquals(compensation.id, parallel.parent)
        assertTrue(compensation.box.contains(parallel.box))
        // The parallel section starts below the title of the compensation.
        assertEquals(compensation.box.y + Geometry.REGION_TOP, parallel.box.y, 0.11)

        fun box(name: String) = graph.nodes.single { it.id == document.step(name) }.also { assertEquals(parallel.id, it.parent) }.box
        // Three lanes one below the other, in the order the compensations were registered; the lane of two steps runs to the right.
        val lanes = listOf(box("CleanUp"), box("Report"), box("Release"))
        lanes.zipWithNext { upper, lower -> assertTrue(upper.bottom < lower.y) }
        assertEquals(1, lanes.map { it.x }.distinct().size)
        assertEquals(box("Report").y, box("Undo").y)
        assertTrue(box("Undo").x > box("Report").right)
        // Nothing joins the lanes, and nothing is drawn from the section to its steps or back: the boxes say it.
        val inside = graph.nodes.filter { it.parent == parallel.id }.map { it.id }.toSet()
        assertEquals(listOf(document.step("Report") to document.step("Undo")), graph.edges.filter { it.from in inside || it.to in inside }.map { it.from to it.to })
        assertTrue(graph.edges.none { it.from == parallel.id || it.to == parallel.id })

        // The failures of the three steps run into the compensation as one line, and the compensation goes on to the failure.
        val failures = graph.edges.filter { it.kind == "exception" }
        assertEquals(3, failures.size)
        assertEquals(setOf(compensation.id), failures.map { it.to }.toSet())
        assertEquals(1, failures.map { PathData.points(it.path).last() }.distinct().size)
        assertEquals(compensation.box.x - Geometry.EDGE_END_GAP, PathData.points(failures.first().path).last().x, 0.11)
        assertEquals(listOf("ActivityFailure"), failures.mapNotNull { it.label?.text })
        val failed = graph.edges.single { it.from == compensation.id }
        assertEquals(compensation.box.right, PathData.points(failed.path).first().x, 0.11)
    }

    @Test
    fun `a handler for every name is a card among the handlers that says so`() {
        val svg = draw("Listeners")
        val dom = SvgDom(svg)
        val graph = SvgDiagrams.positionedGraph(svg)
        val any = graph.nodes.single { it.id == "dynamic-update" }
        assertTrue(any.handler)
        assertEquals("UPDATE", any.kindLabel)
        assertEquals(listOf(EntryPoint.ANY_NAME), any.name)
        assertEquals("UPDATE: (any name)", dom.group("dynamic-update")!!.title())
        assertEquals(listOf("dynamic-update"), dom.group("dynamic-update")!!.ids("data-entry-points"))
        // What it runs hangs below its card, in the band.
        val report = graph.nodes.single { it.id == documents.getValue("Listeners").step("Report") }.box
        assertTrue(graph.band!!.contains(report) && report.y > any.box.bottom)
        assertEquals(setOf("pause", "resume", "isPaused", EntryPoint.ANY_NAME), graph.nodes.filter { it.handler }.map { it.name.single() }.toSet())

        val fallback = SvgDiagrams.positionedGraph(draw("dynamic-Fallback"))
        assertEquals(listOf("SIGNAL" to "(any name)", "QUERY" to "(any name)"), fallback.nodes.filter { it.handler }.map { it.kindLabel to it.name.single() })
        assertEquals(listOf("dynamic-signal"), fallback.edges.filter { it.kind == "wakes" }.map { it.from })
    }

    @Test
    fun `beside a handler that runs steps, the edges of the other handlers run straight down to the end of the band`() {
        val graph = SvgDiagrams.positionedGraph(draw("Listeners"))
        val band = graph.band!!
        val pause = graph.nodes.single { it.id == "signal-pause" }.box
        val resume = graph.nodes.single { it.id == "update-resume" }.box
        // The band is as high as the handler with steps needs, so the card of the one without ends well above its end.
        assertTrue(band.bottom - pause.bottom > Geometry.CARD_HEIGHT)

        val fromPause = PathData.points(graph.edges.single { it.from == "signal-pause" && it.kind == "wakes" }.path)
        val inBand = fromPause.filter { it.y <= band.bottom }
        assertEquals(listOf(pause.centerX), inBand.map { it.x }.distinct())
        assertEquals(band.bottom, inBand.maxOf { it.y }, 0.11)
        // So it does not cross the edge that runs down beside the steps of the handler next to it.
        val fromResume = PathData.points(graph.edges.single { it.from == "update-resume" && it.kind == "wakes" }.path)
        val channel = fromResume.last { it.y <= band.bottom }.x
        assertTrue(channel < resume.x && pause.centerX < channel, "the edge beside the steps runs down at $channel")
        // Below the band the two keep their order to where they arrive.
        assertTrue(fromPause.last().x < fromResume.last().x)
    }

    @Test
    fun `steps taken while the workflow object is made are the first steps of the main flow`() {
        val document = documents.getValue("InitSteps")
        val graph = SvgDiagrams.positionedGraph(draw("InitSteps"))
        val start = graph.nodes.single { it.kind == StyleKinds.START }
        val first = graph.edges.single { it.from == start.id }.to
        assertEquals(document.step("Prepare"), first)
        // They stand in the row of the workflow method's own steps, before them.
        val row = listOf("Prepare", "Check", "Work").map { name -> graph.nodes.single { it.id == document.step(name) }.box }
        assertEquals(1, (row.map { it.centerY } + start.box.centerY).distinct().size)
        (listOf(start.box) + row).zipWithNext { left, right -> assertTrue(left.right < right.x) }
        assertEquals(NodeKind.ACTIVITY, document.nodes.single { it.id == first }.kind)
    }
}
