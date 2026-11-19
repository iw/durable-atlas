package io.github.iw.durableatlas.modelextractor

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleScopingTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `modules that use Temporal are kept together with everything they depend on`() {
        val dependencies = mapOf(
            ":workflows" to listOf(":helpers", ":api"),
            ":helpers" to listOf(":util"),
            ":api" to emptyList(),
            ":util" to emptyList(),
            ":reports" to listOf(":util"),
            ":web" to listOf(":workflows"),
        )

        val scope = ModuleScoping.scope(dependencies, usesTemporal = setOf(":workflows"))

        assertEquals(listOf(":api", ":helpers", ":util", ":workflows"), scope.kept)
        assertEquals(
            listOf(
                DroppedModule(":reports", DropReasons.NO_TEMPORAL_USAGE),
                DroppedModule(":web", DropReasons.NO_TEMPORAL_USAGE),
            ),
            scope.dropped,
        )
    }

    @Test
    fun `a dependency that was not described is reported, not kept`() {
        val scope = ModuleScoping.scope(
            mapOf(":workflows" to listOf(":schemas"), ":other" to listOf(":assets")),
            usesTemporal = setOf(":workflows"),
        )

        assertEquals(listOf(":workflows"), scope.kept)
        assertEquals(
            listOf(
                DroppedModule(":other", DropReasons.NO_TEMPORAL_USAGE),
                DroppedModule(":schemas", DropReasons.NOT_A_JVM_MODULE),
            ),
            scope.dropped,
        )
    }

    @Test
    fun `dependency cycles do not trap the search`() {
        val scope = ModuleScoping.scope(mapOf(":a" to listOf(":b"), ":b" to listOf(":a")), usesTemporal = setOf(":a"))

        assertEquals(listOf(":a", ":b"), scope.kept)
        assertEquals(emptyList(), scope.dropped)
    }

    @Test
    fun `nothing is kept when no module uses Temporal`() {
        val scope = ModuleScoping.scope(mapOf(":a" to listOf(":b"), ":b" to emptyList()), usesTemporal = emptySet())

        assertEquals(emptyList(), scope.kept)
        assertEquals(listOf(":a", ":b"), scope.dropped.map { it.path })
    }

    @Test
    fun `Temporal usage is found in Kotlin and Java sources only`() {
        val kotlin = source("kotlin/src/a/Flow.kt", "import io.temporal.workflow.Workflow\n")
        val java = source("java/src/b/Flow.java", "class Flow { io.temporal.workflow.Workflow w; }\n")
        val text = source("text/src/notes.txt", "see io.temporal docs\n")
        val plain = source("plain/src/c/Report.kt", "annotation class WorkflowInterface\n")

        assertTrue(ModuleScoping.usesTemporal(listOf(kotlin)))
        assertTrue(ModuleScoping.usesTemporal(listOf(plain, java)))
        assertFalse(ModuleScoping.usesTemporal(listOf(text)))
        assertFalse(ModuleScoping.usesTemporal(listOf(plain)))
        assertFalse(ModuleScoping.usesTemporal(emptyList()))
    }

    /** Writes a file and returns the top-level directory it is in. */
    private fun source(path: String, text: String): Path {
        val file = dir.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return dir.resolve(path.substringBefore('/'))
    }
}
