package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.UnknownReasons
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
 * A function of the sources is drawn from the inside only where a step of the workflow is
 * certain to lie in it. Ordinary code that a workflow calls (a wrapper around an object mapper,
 * a factory of events, a chain of helpers) branches, loops and throws without holding a step:
 * none of that is drawn. Where such a call may fail, a branch at the call asks whether it throws
 * and leads where a `throw` in its place would; where it calls something that cannot be
 * followed, a node at the call says so; otherwise it leaves nothing, like the call of a library.
 *
 * The sources of [StepFreeSources] are read twice, as a degraded model has them and as a model
 * made from the build does: the rule is the same for both.
 *
 * A worker of these sources is told to fail its workflows on whatever is thrown, so every
 * failure that may leave a workflow here fails its execution and is drawn. What becomes of
 * failures that fail the workflow task alone is the matter of [RealFailuresExtractionTest].
 *
 * Each test gives the whole graph of one workflow, without ids: a node is its kind and its line,
 * as [IrStructure] writes it, with the file in front of the line where the nodes of a workflow
 * come from more than one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StepFreeCallsExtractionTest {
    private lateinit var backend: SourceBackend
    private lateinit var discovery: Discovery
    private lateinit var degraded: List<IrDocument>
    private lateinit var built: List<IrDocument>
    private val warnings = ArrayList<String>()

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val sources = SyntheticSources(directory)
        StepFreeSources.FILES.forEach { (name, text) -> sources.file(":app", "plain/$name.kt", text) }
        sources.file(":app", "workers/Workers.kt", STRICT_WORKER)
        backend = sources.open()
        discovery = TemporalDiscovery.discover(backend)
        val lenient = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.DEGRADED)
        degraded = lenient.extract(discovery)
        warnings += lenient.warnings
        built = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.GRADLE).extract(discovery)
    }

    @AfterAll
    fun close() {
        backend.close()
    }

    private fun List<IrDocument>.of(workflowType: String): IrDocument = single { it.workflowType == workflowType }

    /** The nodes and edges of a workflow, without ids. */
    private fun graph(workflowType: String): List<String> = degraded.of(workflowType).graph()

    private fun IrDocument.graph(): List<String> = outline().filterNot { it.startsWith("entry point ") }

    /** The document of a workflow when calls are followed [maxDepth] deep. */
    private fun document(workflowType: String, maxDepth: Int, mode: ModelMode = ModelMode.DEGRADED): IrDocument {
        val workflow = discovery.workflows.single { it.name == workflowType }
        return WorkflowGraphs(backend, FIXED_COMMIT, mode, maxDepth).extract(workflow, workflow.implementations.single())
    }

    /** The files the nodes of a workflow lie in, by their names. */
    private fun files(workflowType: String): Set<String> =
        degraded.of(workflowType).nodes.mapNotNull { it.loc?.file?.substringAfterLast('/') }.toSet()

    @Test
    fun `every document is well formed, both models give the same, and those of the degraded one are the golden ones`() {
        assertEquals(StepFreeSources.WORKFLOWS, degraded.map { it.workflowType })
        for (document in degraded + built) assertWellFormed(document)
        // Everything in these sources resolves: nothing is left for a degraded model to guess at.
        assertEquals(emptyList(), degraded.flatMap { it.skippedCalls })
        assertEquals(emptyList(), (degraded + built).flatMap { it.nodes }.filter { it.reason == UnknownReasons.UNRESOLVED }.map { it.inWords() })
        assertEquals(degraded.map { it.copy(modelMode = ModelMode.GRADLE) }, built)
        assertEquals(emptyList(), warnings)
        for (name in StepFreeSources.WORKFLOWS) GoldenFiles.assertMatches("ir/stepfree/$name.ir.json", IrJson.encode(degraded.of(name)))
        // The chains of calls once more, with calls followed two deep: three of their four functions lie beyond that.
        val shallow = document("Deep", maxDepth = 2)
        assertWellFormed(shallow)
        assertEquals(shallow.copy(modelMode = ModelMode.GRADLE), document("Deep", maxDepth = 2, ModelMode.GRADLE))
        GoldenFiles.assertMatches("ir/stepfree/Deep.depth-2.ir.json", IrJson.encode(shallow))
    }

    @Test
    fun `sparing the functions that hold nothing changes no graph`() {
        assertTrue(assertSparingChangesNothing(backend, discovery) > 0)
    }

    // ---- ordinary code around the steps

    @Test
    fun `a workflow that calls a factory and a mapper around its steps comes down to its steps, its own decisions and what may fail`() {
        assertEquals(
            listOf(
                "node activity@27: name One, timeout PT5S",
                "node activity@29: name Two, timeout PT5S",
                "node activity@32: name Two, timeout PT5S",
                // The `when` of the factory, with a `throw` in its last arm and in a function it calls: one branch for the call.
                "node branch@26: label Events.create throws?",
                // Another `when`, whose arms all call the mapper: what that throws when the object mapper fails.
                "node branch@27: label Events.toJson throws?",
                // A decision of the workflow itself. What it asks is ordinary code that cannot fail: nothing is drawn for it.
                "node branch@28: label events.describe(event).isEmpty()",
                "node end(main)",
                // Each failure the factory may raise, by its name.
                "node fail@26: label IllegalArgumentException, IllegalStateException, UnknownEvent",
                "node fail@27: name MappingException",
                "node start@25",
                "edge activity@27 -> branch@28",
                // `readOrNull` catches what `read` throws: nothing comes out of it, and nothing stands for it.
                "edge activity@29 -> activity@32",
                "edge activity@32 -> end(main)",
                "edge branch@26 -> branch@27 [no]",
                "edge branch@26 -> fail@26 [yes]",
                "edge branch@27 -> activity@27 [no]",
                "edge branch@27 -> fail@27 [yes]",
                "edge branch@28 -> activity@29 [yes]",
                "edge branch@28 -> activity@32 [no]",
                "edge start@25 -> branch@26",
            ),
            graph("Published"),
        )
        // No node comes from inside the factory or the mapper.
        assertEquals(setOf("Published.kt"), files("Published"))
    }

    @Test
    fun `what a function that is not drawn may throw reaches the clause that catches it, from the branch at its call`() {
        assertEquals(
            listOf(
                "node activity@29: name Two, timeout PT5S",
                "node activity@33: name One, timeout PT5S",
                "node activity@35: name Two, timeout PT5S",
                "node branch@27: label Events.create throws?",
                "node branch@33: label Mapper.write throws?",
                "node end(main)",
                // What the clause does not catch fails the workflow.
                "node fail@27: label IllegalArgumentException, IllegalStateException",
                "node start@25",
                "edge activity@29 -> end(main)",
                "edge activity@33 -> end(main)",
                "edge activity@35 -> end(main)",
                // The clause is sure to catch an `UnknownEvent`, and may catch what is thrown as its supertype.
                "edge branch@27 -(exception)-> activity@29 [UnknownEvent]",
                "edge branch@27 -> branch@33 [no]",
                "edge branch@27 -> fail@27 [yes]",
                // All that the mapper throws is caught: no way leads to a failure of the workflow.
                "edge branch@33 -(exception)-> activity@35 [MappingException]",
                "edge branch@33 -> activity@33 [no]",
                "edge start@25 -> branch@27",
            ),
            graph("Caught"),
        )
    }

    @Test
    fun `what a function that is not drawn may throw out of the workflow leads to a failure, and a call that never returns fails without being asked`() {
        assertEquals(
            listOf(
                "node activity@28: name One, timeout PT5S",
                "node activity@30: name Two, timeout PT5S",
                // Two `if`s that throw the same: one branch, one failure.
                "node branch@27: label EscapingImpl.validate throws?",
                // The workflow's own `?:`, whose right-hand side never returns.
                "node branch@29: label table[first] == null",
                // The getter of a property is a function like any other.
                "node branch@30: label EscapingImpl.limit throws?",
                "node end(main)",
                "node fail@27: name IllegalArgumentException",
                // `fail` always throws: the failure stands where it is called, and no branch asks.
                "node fail@29: name IllegalStateException",
                "node fail@30: name IllegalStateException",
                "node start@26",
                "edge activity@28 -> branch@29",
                "edge activity@30 -> end(main)",
                "edge branch@27 -> activity@28 [no]",
                "edge branch@27 -> fail@27 [yes]",
                "edge branch@29 -> branch@30 [no]",
                "edge branch@29 -> fail@29 [yes]",
                "edge branch@30 -> activity@30 [no]",
                "edge branch@30 -> fail@30 [yes]",
                "edge start@26 -> branch@27",
            ),
            graph("Escaping"),
        )
    }

    // ---- how deep calls go

    @Test
    fun `a step that lies beyond the depth to which calls are followed keeps its node, and a chain that only throws or does nothing gets none`() {
        assertEquals(
            listOf(
                "node activity@Deep.kt:26: name One, timeout PT5S",
                // The chain that ends in a `throw` is not followed at all: what it may throw is known however deep it lies.
                "node branch@Deep.kt:24: label checked1 throws?",
                "node end(main)",
                "node fail@Deep.kt:24: name IllegalArgumentException",
                "node start@Deep.kt:23",
                // The chain that ends in an activity is followed as far as calls are, and the node names the function that was not.
                "node unknown@Chains.kt:31: label stepped3, reason depth-limit",
                "edge activity@Deep.kt:26 -> unknown@Chains.kt:31",
                "edge branch@Deep.kt:24 -> activity@Deep.kt:26 [no]",
                "edge branch@Deep.kt:24 -> fail@Deep.kt:24 [yes]",
                "edge start@Deep.kt:23 -> branch@Deep.kt:24",
                // The chain that ends in nothing leaves nothing.
                "edge unknown@Chains.kt:31 -> end(main)",
            ),
            document("Deep", maxDepth = 2).graph(),
        )
        // Followed all the way, the step is drawn; the other two chains read the same at any depth.
        assertEquals(
            listOf(
                "node activity@Chains.kt:33: name Two, timeout PT5S",
                "node activity@Deep.kt:26: name One, timeout PT5S",
                "node branch@Deep.kt:24: label checked1 throws?",
                "node end(main)",
                "node fail@Deep.kt:24: name IllegalArgumentException",
                "node start@Deep.kt:23",
                "edge activity@Chains.kt:33 -> end(main)",
                "edge activity@Deep.kt:26 -> activity@Chains.kt:33",
                "edge branch@Deep.kt:24 -> activity@Deep.kt:26 [no]",
                "edge branch@Deep.kt:24 -> fail@Deep.kt:24 [yes]",
                "edge start@Deep.kt:23 -> branch@Deep.kt:24",
            ),
            graph("Deep"),
        )
        for (depth in 0..3) {
            val nodes = document("Deep", depth).nodes
            assertEquals(listOf("stepped${depth + 1}"), nodes.filter { it.kind == NodeKind.UNKNOWN }.map { it.label }, "depth $depth")
            assertEquals(listOf("checked1 throws?"), nodes.filter { it.kind == NodeKind.BRANCH }.map { it.label }, "depth $depth")
        }
    }

    @Test
    fun `a function that calls itself is drawn where it holds a step, and is one branch where it only may throw`() {
        assertEquals(
            listOf(
                "node activity@Chains.kt:44: name One, timeout PT5S",
                "node branch@Chains.kt:43: label value.isEmpty()",
                // `depthOf` calls itself and holds no step: nothing marks the call that leads back.
                "node branch@Circling.kt:24: label depthOf throws?",
                "node end(main)",
                "node fail@Circling.kt:24: name IllegalStateException",
                "node start@Circling.kt:23",
                // `drain` calls itself around an activity.
                "node unknown@Chains.kt:44: label drain, reason recursion",
                "edge activity@Chains.kt:44 -> unknown@Chains.kt:44",
                "edge branch@Chains.kt:43 -> activity@Chains.kt:44 [no]",
                "edge branch@Chains.kt:43 -> end(main) [yes]",
                "edge branch@Circling.kt:24 -> branch@Chains.kt:43 [no]",
                "edge branch@Circling.kt:24 -> fail@Circling.kt:24 [yes]",
                "edge start@Circling.kt:23 -> branch@Circling.kt:24",
                "edge unknown@Chains.kt:44 -> end(main)",
            ),
            graph("Circling"),
        )
    }

    // ---- helpers with steps, and with code they are handed

    @Test
    fun `a function with a step on one way through it is drawn, and those beside it that hold none leave nothing`() {
        assertEquals(
            listOf(
                "node activity@33: name One, timeout PT5S",
                // The `if` of `maybeOne`. The `if` of `normalise` and the `when` of `label` lead to nothing.
                "node branch@32: label input.length < 3",
                "node end(main)",
                "node start@23",
                "edge activity@33 -> end(main)",
                "edge branch@32 -> activity@33 [no]",
                "edge branch@32 -> end(main) [yes]",
                "edge start@23 -> branch@32",
            ),
            graph("Forked"),
        )
    }

    @Test
    fun `a function without a step that is handed a lambda with one is drawn, and the same function handed a lambda without is not`() {
        assertEquals(
            listOf(
                // The lambda of the first call, where `each` runs it: in its loop.
                "node activity@Handing.kt:25: name One, timeout PT5S, parent loop@Mapper.kt:36",
                "node activity@Handing.kt:27: name Two, timeout PT5S",
                // The second call hands over nothing that is a step: one branch for all `each` does.
                "node branch@Handing.kt:26: label Mapper.each throws?",
                // `timed` is handed a step, so its own `if` is drawn around it.
                "node branch@Handing.kt:31: label what.isEmpty()",
                "node end(main)",
                "node fail@Handing.kt:26: name MappingException",
                "node fail@Handing.kt:31: name IllegalArgumentException",
                "node fail@Mapper.kt:46: name MappingException",
                "node loop@Mapper.kt:36: label for each value",
                "node start@Handing.kt:24",
                "edge activity@Handing.kt:27 -> end(main)",
                "edge branch@Handing.kt:26 -> branch@Handing.kt:31 [no]",
                "edge branch@Handing.kt:26 -> fail@Handing.kt:26 [yes]",
                "edge branch@Handing.kt:31 -> activity@Handing.kt:27 [no]",
                "edge branch@Handing.kt:31 -> fail@Handing.kt:31 [yes]",
                "edge loop@Mapper.kt:36 -(exception)-> fail@Mapper.kt:46 [JsonProcessingException]",
                "edge loop@Mapper.kt:36 -> branch@Handing.kt:26",
                "edge start@Handing.kt:24 -> loop@Mapper.kt:36",
            ),
            graph("Handing"),
        )
    }

    @Test
    fun `what making the workflow object calls follows the same rule`() {
        assertEquals(
            listOf(
                // The initialiser of a property is a step of the workflow.
                "node activity@25: name One, timeout PT5S",
                "node activity@27: name Two, timeout PT5S",
                // The initialiser of another calls a function that may throw.
                "node branch@24: label limitOf throws?",
                "node end(main)",
                "node fail@24: name IllegalArgumentException",
                "node start@27",
                "edge activity@25 -> activity@27",
                "edge activity@27 -> end(main)",
                "edge branch@24 -> activity@25 [no]",
                "edge branch@24 -> fail@24 [yes]",
                "edge start@27 -> branch@24",
            ),
            graph("Made"),
        )
    }

    @Test
    fun `a call that may throw is a branch in the loop it is made in, at the start of a round, at its end and alone`() {
        assertEquals(
            listOf(
                "node activity@28: name One, timeout PT5S, parent loop@26",
                "node activity@32: name Two, timeout PT5S",
                // The first thing in a round: the round begins with the branch.
                "node branch@27: label Events.create throws?, parent loop@26",
                // The last thing in a round.
                "node branch@29: label Mapper.write throws?, parent loop@26",
                // All that a round does: the loop is drawn for it.
                "node branch@31: label Events.create throws?, parent loop@31",
                "node end(main)",
                // A failure ends the workflow, so its node lies in no loop.
                "node fail@27: label IllegalArgumentException, IllegalStateException, UnknownEvent",
                "node fail@29: name MappingException",
                "node fail@31: label IllegalArgumentException, IllegalStateException, UnknownEvent",
                "node loop@26: label for each item",
                "node loop@31: label for each of input",
                "node start@25",
                "edge activity@28 -> branch@29",
                "edge activity@32 -> end(main)",
                "edge branch@27 -> activity@28 [no]",
                "edge branch@27 -> fail@27 [yes]",
                "edge branch@29 -> fail@29 [yes]",
                // Where it does not throw, the round is over.
                "edge branch@29 -> loop@26 [no]",
                "edge branch@31 -> fail@31 [yes]",
                "edge branch@31 -> loop@31 [no]",
                "edge loop@26 -> loop@31",
                "edge loop@31 -> activity@32",
                "edge start@25 -> loop@26",
            ),
            graph("Looped"),
        )
    }

    // ---- the state of the workflow

    @Test
    fun `what a function that is not drawn reads and writes of the workflow's state still counts`() {
        val keeping = degraded.of("Keeping")
        // The signal writes through `record`, the query reads through `describe`, and neither function is drawn.
        assertEquals(listOf("signal-approve writes approvedBy", "query-status reads approvedBy, stage"), keeping.handlers())
        // The await reads through `isApproved`, and the signal wakes it.
        assertEquals(listOf("signal-approve -> await@33"), keeping.wakes())
        assertEquals(
            listOf(
                "node activity@35: name One, timeout PT5S",
                "node await@33: reads approvedBy, label isApproved()",
                "node branch@32: label KeepingImpl.advance throws?",
                "node branch@34: label KeepingImpl.advance throws?",
                "node branch@35: label KeepingImpl.approver throws?",
                "node branch@39: label KeepingImpl.record throws?",
                "node end(main)",
                "node end(query-status)",
                "node end(signal-approve)",
                "node fail@32: name IllegalStateException",
                "node fail@34: name IllegalStateException",
                "node fail@35: name IllegalStateException",
                "node fail@39: name IllegalArgumentException",
                "node start@31",
                "node start@38",
                "node start@42",
                "edge activity@35 -> end(main)",
                "edge await@33 -> branch@34",
                "edge branch@32 -> await@33 [no]",
                "edge branch@32 -> fail@32 [yes]",
                "edge branch@34 -> branch@35 [no]",
                "edge branch@34 -> fail@34 [yes]",
                "edge branch@35 -> activity@35 [no]",
                "edge branch@35 -> fail@35 [yes]",
                "edge branch@39 -> end(signal-approve) [no]",
                "edge branch@39 -> fail@39 [yes]",
                "edge entry point signal-approve -(wakes)-> await@33",
                "edge start@31 -> branch@32",
                "edge start@38 -> branch@39",
                "edge start@42 -> end(query-status)",
            ),
            keeping.graph(),
        )
    }

    // ---- what cannot be followed

    @Test
    fun `a call behind which lies a function that no class implements is a node that names both, and one that may run several functions without a step is a branch`() {
        assertEquals(
            listOf(
                "node activity@30: name One, timeout PT5S",
                // `tell` runs an activity, so it is drawn, and the call in it that cannot be followed keeps its node where it stands.
                "node activity@42: name Two, timeout PT5S",
                // A function that looks a class up and calls it: whichever class builds, none holds a step and one may throw.
                "node branch@27: label Events.build throws?",
                // The workflow makes such a call itself: no node for a call that may run one of several functions, as none holds a step.
                "node branch@28: label Builder.build throws?",
                "node branch@30: label UnseenImpl.announce throws?",
                "node end(main)",
                "node fail@27: label IllegalStateException, UnknownEvent",
                "node fail@28: name IllegalStateException",
                "node fail@30: name IllegalArgumentException",
                "node start@24",
                // `enriched` calls a function of an interface that no class of the sources implements.
                "node unknown@25: label Events.enriched, reason unseen-code, candidates plain.Enricher.enrich(plain.Event)",
                // The workflow makes that call itself: the node it always had.
                "node unknown@26: reason dynamic-dispatch",
                // A function that calls what cannot be followed and may throw gets a node for each.
                "node unknown@30: label UnseenImpl.announce, reason unseen-code, candidates plain.Enricher.enrich(plain.Event)",
                "node unknown@41: reason dynamic-dispatch",
                "edge activity@30 -> end(main)",
                "edge activity@42 -> unknown@30",
                "edge branch@27 -> branch@28 [no]",
                "edge branch@27 -> fail@27 [yes]",
                "edge branch@28 -> fail@28 [yes]",
                "edge branch@28 -> unknown@41 [no]",
                "edge branch@30 -> activity@30 [no]",
                "edge branch@30 -> fail@30 [yes]",
                "edge start@24 -> unknown@25",
                "edge unknown@25 -> unknown@26",
                "edge unknown@26 -> branch@27",
                "edge unknown@30 -> branch@30",
                "edge unknown@41 -> activity@42",
            ),
            graph("Unseen"),
        )
    }

    @Test
    fun `a call that may be either of two functions the compiler could not choose among is a branch where neither holds a step and one may throw`(@TempDir directory: Path) {
        val sources = SyntheticSources(directory)
        sources.file(":app", "workers/Workers.kt", STRICT_WORKER)
        sources.file(
            ":app",
            "unsettled/Unsettled.kt",
            """
            package unsettled

            import com.acme.util.Money
            import io.temporal.activity.ActivityInterface
            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod

            @ActivityInterface
            interface Jobs {
                fun one(input: String): String
            }

            object Checks {
                fun check(code: String): String {
                    if (code.isEmpty()) throw IllegalArgumentException("no code")
                    return code
                }

                fun check(code: Int): String = code.toString()

                fun pick(code: String, jobs: Jobs): String = jobs.one(code)

                fun pick(code: Int, jobs: Jobs): String = code.toString()
            }

            @WorkflowInterface
            interface Unsettled {
                @WorkflowMethod
                fun run(input: String): String
            }

            class UnsettledImpl : Unsettled {
                private val jobs = Workflow.newActivityStub(Jobs::class.java)

                override fun run(input: String): String {
                    val money = Money.of(1)
                    Checks.check(money.text())
                    return Checks.pick(money.text(), jobs)
                }
            }
            """,
        )
        sources.open().use { alone ->
            val document = WorkflowGraphs(alone, FIXED_COMMIT, ModelMode.DEGRADED).extract(TemporalDiscovery.discover(alone)).single()
            assertWellFormed(document)
            assertEquals(
                listOf(
                    // Neither `check` holds a step, and one of them may throw.
                    "node branch@38: label check throws?",
                    "node end(main)",
                    "node fail@38: name IllegalArgumentException",
                    "node start@36",
                    // One `pick` runs an activity: a step lies behind the call if that one is meant.
                    "node unknown@39: label pick, reason unresolved, candidates unsettled.Checks.pick(kotlin.Int,unsettled.Jobs), unsettled.Checks.pick(kotlin.String,unsettled.Jobs)",
                    "edge branch@38 -> fail@38 [yes]",
                    "edge branch@38 -> unknown@39 [no]",
                    "edge start@36 -> branch@38",
                    "edge unknown@39 -> end(main)",
                ),
                document.outline().filterNot { it.startsWith("entry point ") },
            )
        }
    }

    private companion object {
        /** Where a worker is told to fail a workflow on any exception that leaves it. */
        const val STRICT_WORKER = """
            package workers

            import io.temporal.worker.WorkflowImplementationOptions

            fun strict(): WorkflowImplementationOptions =
                WorkflowImplementationOptions.newBuilder().setFailWorkflowExceptionTypes(Throwable::class.java).build()
        """
    }
}
