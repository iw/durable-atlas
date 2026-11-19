package io.github.iw.durableatlas.modelextractor

import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelJsonTest {
    private val commit = "0123456789abcdef0123456789abcdef01234567"

    private val manifest = ModuleManifest(
        commit = commit,
        path = ":orders:workflows",
        sources = listOf("orders/workflows/src/main/kotlin"),
        projectDeps = listOf(":orders:api"),
        jars = listOf("/home/dev/.gradle/caches/temporal-sdk-1.0.0.jar", "orders/workflows/libs/local.jar"),
        mode = ModelMode.GRADLE,
    )

    private val index = ModelIndex(
        commit = commit,
        mode = ModelMode.GRADLE,
        modules = listOf(IndexedModule(":orders:workflows", "modules/_orders_workflows.json")),
        dropped = listOf(DroppedModule(":reports", DropReasons.NO_TEMPORAL_USAGE)),
    )

    private val manifestSchema = JsonSchemaChecker.forResource(ModelSchemas.MANIFEST_RESOURCE)
    private val indexSchema = JsonSchemaChecker.forResource(ModelSchemas.INDEX_RESOURCE)

    @Test
    fun `a manifest is written with a fixed key order and a trailing newline`() {
        val expected = """
            {
              "schemaVersion": 1,
              "commit": "0123456789abcdef0123456789abcdef01234567",
              "path": ":orders:workflows",
              "sources": [
                "orders/workflows/src/main/kotlin"
              ],
              "projectDeps": [
                ":orders:api"
              ],
              "jars": [
                "/home/dev/.gradle/caches/temporal-sdk-1.0.0.jar",
                "orders/workflows/libs/local.jar"
              ],
              "mode": "gradle"
            }
        """.trimIndent() + "\n"

        assertEquals(expected, ModelJson.encode(manifest))
    }

    @Test
    fun `an index is written with a fixed key order and a trailing newline`() {
        val expected = """
            {
              "schemaVersion": 1,
              "commit": "0123456789abcdef0123456789abcdef01234567",
              "mode": "gradle",
              "modules": [
                {
                  "path": ":orders:workflows",
                  "manifest": "modules/_orders_workflows.json"
                }
              ],
              "dropped": [
                {
                  "path": ":reports",
                  "reason": "no-temporal-usage"
                }
              ]
            }
        """.trimIndent() + "\n"

        assertEquals(expected, ModelJson.encode(index))
    }

    @Test
    fun `manifests and indexes survive a round trip`() {
        assertEquals(manifest, ModelJson.decodeManifest(ModelJson.encode(manifest)))
        assertEquals(index, ModelJson.decodeIndex(ModelJson.encode(index)))
    }

    @Test
    fun `written files match their schemas`() {
        manifestSchema.assertValid(ModelJson.encode(manifest))
        val degraded = manifest.copy(path = ":", mode = ModelMode.DEGRADED, projectDeps = emptyList())
        manifestSchema.assertValid(ModelJson.encode(degraded))
        indexSchema.assertValid(ModelJson.encode(index))
        indexSchema.assertValid(ModelJson.encode(index.copy(modules = emptyList(), dropped = emptyList())))
    }

    @Test
    fun `the schema resources are the ones the module publishes`() {
        assertContains(ModelSchemas.manifestText(), "urn:durable-atlas:schema:module-manifest:1")
        assertContains(ModelSchemas.indexText(), "urn:durable-atlas:schema:model-index:1")
    }

    @Test
    fun `the manifest schema rejects what the extractor must never write`() {
        val changes = listOf(
            "\"orders/workflows/src/main/kotlin\"" to "\"/scratch/orders/src/main/kotlin\"",
            commit to "abc123",
            "\"gradle\"" to "\"guessed\"",
            "\":orders:api\"" to "\"orders-api\"",
            "\"schemaVersion\": 1" to "\"schemaVersion\": 2",
            "\"mode\"" to "\"extra\": true,\n  \"mode\"",
        )

        for ((valid, invalid) in changes) {
            val text = ModelJson.encode(manifest)
            assertContains(text, valid)
            assertTrue(manifestSchema.violations(text.replace(valid, invalid)).isNotEmpty(), invalid)
        }
    }

    @Test
    fun `the index schema rejects unknown reasons and stray manifest locations`() {
        val text = ModelJson.encode(index)

        assertTrue(indexSchema.violations(text.replace("no-temporal-usage", "because")).isNotEmpty())
        val stray = text.replace("modules/_orders_workflows.json", "../elsewhere.json")
        assertTrue(indexSchema.violations(stray).isNotEmpty())
    }

    @Test
    fun `other schema versions and malformed text are refused`() {
        val future = ModelJson.encode(manifest).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")

        val refused = assertFailsWith<ModelFormatException> { ModelJson.decodeManifest(future) }
        assertContains(refused.message!!, "schemaVersion 2")
        assertFailsWith<ModelFormatException> { ModelJson.decodeManifest("{}") }
        assertFailsWith<ModelFormatException> { ModelJson.decodeIndex("not json") }
        assertFailsWith<ModelFormatException> {
            ModelJson.decodeIndex(ModelJson.encode(index).replace("\"schemaVersion\": 1", "\"schemaVersion\": 7"))
        }
    }
}
