package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.render.Simplification
import io.github.iw.durableatlas.render.layout.RequestTexts
import java.time.Duration

/** Reads what an SVG diagram says about itself. */
internal object SvgDiagrams {
    /** Namespace of the elements a diagram describes itself with, inside its `metadata` element. */
    const val NAMESPACE = "urn:durable-atlas:svg-diagram:1"

    /** Attributes of a diagram that is drawn more simply than asked for: how, and the time the drawing as asked was given. */
    const val SIMPLIFIED = "simplified"
    const val TIME_LIMIT = "timeLimit"

    private val layout = Regex("<atlas:layout>(.*?)</atlas:layout>", RegexOption.DOT_MATCHES_ALL)
    private val description = Regex("<atlas:diagram\\s[^>]*>")
    private val simplified = Regex("\\s$SIMPLIFIED=\"([^\"]*)\"")
    private val timeLimit = Regex("\\s$TIME_LIMIT=\"([^\"]*)\"")

    /**
     * How a diagram that [SvgFormat] wrote is drawn more simply than was asked for; null if it
     * is not, which is also the answer for a file that says something this tool does not write.
     *
     * An earlier version wrote `simplified="near"` into a diagram whose rows it had found with
     * only the steps near each row placed, after its search with all steps placed had run out of
     * time. The rows of every diagram are found that way now, so such a file is a diagram drawn
     * as asked, and the answer for it is null.
     */
    fun simplification(svg: String): Simplification? {
        val element = description.find(svg)?.value ?: return null
        val level = simplified.find(element)?.let { Simplification.Level.ofKey(it.groupValues[1]) } ?: return null
        val limit = timeLimit.find(element)?.let { runCatching { Duration.parse(it.groupValues[1]) }.getOrNull() } ?: return null
        return Simplification(level, limit)
    }

    /**
     * The positions recorded in a diagram that [SvgFormat] wrote.
     *
     * @throws IllegalArgumentException if the text holds none
     */
    fun positionedGraph(svg: String): PositionedGraph {
        val match = requireNotNull(layout.find(svg)) { "The SVG file records no layout; it was not written by this tool" }
        return PositionedGraph.decode(unescape(match.groupValues[1]))
    }

    /**
     * Makes sure [graph] is a layout of [document]: everything it places must be in the
     * document, and each card must name the requests its node makes, so that a drawing with
     * requests and one without are not taken for each other.
     */
    fun check(graph: PositionedGraph, document: IrDocument) {
        val nodes = document.nodes.map { it.id }.toSet()
        val entryPoints = document.entryPoints.map { it.id }.toSet()
        val unknown = graph.regions.map { it.id }.filter { it !in nodes } +
            graph.nodes.filter { !it.handler }.map { it.id }.filter { it !in nodes } +
            graph.nodes.filter { it.handler }.flatMap { it.entryPoints + it.id }.filter { it !in entryPoints }
        require(unknown.isEmpty()) {
            "The SVG file was drawn for another document: it places ${unknown.distinct().sorted().joinToString(", ") { "'$it'" }}"
        }
        require(graph.edges.all { edge -> edge.sources.all { it in document.edges.indices } }) {
            "The SVG file was drawn for another document: it has edges this one lacks"
        }
        val byId = document.nodes.associateBy { it.id }
        val requests = graph.nodes.filter { !it.handler && it.requests != byId[it.id]?.let(RequestTexts::tooltip).orEmpty() }
        require(requests.isEmpty()) {
            "The SVG file was drawn with other requests than the document has: at ${requests.joinToString(", ") { "'${it.id}'" }}"
        }
    }

    /** Undoes what [Xml.text] does. */
    private fun unescape(text: String): String =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&#13;", "\r").replace("&amp;", "&")
}
