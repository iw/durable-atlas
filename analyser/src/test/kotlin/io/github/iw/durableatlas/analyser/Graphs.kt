package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.graph.CallRecognizer
import io.github.iw.durableatlas.analyser.graph.Constants
import io.github.iw.durableatlas.analyser.graph.GraphExtractor
import io.github.iw.durableatlas.analyser.graph.Stubs
import io.github.iw.durableatlas.analyser.graph.TemporalNames
import io.github.iw.durableatlas.analyser.graph.ValueTracer
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.IrStats
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.ir.testing.RegionEdges
import io.github.iw.durableatlas.ir.validate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Ways of looking at an IR document that tests of graph extraction share.

private val nodeId = Regex("n[0-9a-f]{10}")

fun IrDocument.node(id: String): IrNode = nodes.single { it.id == id }

/** The `start` node of the workflow method. */
fun IrDocument.start(): IrNode = node(entryPoints.single { it.kind == EntryPointKind.WORKFLOW_METHOD }.start)

/** The entry point with this id. */
fun IrDocument.entryPoint(id: String): EntryPoint = entryPoints.single { it.id == id }

/** The document without its handlers: the graph of the workflow method alone, and no edge that wakes an await. */
fun IrDocument.mainFlow(): IrDocument {
    val owners = IrStructure.of(this).owners
    val kept = nodes.filter { owners[it.id] == MAIN_ENTRY_POINT }
    val ids = kept.map { it.id }.toSet()
    return copy(
        entryPoints = entryPoints.filter { it.id == MAIN_ENTRY_POINT },
        nodes = kept,
        edges = edges.filter { it.from in ids && it.to in ids },
        stats = IrStats.of(kept),
    )
}

/** The handlers of the document, each as one line: its id, and what it reads and writes. */
fun IrDocument.handlers(): List<String> =
    entryPoints.filter { it.kind != EntryPointKind.WORKFLOW_METHOD }.map { handler ->
        listOfNotNull(
            handler.id,
            handler.reads.takeIf { it.isNotEmpty() }?.joinToString(", ", "reads "),
            handler.writes.takeIf { it.isNotEmpty() }?.joinToString(", ", "writes "),
        ).joinToString(" ")
    }

/** The edges from handlers to the awaits they wake, as `handler -> await@line`. */
fun IrDocument.wakes(): List<String> {
    val handles = IrStructure.of(this).handles
    return edges.filter { it.kind == EdgeKind.WAKES }.map { "${it.from} -> ${handles[it.to]}" }.sorted()
}

private const val MAIN_ENTRY_POINT = "main"

fun IrDocument.successors(node: IrNode): List<IrNode> =
    edges.filter { it.from == node.id }.map { node(it.to) }.sortedWith(sourceOrder)

fun IrDocument.predecessors(node: IrNode): List<IrNode> =
    edges.filter { it.to == node.id }.map { node(it.from) }.sortedWith(sourceOrder)

/** The nodes of [kind] in the order of their places in the source. */
fun IrDocument.nodesOf(kind: NodeKind): List<IrNode> = nodes.filter { it.kind == kind }.sortedWith(sourceOrder)

/**
 * What happens from the start for as long as there is only one way to go: each node in a few
 * words, `start` left out. A workflow without branches reads from its first step to `end`.
 */
fun IrDocument.steps(): List<String> {
    val steps = mutableListOf<String>()
    var current = start()
    while (true) {
        current = successors(current).singleOrNull() ?: break
        steps += current.inWords()
    }
    return steps
}

/** A node as a test would say it: its kind, and what tells it from others of its kind. */
fun IrNode.inWords(): String =
    listOfNotNull(
        NodeKind.serializer().descriptor.getElementName(kind.ordinal),
        service,
        name,
        changeId,
        duration,
        reason,
        label?.let { "($it)" },
        timeout?.let { "timeout $it" },
        taskQueue?.let { "on $it" },
        reads.takeIf { it.isNotEmpty() }?.joinToString(", ", "reads "),
    ).joinToString(" ")

private val sourceOrder: Comparator<IrNode> =
    compareBy<IrNode>({ it.loc?.file.orEmpty() }, { it.loc?.line ?: Int.MAX_VALUE }, { it.kind }, { it.id })

