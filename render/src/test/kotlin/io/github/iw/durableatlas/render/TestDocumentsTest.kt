package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.ir.validate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The documents the other tests draw must be real workflow IR and must cover the whole notation. */
class TestDocumentsTest {
    private val documents = TestDocuments.all.map(TestDocuments::load)

    @Test
    fun `every document matches the schema and is consistent`() {
        val schema = JsonSchemaChecker.forWorkflowIr()
        for (name in TestDocuments.all) {
            schema.assertValid(TestDocuments.text(name))
            assertEquals(emptyList(), TestDocuments.load(name).validate(), name)
        }
    }

    @Test
    fun `every kind of node, edge and entry point is drawn somewhere`() {
        assertEquals(NodeKind.entries.toSet(), documents.flatMap { it.nodes }.map { it.kind }.toSet())
        assertEquals(EdgeKind.entries.toSet(), documents.flatMap { it.edges }.map { it.kind }.toSet())
        assertEquals(EntryPointKind.entries.toSet(), documents.flatMap { it.entryPoints }.map { it.kind }.toSet())
        assertEquals(ModelMode.entries.toSet(), documents.map { it.modelMode }.toSet())
    }
}
