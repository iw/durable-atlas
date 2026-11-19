package io.github.iw.durableatlas.ir.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Compares generated text with the files under `src/test/resources/golden/` of the module whose
 * tests are running, byte for byte. Run the tests with `-Ddurable-atlas.updateGolden=true` to
 * rewrite the files instead.
 */
object GoldenFiles {
    private const val UPDATE_PROPERTY = "durable-atlas.updateGolden"

    // Gradle runs tests with the module directory as the working directory.
    private val root: Path = Path.of("src", "test", "resources", "golden")

    fun assertMatches(relativePath: String, actual: String) {
        val file = root.resolve(relativePath)
        if (System.getProperty(UPDATE_PROPERTY).toBoolean()) {
            Files.createDirectories(file.toAbsolutePath().parent)
            Files.write(file, actual.toByteArray(Charsets.UTF_8))
            return
        }
        if (!Files.exists(file)) {
            fail("Missing golden file ${file.toAbsolutePath()}; rerun with -D$UPDATE_PROPERTY=true to create it")
        }
        val expected = Files.readAllBytes(file)
        if (!expected.contentEquals(actual.toByteArray(Charsets.UTF_8))) {
            // Fails with a readable diff; the fallback covers differences that vanish when decoded.
            assertEquals(expected.toString(Charsets.UTF_8), actual, "Golden file $relativePath differs")
            fail("Golden file $relativePath differs in its byte encoding")
        }
    }
}
