package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.render.Decoration
import io.github.iw.durableatlas.render.DiagramDecorator
import io.github.iw.durableatlas.render.DiagramOverlay
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.svg.text.FontMetricsMeasurer
import io.github.iw.durableatlas.render.svg.text.TextStyles
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where the labels of edges sit, checked for every document there is to draw. */
class SvgLabelTest {
    private fun each(check: (String, IrDocument) -> Unit): List<DynamicTest> =
        SvgDocuments.all().map { (name, document) -> DynamicTest.dynamicTest(name) { check(name, document) } }

    private fun problems(document: IrDocument, options: SvgOptions = SvgOptions(), page: PageOptions = PageOptions()): List<String> =
        LabelPlacement.problems(SvgDiagrams.positionedGraph(SvgFormat(options).prepare(document, page).render()))

    @TestFactory
    fun `every label sits beside its own edge, in the region that stretch runs in, and on nothing else`() = each { name, document ->
        // As it is drawn by default, in narrow rows, in one row, and with its regions drawn as single cards.
        val found = problems(document) +
            problems(document, SvgOptions(wrapWidth = 900)).map { "in rows up to 900 wide: $it" } +
            problems(document, SvgOptions(wrapWidth = 0)).map { "in one row: $it" } +
            problems(document, page = PageOptions(render = RenderOptions(maxNodes = 8))).map { "with 8 nodes at most: $it" }
        assertEquals(emptyList(), found, name)
    }

    @TestFactory
    fun `no edge runs through the header of a region or of the band`() = each { name, document ->
        fun found(options: SvgOptions = SvgOptions(), page: PageOptions = PageOptions()): List<String> =
            LabelPlacement.headerProblems(SvgDiagrams.positionedGraph(SvgFormat(options).prepare(document, page).render()))
        val found = found() +
            found(SvgOptions(wrapWidth = 900)).map { "in rows up to 900 wide: $it" } +
            found(SvgOptions(wrapWidth = 0)).map { "in one row: $it" } +
            found(page = PageOptions(render = RenderOptions(maxNodes = 8))).map { "with 8 nodes at most: $it" }
        assertEquals(emptyList(), found, name)
    }

    @TestFactory
    fun `no label lies on the badge of an overlay`() = each { name, document ->
        // The most an overlay can ask for: a count on every card, region and handler, as wide as the layout keeps room for.
        val counted = object : DiagramDecorator {
            override fun node(node: IrNode) = Decoration(note = COUNT)

            override fun entryPoint(entryPoint: EntryPoint) = Decoration(note = COUNT)
        }
        val svg = SvgFormat().prepare(document, PageOptions()).render(DiagramOverlay(counted))
        val graph = SvgDiagrams.positionedGraph(svg)
        // Badges are drawn in the coordinates of the diagram, as the recorded positions are.
        val badges = SvgDom(svg).elements.filter { it.getAttribute("class") == "badge-box" }.map { badge ->
            fun number(name: String) = badge.getAttribute(name).toDouble()
            Box(number("x"), number("y"), number("width"), number("height"))
        }
        assertEquals(graph.nodes.size + graph.regions.size, badges.size, name)
        assertEquals(emptyList(), LabelPlacement.problems(graph, badges), name)
    }

    @Test
    fun `the widest count the layout keeps room for has three digits`() {
        val width = FontMetricsMeasurer.width(COUNT, TextStyles.BADGE) + 2 * Geometry.BADGE_PADDING
        assertTrue(width <= Geometry.BADGE_ROOM && width > Geometry.BADGE_ROOM - 2, "a badge of $width")
    }

