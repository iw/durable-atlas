package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.WaitsFor
import io.github.iw.durableatlas.ir.normalized
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.TestDocuments
import io.github.iw.durableatlas.render.layout.singleLine
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.children
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.classes
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.ids
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.outline
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.texts
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.title
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What every SVG diagram must be, checked for every document there is to draw. */
class SvgStructureTest {
    private class Drawn(val document: IrDocument, val svg: String) {
        val dom = SvgDom(svg)
        val graph = SvgDiagrams.positionedGraph(svg)
    }

    private fun each(check: (Drawn) -> Unit): List<DynamicTest> =
        drawn.map { (name, diagram) -> DynamicTest.dynamicTest(name) { check(diagram) } }

    @TestFactory
    fun `every node of the document is drawn once, or stood for by the card of its handler`() = each { diagram ->
        val document = diagram.document
        val dom = diagram.dom
        val stoodFor = dom.nodes.flatMap { card -> card.ids("data-nodes").map { it to card } }
        assertEquals(stoodFor.map { it.first }.distinct(), stoodFor.map { it.first }, "a node is stood for twice")

        for (node in document.nodes) {
            val group = dom.group(node.id)
            val card = stoodFor.firstOrNull { it.first == node.id }?.second
            assertTrue((group != null) != (card != null), "node '${node.id}' must be drawn or stood for, not both or neither")
            if (group != null) {
                assertEquals(kindName(node.kind), group.getAttribute("data-kind"), node.id)
            } else {
                // Only where a handler starts and where it returns is there nothing to show but its card.
                assertTrue(node.kind == NodeKind.START || node.kind == NodeKind.END, "'${node.id}' is not drawn")
                assertContains(card!!.classes(), "handler")
                // And where it returns only if no way there says more than that it does.
                val told = document.edges.filter { it.to == node.id && it.kind.isFlow && (it.kind != EdgeKind.FLOW || !it.label.isNullOrBlank()) }
                assertEquals(emptyList(), told, "'${node.id}' is not drawn")
            }
        }
        val known = document.nodes.map { it.id }.toSet() + document.entryPoints.map { it.id }
        (dom.nodes + dom.regions).forEach { assertContains(known, it.getAttribute("id")) }

        for (entryPoint in document.entryPoints) {
            val cards = dom.nodes.filter { entryPoint.id in it.ids("data-entry-points") }
            assertEquals(1, cards.size, "entry point '${entryPoint.id}'")
            val expected = when (entryPoint.kind) {
                EntryPointKind.WORKFLOW_METHOD -> "start"
                EntryPointKind.SIGNAL -> "signal"
                EntryPointKind.QUERY -> "query"
                EntryPointKind.UPDATE, EntryPointKind.UPDATE_VALIDATOR -> "update"
            }
            assertEquals(expected, cards.single().getAttribute("data-kind"), entryPoint.id)
        }
    }

    @TestFactory
    fun `every edge of the document is drawn once`() = each { diagram ->
        val document = diagram.document.normalized()
        val dom = diagram.dom
        val nodes = document.nodes.associateBy { it.id }
        val stoodFor = dom.nodes.flatMap { card -> card.ids("data-nodes").map { it to card.getAttribute("id") } }.toMap()
        val handlerOf = dom.nodes.flatMap { card -> card.ids("data-entry-points").map { it to card.getAttribute("id") } }.toMap()
        fun within(id: String, region: String) = generateSequence(nodes[id]?.parent) { nodes[it]?.parent }.any { it == region }

        val expected = document.edges.mapNotNull { edge ->
            // An edge from an entry point starts at its handler; one from a handler's start node at the handler's card.
            val from = if (edge.from in nodes) stoodFor[edge.from] ?: edge.from else handlerOf.getValue(edge.from)
            val returns = (edge.to in stoodFor && nodes.getValue(edge.to).kind == NodeKind.END) ||
                (edge.from in stoodFor && nodes.getValue(edge.from).kind == NodeKind.END)
            // An edge from a region to a step inside it is not drawn: the box says it.
            val boxed = within(edge.to, edge.from)
            if (returns || boxed) null else listOf(from, stoodFor[edge.to] ?: edge.to, kindName(edge.kind)) to edge.label
        }.distinct().map { it.first.toString() }.sorted()
        val actual = dom.edges.map { listOf(it.getAttribute("data-from"), it.getAttribute("data-to"), it.getAttribute("data-kind")).toString() }.sorted()
        assertEquals(expected, actual)

        // Every edge is a line with an arrowhead and says what it joins.
        for (edge in dom.edges) {
            assertNotNull(edge.title())
            val path = edge.children().single { it.localName == "path" }
            assertTrue(path.getAttribute("d").startsWith("M"))
            assertTrue(path.getAttribute("marker-end").startsWith("url(#"))
        }
        // The positions recorded in the file account for the same edges.
        val recorded = diagram.graph.edges.flatMap { it.sources }.sorted()
        assertEquals(recorded.distinct(), recorded)
        assertEquals(dom.edges.size, diagram.graph.edges.size)
    }