/** The document without its ids, one line for each entry point, node and edge; see [IrStructure]. */
fun IrDocument.outline(): List<String> = IrStructure.of(this).describe()

/** The edges of the document without ids, sorted; see [IrStructure]. */
fun IrDocument.edgeOutline(): List<String> = outline().filter { it.startsWith("edge ") }.map { it.removePrefix("edge ") }

/** The nodes of the document without ids, sorted; see [IrStructure]. */
fun IrDocument.nodeOutline(): List<String> = outline().filter { it.startsWith("node ") }.map { it.removePrefix("node ") }

/**
 * Checks what must hold for every document graph extraction makes: it matches the schema, it is
 * consistent, it survives a round trip, each of its graphs has one way in and no loose ends, and
 * its regions keep to the convention for their edges.
 */
fun assertWellFormed(document: IrDocument, schema: JsonSchemaChecker = JsonSchemaChecker.forWorkflowIr()) {
    val about = document.workflowType
    val json = IrJson.encode(document)
    schema.assertValid(json)
    assertEquals(emptyList(), document.validate(), about)
    assertEquals(document, IrJson.decode(json), "$about is in canonical order and survives a round trip")
    assertEquals(json, IrJson.encode(IrJson.decode(json)), about)

    for (node in document.nodes) assertTrue(nodeId.matches(node.id), "$about: ${node.id}")
    assertEquals(document.nodes.size, document.nodes.map { it.id }.toSet().size, "$about: ids are unique")

    // Every entry point has a graph of its own: one start, at most one end, and no node of another's.
    val owners = IrStructure.of(document).owners
    assertEquals(1, document.entryPoints.count { it.kind == EntryPointKind.WORKFLOW_METHOD }, "$about has one workflow method")
    assertEquals(document.entryPoints.map { it.start }.sorted(), document.nodesOf(NodeKind.START).map { it.id }.sorted(), "$about: starts")
    for (entryPoint in document.entryPoints) {
        val own = document.nodes.filter { owners[it.id] == entryPoint.id }
        assertTrue(own.count { it.kind == NodeKind.END } <= 1, "$about: ${entryPoint.id} has at most one end")
        if (entryPoint.kind == EntryPointKind.WORKFLOW_METHOD) {
            assertEquals(emptyList(), entryPoint.reads + entryPoint.writes, "$about: the workflow method lists no state")
        }
    }
    for (node in document.nodes) assertNotNull(owners[node.id], "$about: ${node.inWords()} is reached from an entry point")
    for (edge in document.edges.filter { it.kind.isFlow }) {
        assertEquals(owners[edge.from], owners[edge.to], "$about: ${edge.from} -> ${edge.to} stays within one entry point")
    }

    val reached = document.edges.filter { it.kind.isFlow }.map { it.to }.toSet()
    val left = document.edges.filter { it.kind.isFlow }.map { it.from }.toSet()
    for (node in document.nodes) {
        val words = "$about: ${node.inWords()} at line ${node.loc?.line}"
        if (node.kind != NodeKind.END) assertNotNull(node.loc, "$words has a place in the source")
        assertTrue(node.label.isCaption(), "$words has a label that fits on a line")
        if (node.kind == NodeKind.UNKNOWN) assertNotNull(node.reason, "$words says why it is unknown")
        if (node.kind.isTerminal) {
            assertTrue(node.id !in left, "$words is a terminal and leads nowhere")
            assertEquals(null, node.parent, "$words is a terminal and belongs to no region")
        }
        // Inside a region the first nodes are entered and the last ones left without an edge.
        if (node.parent != null) continue
        assertEquals(node.kind != NodeKind.START, node.id in reached, "$words is reached, unless it is the start")
        // A loop that is only ever left from inside leads on from there.
        if (!node.kind.isTerminal && node.kind != NodeKind.LOOP) assertTrue(node.id in left, "$words leads on")
    }

    assertEquals(emptyList(), RegionEdges.violations(document), "$about keeps to the convention for regions")
    val parents = document.nodes.associate { it.id to it.parent }
    fun regionsAround(id: String): List<String> = generateSequence(parents[id]) { parents[it] }.toList()
    for (edge in document.edges) {
        val words = "$about: ${edge.from} -> ${edge.to}"
        assertTrue(edge.label.isCaption(), "$words has a label that fits on a line")
        if (edge.kind == EdgeKind.WAKES) {
            // A handler wakes an await wherever the await is; the edge starts at the handler, which is no node.
            val handler = document.entryPoints.singleOrNull { it.id == edge.from }
            assertEquals(NodeKind.AWAIT, document.node(edge.to).kind, "$words wakes an await")
            if (handler?.kind == EntryPointKind.WORKFLOW_METHOD) {
                // The workflow method lists nothing it writes: the edge says which of what the await reads it changes.
                val named = edge.label.orEmpty().split(", ")
                assertTrue(named.isNotEmpty() && named.all { it in document.node(edge.to).reads }, "$words names what the await reads")
                assertEquals(named.sorted(), named, "$words names the properties in order")
                continue
            }
            assertTrue(handler?.kind == EntryPointKind.SIGNAL || handler?.kind == EntryPointKind.UPDATE, "$words starts at a signal, an update or the workflow method")
            assertTrue(handler.writes.any { it in document.node(edge.to).reads }, "$words: the handler writes what the await reads")
            continue
        }
        if (edge.kind == EdgeKind.CANCELS) {
            // The place where a scope is cancelled cancels a step wherever that is, in its own graph or another.
            assertEquals(NodeKind.CANCEL_SCOPE, document.node(edge.from).kind, "$words starts where a scope is cancelled")
            assertTrue(!document.node(edge.to).kind.isTerminal && edge.to !in regionsAround(edge.from) && edge.to != edge.from, "$words cancels a step or a region beside it")
            assertEquals(null, edge.label, "$words says no more than that")
            continue
        }
        // The one edge between a node and a region around it: from the node to the region, for "to the end of it".
        if (edge.to in regionsAround(edge.from)) {
            assertTrue(!document.node(edge.from).kind.isTerminal, "$words leaves a terminal")
            // To the end of its own region a node is left by implication, unless another of its edges says
            // where it goes: to another child, to the end of a region around it, or as a flow edge out of the region.
            val saysMore = document.edges.any {
                it != edge && it.from == edge.from && it.kind.isFlow &&
                    (parents[it.to] == edge.to || it.to in regionsAround(edge.from) || it.kind == EdgeKind.FLOW)
            }
            assertTrue(
                parents[edge.from] != edge.to || edge.label != null || edge.kind != EdgeKind.FLOW || saysMore,
                "$words says what the convention implies: no other edge of the node says where it goes",
            )
        }
        when (edge.kind) {
            EdgeKind.EXCEPTION -> assertNotNull(edge.label, "$words says which failure it is for")
            EdgeKind.TIMEOUT -> assertNotNull(document.node(edge.from).timeout, "$words leaves a step that has a timeout")
            else -> Unit
        }
    }
}

