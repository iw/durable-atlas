package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.ir.validate
import io.github.iw.durableatlas.render.Decoration
import io.github.iw.durableatlas.render.DiagramDecorator
import io.github.iw.durableatlas.render.DiagramOverlay
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.layout.LayoutBuilder
import io.github.iw.durableatlas.render.svg.compose.DiagramComposer
import io.github.iw.durableatlas.render.svg.compose.PathData
import io.github.iw.durableatlas.render.svg.engine.ElkLayoutEngine
import io.github.iw.durableatlas.render.svg.scene.SceneBuilder
import io.github.iw.durableatlas.render.svg.text.FontMetricsMeasurer
import io.github.iw.durableatlas.render.svg.text.TextFitter
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Labels that have no place beside their edge as a diagram is first arranged: the type that is
 * caught, between a call that may throw and the step below it that runs then, where what stands
 * beside the two takes the room on both sides. The diagram is arranged again with room for them.
 * How often a diagram is arranged is counted, never timed.
 */
class LabelRoomTest {
    private val documents = SvgDocuments.labelled()

    /** [document] arranged as it is for a file, and how many times that took. */
    private fun arranged(document: IrDocument, wrapWidth: Int = SvgOptions.DEFAULT_WRAP_WIDTH): Pair<PositionedGraph, Int> {
        val scene = SceneBuilder(LayoutBuilder.build(document, RenderOptions()), FontMetricsMeasurer, RenderOptions.DEFAULT_MAX_LABEL_LENGTH).build()
        val composer = DiagramComposer(scene, ElkLayoutEngine(), TextFitter(FontMetricsMeasurer), wrapWidth.toDouble())
        val graph = composer.compose()
        return graph to composer.arrangements
    }

    /** Where the badges of an overlay go that counts everything, as wide as the layout keeps room for. */
    private fun badges(graph: PositionedGraph): List<Box> =
        (graph.nodes.map { it.box } + graph.regions.map { it.box }).map { Geometry.badge(it, Geometry.BADGE_ROOM) }

    @Test
    fun `the documents are real workflow documents and hold nothing of the machine they were made on`() {
        val schema = JsonSchemaChecker.forWorkflowIr()
        val files = Files.list(SvgDocuments.labelled).use { it.sorted().toList() }
        assertEquals(documents.keys.map { "$it.ir.json" }, files.map { it.name })
        for (file in files) {
            val text = Files.readString(file)
            schema.assertValid(text)
            assertEquals(emptyList(), documents.getValue(file.name.removeSuffix(".ir.json")).validate(), file.name)
            // Every path is one inside a repository.
            assertEquals(emptyList(), Regex("\"file\": \"([^\"]*)\"").findAll(text).map { it.groupValues[1] }.filter { it.startsWith("/") || ":" in it || ".." in it }.toList(), file.name)
        }
    }

    @Test
    fun `a label with no place beside its arrow gets room there, and the diagram is arranged once more`() {
        val (graph, arrangements) = arranged(documents.getValue("Helped"))
        assertEquals(2, arrangements)
        assertEquals(emptyList(), LabelPlacement.problems(graph, badges(graph)))

        // The type that is caught stands to the left of its arrow, and the step before is a gap away from it.
        val edge = graph.edges.single { it.label?.text == "IllegalArgumentException" }
        val label = edge.label!!.box
        val arrow = PathData.points(edge.path)
        assertEquals(listOf(arrow.first().x), arrow.map { it.x }.distinct())
        assertEquals(arrow.first().x - 8, label.right, 0.11)
        val cards = graph.nodes.map { it.box }
        val before = cards.filter { it.right <= label.x && it.y < label.bottom + 30 && it.bottom > label.y - 30 }.maxOf { it.right }
        assertTrue(label.x - before >= Geometry.ROW_SPACING - 0.11, "the label starts ${label.x - before} after the step before it")
    }

