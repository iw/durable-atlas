package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.overlay.history.WorkerVersion
import io.github.iw.durableatlas.overlay.version.CommitChoice
import io.github.iw.durableatlas.overlay.version.VersionMap
import io.github.iw.durableatlas.overlay.version.VersionMapEntry
import io.github.iw.durableatlas.overlay.version.VersionMapFormatException
import io.github.iw.durableatlas.overlay.version.VersionMapJson
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VersionMapTest {
    @TempDir
    lateinit var dir: Path

    private val schema = JsonSchemaChecker.forSchema(VersionMapJson.schema())

    private val text = """
        {
          "schemaVersion": 1,
          "versions": [
            { "deployment": "orders", "buildId": "2026.11.3", "commit": "4f2a9c1" },
            { "deployment": "billing", "buildId": "2026.11.3", "commit": "77e0b12" },
            { "buildId": "2026.11.3", "commit": "c0ffee0" },
            { "buildId": "2026.11.4", "commit": "9b7d3e0" }
          ],
          "unversioned": "4f2a9c1"
        }
    """.trimIndent()

    private fun refused(text: String): String = assertFailsWith<VersionMapFormatException> { VersionMapJson.decode(text, "versions.json") }.message!!

    @Test
    fun `a version map is read from JSON, and fits its schema`() {
        val map = VersionMapJson.decode(text, "versions.json")

        assertEquals(
            VersionMap(
                versions = listOf(
                    VersionMapEntry("orders", "2026.11.3", "4f2a9c1"),
                    VersionMapEntry("billing", "2026.11.3", "77e0b12"),
                    VersionMapEntry(null, "2026.11.3", "c0ffee0"),
                    VersionMapEntry(null, "2026.11.4", "9b7d3e0"),
                ),
                unversioned = "4f2a9c1",
            ),
            map,
        )
        schema.assertValid(text)
        assertEquals(map, VersionMapJson.read(Files.writeString(dir.resolve("versions.json"), text)))

        // The least a map can hold.
        val least = """{"schemaVersion": 1, "versions": []}"""
        assertEquals(VersionMap(versions = emptyList()), VersionMapJson.decode(least, "versions.json"))
        schema.assertValid(least)
    }

    @Test
    fun `an entry with the deployment goes before one for the build id alone`() {
        val map = VersionMapJson.decode(text, "versions.json")

        assertEquals(CommitChoice.Commit("4f2a9c1"), map.commitOf(WorkerVersion("orders", "2026.11.3")))
        assertEquals(CommitChoice.Commit("77e0b12"), map.commitOf(WorkerVersion("billing", "2026.11.3")))
        // Any other deployment with that build id.
        assertEquals(CommitChoice.Commit("c0ffee0"), map.commitOf(WorkerVersion("shipping", "2026.11.3")))
        assertEquals(CommitChoice.Commit("9b7d3e0"), map.commitOf(WorkerVersion("orders", "2026.11.4")))
    }

    @Test
    fun `a version without an entry is unknown, and is not taken for a history without a version`() {
        val map = VersionMapJson.decode(text, "versions.json")
        val unknown = WorkerVersion("orders", "2026.11.5")

        assertEquals(CommitChoice.UnknownVersion(unknown), map.commitOf(unknown))
        // A build id is not matched by the name of a deployment.
        assertEquals(CommitChoice.UnknownVersion(WorkerVersion("2026.11.4", "orders")), map.commitOf(WorkerVersion("2026.11.4", "orders")))
    }

    @Test
    fun `a version that names no deployment is matched only by an entry for its build id alone`() {
        val map = VersionMapJson.decode(text, "versions.json")

        assertEquals(CommitChoice.Commit("c0ffee0"), map.commitOf(WorkerVersion(null, "2026.11.3")))
        assertEquals(CommitChoice.Commit("9b7d3e0"), map.commitOf(WorkerVersion(null, "2026.11.4")))
        val named = VersionMap(versions = listOf(VersionMapEntry("orders", "2026.11.3", "4f2a9c1")), unversioned = "4f2a9c1")
        assertEquals(CommitChoice.UnknownVersion(WorkerVersion(null, "2026.11.3")), named.commitOf(WorkerVersion(null, "2026.11.3")))
    }

    @Test
    fun `a history without a version goes to the commit named for such histories, if there is one`() {
        assertEquals(CommitChoice.Commit("4f2a9c1"), VersionMapJson.decode(text, "versions.json").commitOf(null))
        assertEquals(CommitChoice.NoVersion, VersionMap(versions = listOf(VersionMapEntry(null, "b", "c"))).commitOf(null))
    }

    @Test
    fun `what is not a version map is refused, with the reason`() {
        assertContains(refused("[]"), "versions.json is not a version map: it is not a JSON object")
        assertContains(refused("{ not json"), "versions.json is not a version map: ")
        assertContains(refused("""{"versions": []}"""), "it has no schemaVersion")
        assertContains(refused("""{"schemaVersion": 2, "versions": []}"""), "unsupported schemaVersion 2 (supported: 1)")
        assertContains(refused("""{"schemaVersion": 1}"""), "versions")
        assertContains(refused("""{"schemaVersion": 1, "versions": [], "default": "abc"}"""), "default")
        assertContains(refused("""{"schemaVersion": 1, "versions": [{"buildId": "b"}]}"""), "commit")
        assertContains(refused("""{"schemaVersion": 1, "versions": [{"buildId": "b", "commit": "c", "sha": "c"}]}"""), "sha")
        assertContains(refused("""{"schemaVersion": 1, "versions": [{"buildId": " ", "commit": "c"}]}"""), "an entry has an empty buildId")
        assertContains(
            refused("""{"schemaVersion": 1, "versions": [{"deployment": "", "buildId": "b", "commit": "c"}]}"""),
            "the entry for build id b has an empty deployment",
        )
        assertContains(
            refused("""{"schemaVersion": 1, "versions": [{"deployment": "d", "buildId": "b", "commit": "../c"}]}"""),
            "the commit of d:b, '../c', cannot be the name of a directory",
        )
        assertContains(refused("""{"schemaVersion": 1, "versions": [], "unversioned": "a/b"}"""), "the commit for histories without a version, 'a/b'")
        assertContains(
            refused(
                """{"schemaVersion": 1, "versions": [{"deployment": "d", "buildId": "b", "commit": "c1"}, {"deployment": "d", "buildId": "b", "commit": "c2"}]}""",
            ),
            "d:b is listed twice",
        )
        assertContains(
            refused("""{"schemaVersion": 1, "versions": [{"buildId": "b", "commit": "c"}, {"buildId": "b", "commit": "c"}]}"""),
            "b is listed twice",
        )

        val missing = assertFailsWith<VersionMapFormatException> { VersionMapJson.read(dir.resolve("nowhere.json")) }
        assertContains(missing.message!!, "nowhere.json cannot be read")
    }

    @Test
    fun `the schema refuses what the reader refuses`() {
        val refusedByBoth = listOf(
            "[]",
            """{"versions": []}""",
            """{"schemaVersion": 2, "versions": []}""",
            """{"schemaVersion": 1}""",
            """{"schemaVersion": 1, "versions": [], "default": "abc"}""",
            """{"schemaVersion": 1, "versions": [{"buildId": "b"}]}""",
            """{"schemaVersion": 1, "versions": [{"buildId": "b", "commit": "c", "sha": "c"}]}""",
            """{"schemaVersion": 1, "versions": [{"buildId": " ", "commit": "c"}]}""",
            """{"schemaVersion": 1, "versions": [{"deployment": "", "buildId": "b", "commit": "c"}]}""",
            """{"schemaVersion": 1, "versions": [{"deployment": "d", "buildId": "b", "commit": "../c"}]}""",
            """{"schemaVersion": 1, "versions": [], "unversioned": "a/b"}""",
        )
        for (text in refusedByBoth) {
            assertTrue(schema.violations(text).isNotEmpty(), "the schema accepts $text")
            assertFailsWith<VersionMapFormatException>(text) { VersionMapJson.decode(text, "versions.json") }
        }
    }
}
