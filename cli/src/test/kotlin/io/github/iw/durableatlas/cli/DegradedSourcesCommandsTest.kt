package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.modelextractor.ExtractedModel
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
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a degraded model is made of, through the commands: the Java sources of a commit beside
 * its Kotlin sources, and the Kotlin extensions of the Temporal SDK on its classpath. A
 * workflow that uses an activity interface and a workflow interface declared in Java, and the
 * extensions, is drawn as it is from the build.
 */
class DegradedSourcesCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val out: Path get() = dir.resolve("out")
    private val repository: SampleRepository by lazy { SampleRepository.create(dir.resolve("repo")) }

    /** A commit with an activity interface and a workflow interface in Java, a Kotlin file beside them, and the workflow in Kotlin. */
    private val ledger: String by lazy {
        repository.commit("Add a workflow with interfaces declared in Java") { project ->
            val activities = Files.createDirectories(project.resolve("activities/src/main/java/com/example/fixture/activities"))
            Files.writeString(activities.resolve("LedgerActivities.java"), ACTIVITIES.trimIndent() + "\n")
            val java = Files.createDirectories(project.resolve("workflows/src/main/java/com/example/fixture/workflows/ledger"))
            Files.writeString(java.resolve("LedgerWorkflow.java"), INTERFACE.trimIndent() + "\n")
            Files.writeString(java.resolve("LedgerLimits.kt"), LIMITS.trimIndent() + "\n")
            val kotlin = Files.createDirectories(project.resolve("workflows/src/main/kotlin/com/example/fixture/workflows/ledger"))
            Files.writeString(kotlin.resolve("LedgerWorkflowImpl.kt"), WORKFLOW.trimIndent() + "\n")
        }
    }

    private fun run(commit: String, vararg arguments: String): CliktCommandTestResult =
        durableAtlas().test(listOf("run", "--repo", repository.project.toString(), "--commit", commit, "--out", out.toString(), "--mode", "degraded") + arguments)

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun `a degraded run reads what Java declares and the Kotlin extensions of the SDK`() {
        val result = run(ledger, "--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertContains(
            result.stderr,
            "run: the model is a degraded one: it holds the main Kotlin and Java sources of the commit; " +
                "what they take from libraries other than the Temporal SDK and its Kotlin extensions is unresolved\n",
        )
        // Nothing of the workflow is left to guess at.
        assertFalse("did not resolve" in result.stderr, result.stderr)

        val model = ExtractedModel.read(out.resolve("model").resolve(ledger))
        val manifest = model.manifests.single()
        assertEquals(
            listOf("activities/src/main/java", "activities/src/main/kotlin", "workflows/src/main/java", "workflows/src/main/kotlin"),
            manifest.sources.filter { it.startsWith("activities/") || it.startsWith("workflows/") },
        )
        assertTrue(model.jars(manifest).any { it.name.startsWith("temporal-kotlin-") })

        val workflows = Json.parseToJsonElement(result.stdout).jsonObject.getValue("workflows").jsonArray.map { it.jsonObject }
        assertEquals(31, workflows.size)
        val listed = workflows.single { it.text("workflowType") == "Ledger" }
        assertEquals(listOf(5, 0), listOf("nodes", "unknown").map { listed.getValue(it).jsonPrimitive.int })
        assertFalse("skipped" in listed)

        val document = IrJson.decode(Files.readString(Path.of(listed.text("ir"))))
        assertEquals(ModelMode.DEGRADED, document.modelMode)
        assertEquals("com.example.fixture.workflows.ledger.LedgerWorkflow", document.interfaceName)
        assertEquals(
            listOf(
                // The activity type as the Java interface names it; the timeout from options written as a lambda,
                // with a number of seconds that a Kotlin file in the Java directory holds.
                "activity LedgerPost PT8S",
                "activity Archive PT8S",
                "activity Close PT8S",
            ),
            document.nodes.filter { it.kind == NodeKind.ACTIVITY }.sortedBy { it.loc?.line }.map { "activity ${it.name} ${it.timeout}" },
        )

        val inventory = Json.parseToJsonElement(Files.readString(out.resolve("ir").resolve(ledger).resolve("inventory.json"))).jsonObject
        val declared = inventory.getValue("activityInterfaces").jsonArray.map { it.jsonObject }.single { it.text("interface").endsWith(".LedgerActivities") }
        assertEquals("activities/src/main/java/com/example/fixture/activities/LedgerActivities.java", declared.getValue("loc").jsonObject.text("file"))
        assertEquals(listOf("Close", "LedgerPost"), declared.getValue("activities").jsonArray.map { it.jsonObject.text("name") })
    }

    @Test
    fun `a commit with Java sources alone gives a model, an inventory and no diagram, and says why`() {
        val commit = repository.commit("Keep the Java sources alone") { project ->
            val activities = Files.createDirectories(project.resolve("activities/src/main/java/com/example/fixture/activities"))
            Files.writeString(activities.resolve("LedgerActivities.java"), ACTIVITIES.trimIndent() + "\n")
            repository.git("rm", "-r", "--quiet", "--", *MODULES.map { "$it/src/main/kotlin" }.toTypedArray())
        }

        val result = run(commit)

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertContains(
            result.stderr,
            "run: warning: the sources hold 1 Java file(s) and no Kotlin file. Only code written in Kotlin is analysed, " +
                "so no workflow is drawn; what the Java files declare is listed in the inventory\n",
        )
        assertContains(result.stdout, "0 workflow(s) drawn from the degraded model of $commit")
        assertEquals(listOf("activities/src/main/java"), ExtractedModel.read(out.resolve("model").resolve(commit)).manifests.single().sources)
        val inventory = Json.parseToJsonElement(Files.readString(out.resolve("ir").resolve(commit).resolve("inventory.json"))).jsonObject
        assertEquals(listOf("com.example.fixture.activities.LedgerActivities"), inventory.getValue("activityInterfaces").jsonArray.map { it.jsonObject.text("interface") })
        assertEquals(0, inventory.getValue("workflows").jsonArray.size)
    }

    @Test
    fun `a commit without main sources fails, and the message names both directories`() {
        val commit = repository.commit("Remove the sources") { _ ->
            repository.git("rm", "-r", "--quiet", "--", *MODULES.map { "$it/src/main/kotlin" }.toTypedArray())
        }

        val result = run(commit)

        assertEquals(ExitCode.FAILURE, result.statusCode)
        assertContains(result.stderr, "run: failed: Commit $commit has no src/main/kotlin or src/main/java directory")
    }

    private companion object {
        val MODULES = listOf("activities", "helpers", "nexus-api", "unrelated", "workflows")

        const val ACTIVITIES = """
            package com.example.fixture.activities;

            import io.temporal.activity.ActivityInterface;
            import io.temporal.activity.ActivityMethod;

            @ActivityInterface(namePrefix = "Ledger")
            public interface LedgerActivities {
                String post(String entry);

                @ActivityMethod(name = "Close")
                String closeBooks(String period);
            }
        """

        const val INTERFACE = """
            package com.example.fixture.workflows.ledger;

            import io.temporal.workflow.WorkflowInterface;
            import io.temporal.workflow.WorkflowMethod;

            @WorkflowInterface
            public interface LedgerWorkflow {
                @WorkflowMethod(name = "Ledger")
                String settle(String period);
            }
        """

        const val LIMITS = """
            package com.example.fixture.workflows.ledger

            object LedgerLimits {
                const val SECONDS = 8L
            }
        """

        const val WORKFLOW = """
            package com.example.fixture.workflows.ledger

            import com.example.fixture.activities.LedgerActivities
            import io.temporal.activity.ActivityOptions
            import io.temporal.workflow.Workflow
            import io.temporal.workflow.execute
            import java.time.Duration

            class LedgerWorkflowImpl : LedgerWorkflow {
                private val options = ActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(LedgerLimits.SECONDS)) }
                private val ledger = Workflow.newActivityStub(LedgerActivities::class.java, options)
                private val untyped = Workflow.newUntypedActivityStub(options)

                override fun settle(period: String): String {
                    val posted = ledger.post(period)
                    untyped.execute<String>("Archive", posted)
                    return ledger.closeBooks(period)
                }
            }
        """
    }
}
