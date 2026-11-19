package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.inventory.InventoryJson
import io.github.iw.durableatlas.ir.ModelMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Discovery on a model made without the sample project's build: one module, a fixed classpath. */
class DegradedDiscoveryTest : DiscoveryContract(ModelMode.DEGRADED) {
    @Test
    fun `apart from module paths the inventory is the one a Gradle model gives`() {
        // The golden file is written by the test of the Gradle model.
        val golden = InventoryJson.decode(Files.readString(Path.of("src/test/resources/golden/inventory.json")))

        assertEquals(golden.withoutModules(), inventory.withFixedCommit().withoutModules())
    }

    @Test
    fun `everything belongs to the single module of the model`() {
        assertEquals(setOf(":"), inventory.workflows.map { it.module }.toSet())
        assertEquals(setOf(":"), inventory.workflows.flatMap { it.implementations }.map { it.module }.toSet())
        assertEquals(setOf(":"), inventory.activityInterfaces.map { it.module }.toSet())
    }

    @Test
    fun `the look-alike annotation and the interface that carries it are part of the analysed sources`() {
        // Unlike a Gradle model, a degraded one keeps the module that has nothing to do with
        // Temporal, so here the resolved annotation type is all that keeps its interface out.
        val lookAlike = assertNotNull(backend.findClass("com.example.fixture.unrelated.ReportWorkflow"))
        assertTrue(lookAlike.isSource)
        assertEquals(listOf("com.example.fixture.unrelated.WorkflowInterface"), lookAlike.annotations.map { it.type })
        assertTrue(backend.sourceClasses().any { it.name == "com.example.fixture.unrelated.ReportWorkflowImpl" })
    }
}
