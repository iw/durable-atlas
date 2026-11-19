package io.github.iw.durableatlas.modelextractor.testing

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleRepositoryTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a commit leaves the objects of the repository loose`() {
        val repository = SampleRepository.create(dir.resolve("repo"))
        // Git estimates how many objects are loose from those in objects/17. Enough there make it repack after
        // a commit; in the foreground, so that the test sees it, rather than in the background as it usually does.
        repository.git("config", "maintenance.autoDetach", "false")
        repository.git("config", "gc.autoDetach", "false")
        repository.git("hash-object", "-w", *looseObjects(prefix = "17", count = 30).map { it.toString() }.toTypedArray())
        val objects = repository.root.resolve(".git/objects")
        assertTrue(objects.resolve("17").listDirectoryEntries().size >= 30, "objects/17 holds the planted objects")

        repository.commit("Add notes") { project -> Files.writeString(project.resolve("NOTES.txt"), "notes\n") }

        assertEquals(emptyList(), objects.resolve("pack").listDirectoryEntries().map { it.name })
        assertTrue(objects.resolve("17").listDirectoryEntries().size >= 30, "the planted objects are still loose")
    }

    /** Files, outside the repository, whose contents git stores as objects whose ids begin with [prefix]. */
    private fun looseObjects(prefix: String, count: Int): List<Path> =
        generateSequence(0) { it + 1 }
            .map { "probe $it\n" }
            .filter { blobId(it).startsWith(prefix) }
            .take(count)
            .mapIndexed { index, content -> Files.writeString(dir.resolve("probe-$index.txt"), content) }
            .toList()

    private fun blobId(content: String): String {
        val bytes = content.toByteArray()
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray())
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
