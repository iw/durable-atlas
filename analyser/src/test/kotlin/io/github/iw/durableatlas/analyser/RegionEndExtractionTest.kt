package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.CodePrinter
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.Return
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.walk
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Jumps that end at the end of a region further out than the one they are made in. Such a jump
 * is an edge from where it is to the node of that region, whatever lies between the two.
 *
 * Each test gives the whole graph of one workflow of [RegionEndSources], without ids: a node is
 * its kind and its line, as [IrStructure] writes it. The documents are kept as golden files as
 * well, for the tests of what is drawn from them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegionEndExtractionTest {
    private lateinit var backend: SourceBackend
    private lateinit var discovery: Discovery
    private lateinit var documents: List<IrDocument>

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val sources = SyntheticSources(directory)
        RegionEndSources.FILES.forEach { (name, text) -> sources.file(":app", "ends/$name.kt", text) }
        backend = sources.open()
        discovery = TemporalDiscovery.discover(backend)
        documents = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.DEGRADED).extract(discovery)
    }

    @AfterAll
    fun close() {
        backend.close()
    }

    /** The nodes and edges of a workflow, without ids. */
    private fun graph(workflowType: String): List<String> =
        documents.single { it.workflowType == workflowType }.outline().filterNot { it.startsWith("entry point ") }

    @Test
    fun `every document is well formed and gives the golden IR`() {
        assertEquals(RegionEndSources.FILES.keys - "Tasks", documents.map { it.workflowType }.toSet())
        for (document in documents) {
            assertWellFormed(document)
            GoldenFiles.assertMatches("ir/ends/${document.workflowType}.ir.json", IrJson.encode(document))
        }
    }

    @Test
    fun `reading everything an entry point reaches gives the graph that sparing what holds nothing gives`() {
        assertEquals(documents.sumOf { it.nodes.size }, assertSparingChangesNothing(backend, discovery))
    }

    @Test
    fun `continue of an outer loop leads from inside the inner loop to the outer one`() {
        assertEquals(
            listOf(
                "node activity@19: name Prepare, parent loop@18",
                "node activity@22: name Work, parent loop@20",
                "node activity@24: name Report, parent loop@18",
                "node branch@21: label part.isEmpty(), parent loop@20",
                "node end(main)",
                "node loop@18: label for each id",
                "node loop@20: label for each part, parent loop@18",
                "node start@17",
                "edge activity@19 -> loop@20",
                "edge branch@21 -> activity@22 [no]",
                // `continue@outer`: the next round of the outer loop, past what follows the inner one.
                "edge branch@21 -> loop@18 [yes]",
                "edge loop@18 -> end(main)",
                // The inner loop, once it is done, leads on as before.
                "edge loop@20 -> activity@24",
                "edge start@17 -> loop@18",
            ),
            graph("OuterContinue"),
        )
    }

    @Test
    fun `break of a loop that the round of the outer loop ends with leads to the outer loop`() {
        assertEquals(
            listOf(
                "node activity@19: name Prepare, parent loop@18",
                "node activity@23: name Work, parent loop@20",
                "node activity@27: name Check, parent loop@27",
                "node activity@28: name Report, parent loop@27",
                "node branch@21: label part == \"x\", parent loop@20",
                "node branch@22: label part == \"y\", parent loop@20",
                "node end(main)",
                "node loop@18: label for each id",
                "node loop@20: label for each part, parent loop@18",
                "node loop@26: label for each id",
                "node loop@27: label while tasks.check(id), parent loop@26",
                "node start@17",
                "edge activity@19 -> loop@20",
                "edge activity@27 -> activity@28",
                // A `break` that no branch stands before: a plain edge, which nothing implies.
                "edge activity@28 -> loop@26",
                "edge branch@21 -> branch@22 [no]",
                // Nothing follows the inner loop, so leaving it ends the round of the outer one.
                "edge branch@21 -> loop@18 [yes]",
                "edge branch@22 -> activity@23 [no]",
                // The same with the loop named.
                "edge branch@22 -> loop@18 [yes]",
                "edge loop@18 -> loop@26",
                "edge loop@26 -> end(main)",
                "edge start@17 -> loop@18",
            ),
            graph("LastLoopBreak"),
        )
    }

    @Test
    fun `a jump leads to the loop whose round it ends, however many loops lie between`() {
        assertEquals(
            listOf(
                "node activity@19: name Prepare, parent loop@18",
                "node activity@25: name Work, parent loop@21",
                "node branch@22: label part == \"skip\", parent loop@21",
                "node branch@23: label part == \"stop\", parent loop@21",
                "node branch@24: label part == \"next\", parent loop@21",
                "node end(main)",
                "node loop@18: label for each batch",
                "node loop@20: label for each id, parent loop@18",
                "node loop@21: label for each part, parent loop@20",
                "node start@17",
                "edge activity@19 -> loop@20",
                "edge branch@22 -> branch@23 [no]",
                // `continue@middle`: one loop out.
                "edge branch@22 -> loop@20 [yes]",
                "edge branch@23 -> branch@24 [no]",
                // `break@middle`, and the middle loop is the last thing in the round of the outermost: two loops out.
                "edge branch@23 -> loop@18 [yes]",
                "edge branch@24 -> activity@25 [no]",
                // `break` of the innermost loop, the last thing in the round of the middle one.
                "edge branch@24 -> loop@20 [yes]",
                "edge loop@18 -> end(main)",
                "edge start@17 -> loop@18",
            ),
            graph("ThreeDeep"),
        )
    }

    @Test
    fun `it is the same for every kind of loop, and for a return that ends the round of a function's lambda`() {
        assertEquals(
            listOf(
                "node activity@22: name Check, parent loop@21",
                "node activity@23: name Work, parent loop@21",
                "node activity@29: name Report, parent loop@27",
                "node activity@35: name CleanUp, parent loop@33",
                "node activity@41: name Undo, parent loop@39",
                "node branch@22: label tasks.check(\"again\"), parent loop@21",
                "node branch@28: label id.isEmpty(), parent loop@27",
                "node branch@34: label part.isEmpty(), parent loop@33",
                "node branch@40: label part.isEmpty(), parent loop@39",
                "node end(main)",
                "node loop@19: label while round < ids.size",
                "node loop@21: label do while round < 3, parent loop@19",
                "node loop@26: label repeat 3 times",
                "node loop@27: label for each id, parent loop@26",
                "node loop@32: label for each id",
                "node loop@33: label for each part, parent loop@32",
                "node loop@38: label for each id",
                "node loop@39: label for each part, parent loop@38",
                "node start@17",
                "edge activity@22 -> branch@22",
                "edge branch@22 -> activity@23 [no]",
                // `continue@outer` out of a `do`-`while` in a `while`.
                "edge branch@22 -> loop@19 [yes]",
                "edge branch@28 -> activity@29 [no]",
                // `return@repeat` out of a `forEach` in a `repeat`.
                "edge branch@28 -> loop@26 [yes]",
                "edge branch@34 -> activity@35 [no]",
                // `return@forEach` out of a `for` in a `forEach`.
                "edge branch@34 -> loop@32 [yes]",
                "edge branch@40 -> activity@41 [no]",
                // `return@each` out of a `forEach` in a `forEach` whose lambda has that label written in front of it.
                "edge branch@40 -> loop@38 [yes]",
                "edge loop@19 -> loop@26",
                "edge loop@26 -> loop@32",
                "edge loop@32 -> loop@38",
                "edge loop@38 -> end(main)",
                "edge start@17 -> loop@19",
            ),
            graph("LoopKinds"),
        )
    }

    @Test
    fun `the label written in front of a lambda is kept, for a return to name it`() {
        val run = backend.functions("ends.LoopKindsImpl").single { it.name == "run" }
        val body = assertNotNull(backend.body(run.id))

        assertEquals(listOf(null, null, null, "each", null), body.walk().filterIsInstance<Lambda>().map { it.label }.toList())
        assertEquals(listOf("repeat", "forEach", "each"), body.walk().filterIsInstance<Return>().map { it.label }.toList())
        assertTrue(CodePrinter.print(body).lines().any { it.trim().startsWith("each@ lambda (id) ") })
    }

    @Test
    fun `a jump out of a loop leads to the end of the lane or of the compensation the loop is the last thing of`() {
        assertEquals(
            listOf(
                "node activity@22: name Work, parent loop@20",
                "node activity@25: name Prepare, parent parallel@19",
                "node activity@28: name Check, parent parallel@28",
                "node activity@30: name Report, parent parallel@28",
                "node activity@36: name Undo, parent loop@34",
                "node branch@21: label id == \"stop\", parent loop@20",
                "node branch@29: label id.isEmpty(), parent parallel@28",
                "node branch@35: label id == \"kept\", parent loop@34",
                "node compensation@39",
                "node end(main)",
                "node loop@20: label for each id, parent parallel@19",
                "node loop@27: label for each id",
                "node loop@34: label for each id, parent compensation@39",
                "node parallel@19",
                "node parallel@28: parent loop@27",
                "node start@17",
                "edge branch@21 -> activity@22 [no]",
                // `break` in a lane that ends with the loop: the end of the lane.
                "edge branch@21 -> parallel@19 [yes]",
                "edge branch@29 -> activity@30 [no]",
                // `continue` in what the flow does itself beside a lane: out of the parallel region, to the next round.
                "edge branch@29 -> loop@27 [yes]",
                "edge branch@35 -> activity@36 [no]",
                // `break` in a compensation that ends with the loop: the end of the chain.
                "edge branch@35 -> compensation@39 [yes]",
                "edge compensation@39 -> end(main)",
                "edge loop@27 -> compensation@39",
                "edge parallel@19 -> loop@27",
                "edge start@17 -> parallel@19",
            ),
            graph("LaneEnds"),
        )
    }

    @Test
    fun `a jump passes through finally on its way out, and a swallowed failure ends the round as well`() {
        assertEquals(
            listOf(
                "node activity@22: name Work, parent loop@19",
                // `finally` once for the way on and once for `continue@outer`.
                "node activity@24#1: name CleanUp, parent loop@19",
                "node activity@24#2: name CleanUp, parent loop@19",
                "node activity@31: name Report, parent loop@30",
                "node branch@21: label part.isEmpty(), parent loop@19",
                "node end(main)",
                "node loop@18: label for each id",
                "node loop@19: label for each part, parent loop@18",
                "node loop@28: label for each id",
                "node loop@30: label for each part, parent loop@28",
                "node start@17",
                "edge activity@22 -> activity@24#1",
                // After the `finally` of `continue@outer` the flow is at the end of the outer round.
                "edge activity@24#2 -> loop@18",
                // The clause holds nothing, so the failure leads on to what follows the `try`: the end of the outer round.
                "edge activity@31 -(exception)-> loop@28 [ActivityFailure]",
                // A step that leaves its loop for a region further out says where it goes otherwise, too.
                "edge activity@31 -> loop@30",
                "edge branch@21 -> activity@22 [no]",
                "edge branch@21 -> activity@24#2 [yes]",
                "edge loop@18 -> loop@28",
                "edge loop@28 -> end(main)",
                "edge start@17 -> loop@18",
            ),
            graph("ThroughFinally"),
        )
    }

    @Test
    fun `a return in a followed function and in a lambda handed to one end the round of the loop that runs them`() {
        assertEquals(
            listOf(
                "node activity@19: name Prepare, parent loop@18",
                "node activity@25: name Report, parent loop@23",
                "node activity@33: name Work, parent loop@31",
                "node branch@24: label part.isEmpty(), parent loop@23",
                "node branch@32: label part == \"x\", parent loop@31",
                "node end(main)",
                "node loop@18: label for each id",
                "node loop@23: label for each part, parent loop@38",
                "node loop@31: label for each part, parent loop@18",
                "node loop@38: label for each id",
                "node start@17",
                "edge activity@19 -> loop@31",
                "edge branch@24 -> activity@25 [no]",
                // `return@each` ends the lambda, which the loop of the function it was handed to runs last.
                "edge branch@24 -> loop@38 [yes]",
                "edge branch@32 -> activity@33 [no]",
                // `return` ends the function, whose call is the last thing in the round of the caller's loop.
                "edge branch@32 -> loop@18 [yes]",
                "edge loop@18 -> loop@38",
                "edge loop@38 -> end(main)",
                "edge start@17 -> loop@18",
            ),
            graph("InHelper"),
        )
    }
}