    @TestFactory
    fun `every card says what kind of thing it is and what it is called`() = each { diagram ->
        val nodes = diagram.document.nodes.associateBy { it.id }
        val entryPoints = diagram.document.entryPoints.associateBy { it.id }
        // What is drawn in the band belongs to a handler: where one of them ends, the handler returns.
        val inBand = diagram.graph.band?.let { band -> diagram.graph.nodes.filter { band.contains(it.box) }.map { it.id } }.orEmpty().toSet()
        for (group in diagram.dom.nodes + diagram.dom.regions) {
            val id = group.getAttribute("id")
            val texts = group.texts()
            val title = assertNotNull(group.title(), id)
            assertTrue(texts.isNotEmpty() && texts.first().isNotBlank(), "'$id' has no kind label")
            assertTrue(title.startsWith(texts.first()), "title of '$id': $title")
            // The word for the construct is set in capitals; what follows it may be code, which keeps its case.
            val word = texts.first().substringBefore(" · ")
            assertEquals(word, word.uppercase(), "kind label of '$id'")

            val node = nodes[id]
            if (node != null) {
                val kind = texts.first()
                val expected = kindLabel(node)
                assertTrue(kind == expected || kind.startsWith("$expected · "), "'$id' is labelled '$kind', not '$expected'")
                // A step that is not waited for says so on the line below its name, and a region in its title.
                val waiting = when (node.waitsFor) {
                    WaitsFor.NOTHING -> "not awaited"
                    WaitsFor.START -> "waits for start only"
                    null -> null
                }
                if (waiting != null && node.kind.isContainer) assertEquals("$expected · ${waiting.uppercase()}", kind, id)
                if (waiting != null && !node.kind.isContainer) assertEquals(waiting, texts.last(), id)
                if (node.parentClosePolicy != null) assertEquals("$expected · ABANDON", kind, id)
                val name = if (node.kind == NodeKind.END && id in inBand) "Return" else name(node)
                name?.let { assertNamed(id, if (waiting == null || node.kind.isContainer) texts.drop(1) else texts.drop(1).dropLast(1), it) }
            } else {
                val entryPoint = entryPoints.getValue(id)
                assertContains(listOf("SIGNAL", "QUERY", "UPDATE", "UPDATE + VALIDATOR", "UPDATE VALIDATOR"), texts.first())
                assertNamed(id, texts.drop(1), entryPoint.name)
            }
        }
    }

    /** The lines of a name are the name, broken at will and perhaps cut short. */
    private fun assertNamed(id: String, lines: List<String>, name: String) {
        val expected = name.singleLine().filterNot { it == ' ' }
        val shown = lines.joinToString("").filterNot { it == ' ' }
        val matches = if (shown.endsWith("…")) expected.startsWith(shown.dropLast(1)) || shown.contains("…") else shown == expected
        assertTrue(matches, "'$id' is called '$lines', not '$name'")
        assertTrue(lines.size in 1..2, "'$id' has ${lines.size} lines of name")
    }

