package io.github.iw.durableatlas.modelextractor

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ZipTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `entries are unpacked below the target`() {
        val archive = archive("a/" to null, "a/b/One.kt" to "one", "Two.kt" to "two")

        Zip.extract(archive, dir.resolve("out"))

        assertEquals("one", Files.readString(dir.resolve("out/a/b/One.kt")))
        assertEquals("two", Files.readString(dir.resolve("out/Two.kt")))
    }

    @Test
    fun `an entry that points outside the target is refused`() {
        val archive = archive("../escaped.txt" to "x")

        assertFailsWith<ModelExtractionException> { Zip.extract(archive, dir.resolve("out")) }
        assertFalse(Files.exists(dir.resolve("escaped.txt")))
    }

    /** Writes an archive of files, given as name and text, and directories, given as name and `null`. */
    private fun archive(vararg entries: Pair<String, String?>): Path {
        val file = dir.resolve("archive.zip")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (text != null) zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}
