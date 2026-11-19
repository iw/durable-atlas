package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.render.DiagramFormats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `render` command on the IR of the sample repository. Which formats exist is taken from the
 * registry, so the tests hold whatever is registered.
 */
class RenderCommandTest {
    @TempDir
    lateinit var dir: Path

    private val out: Path get() = dir.resolve("diagrams")
    private val formats = DiagramFormats.all

    private fun render(vararg arguments: String, ir: Path = SampleIr.degraded, out: Path = this.out): CliktCommandTestResult =
        durableAtlas().test(listOf("render", "--ir", ir.toString(), "--out", out.toString()) + arguments)

    private fun CliktCommandTestResult.summary(): JsonObject = Json.parseToJsonElement(stdout).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    /** Help and usage errors are wrapped to the width of the terminal. */
    private fun String.onOneLine(): String = replace(Regex("\\s+"), " ")

    private fun written(directory: Path = out): List<String> = directory.listDirectoryEntries().map { it.name }.sorted()

    @Test
    fun `every workflow is drawn in every format and the result is summarised as JSON on stdout, logs go to stderr`() {
        val result = render("--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val summary = result.summary()
        assertEquals(listOf("command", "directory", "index", "formats", "diagrams", "skipped"), summary.keys.toList())
        assertEquals("render", summary.text("command"))
        assertEquals(out.toRealPath(), Path.of(summary.text("directory")).toRealPath())
        assertEquals(out.toRealPath().resolve("index.md"), Path.of(summary.text("index")).toRealPath())
        assertEquals(formats.map { it.name }, summary.getValue("formats").jsonArray.map { it.jsonPrimitive.content })
        // The inventory and the list of standalone activities live next to the IR files and are not among them.
        assertEquals(
            listOf("inventory.json", "standalone-activities.json"),
            summary.getValue("skipped").jsonArray.map { it.jsonPrimitive.content },
        )

        val diagrams = summary.getValue("diagrams").jsonArray.map { it.jsonObject }
        assertEquals(30, diagrams.size)
        assertEquals(diagrams.map { it.text("workflowType") }.sorted(), diagrams.map { it.text("workflowType") })
        val files = mutableListOf("index.md")
        for (diagram in diagrams) {
            assertEquals(
                listOf("workflowType", "implementation", "ir", "nodes", "unknown", "collapsedRegions", "files"),
                diagram.keys.toList(),
            )
            val type = diagram.text("workflowType")
            assertEquals("$type.ir.json", diagram.text("ir"))
            val written = diagram.getValue("files").jsonArray.map { it.jsonObject }
            assertEquals(formats.map { it.name }, written.map { it.text("format") }, type)
            for ((format, file) in formats.zip(written.map { Path.of(it.text("file")) })) {
                assertTrue(file.isRegularFile(), file.toString())
                assertEquals(out.toRealPath().resolve("$type.${format.extension}"), file.toRealPath())
                files += file.name
            }
        }
        assertEquals(files.sorted(), written())
        val typed = diagrams.single { it.text("workflowType") == "TypedActivityWorkflow" }
        assertEquals(6, typed.getValue("nodes").jsonPrimitive.int)
        assertEquals(0, typed.getValue("unknown").jsonPrimitive.int)
        assertEquals(1, diagrams.single { it.text("workflowType") == "ComputedActivityNameWorkflow" }.getValue("unknown").jsonPrimitive.int)

        // The index lists every workflow.
        val index = Files.readString(out.resolve("index.md"))
        for (diagram in diagrams) assertContains(index, "`${diagram.text("workflowType")}`")

        assertTrue(result.stderr.lines().filter { it.isNotEmpty() }.all { it.startsWith("render: ") }, result.stderr)
        assertContains(result.stderr, "render: wrote 30 diagram(s) as ${formats.joinToString(", ") { it.name }} to ")
    }

    @Test
    fun `without --json the summary is a sentence and the index page`() {
        val result = render()

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val lines = result.stdout.lines()
        assertEquals("30 workflow(s) drawn as ${formats.joinToString(", ") { it.name }}", lines[0])
        assertEquals(out.toRealPath().resolve("index.md"), Path.of(lines[1]).toRealPath())
        assertEquals(listOf(""), lines.drop(2))
    }

    @Test
    fun `each registered format can be asked for by name, and all of them at once`() {
        for (format in formats) {
            val directory = dir.resolve("only-${format.name}")

            val result = render("--format", format.name, "--json", out = directory)

            assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
            assertEquals(listOf(format.name), result.summary().getValue("formats").jsonArray.map { it.jsonPrimitive.content })
            val files = directory.listDirectoryEntries().filter { it.name != "index.md" }
            assertEquals(30, files.size, format.name)
            assertEquals(setOf(format.extension), files.map { it.extension }.toSet())
        }

        val all = render("--format", "all", "--json")
        assertEquals(ExitCode.SUCCESS, all.statusCode, all.stderr)
        assertEquals(formats.map { it.name }, all.summary().getValue("formats").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(30 * formats.size + 1, written().size)
    }

    @Test
    fun `a format that is not registered is a usage error that names the ones that are`() {
        val result = render("--format", "no-such-format", "--json")

        assertEquals(ExitCode.FAILURE, result.statusCode)
        assertEquals("", result.stdout)
        val error = result.stderr.onOneLine()
        assertContains(error, "invalid value for --format: 'no-such-format' is not a format this tool can write")
        assertContains(error, "(choose from ${(formats.map { it.name } + "all").joinToString(", ")})")
        assertFalse(out.exists())

        // The help names the same choices.
        val help = durableAtlas().test("render --help").stdout.onOneLine()
        assertContains(help, "--format=${(formats.map { it.name } + "all").joinToString("|")}")
        assertContains(help, "The default, 'all', writes every format.")
    }

    @Test
    fun `two runs write the same bytes`() {
        fun contents(): Map<String, String> = out.listDirectoryEntries().sorted().associate { it.name to Files.readString(it) }

        render()
        val first = contents()
        render()

        assertEquals(30 * formats.size + 1, first.size)
        assertEquals(first, contents())
    }

    @Test
    fun `the settings of the SVG diagrams are passed on`() {
        val plain = render("--format", "svg")
        assertEquals(ExitCode.SUCCESS, plain.statusCode, plain.stderr)
        val drawn = Files.readString(out.resolve("TypedActivityWorkflow.svg"))
        assertContains(drawn, "class=\"paper\"")
        assertContains(drawn, "class=\"legend\"")
        assertFalse("xlink:href=\"https://" in drawn)
        assertFalse(written().any { it.endsWith(".html") })

        val other = dir.resolve("other")
        val result = render(
            "--format", "svg", "--html", "--wrap-width", "0", "--legend", "none", "--background", "transparent",
            "--source-base-url", "https://example.org/repo/blob/{commit}",
            out = other,
        )
        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val svg = Files.readString(other.resolve("TypedActivityWorkflow.svg"))
        assertFalse("class=\"paper\"" in svg)
        assertFalse("class=\"legend\"" in svg)
        assertContains(svg, "xlink:href=\"https://example.org/repo/blob/")
        assertTrue(other.resolve("TypedActivityWorkflow.html").isRegularFile())
        assertFalse(written(other).any { it.endsWith(".md") && it != "index.md" })
    }

    @Test
    fun `SVG settings with a value outside their range are usage errors`() {
        for ((option, value) in listOf("--wrap-width" to "-1", "--legend" to "some", "--background" to "white")) {
            val invalid = render(option, value)
            assertEquals(ExitCode.FAILURE, invalid.statusCode, "$option $value")
            assertContains(invalid.stderr, option)
        }
    }

    @Test
    fun `the node limit is passed on, and has to be a positive number`() {
        // The workflow of loops has 12 nodes, five of them loops, which are folded away above the limit.
        assertEquals(ExitCode.SUCCESS, render("--format", "mermaid", "--max-nodes", "10").statusCode)
        val limited = Files.readString(out.resolve("LoopWorkflow.md"))
        assertContains(limited, "This workflow has 12 nodes, more than the limit of 10")
        assertFalse("more than the limit" in Files.readString(out.resolve("TypedActivityWorkflow.md")))

        assertEquals(ExitCode.SUCCESS, render("--format", "mermaid").statusCode)
        assertFalse("more than the limit" in Files.readString(out.resolve("LoopWorkflow.md")))

        val invalid = render("--max-nodes", "0")
        assertEquals(ExitCode.FAILURE, invalid.statusCode)
        assertContains(invalid.stderr, "--max-nodes")
    }

    @Test
    fun `a document that cannot be read is a failure, and nothing is written`() {
        val ir = dir.resolve("ir")
        Files.createDirectories(ir)
        Files.copy(SampleIr.degraded.resolve("TimerWorkflow.ir.json"), ir.resolve("TimerWorkflow.ir.json"))
        Files.writeString(ir.resolve("Broken.ir.json"), "{ \"schemaVersion\": 1 }")

        val result = render("--json", ir = ir)

        assertEquals(ExitCode.FAILURE, result.statusCode)
        assertEquals("", result.stdout)
        assertContains(result.stderr, "render: failed: Broken.ir.json: ")
        assertFalse(out.exists())
    }

    @Test
    fun `a directory without IR gives an empty index and a warning`() {
        val ir = Files.createDirectories(dir.resolve("empty"))

        val result = render("--json", ir = ir)

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertEquals(0, result.summary().getValue("diagrams").jsonArray.size)
        assertEquals(listOf("index.md"), written())
        assertContains(result.stderr, "render: warning: there is no workflow IR in ")

        val missing = render(ir = dir.resolve("nowhere"))
        assertEquals(ExitCode.FAILURE, missing.statusCode)
        assertContains(missing.stderr, "--ir")
    }
}
