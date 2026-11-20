package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.IrFiles
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.Join
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
 * What the flow waits for and how far: races, steps that a call gives a promise for, a promise
 * that is waited for a time at most, scope functions whose value is dropped, waits for room in
 * a queue, on what a listener feeds and on what the workflow method lets go of itself, a child
 * whose start is waited for from outside its region, and upserts that name some of what they
 * write.
 *
 * Each test gives the graph of one workflow of [OpenSources] without ids: a node is its kind
 * and its line, as [IrStructure] writes it. The documents are kept as golden files as well,
 * for the tests of what is drawn from them and of how histories are laid over them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenExtractionTest {
    private lateinit var backend: SourceBackend
    private lateinit var discovery: Discovery
    private lateinit var documents: List<IrDocument>
    private lateinit var warnings: List<String>

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val sources = SyntheticSources(directory)
        OpenSources.FILES.forEach { (name, text) -> sources.file(":app", "open/$name.kt", text) }
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

    private fun document(implementation: String): IrDocument = documents.single { it.implementation == "open.${implementation}Impl" }

    /** The entry points, nodes and edges of a workflow, without ids. */
    private fun whole(implementation: String): List<String> = document(implementation).outline()

    @Test
    fun `an overview keeps every step of the full document, and its golden file is there where it differs`() {
        assertEquals(emptyList<String>(), assertOverviewsKeepTheSteps(backend, discovery, "overview/open"))
    }

    @Test
    fun `every document is well formed and gives the golden IR`() {
        val names = IrFiles.names(documents).map { it.removeSuffix(IrFiles.SUFFIX) }
        assertEquals(OpenSources.FILES.keys - OpenSources.SHARED, names.toSet())
        for ((document, name) in documents.zip(names)) {
            assertWellFormed(document)
            GoldenFiles.assertMatches("ir/open/$name.ir.json", IrJson.encode(document))
        }
        assertEquals(emptyList(), discovery.warnings)
    }

    @Test
    fun `reading everything an entry point reaches gives the graph that sparing what holds nothing gives`() {
        assertEquals(documents.sumOf { it.mainFlow().nodes.size }, assertSparingChangesNothing(backend, discovery))
    }

    @Test
    fun `nothing in these workflows is left unknown, and nothing in them needs a warning`() {
        assertEquals(emptyMap(), documents.associate { it.implementation to it.stats.unknown }.filterValues { it > 0 })
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `the new fields are written where they apply and nowhere else`() {
        val nodes = documents.flatMap { it.nodes }
        // Only a parallel region says which of its lanes is waited for, and only an upsert with names that it writes more.
        assertTrue(nodes.filter { it.join != null }.all { it.kind == NodeKind.PARALLEL && it.join == Join.FIRST && it.waitsFor == null })
        assertEquals(4, nodes.count { it.join != null })
        assertTrue(nodes.filter { it.otherKeys }.all { it.kind.isUpsert && it.keys.isNotEmpty() })
        assertEquals(3, nodes.count { it.otherKeys })
        // An edge that starts at the workflow method wakes, and says what it is about; no other edge of a wait is labelled.
        val fromMain = documents.flatMap { it.edges }.filter { it.from == WorkflowGraphs.MAIN }
        assertTrue(fromMain.all { it.kind == EdgeKind.WAKES && !it.label.isNullOrEmpty() })
        assertEquals(4, fromMain.size)
        assertTrue(documents.flatMap { it.edges }.filter { it.kind == EdgeKind.WAKES && it.from != WorkflowGraphs.MAIN }.all { it.label == null })
    }

    // ---- races

    @Test
    fun `the lanes handed to Promise anyOf together are a region that is left when the first of them ends`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Fastest, start start@27",
                "node activity@28: name Fetch, parent parallel@28",
                "node activity@29: name Store, parent parallel@28",
                "node activity@31: name Notify",
                "node activity@32: name Fetch, parent parallel@32",
                "node activity@33: name Store, parent parallel@32",
                "node end(main)",
                // The flow waits for the first of the two, and no longer.
                "node parallel@28: join first",
                // The first of these is waited for on its own before the two are handed over: that is no race.
                "node parallel@32",
                "node start@27",
                "edge activity@31 -> parallel@32",
                "edge parallel@28 -> activity@31",
                "edge parallel@32 -> end(main)",
                "edge start@27 -> parallel@28",
            ),
            whole("Fastest"),
        )
    }

    @Test
    fun `a scope around the lanes of a race cancels the region, and with it whichever lanes still run`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Winner, start start@27",
                "node activity@30: name Fetch, parent parallel@30",
                "node activity@33: name Store, parent parallel@30",
                "node activity@39: name Store",
                "node cancelScope@38: label scope",
                "node end(main)",
                // The promises are put into a list, and the list is what is waited on.
                "node parallel@30: join first",
                "node start@27",
                "node timer@32: duration PT10S, parent parallel@30",
                "edge activity@39 -> end(main)",
                // The scope holds the region: cancelling it cancels the lanes that lost.
                "edge cancelScope@38 -(cancels)-> parallel@30",
                "edge cancelScope@38 -> activity@39",
                "edge parallel@30 -> cancelScope@38",
                "edge start@27 -> parallel@30",
                "edge timer@32 -> activity@33",
            ),
            whole("Winner"),
        )
    }

    @Test
    fun `a lane for each of many, of which the first is waited for, is a race inside the loop that starts them`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Soonest, start start@27",
                "node activity@28: name Fetch, parent parallel@28",
                "node activity@30: name Store",
                "node end(main)",
                "node loop@28: label for each of ids",
                // One lane in each round: the lanes of all the rounds run the race.
                "node parallel@28: join first, parent loop@28",
                "node start@27",
                "edge activity@30 -> end(main)",
                "edge loop@28 -> activity@30",
                "edge start@27 -> loop@28",
            ),
            whole("Soonest"),
        )
    }

    @Test
    fun `a timer that is given a promise for is a lane, and can lose a race`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Deadline, start start@27",
                "node activity@28: name Fetch, parent parallel@28",
                "node activity@32: name Notify",
                "node branch@31: label !work.isCompleted",
                "node end(main)",
                "node parallel@28: join first",
                "node start@27",
                // `Workflow.newTimer` starts the timer and goes on.
                "node timer@29: duration PT1M, parent parallel@28",
                "edge activity@32 -> end(main)",
                "edge branch@31 -> activity@32 [yes]",
                "edge branch@31 -> end(main) [no]",
                "edge parallel@28 -> branch@31",
                "edge start@27 -> parallel@28",
            ),
            whole("Deadline"),
        )
    }

    // ---- promises of calls that start a step

    @Test
    fun `what a stub without an interface is asked to start runs beside the flow until its promise is waited for`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Handles, start start@29",
                // Two that are started one after the other and waited for together are lanes.
                "node activity@30: name Fetch, timeout PT5S, parent parallel@30",
                "node activity@31: name Store, timeout PT5S, parent parallel@30",
                // A promise that is dropped is waited for by nobody.
                "node activity@33: name Notify, timeout PT5S, waitsFor nothing",
                // What the flow does itself until it waits for the child is the other lane.
                "node activity@39: name Notify, timeout PT5S, parent parallel@38",
                // A promise that is waited for where it is made is a step the flow waits for there, as before.
                "node activity@41: name Fetch, timeout PT5S",
                // `errand.execution.get()` waits until the child has started, and no longer.
                "node childWorkflow@35: name Errand, waitsFor start, parentClosePolicy abandon",
                "node childWorkflow@38: name Errand, parent parallel@38",
                "node end(main)",
                "node parallel@30",
                "node parallel@38",
                "node start@29",
                "edge activity@33 -> childWorkflow@35",
                "edge activity@41 -> end(main)",
                "edge childWorkflow@35 -> parallel@38",
                "edge parallel@30 -> activity@33",
                "edge parallel@38 -> activity@41",
                "edge start@29 -> parallel@30",
            ),
            whole("Handles"),
        )
    }

    @Test
    fun `a timer and a condition that are given promises for are lanes, and what is chained to them follows them`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Pauses, start start@32",
                "entry point signal-release: kind SIGNAL, name release, start start@45, writes released",
                "node activity@35: name Fetch, parent parallel@33",
                "node activity@37: name Store, parent parallel@37",
                "node activity@38: name Notify, parent parallel@37",
                // `Async.await` is the wait of `Workflow.await`, beside the flow.
                "node await@34: reads released, label released, parent parallel@33",
                "node await@38: timeout PT1H, reads released, label released, parent parallel@37",
                "node end(main)",
                "node end(signal-release)",
                "node parallel@33",
                "node parallel@37",
                "node start@32",
                "node start@45",
                "node timer@33: duration PT5M, parent parallel@33",
                "node timer@37: duration PT1M, parent parallel@37",
                // `Workflow.newTimer(…).get()` is the sleep it always was.
                "node timer@40: duration PT30S",
                // A timer nobody waits for.
                "node timer@41: duration PT24H, waitsFor nothing",
                // And what is chained to the promise of a condition, when the wait for it is over.
                "edge await@38 -> activity@38",
                // The signal ends both waits, wherever they stand.
                "edge entry point signal-release -(wakes)-> await@34",
                "edge entry point signal-release -(wakes)-> await@38",
                "edge parallel@33 -> parallel@37",
                "edge parallel@37 -> timer@40",
                "edge start@32 -> parallel@33",
                "edge start@45 -> end(signal-release)",
                // What is chained to the promise of a timer runs when the timer has fired.
                "edge timer@37 -> activity@37",
                "edge timer@40 -> timer@41",
                "edge timer@41 -> end(main)",
            ),
            whole("Pauses"),
        )
    }

    // ---- a promise waited for a time at most

    @Test
    fun `a promise that is waited for a time at most leaves on its timeout for the clause that catches the failure`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Patience, start start@32",
                "entry point signal-approve: kind SIGNAL, name approve, start start@48, writes approval",
                "node activity@36: name Notify",
                "node activity@40: name Store",
                "node activity@43: name Audit",
                "node activity@45: name Fetch",
                "node await@34: timeout PT30S, reads approval, label approval.get()",
                // Nothing catches the failure of this one: its timeout has no way to take.
                "node await@37: timeout PT1H, reads approval, label approval.cancellableGet()",
                "node await@41: timeout PT5M, reads approval, label approval.get()",
                "node end(main)",
                "node end(signal-approve)",
                "node start@32",
                "node start@48",
                "edge activity@36 -> await@37",
                "edge activity@40 -(exception)-> activity@43 [Exception]",
                "edge activity@40 -> await@41",
                "edge activity@43 -> activity@45",
                "edge activity@45 -> end(main)",
                // `catch (e: TimeoutException)`: when the time is up, the clause takes over.
                "edge await@34 -(timeout)-> activity@36",
                "edge await@34 -> activity@40",
                "edge await@37 -> activity@40",
                "edge await@41 -(exception)-> activity@43 [Exception]",
                // A clause for every exception catches it as well.
                "edge await@41 -(timeout)-> activity@43",
                "edge await@41 -> activity@45",
                "edge entry point signal-approve -(wakes)-> await@34",
                "edge entry point signal-approve -(wakes)-> await@37",
                "edge entry point signal-approve -(wakes)-> await@41",
                "edge start@32 -> await@34",
                "edge start@48 -> end(signal-approve)",
            ),
            whole("Patience"),
        )
    }

    // ---- scope functions

    @Test
    fun `what a scope function starts and gives back to nobody is not waited for`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Dropped, start start@27",
                // `run { … }`, `let { … }` and `with(…) { … }` as statements: what their lambda ends with is dropped.
                "node activity@28: name Notify, waitsFor nothing",
                "node activity@29: name Fetch, waitsFor nothing",
                "node activity@30: name Store, waitsFor nothing",
                // `also` gives back what it is called on, which is dropped here as well.
                "node activity@31: name Fetch, waitsFor nothing",
                "node activity@34: name Fetch, parent parallel@33",
                "node activity@35: name Notify, parent parallel@33",
                "node activity@38: name Store, parent parallel@38",
                "node end(main)",
                // `?.let { … }` is no different.
                "node parallel@33: waitsFor nothing",
                // A promise that `run` hands on to a variable is kept, and waited for.
                "node parallel@38",
                "node start@27",
                "edge activity@28 -> activity@29",
                "edge activity@29 -> activity@30",
                "edge activity@30 -> activity@31",
                "edge activity@31 -> parallel@33",
                "edge activity@34 -> activity@35",
                "edge parallel@33 -> parallel@38",
                "edge parallel@38 -> end(main)",
                "edge start@27 -> activity@28",
            ),
            whole("Dropped"),
        )
    }

    // ---- more waits

    @Test
    fun `putting into a queue that is full is an await that whoever takes something out wakes`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Buffer, start start@36",
                "entry point signal-add: kind SIGNAL, name add, start start@48, writes items",
                "entry point signal-drain: kind SIGNAL, name drain, start start@52, writes items",
                "node activity@39: name Fetch",
                "node activity@45: name Store",
                // The queue holds two: the third waits for room.
                "node await@37: reads items, label items.put()",
                // With a time to wait for at most, the await has a timeout.
                "node await@38: timeout PT10S, reads items, label items.offer()",
                "node await@41: reads items, label items.cancellablePut()",
                "node await@42: timeout PT5S, reads items, label items.cancellableOffer()",
                // A handler that puts something in waits like the workflow method.
                "node await@49: reads items, label items.put()",
                "node end(main)",
                "node end(signal-add)",
                "node end(signal-drain)",
                "node start@36",
                "node start@48",
                "node start@52",
                "edge activity@39 -> end(main)",
                "edge activity@45 -> end(main)",
                "edge await@37 -> await@38",
                // `if (!items.offer(…))`: when the time is up the answer is no.
                "edge await@38 -(timeout)-> activity@39",
                "edge await@38 -> await@41",
                "edge await@41 -> await@42",
                "edge await@42 -> activity@45",
                "edge await@49 -> end(signal-add)",
                // Only the signal that takes something out makes room. The one that puts something in ends no such wait, its own
                // included.
                "edge entry point signal-drain -(wakes)-> await@37",
                "edge entry point signal-drain -(wakes)-> await@38",
                "edge entry point signal-drain -(wakes)-> await@41",
                "edge entry point signal-drain -(wakes)-> await@42",
                "edge entry point signal-drain -(wakes)-> await@49",
                "edge start@36 -> await@37",
                "edge start@48 -> await@49",
                "edge start@52 -> end(signal-drain)",
            ),
            whole("Buffer"),
        )
    }

    @Test
    fun `a queue and a promise in variables of the workflow method are waited on by name, and the handler it registers wakes the waits`() {
        assertEquals(
            listOf(
                // The handler is a lambda that sees the variables of the function it is written in.
                "entry point dynamic-signal: kind SIGNAL, name (any name), start start@30, writes done, items",
                "entry point main: kind WORKFLOW_METHOD, name Listener, start start@27",
                "node activity@34: name Store",
                "node await@33: reads items, label items.take()",
                "node await@35: reads done, label done.get()",
                "node end(dynamic-signal)",
                "node end(main)",
                "node start@27",
                "node start@30",
                "edge activity@34 -> await@35",
                "edge await@33 -> activity@34",
                "edge await@35 -> end(main)",
                "edge entry point dynamic-signal -(wakes)-> await@33",
                "edge entry point dynamic-signal -(wakes)-> await@35",
                "edge start@27 -> await@33",
                "edge start@30 -> end(dynamic-signal)",
            ),
            whole("Listener"),
        )
    }

    @Test
    fun `the workflow method wakes the waits that its own code ends from another lane, and those of its handlers`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Permits, start start@34",
                "entry point update-change: kind UPDATE, name change, start start@54, reads lock, ready, writes lock",
                "node activity@38: name Fetch, parent parallel@36",
                "node activity@43: name Store, parent parallel@36",
                "node activity@50: name Notify",
                "node activity@58: name Audit",
                "node await@35: reads lock, label lock.lock()",
                "node await@37: reads permits, label permits.acquire(), parent parallel@36",
                "node await@42: reads permits, label permits.acquire(), parent parallel@36",
                // Both lanes are over by the time the flow asks for the permit itself: nothing of its own code ends that wait.
                "node await@49: reads permits, label permits.acquire()",
                "node await@55: reads ready, label ready",
                "node await@56: reads lock, label lock.lock()",
                "node end(main)",
                "node end(update-change)",
                "node parallel@36",
                "node start@34",
                "node start@54",
                "edge activity@50 -> end(main)",
                "edge activity@58 -> end(update-change)",
                "edge await@35 -> parallel@36",
                "edge await@37 -> activity@38",
                "edge await@42 -> activity@43",
                "edge await@49 -> activity@50",
                "edge await@55 -> await@56",
                "edge await@56 -> activity@58",
                // Each lane lets go of the permit the other waits for. The edge starts at the workflow method and says what it is
                // about.
                "edge entry point main -(wakes)-> await@37 [permits]",
                "edge entry point main -(wakes)-> await@42 [permits]",
                // The update waits for what the workflow method sets, and for the lock it holds.
                "edge entry point main -(wakes)-> await@55 [ready]",
                "edge entry point main -(wakes)-> await@56 [lock]",
                // And the other way round: the update lets go of the lock the workflow method waits for.
                "edge entry point update-change -(wakes)-> await@35",
                "edge entry point update-change -(wakes)-> await@56",
                "edge parallel@36 -> await@49",
                "edge start@34 -> await@35",
                "edge start@54 -> await@55",
            ),
            whole("Permits"),
        )
    }

    // ---- start-only waits inside a region

    @Test
    fun `a child that a lane runs and whose start the flow outside waits for is marked, and so is its region`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Begun, start start@27",
                "node activity@30: name Fetch, parent parallel@29",
                "node activity@32: name Notify, parent parallel@29",
                "node activity@35: name Store",
                "node activity@38: name Fetch, parent parallel@37",
                // The lane waits for the child to end, like for any of its steps: the notice follows it.
                "node childWorkflow@31: name Errand, waitsFor start, parentClosePolicy abandon, parent parallel@29",
                // Here the lane itself waits for the start, after the child is done: that says nothing of the flow outside.
                "node childWorkflow@39: name Errand, parent parallel@37",
                "node end(main)",
                // The flow goes on once the child in the region has started.
                "node parallel@29: waitsFor start",
                "node parallel@37: waitsFor nothing",
                "node start@27",
                "edge activity@30 -> childWorkflow@31",
                "edge activity@35 -> parallel@37",
                "edge activity@38 -> childWorkflow@39",
                "edge childWorkflow@31 -> activity@32",
                "edge parallel@29 -> activity@35",
                "edge parallel@37 -> end(main)",
                "edge start@27 -> parallel@29",
            ),
            whole("Begun"),
        )
    }

    // ---- upserts

    @Test
    fun `an upsert that names some of what it writes says which, and that there is more`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Tags, start start@30",
                "node activity@35: name Notify",
                "node end(main)",
                "node start@30",
                // A map put together with `+` has what each part has.
                "node upsertMemo@34: keys note, by, otherKeys true",
                // Where no name is known there is nothing to say more of.
                "node upsertMemo@36",
                // One update is made where the code can be read, the other comes out of a branch.
                "node upsertSearchAttributes@32: keys Stage, otherKeys true",
                // A map with a key that is no constant.
                "node upsertSearchAttributes@33: keys Stage, Owner, otherKeys true",
                // Every name known: the list is whole.
                "node upsertSearchAttributes@37: keys Stage, Rank",
                "edge activity@35 -> upsertMemo@36",
                "edge start@30 -> upsertSearchAttributes@32",
                "edge upsertMemo@34 -> activity@35",
                "edge upsertMemo@36 -> upsertSearchAttributes@37",
                "edge upsertSearchAttributes@32 -> upsertSearchAttributes@33",
                "edge upsertSearchAttributes@33 -> upsertMemo@34",
                "edge upsertSearchAttributes@37 -> end(main)",
            ),
            whole("Tags"),
        )
    }
}
