package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.overlay.version.AmbiguousCommitException
import io.github.iw.durableatlas.overlay.version.CommitDirectories
import io.github.iw.durableatlas.overlay.version.VersionMap
import io.github.iw.durableatlas.overlay.version.VersionMapEntry
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Which directory a version map means by a commit, among the directories named after commits. */
class CommitDirectoriesTest {
    @TempDir
    lateinit var dir: Path

    private val first = "4f2a9c1d07e35b8a6c2f9e0d1b4a7c3e5f6a8b90"
    private val second = "4f2b7d3e0a1c5e9f8b6d4c2a0e7f5b3d1c9a8e60"
    private val third = "9b7d3e0c5a1f8e6d4b2c0a9f7e5d3b1c8a6f4e20"

    private fun directories(vararg names: String) = names.forEach { Files.createDirectories(dir.resolve(it)) }

    private fun map(vararg commits: String, unversioned: String? = null): VersionMap =
        VersionMap(versions = commits.mapIndexed { index, commit -> VersionMapEntry(null, "build-$index", commit) }, unversioned = unversioned)

    private fun find(vararg commits: String, unversioned: String? = null): CommitDirectories =
        CommitDirectories.find(dir, map(*commits, unversioned = unversioned))

    @Test
    fun `a commit written in full is the directory of that name`() {
        directories(first, second, third)

        val found = find(first, third)

        assertEquals(first, found.of(first))
        assertEquals(third, found.of(third))
    }

    @Test
    fun `an abbreviated commit is the only directory that begins with it`() {
        directories(first, second, third)

        val found = find("4f2a9c1", "4f2b", "9b7d", unversioned = "9b7d3e0")

        assertEquals(first, found.of("4f2a9c1"))
        assertEquals(second, found.of("4f2b"))
        assertEquals(third, found.of("9b7d"))
        assertEquals(third, found.of("9b7d3e0"))
    }

    @Test
    fun `a directory of exactly the name goes before those that begin with it`() {
        directories("4f2a9c1", first, "4f2a9c1ffff")

        assertEquals("4f2a9c1", find("4f2a9c1").of("4f2a9c1"))
    }

    @Test
    fun `a commit that several directories begin with is refused, with the directories it could be`() {
        val neighbour = "4f2a9c1e5d3b8a6c2f9e0d1b4a7c3e5f6a8b9012"
        directories(neighbour, first, third)

        val refused = assertFailsWith<AmbiguousCommitException> { find(third, "4f2a9c1") }

        assertEquals("4f2a9c1", refused.commit)
        assertEquals(listOf(first, neighbour), refused.candidates)
        assertEquals(
            "the version map names commit 4f2a9c1, which several directories in $dir begin with: $first, $neighbour; " +
                "the map has to write more of the commit",
            refused.message,
        )
        // So is the commit for histories without a version.
        assertEquals("4f2a", assertFailsWith<AmbiguousCommitException> { find(third, unversioned = "4f2a") }.commit)
    }

    @Test
    fun `a commit no directory begins with has none`() {
        directories(first, second)

        val found = find("9b7d3e0", first)

        assertNull(found.of("9b7d3e0"))
        assertEquals(first, found.of(first))
        // The end or the middle of a name is not its start, and neither is another case.
        assertNull(find("6a8b90").of("6a8b90"))
        assertNull(find("4F2A9C1").of("4F2A9C1"))
        // A commit the map does not name was not looked for.
        assertNull(found.of(second))
    }

    @Test
    fun `only what looks like an abbreviated SHA is looked for at the start of names`() {
        directories("v10", "v11", "release-2", first)

        // Too short to be a SHA, or not one at all: taken only in full.
        assertNull(find("v1").of("v1"))
        assertNull(find("release").of("release"))
        assertNull(find("4f2").of("4f2"))
        assertEquals("v10", find("v10").of("v10"))
        assertEquals(first, find("4f2a").of("4f2a"))
    }

    @Test
    fun `only directories stand for commits`() {
        directories(first)
        Files.writeString(dir.resolve("4f2a9c1.json"), "{}")
        Files.writeString(dir.resolve("9b7d3e0"), "")

        val found = find("4f2a", "9b7d3e0")

        assertEquals(first, found.of("4f2a"))
        assertNull(found.of("9b7d3e0"))
        // Where there is no directory to look in, no commit has one.
        assertNull(CommitDirectories.find(dir.resolve("nowhere"), map("4f2a")).of("4f2a"))
    }
}
