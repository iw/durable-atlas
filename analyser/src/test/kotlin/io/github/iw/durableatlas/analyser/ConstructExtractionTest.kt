package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.analyser.inventory.Inventory
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a workflow does outside its workflow method, and what the SDK runs for it in ways of its
 * own: the steps taken while the workflow object is made, the options of stubs made there, a
 * class that runs for every workflow type, handlers registered while the workflow runs, the
 * test of what an await waited for, a saga that compensates side by side, and a retry.
 *
 * Each test gives the whole graph of one workflow of [ConstructSources], without ids: a node is
 * its kind and its line, as [IrStructure] writes it. The documents are kept as golden files as
 * well, for the tests of what is drawn from them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConstructExtractionTest {
    private lateinit var backend: SourceBackend
    private lateinit var discovery: Discovery
    private lateinit var documents: List<IrDocument>
    private lateinit var warnings: List<String>

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val sources = SyntheticSources(directory)
        ConstructSources.FILES.forEach { (name, text) -> sources.file(":app", "constructs/$name.kt", text) }
        backend = sources.open()
        discovery = TemporalDiscovery.discover(backend)
        val graphs = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.DEGRADED)
        documents = graphs.extract(discovery)
        warnings = graphs.warnings
    }

    @AfterAll
    fun close() {
        backend.close()
    }

    /** The nodes and edges of a workflow, without ids. */
    private fun graph(workflowType: String): List<String> = whole(workflowType).filterNot { it.startsWith("entry point ") }

    /** The entry points, nodes and edges of a workflow, without ids. */
    private fun whole(workflowType: String): List<String> = documents.single { it.workflowType == workflowType }.outline()

    @Test
    fun `an overview keeps every step of the full document, and its golden file is there where it differs`() {
        // The block handed to `lazy` stands where the property is declared; nothing else of these sources is left out.
        assertEquals(listOf("BaseSteps"), assertOverviewsKeepTheSteps(backend, discovery, "overview/constructs"))
    }

    @Test
    fun `every document is well formed and gives the golden IR`() {
        val expected = ConstructSources.FILES.keys - ConstructSources.SHARED - "Fallback" + "dynamic-Fallback"
        assertEquals(expected, documents.map { it.workflowType }.toSet())
        for (document in documents) {
            assertWellFormed(document)
            GoldenFiles.assertMatches("ir/constructs/${document.workflowType}.ir.json", IrJson.encode(document))
        }
        assertEquals(emptyList(), discovery.warnings)
    }

    @Test
    fun `reading everything an entry point reaches gives the graph that sparing what holds nothing gives`() {
        // The steps taken while the object is made are no part of what is compared: they are read the same either way.
        val made = mapOf("InitSteps" to 4, "BaseSteps" to 4)
        assertEquals(documents.sumOf { it.mainFlow().nodes.size - (made[it.workflowType] ?: 0) }, assertSparingChangesNothing(backend, discovery))
    }

    @Test
    fun `nothing in these workflows is left unknown but what a lazy value holds`() {
        assertEquals(mapOf("BaseSteps" to 1), documents.associate { it.workflowType to it.stats.unknown }.filterValues { it > 0 })
    }

    // ---- steps taken while the workflow object is made

    @Test
    fun `the steps of initialisers, init blocks and the constructor the SDK calls come before those of the workflow method`() {
        assertEquals(
            listOf(
                "node activity@32: name Prepare",
                "node activity@36: name Check",
                "node activity@42: name Report",
                "node activity@46: name Work",
                "node branch@41: label !checked",
                "node end(main)",
                "node start@45",
                // The initialiser of a property, then the `init` block, in the order they are written.
                "edge activity@32 -> activity@36",
                // Then the body of the constructor.
                "edge activity@36 -> branch@41",
                "edge activity@42 -> activity@46",
                "edge activity@46 -> end(main)",
                "edge branch@41 -> activity@42 [no]",
                // A `return` ends the constructor, not the workflow: the workflow method runs next.
                "edge branch@41 -> activity@46 [yes]",
                "edge start@45 -> activity@32",
            ),
            graph("InitSteps"),
        )
    }

    @Test
    fun `a class without a constructor of its own is made after its superclass, and a lazy value is no step of the making`() {
        assertEquals(
            listOf(
                "node activity@34: name Prepare",
                "node activity@39: name Check",
                "node activity@46: name Work",
                "node end(main)",
                "node start@45",
                "node timer@42: duration PT1S",
                "node unknown@39: label lazy, reason unsupported",
                // The `init` block of the superclass comes first.
                "edge activity@34 -> unknown@39",
                "edge activity@39 -> timer@42",
                "edge activity@46 -> end(main)",
                "edge start@45 -> activity@34",
                "edge timer@42 -> activity@46",
                // Nothing says when the value is asked for: what it holds may run or be passed by.
                "edge unknown@39 -> activity@39",
                "edge unknown@39 -> timer@42",
            ),
            graph("BaseSteps"),
        )
    }

    @Test
    fun `where nothing says which constructor makes the workflow its steps are left out and a warning names it`() {
        assertEquals(
            listOf(
                "node activity@42: name Work",
                "node end(main)",
                "node start@41",
                "edge activity@42 -> end(main)",
                "edge start@41 -> activity@42",
            ),
            graph("TwoConstructors"),
        )
        // The constructor without a step is not worth a warning.
        assertEquals(
            listOf(
                "app/src/main/kotlin/constructs/TwoConstructors.kt:33: constructs.TwoConstructorsImpl has several constructors " +
                    "and none says that it makes the workflow; the steps in this one are not drawn",
            ),
            warnings.filter { "TwoConstructors" in it },
        )
    }

    @Test
    fun `a stub made in an init block or in the body of a constructor keeps its options`() {
        assertEquals(
            listOf(
                "node activity@45: name Prepare, timeout PT20S",
                "node activity@46: name Work, taskQueue slow, timeout PT5M",
                "node end(main)",
                "node start@44",
                "edge activity@45 -> activity@46",
                "edge activity@46 -> end(main)",
                "edge start@44 -> activity@45",
            ),
            graph("InitStubs"),
        )
    }

    // ---- a class that runs for every workflow type

    @Test
    fun `a class that implements the dynamic workflow of the SDK is a workflow, named after the class`() {
        assertEquals(
            listOf(
                "entry point dynamic-query: kind QUERY, name (any name), start start@29, reads note",
                "entry point dynamic-signal: kind SIGNAL, name (any name), start start@28, writes note",
                "entry point main: kind WORKFLOW_METHOD, name dynamic-Fallback, start start@27",
                "node activity@34: name Handle, timeout PT10S",
                "node await@33: reads note, label note != null",
                "node end(dynamic-query)",
                "node end(dynamic-signal)",
                "node end(main)",
                "node start@27",
                "node start@28",
                "node start@29",
                "edge activity@34 -> end(main)",
                "edge await@33 -> activity@34",
                // The handler for every signal writes what the await waits for.
                "edge entry point dynamic-signal -(wakes)-> await@33",
                "edge start@27 -> await@33",
                "edge start@28 -> end(dynamic-signal)",
                "edge start@29 -> end(dynamic-query)",
            ),
            whole("dynamic-Fallback"),
        )
        val document = documents.single { it.workflowType == "dynamic-Fallback" }
        assertEquals("io.temporal.workflow.DynamicWorkflow", document.interfaceName)
        assertEquals("constructs.Fallback", document.implementation)

        val listed = Inventory.of(discovery, FIXED_COMMIT, ModelMode.DEGRADED).workflows.single { it.workflowType == "dynamic-Fallback" }
        assertEquals("io.temporal.workflow.DynamicWorkflow", listed.interfaceName)
        // The interface is the SDK's: it has no place in the sources.
        assertEquals(null, listed.loc)
        assertEquals(listOf("workflowMethod dynamic-Fallback execute"), listed.entryPoints.map { "${it.kind.name.camel()} ${it.name} ${it.method}" })
        assertEquals(listOf("constructs.Fallback"), listed.implementations.map { it.className })
    }

    // ---- handlers registered while the workflow runs

    @Test
    fun `an object and a lambda handed to registerListener bring handlers, and what cannot be read brings a warning`() {
        assertEquals(
            listOf(
                // The lambda handles every update that has no handler of its own.
                "entry point dynamic-update: kind UPDATE, name (any name), start start@71, reads extra, writes extra",
                "entry point main: kind WORKFLOW_METHOD, name Listeners, start start@68",
                // The object brings the handlers its class declares; what they do to it is done to the property that holds it.
                "entry point query-isPaused: kind QUERY, name isPaused, start start@52, reads panel",
                "entry point signal-pause: kind SIGNAL, name pause, start start@48, writes panel",
                "entry point update-resume: kind UPDATE, name resume, start start@54, writes panel",
                "entry point validator-resume: kind UPDATE_VALIDATOR, name resume, start start@59",
                "node activity@56: name Prepare",
                "node activity@73: name Report",
                "node activity@80: name Work",
                "node await@79: reads panel, label not panel.paused",
                "node end(dynamic-update)",
                "node end(main)",
                "node end(query-isPaused)",
                "node end(signal-pause)",
                "node end(update-resume)",
                "node end(validator-resume)",
                "node start@48",
                "node start@52",
                "node start@54",
                "node start@59",
                "node start@68",
                "node start@71",
                "edge activity@56 -> end(update-resume)",
                "edge activity@73 -> end(dynamic-update)",
                "edge activity@80 -> end(main)",
                "edge await@79 -> activity@80",
                "edge entry point signal-pause -(wakes)-> await@79",
                "edge entry point update-resume -(wakes)-> await@79",
                "edge start@48 -> end(signal-pause)",
                "edge start@52 -> end(query-isPaused)",
                "edge start@54 -> activity@56",
                "edge start@59 -> end(validator-resume)",
                "edge start@68 -> await@79",
                "edge start@71 -> activity@73",
            ),
            whole("Listeners"),
        )
        // The object literal has no class whose functions could be read.
        assertEquals(
            listOf(
                "app/src/main/kotlin/constructs/Listeners.kt:76: what the object given to Workflow.registerListener handles " +
                    "cannot be told; its handlers are not drawn",
            ),
            warnings.filter { "Listeners" in it },
        )
    }

    // ---- a test of what an await waited for

    @Test
    fun `an arm that is tested for with more than one reason of the await is called what the test says`() {
        assertEquals(
            listOf(
                "node activity@45: name Work, parent loop@42",
                "node await@43: reads done, queue, label not queue.isEmpty() or done, parent loop@42",
                "node end(main)",
                "node end(signal-add)",
                "node end(signal-finish)",
                "node loop@42: label while true",
                "node start@40",
                "node start@50",
                "node start@54",
                // Whenever something is queued the work goes on, also when the workflow was told to finish.
                "edge await@43 -> activity@45 [not queue.isEmpty()]",
                // So being told to finish is not what ends it: an empty queue and being told to finish is.
                "edge await@43 -> end(main) [queue.isEmpty() and done]",
                "edge entry point signal-add -(wakes)-> await@43",
                "edge entry point signal-finish -(wakes)-> await@43",
                "edge start@40 -> loop@42",
                "edge start@50 -> end(signal-add)",
                "edge start@54 -> end(signal-finish)",
            ),
            graph("QueueLoop"),
        )
    }

    @Test
    fun `an arm that is tested for with one reason keeps its name, and the arm left over that of the other`() {
        assertEquals(
            listOf(
                "node activity@44: name CleanUp",
                "node activity@46: name Work",
                "node await@41: timeout PT1H, reads approved, stopped, label approved or stopped",
                "node end(main)",
                "node end(signal-approve)",
                "node end(signal-stop)",
                "node start@40",
                "node start@50",
                "node start@54",
                "edge activity@44 -> end(main)",
                "edge activity@46 -> end(main)",
                "edge await@41 -(timeout)-> end(main)",
                // Wherever the workflow was stopped, this is the way, approved or not.
                "edge await@41 -> activity@44 [stopped]",
                // What is left over reads as "otherwise".
                "edge await@41 -> activity@46 [approved]",
                "edge entry point signal-approve -(wakes)-> await@41",
                "edge entry point signal-stop -(wakes)-> await@41",
                "edge start@40 -> await@41",
                "edge start@50 -> end(signal-approve)",
                "edge start@54 -> end(signal-stop)",
            ),
            graph("FirstWins"),
        )
    }

    @Test
    fun `the arm of a when that is tested for with a reason and against another is called what it tests`() {
        assertEquals(
            listOf(
                "node activity@43: name CleanUp",
                "node activity@44: name Work",
                "node await@41: reads approved, stopped, label approved or stopped",
                "node end(main)",
                "node end(signal-approve)",
                "node end(signal-stop)",
                "node start@40",
                "node start@48",
                "node start@52",
                "edge activity@43 -> end(main)",
                "edge activity@44 -> end(main)",
                // Being stopped does not lead here when the workflow was approved as well.
                "edge await@41 -> activity@43 [stopped and not approved]",
                // Being approved always leads here: the `else` is called after it.
                "edge await@41 -> activity@44 [approved]",
                "edge entry point signal-approve -(wakes)-> await@41",
                "edge entry point signal-stop -(wakes)-> await@41",
                "edge start@40 -> await@41",
                "edge start@48 -> end(signal-approve)",
                "edge start@52 -> end(signal-stop)",
            ),
            graph("WhenReasons"),
        )
    }

    // ---- sagas

    @Test
    fun `a saga made to compensate side by side has a lane for each compensation`() {
        assertEquals(
            listOf(
                "node activity@34: name CleanUp, parent parallel@44",
                "node activity@35: name Prepare",
                "node activity@37: name Report, parent parallel@44",
                "node activity@38: name Undo, parent parallel@44",
                "node activity@40: name Work",
                "node activity@41: name Release, parent parallel@44",
                "node activity@42: name Check",
                "node compensation@44",
                "node end(main)",
                "node fail@45: name ActivityFailure",
                // The lanes are all the compensation holds.
                "node parallel@44: parent compensation@44",
                "node start@31",
                "edge activity@35 -(exception)-> compensation@44 [ActivityFailure]",
                "edge activity@35 -> activity@40",
                // The one edge among the lanes joins the two steps of one compensation.
                "edge activity@37 -> activity@38",
                "edge activity@40 -(exception)-> compensation@44 [ActivityFailure]",
                "edge activity@40 -> activity@42",
                "edge activity@42 -(exception)-> compensation@44 [ActivityFailure]",
                "edge activity@42 -> end(main)",
                "edge compensation@44 -> fail@45",
                "edge start@31 -> activity@35",
            ),
            graph("SideBySide"),
        )
        val document = documents.single { it.workflowType == "SideBySide" }
        val lanes = document.nodes.single { it.kind == NodeKind.PARALLEL }
        assertEquals(NodeKind.COMPENSATION, document.node(lanes.parent!!).kind)
        assertEquals(listOf(lanes.id), document.nodes.filter { it.parent == lanes.parent }.map { it.id })
    }

    @Test
    fun `a saga compensates in turn where its options say so, and where they do not say with a warning`() {
        assertEquals(
            listOf(
                "node activity@35: name CleanUp, parent compensation@42",
                "node activity@36: name Report, parent compensation@43",
                "node activity@37: name Prepare",
                "node activity@38: name CleanUp, parent compensation@42",
                "node activity@39: name Report, parent compensation@43",
                "node activity@40: name Work",
                "node compensation@42",
                "node compensation@43",
                "node end(main)",
                "node start@31",
                "edge activity@37 -(exception)-> compensation@42 [ActivityFailure]",
                "edge activity@37 -> activity@40",
                // Last registered first, in both.
                "edge activity@38 -> activity@35",
                "edge activity@39 -> activity@36",
                "edge activity@40 -(exception)-> compensation@42 [ActivityFailure]",
                "edge activity@40 -> end(main)",
                "edge compensation@42 -> compensation@43",
                "edge compensation@43 -> end(main)",
                "edge start@31 -> activity@37",
            ),
            graph("InTurn"),
        )
        // Only the saga whose option is no constant is worth a warning.
        assertEquals(
            listOf(
                "app/src/main/kotlin/constructs/InTurn.kt:43: whether this saga compensates side by side cannot be told; " +
                    "its compensations are drawn one after the other",
            ),
            warnings.filter { "InTurn" in it },
        )
    }

    // ---- retries

    @Test
    fun `what is retried is a loop that says when it gives up, and nothing passes it by`() {
        assertEquals(
            listOf(
                "node activity@34: name Prepare, parent loop@33",
                "node activity@35: name Work, parent loop@33",
                "node activity@37: name Check, parent loop@37",
                "node activity@38: name Report",
                "node branch@38: label fine",
                "node end(main)",
                // The loop says that it is a retry, for whoever reads a history: its pauses are steps of the loop.
                "node loop@33: label retry on failure, up to 3 attempts, for up to 10m, retry true",
                // Without a number of attempts or a time allowed, it goes on until it succeeds.
                "node loop@37: label retry on failure, retry true",
                "node start@31",
                "edge activity@34 -> activity@35",
                "edge activity@38 -> end(main)",
                "edge branch@38 -> activity@38 [yes]",
                "edge branch@38 -> end(main) [no]",
                // The only way on is through what is retried.
                "edge loop@33 -> loop@37",
                "edge loop@37 -> branch@38",
                "edge start@31 -> loop@33",
            ),
            graph("Retries"),
        )
    }

    @Test
    fun `the warnings are those the tests above expect and no others`() {
        assertEquals(listOf("InTurn.kt:43", "Listeners.kt:76", "TwoConstructors.kt:33"), warnings.map { it.substringBefore(": ").substringAfterLast('/') })
    }

    private fun String.camel(): String =
        lowercase().split('_').mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar { it.uppercase() } }.joinToString("")
}
