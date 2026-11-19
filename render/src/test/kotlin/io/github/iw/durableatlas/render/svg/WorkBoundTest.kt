package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.layout.LayoutBuilder
import io.github.iw.durableatlas.render.svg.compose.DiagramComposer
import io.github.iw.durableatlas.render.svg.compose.Kinks
import io.github.iw.durableatlas.render.svg.compose.LabelTidier
import io.github.iw.durableatlas.render.svg.engine.EngineGraph
import io.github.iw.durableatlas.render.svg.engine.EngineLayout
import io.github.iw.durableatlas.render.svg.engine.EngineRoute
import io.github.iw.durableatlas.render.svg.engine.Flow
import io.github.iw.durableatlas.render.svg.engine.LayoutEngine
import io.github.iw.durableatlas.render.svg.scene.SceneBuilder
import io.github.iw.durableatlas.render.svg.text.FontMetricsMeasurer
import io.github.iw.durableatlas.render.svg.text.TextFitter
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The steps of arranging a diagram that are this tool's own must not do work that grows with the
 * square or the cube of the size of the diagram where they need not. The work is measured by the
 * memory a step asks for, which is the same on a slow machine as on a fast one, and never by the
 * time it takes: each of these steps once copied what it had at every turn, and the copies were
 * what took the time.
 */
class WorkBoundTest {
    private val megabyte = 1_000_000L

    /** The bytes the thread asks for while it does [work]; the test is skipped where the JVM does not count them. */
    private fun allocated(work: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(threads != null && threads.isThreadAllocatedMemorySupported && threads.isThreadAllocatedMemoryEnabled)
        val thread = Thread.currentThread().id
        val before = threads!!.getThreadAllocatedBytes(thread)
        work()
        return threads.getThreadAllocatedBytes(thread) - before
    }

    /** Puts the nodes of a graph side by side in the order given and joins them with straight lines, at next to no cost. */
    private class RowEngine : LayoutEngine {
        override fun layout(graph: EngineGraph): EngineLayout {
            val boxes = LinkedHashMap<String, Box>()
            var x = 0.0
            for (node in graph.nodes) {
                boxes[node.id] = Box(x, 0.0, node.width, node.height)
                x += node.width + Geometry.ROW_SPACING
            }
            val routes = graph.edges.associate { edge ->
                val from = boxes.getValue(edge.from)
                val to = boxes.getValue(edge.to)
                edge.id to EngineRoute(listOf(Point(from.right, from.centerY), Point(to.x, to.centerY)), null)
            }
            return EngineLayout(x - Geometry.ROW_SPACING, Geometry.CARD_HEIGHT, boxes, routes)
        }
    }

    @Test
    fun `looking for the rows of a long flow does not weigh every break against every step each time`() {
        val layout = LayoutBuilder.build(StressDocuments.chain(400), RenderOptions())
        val scene = SceneBuilder(layout, FontMetricsMeasurer, RenderOptions.DEFAULT_MAX_LABEL_LENGTH).build()
        var rows = 0
        val bytes = allocated {
            rows = DiagramComposer(scene, RowEngine(), TextFitter(FontMetricsMeasurer), SvgOptions.DEFAULT_WRAP_WIDTH.toDouble()).compose().rows.size
        }
        assertEquals(47, rows)
        // With every break of every row weighed in advance, which is to follow the flow from each of them anew: 4600 MB. As it is: 220 MB.
        assertTrue(bytes < 1000 * megabyte, "${bytes / megabyte} MB were asked for")
    }

    @Test
    fun `taking turns out of routes does not copy all routes for each of them`() {
        val count = 4000
        // Straight lines with a label each: there is no turn to take out of any, so nothing has to be looked at.
        val routes = (0 until count).associate { "e$it" to EngineRoute(listOf(Point(0.0, it * 20.0), Point(100.0, it * 20.0)), Box(40.0, it * 20.0 - 18, 20.0, 16.0)) }
        val boxes = (0 until count).associate { "n$it" to Box(-150.0, it * 20.0 - 8, 150.0, 16.0) }
        var smooth = emptyMap<String, EngineRoute>()
        val bytes = allocated { smooth = Kinks.removed(routes, boxes, Flow.RIGHT) }
        assertEquals(routes.keys.toList(), smooth.keys.toList())
        assertTrue(smooth.all { (id, route) -> route === routes[id] })
        // As it was: 2000 MB, for two copies of all routes per route. Now next to nothing.
        assertTrue(bytes < 200 * megabyte, "${bytes / megabyte} MB were asked for")
    }

    @Test
    fun `tidying labels does not gather the points of all other lines for each label`() {
        val count = 1000
        // Lines far apart, each with its label beside it: every label is in a good place.
        val edges = (0 until count).map { PositionedEdge("a$it", "b$it", "flow", "M0 ${it * 40}H400", PositionedLabel("label", Box(180.0, it * 40.0 - 22, 40.0, 16.0))) }
        var tidy = emptyList<PositionedEdge>()
        val bytes = allocated { tidy = LabelTidier.tidy(edges, emptyList(), emptyList(), emptyList()).edges }
        assertEquals(edges, tidy)
        // As it was: 2000 MB, for a list of the hundred thousand points of the other lines per label. Now 65 MB.
        assertTrue(bytes < 500 * megabyte, "${bytes / megabyte} MB were asked for")
    }
}
