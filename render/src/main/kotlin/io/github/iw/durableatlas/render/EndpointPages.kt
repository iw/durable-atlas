package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.Request
import io.github.iw.durableatlas.ir.ServiceNames
import io.github.iw.durableatlas.render.endpoints.EndpointSet
import io.github.iw.durableatlas.render.endpoints.EndpointsHtml
import io.github.iw.durableatlas.render.endpoints.EndpointsMarkdown
import io.github.iw.durableatlas.render.endpoints.InventoryPart
import io.github.iw.durableatlas.render.endpoints.OpenApi
import io.github.iw.durableatlas.render.layout.RequestTexts
import io.github.iw.durableatlas.render.markdown.Markdown
import io.github.iw.durableatlas.render.mermaid.MermaidFormat
import io.github.iw.durableatlas.render.svg.Links
import io.github.iw.durableatlas.render.svg.SvgFormat
import io.github.iw.durableatlas.render.svg.SvgHtmlFormat
import io.github.iw.durableatlas.render.svg.SvgOptions
import java.nio.file.Path

/**
 * Where the cards of a diagram link: to the place on the endpoints page, written with the
 * diagram, of what an activity calls. An activity that makes one request links to its
 * operation; one that makes several, to its entry, which lists them.
 */
class EndpointLinks internal constructor(private val page: String, private val set: EndpointSet, private val anchor: (String) -> String?) {
    /** The link of a node's card, relative to the diagram; `null` for a node that makes no request. */
    fun of(node: IrNode): String? {
        val requests = RequestTexts.of(node)
        val id = when {
            requests.isEmpty() -> return null
            requests.size == 1 -> set.operationOf(requests.single())?.id
            else -> node.name?.let(set::activity)?.id
        } ?: return null
        return anchor(id)?.let(::link)
    }

    /** The link of a request, relative to the diagram, to its operation. */
    fun of(request: Request): String? = set.operationOf(request)?.id?.let(anchor)?.let(::link)

    private fun link(anchor: String): String = "${Links.encodePath(page)}#${Links.encodeSegment(anchor)}"
}

/** What [DiagramDirectory.render] wrote of the endpoints the activities call. */
data class EndpointSummary(
    /** The pages, relative to the output directory: HTML for the SVG diagrams, Markdown for Mermaid. */
    val pages: List<String>,
    /** The OpenAPI documents, one per service, relative to the output directory. */
    val documents: List<String>,
    val services: Int,
    val operations: Int,
    val models: Int,
)

/**
 * The endpoints page of a set of documents in the forms the formats of a run call for, and the
 * OpenAPI document of each service, all in the directory [DIRECTORY] of the output: away from the
 * files of the workflows, whose names come from the workflows and could be any.
 */
internal class EndpointPages private constructor(private val set: EndpointSet) {
    private var markdown: EndpointsMarkdown? = null

    /** The links the diagrams of [format] get: into the HTML page for SVG, into the Markdown page for Mermaid. */
    fun links(format: DiagramFormat): EndpointLinks? =
        when (kindOf(format)) {
            Kind.HTML -> EndpointLinks("$DIRECTORY/$HTML_FILE", set) { it }
            Kind.MARKDOWN -> EndpointLinks("$DIRECTORY/$MARKDOWN_FILE", set) { id -> markdownOf().anchors[id] }
            null -> null
        }

    private fun markdownOf(): EndpointsMarkdown =
        markdown ?: EndpointsMarkdown(set, { null }, null, { _, _ -> null }).also { markdown = it }