/**
 * Checks that the graph of every workflow method is the same whether the functions that hold
 * nothing are spared, as they are for a workflow method, or read like the rest, as they are for
 * a handler: sparing them must never change what is drawn. Returns the number of nodes compared.
 */
fun assertSparingChangesNothing(backend: SourceBackend, discovery: Discovery): Int {
    val names = TemporalNames(backend)
    val tracer = ValueTracer(backend)
    val constants = Constants(tracer)
    val stubs = Stubs(tracer, constants)
    var compared = 0
    for (workflow in discovery.workflows) {
        for (implementation in workflow.implementations) {
            val main = implementation.entryPoints.single { it.entryPoint.kind == EntryPointKind.WORKFLOW_METHOD }
            val recognizer = CallRecognizer(backend, names, stubs, constants, tracer, workflow.name)
            val extractor = GraphExtractor(backend, recognizer, tracer, WorkflowGraphs.DEFAULT_MAX_DEPTH)
            val className = implementation.declaration.name
            val spared = extractor.extract("main", main.function, className, null, EntryPointKind.WORKFLOW_METHOD).graph
            // A signal is read like any handler, and what leaves it is judged like what leaves the workflow method.
            val thorough = extractor.extract("main", main.function, className, null, EntryPointKind.SIGNAL).graph

            assertEquals(spared.nodes, thorough.nodes, workflow.name)
            assertEquals(spared.edges, thorough.edges, workflow.name)
            compared += spared.nodes.size
        }
    }
    return compared
}

private fun String?.isCaption(): Boolean = this == null || (isNotEmpty() && length <= 80 && none { it == '\n' || it == '\r' })
