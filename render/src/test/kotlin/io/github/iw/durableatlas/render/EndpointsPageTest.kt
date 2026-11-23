package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.testing.GoldenFiles
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.render.endpoints.MarkdownAnchors
import io.github.iw.durableatlas.render.mermaid.MermaidFormat
import io.github.iw.durableatlas.render.svg.SvgFormat
import io.github.iw.durableatlas.render.svg.SvgOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The endpoints page and the OpenAPI documents drawn from the document and the inventory the
 * analyser's tests expect of the activities of `Paying`, whose requests send and read models of
 * both libraries, take parameters, and call a client.
 */
class EndpointsPageTest {
    @TempDir
    lateinit var temp: Path

    private val golden: Path = Path.of("..", "analyser", "src", "test", "resources", "golden")

    private val ir: Path by lazy {
        val ir = Files.createDirectories(temp.resolve("ir"))
        Files.copy(golden.resolve("ir/payloads/Paying.ir.json"), ir.resolve("Paying.ir.json"))
        Files.copy(golden.resolve("inventory/payloads/inventory.json"), ir.resolve("inventory.json"))
        ir
    }

    private val every: List<DiagramFormat> = DiagramFormats.configured(DiagramFormats.all, SvgOptions(), html = true)

    private fun render(name: String, formats: List<DiagramFormat> = every, requests: Boolean = true): RenderSummary =
        DiagramDirectory.render(ir, temp.resolve(name), DirectoryOptions(formats = formats, requests = requests))

    private fun read(name: String, file: String): String = Files.readString(temp.resolve(name).resolve(file))

    @Test
    fun `the page in HTML and in Markdown and an OpenAPI document for each service are the golden ones`() {
        val summary = render("out")
        val endpoints = summary.endpoints!!
        assertEquals(listOf("endpoints/index.html", "endpoints/index.md"), endpoints.pages)
        assertEquals(listOf("endpoints/billing.openapi.json", "endpoints/ServiceClient.openapi.json"), endpoints.documents)
        // Two activities send the same operation, which is listed once.
        assertEquals(listOf(2, 15, 17), listOf(endpoints.services, endpoints.operations, endpoints.models))
        // Nothing else is written there.
        assertEquals(
            (endpoints.pages + endpoints.documents).map { it.substringAfter('/') }.sorted(),
            temp.resolve("out/endpoints").listDirectoryEntries().map { it.name }.sorted(),
        )
        for (file in endpoints.pages + endpoints.documents) GoldenFiles.assertMatches("endpoints/payloads/${file.substringAfter('/')}", read("out", file))
        // The index links the pages and the documents.
        assertContains(read("out", IndexPage.FILE), "## Endpoints\n\nThe activities call 15 operations of 2 services")
        assertContains(read("out", IndexPage.FILE), "[HTML](endpoints/index.html) · [Markdown](endpoints/index.md)")
    }

    @Test
    fun `each OpenAPI document is valid OpenAPI 3_1 with every reference resolved, and the check finds one that is not`() {
        val summary = render("out")
        for (file in summary.endpoints!!.documents) {
            val document = read("out", file)
            OPENAPI.assertValid(document)
            // The schema does not follow references: each must name an entry of the document's own components.
            val references = Regex("\"\\\$ref\": \"#/components/schemas/([^\"]+)\"").findAll(document).map { it.groupValues[1] }.toSet()
            val schemas = Json.parseToJsonElement(document).jsonObject["components"]?.jsonObject?.get("schemas")?.jsonObject?.keys.orEmpty()
            assertTrue(references.isNotEmpty(), file)
            assertEquals(emptySet(), references - schemas, file)
        }
        val billing = read("out", "endpoints/billing.openapi.json")
        assertTrue(OPENAPI.violations(billing.replace("\"openapi\": \"3.1.0\"", "\"openapi\": 3")).isNotEmpty())
        assertTrue(OPENAPI.violations(billing.replace("\"in\": \"path\"", "\"in\": \"body\"")).isNotEmpty())
    }

    @Test
    fun `the HTML page holds all it shows, with no script, nothing to fetch, no hover, and links only within the output`() {
        render("out")
        val page = read("out", "endpoints/index.html")
        for (forbidden in listOf("<script", "<link", "src=", "@import", "url(", ":hover", "http-equiv")) assertFalse(forbidden in page, forbidden)
        val ids = Regex(" id=\"([^\"]+)\"").findAll(page).map { it.groupValues[1] }.toList()
        assertEquals(ids.distinct(), ids, "ids are unique")
        for (target in Regex("href=\"([^\"]+)\"").findAll(page).map { it.groupValues[1] }.toSet()) {
            when {
                target.startsWith("#") -> assertTrue(target.drop(1) in ids, target)
                else -> {
                    assertFalse(":" in target, target)
                    assertTrue(temp.resolve("out/endpoints").resolve(target.substringBefore('#')).normalize().exists(), target)
                }
            }
        }
        // Each workflow links to its HTML page, where the links of its cards work.
        assertContains(page, "href=\"../Paying.html\"")
    }