    @TestFactory
    fun `everything is where the file says it is, children inside their regions and nothing on top of anything else`() = each { diagram ->
        val graph = diagram.graph
        val regions = graph.regions.associateBy { it.id }

        // The outline that is drawn is the box that is recorded.
        for (node in graph.nodes) assertEquals(node.box, diagram.dom.group(node.id)!!.outline(), node.id)
        // A region's border is drawn half its width inside the box, and that is rounded like every coordinate.
        for (region in graph.regions) {
            val outline = diagram.dom.group(region.id)!!.outline().grown(Geometry.REGION_BORDER / 2)
            val off = listOf(outline.x - region.box.x, outline.y - region.box.y, outline.right - region.box.right, outline.bottom - region.box.bottom)
            assertTrue(off.all { kotlin.math.abs(it) < 0.11 }, "'${region.id}' is drawn at $outline, not ${region.box}")
        }

        for (node in graph.nodes) {
            val parent = node.parent ?: continue
            assertTrue(regions.getValue(parent).box.contains(node.box), "'${node.id}' sticks out of '$parent'")
        }
        for (region in graph.regions) {
            val parent = region.parent ?: continue
            assertTrue(regions.getValue(parent).box.contains(region.box), "'${region.id}' sticks out of '$parent'")
        }

        // Among the things that share a place in the nesting, no two overlap.
        val siblings = (graph.nodes.map { Triple(it.id, it.parent, it.box) } + graph.regions.map { Triple(it.id, it.parent, it.box) })
            .groupBy { it.second }
        for (group in siblings.values) {
            for (i in group.indices) {
                for (j in i + 1 until group.size) {
                    assertFalse(group[i].third.intersects(group[j].third), "'${group[i].first}' and '${group[j].first}' overlap")
                }
            }
        }
        // The handlers are in their band, and the band is clear of the main flow.
        graph.band?.let { band ->
            graph.nodes.filter { it.handler }.forEach { assertTrue(band.contains(it.box), "'${it.id}' is outside the band") }
            graph.rows.forEach { assertFalse(band.intersects(it)) }
        }
        val all = Box(0.0, 0.0, graph.width, graph.height)
        (graph.nodes.map { it.box } + graph.regions.map { it.box } + graph.rows + listOfNotNull(graph.band)).forEach {
            assertTrue(all.grown(0.05).contains(it), "$it is outside the diagram")
        }
    }

