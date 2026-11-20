package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.analyser.testing.SampleSources
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import kotlin.test.Test
import kotlin.test.assertEquals

/** Graph extraction on a model extracted through the sample project's own Gradle build. */
class GradleExtractionTest : ExtractionContract(ModelMode.GRADLE) {
    override fun assertGolden(workflowType: String, document: IrDocument) {
        GoldenFiles.assertMatches("ir/$workflowType.ir.json", IrJson.encode(document))
    }

    override fun assertOverviewGolden(workflowType: String, overview: IrDocument?) {
        val file = "overview/$workflowType.ir.json"
        if (overview == null) GoldenFiles.assertAbsent(file) else GoldenFiles.assertMatches(file, IrJson.encode(overview))
    }

    @Test
    fun `a workflow belongs to the module that declares the class implementing it`() {
        assertEquals(setOf(":workflows"), documents.map { it.module }.toSet())
    }

    @Test
    fun `apart from module and mode a degraded model gives the same documents, node ids included`() {
        val degraded = SampleSources.degraded.let { backend ->
            WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.DEGRADED).extract(TemporalDiscovery.discover(backend))
        }

        assertEquals(documents, degraded.map { it.copy(modelMode = ModelMode.GRADLE, module = ":workflows") })
    }
}
