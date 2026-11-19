package io.github.iw.durableatlas.modelextractor

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelLayoutTest {
    @Test
    fun `a model lives in a directory named after its commit`() {
        assertEquals(Path.of("/out/abc"), ModelLayout.modelDirectory(Path.of("/out"), "abc"))
    }

    @Test
    fun `manifest names replace the separators of the module path`() {
        assertEquals(
            mapOf(
                ":" to "modules/_.json",
                ":orders" to "modules/_orders.json",
                ":orders:workflows" to "modules/_orders_workflows.json",
            ),
            ModelLayout.manifestNames(listOf(":orders:workflows", ":", ":orders")),
        )
    }

    @Test
    fun `module paths that collapse to one name are told apart the same way every time`() {
        val expected = mapOf(
            ":a:b" to "modules/_a_b.json",
            ":a_b" to "modules/_a_b-2.json",
        )

        assertEquals(expected, ModelLayout.manifestNames(listOf(":a_b", ":a:b")))
        assertEquals(expected, ModelLayout.manifestNames(listOf(":a:b", ":a_b")))
    }
}
