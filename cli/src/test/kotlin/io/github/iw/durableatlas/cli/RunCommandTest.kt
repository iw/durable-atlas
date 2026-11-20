package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.modelextractor.testing.SampleRepository
import io.github.iw.durableatlas.modelextractor.testing.TreeSnapshot
import io.github.iw.durableatlas.render.DiagramFormats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `run` command in the cases that need git but not the sample repository's Gradle build. */
class RunCommandTest {
    @TempDir
    lateinit var dir: Path

    private val out: Path get() = dir.resolve("out")
    private val repository: SampleRepository by lazy { SampleRepository.create(dir.resolve("repo")) }
    private val formats = DiagramFormats.all

    private fun run(vararg arguments: String, commit: String = repository.head): CliktCommandTestResult {
        val repo = repository.project.toString()
        return durableAtlas().test(listOf("run", "--repo", repo, "--commit", commit, "--out", out.toString()) + arguments)
    }

    private fun CliktCommandTestResult.summary(): JsonObject = Json.parseToJsonElement(stdout).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.workflows(): List<JsonObject> = getValue("workflows").jsonArray.map { it.jsonObject }

    private fun names(directory: Path): List<String> = directory.listDirectoryEntries().map { it.name }.sorted()

    /** The directory of [commit] among the outputs of one [kind]: `model`, `ir` or `diagrams`. */
    private fun of(kind: String, commit: String = repository.head): Path = out.resolve(kind).resolve(commit)

