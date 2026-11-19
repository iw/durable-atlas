package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.inventory.InventoryJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Discovery on a model extracted through the sample project's own Gradle build. */
class GradleDiscoveryTest : DiscoveryContract(ModelMode.GRADLE) {
    @Test
    fun `the inventory is the golden one`() {
        GoldenFiles.assertMatches("inventory.json", InventoryJson.encode(inventory.withFixedCommit()))
    }

    @Test
    fun `interfaces and implementations are attributed to the modules that declare them`() {
        assertEquals(setOf(":workflows"), inventory.workflows.map { it.module }.toSet())
        assertEquals(setOf(":workflows"), inventory.workflows.flatMap { it.implementations }.map { it.module }.toSet())
        assertEquals(setOf(":activities"), inventory.activityInterfaces.map { it.module }.toSet())
        assertEquals(
            setOf(":activities"),
            inventory.activityInterfaces.flatMap { it.implementations }.map { it.module }.toSet(),
        )
    }

    @Test
    fun `a module the model left out is not analysed`() {
        assertFalse(backend.sourceClasses().any { it.name.startsWith("com.example.fixture.unrelated.") })
        assertEquals(
            setOf(":activities", ":helpers", ":nexus-api", ":workflows"),
            backend.sourceClasses().map { it.module }.toSet(),
        )
    }
}
