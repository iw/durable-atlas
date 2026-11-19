package io.github.iw.durableatlas.overlay.testing

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** An SVG diagram, read back as XML for tests to ask about. */
class SvgFile(val text: String) {
    private val root: Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement

    private val groups: List<Element> = root.getElementsByTagName("g").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    /** The group of the card, pill or region drawn for a node or handler. */
    fun node(id: String): Element = groups.single { it.getAttribute("id") == id }

    val edges: List<Element> get() = groups.filter { " ${it.getAttribute("class")} ".contains(" edge ") }

    fun edge(from: String, to: String, kind: String = "flow"): Element =
        edges.single { it.getAttribute("data-from") == from && it.getAttribute("data-to") == to && it.getAttribute("data-kind") == kind }

    val title: String get() = children(root).first { it.localName == "title" }.textContent

    /** Where everything was placed, as the file records it. */
    val layout: String get() = Regex("<atlas:layout>(.*?)</atlas:layout>", RegexOption.DOT_MATCHES_ALL).find(text)!!.groupValues[1]

    /** Every line of text below the diagram. */
    val notes: List<String>
        get() = groups.single { it.getAttribute("class") == "notes" }.let { group -> children(group).map { it.textContent } }

    companion object {
        fun children(element: Element): List<Element> =
            element.childNodes.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }

        fun Element.classes(): List<String> = getAttribute("class").split(' ').filter { it.isNotEmpty() }

        fun Element.title(): String = children(this).first { it.localName == "title" }.textContent

        /** The text of the badge on a card; `null` if it has none. */
        fun Element.badge(): String? = children(this).firstOrNull { it.getAttribute("class") == "badge" }?.textContent

        val Element.dimmed: Boolean get() = "dimmed" in classes()

        val Element.highlighted: Boolean get() = "highlighted" in classes()
    }
}
