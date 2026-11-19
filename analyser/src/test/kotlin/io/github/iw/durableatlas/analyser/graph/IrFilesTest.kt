package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrFilesTest {
    private fun document(workflowType: String, implementation: String = "com.example.${workflowType}Impl"): IrDocument =
        IrDocument(
            commit = "0000000000000000000000000000000000000000",
            modelMode = ModelMode.GRADLE,
            workflowType = workflowType,
            interfaceName = implementation.removeSuffix("Impl"),
            implementation = implementation,
            module = ":app",
            entryPoints = listOf(EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, workflowType, "start")),
            nodes = listOf(IrNode("start", NodeKind.START)),
            edges = emptyList(),
        )

    private fun names(vararg documents: IrDocument): List<String> = IrFiles.names(documents.toList())

    @Test
    fun `a document is called after its workflow type`() {
        assertEquals(
            listOf("OrderWorkflow.ir.json", "Shipment.ir.json", "order-v2_final.ir.json"),
            names(document("OrderWorkflow"), document("Shipment"), document("order-v2_final")),
        )
        assertEquals(emptyList(), IrFiles.names(emptyList()))
    }

    @Test
    fun `characters that do not belong in a file name are replaced`() {
        assertEquals(
            listOf("billing_invoice.ir.json", "a_b_c.ir.json", "caf_.ir.json", "_.ir.json", "hidden.ir.json", "_.._up.ir.json"),
            names(
                document("billing/invoice"),
                document("a b:c"),
                document("café"),
                document(""),
                document("..hidden"),
                document("/../up"),
            ),
        )
    }

    @Test
    fun `documents of one workflow type are told apart by the class that implements it`() {
        assertEquals(
            listOf("Order--com.a.OrderImpl.ir.json", "Order--com.b.OrderImpl.ir.json", "Other.ir.json"),
            names(document("Order", "com.a.OrderImpl"), document("Order", "com.b.OrderImpl"), document("Other")),
        )
    }

    @Test
    fun `names that differ only in case, or only in what was replaced, count as the same`() {
        assertEquals(
            listOf("Order--com.a.UpperImpl.ir.json", "order--com.a.LowerImpl.ir.json"),
            names(document("Order", "com.a.UpperImpl"), document("order", "com.a.LowerImpl")),
        )
        assertEquals(
            listOf("a_b--com.a.SlashImpl.ir.json", "a_b--com.a.SpaceImpl.ir.json"),
            names(document("a/b", "com.a.SlashImpl"), document("a b", "com.a.SpaceImpl")),
        )
    }

    @Test
    fun `whatever still clashes is numbered in the order of the documents`() {
        assertEquals(
            listOf("Order--com.a.Impl.ir.json", "Order--com.a.Impl-2.ir.json", "Order--com.a.Impl-3.ir.json"),
            names(document("Order", "com.a.Impl"), document("Order", "com.a.Impl"), document("Order", "com.a.Impl")),
        )
        // A plain name that is already what another document was given moves on as well.
        assertEquals(
            listOf("Order--com.a.Impl.ir.json", "Order--com.b.Impl.ir.json", "Order--com.a.Impl-2.ir.json"),
            names(document("Order", "com.a.Impl"), document("Order", "com.b.Impl"), document("Order--com.a.Impl", "com.c.Impl")),
        )
    }

    @Test
    fun `no two names are the same file on a file system that ignores case`() {
        val documents = listOf("A", "a", "A", "a--com.example.aImpl", "B", "b ", "b_").map { document(it) }
        val names = IrFiles.names(documents)

        assertEquals(documents.size, names.map { it.lowercase() }.toSet().size, names.toString())
        assertTrue(names.all { IrFiles.isIrFile(it) && Regex("[A-Za-z0-9._-]+").matches(it) }, names.toString())
    }

    @Test
    fun `IR files are known by their suffix`() {
        assertTrue(IrFiles.isIrFile("OrderWorkflow.ir.json"))
        assertFalse(IrFiles.isIrFile("inventory.json"))
        assertFalse(IrFiles.isIrFile(".ir.json"))
        assertFalse(IrFiles.isIrFile("OrderWorkflow.ir.json.tmp"))
    }
}