    @TestFactory
    fun `the file stands on its own and is sized to its content`() = each { diagram ->
        val dom = diagram.dom
        assertEquals("svg", dom.root.localName)
        assertEquals("http://www.w3.org/2000/svg", dom.root.namespaceURI)
        assertTrue(dom.named("script").isEmpty())
        assertTrue(dom.named("image").isEmpty() && dom.named("foreignObject").isEmpty())
        assertEquals(1, dom.named("style").size)
        assertFalse("@import" in diagram.svg || "url(http" in diagram.svg || "<?xml-stylesheet" in diagram.svg)
        for (element in dom.elements) {
            for (index in 0 until element.attributes.length) {
                val attribute = element.attributes.item(index)
                if (attribute.localName != "href") continue
                // Without a base URL for sources and without sibling diagrams, every reference stays inside the file.
                assertTrue(attribute.nodeValue.startsWith("#"), "reference to ${attribute.nodeValue}")
                assertNotNull(dom.document.getElementsByTagName("*").let { all ->
                    (0 until all.length).map { all.item(it) as org.w3c.dom.Element }.firstOrNull { it.getAttribute("id") == attribute.nodeValue.drop(1) }
                })
            }
        }
        // Icons are defined once and used by reference.
        val symbols = dom.named("symbol").map { it.getAttribute("id") }
        assertEquals(symbols.distinct(), symbols)
        assertTrue(dom.named("use").isNotEmpty())

        val width = dom.root.getAttribute("width").toDouble()
        val height = dom.root.getAttribute("height").toDouble()
        assertEquals("0 0 ${dom.root.getAttribute("width")} ${dom.root.getAttribute("height")}", dom.root.getAttribute("viewBox"))
        assertTrue(width >= diagram.graph.width + 2 * Geometry.MARGIN && height >= diagram.graph.height + 2 * Geometry.MARGIN)

        assertEquals("img", dom.root.getAttribute("role"))
        // The file names the class as well as the type, which other workflows may have too.
        val (type, implementation) = diagram.document.workflowType to diagram.document.implementation
        if (diagram.document.isDynamic) {
            // A class that runs for every type has none of its own: the file says what it is, and so does a heading.
            assertEquals("Workflow diagram: dynamic workflow, implemented by $implementation", dom.root.getAttribute("aria-label"))
            assertEquals("Dynamic workflow · $implementation", dom.root.children().first { it.localName == "title" }.textContent)
            assertEquals("Dynamic workflow$implementation", dom.groups("heading").single().children().single().textContent)
        } else {
            assertEquals("Workflow diagram: $type, implemented by $implementation", dom.root.getAttribute("aria-label"))
            assertEquals("$type · $implementation", dom.root.children().first { it.localName == "title" }.textContent)
            // Nothing says that another diagram shows a workflow of this type, so the diagram has no heading.
            assertTrue(dom.groups("heading").isEmpty())
        }
        val facts = dom.named("diagram").single()
        assertEquals(diagram.document.workflowType, facts.getAttribute("workflowType"))
        assertEquals(diagram.document.implementation, facts.getAttribute("implementation"))
        assertEquals(diagram.document.commit, facts.getAttribute("commit"))
        assertEquals(diagram.document.modelMode.name.lowercase(), facts.getAttribute("modelMode"))
        assertEquals(diagram.document.nodes.count { it.kind == NodeKind.UNKNOWN }.toString(), facts.getAttribute("unknownNodes"))

        assertTrue(diagram.svg.endsWith("</svg>\n"))
        assertFalse('\r' in diagram.svg)
        // Coordinates have one decimal place at most.
        assertFalse(Regex("""[ "MLHVCQ,(]-?\d+\.\d{2,}[ "MLHVCQ,)]""").containsMatchIn(diagram.svg.substringAfter("</metadata>").substringAfter("</style>")))
    }

    @TestFactory
    fun `layers are drawn in order - regions, edges, cards, edge labels, legend`() = each { diagram ->
        val layers = diagram.dom.elements.filter { it.localName == "g" }.map { it.getAttribute("class") }
            .filter { it in listOf("regions", "edges", "nodes", "edge-labels", "legend") }
        assertEquals(listOf("regions", "edges", "nodes", "edge-labels", "legend"), layers)
    }

