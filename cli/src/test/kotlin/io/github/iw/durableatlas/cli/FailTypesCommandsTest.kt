package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.modelextractor.testing.SampleRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Which exceptions fail a workflow, through the commands: a failure of Temporal and an exception
 * that a worker is told of are drawn as failures, any other is not. Where the sources hand the
 * exceptions over as a value, nothing can be read, every exception is drawn as before, and a
 * warning names the place once.
 */
class FailTypesCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val out: Path get() = dir.resolve("out")
    private val repository: SampleRepository by lazy { SampleRepository.create(dir.resolve("repo")) }

    /** A workflow that throws four exceptions, and a worker set up in Java that is told of one of them. */
    private val faulty: String by lazy {
        repository.commit("Add a workflow that throws") { project ->
            val kotlin = Files.createDirectories(project.resolve("workflows/src/main/kotlin/com/example/fixture/workflows/faults"))
            Files.writeString(kotlin.resolve("FaultyWorkflow.kt"), WORKFLOW.trimIndent() + "\n")
            val java = Files.createDirectories(project.resolve("workflows/src/main/java/com/example/fixture/workflows/faults"))
            Files.writeString(java.resolve("FaultyWorkers.java"), JAVA_WORKER.trimIndent() + "\n")
        }
    }

    private fun run(commit: String): CliktCommandTestResult =
        durableAtlas().test(listOf("run", "--repo", repository.project.toString(), "--commit", commit, "--out", out.toString(), "--mode", "degraded", "--json"))

    /** The names of the `fail` nodes of the workflow, in the order of their lines. */
    private fun failures(result: CliktCommandTestResult): List<String?> {
        val workflows = Json.parseToJsonElement(result.stdout).jsonObject.getValue("workflows").jsonArray.map { it.jsonObject }
        val listed = workflows.single { it.getValue("workflowType").jsonPrimitive.content == "FaultyWorkflow" }
        val document = IrJson.decode(Files.readString(Path.of(listed.getValue("ir").jsonPrimitive.content)))
        return document.nodes.filter { it.kind == NodeKind.FAIL }.sortedBy { it.loc?.line }.map { it.name }
    }

    @Test
    fun `a run draws the failures of Temporal and the exceptions a worker is told of, and says nothing of it`() {
        val result = run(faulty)

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertEquals(listOf("StockFault", "Stopped"), failures(result))
        assertFalse("which exceptions fail a workflow" in result.stderr, result.stderr)
    }

    @Test
    fun `a run warns once where the exceptions that fail a workflow cannot be read, and draws every exception as a failure`() {
        faulty
        val commit = repository.commit("Tell a worker its exceptions through a value") { project ->
            val kotlin = project.resolve("workflows/src/main/kotlin/com/example/fixture/workflows/faults")
            Files.writeString(kotlin.resolve("FaultyWiring.kt"), UNREADABLE_WORKER.trimIndent() + "\n")
        }

        val result = run(commit)

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val warning = "run: warning: workflows/src/main/kotlin/com/example/fixture/workflows/faults/FaultyWiring.kt:6: " +
            "which exceptions fail a workflow here cannot be told; every exception that may leave a workflow is drawn as its failure\n"
        assertContains(result.stderr, warning)
        assertEquals(1, result.stderr.windowed(warning.length).count { it == warning })
        assertEquals(listOf("IllegalArgumentException", "OrderFault", "StockFault", "Stopped"), failures(result))
    }

    private companion object {
        const val WORKFLOW = """
            package com.example.fixture.workflows.faults

            import io.temporal.failure.ApplicationFailure
            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod
            import java.time.Duration

            class OrderFault(message: String) : RuntimeException(message)

            class StockFault(message: String) : RuntimeException(message)

            @WorkflowInterface
            interface FaultyWorkflow {
                @WorkflowMethod
                fun run(input: String): String
            }

            class FaultyWorkflowImpl : FaultyWorkflow {
                override fun run(input: String): String {
                    if (input.isBlank()) throw IllegalArgumentException("nothing given")
                    Workflow.sleep(Duration.ofSeconds(1))
                    if (input == "order") throw OrderFault("no such order")
                    if (input == "stock") throw StockFault("out of stock")
                    if (input == "stop") throw ApplicationFailure.newFailure("stopped", "Stopped")
                    return input
                }
            }
        """

        const val JAVA_WORKER = """
            package com.example.fixture.workflows.faults;

            import io.temporal.worker.WorkflowImplementationOptions;

            public final class FaultyWorkers {
                private FaultyWorkers() {}

                public static WorkflowImplementationOptions options() {
                    return WorkflowImplementationOptions.newBuilder().setFailWorkflowExceptionTypes(StockFault.class).build();
                }
            }
        """

        const val UNREADABLE_WORKER = """
            package com.example.fixture.workflows.faults

            import io.temporal.worker.WorkflowImplementationOptions

            fun options(failOn: Array<Class<out Throwable>>): WorkflowImplementationOptions =
                WorkflowImplementationOptions.newBuilder().setFailWorkflowExceptionTypes(*failOn).build()
        """
    }
}
