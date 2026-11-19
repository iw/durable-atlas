package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.analyser.inventory.InventoryJson
import io.github.iw.durableatlas.analyser.inventory.InventorySchema
import io.github.iw.durableatlas.analyser.inventory.StandaloneActivitiesJson
import io.github.iw.durableatlas.analyser.testing.SampleModels
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.ir.validate
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
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `analyse` command on a degraded model of the sample repository, which needs git only. */
class AnalyseCommandTest {
    @TempDir
    lateinit var dir: Path

    private val model: Path get() = SampleModels.degraded.directory
    private val out: Path get() = dir.resolve("ir")

    private fun analyse(vararg arguments: String, model: Path = this.model): CliktCommandTestResult =
        durableAtlas().test(listOf("analyse", "--model", model.toString(), "--out", out.toString()) + arguments)

    private fun CliktCommandTestResult.summary(): JsonObject = Json.parseToJsonElement(stdout).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun irFiles(): List<String> = out.listDirectoryEntries("*.ir.json").map { it.name }.sorted()

    @Test
    fun `the inventory and the IR files are written and summarised as JSON on stdout, logs go to stderr`() {
        val result = analyse("--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val summary = result.summary()
        assertEquals(
            listOf("command", "commit", "mode", "inventory", "workflows", "activityInterfaces", "standaloneActivities", "warnings"),
            summary.keys.toList(),
        )
        assertEquals("analyse", summary.text("command"))
        assertEquals(SampleModels.degraded.index.commit, summary.text("commit"))
        assertEquals("degraded", summary.text("mode"))
        assertEquals(0, summary.getValue("warnings").jsonArray.size)

        val file = Path.of(summary.text("inventory"))
        assertEquals(out.toRealPath().resolve("inventory.json"), file.toRealPath())
        assertEquals(
            (irFiles() + "inventory.json" + "standalone-activities.json").sorted(),
            out.listDirectoryEntries().map { it.name }.sorted(),
        )
        val text = Files.readString(file)
        JsonSchemaChecker.forSchema(InventorySchema.text()).assertValid(text)
        val inventory = InventoryJson.decode(text)
        assertEquals(SampleModels.degraded.index.commit, inventory.commit)
        assertEquals(ModelMode.DEGRADED, inventory.modelMode)

        // The summary names what the inventory holds.
        val workflows = summary.getValue("workflows").jsonArray.map { it.jsonObject }
        assertEquals(inventory.workflows.map { it.workflowType }, workflows.map { it.text("workflowType") })
        assertEquals(30, workflows.size)
        val order = workflows.single { it.text("workflowType") == "OrderWorkflow" }
        assertEquals("com.example.fixture.workflows.order.OrderWorkflow", order.text("interface"))
        assertEquals(
            listOf("com.example.fixture.workflows.order.OrderWorkflowImpl"),
            order.getValue("implementations").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(6, order.getValue("entryPoints").jsonPrimitive.int)

        // One IR file for each workflow type, called after it, with the counts the summary gives.
        assertEquals(inventory.workflows.map { "${it.workflowType}.ir.json" }.sorted(), irFiles())
        val schema = JsonSchemaChecker.forWorkflowIr()
        for (workflow in workflows) {
            val ir = workflow.getValue("ir").jsonArray.single().jsonObject
            assertEquals(listOf("implementation", "file", "nodes", "unknown"), ir.keys.toList())
            val file = Path.of(ir.text("file"))
            assertEquals(out.toRealPath().resolve("${workflow.text("workflowType")}.ir.json"), file.toRealPath())
            val irText = Files.readString(file)
            schema.assertValid(irText)
            val document = IrJson.decode(irText)
            assertEquals(emptyList(), document.validate(), file.name)
            assertEquals(workflow.text("workflowType"), document.workflowType)
            assertEquals(workflow.text("interface"), document.interfaceName)
            assertEquals(ir.text("implementation"), document.implementation)
            assertEquals(inventory.commit, document.commit)
            assertEquals(ModelMode.DEGRADED, document.modelMode)
            assertEquals(document.stats.nodes, ir.getValue("nodes").jsonPrimitive.int)
            assertEquals(document.stats.unknown, ir.getValue("unknown").jsonPrimitive.int)
        }
        assertEquals(0, workflows.single { it.text("workflowType") == "TypedActivityWorkflow" }.getValue("ir").jsonArray.single().jsonObject.getValue("unknown").jsonPrimitive.int)
        assertEquals(
            inventory.activityInterfaces.map { it.interfaceName },
            summary.getValue("activityInterfaces").jsonArray.map { it.jsonPrimitive.content },
        )

        // The activity types no workflow calls: the sample project has none, and four workflows that may hide a call.
        val standalone = summary.getValue("standaloneActivities").jsonObject
        assertEquals(listOf("file", "activities", "unresolvedWorkflows"), standalone.keys.toList())
        val standaloneFile = Path.of(standalone.text("file"))
        assertEquals(out.toRealPath().resolve("standalone-activities.json"), standaloneFile.toRealPath())
        JsonSchemaChecker.forSchema(StandaloneActivitiesJson.schema()).assertValid(Files.readString(standaloneFile))
        val listed = StandaloneActivitiesJson.decode(Files.readString(standaloneFile))
        assertEquals(inventory.commit, listed.commit)
        assertEquals(listed.activities.map { it.name }, standalone.getValue("activities").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0, listed.activities.size)
        assertEquals(listed.unresolvedWorkflows, standalone.getValue("unresolvedWorkflows").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            listOf("ComputedActivityNameWorkflow", "DeepHelperChainWorkflow", "DynamicDispatchWorkflow", "RecursionWorkflow"),
            listed.unresolvedWorkflows,
        )

        assertTrue(result.stderr.lines().filter { it.isNotEmpty() }.all { it.startsWith("analyse: ") }, result.stderr)
        assertContains(result.stderr, "found 30 workflow type(s) and 4 activity interface(s)")
        assertContains(result.stderr, "wrote 30 IR file(s)")
        assertContains(result.stderr, "the model is a degraded one")
    }

    @Test
    fun `what the analysis warns of while it reads the workflows is in the JSON summary as well as in the log`() {
        // The sample model with one more file: a workflow whose class has two constructors, one of them with a step.
        val copy = dir.resolve("model")
        Files.walk(model).use { paths ->
            paths.forEach { source ->
                val target = copy.resolve(model.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        }
        val manifest = copy.resolve("modules").listDirectoryEntries("*.json").sorted()
            .map { Json.parseToJsonElement(Files.readString(it)).jsonObject }
            .first { it.getValue("sources").jsonArray.isNotEmpty() }
        val sources = copy.resolve("source").resolve(manifest.getValue("sources").jsonArray.first().jsonPrimitive.content)
        Files.createDirectories(sources.resolve("extra"))
        Files.writeString(
            sources.resolve("extra/TwoWays.kt"),
            """
            package extra

            import io.temporal.activity.ActivityInterface
            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod

            @ActivityInterface
            interface ExtraTasks {
                fun prepare(id: String)
                fun work(id: String)
            }

            @WorkflowInterface
            interface TwoWays {
                @WorkflowMethod
                fun run(id: String)
            }

            class TwoWaysImpl : TwoWays {
                private val tasks = Workflow.newActivityStub(ExtraTasks::class.java)

                constructor(first: String) {
                    tasks.prepare(first)
                }

                constructor(first: String, second: String) {
                    println(first + second)
                }

                override fun run(id: String) {
                    tasks.work(id)
                }
            }
            """.trimIndent() + "\n",
        )

        val result = analyse("--json", model = copy)

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val warnings = result.summary().getValue("warnings").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(1, warnings.size, warnings.toString())
        assertContains(warnings.single(), "TwoWays.kt:23: ")
        assertContains(warnings.single(), "several constructors")
        // The log says the same, once.
        assertEquals(listOf("analyse: warning: ${warnings.single()}"), result.stderr.lines().filter { "warning: " in it })
        assertContains(irFiles(), "TwoWays.ir.json")
    }

    @Test
    fun `without --json the summary is a sentence, the inventory file, the activities no workflow calls and where the IR files are`() {
        val result = analyse()

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val lines = result.stdout.lines()
        assertEquals(
            "30 workflow type(s) and 4 activity interface(s) in the degraded model of ${SampleModels.degraded.index.commit}",
            lines[0],
        )
        assertTrue(Path.of(lines[1]).exists(), lines[1])
        assertEquals("0 activity type(s) that no workflow calls", lines[2])
        assertEquals(Path.of(lines[1]).resolveSibling("standalone-activities.json"), Path.of(lines[3]))
        assertTrue(Path.of(lines[3]).exists(), lines[3])
        assertEquals("30 IR file(s) in ${Path.of(lines[1]).parent}", lines[4])
        assertEquals(listOf(""), lines.drop(5))
    }

    @Test
    fun `two runs write the same bytes`() {
        fun contents(): Map<String, String> =
            out.listDirectoryEntries().sorted().associate { it.name to Files.readString(it) }

        analyse()
        val first = contents()
        analyse()

        assertEquals(32, first.size)
        assertEquals(first, contents())
    }

    @Test
    fun `the IR of one workflow type can be asked for`() {
        val result = analyse("--workflow", "OrderWorkflow", "--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertEquals(listOf("OrderWorkflow.ir.json"), irFiles())
        // The inventory and the summary still cover every workflow type.
        val workflows = result.summary().getValue("workflows").jsonArray.map { it.jsonObject }
        assertEquals(30, workflows.size)
        assertEquals(
            listOf("OrderWorkflow"),
            workflows.filter { it.getValue("ir").jsonArray.isNotEmpty() }.map { it.text("workflowType") },
        )
    }

    @Test
    fun `calls are followed as deep as --max-depth says, eight deep by default`() {
        /** The node and unknown counts of the workflow whose activity is ten calls away. */
        fun counts(vararg arguments: String): Pair<Int, Int> {
            val result = analyse("--workflow", "DeepHelperChainWorkflow", "--json", *arguments)
            assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
            val ir = result.summary().getValue("workflows").jsonArray.map { it.jsonObject }
                .single { it.text("workflowType") == "DeepHelperChainWorkflow" }
                .getValue("ir").jsonArray.single().jsonObject
            return ir.getValue("nodes").jsonPrimitive.int to ir.getValue("unknown").jsonPrimitive.int
        }

        // Cut off, the workflow is a start, the unknown node that says so and an end; followed, the activity takes its place.
        assertEquals(3 to 1, counts())
        assertEquals(3 to 1, counts("--max-depth", "8"))
        assertEquals(3 to 1, counts("--max-depth", "0"))
        assertEquals(3 to 0, counts("--max-depth", "10"))

        val negative = analyse("--max-depth", "-1")
        assertEquals(ExitCode.FAILURE, negative.statusCode)
        assertContains(negative.stderr, "--max-depth")
    }

    @Test
    fun `a workflow with more unknown nodes than allowed makes the exit status 2, after everything is written`() {
        // One of the three nodes of this workflow stands for an activity whose name is only known at run time.
        val over = analyse("--workflow", "ComputedActivityNameWorkflow", "--max-unresolved-pct", "33.2", "--json")

        assertEquals(ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED, over.statusCode, over.stderr)
        assertEquals(listOf("ComputedActivityNameWorkflow.ir.json"), irFiles())
        assertEquals("analyse", over.summary().text("command"))
        assertContains(
            over.stderr,
            "analyse: ComputedActivityNameWorkflow: 1 of 3 nodes are unknown (33.3 %), more than the 33.2 % allowed",
        )

        val within = analyse("--workflow", "ComputedActivityNameWorkflow", "--max-unresolved-pct", "33.4")
        assertEquals(ExitCode.SUCCESS, within.statusCode, within.stderr)
        assertFalse("nodes are unknown" in within.stderr)

        // Every workflow counts, and one that is fully resolved never does.
        val all = analyse("--max-unresolved-pct", "0")
        assertEquals(ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED, all.statusCode)
        // The four workflows that hold something that cannot be resolved.
        assertEquals(4, all.stderr.lines().count { "nodes are unknown" in it })
        assertFalse("TypedActivityWorkflow: " in all.stderr)
        assertEquals(ExitCode.SUCCESS, analyse("--max-unresolved-pct", "100").statusCode)
    }

    @Test
    fun `IR files of an earlier analysis are replaced, other files are left alone`() {
        Files.createDirectories(out)
        Files.writeString(out.resolve("RemovedWorkflow.ir.json"), "{}")
        Files.writeString(out.resolve("notes.json"), "{}")

        // Narrowed to one workflow type, a run leaves the IR of the others as it is.
        assertEquals(ExitCode.SUCCESS, analyse("--workflow", "TimerWorkflow").statusCode)
        assertEquals(listOf("RemovedWorkflow.ir.json", "TimerWorkflow.ir.json"), irFiles())

        val result = analyse()
        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertEquals(30, irFiles().size)
        assertFalse(out.resolve("RemovedWorkflow.ir.json").exists())
        assertTrue(out.resolve("notes.json").exists())
        assertContains(result.stderr, "analyse: removed RemovedWorkflow.ir.json")
    }

    @Test
    fun `asking for a workflow type the model does not have is a failure`() {
        val result = analyse("--workflow", "NoSuchWorkflow", "--json")

        assertEquals(ExitCode.FAILURE, result.statusCode)
        assertEquals("", result.stdout)
        assertContains(result.stderr, "analyse: failed: the model has no workflow type 'NoSuchWorkflow'")
        assertEquals(emptyList(), irFiles())
    }

    @Test
    fun `the JDK to resolve against can be given, and must be a JDK`() {
        val notAJdk = analyse("--jdk-home", dir.toString(), "--json")
        assertEquals(ExitCode.FAILURE, notAJdk.statusCode)
        assertEquals("", notAJdk.stdout)
        assertContains(notAJdk.stderr, "analyse: failed: ")
        assertContains(notAJdk.stderr, "is not the home directory of a JDK")
        assertFalse(out.exists())

        val missing = analyse("--jdk-home", dir.resolve("nowhere").toString())
        assertEquals(ExitCode.FAILURE, missing.statusCode)
        assertContains(missing.stderr, "--jdk-home")

        val running = analyse("--jdk-home", System.getProperty("java.home"))
        assertEquals(ExitCode.SUCCESS, running.statusCode, running.stderr)
        assertContains(running.stderr, "the JDK at ${System.getProperty("java.home")}")
    }

    @Test
    fun `a directory without a model is a failure`() {
        val result = analyse("--json", model = dir)

        assertEquals(ExitCode.FAILURE, result.statusCode)
        assertEquals("", result.stdout)
        assertContains(result.stderr, "analyse: failed: No model index at ")
        assertFalse(out.exists())
    }
}
