package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.graph.IrFiles
import io.github.iw.durableatlas.analyser.inventory.StandaloneActivitiesJson
import io.github.iw.durableatlas.analyser.testing.SampleModels
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.ir.validate
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A whole analysis of the sample project, from a model to the files in the output directory. */
class AnalyserTest {
    @TempDir
    lateinit var dir: Path

    private val model: Path get() = SampleModels.degraded.directory

    private fun contents(directory: Path): Map<String, String> =
        directory.listDirectoryEntries().sorted().associate { it.name to Files.readString(it) }

    @Test
    fun `an analysis writes the inventory and one valid IR file for each workflow type`() {
        val log = mutableListOf<String>()

        val result = Analyser(log::add).analyse(AnalysisRequest(model, dir.resolve("ir")))

        assertEquals(emptyList(), result.failures)
        assertEquals(result.inventory.workflows.map { it.workflowType }, result.workflows.map { it.document.workflowType })
        assertEquals(result.workflows.map { "${it.document.workflowType}.ir.json" }, result.workflows.map { it.file.name })
        assertEquals(
            (result.workflows.map { it.file.name } + "inventory.json" + "standalone-activities.json").sorted(),
            dir.resolve("ir").listDirectoryEntries().map { it.name }.sorted(),
        )
        val schema = JsonSchemaChecker.forWorkflowIr()
        for (workflow in result.workflows) {
            val text = Files.readString(workflow.file)
            schema.assertValid(text)
            assertTrue(text.endsWith("}\n") && '\r' !in text, workflow.file.name)
            val document = IrJson.decode(text)
            assertEquals(workflow.document, document)
            assertEquals(emptyList(), document.validate(), workflow.file.name)
            assertEquals(SampleModels.degraded.index.commit, document.commit)
            assertEquals(ModelMode.DEGRADED, document.modelMode)
            assertTrue(IrFiles.isIrFile(workflow.file.name))
        }
        assertTrue(log.any { it.startsWith("wrote 30 IR file(s)") }, log.joinToString("\n"))
        assertTrue(log.none { it.startsWith("warning") }, log.joinToString("\n"))
    }

    @Test
    fun `an analysis lists the activity types no workflow calls, next to the inventory`() {
        val result = Analyser().analyse(AnalysisRequest(model, dir.resolve("ir")))

        assertEquals(dir.resolve("ir").toRealPath().resolve("standalone-activities.json"), result.standaloneActivitiesFile.toRealPath())
        val text = Files.readString(result.standaloneActivitiesFile)
        JsonSchemaChecker.forSchema(StandaloneActivitiesJson.schema()).assertValid(text)
        val standalone = StandaloneActivitiesJson.decode(text)
        assertEquals(result.standaloneActivities, standalone)
        assertEquals(SampleModels.degraded.index.commit, standalone.commit)
        assertEquals(ModelMode.DEGRADED, standalone.modelMode)
        // Every one of the twelve activity types of the sample project is a step of some workflow.
        assertEquals(12, result.inventory.activityInterfaces.sumOf { it.activities.size })
        assertEquals(emptyList(), standalone.activities)
        // The four workflows with a call the analysis cannot follow.
        assertEquals(
            listOf("ComputedActivityNameWorkflow", "DeepHelperChainWorkflow", "DynamicDispatchWorkflow", "RecursionWorkflow"),
            standalone.unresolvedWorkflows,
        )
    }

    @Test
    fun `two analyses of one model write the same directory, byte for byte`() {
        Analyser().analyse(AnalysisRequest(model, dir.resolve("first")))
        Analyser().analyse(AnalysisRequest(model, dir.resolve("second")))

        val first = contents(dir.resolve("first"))
        assertEquals(32, first.size)
        assertEquals(first, contents(dir.resolve("second")))
    }

    @Test
    fun `an analysis narrowed to a workflow type writes its IR only, to the file it always goes to`() {
        val out = dir.resolve("ir")

        val result = Analyser().analyse(AnalysisRequest(model, out, workflowType = "Shipment"))

        assertEquals(listOf("Shipment.ir.json"), result.workflows.map { it.file.name })
        assertEquals("com.example.fixture.workflows.order.ShipmentWorkflowImpl", result.workflows.single().document.implementation)
        assertEquals(
            listOf("Shipment.ir.json", "inventory.json", "standalone-activities.json"),
            out.listDirectoryEntries().map { it.name }.sorted(),
        )
        assertEquals(30, result.inventory.workflows.size)

        // A workflow type the model does not have gives no IR; the caller decides what that means.
        val none = Analyser().analyse(AnalysisRequest(model, dir.resolve("none"), workflowType = "NoSuchWorkflow"))
        assertEquals(emptyList(), none.workflows)
        assertEquals(emptyList(), none.failures)
    }

    @Test
    fun `calls are followed as deep as the request says, eight deep unless it says otherwise`() {
        fun unknownAt(maxDepth: Int?): List<String?> {
            val request = AnalysisRequest(model, dir.resolve("depth-$maxDepth"), workflowType = "DeepHelperChainWorkflow")
            val result = Analyser().analyse(if (maxDepth == null) request else request.copy(maxDepth = maxDepth))
            return result.workflows.single().document.nodes.filter { it.kind == NodeKind.UNKNOWN }.map { it.label }
        }

        // The activity of this workflow is ten calls away.
        assertEquals(listOf("deepChainLevel9"), unknownAt(null))
        assertEquals(listOf("deepChainLevel9"), unknownAt(8))
        assertEquals(listOf("deepChainLevel1"), unknownAt(0))
        assertEquals(emptyList(), unknownAt(10))
    }
}