    /**
     * Writes the pages that [formats] call for and the OpenAPI documents into [outDir]. A workflow
     * on a page links to its diagram in [diagramFiles], by format and workflow type.
     */
    fun write(outDir: Path, formats: List<DiagramFormat>, diagramFiles: Map<DiagramFormat, Map<String, List<String>>>): EndpointSummary {
        val directory = outDir.resolve(DIRECTORY)
        val kinds = formats.mapNotNull { kindOf(it) }.distinct()
        val svg = formats.filterIsInstance<SvgFormat>().firstOrNull()?.options ?: formats.filterIsInstance<SvgHtmlFormat>().firstOrNull()?.options
        val sourceLink = sourceLink(svg)
        val pages = ArrayList<String>()
        fun fileOf(format: DiagramFormat?, workflow: String): String? = format?.let { diagramFiles[it]?.get(workflow)?.minOrNull() }?.let { "../$it" }
        for (kind in kinds) {
            when (kind) {
                Kind.HTML -> {
                    // A workflow links to its HTML page where one is written, where its links work, else to its SVG file.
                    val pageFormat = formats.firstOrNull { it is SvgHtmlFormat } ?: formats.firstOrNull { it is SvgFormat }
                    val text = EndpointsHtml(set, { fileOf(pageFormat, it) }, "../${IndexPage.FILE}", sourceLink, svg?.embedFont == true).render()
                    DiagramFiles.write(directory.resolve(HTML_FILE), text)
                    pages += "$DIRECTORY/$HTML_FILE"
                }
                Kind.MARKDOWN -> {
                    val format = formats.firstOrNull { kindOf(it) == Kind.MARKDOWN }
                    val text = EndpointsMarkdown(set, { fileOf(format, it) }, "../${IndexPage.FILE}", sourceLink).render()
                    DiagramFiles.write(directory.resolve(MARKDOWN_FILE), text)
                    pages += "$DIRECTORY/$MARKDOWN_FILE"
                }
            }
        }
        val openApi = OpenApi(set, sourceLink)
        val documents = set.services.map { service ->
            DiagramFiles.write(directory.resolve(service.file), openApi.write(service))
            "$DIRECTORY/${service.file}"
        }
        return EndpointSummary(pages, documents, set.services.size, set.operations.size, set.models.size)
    }

    /** The line of the sources a caller links to, where a base URL for them was given and the documents are of one commit. */
    private fun sourceLink(options: SvgOptions?): (String, Int) -> String? {
        val base = options?.sourceBaseUrl?.takeIf { it.isNotBlank() } ?: return { _, _ -> null }
        if ("{commit}" in base && set.commits.size != 1) return { _, _ -> null }
        val root = base.replace("{commit}", set.commits.firstOrNull()?.let(Links::encodeSegment).orEmpty()).trimEnd('/')
        return { file, line -> "$root/${Links.encodePath(file)}#L$line" }
    }

    private enum class Kind { HTML, MARKDOWN }

    companion object {
        /** The directory of the output that holds the pages and the OpenAPI documents. */
        const val DIRECTORY: String = "endpoints"
        const val HTML_FILE: String = "index.html"
        const val MARKDOWN_FILE: String = "index.md"

        private fun kindOf(format: DiagramFormat): Kind? =
            when (format) {
                is SvgFormat, is SvgHtmlFormat -> Kind.HTML
                is MermaidFormat -> Kind.MARKDOWN
                else -> null
            }

        /** The pages of [documents] and of the inventory beside them, or `null` where none makes a request. */
        fun of(documents: List<IrDocument>, inventory: InventoryPart?): EndpointPages? = EndpointSet.of(documents, inventory)?.let(::EndpointPages)

        /**
         * [documents] with each request named after its service, where a document was written
         * before services were named: against every request of the documents and the inventory,
         * as the analysis names them.
         */
        fun named(documents: List<IrDocument>, inventory: InventoryPart?): List<IrDocument> {
            val requests = documents.flatMap { document -> document.nodes.flatMap { it.requests } } + inventory?.activities.orEmpty().flatMap { it.requests }
            if (requests.all { it.service != null }) return documents
            val names = ServiceNames.of(requests)
            return documents.map { document ->
                if (document.nodes.all { node -> node.requests.all { it.service != null } }) {
                    document
                } else {
                    document.copy(nodes = document.nodes.map { node -> node.copy(requests = node.requests.map { it.copy(service = it.service ?: names[it]) }) })
                }
            }
        }

        /** The section of the index that links the pages and the OpenAPI documents. */
        fun indexSection(summary: EndpointSummary): String {
            val pages = summary.pages.joinToString(" · ") { page -> Markdown.link(if (page.endsWith(".html")) "HTML" else "Markdown", page) }
            val services = if (summary.services == 1) "1 service" else "${summary.services} services"
            val operations = if (summary.operations == 1) "1 operation" else "${summary.operations} operations"
            return listOf(
                "## Endpoints",
                "",
                "The activities call $operations of $services: the endpoints page shows them with the models they send and receive " +
                    "($pages), and each service is an OpenAPI 3.1 document in ${Markdown.code(DIRECTORY)}: " +
                    summary.documents.joinToString(" · ") { Markdown.link(it.substringAfterLast('/').removeSuffix(EndpointSet.OPENAPI_SUFFIX), it) } + ".",
            ).joinToString("\n")
        }
    }
}
