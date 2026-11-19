package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrFormatException
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import io.github.iw.durableatlas.render.mermaid.MermaidFormat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiagramDirectoryTest {
    @TempDir
    lateinit var temp: Path

    private val out: Path
        get() = temp.resolve("out")

    private val mermaidOnly = DirectoryOptions(formats = listOf(MermaidFormat))

    @Test
    fun `a directory of documents becomes a page per workflow and an index`() {
        val summary = DiagramDirectory.render(TestDocuments.directory, out, mermaidOnly)

        assertEquals(listOf("FulfilmentWorkflow.md", "InvoiceWorkflow.md", "OrderWorkflow.md", "index.md", "odd-workflow.md"), written())
        for (file in written()) {
            val text = read(file)
            assertFalse('\r' in text, file)
            assertTrue(text.endsWith("\n") && !text.endsWith("\n\n"), file)
            MermaidLint.diagramsIn(text).forEach(MermaidLint::check)
            assertEquals(if (file == "index.md") 0 else 1, MermaidLint.diagramsIn(text).size, file)
            GoldenFiles.assertMatches("site/$file", text)
        }

        assertEquals(out, summary.outDir)
        assertEquals("index.md", summary.index)
        assertEquals(emptyList(), summary.skipped)
        assertEquals(
            listOf(
                RenderedDiagram(
                    workflowType = "FulfilmentWorkflow",
                    implementation = "com.example.fulfilment.FulfilmentWorkflowImpl",
                    modelMode = ModelMode.GRADLE,
                    irFile = "FulfilmentWorkflow.ir.json",
                    files = listOf(DiagramLink("mermaid", "FulfilmentWorkflow.md")),
                    nodes = 34,
                    unknown = 2,
                    collapsedRegions = 0,
                ),
                RenderedDiagram(
                    workflowType = "InvoiceWorkflow",
                    implementation = "com.example.billing.InvoiceWorkflowImpl",
                    modelMode = ModelMode.DEGRADED,
                    irFile = "InvoiceWorkflow.ir.json",
                    files = listOf(DiagramLink("mermaid", "InvoiceWorkflow.md")),
                    nodes = 4,
                    unknown = 1,
                    collapsedRegions = 0,
                ),
            ),
            summary.diagrams.take(2),
        )
        assertEquals(
            listOf("FulfilmentWorkflow", "InvoiceWorkflow", "Odd|Workflow<T> #1 *beta* `v2`", "OrderWorkflow"),
            summary.diagrams.map { it.workflowType },
        )
    }

    @Test
    fun `rendering twice gives the same bytes`() {
        DiagramDirectory.render(TestDocuments.directory, out)
        val first = written().associateWith(::read)
        DiagramDirectory.render(TestDocuments.directory, out)
        assertEquals(first, written().associateWith(::read))
    }

    @Test
    fun `child workflows link to the page of their type, or say that there is none`() {
        DiagramDirectory.render(TestDocuments.directory, out)
        assertContains(read("OrderWorkflow.md"), "## Child workflows\n\n- `InvoiceWorkflow`: [InvoiceWorkflow.md](InvoiceWorkflow.md)\n")
        assertContains(read("FulfilmentWorkflow.md"), "## Child workflows\n\n- `ShippingWorkflow`: no diagram\n")
    }

    @Test
    fun `the caller can name the pages, and same-named workflow types are all linked`() {
        val ir = temp.resolve("ir")
        Files.createDirectories(ir)
        copy(TestDocuments.ORDER, ir.resolve("a.ir.json"))
        copy(TestDocuments.INVOICE, ir.resolve("b.ir.json"))
        copy(TestDocuments.INVOICE, ir.resolve("c.json"))
        val names = DiagramFileNames { irFile, document -> "${document.workflowType} (${irFile.substringBefore('.')})" }

        val summary = DiagramDirectory.render(ir, out, mermaidOnly.copy(fileNames = names))

        assertEquals(
            listOf("InvoiceWorkflow (b).md", "InvoiceWorkflow (c).md", "OrderWorkflow (a).md", "index.md"),
            written(),
        )
        assertEquals(listOf("b.ir.json", "c.json", "a.ir.json"), summary.diagrams.map { it.irFile })
        assertContains(
            read("OrderWorkflow (a).md"),
            "- `InvoiceWorkflow`: [InvoiceWorkflow (b).md](InvoiceWorkflow%20%28b%29.md), " +
                "[InvoiceWorkflow (c).md](InvoiceWorkflow%20%28c%29.md)\n",
        )
        assertContains(read("index.md"), "| [mermaid](InvoiceWorkflow%20%28c%29.md) |\n")
    }

    @Test
    fun `other JSON files next to the documents are skipped and reported`() {
        val ir = temp.resolve("ir")
        Files.createDirectories(ir.resolve("nested"))
        copy(TestDocuments.INVOICE, ir.resolve("InvoiceWorkflow.ir.json"))
        copy(TestDocuments.ORDER, ir.resolve("nested").resolve("OrderWorkflow.ir.json"))
        Files.writeString(ir.resolve("inventory.json"), "{\"schemaVersion\": 1, \"workflows\": []}\n")
        Files.writeString(ir.resolve("list.json"), "[1, 2]\n")
        Files.writeString(ir.resolve("broken.json"), "{ not json\n")
        Files.writeString(ir.resolve("notes.txt"), "not a document\n")

        val summary = DiagramDirectory.render(ir, out, mermaidOnly)

        assertEquals(listOf("broken.json", "inventory.json", "list.json"), summary.skipped)
        assertEquals(listOf("InvoiceWorkflow.md", "index.md"), written())
    }

    @Test
    fun `a broken document fails with its file name and nothing is written`() {
        val ir = temp.resolve("ir")
        Files.createDirectories(ir)
        copy(TestDocuments.INVOICE, ir.resolve("good.ir.json"))
        Files.writeString(ir.resolve("bad.ir.json"), TestDocuments.text(TestDocuments.INVOICE).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"))
        val unreadable = assertFailsWith<IrFormatException> { DiagramDirectory.render(ir, out) }
        assertContains(unreadable.message.orEmpty(), "bad.ir.json")

        Files.writeString(ir.resolve("bad.ir.json"), TestDocuments.text(TestDocuments.INVOICE).replace("\"to\": \"n3\"", "\"to\": \"n7\""))
        val inconsistent = assertFailsWith<IllegalArgumentException> { DiagramDirectory.render(ir, out) }
        assertContains(inconsistent.message.orEmpty(), "bad.ir.json")
        assertContains(inconsistent.message.orEmpty(), "n7")
        assertFalse(Files.exists(out))
    }

    @Test
    fun `page names must be distinct plain file names other than the index`() {
        fun failure(names: DiagramFileNames): String =
            assertFailsWith<IllegalArgumentException> {
                DiagramDirectory.render(TestDocuments.directory, out, DirectoryOptions(fileNames = names))
            }.message.orEmpty()

        assertContains(failure { _, _ -> "same" }, "same from FulfilmentWorkflow.ir.json, InvoiceWorkflow.ir.json")
        assertContains(failure { irFile, _ -> if (irFile.startsWith("Order")) "Index" else irFile }, "name of the index")
        assertContains(failure { irFile, _ -> "../$irFile" }, "not a plain file name")
        assertContains(failure { irFile, _ -> if (irFile.startsWith("odd")) "ORDERWORKFLOW.IR.JSON" else irFile.uppercase() }, "same files")
        assertFalse(Files.exists(out))
    }

    @Test
    fun `an empty directory gives an index without workflows`() {
        val ir = temp.resolve("ir")
        Files.createDirectories(ir)
        val summary = DiagramDirectory.render(ir, out)
        assertEquals(emptyList(), summary.diagrams)
        assertTrue(read("index.md").startsWith("# Workflow diagrams\n\n0 workflows.\n"))
    }

    @Test
    fun `the node limit is passed on`() {
        val summary = DiagramDirectory.render(TestDocuments.directory, out, DirectoryOptions(maxNodes = 10))
        assertEquals(listOf(2, 0, 2, 1), summary.diagrams.map { it.collapsedRegions })
        assertContains(read("OrderWorkflow.md"), "> **Note:** This workflow has 23 nodes, more than the limit of 10")
    }

    @Test
    fun `every format writes its own file per workflow and the one index links them all`() {
        val text = object : DiagramFormat {
            override val name = "text"
            override val extension = "txt"

            override fun prepare(document: IrDocument, options: PageOptions): PreparedDiagram = object : PreparedDiagram {
                override val limit = MermaidFormat.prepare(document, options).limit

                override fun render(overlay: DiagramOverlay): String =
                    "${document.workflowType} calls ${options.diagramFiles.keys.sorted()} from ${options.indexFile}\n"
            }

            override fun indexSection(): String = "## Text notation\n\nNone.\n"
        }

        val summary = DiagramDirectory.render(TestDocuments.directory, out, DirectoryOptions(formats = listOf(text, MermaidFormat)))

        assertEquals(9, written().size)
        assertEquals(
            listOf(DiagramLink("text", "InvoiceWorkflow.txt"), DiagramLink("mermaid", "InvoiceWorkflow.md")),
            summary.diagrams[1].files,
        )
        assertTrue(read("InvoiceWorkflow.txt").startsWith("InvoiceWorkflow calls [FulfilmentWorkflow, InvoiceWorkflow, "))
        val index = read("index.md")
        assertContains(index, "| [text](InvoiceWorkflow.txt) · [mermaid](InvoiceWorkflow.md) |\n")
        assertTrue(index.indexOf("## Text notation") < index.indexOf("## Mermaid notation"))
        assertEquals(read("InvoiceWorkflow.md"), MermaidFormat.prepare(
            TestDocuments.load(TestDocuments.INVOICE),
            PageOptions(diagramFiles = mapOf("InvoiceWorkflow" to listOf("InvoiceWorkflow.md")), indexFile = "index.md"),
        ).render())
    }

    @Test
    fun `by default every workflow is written as SVG and as Mermaid and the index links both`() {
        val summary = DiagramDirectory.render(TestDocuments.directory, out)

        assertEquals(
            listOf(
                "FulfilmentWorkflow.md", "FulfilmentWorkflow.svg", "InvoiceWorkflow.md", "InvoiceWorkflow.svg",
                "OrderWorkflow.md", "OrderWorkflow.svg", "index.md", "odd-workflow.md", "odd-workflow.svg",
            ),
            written(),
        )
        assertEquals(
            listOf(DiagramLink("svg", "InvoiceWorkflow.svg"), DiagramLink("mermaid", "InvoiceWorkflow.md")),
            summary.diagrams[1].files,
        )
        assertContains(read("index.md"), "| [svg](InvoiceWorkflow.svg) · [mermaid](InvoiceWorkflow.md) |\n")
        assertTrue(read("InvoiceWorkflow.svg").startsWith("<svg "))
        // The Mermaid pages are the same whatever is written next to them.
        for (file in written().filter { it.endsWith(".md") && it != "index.md" }) GoldenFiles.assertMatches("site/$file", read(file))
    }

    @Test
    fun `formats are found by name and must not share an extension`() {
        assertSame(MermaidFormat, DiagramFormats.named("mermaid"))
        assertSame(DiagramFormats.SVG, DiagramFormats.named("svg"))
        assertEquals(listOf("svg", "mermaid"), DiagramFormats.all.map { it.name })
        assertNull(DiagramFormats.named("dot"))
        assertNull(DiagramFormats.named("html"))
        assertEquals(listOf("mermaid"), DiagramFormats.all.map { it.name }.filter { it == "mermaid" })
        assertFailsWith<IllegalArgumentException> { DirectoryOptions(formats = emptyList()) }
        assertFailsWith<IllegalArgumentException> { DirectoryOptions(formats = listOf(MermaidFormat, MermaidFormat)) }
    }

    private fun written(): List<String> = Files.list(out).use { stream -> stream.map { it.name }.sorted().toList() }

    private fun read(file: String): String = Files.readAllBytes(out.resolve(file)).toString(Charsets.UTF_8)

    private fun copy(name: String, target: Path) {
        Files.writeString(target, TestDocuments.text(name))
    }
}