    @Test
    fun `only the stack whose label has no place is given room`() {
        // Two calls that may throw the same, each with its step below it, then a loop. The first label has the room
        // under the start of the workflow to its left; the second has the first stack there, and the loop to its right.
        val (graph, arrangements) = arranged(documents.getValue("CaughtBesideLoop"))
        assertEquals(2, arrangements)
        fun box(id: String) = graph.nodes.single { it.id == id }.box
        val (first, second) = graph.edges.filter { it.label?.text == "IllegalArgumentException" }.map { it.label!!.box }

        // The first stack is as wide as its cards, a gap after the start, and its label reaches out of it on the left.
        assertEquals(Geometry.ROW_SPACING, box("n01").x - box("start").right, 0.11)
        assertTrue(first.x < box("n01").x - 50, "the first label starts at ${first.x}, its cards at ${box("n01").x}")
        // The second is as much wider as its label reaches out of the cards: the label starts where the stack does.
        assertEquals(second.width + 8 - box("n03").width / 2, box("n03").x - second.x, 0.11)
        assertTrue(second.x - box("n02").right >= Geometry.ROW_SPACING - 0.11, "the second label starts ${second.x - box("n02").right} after the first stack")
        assertEquals(box("n03").x, box("n04").x, 0.01)
    }

    @Test
    fun `a diagram whose labels all have a place is arranged once`() {
        val twice = SvgDocuments.all().filterKeys { !it.startsWith("labels/") }.filterValues { arranged(it).second != 1 }
        assertEquals(emptySet(), twice.keys)
        // Among the documents with such labels, those with room beside every stack, and those with none: steps that follow each other downwards.
        assertEquals(
            mapOf(
                "CaughtAfterFork" to 2, "CaughtAlone" to 1, "CaughtBesideLoop" to 2, "CaughtByRegions" to 1, "CaughtInFull" to 2, "CaughtInHandler" to 1,
                "CaughtInLane" to 2, "CaughtInLoop" to 1, "CaughtInRows" to 2, "CaughtSeveral" to 2, "FailedSteps" to 2, "Helped" to 2,
            ),
            documents.mapValues { arranged(it.value).second },
        )
    }

    @Test
    fun `where room for one label moves another out of its place, that one gets room as well`() {
        // In rows of 900 the first arrangement leaves one label without a place; with room for it the rows break elsewhere.
        val (graph, arrangements) = arranged(documents.getValue("CaughtInRows"), wrapWidth = 900)
        assertEquals(3, arrangements)
        assertEquals(emptyList(), LabelPlacement.problems(graph, badges(graph)))
    }

    @TestFactory
    fun `no label lies on anything, or on a badge, however the diagram is drawn`() = documents.map { (name, document) ->
        DynamicTest.dynamicTest(name) {
            val counted = object : DiagramDecorator {
                override fun node(node: IrNode) = Decoration(note = "×999")

                override fun entryPoint(entryPoint: EntryPoint) = Decoration(note = "×999")
            }

            fun found(options: SvgOptions = SvgOptions(), page: PageOptions = PageOptions()): List<String> {
                val svg = SvgFormat(options).prepare(document, page).render(DiagramOverlay(counted))
                val drawn = SvgDom(svg).elements.filter { it.getAttribute("class") == "badge-box" }.map { badge ->
                    fun number(name: String) = badge.getAttribute(name).toDouble()
                    Box(number("x"), number("y"), number("width"), number("height"))
                }
                val graph = SvgDiagrams.positionedGraph(svg)
                assertEquals(graph.nodes.size + graph.regions.size, drawn.size, name)
                // The badges as they are drawn, and the widest the layout keeps room for.
                return LabelPlacement.problems(graph, drawn) + LabelPlacement.problems(graph, badges(graph)).filter { "badge" in it }
            }
            val found = found() +
                found(SvgOptions(wrapWidth = 900)).map { "in rows up to 900 wide: $it" } +
                found(SvgOptions(wrapWidth = 0)).map { "in one row: $it" } +
                found(page = PageOptions(render = RenderOptions(maxNodes = 8))).map { "with 8 nodes at most: $it" }
            assertEquals(emptyList(), found, name)
        }
    }
}