    @Test
    fun `text from the document is drawn as text, whatever it contains`() {
        val diagram = drawn.getValue("render/${TestDocuments.ODD}")
        val branch = diagram.dom.group("a b")!!
        assertEquals("BRANCH", branch.texts().first())
        // The condition is long: it is cut, and what is left of it is the text as written.
        val shown = branch.texts().drop(1).joinToString(" ")
        assertTrue(shown.startsWith("items.any { it.qty > 0 && it."), shown)
        assertContains(diagram.svg, "items.any { it.qty &gt; 0 &amp;&amp; it.")
        assertEquals("""fix/#42: "quotes"""", diagram.dom.group("n6")!!.texts()[1])
        assertEquals("<b>bold</b> &amp; &lt;i&gt;", diagram.dom.group("n3")!!.texts().drop(1).joinToString(""))
        // Ids with quotes and spaces are still the ids of the groups.
        assertNotNull(diagram.dom.group("x\"y"))
        assertEquals("childWorkflow", diagram.dom.group("x\"y")!!.getAttribute("data-kind"))
        assertEquals(listOf("h start", "h end"), diagram.dom.group("sig-set flag")!!.ids("data-nodes"))
        val labels = diagram.dom.edgeLabels.flatMap { it.texts() }
        assertContains(labels, """"a" < b; c > d""")
        assertContains(labels, "bell and <br/> break")
        // No markup came out of any of it.
        assertTrue(diagram.dom.named("b").isEmpty() && diagram.dom.named("br").isEmpty() && diagram.dom.named("i").isEmpty())
    }

    @Test
    fun `every hard case is a dashed card that says why`() {
        val diagram = drawn.getValue("render/${TestDocuments.FULFILMENT}")
        for (node in diagram.document.nodes.filter { it.kind == NodeKind.UNKNOWN }) {
            val card = diagram.dom.group(node.id)!!
            assertEquals(listOf("UNKNOWN CALL", node.reason), card.texts().take(2))
            assertTrue(card.children().any { it.localName == "rect" && it.getAttribute("stroke-dasharray") == Geometry.OUTLINE_DASH })
        }
        val demo = drawn.getValue("demo/OrderWorkflow")
        assertEquals(listOf("UNKNOWN CALL", "dynamic-dispatch"), demo.dom.group("n17-discount-policy")!!.texts())
    }

    private fun kindName(kind: NodeKind): String = StyleKinds.of(kind)

    private fun kindName(kind: EdgeKind): String = kind.name.lowercase()

    private fun kindLabel(node: IrNode): String = when (node.kind) {
        NodeKind.START -> "START"
        NodeKind.END, NodeKind.CONTINUE_AS_NEW -> "TERMINAL"
        NodeKind.FAIL -> "FAIL"
        NodeKind.ACTIVITY -> "ACTIVITY"
        NodeKind.LOCAL_ACTIVITY -> "LOCAL ACTIVITY"
        NodeKind.CHILD_WORKFLOW -> "CHILD WORKFLOW"
        NodeKind.NEXUS_OPERATION -> "NEXUS OPERATION"
        NodeKind.SIGNAL_EXTERNAL -> "SIGNAL EXTERNAL"
        NodeKind.SIDE_EFFECT -> "SIDE EFFECT"
        NodeKind.VERSION_GATE -> "VERSION GATE"
        NodeKind.BRANCH -> "BRANCH"
        NodeKind.UNKNOWN -> "UNKNOWN CALL"
        NodeKind.TIMER -> "TIMER"
        NodeKind.AWAIT -> "AWAIT"
        NodeKind.LOOP -> "LOOP"
        NodeKind.PARALLEL -> "PARALLEL"
        NodeKind.COMPENSATION -> "COMPENSATION"
        NodeKind.CANCEL_SCOPE -> "CANCEL SCOPE"
        NodeKind.CANCEL_EXTERNAL -> "CANCEL EXTERNAL"
        NodeKind.UPSERT_SEARCH_ATTRIBUTES -> "UPSERT SEARCH ATTRIBUTES"
        NodeKind.UPSERT_MEMO -> "UPSERT MEMO"
    }

    /** The name a card must show, for the kinds whose name comes straight from the document. */
    private fun name(node: IrNode): String? = when (node.kind) {
        NodeKind.START -> "Start"
        NodeKind.END -> "Complete"
        NodeKind.CONTINUE_AS_NEW -> "Continue-as-new"
        NodeKind.ACTIVITY, NodeKind.LOCAL_ACTIVITY, NodeKind.CHILD_WORKFLOW -> node.name
        NodeKind.SIGNAL_EXTERNAL, NodeKind.SIDE_EFFECT -> node.label ?: node.name
        NodeKind.VERSION_GATE -> node.changeId
        NodeKind.BRANCH -> node.label
        NodeKind.CANCEL_SCOPE -> node.label
        NodeKind.CANCEL_EXTERNAL -> node.name ?: "another workflow"
        NodeKind.UPSERT_SEARCH_ATTRIBUTES, NodeKind.UPSERT_MEMO -> node.keys.joinToString(", ").ifEmpty { null }
        else -> null
    }

    companion object {
        private val drawn: Map<String, Drawn> by lazy {
            SvgDocuments.all().mapValues { (_, document) -> Drawn(document, SvgFormat().prepare(document, PageOptions()).render()) }
        }
    }
}
