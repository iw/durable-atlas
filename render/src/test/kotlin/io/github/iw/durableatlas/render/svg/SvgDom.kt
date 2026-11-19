package io.github.iw.durableatlas.render.svg

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** An SVG diagram read back as XML, with the questions the tests ask of it. */
internal class SvgDom(val text: String) {
    val document: Document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(InputSource(StringReader(text)))

    val root: Element = document.documentElement

    /** Every element, in document order. */
    val elements: List<Element> = buildList {
        fun visit(element: Element) {
            add(element)
            element.children().forEach(::visit)
        }
        visit(root)
    }

    /** The groups of cards and pills. */
    val nodes: List<Element> = groups("node")
    val regions: List<Element> = groups("region")
    val edges: List<Element> = groups("edge")
    val edgeLabels: List<Element> = groups("edge-label")
    val legendEntries: List<Element> = groups("legend-entry")

    fun groups(className: String): List<Element> =
        elements.filter { it.localName == "g" && className in it.getAttribute("class").split(' ') }

    fun named(tag: String): List<Element> = elements.filter { it.localName == tag }

    /** The group that is drawn for the node or handler [id]; null if there is none or several. */
    fun group(id: String): Element? = (nodes + regions).singleOrNull { it.getAttribute("id") == id }

    companion object {
        fun Element.children(): List<Element> = buildList {
            var child: Node? = firstChild
            while (child != null) {
                if (child is Element) add(child)
                child = child.nextSibling
            }
        }

        /** The words drawn in a group, one entry per text element. */
        fun Element.texts(): List<String> = children().filter { it.localName == "text" }.map { it.textContent }

        /** The text elements drawn in a group, top to bottom. */
        fun Element.childTexts(): List<Element> = children().filter { it.localName == "text" }

        fun Element.title(): String? = children().firstOrNull { it.localName == "title" }?.textContent

        fun Element.classes(): List<String> = getAttribute("class").split(' ').filter { it.isNotEmpty() }

        /** An attribute that holds a list of ids. */
        fun Element.ids(attribute: String): List<String> =
            getAttribute(attribute).takeIf { it.isNotEmpty() }?.let { Json.decodeFromString(ListSerializer(String.serializer()), it) }.orEmpty()

        /** The box of the first rectangle in a group, which is the outline of what the group draws. */
        fun Element.outline(): Box {
            val rect = children().first { it.localName == "rect" && it.getAttribute("class") != "ring" }
            fun number(name: String) = rect.getAttribute(name).toDouble()
            return Box(number("x"), number("y"), number("width"), number("height"))
        }
    }
}
