package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.modelextractor.testing.SampleRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lines of progress and the time limit of the SVG diagrams, in the three commands that draw.
 *
 * A limit that some diagram does not keep is set as a billionth of a second: no machine arranges
 * a diagram in that, and every arrangement asks the clock before it lays anything out, so the
 * outcome is the same wherever the tests run and nothing has to be waited for.
 */
class TimeLimitCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val out: Path get() = dir.resolve("out")
    private val histories: Path = Path.of(System.getProperty("durable-atlas.fixtureDir")).resolve("histories")
    private val noTime = arrayOf("--svg-timeout", "0.000000001")

    private fun render(vararg arguments: String): CliktCommandTestResult =
        durableAtlas().test(listOf("render", "--ir", SampleIr.degraded.toString(), "--out", out.toString()) + arguments)

    private fun overlay(vararg arguments: String, history: Path = histories): CliktCommandTestResult =
        durableAtlas().test(listOf("overlay", "--ir", SampleIr.degraded.toString(), "--history", history.toString(), "--out", out.toString()) + arguments)

    private fun CliktCommandTestResult.summary(): JsonObject = Json.parseToJsonElement(stdout).jsonObject

    private fun JsonObject.list(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    private fun String.onOneLine(): String = replace(Regex("\\s+"), " ")

    private fun files(directory: Path = out): List<String> =
        Files.walk(directory).use { paths -> paths.filter { it.isRegularFile() }.map { it.relativeTo(directory).toString() }.sorted().toList() }

    /**
     * The lines of progress among what a command wrote to standard error. A drawing that was told to stop does so at
     * once in these tests, but on a machine that is busy enough it may be slower than the caller's patience; that it
     * was left behind is then said in the line, and is not what these tests are about.
     */
    private fun CliktCommandTestResult.progress(command: String): List<String> =
        stderr.lines().filter { Regex("$command: \\d+/\\d+ ").containsMatchIn(it) }
            .map { it.removePrefix("$command: ").replace("; its layout did not stop and runs on in the background", "") }

    @Test
    fun `render says of every workflow that it is drawn, in order, on standard error alone`() {
        val result = render()

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val progress = result.progress("render")
        assertEquals(30, progress.size, result.stderr)
        progress.forEachIndexed { index, line ->
            assertTrue(Regex("${index + 1}/30 \\S+: \\d+ nodes?, \\d+\\.\\d s").matches(line), line)
        }
        assertTrue(progress.any { it.startsWith("28/30 TypedActivityWorkflow: 6 nodes, ") }, progress.toString())
        assertEquals(progress.map { it.substringAfter(' ').substringBefore(':') }.sortedBy { it.lowercase() }, progress.map { it.substringAfter(' ').substringBefore(':') })
        // The lines come before the one that says where everything went, which is the last: nothing was missing.
        assertTrue(result.stderr.trimEnd().lines().last().startsWith("render: wrote 30 diagram(s) as svg, mermaid to "))
        assertFalse("warning" in result.stderr)
        assertFalse(Regex("\\d+/30 ").containsMatchIn(result.stdout))
        assertEquals(listOf(""), result.stdout.lines().drop(2))
    }

    @Test
    fun `the time limit is an option of every command that draws, in seconds, and zero sets none`() {
        for (command in listOf("render", "run", "overlay")) {
            val help = durableAtlas().test(listOf(command, "--help")).stdout.onOneLine()
            assertContains(help, "--svg-timeout=<seconds>")
            assertContains(
                help,
                "Give up arranging the SVG diagram of a workflow after this many seconds (by default 30) and draw it more simply: " +
                    "with each row ended where it is full, then with regions as single nodes too, each try with the same time.",
            )
            assertContains(help, "A workflow that neither of them draws gets no SVG diagram, and the exit status is 2. 0 sets no limit.")
            // How the rows of every diagram are found is no simpler way of drawing one, and nothing to choose.
            assertFalse("quicker search" in help, help)
        }
        for (value in listOf("-1", "soon")) {
            val invalid = render("--svg-timeout", value)
            assertEquals(ExitCode.FAILURE, invalid.statusCode, value)
            assertContains(invalid.stderr, "--svg-timeout")
            assertFalse(Files.exists(out))
        }

        // Without a limit, and with one that is ample, every file is what it is by default.
        assertEquals(ExitCode.SUCCESS, render().statusCode)
        val byDefault = files().associateWith { Files.readString(out.resolve(it)) }
        for (seconds in listOf("0", "0.0", "3600", "2.5")) {
            Files.walk(out).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            assertEquals(ExitCode.SUCCESS, render("--svg-timeout", seconds).statusCode, seconds)
            assertEquals(byDefault, files().associateWith { Files.readString(out.resolve(it)) }, seconds)
        }
    }

    @Test
    fun `render with no time for SVG writes everything else, says what is missing and exits with status 2`() {
        val result = render(*noTime)

        assertEquals(ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED, result.statusCode, result.stderr)
        assertEquals(31, files().size)
        assertTrue(files().all { it.endsWith(".md") }, files().toString())

        val progress = result.progress("render")
        // A workflow with regions is tried in two simpler ways, one without in one.
        assertEquals(
            listOf(
                "svg not drawn within 0.000000001 s; drawing it with plain rows",
                "svg not drawn within 0.000000001 s; drawing it with regions as single nodes",
                "svg not drawn within 0.000000001 s; giving it up",
            ),
            progress.filter { " LoopWorkflow: svg " in it }.map { it.substringAfter(": ") },
        )
        assertEquals(
            listOf(
                "svg not drawn within 0.000000001 s; drawing it with plain rows",
                "svg not drawn within 0.000000001 s; giving it up",
            ),
            progress.filter { " TypedActivityWorkflow: svg " in it }.map { it.substringAfter(": ") },
        )
        assertEquals(30, progress.count { Regex("\\d+/30 \\S+: \\d+ nodes?, \\d+\\.\\d s, no svg").matches(it) }, result.stderr)
        // What is missing is said once more at the end, where it is still on the screen.
        val last = result.stderr.trimEnd().lines().last()
        assertTrue(last.startsWith("render: warning: 30 workflow(s) without svg diagram, which was not drawn within 0.000000001 s: "), last)

        val lines = result.stdout.lines()
        // No sentence says that more was drawn than was.
        assertContains(result.stderr, "render: wrote 30 diagram(s) as svg (0 of 30), mermaid to ")
        assertEquals("30 workflow(s) drawn as svg (0 of 30), mermaid", lines[0])
        assertTrue(lines[1].startsWith("30 workflow(s) without svg diagram, which was not drawn within 0.000000001 s: "), lines[1])
        assertContains(lines[1], ", TypedActivityWorkflow, ")
        assertEquals(out.toRealPath().resolve("index.md"), Path.of(lines[2]).toRealPath())

        val page = Files.readString(out.resolve("TypedActivityWorkflow.md"))
        assertContains(page, "> This workflow has no SVG diagram: drawing one took longer than the limit of 0.000000001 s, also when it was drawn more simply.")
        assertContains(page, "```mermaid\n")
        val index = Files.readString(out.resolve("index.md"))
        assertContains(index, "| [mermaid](TypedActivityWorkflow.md) · no svg |\n")
        assertContains(index, "Not drawn: 30 workflows have no SVG diagram, because drawing one took longer than the limit of 0.000000001 s")
    }

    @Test
    fun `the summary as JSON lists what was not drawn, for the workflows it concerns alone`() {
        val limited = render(*noTime, "--json")
        assertEquals(ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED, limited.statusCode, limited.stderr)
        for (diagram in limited.summary().list("diagrams")) {
            assertEquals(listOf("workflowType", "implementation", "ir", "nodes", "unknown", "collapsedRegions", "files", "notDrawn"), diagram.keys.toList())
            assertEquals("""[{"format":"svg","limitSeconds":1.0E-9}]""", diagram.getValue("notDrawn").toString())
            assertEquals(listOf("mermaid"), diagram.list("files").map { it.getValue("format").jsonPrimitive.content })
        }

        // Mermaid alone has no limit to run into.
        val mermaid = render(*noTime, "--format", "mermaid", "--json")
        assertEquals(ExitCode.SUCCESS, mermaid.statusCode, mermaid.stderr)
        assertTrue(mermaid.summary().list("diagrams").none { "notDrawn" in it || "simplified" in it })
        assertFalse("not drawn" in mermaid.stderr)
    }

    @Test
    fun `run with no time for SVG writes the IR and the Mermaid pages, and its status is 2 unless the model was a fallback`() {
        val repository = SampleRepository.create(dir.resolve("repo"))
        val result = durableAtlas().test(
            listOf("run", "--repo", repository.project.toString(), "--commit", repository.head, "--out", out.toString(), "--mode", "degraded", "--json") + noTime,
        )

        assertEquals(ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED, result.statusCode, result.stderr)
        val summary = result.summary()
        assertEquals(2, summary.getValue("exitCode").jsonPrimitive.int)
        val workflows = summary.list("workflows")
        assertEquals(30, workflows.size)
        assertTrue(workflows.all { it.getValue("notDrawn").toString() == """[{"format":"svg","limitSeconds":1.0E-9}]""" })
        val diagrams = out.resolve("diagrams").resolve(repository.head)
        assertEquals(31, files(diagrams).size)
        assertTrue(files(diagrams).all { it.endsWith(".md") })
        assertEquals(30, files(out.resolve("ir").resolve(repository.head)).count { it.endsWith(".ir.json") })
        assertEquals(30, result.progress("run").count { it.endsWith(", no svg") }, result.stderr)

        // For people, the table lists the files that are there, and a line says what is not.
        val table = durableAtlas().test(
            listOf("run", "--repo", repository.project.toString(), "--commit", repository.head, "--out", out.toString(), "--mode", "degraded") + noTime,
        )
        assertEquals(ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED, table.statusCode, table.stderr)
        val lines = table.stdout.lines()
        assertTrue(lines.subList(1, 31).all { it.trimEnd().endsWith(".md") && ".svg" !in it }, table.stdout)
        assertEquals("30 workflow(s) drawn from the degraded model of ${repository.head}", lines[31])
        assertTrue(lines[32].startsWith("30 workflow(s) without svg diagram, which was not drawn within 0.000000001 s: "), lines[32])
        assertEquals(diagrams.toRealPath().resolve("index.md"), Path.of(lines[33]).toRealPath())

        // A run that draws everything leaves the SVG files that the limited runs left out, and nothing says that one is missing.
        val full = durableAtlas().test(listOf("run", "--repo", repository.project.toString(), "--commit", repository.head, "--out", out.toString(), "--mode", "degraded"))
        assertEquals(ExitCode.SUCCESS, full.statusCode, full.stderr)
        assertEquals(61, files(diagrams).size)
        assertFalse("no SVG diagram" in Files.readString(diagrams.resolve("TypedActivityWorkflow.md")))
        assertEquals(30, full.progress("run").count { Regex("\\d+/30 \\S+: \\d+ nodes?, \\d+\\.\\d s").matches(it) }, full.stderr)
    }

    @Test
    fun `overlay reports every workflow type with its executions, and with no time for SVG it draws the rest and exits with status 2`() {
        val some = Files.createDirectories(dir.resolve("histories"))
        for (name in listOf("OrderWorkflow.json", "OrderWorkflow.payment-timeout.json", "TimerWorkflow.json")) Files.copy(histories.resolve(name), some.resolve(name))

        val drawn = overlay(history = some)
        assertEquals(ExitCode.SUCCESS, drawn.statusCode, drawn.stderr)
        val progress = drawn.progress("overlay")
        assertEquals(2, progress.size, drawn.stderr)
        assertTrue(Regex("1/2 OrderWorkflow: 33 nodes, 2 executions, \\d+\\.\\d s").matches(progress[0]), progress[0])
        assertTrue(Regex("2/2 TimerWorkflow: 5 nodes, 1 execution, \\d+\\.\\d s").matches(progress[1]), progress[1])
        assertTrue(files().any { it == "OrderWorkflow.coverage.svg" })

        Files.walk(out).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        val limited = overlay(*noTime, history = some)
        assertEquals(ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED, limited.statusCode, limited.stderr)
        assertEquals(
            listOf(
                "OrderWorkflow.coverage.json", "OrderWorkflow.coverage.md", "TimerWorkflow.coverage.json", "TimerWorkflow.coverage.md",
                "executions/OrderWorkflow.md", "executions/OrderWorkflow.payment-timeout.md", "executions/TimerWorkflow.md", "index.md",
            ),
            files(),
        )
        assertTrue("2/2 TimerWorkflow: svg not drawn within 0.000000001 s; giving it up" in limited.progress("overlay"), limited.stderr)
        assertContains(limited.stderr, "overlay: warning: 2 workflow(s) without svg diagram, which was not drawn within 0.000000001 s: OrderWorkflow, TimerWorkflow\n")
        assertTrue(limited.progress("overlay").any { Regex("2/2 TimerWorkflow: 5 nodes, 1 execution, \\d+\\.\\d s, no svg").matches(it) }, limited.stderr)
        val lines = limited.stdout.lines()
        assertEquals("3 execution(s) of 2 workflow type(s) drawn as svg (0 of 2), mermaid", lines[3])
        assertEquals("2 workflow(s) without svg diagram, which was not drawn within 0.000000001 s: OrderWorkflow, TimerWorkflow", lines[4])
        assertEquals(out.toRealPath().resolve("index.md"), Path.of(lines[5]).toRealPath())
        assertContains(Files.readString(out.resolve("executions/TimerWorkflow.md")), "> This workflow has no SVG diagram: drawing one took longer than the limit")

        val json = overlay(*noTime, "--json", history = some)
        assertEquals(ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED, json.statusCode, json.stderr)
        assertEquals(2, json.summary().getValue("exitCode").jsonPrimitive.int)
        for (workflow in json.summary().list("workflows")) {
            assertEquals("""[{"format":"svg","limitSeconds":1.0E-9}]""", workflow.getValue("notDrawn").toString())
            assertFalse("diagram" in workflow)
            assertEquals(listOf("mermaid"), workflow.list("files").map { it.getValue("format").jsonPrimitive.content })
        }
    }

    @Test
    fun `overlay on a diagram that an earlier version marked as drawn with a quicker search reports nothing simpler`() {
        val some = Files.createDirectories(dir.resolve("histories"))
        Files.copy(histories.resolve("TimerWorkflow.json"), some.resolve("TimerWorkflow.json"))
        assertEquals(ExitCode.SUCCESS, overlay(history = some).statusCode)
        val before = files().associateWith { Files.readString(out.resolve(it)) }

        // As that version left the plain diagram when its time ran out: the rows found the way they are found for
        // every diagram now, and the file saying so.
        val plain = before.getValue("TimerWorkflow.svg")
        val marked = plain.replace(" collapsedRegions=\"0\">", " collapsedRegions=\"0\" simplified=\"near\" timeLimit=\"PT30S\">")
        assertTrue(marked != plain)
        Files.writeString(out.resolve("TimerWorkflow.svg"), marked)

        val again = overlay(history = some)
        assertEquals(ExitCode.SUCCESS, again.statusCode, again.stderr)
        assertFalse("warning" in again.stderr || "simpler" in again.stderr || "simpler" in again.stdout, again.stderr)
        assertEquals(before + ("TimerWorkflow.svg" to marked), files().associateWith { Files.readString(out.resolve(it)) })

        val json = overlay("--json", history = some)
        assertEquals(ExitCode.SUCCESS, json.statusCode, json.stderr)
        assertTrue(json.summary().list("workflows").none { "simplified" in it || "notDrawn" in it })
    }
}
