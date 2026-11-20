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

/**
 * Where the options of an activity come from when they are not written out at the stub:
 * options made from other options, options a worker is given in code that no function holds,
 * and stubs that a function makes and hands back.
 *
 * Each test gives the activities of one workflow of [OptionSources] with what their options
 * give them, without ids: a node is its kind and its line, as [IrStructure] writes it. The
 * documents are kept as golden files as well.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OptionExtractionTest {
    private lateinit var backend: SourceBackend
    private lateinit var discovery: Discovery
    private lateinit var documents: List<IrDocument>
    private lateinit var warnings: List<String>

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val sources = SyntheticSources(directory)
        OptionSources.FILES.forEach { (name, text) -> sources.file(":app", "options/$name.kt", text) }
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

    /** The activities of a workflow, with what their options give them, without ids. */
    private fun activities(implementation: String): List<String> =
        documents.single { it.implementation == "options.${implementation}Impl" }.outline()
            .filter { it.startsWith("node activity") || it.startsWith("node localActivity") }

    @Test
    fun `an overview keeps every step of the full document, and its golden file is there where it differs`() {
        assertEquals(emptyList<String>(), assertOverviewsKeepTheSteps(backend, discovery, "overview/options"))
    }

    @Test
    fun `every document is well formed and gives the golden IR`() {
        val names = IrFiles.names(documents).map { it.removeSuffix(IrFiles.SUFFIX) }
        assertEquals(setOf("Copies", "Unread", "Helpers", "ByInit", "ByProperty", "ByConstructor", "ByFile", "Branched", "Handed"), names.toSet())
        for ((document, name) in documents.zip(names)) {
            assertWellFormed(document)
            GoldenFiles.assertMatches("ir/options/$name.ir.json", IrJson.encode(document))
        }
        assertEquals(emptyList(), discovery.warnings)
        assertEquals(emptyMap(), documents.associate { it.implementation to it.stats.unknown }.filterValues { it > 0 })
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `reading everything an entry point reaches gives the graph that sparing what holds nothing gives`() {
        assertEquals(documents.sumOf { it.mainFlow().nodes.size }, assertSparingChangesNothing(backend, discovery))
    }

    // ---- options made from other options

    @Test
    fun `options made from other options hold what those hold, and what their builder sets over it`() {
        assertEquals(
            listOf(
                // `SHORT.toBuilder().setTaskQueue(…)`: the timeout of the options copied, and the task queue set over them.
                "node activity@31: name Fetch, taskQueue fast, timeout PT5S",
                // `newBuilder(LONG).setStartToCloseTimeout(…)`: the time for the whole that was copied still comes first.
                "node activity@32: name Fetch, taskQueue slow, timeout PT2M",
                // What the builder sets takes the place of what was copied.
                "node activity@33: name Fetch, taskQueue slow, timeout PT1M",
                // A copy of a copy.
                "node activity@34: name Fetch, taskQueue fast, timeout PT5S",
                // A builder made from `null` begins with nothing.
                "node activity@35: name Fetch, timeout PT7S",
                // `newBuilder(SHORT).mergeActivityOptions(LONG)`: what LONG sets lies over what was copied.
                "node activity@36: name Fetch, taskQueue slow, timeout PT2M",
                // A default made from other options, for a stub made after it was set.
                "node activity@42: name Sweep, taskQueue fast, timeout PT5S",
                // Options for one activity type made from other options, laid over that default.
                "node activity@43: name Notify, taskQueue slower, timeout PT2M",
                // The same for local activities, with `toBuilder` and with `newBuilder(other)`.
                "node localActivity@37: name Store, timeout PT8S",
                "node localActivity@38: name Store, timeout PT2S",
            ),
            activities("Copies"),
        )
    }

    @Test
    fun `of options made from options that cannot be read, only what their builder sets is known`() {
        assertEquals(
            listOf(
                // The task queue is set; whether the options handed in hold a timeout is not known.
                "node activity@22: name Fetch, taskQueue mine",
                // The time for the whole is set, and comes first whatever else was copied.
                "node activity@23: name Store, timeout PT3M",
                // The time for one attempt is set, but the options copied may hold one for the whole.
                "node activity@24: name Notify",
                // A builder kept in a variable: what the last chain sets is known, what was set on it before is not followed.
                "node activity@27: name Sweep, taskQueue kept",
                // Options that cannot be read are merged in last: they may set anything.
                "node activity@28: name Fetch",
                // Options are merged in before a setter: what the options hold in the end is not followed.
                "node activity@29: name Store",
                // A default made from options that cannot be read says nothing of the time for the whole.
                "node activity@31: name Notify",
            ),
            activities("Unread"),
        )
    }

    // ---- stubs that a function makes and hands back

    @Test
    fun `a stub that a function makes is given the default in force where the function is called`() {
        assertEquals(
            listOf(
                // The property was given its stub when the object was made, before any default was set.
                "node activity@44: name Fetch",
                // So only what is set for the activity type holds: nothing lies under it.
                "node activity@45: name Sweep, timeout PT9S",
                // A function that hands back the stub the property keeps makes no new one.
                "node activity@46: name Sweep, timeout PT9S",
                // Made by a call that is kept in a variable, after the default was set.
                "node activity@48: name Fetch, timeout PT5S",
                // Made where it is called.
                "node activity@49: name Fetch, timeout PT5S",
                // Options handed to the function are those of the stub.
                "node activity@51: name Notify, taskQueue slow, timeout PT2M",
                // A stub without an interface has the options the function gives it.
                "node activity@52: name Fetch, taskQueue slow, timeout PT2M",
                // A function that makes a stub of any interface it is handed.
                "node activity@53: name Notify, timeout PT5S",
                // A function that calls the function that makes the stub.
                "node activity@54: name Store, timeout PT5S",
                // The stub in the variable keeps what it was given when the default changes.
                "node activity@56: name Store, timeout PT5S",
                // One made after the change has the new default, merged over the old one.
                "node activity@57: name Store, taskQueue slow, timeout PT2M",
                // A local activity stub that the function keeps in a variable of its own before it hands it back.
                "node localActivity@50: name Store, timeout PT2S",
            ),
            activities("Helpers"),
        )
    }

    // ---- options a worker is given in code that no function holds

    @Test
    fun `workflows registered in an init block, a property initialiser or a constructor start with the options they are registered with`() {
        // `base.toBuilder().setDefaultActivityOptions(LONG)` in an `init` block: all of the options copied, with another default.
        assertEquals(
            listOf(
                "node activity@25: name Fetch, taskQueue slow, timeout PT2M",
                "node activity@26: name Store, taskQueue slow, timeout PT2M",
                "node localActivity@27: name Fetch, timeout PT2S",
            ),
            activities("ByInit"),
        )
        // `newBuilder(base).setActivityOptions(…)` in the initialiser of a property: the map takes the place of the one copied.
        assertEquals(
            listOf(
                "node activity@42: name Fetch, taskQueue slow, timeout PT2M",
                "node activity@43: name Store, timeout PT5S",
                "node localActivity@44: name Fetch, timeout PT2S",
            ),
            activities("ByProperty"),
        )
        // In the body of a constructor.
        assertEquals(
            listOf(
                "node activity@59: name Fetch, timeout PT5S",
                "node activity@60: name Store, taskQueue slow, timeout PT2M",
                "node localActivity@61: name Fetch, timeout PT2S",
            ),
            activities("ByConstructor"),
        )
        // In the initialiser of a property of a file; nothing is set there for local activities.
        assertEquals(
            listOf(
                "node activity@76: name Fetch, timeout PT5S",
                "node activity@77: name Store, timeout PT5S",
                "node localActivity@78: name Fetch",
            ),
            activities("ByFile"),
        )
    }

    @Test
    fun `a workflow registered with one set of options or another has what both give, and nothing of options that cannot be read`() {
        // The two arms of an `if` register the class with different defaults and the same options for one type.
        assertEquals(
            listOf(
                "node activity@93: name Fetch",
                "node activity@94: name Store, taskQueue slow, timeout PT2M",
                "node localActivity@95: name Fetch, timeout PT2S",
            ),
            activities("Branched"),
        )
        // Options made from options handed in: the default is set, but the map copied may hold anything for any type.
        assertEquals(
            listOf(
                "node activity@110: name Fetch",
                "node activity@111: name Store",
                "node localActivity@112: name Fetch",
            ),
            activities("Handed"),
        )
    }

    @Test
    fun `code that registers workflows is found where no function holds it, and read like any other`() {
        val registering = backend.bodiesMentioning("registerWorkflowImplementationTypes")
        val calls = registering.map { block ->
            block.location.line to block.walk().filterIsInstance<Call>()
                .filter { it.callee?.function?.name == "io.temporal.worker.Worker.registerWorkflowImplementationTypes" }
                .map { it.location.line }
                .toList()
        }
        assertEquals(
            listOf(
                // An `init` block, with a call in each arm of its `if`.
                21 to listOf(22, 24, 26),
                // The initialiser of a property of a class, whose lambda holds the call.
                33 to listOf(35),
                // The body of a constructor.
                38 to listOf(39),
                // A function.
                42 to listOf(43),
                // The initialiser of a property of the file.
                53 to listOf(54),
            ),
            calls,
        )
        // The initialisers that only make the options are not among them: what is looked for is the call.
        assertEquals(emptyList(), backend.bodiesMentioning("NoSuchNameAnywhere"))
    }
}
