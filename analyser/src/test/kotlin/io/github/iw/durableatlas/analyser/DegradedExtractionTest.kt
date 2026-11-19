package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Graph extraction on a model made without the sample project's build: one module, a fixed classpath. */
class DegradedExtractionTest : ExtractionContract(ModelMode.DEGRADED) {
    /**
     * The golden files are written by the test of the Gradle model. A degraded model gives the
     * same documents byte for byte, node ids included, once the two things it cannot know are
     * taken from the golden file: the module, and the mode itself.
     */
    override fun assertGolden(workflowType: String, document: IrDocument) {
        val golden = Files.readString(Path.of("src/test/resources/golden/ir/$workflowType.ir.json"))
        val expected = IrJson.decode(golden)

        assertEquals(ModelMode.GRADLE, expected.modelMode)
        assertEquals(golden, IrJson.encode(document.copy(modelMode = expected.modelMode, module = expected.module)))
    }

    @Test
    fun `everything belongs to the single module of the model`() {
        assertEquals(setOf(":"), documents.map { it.module }.toSet())
    }
}
