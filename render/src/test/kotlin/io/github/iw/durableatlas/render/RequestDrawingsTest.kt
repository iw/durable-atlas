package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.Payload
import io.github.iw.durableatlas.ir.PayloadFormat
import io.github.iw.durableatlas.ir.PayloadKind
import io.github.iw.durableatlas.ir.Request
import io.github.iw.durableatlas.ir.RequestKind
import io.github.iw.durableatlas.ir.SourceLocation
import io.github.iw.durableatlas.ir.withoutRequests
import io.github.iw.durableatlas.render.mermaid.MermaidFormat
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import io.github.iw.durableatlas.render.svg.SvgDiagrams
import io.github.iw.durableatlas.render.svg.SvgDocuments
import io.github.iw.durableatlas.render.svg.SvgDom
import io.github.iw.durableatlas.render.svg.SvgFormat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Diagrams of activities that make requests of other services: the analyser's document of
 * workflow `Requested`, whose activities make one request, two, or none, and versions of it with
 * more and longer requests.
 */
class RequestDrawingsTest {
    @TempDir
    lateinit var temp: Path

    private val requested: IrDocument = SvgDocuments.all().getValue("analyser/requests/Requested")

    private fun page(document: IrDocument): String = MermaidFormat.prepare(document, PageOptions()).render()

    private fun svg(document: IrDocument): String = SvgFormat().prepare(document, PageOptions()).render()

    private fun node(document: IrDocument, name: String) = document.nodes.single { it.name == name }

    /** The texts of the card of the node named [name], in order. */
    private fun card(dom: SvgDom, document: IrDocument, name: String): List<String> =
        dom.nodes.single { it.getAttribute("id") == node(document, name).id }.let { element ->
            val texts = element.getElementsByTagName("text")
            (0 until texts.length).map { texts.item(it).textContent }
        }

    /** The tooltip of the card of the node named [name]. */
    private fun tooltip(dom: SvgDom, document: IrDocument, name: String): String =
        dom.nodes.single { it.getAttribute("id") == node(document, name).id }.getElementsByTagName("title").item(0).textContent

    /** [document] with [requests] made by its activity [name]. */
    private fun withRequests(document: IrDocument, name: String, requests: List<Request>): IrDocument =
        document.copy(nodes = document.nodes.map { if (it.name == name) it.copy(requests = requests) else it })

    private fun http(target: String, line: Int, service: String = "api") =
        Request(kind = RequestKind.HTTP, service = service, method = "GET", target = target, loc = SourceLocation("app/src/main/kotlin/requests/More.kt", line))

    @Test
    fun `a card shows the first request briefly under the name of the activity, and how many more there are`() {
        val dom = SvgDom(svg(requested))
        // The method and the service, or FN and the operation of a client.
        assertEquals(listOf("ACTIVITY", "Helper", "POST api"), card(dom, requested, "Helper"))
        assertEquals(listOf("ACTIVITY", "Announce", "POST mail +1"), card(dom, requested, "Announce"))
        assertEquals(listOf("ACTIVITY", "Named", "FN named-fn"), card(dom, requested, "Named"))
        // An activity without requests has its card as before.
        assertEquals(listOf("ACTIVITY", "Call"), card(dom, requested, "Call"))
        SvgDiagrams.check(SvgDiagrams.positionedGraph(svg(requested)), requested)
    }

    @Test
    fun `a long request is cut as names are, and the count of more stays whole`() {
        val long = http("https://api.example.com/" + "segment/".repeat(20) + "{id}", 3, service = "a-service-whose-name-is-longer-than-a-card")
        val document = withRequests(requested, "Helper", listOf(long) + (4..7).map { http("https://api.example.com/$it", it) })
        val dom = SvgDom(svg(document))
        val line = card(dom, document, "Helper")[2]
        assertEquals("GET a-service-whose… +4", line)
        // The tooltip has them all, whole.
        assertContains(tooltip(dom, document, "Helper"), "\nGET ${long.target}\nGET https://api.example.com/4\n")
    }

    @Test
    fun `the tooltip of a card lists every request in full with its host, what it sends and reads, its response type and the class that makes it`() {
        val dom = SvgDom(svg(requested))
        assertEquals(
            "ACTIVITY: Announce\n" +
                "POST https://mail.example.com/send · sends text · answer not read · response String · in MailNotices\n" +
                "ServiceClient · text-send · answer not read · in TextNotices",
            tooltip(dom, requested, "Announce"),
        )
        val hosted = Request(
            kind = RequestKind.HTTP,
            method = "PUT",
            target = "{billing.url}/v1/charges",
            keys = listOf("billing.url"),
            host = "http://billing.example.com",
            sends = Payload(PayloadKind.TYPE, "com.example.Charge", format = PayloadFormat.JACKSON),
            response = "String",
            receives = Payload(PayloadKind.TYPE, "kotlin.collections.List<com.example.Receipt>", format = PayloadFormat.JACKSON),
            loc = SourceLocation("app/src/main/kotlin/requests/Billing.kt", 9),
        )
        val document = withRequests(requested, "Call", listOf(hosted))
        assertEquals(
            "ACTIVITY: Call\nPUT {billing.url}/v1/charges · host http://billing.example.com · sends Charge · reads List<Receipt> · response String",
            tooltip(SvgDom(svg(document)), document, "Call"),
        )
    }

