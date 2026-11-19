package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DurableAtlasTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `help lists every subcommand`() {
        val result = durableAtlas().test("--help")

        assertEquals(ExitCode.SUCCESS, result.statusCode)
        val listed = Regex("""^ {2}(\w+) """, RegexOption.MULTILINE)
            .findAll(result.stdout.substringAfter("Commands:"))
            .map { it.groupValues[1] }
            .toList()
        assertEquals(listOf("model", "analyse", "render", "overlay", "fetch", "run"), listed)
    }

    @Test
    fun `bad arguments fail with a usage error on stderr`() {
        val missing = durableAtlas().test("model --commit abc123 --out $dir/model")
        assertEquals(ExitCode.FAILURE, missing.statusCode)
        assertContains(missing.stderr, "--repo")

        val invalid = durableAtlas().test("render --ir $dir --out $dir/diagrams --max-nodes none")
        assertEquals(ExitCode.FAILURE, invalid.statusCode)
        assertContains(invalid.stderr, "--max-nodes")
    }
}