    @Test
    fun `the links of the Markdown page go to its headings, as GitHub and GitLab name them, and to files of the output`() {
        render("out")
        val page = read("out", "endpoints/index.md")
        val anchors = HashMap<String, Int>()
        val headings = page.lines().filter { it.startsWith("#") }.map { it.trimStart('#').trim() }.map { heading ->
            val github = MarkdownAnchors.github(heading)
            assertEquals(github, MarkdownAnchors.gitlab(heading), heading)
            val seen = anchors.merge(github, 1, Int::plus)!!
            if (seen == 1) github else "$github-${seen - 1}"
        }
        assertEquals(headings.distinct(), headings, "anchors are unique")
        val links = Regex("\\]\\(([^)]+)\\)").findAll(page).map { it.groupValues[1] }.toSet()
        assertTrue(links.any { it.startsWith("#op-") || it.startsWith("#post-") })
        for (target in links) {
            if (target.startsWith("#")) {
                assertTrue(target.drop(1) in headings, target)
            } else {
                assertTrue(temp.resolve("out/endpoints").resolve(target.substringBefore('#')).normalize().exists(), target)
            }
        }
        assertContains(page, "](../Paying.md)")
    }

    @Test
    fun `the cards of activities that make requests link to their operations, and the Markdown page of a workflow to the endpoints page`() {
        render("out")
        val svg = read("out", "Paying.svg")
        assertContains(svg, "<a xlink:href=\"endpoints/index.html#op-billing-post-charges\">")
        assertContains(svg, "<a xlink:href=\"endpoints/index.html#op-billing-put-bookings\">")
        val markdown = read("out", "Paying.md")
        assertContains(markdown, "| `Jackson` | [`POST {billing.url}/charges`](endpoints/index.md#post-billing-charges) |")
    }

    @Test
    fun `only the page of the formats drawn is written, and none without requests`() {
        val svg = render("svg", formats = listOf(SvgFormat())).endpoints!!
        assertEquals(listOf("endpoints/index.html"), svg.pages)
        val mermaid = render("mermaid", formats = listOf(MermaidFormat)).endpoints!!
        assertEquals(listOf("endpoints/index.md"), mermaid.pages)
        assertEquals(svg.documents, mermaid.documents)
        // A page without a link back to diagrams of another format: the Mermaid page links the Markdown pages.
        assertContains(read("mermaid", "endpoints/index.md"), "](../Paying.md)")

        val without = render("off", requests = false)
        assertNull(without.endpoints)
        assertFalse(temp.resolve("off/endpoints").exists())
        assertFalse("Endpoints" in read("off", IndexPage.FILE))
        assertFalse("endpoints/" in read("off", "Paying.svg"))
    }

    @Test
    fun `GitHub and GitLab make the same anchor of a heading unless it gives hyphens in a row, which the page never writes`() {
        assertEquals("post-billing-v1charges", MarkdownAnchors.github("POST billing /v1/charges"))
        assertEquals("post-billing--charges", MarkdownAnchors.github("POST billing / charges"))
        assertEquals("post-billing-charges", MarkdownAnchors.gitlab("POST billing / charges"))
        assertEquals("model-billingchargerequest", MarkdownAnchors.github("Model billing.ChargeRequest"))
        val headings = MarkdownAnchors.Headings()
        assertEquals("POST orders" to "post-orders", headings.take("POST orders /"))
        assertEquals("POST orders 2" to "post-orders-2", headings.take("POST orders /"))
        assertEquals("FN pricing-quote" to "fn-pricing-quote", headings.take("FN pricing-quote"))
    }

    private companion object {
        private const val BASE = "https://spec.openapis.org/oas/3.1/"

        /** The JSON Schema of OpenAPI 3.1 that also checks the schemas of a document, read from the files of the library that has it. */
        val OPENAPI: JsonSchemaChecker = JsonSchemaChecker.forSchemaAt("${BASE}schema-base/2022-10-07") { iri ->
            iri.substringBefore('#').takeIf { it.startsWith(BASE) }?.let { id ->
                EndpointsPageTest::class.java.classLoader.getResourceAsStream("spec.openapis.org/oas/3.1/" + id.removePrefix(BASE))?.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }
    }
}
