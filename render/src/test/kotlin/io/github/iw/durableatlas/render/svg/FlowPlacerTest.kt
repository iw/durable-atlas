package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.layout.LayoutBuilder
import io.github.iw.durableatlas.render.svg.compose.FlowPlacer
import io.github.iw.durableatlas.render.svg.compose.Placed
import io.github.iw.durableatlas.render.svg.engine.ElkLayoutEngine
import io.github.iw.durableatlas.render.svg.engine.Flow
import io.github.iw.durableatlas.render.svg.scene.Scene
import io.github.iw.durableatlas.render.svg.scene.SceneBuilder
import io.github.iw.durableatlas.render.svg.scene.SceneEdge
import io.github.iw.durableatlas.render.svg.text.FontMetricsMeasurer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What a stack of two steps keeps free for the label of the edge between them. */
class FlowPlacerTest {
    /**
     * A step, then a call that may throw [caught] with the step below it that runs then, then
     * another step. With null the edge to the step below is plain flow and says nothing.
     */
    private fun scene(caught: String?): Scene {
        val nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("load", NodeKind.ACTIVITY, name = "LoadOrder"),
            IrNode("check", NodeKind.BRANCH, label = "Checks.known throws?"),
            IrNode("report", NodeKind.ACTIVITY, name = "Report"),
            IrNode("ship", NodeKind.ACTIVITY, name = "Ship"),
            IrNode("end", NodeKind.END),
        )
        val edges = listOf(
            IrEdge("start", "load"),
            IrEdge("load", "check"),
            if (caught == null) IrEdge("check", "report") else IrEdge("check", "report", EdgeKind.EXCEPTION, caught),
            IrEdge("check", "ship", label = "no"),
            IrEdge("report", "ship"),
            IrEdge("ship", "end"),
        )
        val document = IrDocument(
            commit = "0000000000000000000000000000000000000000",
            modelMode = ModelMode.GRADLE,
            workflowType = "Checked",
            interfaceName = "com.example.Checked",
            implementation = "com.example.CheckedImpl",
            module = ":made",
            entryPoints = listOf(EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, "Checked", "start")),
            nodes = nodes,
            edges = edges,
        )
        return SceneBuilder(LayoutBuilder.build(document, RenderOptions()), FontMetricsMeasurer, RenderOptions.DEFAULT_MAX_LABEL_LENGTH).build()
    }

    private fun Scene.edge(from: String, to: String): SceneEdge = mainEdges.single { it.from == from && it.to == to }

    private fun place(scene: Scene, roomy: Set<String> = emptySet()): Placed =
        FlowPlacer(scene, ElkLayoutEngine(), roomy).place(scene.main, scene.mainEdges, Flow.RIGHT)

    @Test
    fun `the edge between the two cards of a stack is the one a stack can keep room for`() {
        val scene = scene("IllegalArgumentException")
        val placer = FlowPlacer(scene, ElkLayoutEngine())
        assertEquals(listOf("check" to "report"), scene.mainEdges.filter(placer::inStack).map { it.from to it.to })
    }

    @Test
    fun `a stack keeps no room for its label unless it is told to`() {
        val scene = scene("IllegalArgumentException")
        val placed = place(scene)
        val top = placed.boxes.getValue("check")
        val label = assertNotNull(placed.routes.getValue(scene.edge("check", "report").id).label)
        // To the right of the arrow, and out of the stack on that side: a label of this length ends beyond the cards.
        assertEquals(top.centerX + 8, label.x, 0.01)
        assertTrue(label.right > top.right, "the label ends at ${label.right}, the cards at ${top.right}")
        // The stack is as wide as its cards: the step before it is one gap away.
        assertEquals(Geometry.ROW_SPACING, top.x - placed.boxes.getValue("load").right, 0.01)
    }

    @Test
    fun `a stack that keeps room has its label to the left of the arrow, clear of everything beside the stack`() {
        val scene = scene("IllegalArgumentException")
        val inner = scene.edge("check", "report")
        val plain = place(scene)
        val placed = place(scene, setOf(inner.id))
        val top = placed.boxes.getValue("check")
        val bottom = placed.boxes.getValue("report")
        val label = assertNotNull(placed.routes.getValue(inner.id).label)

        // Beside the arrow, between the two cards, which lie where they did in the stack.
        assertEquals(top.centerX - 8, label.right, 0.01)
        assertTrue(label.y > top.bottom && label.bottom < bottom.y, "$label between $top and $bottom")
        assertEquals(top.x, bottom.x, 0.01)
        assertEquals(plain.boxes.getValue("report").y - plain.boxes.getValue("check").y, bottom.y - top.y, 0.01)
        // The label reaches out of the cards on the left, and the step before the stack is a gap away from the label, not from the cards.
        val room = top.x - label.x
        assertEquals(inner.labelWidth + 8 - top.width / 2, room, 0.01)
        assertEquals(Geometry.ROW_SPACING, label.x - placed.boxes.getValue("load").right, 0.01)
        assertEquals(plain.width + room, placed.width, 0.01)
        // Nothing is where the badge of the lower card goes, and no other label or line is near the label.
        assertFalse(Geometry.badge(bottom, Geometry.BADGE_ROOM).intersects(label))
        val others = placed.routes.filterKeys { it != inner.id }.values
        assertEquals(emptyList(), others.mapNotNull { it.label }.filter { it.intersects(label.grown(2.0)) })
        assertEquals(emptyList(), others.filter { route -> route.points.zipWithNext().any { (a, b) -> Box.around(listOf(Box(a.x, a.y, 0.0, 0.0), Box(b.x, b.y, 0.0, 0.0)))!!.intersects(label.grown(2.0)) } })
        // The edge into the stack runs on across the room to the upper card, level all the way.
        val into = placed.routes.getValue(scene.edge("load", "check").id).points
        assertEquals(Point(top.x, top.centerY), into.last())
        assertEquals(listOf(top.centerY), into.map { it.y }.distinct())
        // The edges out of the stack start at its cards as before.
        assertEquals(Point(top.right, top.centerY), placed.routes.getValue(scene.edge("check", "ship").id).points.first())
        assertEquals(Point(bottom.right, bottom.centerY), placed.routes.getValue(scene.edge("report", "ship").id).points.first())
    }

    @Test
    fun `a stack that keeps room is no wider for a label that fits between its cards`() {
        val scene = scene("Fault")
        val inner = scene.edge("check", "report")
        val plain = place(scene)
        val placed = place(scene, setOf(inner.id))
        val top = placed.boxes.getValue("check")
        val label = assertNotNull(placed.routes.getValue(inner.id).label)
        assertEquals(top.centerX - 8, label.right, 0.01)
        assertTrue(label.x >= top.x, "the label starts at ${label.x}, the cards at ${top.x}")
        assertEquals(plain.width, placed.width, 0.01)
        assertEquals(plain.boxes, placed.boxes)
    }

    @Test
    fun `room is kept only for the label of the edge between the cards, and only if it says something`() {
        val scene = scene("IllegalArgumentException")
        val plain = place(scene)
        // An edge that leaves the stack is none a stack keeps room for.
        val other = place(scene, setOf(scene.edge("check", "ship").id))
        assertEquals(plain.boxes, other.boxes)
        assertEquals(plain.routes.mapValues { it.value.label }, other.routes.mapValues { it.value.label })

        val silent = scene(null)
        val inner = silent.edge("check", "report")
        val placed = place(silent, setOf(inner.id))
        assertEquals(place(silent).boxes, placed.boxes)
        assertEquals(null, placed.routes.getValue(inner.id).label)
    }
}