    @Test
    fun `the check notices a label in each kind of bad place`() {
        // A loop with one card in it, a card beside the loop, and an edge that runs down the loop to its bottom border.
        val loop = PositionedRegion("loop", StyleKinds.LOOP, "LOOP", Box(100.0, 100.0, 220.0, 240.0))
        val inside = PositionedNode("inside", "activity", Shape.CARD, Box(120.0, 150.0, 160.0, 60.0), "ACTIVITY", parent = "loop")
        val outside = PositionedNode("outside", "activity", Shape.CARD, Box(360.0, 150.0, 160.0, 60.0), "ACTIVITY")
        val other = PositionedEdge("loop", "outside", "flow", "M320 180H358", PositionedLabel("other", Box(330.0, 158.0, 20.0, 16.0)))

        fun found(label: Box, badges: List<Box> = emptyList(), width: Double = 600.0): List<String> {
            val edge = PositionedEdge("inside", "loop", "flow", "M200 210V338", PositionedLabel("repeat", label, "loop"))
            val graph = PositionedGraph(width, 400.0, regions = listOf(loop), nodes = listOf(inside, outside), edges = listOf(edge, other), rows = emptyList())
            // What is said about the label, without its name and place.
            return LabelPlacement.problems(graph, badges).filter { it.startsWith("'repeat'") }.map { it.substringAfter("16.0 ") }
        }

        // Beside the edge, inside the loop.
        assertEquals(emptyList(), found(Box(206.0, 250.0, 48.0, 16.0)))
        assertEquals(listOf("lies on the card 'inside'"), found(Box(206.0, 200.0, 48.0, 16.0)))
        // Across the border, and inside the box but on the line that is drawn there.
        assertEquals(listOf("lies on the border of 'loop'"), found(Box(206.0, 330.0, 48.0, 16.0)))
        assertEquals(listOf("lies on the border of 'loop'"), found(Box(206.0, 323.5, 48.0, 16.0)))
        assertEquals(emptyList(), found(Box(206.0, 322.0, 48.0, 16.0)))
        // Below the loop, beside the end of an edge that never leaves it.
        assertEquals(listOf("lies in no region, but its edge runs in 'loop' there"), found(Box(206.0, 342.0, 48.0, 16.0)))
        assertEquals(listOf("is 30.0 away from its edge"), found(Box(230.0, 250.0, 48.0, 16.0)))
        assertEquals(
            listOf(
                "lies on the label 'other' of loop -> outside",
                "lies on the border of 'loop'",
                "is 111.3 away from its edge",
                "lies in no region, but its edge runs in 'loop' there",
            ),
            found(Box(306.0, 160.0, 48.0, 16.0)),
        )
        assertEquals(listOf("lies on the header of 'loop'", "is 83.9 away from its edge"), found(Box(130.0, 113.0, 48.0, 16.0)))
        assertEquals(listOf("lies outside the diagram"), found(Box(206.0, 250.0, 48.0, 16.0), width = 250.0))
        assertEquals(listOf("lies on the badge at (240.0, 241.0) 32.0 x 18.0"), found(Box(206.0, 250.0, 48.0, 16.0), listOf(Box(240.0, 241.0, 32.0, 18.0))))
    }

    @Test
    fun `the check notices an edge that runs through the words of a header`() {
        val loop = PositionedRegion("loop", StyleKinds.LOOP, "LOOP · WHILE TRUE", Box(100.0, 100.0, 300.0, 240.0))
        val inside = PositionedNode("inside", "await", Shape.CARD, Box(114.0, 140.0, 260.0, 60.0), "AWAIT", parent = "loop")
        val band = Box(100.0, 0.0, 200.0, 60.0)

        fun found(path: String): List<String> {
            val graph = PositionedGraph(600.0, 400.0, band, listOf(loop), listOf(inside), listOf(PositionedEdge("signal", "inside", "wakes", path)), emptyList())
            return LabelPlacement.headerProblems(graph).map { it.substringBefore(" at ") }
        }

        // Straight down through the title, and beside it.
        assertEquals(listOf("the edge signal -> inside (wakes) runs through the header of 'loop'"), found("M200 60V138"))
        assertEquals(emptyList(), found("M300 60V138"))
        // Through the icon; below the header row; and a curve that crosses the title on its way.
        assertEquals(listOf("the edge signal -> inside (wakes) runs through the header of 'loop'"), found("M120 60V138"))
        assertEquals(emptyList(), found("M90 135H112"))
        assertEquals(listOf("the edge signal -> inside (wakes) runs through the header of 'loop'"), found("M150 60C150 120 320 100 320 138"))
        assertEquals(listOf("the edge signal -> inside (wakes) runs through the header of the band of the handlers"), found("M150 0V80H420V138"))
    }

    private companion object {
        const val COUNT = "×999"
    }
}