    @Test
    fun `the page of a workflow has a table of the requests of its activities, and the Mermaid node the first request`() {
        val text = page(requested)
        assertContains(
            text,
            "## Requests\n\n| Activity | Request | Host | Response | Source |\n|---|---|---|---|---|\n" +
                "| `Announce` | `POST https://mail.example.com/send` |  | `String` | `app/src/main/kotlin/requests/Requested.kt:54 (MailNotices)` |\n" +
                "| `Announce` | `ServiceClient · text-send` |  |  | `app/src/main/kotlin/requests/Requested.kt:60 (TextNotices)` |\n" +
                "| `Helper` | `POST https://api.example.com/orders/{id}` |  | `String` | `app/src/main/kotlin/requests/HttpCalls.kt:80` |\n" +
                "| `Named` | `ServiceClient · named-fn` |  | `List<Item>` | `app/src/main/kotlin/requests/ClientCalls.kt:77` |\n",
        )
        val mermaid = MermaidRenderer.render(requested)
        MermaidLint.check(mermaid)
        // Mermaid's colon is written as an entity, as in any other text of a node.
        assertContains(mermaid, "[\"Helper<br/>POST api\"]")
        assertContains(mermaid, "[\"Announce<br/>POST mail +1\"]")
        assertContains(mermaid, "[\"Call\"]")
    }

    @Test
    fun `a document without requests is drawn as one whose requests were taken away, and says nothing of them`() {
        val without = requested.withoutRequests()
        assertTrue(without.nodes.none { it.requests.isNotEmpty() })
        val text = page(without)
        assertFalse("## Requests" in text)
        assertFalse("ServiceClient" in text)
        assertFalse("ServiceClient" in svg(without))
        // The cards keep their names, and nothing else changes: the same nodes and edges.
        val drawn = SvgDom(svg(requested))
        val plain = SvgDom(svg(without))
        assertEquals(plain.nodes.map { it.getAttribute("id") }.sorted(), drawn.nodes.map { it.getAttribute("id") }.sorted())
        assertEquals(plain.edges.size, drawn.edges.size)
        assertEquals(listOf("ACTIVITY", "Helper"), card(plain, without, "Helper"))
        // Every other document of the tests has none, and draws as it did.
        val others = SvgDocuments.all().filterKeys { it != "analyser/requests/Requested" && it != "analyser/payloads/Paying" }
        assertTrue(others.values.all { document -> document.nodes.none { it.requests.isNotEmpty() } })
    }

    @Test
    fun `without requests a directory is drawn as the documents would be without them`() {
        val ir = Files.createDirectories(temp.resolve("ir"))
        Files.writeString(ir.resolve("Requested.ir.json"), IrJson.encode(requested))
        val plainIr = Files.createDirectories(temp.resolve("plain-ir"))
        Files.writeString(plainIr.resolve("Requested.ir.json"), IrJson.encode(requested.withoutRequests()))

        val formats = listOf(MermaidFormat, SvgFormat())
        DiagramDirectory.render(ir, temp.resolve("off"), DirectoryOptions(formats = formats, requests = false))
        DiagramDirectory.render(plainIr, temp.resolve("plain"), DirectoryOptions(formats = formats))
        DiagramDirectory.render(ir, temp.resolve("on"), DirectoryOptions(formats = formats))

        val names = temp.resolve("plain").listDirectoryEntries().map { it.name }.sorted()
        assertEquals(names, temp.resolve("off").listDirectoryEntries().map { it.name }.sorted())
        for (name in names) {
            assertEquals(Files.readString(temp.resolve("plain").resolve(name)), Files.readString(temp.resolve("off").resolve(name)), name)
        }
        assertContains(Files.readString(temp.resolve("on").resolve("Requested.md")), "## Requests")
        assertTrue(node(requested, "Helper").kind == NodeKind.ACTIVITY)
        // Drawn with them, a card links to its one operation on the endpoints page, or to the entry of its activity, which lists several.
        val on = SvgDom(Files.readString(temp.resolve("on").resolve("Requested.svg")))
        fun link(name: String) = on.nodes.single { it.getAttribute("id") == node(requested, name).id }.parentNode.let { it as org.w3c.dom.Element }
            .takeIf { it.localName == "a" }?.getAttributeNS("http://www.w3.org/1999/xlink", "href")
        assertEquals("endpoints/index.html#op-api-post-orders-id", link("Helper"))
        assertEquals("endpoints/index.html#op-serviceclient-named-fn", link("Named"))
        assertEquals("endpoints/index.html#activity-announce", link("Announce"))
        assertEquals(null, link("Call"))
        assertContains(Files.readString(temp.resolve("on").resolve("endpoints/index.html")), "id=\"activity-announce\"")
    }
}