    @Test
    fun `the stages run in turn, everything goes below --out and the result is summarised as JSON on stdout`() {
        val before = TreeSnapshot.metadata(repository.root)

        val result = run("--mode", "degraded", "--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val summary = result.summary()
        assertEquals(
            listOf(
                "command", "commit", "mode", "detail", "cached", "fallback", "model", "ir", "diagrams", "index", "formats",
                "workflows", "exitCode",
            ),
            summary.keys.toList(),
        )
        assertEquals("run", summary.text("command"))
        assertEquals(repository.head, summary.text("commit"))
        assertEquals("degraded", summary.text("mode"))
        assertFalse(summary.flag("cached"))
        assertFalse(summary.flag("fallback"))
        assertEquals(0, summary.number("exitCode"))
        assertEquals(formats.map { it.name }, summary.getValue("formats").jsonArray.map { it.jsonPrimitive.content })

        // The layout: the model, the IR and the diagrams, each in a directory named after the commit.
        assertEquals(listOf("diagrams", "ir", "model"), names(out))
        for (kind in names(out)) assertEquals(listOf(repository.head), names(out.resolve(kind)), kind)
        val model = of("model").toRealPath()
        val ir = of("ir").toRealPath()
        val diagrams = of("diagrams").toRealPath()
        assertEquals(model, Path.of(summary.text("model")).toRealPath())
        assertTrue(model.resolve("index.json").isRegularFile())
        assertEquals(ir, Path.of(summary.text("ir")).toRealPath())
        assertTrue(ir.resolve("inventory.json").isRegularFile())
        assertEquals(diagrams, Path.of(summary.text("diagrams")).toRealPath())
        assertEquals(diagrams.resolve("index.md"), Path.of(summary.text("index")).toRealPath())

        // One row for each workflow type, with what its IR says and where its files are.
        val workflows = summary.workflows()
        assertEquals(30, workflows.size)
        assertEquals(30, workflows.map { it.text("workflowType") }.toSet().size)
        val diagramFiles = mutableListOf("index.md")
        for (workflow in workflows) {
            assertEquals(
                listOf("workflowType", "implementation", "nodes", "unknown", "unknownPct", "overThreshold", "ir", "files"),
                workflow.keys.toList(),
            )
            val type = workflow.text("workflowType")
            val irFile = Path.of(workflow.text("ir"))
            assertEquals(ir.resolve("$type.ir.json"), irFile.toRealPath())
            val document = IrJson.decode(Files.readString(irFile))
            assertEquals(type, document.workflowType)
            assertEquals(workflow.text("implementation"), document.implementation)
            assertEquals(repository.head, document.commit)
            assertEquals(document.stats.nodes, workflow.number("nodes"), type)
            assertEquals(document.stats.unknown, workflow.number("unknown"), type)
            assertFalse(workflow.flag("overThreshold"))
            val files = workflow.getValue("files").jsonArray.map { it.jsonObject }
            assertEquals(formats.map { it.name }, files.map { it.text("format") })
            for ((format, file) in formats.zip(files.map { Path.of(it.text("file")) })) {
                assertEquals(diagrams.resolve("$type.${format.extension}"), file.toRealPath())
                diagramFiles += file.name
            }
        }
        assertEquals(diagramFiles.sorted(), names(diagrams))
        assertEquals(
            workflows.map { "${it.text("workflowType")}.ir.json" } + "inventory.json" + "standalone-activities.json",
            names(ir),
        )
        val computed = workflows.single { it.text("workflowType") == "ComputedActivityNameWorkflow" }
        assertEquals(33.3, computed.getValue("unknownPct").jsonPrimitive.double)
        assertEquals(0.0, workflows.single { it.text("workflowType") == "TimerWorkflow" }.getValue("unknownPct").jsonPrimitive.double)

        // Every stage logs under the name of the command, and nothing but the summary is on stdout.
        assertTrue(result.stderr.lines().filter { it.isNotEmpty() }.all { it.startsWith("run: ") }, result.stderr)
        assertContains(result.stderr, "run: wrote a degraded model")
        assertContains(result.stderr, "run: wrote 30 IR file(s)")
        assertContains(result.stderr, "run: wrote 30 diagram(s)")
        assertFalse("completed in degraded mode" in result.stderr)

        assertEquals(before, TreeSnapshot.metadata(repository.root))
        assertEquals("", repository.status())
    }

    @Test
    fun `without --json the summary is a table of the workflows, a sentence and the index page`() {
        val result = run("--mode", "degraded")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val lines = result.stdout.lines()
        assertEquals(listOf("Workflow type", "Nodes", "Unknown", "File"), lines[0].split(Regex(" {2,}")))
        val rows = lines.subList(1, 31).map { it.trim().split(Regex(" {2,}")) }
        assertEquals(rows.map { it[0] }.sorted(), rows.map { it[0] })
        val diagrams = of("diagrams").toRealPath()
        for (row in rows) {
            val files = row[3].split(", ").map { Path.of(it) }
            assertEquals(formats.map { diagrams.resolve("${row[0]}.${it.extension}") }, files.map { it.toRealPath() })
        }
        assertEquals(listOf("TypedActivityWorkflow", "6", "0"), rows.single { it[0] == "TypedActivityWorkflow" }.take(3))
        assertEquals(listOf("ComputedActivityNameWorkflow", "3", "1"), rows.single { it[0] == "ComputedActivityNameWorkflow" }.take(3))
        // The cells are in columns: the last one starts where its heading does, in every row.
        assertEquals(
            setOf(lines[0].indexOf("File")),
            lines.subList(1, 31).zip(rows) { line, row -> line.indexOf(row[3]) }.toSet(),
        )
        assertEquals("30 workflow(s) drawn from the degraded model of ${repository.head}", lines[31])
        assertEquals(diagrams.resolve("index.md"), Path.of(lines[32]).toRealPath())
        assertEquals(listOf(""), lines.drop(33))
    }

    @Test
    fun `a workflow with more unknown nodes than allowed makes the exit status 2, after everything is written`() {
        val strict = run("--mode", "degraded", "--max-unresolved-pct", "30", "--json")

        assertEquals(ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED, strict.statusCode, strict.stderr)
        val summary = strict.summary()
        assertEquals(2, summary.number("exitCode"))
        assertEquals(30.0, summary.getValue("maxUnresolvedPct").jsonPrimitive.double)
        val over = summary.workflows().filter { it.flag("overThreshold") }
        assertEquals(
            summary.workflows().filter { it.getValue("unknownPct").jsonPrimitive.double > 30 }.map { it.text("workflowType") },
            over.map { it.text("workflowType") },
        )
        assertTrue("ComputedActivityNameWorkflow" in over.map { it.text("workflowType") })
        assertFalse("TimerWorkflow" in over.map { it.text("workflowType") })
        assertContains(strict.stderr, "run: ComputedActivityNameWorkflow: 1 of 3 nodes are unknown (33.3 %), more than the 30.0 % allowed")
        assertEquals(over.size, strict.stderr.lines().count { "nodes are unknown" in it })
        // The diagrams are there all the same.
        assertTrue(Path.of(summary.text("index")).isRegularFile())

        val lenient = run("--mode", "degraded", "--max-unresolved-pct", "100", "--json")
        assertEquals(ExitCode.SUCCESS, lenient.statusCode, lenient.stderr)
        assertTrue(lenient.summary().flag("cached"))
        assertEquals(emptyList(), lenient.summary().workflows().filter { it.flag("overThreshold") })
        assertFalse("nodes are unknown" in lenient.stderr)
    }

    @Test
    fun `falling back to a degraded model makes the exit status 3, and asking for the Gradle model makes it 1`() {
        val commit = repository.commit("Remove the wrapper") { project -> Files.delete(project.resolve("gradlew")) }

        val explicit = run("--mode", "gradle", "--json", commit = commit)
        assertEquals(ExitCode.FAILURE, explicit.statusCode)
        assertEquals("", explicit.stdout)
        assertContains(explicit.stderr, "run: failed: ")
        assertFalse(out.resolve("ir").exists())
        assertFalse(out.resolve("diagrams").exists())

        val fallback = run("--json", commit = commit)
        assertEquals(ExitCode.DEGRADED, fallback.statusCode, fallback.stderr)
        val summary = fallback.summary()
        assertEquals("degraded", summary.text("mode"))
        assertTrue(summary.flag("fallback"))
        assertEquals(3, summary.number("exitCode"))
        assertEquals(30, summary.workflows().size)
        assertTrue(Path.of(summary.text("index")).isRegularFile())
        assertContains(fallback.stderr, "Gradle extraction failed")
        assertContains(fallback.stderr, "run: completed in degraded mode")

        // The degraded model is reused and the run is still flagged, whatever the count of unknown nodes says.
        val again = run("--max-unresolved-pct", "0", "--json", commit = commit)
        assertEquals(ExitCode.DEGRADED, again.statusCode, again.stderr)
        assertTrue(again.summary().flag("cached"))
        assertFalse(again.summary().flag("fallback"))
        assertTrue(again.summary().workflows().any { it.flag("overThreshold") })
        assertContains(again.stderr, "nodes are unknown")

        // Asked for, a degraded model is nothing to flag.
        val degraded = run("--mode", "degraded", commit = commit)
        assertEquals(ExitCode.SUCCESS, degraded.statusCode, degraded.stderr)
    }

    @Test
    fun `a stage that fails makes the exit status 1 and leaves stdout empty`() {
        val unknownCommit = run("--mode", "degraded", "--json", commit = "no-such-commit")
        assertEquals(ExitCode.FAILURE, unknownCommit.statusCode)
        assertEquals("", unknownCommit.stdout)
        assertContains(unknownCommit.stderr, "run: failed: ")
        assertContains(unknownCommit.stderr, "no-such-commit")
        assertFalse(out.resolve("ir").exists())

        // The analysis cannot start without a JDK to resolve against; the model is kept for the next run.
        val notAJdk = run("--mode", "degraded", "--jdk-home", dir.toString(), "--json")
        assertEquals(ExitCode.FAILURE, notAJdk.statusCode)
        assertEquals("", notAJdk.stdout)
        assertContains(notAJdk.stderr, "is not the home directory of a JDK")
        assertTrue(out.resolve("model").resolve(repository.head).resolve("index.json").isRegularFile())
        assertFalse(out.resolve("diagrams").exists())

        // Bad arguments never get as far as a stage.
        val unknownFormat = run("--mode", "degraded", "--format", "no-such-format")
        assertEquals(ExitCode.FAILURE, unknownFormat.statusCode)
        assertContains(unknownFormat.stderr, "--format")
        val missingRepo = durableAtlas().test("run --commit HEAD --out $out")
        assertEquals(ExitCode.FAILURE, missingRepo.statusCode)
        assertContains(missingRepo.stderr, "--repo")
    }

    @Test
    fun `runs on two commits into one directory keep what the other wrote`() {
        val first = repository.head
        val second = SampleCommits.withoutTimerWorkflow(repository)
        val removed = SampleCommits.REMOVED_WORKFLOW_TYPE

        val earlier = run("--mode", "degraded", "--json", commit = first)
        assertEquals(ExitCode.SUCCESS, earlier.statusCode, earlier.stderr)
        val written = TreeSnapshot.contents(out)
        val later = run("--mode", "degraded", "--json", commit = second)
        assertEquals(ExitCode.SUCCESS, later.statusCode, later.stderr)

        // Each commit has its own directory of every kind, and the summary of a run names those of its commit.
        for (kind in listOf("model", "ir", "diagrams")) {
            assertEquals(listOf(first, second).sorted(), names(out.resolve(kind)), kind)
            assertEquals(of(kind, first).toRealPath(), Path.of(earlier.summary().text(kind)).toRealPath())
            assertEquals(of(kind, second).toRealPath(), Path.of(later.summary().text(kind)).toRealPath())
        }
        assertEquals(of("diagrams", second).resolve("index.md").toRealPath(), Path.of(later.summary().text("index")).toRealPath())
        assertEquals(30, earlier.summary().workflows().size)
        assertEquals(29, later.summary().workflows().size)
        assertTrue(later.summary().workflows().all { Path.of(it.text("ir")).toRealPath().startsWith(of("ir", second).toRealPath()) })

        // The later commit has lost a workflow type; the earlier one still has its IR and its diagrams.
        assertFalse(removed in later.summary().workflows().map { it.text("workflowType") })
        assertFalse(of("ir", second).resolve("$removed.ir.json").exists())
        assertTrue(of("ir", first).resolve("$removed.ir.json").isRegularFile())
        for (format in formats) {
            assertFalse(of("diagrams", second).resolve("$removed.${format.extension}").exists())
            assertTrue(of("diagrams", first).resolve("$removed.${format.extension}").isRegularFile())
        }
        assertEquals(second, IrJson.decode(Files.readString(of("ir", second).resolve("OrderWorkflow.ir.json"))).commit)
        assertEquals(first, IrJson.decode(Files.readString(of("ir", first).resolve("OrderWorkflow.ir.json"))).commit)

        // Nothing the earlier run wrote was touched by the later one.
        assertEquals(written, TreeSnapshot.contents(out).filterKeys { it in written })
        assertFalse("run: removed " in later.stderr, later.stderr)
    }

    @Test
    fun `a run on a commit that was run before leaves nothing of the earlier run behind`() {
        assertEquals(ExitCode.SUCCESS, run("--mode", "degraded").statusCode)
        val written = TreeSnapshot.contents(out)

        // What a run that found one more workflow type would have left, and a directory that is someone else's.
        Files.copy(of("ir").resolve("TimerWorkflow.ir.json"), of("ir").resolve("GoneWorkflow.ir.json"))
        for (format in formats) {
            Files.copy(of("diagrams").resolve("TimerWorkflow.${format.extension}"), of("diagrams").resolve("GoneWorkflow.${format.extension}"))
        }
        Files.writeString(Files.createDirectories(of("diagrams").resolve("extra")).resolve("kept.txt"), "kept\n")

        // The commit is the same one, however it is named.
        val again = run("--mode", "degraded", "--json", commit = repository.head.take(10))

        assertEquals(ExitCode.SUCCESS, again.statusCode, again.stderr)
        assertEquals(repository.head, again.summary().text("commit"))
        assertEquals(of("ir").toRealPath(), Path.of(again.summary().text("ir")).toRealPath())
        assertEquals(of("diagrams").toRealPath(), Path.of(again.summary().text("diagrams")).toRealPath())
        for (kind in listOf("model", "ir", "diagrams")) assertEquals(listOf(repository.head), names(out.resolve(kind)), kind)
        val now = TreeSnapshot.contents(out)
        assertEquals(setOf("diagrams/${repository.head}/extra", "diagrams/${repository.head}/extra/kept.txt"), now.keys - written.keys)
        assertEquals(written, now.filterKeys { it in written })
        assertContains(again.stderr, "run: removed GoneWorkflow.ir.json, which is not part of this analysis\n")
        for (format in formats) {
            assertContains(again.stderr, "run: removed GoneWorkflow.${format.extension}, which is not part of this run\n")
        }
        assertEquals(1 + formats.size, again.stderr.lines().count { it.startsWith("run: removed ") }, again.stderr)

        // Drawn in one format only, the commit has diagrams in that format only.
        val format = formats.last()
        val narrowed = run("--mode", "degraded", "--format", format.name, commit = "HEAD")
        assertEquals(ExitCode.SUCCESS, narrowed.statusCode, narrowed.stderr)
        val files = of("diagrams").listDirectoryEntries().filter { it.isRegularFile() && it.name != "index.md" }
        assertEquals(30, files.size)
        assertTrue(files.all { it.name.endsWith(".${format.extension}") }, files.toString())
        assertTrue(of("diagrams").resolve("extra/kept.txt").isRegularFile())
    }

    @Test
    fun `the depth limit is passed on to the analysis`() {
        fun unknownInDeepChain(vararg arguments: String): Int {
            val result = run("--mode", "degraded", "--json", *arguments)
            assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
            return result.summary().workflows().single { it.text("workflowType") == "DeepHelperChainWorkflow" }.number("unknown")
        }

        // The activity of that workflow is ten calls away.
        assertEquals(1, unknownInDeepChain())
        assertEquals(0, unknownInDeepChain("--max-depth", "10"))
    }

    @Test
    fun `the formats and the node limit are passed on to the diagrams`() {
        val format = formats.last()

        val result = run("--mode", "degraded", "--format", format.name, "--max-nodes", "20", "--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertEquals(listOf(format.name), result.summary().getValue("formats").jsonArray.map { it.jsonPrimitive.content })
        val files = of("diagrams").listDirectoryEntries().filter { it.name != "index.md" }
        assertEquals(30, files.size)
        assertTrue(files.all { it.name.endsWith(".${format.extension}") })
    }
}
