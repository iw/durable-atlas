package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.walk
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.IrFiles
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a history records, or what holds the flow up, without a call that looks like a step:
 * waits for a workflow lock or semaphore, search attributes and memo entries that are set,
 * options for activities that are set somewhere other than at the stub, and activities that
 * are run by handing the call of a stub to `Workflow.executeActivity`.
 *
 * Each test gives the graph of one workflow of [SilentSources], or the steps of it that the
 * test is about, without ids: a node is its kind and its line, as [IrStructure] writes it. The
 * documents are kept as golden files as well, for the tests of what is drawn from them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SilentExtractionTest {
    private lateinit var backend: SourceBackend
    private lateinit var discovery: Discovery
    private lateinit var documents: List<IrDocument>
    private lateinit var warnings: List<String>

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val sources = SyntheticSources(directory)
        SilentSources.FILES.forEach { (name, text) -> sources.file(":app", "silent/$name.kt", text) }
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

    private fun document(implementation: String): IrDocument = documents.single { it.implementation == "silent.${implementation}Impl" }

    /** The entry points, nodes and edges of a workflow, without ids. */
    private fun whole(implementation: String): List<String> = document(implementation).outline()

    /** The activities of a workflow, with what their options give them, without ids. */
    private fun activities(implementation: String): List<String> =
        whole(implementation).filter { it.startsWith("node activity") || it.startsWith("node localActivity") }

    @Test
    fun `an overview keeps every step of the full document, and its golden file is there where it differs`() {
        assertEquals(emptyList<String>(), assertOverviewsKeepTheSteps(backend, discovery, "overview/silent"))
    }

    @Test
    fun `every document is well formed and gives the golden IR`() {
        val expected = SilentSources.FILES.keys - SilentSources.SHARED + "Twice"
        val names = IrFiles.names(documents).map { it.removeSuffix(IrFiles.SUFFIX) }
        assertEquals(expected, names.toSet())
        for ((document, name) in documents.zip(names)) {
            assertWellFormed(document)
            GoldenFiles.assertMatches("ir/silent/$name.ir.json", IrJson.encode(document))
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

    // ---- locks and semaphores

    @Test
    fun `waiting for a lock is an await that whoever lets go of it wakes, also another run of the handler that waits`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Guarded, start start@37",
                "entry point signal-release: kind SIGNAL, name release, start start@57, reads lock, writes lock",
                "entry point update-change: kind UPDATE, name change, start start@41, reads lock, writes lock",
                "entry point update-tryChange: kind UPDATE, name tryChange, start start@50, reads lock, writes lock",
                "node activity@44: name Fetch",
                "node activity@52: name Store",
                "node await@38: label isEveryHandlerFinished()",
                // What is waited on is the property that holds the lock.
                "node await@42: reads lock, label lock.lock()",
                // With a time to wait for at most, the await has a timeout.
                "node await@51: timeout PT30S, reads lock, label lock.tryLock()",
                "node end(main)",
                "node end(signal-release)",
                "node end(update-change)",
                "node end(update-tryChange)",
                "node start@37",
                "node start@41",
                "node start@50",
                "node start@57",
                "edge activity@44 -> end(update-change)",
                "edge activity@52 -> end(update-tryChange)",
                "edge await@38 -> end(main)",
                "edge await@42 -> activity@44",
                // `if (!lock.tryLock(…)) return`: when the time is up the answer is no, and the update returns.
                "edge await@51 -(timeout)-> end(update-tryChange)",
                "edge await@51 -> activity@52",
                // Whoever lets go of the lock ends every wait for it: the signal, and each update for the runs of both that wait.
                // Asking for the lock without a time, as `tryChange` does at its end, is no wait and no node.
                "edge entry point signal-release -(wakes)-> await@42",
                "edge entry point signal-release -(wakes)-> await@51",
                "edge entry point update-change -(wakes)-> await@42",
                "edge entry point update-change -(wakes)-> await@51",
                "edge entry point update-tryChange -(wakes)-> await@42",
                "edge entry point update-tryChange -(wakes)-> await@51",
                "edge start@37 -> await@38",
                "edge start@41 -> await@42",
                "edge start@50 -> await@51",
                "edge start@57 -> end(signal-release)",
            ),
            whole("Guarded"),
        )
    }

    @Test
    fun `waiting for a semaphore is an await, a time to wait for at most is its timeout, and asking without one is no wait`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Pool, start start@31",
                "entry point signal-grant: kind SIGNAL, name grant, start start@47, writes permits",
                "node activity@34: name Fetch, parent loop@32",
                "node activity@39: name Store",
                "node activity@41: name Notify",
                "node activity@43: name Notify",
                // Each round waits for a permit before its step.
                "node await@33: reads permits, label permits.acquire(), parent loop@32",
                "node await@37: timeout PT1M, reads permits, label permits.tryAcquire()",
                "node await@44: reads permits, label permits.acquire()",
                // Asking without a time is no wait: the test of its answer is an ordinary branch.
                "node branch@43: label permits.tryAcquire()",
                "node end(main)",
                "node end(signal-grant)",
                "node loop@32: label for each id",
                "node start@31",
                "node start@47",
                "edge activity@39 -> branch@43",
                "edge activity@41 -> branch@43",
                "edge activity@43 -> await@44",
                "edge await@33 -> activity@34",
                // `if (both) … else …` asks whether the permits were got in time.
                "edge await@37 -(timeout)-> activity@41",
                "edge await@37 -> activity@39",
                "edge await@44 -> end(main)",
                "edge branch@43 -> activity@43 [yes]",
                "edge branch@43 -> await@44 [no]",
                // The signal gives a permit back. That the workflow method does so itself, at the end of each round, is no edge.
                "edge entry point signal-grant -(wakes)-> await@33",
                "edge entry point signal-grant -(wakes)-> await@37",
                "edge entry point signal-grant -(wakes)-> await@44",
                "edge loop@32 -> await@37",
                "edge start@31 -> loop@32",
                "edge start@47 -> end(signal-grant)",
            ),
            whole("Pool"),
        )
    }

    // ---- search attributes and the memo

    @Test
    fun `setting search attributes or memo entries is a step that names what it writes where the code does`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Labels, start start@30",
                "node activity@33: name Fetch",
                "node end(main)",
                "node start@30",
                // A memo written with `mapOf`, and one with `Map.of`.
                "node upsertMemo@32: keys note",
                "node upsertMemo@36: keys note, by",
                // A map that is handed in names nothing here.
                "node upsertMemo@38",
                // Keys held in a property of the file and in one of the class; one that is set and one that is removed.
                "node upsertSearchAttributes@31: keys Stage, Attempts",
                "node upsertSearchAttributes@34: keys Stage, Attempts",
                // The older call takes a map.
                "node upsertSearchAttributes@35: keys Stage, Owner",
                "node upsertSearchAttributes@37",
                // A key whose name is worked out as the workflow runs.
                "node upsertSearchAttributes@39",
                "edge activity@33 -> upsertSearchAttributes@34",
                "edge start@30 -> upsertSearchAttributes@31",
                "edge upsertMemo@32 -> activity@33",
                "edge upsertMemo@36 -> upsertSearchAttributes@37",
                "edge upsertMemo@38 -> upsertSearchAttributes@39",
                "edge upsertSearchAttributes@31 -> upsertMemo@32",
                "edge upsertSearchAttributes@34 -> upsertSearchAttributes@35",
                "edge upsertSearchAttributes@35 -> upsertMemo@36",
                "edge upsertSearchAttributes@37 -> upsertMemo@38",
                "edge upsertSearchAttributes@39 -> end(main)",
            ),
            whole("Labels"),
        )
    }

    // ---- options for activities that are set elsewhere

    @Test
    fun `a default the workflow sets is what the stubs made after it are given`() {
        assertEquals(
            listOf(
                "node activity@31: name Fetch, timeout PT5S",
                // The second default is laid over the first: a stub made after it has the task queue as well.
                "node activity@34: name Notify, taskQueue other, timeout PT5S",
                // The stub made before it still has what it was given then.
                "node activity@35: name Notify, timeout PT5S",
                "node localActivity@32: name Store, timeout PT2S",
            ),
            activities("Defaults"),
        )
    }

    @Test
    fun `options for an activity type lie over those of a stub from the moment they are set`() {
        assertEquals(
            listOf(
                // Before anything is set for its type, an activity has the options of its stub.
                "node activity@30: name Fetch, timeout PT5S",
                // What is set for the type lies over them: the timeout is now the one that ends the activity as a whole.
                "node activity@32: name Fetch, taskQueue slow, timeout PT2M",
                "node activity@33: name Store, timeout PT5S",
                // `setActivityOptions` is the older name of the same call.
                "node activity@35: name Store, timeout PT9S",
                // Local activities have options of their own.
                "node localActivity@37: name Notify, timeout PT8S",
                "node localActivity@38: name Fetch, timeout PT2S",
            ),
            activities("PerType"),
        )
    }

    @Test
    fun `a stub made with options for single activity types does not look at what the workflow sets for them`() {
        assertEquals(
            listOf(
                // What the workflow applies for `Fetch` does not reach a stub that has a map of its own.
                "node activity@35: name Fetch, timeout PT5S",
                // The map of the stub lies over its options.
                "node activity@36: name Store, taskQueue slow, timeout PT2M",
                // A map that is handed in may hold anything for the type: nothing is said.
                "node activity@39: name Fetch",
                "node localActivity@37: name Store, timeout PT4S",
            ),
            activities("StubMaps"),
        )
    }

    @Test
    fun `a workflow starts out with the options handed over where a worker registers its class`() {
        assertEquals(
            listOf(
                // A stub without options has the default the worker was given.
                "node activity@31: name Fetch, timeout PT5S",
                // What the worker was given for the type lies over it.
                "node activity@32: name Store, taskQueue slow, timeout PT2M",
                // A stub with options of its own has those, not the default.
                "node activity@34: name Fetch, timeout PT1S",
                // But what is given for the type lies over them too.
                "node activity@35: name Store, taskQueue slow, timeout PT2M",
                "node localActivity@33: name Fetch, timeout PT2S",
            ),
            activities("Registered"),
        )
    }

    @Test
    fun `a class that two workers register with different options has what both agree on`() {
        assertEquals(
            listOf(
                // One worker gives five seconds to start and finish, the other two minutes in all: neither is certain.
                "node activity@49: name Fetch",
                // Both give the same for `Store`, and that covers what the defaults differ in.
                "node activity@50: name Store, taskQueue slow, timeout PT2M",
            ),
            activities("Twice"),
        )
    }

    @Test
    fun `what is set in a branch, or by the workflow method while a handler may run, is not taken for certain`() {
        assertEquals(
            listOf(
                // The stub was made before the class set a default: it has none.
                "node activity@39: name Fetch",
                // This one was made after it.
                "node activity@40: name Fetch, timeout PT5S",
                // A default set in a branch may or may not hold: with it there would be a timeout for the whole, without it only one to finish.
                "node activity@44: name Store",
                // Set again for certain, the timeout is known once more; the task queue the branch may have set is not.
                "node activity@46: name Notify, timeout PT1H",
                // A handler may run before or after the workflow method has set its defaults.
                "node activity@52: name Notify",
                // What a stub was given when the object was made holds whenever it is called.
                "node activity@53: name Store, timeout PT5S",
            ),
            activities("Precedence"),
        )
    }

    @Test
    fun `the functions that register workflows are found by the text they hold, and read like any other`() {
        assertTrue(backend.mentions("WorkflowImplementationOptions"))
        assertFalse(backend.mentions("NoSuchNameAnywhere"))
        assertTrue(backend.bodiesMentioning("NoSuchNameAnywhere").isEmpty())
        // One function registers workflows: three calls, which are known by what they resolve to.
        val registering = backend.bodiesMentioning("registerWorkflowImplementationTypes").single()
        val calls = registering.walk().filterIsInstance<Call>().filter { it.callee?.function?.name == "io.temporal.worker.Worker.registerWorkflowImplementationTypes" }
        assertEquals(listOf(3, 2, 1), calls.map { it.arguments.size }.toList())
    }

    // ---- activities run without calling the stub

    @Test
    fun `the call of a stub that is handed to the workflow is a step where it stands, and one started beside the flow is a lane`() {
        assertEquals(
            listOf(
                "entry point main: kind WORKFLOW_METHOD, name Direct, start start@29",
                // The activity is the one the stub in the reference runs, with the options of that stub.
                "node activity@31: name Fetch, timeout PT5S",
                // Started beside the flow and waited for two lines on: a lane, like `Async.function`.
                "node activity@32: name Store, timeout PT5S, parent parallel@32",
                // Started beside the flow and never waited for.
                "node activity@35: name Notify, timeout PT5S, waitsFor nothing",
                "node end(main)",
                // What the flow does in the meantime is the other lane; the stub says that it runs locally.
                "node localActivity@33: name Notify, timeout PT2S, parent parallel@32",
                "node parallel@32",
                "node start@29",
                "edge activity@31 -> parallel@32",
                "edge activity@35 -> end(main)",
                "edge parallel@32 -> activity@35",
                "edge start@29 -> activity@31",
            ),
            whole("Direct"),
        )
    }
}
