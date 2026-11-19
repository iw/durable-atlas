package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeIds
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.SourceLocation
import io.github.iw.durableatlas.ir.UnknownReasons
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How calls into functions of the sources are followed, and what handlers are made of, in cases
 * the sample project does not contain: lambdas handed to such functions, getters, objects and
 * class hierarchies, calls that may run one of several functions, functions that call each other
 * in a circle, compensations registered and run in helpers, state that handlers change in other
 * ways than by assigning to a property, and the edges to the ends of regions.
 *
 * Each test gives the whole graph of one workflow of [InliningSources], without ids: a node is
 * its kind and its line, as [IrStructure] writes it, with the file in front of the line where
 * the nodes of a workflow come from more than one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InliningExtractionTest {
    private lateinit var backend: SourceBackend
    private lateinit var discovery: Discovery
    private lateinit var documents: List<IrDocument>

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val sources = SyntheticSources(directory)
        InliningSources.FILES.forEach { (name, text) -> sources.file(":app", "inline/$name.kt", text) }
        backend = sources.open()
        discovery = TemporalDiscovery.discover(backend)
        documents = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.DEGRADED).extract(discovery)
    }

    @AfterAll
    fun close() {
        backend.close()
    }

    private fun document(workflowType: String): IrDocument = documents.single { it.workflowType == workflowType }

    /** The nodes and edges of a workflow, without ids. */
    private fun graph(workflowType: String): List<String> = document(workflowType).outline().filterNot { it.startsWith("entry point ") }

    /** The document of a workflow when calls are followed [maxDepth] deep. */
    private fun document(workflowType: String, maxDepth: Int): IrDocument {
        val workflow = discovery.workflows.single { it.name == workflowType }
        return WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.DEGRADED, maxDepth).extract(workflow, workflow.implementations.single())
    }

    @Test
    fun `every document is well formed, and nothing in the sources fails to resolve`() {
        // An interface that only declares a signal is no workflow type.
        assertEquals(
            listOf("Boundaries", "Choices", "Cycles", "Getters", "Levels", "Members", "Neighbour", "Rounds", "Stateful", "Undoing", "Wrappers"),
            documents.map { it.workflowType },
        )
        documents.forEach { assertWellFormed(it) }
        assertEquals(
            emptyList(),
            documents.flatMap { it.nodes }.filter { it.reason == UnknownReasons.UNRESOLVED }.map { it.inWords() },
        )
    }

    // ---- what is followed

    @Test
    fun `a lambda handed to a function of the sources stands where the function runs it`() {
        assertEquals(
            listOf(
                // What `retrying` does when the lambda fails.
                "node activity@Helpers.kt:22: name Report",
                // The lambda given to `retrying`, once for each place the function runs it.
                "node activity@Wrappers.kt:25#1: name Prepare",
                "node activity@Wrappers.kt:25#2: name Prepare",
                // The lambda given to `beside`, which hands it on to `Async`: a lane.
                "node activity@Wrappers.kt:26: name Check, parent parallel@Helpers.kt:28",
                "node activity@Wrappers.kt:28: name Undo",
                "node activity@Wrappers.kt:31: name Work",
                "node branch@Wrappers.kt:30: label prepared.isEmpty()",
                "node end(main)",
                "node parallel@Helpers.kt:28",
                "node start@Wrappers.kt:24",
                // `later` keeps its lambda in a property: nothing says whether and when it runs.
                "node unknown@Wrappers.kt:28: label later, reason unsupported",
                "edge activity@Helpers.kt:22 -> activity@Wrappers.kt:25#1",
                "edge activity@Wrappers.kt:25#1 -> parallel@Helpers.kt:28",
                // The `try` of the function catches what the lambda raises.
                "edge activity@Wrappers.kt:25#2 -(exception)-> activity@Helpers.kt:22 [ActivityFailure]",
                // `return block()` leaves the function: the flow goes on behind its call.
                "edge activity@Wrappers.kt:25#2 -> parallel@Helpers.kt:28",
                "edge activity@Wrappers.kt:28 -> branch@Wrappers.kt:30",
                "edge activity@Wrappers.kt:31 -> end(main)",
                "edge branch@Wrappers.kt:30 -> activity@Wrappers.kt:31 [no]",
                // A `return` in a lambda given to an inline function leaves the workflow method.
                "edge branch@Wrappers.kt:30 -> end(main) [yes]",
                "edge parallel@Helpers.kt:28 -> unknown@Wrappers.kt:28",
                "edge start@Wrappers.kt:24 -> activity@Wrappers.kt:25#2",
                "edge unknown@Wrappers.kt:28 -> activity@Wrappers.kt:28",
                "edge unknown@Wrappers.kt:28 -> branch@Wrappers.kt:30",
            ),
            graph("Wrappers"),
        )
    }

    @Test
    fun `the getter of a property is followed where the property is read, and one that holds no step leaves nothing`() {
        assertEquals(
            listOf(
                // The getter of `ready`, in front of the branch that asks for it.
                "node activity@30: name Check",
                "node activity@36: name Work",
                "node activity@38: name Report",
                "node branch@35: label ready",
                "node end(main)",
                "node start@34",
                "edge activity@30 -> branch@35",
                "edge activity@36 -> activity@38",
                "edge activity@38 -> end(main)",
                "edge branch@35 -> activity@36 [yes]",
                "edge branch@35 -> activity@38 [no]",
                "edge start@34 -> activity@30",
            ),
            graph("Getters"),
        )
    }

    @Test
    fun `functions of objects, of superclasses and of the class itself are followed on the object they run on`() {
        assertEquals(
            listOf(
                // A function of an object.
                "node activity@Helpers.kt:43: name CleanUp",
                // The superclass's function, which calls what the workflow class overrides.
                "node activity@Members.kt:26: name Work",
                "node activity@Members.kt:43: name Prepare",
                "node activity@Members.kt:47: name Report",
                // An extension function that is a member of the workflow class.
                "node activity@Members.kt:52: name Check",
                "node end(main)",
                "node start@Members.kt:36",
                "edge activity@Helpers.kt:43 -> activity@Members.kt:52",
                "edge activity@Members.kt:26 -> activity@Members.kt:47",
                "edge activity@Members.kt:43 -> activity@Members.kt:26",
                // `super.before` names the function that runs, which holds nothing.
                "edge activity@Members.kt:47 -> activity@Helpers.kt:43",
                "edge activity@Members.kt:52 -> end(main)",
                "edge start@Members.kt:36 -> activity@Members.kt:43",
            ),
            graph("Members"),
        )
        // The other class of the template is never the object: its `after` shows nowhere.
        assertEquals(emptyList(), document("Members").nodes.filter { it.name == "Undo" })
    }

    // ---- what is not followed

    @Test
    fun `a call through a stub is never followed, nor is one into the implementation of an activity`() {
        // A query of another workflow is no step here, and what answers it is not this workflow's code.
        // An activity's implementation called as a plain function is not followed either: its timer shows nowhere.
        assertEquals(listOf("activity CleanUp", "end"), document("Boundaries").steps())
        // The class that implements the other workflow is read for that workflow's own document.
        assertEquals(listOf("activity Work", "end"), document("Neighbour").steps())
        assertEquals(
            listOf("node activity@38: name Report", "node end(query-state)", "node start@37"),
            (document("Neighbour").outline() - document("Neighbour").mainFlow().outline().toSet()).filter { it.startsWith("node ") },
        )
    }

    @Test
    fun `a call that may run one of several functions is an unknown node, unless none of them holds a step`() {
        assertEquals(
            listOf(
                // The object was made on the line before by a constructor: its class is known.
                "node activity@29: name Work",
                // One class implements the interface.
                "node activity@62: name CleanUp",
                "node end(main)",
                "node start@69",
                "node unknown@71: reason dynamic-dispatch, candidates inline.BikeCourier, inline.VanCourier",
                // No class of the sources implements the interface: nothing is known of what runs.
                "node unknown@76: reason dynamic-dispatch",
                "edge activity@29 -> unknown@76",
                "edge activity@62 -> end(main)",
                "edge start@69 -> unknown@71",
                // Neither formatter holds a step: the call on line 73 leaves nothing.
                "edge unknown@71 -> activity@29",
                "edge unknown@76 -> activity@62",
            ),
            graph("Choices"),
        )
    }

    @Test
    fun `functions that call each other in a circle are followed once round, and one that holds no step is no node`() {
        assertEquals(
            listOf(
                "node activity@30: name Work",
                "node activity@36: name Report",
                // The same function called on a lambda inside its own lambda is no circle.
                "node activity@44: name Check",
                "node branch@29: label count > 0",
                "node end(main)",
                "node start@41",
                // `pong` calls `ping`, which is being followed.
                "node unknown@37: label ping, reason recursion",
                "edge activity@30 -> activity@36",
                "edge activity@36 -> unknown@37",
                "edge activity@44 -> end(main)",
                "edge branch@29 -> activity@30 [yes]",
                "edge branch@29 -> activity@44 [no]",
                // `length` calls itself and holds no step: nothing stands for it.
                "edge start@41 -> branch@29",
                "edge unknown@37 -> activity@44",
            ),
            graph("Cycles"),
        )
    }

    @Test
    fun `a call nested deeper than the limit is an unknown node only if a step may lie behind it`() {
        assertEquals(emptyList(), document("Levels").nodesOf(NodeKind.UNKNOWN))
        assertEquals(document("Levels"), document("Levels", maxDepth = 2))

        // One call deep, `inner` is cut off; `label`, which holds nothing, is not worth a node.
        val oneDeep = document("Levels", maxDepth = 1)
        assertEquals(
            listOf("activity Prepare", "unknown depth-limit (inner)", "activity Prepare", "unknown depth-limit (inner)", "end"),
            oneDeep.steps(),
        )
        assertEquals(listOf(28, 28), oneDeep.nodesOf(NodeKind.UNKNOWN).map { it.loc?.line })
        // Not followed at all, the calls of the workflow method are where the flow is cut off.
        val notFollowed = document("Levels", maxDepth = 0)
        assertEquals(listOf("unknown depth-limit (outer)", "unknown depth-limit (outer)", "end"), notFollowed.steps())
        assertEquals(listOf(37, 38), notFollowed.nodesOf(NodeKind.UNKNOWN).map { it.loc?.line })
        listOf(oneDeep, notFollowed).forEach { assertWellFormed(it) }
    }

    // ---- ids

    @Test
    fun `a node's id is made of its entry point, its place and the calls followed to get there`() {
        val document = document("Levels")
        val file = "app/src/main/kotlin/inline/Levels.kt"
        val first = "outer at $file:37"
        val second = "outer at $file:38"

        // The same function is followed from two calls: its steps are two nodes each, told apart by the call.
        assertEquals(
            setOf(
                NodeIds.of("main", SourceLocation(file, 27), listOf(first), "activity"),
                NodeIds.of("main", SourceLocation(file, 27), listOf(second), "activity"),
                NodeIds.of("main", SourceLocation(file, 32), listOf(first, "inner at $file:28"), "activity"),
                NodeIds.of("main", SourceLocation(file, 32), listOf(second, "inner at $file:28"), "activity"),
            ),
            document.nodesOf(NodeKind.ACTIVITY).map { it.id }.toSet(),
        )
        // What is cut off keeps the id it would have as the first node of what it stands for.
        assertEquals(
            setOf(
                NodeIds.of("main", SourceLocation(file, 28), listOf(first), "unknown"),
                NodeIds.of("main", SourceLocation(file, 28), listOf(second), "unknown"),
            ),
            document("Levels", maxDepth = 1).nodesOf(NodeKind.UNKNOWN).map { it.id }.toSet(),
        )
    }

    @Test
    fun `the nodes of a handler have ids of their own, also where two entry points reach the same line`() {
        val document = document("Stateful")
        val file = "app/src/main/kotlin/inline/Stateful.kt"

        assertEquals(NodeIds.of("update-rename", SourceLocation(file, 108), emptyList(), "activity"), document.nodesOf(NodeKind.ACTIVITY).single().id)
        assertEquals(NodeIds.of("update-rename", null, emptyList(), "start"), document.entryPoint("update-rename").start)
        assertEquals(document.entryPoints.size, document.entryPoints.map { it.start }.toSet().size)
    }

    // ---- sagas

    @Test
    fun `compensations registered and run in functions of the sources are seen`() {
        assertEquals(
            listOf(
                // `reserve`, followed from two calls.
                "node activity@Helpers.kt:48#1: name Prepare",
                "node activity@Helpers.kt:48#2: name Prepare",
                // What `reserve` registers, once for each call, and what `noteOnly` registers.
                "node activity@Helpers.kt:49#1: name Undo, parent compensation@Helpers.kt:57",
                "node activity@Helpers.kt:49#2: name Undo, parent compensation@Helpers.kt:57",
                "node activity@Helpers.kt:53: name CleanUp, parent compensation@Helpers.kt:57",
                "node activity@Undoing.kt:30: name Work",
                // The saga compensates in `rollBack`, where it goes by another name.
                "node compensation@Helpers.kt:57",
                "node end(main)",
                "node fail@Undoing.kt:33: name ActivityFailure",
                "node start@Undoing.kt:24",
                "edge activity@Helpers.kt:48#1 -(exception)-> compensation@Helpers.kt:57 [ActivityFailure]",
                "edge activity@Helpers.kt:48#1 -> activity@Undoing.kt:30",
                "edge activity@Helpers.kt:48#2 -(exception)-> compensation@Helpers.kt:57 [ActivityFailure]",
                "edge activity@Helpers.kt:48#2 -> activity@Helpers.kt:48#1",
                // Last registered, first run.
                "edge activity@Helpers.kt:49#1 -> activity@Helpers.kt:49#2",
                "edge activity@Helpers.kt:49#2 -> activity@Helpers.kt:53",
                "edge activity@Undoing.kt:30 -(exception)-> compensation@Helpers.kt:57 [ActivityFailure]",
                "edge activity@Undoing.kt:30 -> end(main)",
                "edge compensation@Helpers.kt:57 -> fail@Undoing.kt:33",
                "edge start@Undoing.kt:24 -> activity@Helpers.kt:48#2",
            ),
            graph("Undoing"),
        )
    }

    // ---- handlers and state

    @Test
    fun `a handler writes what it assigns to, changes through a call or has a function of the sources change`() {
        assertEquals(
            listOf(
                // `items += item`: a call that changes the list. Naming the list for it is no read.
                "signal-add writes items",
                // A private function of the workflow class assigns to the property.
                "signal-cancel writes cancelled",
                // `counts[key] = 1`.
                "signal-count writes counts",
                // The handler of another workflow interface the class implements.
                "signal-pause writes cancelled",
                // A function of the object in `progress` assigns to a property of that object.
                "signal-pay writes progress",
                "query-describe reads label, progress",
                // `with(progress) { notes.add(name) }` changes what `progress` holds.
                "update-rename reads label writes label, progress",
                // `attempts++` reads what it writes; the handler waits for `cancelled` as well.
                "update-retry reads attempts, cancelled writes attempts",
                "validator-rename reads label",
            ),
            document("Stateful").handlers(),
        )
    }

    @Test
    fun `an await reads what its condition reads, also through functions and lambdas, and is woken by who writes that`() {
        val document = document("Stateful")

        assertEquals(
            listOf(
                // The await of the update handler.
                "await@100: reads cancelled, label cancelled",
                // `isCancelled()` is a function of the workflow class that reads `cancelled`.
                "await@65: reads cancelled, progress, label progress.paid or isCancelled()",
                "await@66: reads items, label items.isNotEmpty()",
                "await@68: reads attempts, label attempts > 3",
                // The await is in a function of the class; its condition is the lambda the function was handed.
                "await@75: timeout PT5M, reads counts, label counts.size > 2",
            ),
            document.nodeOutline().filter { it.startsWith("await") },
        )
        assertEquals(
            listOf(
                "signal-add -> await@66",
                // A signal wakes the await of an update handler too.
                "signal-cancel -> await@100",
                "signal-cancel -> await@65",
                "signal-count -> await@75",
                "signal-pause -> await@100",
                "signal-pause -> await@65",
                "signal-pay -> await@65",
                // Whatever is reached through a property counts as the property.
                "update-rename -> await@65",
                "update-retry -> await@68",
            ),
            document.wakes(),
        )
        // The update handlers hold steps; every other handler is a start and an end.
        assertEquals(
            listOf(
                "edge activity@108 -> end(update-rename)",
                "edge await@100 -> end(update-retry)",
                "edge start@104 -> activity@108",
                "edge start@111 -> end(validator-rename)",
                "edge start@115 -> end(query-describe)",
                "edge start@117 -> end(signal-pause)",
                "edge start@78 -> end(signal-pay)",
                "edge start@82 -> end(signal-cancel)",
                "edge start@90 -> end(signal-add)",
                "edge start@94 -> end(signal-count)",
                "edge start@98 -> await@100",
            ),
            (document.outline() - document.mainFlow().outline().toSet()).filter { it.startsWith("edge ") && "wakes" !in it },
        )
    }

    @Test
    fun `reading everything an entry point reaches gives the graph that sparing what holds nothing gives`() {
        assertEquals(documents.sumOf { it.mainFlow().nodes.size }, assertSparingChangesNothing(backend, discovery))
    }

    // ---- the ends of regions

    @Test
    fun `where the flow reaches the end of a region on an edge worth drawing, the edge leads to the region`() {
        assertEquals(
            listOf(
                "node activity@27: name Prepare, parent loop@26",
                "node activity@29: name Report, parent loop@26",
                "node activity@36: name Work, parent loop@33",
                "node activity@40: name CleanUp, parent parallel@39",
                "node activity@44: name Undo, parent compensation@46",
                "node branch@28: label id.isEmpty(), parent loop@26",
                "node branch@34: label part.isEmpty(), parent loop@33",
                "node branch@35: label part == \"x\", parent loop@33",
                "node branch@40: label ids.isEmpty(), parent parallel@39",
                "node branch@44: label ids.size > 1, parent compensation@46",
                "node compensation@46",
                "node end(main)",
                "node loop@26: label for each id",
                "node loop@32: label for each id",
                "node loop@33: label for each part, parent loop@32",
                "node parallel@39",
                "node start@24",
                "edge activity@27 -> branch@28",
                "edge branch@28 -> activity@29 [yes]",
                // The arm that does nothing leads to the end of the round. The step in the other arm is the last
                // of its region and leads nowhere else: it is left by implication.
                "edge branch@28 -> loop@26 [no]",
                // `continue@outer` ends the round of the outer loop, and so does `break` where nothing follows the
                // inner loop in that round: an edge to the loop around the one the branch is in.
                "edge branch@34 -> branch@35 [no]",
                "edge branch@34 -> loop@32 [yes]",
                "edge branch@35 -> activity@36 [no]",
                "edge branch@35 -> loop@32 [yes]",
                // The end of a lane.
                "edge branch@40 -> activity@40 [yes]",
                "edge branch@40 -> parallel@39 [no]",
                // The end of the chain of compensations.
                "edge branch@44 -> activity@44 [yes]",
                "edge branch@44 -> compensation@46 [no]",
                "edge compensation@46 -> end(main)",
                "edge loop@26 -> loop@32",
                "edge loop@32 -> parallel@39",
                "edge parallel@39 -> compensation@46",
                "edge start@24 -> loop@26",
            ),
            graph("Rounds"),
        )
    }
}
