package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.IrFiles
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.ir.EdgeKind
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
import kotlin.test.assertTrue

/**
 * What a workflow waits for and what it does not: a queue or a promise that a handler feeds,
 * work started beside the flow that nothing waits for, a child that is only waited for until it
 * has started, cancellation scopes and the places they are cancelled at, another workflow that
 * is asked to cancel, what is chained to a promise, a retry that runs beside the flow, and
 * children whose workflow type two interfaces share.
 *
 * Each test gives the whole graph of one workflow of [PatternSources], without ids: a node is
 * its kind and its line, as [IrStructure] writes it. The documents are kept as golden files as
 * well, for the tests of what is drawn from them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatternExtractionTest {
    private lateinit var backend: SourceBackend
    private lateinit var discovery: Discovery
    private lateinit var documents: List<IrDocument>
    private lateinit var warnings: List<String>

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val sources = SyntheticSources(directory)
        PatternSources.FILES.forEach { (name, text) -> sources.file(":app", "patterns/$name.kt", text) }
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

    /** The document of the class [implementation], which tells apart two workflows of one type. */
    private fun document(implementation: String): IrDocument = documents.single { it.implementation == "patterns.${implementation}Impl" }

    /** The nodes and edges of a workflow, without ids. */
    private fun graph(implementation: String): List<String> = whole(implementation).filterNot { it.startsWith("entry point ") }

    /** The entry points, nodes and edges of a workflow, without ids. */
    private fun whole(implementation: String): List<String> = document(implementation).outline()

    @Test
    fun `an overview keeps every step of the full document, and its golden file is there where it differs`() {
        assertEquals(emptyList<String>(), assertOverviewsKeepTheSteps(backend, discovery, "overview/patterns"))
    }

    @Test
    fun `every document is well formed and gives the golden IR`() {
        val expected = PatternSources.FILES.keys - PatternSources.SHARED - "Notices" +
            setOf("Courier", "Carrier", "Notices", "Notice--patterns.NotifyImpl", "Notice--patterns.RemindImpl")
        val names = IrFiles.names(documents).map { it.removeSuffix(IrFiles.SUFFIX) }
        assertEquals(expected, names.toSet())
        for ((document, name) in documents.zip(names)) {
            assertWellFormed(document)
            GoldenFiles.assertMatches("ir/patterns/$name.ir.json", IrJson.encode(document))
        }
        assertEquals(emptyList(), discovery.warnings)
    }

    @Test
    fun `reading everything an entry point reaches gives the graph that sparing what holds nothing gives`() {
        assertEquals(documents.sumOf { it.mainFlow().nodes.size }, assertSparingChangesNothing(backend, discovery))
    }

    @Test
    fun `nothing in these workflows is left unknown, and the one thing that cannot be told is said`() {
        assertEquals(emptyMap(), documents.associate { it.implementation to it.stats.unknown }.filterValues { it > 0 })
        assertEquals(
            listOf(
                "app/src/main/kotlin/patterns/SelfStop.kt:42: which cancellation scope this cancels cannot be told; " +
                    "the place is drawn without what it cancels",
            ),
            warnings,
        )
    }

    // ---- waits that handlers feed

    @Test
    fun `taking from a queue is an await that the handlers who fill the queue wake, and a time to wait for at most is a timeout`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Inbox, start start@38",
                "entry point signal-add: kind SIGNAL, name add, start start@50, writes items",
                "entry point update-push: kind UPDATE, name push, start start@54, writes items",
                "node activity@40: name Work",
                "node activity@42: name Work",
                "node activity@45: name Report",
                // What is waited on is the property that holds the queue.
                "node await@39: reads items, label items.take()",
                // With a time to wait for at most, the await has a timeout.
                "node await@41: timeout PT5M, reads items, label items.poll()",
                "node await@43: timeout PT30S, reads items, label items.cancellablePoll()",
                // The queue holds eight: the signal that puts a ninth in waits for room.
                "node await@51: reads items, label items.put()",
                "node end(main)",
                "node end(signal-add)",
                "node end(update-push)",
                "node start@38",
                "node start@50",
                "node start@54",
                "edge activity@40 -> await@41",
                "edge activity@42 -> await@43",
                "edge activity@45 -> end(main)",
                "edge await@39 -> activity@40",
                // `?: return`: when the time is up there is nothing, and the workflow returns.
                "edge await@41 -(timeout)-> end(main)",
                "edge await@41 -> activity@42",
                // `if (third == null)` asks the same of the last one.
                "edge await@43 -(timeout)-> activity@45",
                "edge await@43 -> end(main)",
                "edge await@51 -> end(signal-add)",
                // The workflow method takes from the queue, which makes the room the signal waits for.
                "edge entry point main -(wakes)-> await@51 [items]",
                // The signal puts into the queue and the update offers to it: both wake every wait for something to take.
                // Neither makes room, so neither ends the wait of the signal.
                "edge entry point signal-add -(wakes)-> await@39",
                "edge entry point signal-add -(wakes)-> await@41",
                "edge entry point signal-add -(wakes)-> await@43",
                "edge entry point update-push -(wakes)-> await@39",
                "edge entry point update-push -(wakes)-> await@41",
                "edge entry point update-push -(wakes)-> await@43",
                "edge start@38 -> await@39",
                "edge start@50 -> await@51",
                "edge start@54 -> end(update-push)",
            ),
            whole("Inbox"),
        )
    }

    @Test
    fun `waiting for a promise that the workflow completes itself is an await that the handlers who complete it wake`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Approval, start start@39",
                "entry point signal-approve: kind SIGNAL, name approve, start start@46, reads decision, writes decision, second",
                "entry point signal-reject: kind SIGNAL, name reject, start start@50, writes decision",
                "node activity@40: name Prepare",
                "node activity@42: name Work",
                "node await@41: reads decision, label decision.get()",
                // A promise that is declared as one the workflow completes, and waited for with a time limit.
                "node await@43: timeout PT1H, reads second, label second.cancellableGet()",
                "node end(main)",
                "node end(signal-approve)",
                "node end(signal-reject)",
                "node start@39",
                "node start@46",
                "node start@50",
                "edge activity@40 -> await@41",
                "edge activity@42 -> await@43",
                "edge await@41 -> activity@42",
                "edge await@43 -> end(main)",
                // One signal completes either promise, the other fails the first.
                "edge entry point signal-approve -(wakes)-> await@41",
                "edge entry point signal-approve -(wakes)-> await@43",
                "edge entry point signal-reject -(wakes)-> await@41",
                "edge start@39 -> activity@40",
                "edge start@46 -> end(signal-approve)",
                "edge start@50 -> end(signal-reject)",
            ),
            whole("Approval"),
        )
    }

    // ---- work that nothing waits for

    @Test
    fun `what is started and never waited for holds nothing up, and fails unseen`() {
        assertEquals(
            listOf(
                // The promise is dropped where it is made: a step that nothing waits for, and the flow goes on from it.
                "node activity@31: name Announce, waitsFor nothing",
                "node activity@33: name Load, parent parallel@32",
                "node activity@34: name Weigh, parent parallel@32",
                "node activity@35: name Audit, parent parallel@32",
                "node activity@37: name Check, parent parallel@37",
                // The promise is kept in a variable that nothing reads. It stands in the flow's own lane of the region around it.
                "node activity@38: name Prepare, waitsFor nothing, parent parallel@37",
                "node activity@39: name Work, parent parallel@37",
                // Inside a `try`: its failure reaches nobody, so no edge leads from it to the clause.
                "node activity@42: name Undo, waitsFor nothing",
                "node activity@43: name CleanUp",
                "node activity@45: name Report",
                "node branch@34: label tasks.weigh(id), parent parallel@32",
                "node end(main)",
                // More than one step that nothing waits for: a region that says so.
                "node parallel@32: waitsFor nothing",
                // This one is waited for, by `checked.get()`.
                "node parallel@37",
                "node start@30",
                "edge activity@31 -> parallel@32",
                "edge activity@33 -> activity@34",
                "edge activity@34 -> branch@34",
                "edge activity@38 -> activity@39",
                "edge activity@42 -> activity@43",
                "edge activity@43 -(exception)-> activity@45 [RuntimeException]",
                "edge activity@43 -> end(main)",
                "edge activity@45 -> end(main)",
                "edge branch@34 -> activity@35 [no]",
                // A `throw` in there ends the lane, and not the workflow.
                "edge branch@34 -> parallel@32 [yes]",
                // The flow goes on from the region at once.
                "edge parallel@32 -> parallel@37",
                "edge parallel@37 -> activity@42",
                "edge start@30 -> activity@31",
            ),
            graph("Unjoined"),
        )
    }

    @Test
    fun `a child that is waited for until it has started says so, and what becomes of it when its parent closes`() {
        assertEquals(
            listOf(
                // `Workflow.getWorkflowExecution(courier).get()` waits until the child has started, and no longer.
                "node childWorkflow@45: name Courier, waitsFor start, parentClosePolicy abandon",
                // Nothing waits for this one at all.
                "node childWorkflow@48: name Courier, waitsFor nothing",
                // This one is called and waited for as usual, and asked to cancel when its parent closes: it does not outlive it.
                "node childWorkflow@50: name Courier",
                "node end(main)",
                "node start@42",
                "edge childWorkflow@45 -> childWorkflow@48",
                "edge childWorkflow@48 -> childWorkflow@50",
                "edge childWorkflow@50 -> end(main)",
                "edge start@42 -> childWorkflow@45",
            ),
            graph("Detach"),
        )
    }

    // ---- cancellation

    @Test
    fun `what a scope started runs beside the steps that follow, until the scope is cancelled`() {
        assertEquals(
            listOf(
                // The scope starts it and does not wait: it runs beside the timer.
                "node activity@31: name Prepare, waitsFor nothing",
                "node activity@35: name Report",
                "node cancelScope@34: label scope",
                "node end(main)",
                "node start@30",
                "node timer@33: duration PT1M",
                "edge activity@31 -> timer@33",
                "edge activity@35 -> end(main)",
                // When the timer has fired the scope is cancelled, and with it the activity if it still runs.
                "edge cancelScope@34 -(cancels)-> activity@31",
                "edge cancelScope@34 -> activity@35",
                "edge start@30 -> activity@31",
                "edge timer@33 -> cancelScope@34",
            ),
            graph("CancelLater"),
        )
    }

    @Test
    fun `a scope that holds a loop of lanes cancels the loop for all it holds`() {
        assertEquals(
            listOf(
                "node activity@33: name Prepare, parent parallel@33",
                "node activity@38: name Report",
                "node cancelScope@37: label scope",
                "node end(main)",
                "node loop@33: label for each id",
                // The promises go into a list that is handed to `Promise.anyOf`: the lanes of all the rounds run a race.
                "node parallel@33: join first, parent loop@33",
                "node start@30",
                "edge activity@38 -> end(main)",
                // The scope holds the loop, and the lanes in it.
                "edge cancelScope@37 -(cancels)-> loop@33",
                "edge cancelScope@37 -> activity@38",
                "edge loop@33 -> cancelScope@37",
                "edge start@30 -> loop@33",
            ),
            graph("Race"),
        )
    }

    @Test
    fun `a handler that cancels the scope a property holds cancels the steps that scope runs in the workflow method`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Stoppable, start start@35",
                "entry point signal-stop: kind SIGNAL, name stop, start start@47",
                "node activity@38: name Prepare",
                "node activity@40: name Work",
                "node activity@43: name CleanUp",
                "node cancelScope@48: label scope",
                "node end(main)",
                "node end(signal-stop)",
                "node start@35",
                "node start@47",
                "node timer@39: duration PT1H",
                // The steps of the scope run in place, inside the `try`.
                "edge activity@38 -(exception)-> activity@43 [CanceledFailure]",
                "edge activity@38 -> timer@39",
                "edge activity@40 -(exception)-> activity@43 [CanceledFailure]",
                "edge activity@40 -> end(main)",
                "edge activity@43 -> end(main)",
                // The signal may arrive at any time: whichever step is running is cancelled.
                "edge cancelScope@48 -(cancels)-> activity@38",
                "edge cancelScope@48 -(cancels)-> activity@40",
                "edge cancelScope@48 -(cancels)-> timer@39",
                "edge cancelScope@48 -> end(signal-stop)",
                "edge start@35 -> activity@38",
                "edge start@47 -> cancelScope@48",
                "edge timer@39 -(exception)-> activity@43 [CanceledFailure]",
                "edge timer@39 -> activity@40",
            ),
            whole("Stoppable"),
        )
        // The edges lead from the graph of the handler into that of the workflow method.
        val document = document("Stoppable")
        val owners = IrStructure.of(document).owners
        val cancels = document.edges.filter { it.kind == EdgeKind.CANCELS }
        assertEquals(setOf("signal-stop"), cancels.map { owners[it.from] }.toSet())
        assertEquals(setOf("main"), cancels.map { owners[it.to] }.toSet())
    }

    @Test
    fun `a scope can cancel itself, and a step the flow has waited for is not among what is cancelled`() {
        assertEquals(
            listOf(
                "node activity@32: name Work, waitsFor nothing",
                "node activity@33: name Check",
                "node activity@36: name Prepare, waitsFor nothing",
                // The scope is one of a list: which one `scopes.first()` is cannot be told.
                "node activity@40: name Report, waitsFor nothing",
                "node branch@33: label !tasks.check(id)",
                "node cancelScope@33: label current scope",
                "node cancelScope@38: label own",
                "node cancelScope@42: label scopes.first()",
                // Outside every scope the workflow makes, the current one is that of the workflow itself.
                "node cancelScope@43: label current scope",
                "node end(main)",
                "node start@30",
                "node timer@37: duration PT5S",
                "edge activity@32 -> activity@33",
                "edge activity@33 -> branch@33",
                "edge activity@36 -> timer@37",
                "edge activity@40 -> cancelScope@42",
                "edge branch@33 -> activity@36 [no]",
                "edge branch@33 -> cancelScope@33 [yes]",
                // `CancellationScope.current()` is the scope the code runs in. The check before it is over by then.
                "edge cancelScope@33 -(cancels)-> activity@32",
                "edge cancelScope@33 -> activity@36",
                // The scope a lambda is handed is its own. The timer before it has fired by then.
                "edge cancelScope@38 -(cancels)-> activity@36",
                "edge cancelScope@38 -> activity@40",
                "edge cancelScope@42 -> cancelScope@43",
                "edge cancelScope@43 -> end(main)",
                "edge start@30 -> activity@32",
                "edge timer@37 -> cancelScope@38",
            ),
            graph("SelfStop"),
        )
    }

    @Test
    fun `asking another workflow to cancel is a step, of the workflow type of the stub where that is known`() {
        assertEquals(
            listOf(
                // A stub made of an id alone says nothing of the workflow type.
                "node cancelExternal@43",
                // One made from a stub of a workflow interface is of that workflow type.
                "node cancelExternal@45: name Carrier",
                "node end(main)",
                "node start@42",
                "edge cancelExternal@43 -> cancelExternal@45",
                "edge cancelExternal@45 -> end(main)",
                "edge start@42 -> cancelExternal@43",
            ),
            graph("CallOff"),
        )
    }

    // ---- what is chained to a promise, and what is tried again beside the flow

    @Test
    fun `what is chained to a promise follows the step the promise stands for, in its lane`() {
        assertEquals(
            listOf(
                "node activity@31#1: name CleanUp, parent parallel@31",
                "node activity@31#2: name Report, parent parallel@31",
                "node activity@32: name Prepare, parent parallel@32",
                "node activity@33: name Work, parent parallel@32",
                "node activity@34: name Undo, parent parallel@32",
                "node activity@35: name Check, parent parallel@32",
                "node activity@36: name Prepare, parent parallel@32",
                "node activity@37: name Report, parent parallel@32",
                "node branch@37: label failure != null, parent parallel@32",
                "node end(main)",
                // A chain whose promise is dropped: nothing waits for either step.
                "node parallel@31: waitsFor nothing",
                "node parallel@32",
                "node start@30",
                // `thenApply`: after the step, if it succeeds.
                "edge activity@31#2 -> activity@31#1",
                // `exceptionally`: if what came before fails, either step of it.
                "edge activity@32 -(exception)-> activity@34 [Throwable]",
                "edge activity@32 -> activity@33",
                "edge activity@33 -(exception)-> activity@34 [Throwable]",
                // If nothing fails, the lane ends after the second step.
                "edge activity@33 -> parallel@32",
                // `handle`: after the steps, however they ended.
                "edge activity@35 -(exception)-> branch@37 [RuntimeException]",
                // `thenCompose`: the promise it returns is waited for in turn, so its step follows in the lane.
                "edge activity@35 -> activity@36",
                "edge activity@36 -(exception)-> branch@37 [RuntimeException]",
                "edge activity@36 -> branch@37",
                "edge branch@37 -> activity@37 [yes]",
                "edge branch@37 -> parallel@32 [no]",
                "edge parallel@31 -> parallel@32",
                "edge parallel@32 -> end(main)",
                "edge start@30 -> parallel@31",
            ),
            graph("Chains"),
        )
        assertTrue(document("Chains").nodes.none { it.kind == NodeKind.UNKNOWN })
    }

    @Test
    fun `a retry beside the flow is a lane that holds a loop called what it is`() {
        assertEquals(
            listOf(
                "node activity@32: name Prepare, parent loop@32",
                // What the flow does before it waits for the retry.
                "node activity@33: name Check, parent parallel@32",
                "node activity@36#1: name Report, parent loop@35",
                "node activity@36#2: name Work, parent loop@35",
                "node end(main)",
                // The promise the lambda returns is waited for by the retry: its step is the body of the loop.
                "node loop@32: label retry on failure, up to 3 attempts, for up to 10m, retry true, parent parallel@32",
                "node loop@35: label retry on failure, retry true, parent parallel@35",
                "node parallel@32",
                "node parallel@35",
                "node start@30",
                // A chain inside the lambda is part of every attempt.
                "edge activity@36#2 -> activity@36#1",
                "edge parallel@32 -> parallel@35",
                "edge parallel@35 -> end(main)",
                "edge start@30 -> parallel@32",
            ),
            graph("AsyncRetries"),
        )
    }

    // ---- children of a workflow type that two interfaces share

    @Test
    fun `a child names the interface of its stub where its workflow type does not say which workflow it is`() {
        assertEquals(
            listOf(
                // Two interfaces register the type `Notice`.
                "node childWorkflow@56: name Notice, interface patterns.Notify",
                "node childWorkflow@57: name Notice, interface patterns.Remind",
                // One interface registers `Courier`: the type says it.
                "node childWorkflow@58: name Courier",
                "node end(main)",
                "node start@55",
                "edge childWorkflow@56 -> childWorkflow@57",
                "edge childWorkflow@57 -> childWorkflow@58",
                "edge childWorkflow@58 -> end(main)",
                "edge start@55 -> childWorkflow@56",
            ),
            graph("Notices"),
        )
        // The interface is that of one document, and of no other.
        val children = document("Notices").nodes.filter { it.kind == NodeKind.CHILD_WORKFLOW }
        assertEquals(listOf("patterns.NotifyImpl", "patterns.RemindImpl"), children.mapNotNull { it.interfaceName }.map { stub -> documents.single { it.interfaceName == stub }.implementation })
        // Nowhere else is it written: the type says it.
        assertEquals(2, documents.sumOf { document -> document.nodes.count { it.interfaceName != null } })
    }
}
