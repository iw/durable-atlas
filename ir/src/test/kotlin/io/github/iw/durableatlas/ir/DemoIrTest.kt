package io.github.iw.durableatlas.ir

import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.ir.testing.RegionEdges
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the hand-written IR documents of the demo project under `demo/expected`: what the
 * analysis of the demo's workflows is expected to give.
 */
class DemoIrTest {
    private val checker = JsonSchemaChecker.forWorkflowIr()
    private val documents: List<Path> = DEMO.resolve("expected").listDirectoryEntries("*.ir.json").sorted()

    @Test
    fun `the demo has expected documents`() {
        assertTrue(documents.isNotEmpty(), "no *.ir.json file in ${DEMO.resolve("expected").toAbsolutePath()}")
    }

    @Test
    fun `expected documents match the schema`() = eachDocument { file, _ ->
        assertEquals(emptyList(), checker.violations(file.readText()), file.name)
    }

    @Test
    fun `expected documents are consistent`() = eachDocument { file, document ->
        assertEquals(emptyList(), document.validate(), file.name)
    }

    @Test
    fun `expected documents are written in canonical form`() = eachDocument { file, document ->
        assertEquals(file.readText(), IrJson.encode(document), file.name)
    }

    @Test
    fun `source locations point at lines of the demo sources`() = eachDocument { file, document ->
        val lineCounts = HashMap<String, Int>()
        for (node in document.nodes) {
            val loc = node.loc ?: continue
            val lines = lineCounts.getOrPut(loc.file) {
                val source = DEMO.resolve(loc.file)
                assertTrue(source.isRegularFile(), "${file.name}: node '${node.id}' points at missing file ${loc.file}")
                source.readLines().size
            }
            assertTrue(loc.line <= lines, "${file.name}: node '${node.id}' points at line ${loc.line} of ${loc.file}, which has $lines")
        }
    }

    @Test
    fun `no edge joins a region and a node inside it, apart from a node's edge to the end of a region around it`() = eachDocument { file, document ->
        assertEquals(emptyList(), RegionEdges.violations(document), file.name)
    }

    private fun eachDocument(check: (Path, IrDocument) -> Unit) {
        documents.forEach { check(it, IrJson.decode(it.readText())) }
    }

    private companion object {
        // Gradle runs tests with the module directory as the working directory.
        val DEMO: Path = Path.of("..", "demo").also {
            check(Files.isDirectory(it)) { "the demo project was not found at ${it.toAbsolutePath()}" }
        }
    }
}
