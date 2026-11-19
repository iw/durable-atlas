package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.ModelMode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How control flow is drawn in cases the sample project does not contain: jumps out of loops,
 * `try` inside `try`, lanes beside the flow's own steps, several versions, awaits asked about in
 * different ways, and sagas whose compensations are registered here and there.
 *
 * Each test gives the whole graph of one workflow of [ControlFlowSources], without ids: a node is
 * its kind and its line, as [IrStructure] writes it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControlFlowExtractionTest {
    private lateinit var backend: SourceBackend
    private lateinit var documents: List<IrDocument>

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val sources = SyntheticSources(directory)
        ControlFlowSources.FILES.forEach { (name, text) -> sources.file(":app", "flow/$name.kt", text) }
        backend = sources.open()
        documents = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.DEGRADED).extract(TemporalDiscovery.discover(backend))
    }

    @AfterAll
    fun close() {
        backend.close()
    }

    /** The nodes and edges of a workflow, without ids. */
    private fun graph(workflowType: String): List<String> =
        documents.single { it.workflowType == workflowType }.outline().filterNot { it.startsWith("entry point ") }

    @Test
    fun `every document is well formed`() {
        assertEquals(ControlFlowSources.FILES.size - 1, documents.size)
        documents.forEach { assertWellFormed(it) }
    }

    @Test
    fun `break leads from where it is to what follows the loop, return to the end, and continue to the loop itself`() {
        assertEquals(
            listOf(
                "node activity@23: name Check, parent loop@21",
                "node activity@24: name Work, parent loop@21",
                "node activity@27: name Prepare, parent loop@26",
                "node activity@30: name Work, parent loop@26",
                "node activity@32: name Report",
                "node branch@22: label id.isEmpty(), parent loop@21",
                "node branch@23: label !tasks.check(id), parent loop@21",
                "node branch@28: label next == \"stop\", parent loop@26",
                "node branch@29: label next == \"abort\", parent loop@26",
                "node end(main)",
                "node loop@21: label for each id",
                "node loop@26: label while true",
                "node start@20",
                // The call in the condition is a step in front of the branch.
                "edge activity@23 -> branch@23",
                "edge activity@27 -> branch@28",
                "edge activity@32 -> end(main)",
                "edge branch@22 -> activity@23 [no]",
                // `continue` ends the round: an edge to the loop the branch is in.
                "edge branch@22 -> loop@21 [yes]",
                "edge branch@23 -> activity@24 [no]",
                "edge branch@23 -> loop@26 [yes]",
                "edge branch@28 -> activity@32 [yes]",
                "edge branch@28 -> branch@29 [no]",
                "edge branch@29 -> activity@30 [no]",
                "edge branch@29 -> end(main) [yes]",
                // `while (true)` is only left from inside: nothing leads on from the loop itself.
                "edge loop@21 -> loop@26",
                "edge start@20 -> loop@21",
            ),
            graph("LoopExits"),
        )
    }

    @Test
    fun `a jump passes through finally on its way, and a labelled one leaves the loop it names`() {
        assertEquals(
            listOf(
                "node activity@25: name Work, parent loop@21",
                // `finally` once for the way on, once for `continue` and once for `break`.
                "node activity@27#1: name CleanUp, parent loop@21",
                "node activity@27#2: name CleanUp, parent loop@21",
                "node activity@27#3: name CleanUp, parent loop@21",
                "node activity@33: name Check, parent loop@31",
                "node activity@36: name Report",
                "node branch@23: label id.isEmpty(), parent loop@21",
                "node branch@24: label id == \"last\", parent loop@21",
                "node branch@32: label first == second, parent loop@31",
                "node end(main)",
                "node loop@21: label for each id",
                "node loop@30: label for each first",
                "node loop@31: label for each second, parent loop@30",
                "node start@20",
                "edge activity@25 -> activity@27#1",
                // After the `finally` of a `break`, the flow is out of the loop.
                "edge activity@27#3 -> loop@30",
                "edge activity@36 -> end(main)",
                "edge branch@23 -> activity@27#2 [yes]",
                "edge branch@23 -> branch@24 [no]",
                "edge branch@24 -> activity@25 [no]",
                "edge branch@24 -> activity@27#3 [yes]",
                "edge branch@32 -> activity@33 [no]",
                // `break@outer` leaves both loops: an edge from inside the inner one to what follows the outer one.
                "edge branch@32 -> activity@36 [yes]",
                "edge loop@21 -> loop@30",
                "edge loop@30 -> activity@36",
                "edge start@20 -> loop@21",
            ),
            graph("Jumps"),
        )
    }

    @Test
    fun `a failure passes through the finally of an inner try on its way to the clause of an outer one`() {
        assertEquals(
            listOf(
                "node activity@22: name Prepare",
                "node activity@24: name Work",
                // `finally` three times: for the failure of the step before it, for the exception thrown, and for the way on.
                "node activity@27#1: name CleanUp",
                "node activity@27#2: name CleanUp",
                "node activity@27#3: name CleanUp",
                "node activity@29: name Report",
                "node activity@31: name Undo",
                "node branch@25: label id == \"bad\"",
                "node end(main)",
                "node start@20",
                "edge activity@22 -(exception)-> activity@31 [ActivityFailure]",
                "edge activity@22 -> activity@24",
                "edge activity@24 -(exception)-> activity@27#1 [ActivityFailure]",
                "edge activity@24 -> branch@25",
                // After `finally` the failure goes on to the clause; a step of `finally` can fail too.
                "edge activity@27#1 -(exception)-> activity@31 [ActivityFailure]",
                "edge activity@27#1 -> activity@31",
                "edge activity@27#2 -(exception)-> activity@31 [ActivityFailure]",
                // The clause for the exception thrown holds no step and returns.
                "edge activity@27#2 -> end(main)",
                "edge activity@27#3 -(exception)-> activity@31 [ActivityFailure]",
                "edge activity@27#3 -> activity@29",
                "edge activity@29 -(exception)-> activity@31 [ActivityFailure]",
                "edge activity@29 -> end(main)",
                "edge activity@31 -> end(main)",
                // What is thrown is caught by the second clause, so it is no terminal.
                "edge branch@25 -(exception)-> activity@27#2 [IllegalStateException]",
                "edge branch@25 -> activity@27#3 [no]",
                "edge start@20 -> activity@22",
            ),
            graph("NestedTry"),
        )
    }

    @Test
    fun `an empty clause lets the flow go on, and a clause no step reaches is entered from where the try begins`() {
        assertEquals(
            listOf(
                "node activity@22: name Work",
                "node activity@29: name Report",
                "node activity@31: name CleanUp",
                "node end(main)",
                "node start@20",
                // The second `try` holds no step: its clause is entered from the step before it.
                "edge activity@22 -(exception)-> activity@29 [IllegalArgumentException]",
                // The failure the first clause swallows.
                "edge activity@22 -(exception)-> activity@31 [Exception]",
                "edge activity@22 -> activity@31",
                "edge activity@29 -> activity@31",
                "edge activity@31 -> end(main)",
                "edge start@20 -> activity@22",
            ),
            graph("Swallow"),
        )
    }

    @Test
    fun `what the flow does before it joins is a lane, and what nothing waits for is a step the flow goes on from at once`() {
        assertEquals(
            listOf(
                "node activity@21: name Prepare, parent parallel@21",
                // The flow's own step between the start and the join.
                "node activity@22: name Check, parent parallel@21",
                // Nothing waits for this one: it is no region with a join, and the next step follows it at once.
                "node activity@24: name Report, waitsFor nothing",
                "node activity@25: name Work",
                "node activity@27: name CleanUp, parent parallel@27",
                "node branch@26: label urgent",
                "node end(main)",
                "node parallel@21",
                "node parallel@27",
                "node start@20",
                "edge activity@24 -> activity@25",
                "edge activity@25 -> branch@26",
                "edge branch@26 -> end(main) [no]",
                "edge branch@26 -> parallel@27 [yes]",
                "edge parallel@21 -> activity@24",
                "edge parallel@27 -> end(main)",
                "edge start@20 -> parallel@21",
            ),
            graph("FanOut"),
        )
    }

    @Test
    fun `a lane holds whatever its code does, and the flow's own lane may leave the region early`() {
        assertEquals(
            listOf(
                "node activity@23: name Work, parent parallel@21",
                "node activity@26: name Undo, parent parallel@21",
                "node activity@31: name Prepare, parent parallel@21",
                "node activity@32: name Check, parent loop@32",
                "node activity@36: name Report",
                "node branch@30: label id.isEmpty(), parent parallel@21",
                "node end(main)",
                // The flow's own lane: a guard clause and a loop, between the first start and the join.
                "node loop@32: label while !tasks.check(id), parent parallel@21",
                "node parallel@21",
                "node start@20",
                "node timer@33: duration PT1S, parent loop@32",
                // A lane with a `try` of its own: the failure stays inside the lane.
                "edge activity@23 -(exception)-> activity@26 [ActivityFailure]",
                // A step that leads to another one in its lane is not left by implication: if it succeeds, the lane ends.
                "edge activity@23 -> parallel@21",
                // The condition of a `while` is part of every round.
                "edge activity@32 -> timer@33",
                "edge activity@36 -> end(main)",
                // An early return, from inside the region straight to the end.
                "edge branch@30 -> end(main) [yes]",
                "edge branch@30 -> loop@32 [no]",
                "edge parallel@21 -> activity@36",
                "edge start@20 -> parallel@21",
            ),
            graph("Lanes"),
        )
    }

    @Test
    fun `a test of a version is the gate's edges, also in a when and where the gate is the condition itself`() {
        assertEquals(
            listOf(
                "node activity@22: name Prepare",
                "node activity@23: name Check",
                "node activity@24: name Work",
                "node activity@27: name Report",
                "node activity@30: name CleanUp",
                "node activity@31: name Undo",
                // A step lies between the third gate and the test of its version, so that test is a branch.
                "node branch@31: label audit == 1",
                "node end(main)",
                "node start@20",
                "node versionGate@21: changeId pricing",
                "node versionGate@26: changeId reporting",
                "node versionGate@29: changeId audit",
                "edge activity@22 -> versionGate@26",
                "edge activity@23 -> versionGate@26",
                "edge activity@24 -> versionGate@26",
                "edge activity@27 -> versionGate@29",
                "edge activity@30 -> branch@31",
                "edge activity@31 -> end(main)",
                "edge branch@31 -> activity@31 [yes]",
                "edge branch@31 -> end(main) [no]",
                "edge start@20 -> versionGate@21",
                "edge versionGate@21 -> activity@22 [default]",
                "edge versionGate@21 -> activity@23 [v1]",
                "edge versionGate@21 -> activity@24 [v2]",
                // Every version has an edge of its own.
                "edge versionGate@26 -> activity@27 [v2]",
                "edge versionGate@26 -> activity@27 [v3]",
                "edge versionGate@26 -> versionGate@29 [v1]",
                "edge versionGate@29 -> activity@30",
            ),
            graph("Versions"),
        )
    }

    @Test
    fun `a test of what an await waited for is the await's edges, until something changes what it read`() {
        assertEquals(
            listOf(
                "node activity@26: name Work",
                "node activity@29: name Report",
                "node activity@30: name CleanUp",
                "node activity@34: name Undo",
                "node await@24: reads approved, rejected, label approved or rejected",
                "node await@27: timeout PT1H, reads approved, label not approved",
                "node await@32: timeout PT5M, reads rejected, label rejected",
                // The third await did not read `approved`: the test of it is a branch.
                "node branch@34: label approved",
                "node end(main)",
                "node start@23",
                "edge activity@26 -> await@27",
                "edge activity@29 -> await@32",
                "edge activity@30 -> await@32",
                "edge activity@34 -> end(main)",
                // Without a timeout there are only the reasons to go on.
                "edge await@24 -> activity@26 [approved]",
                "edge await@24 -> end(main) [rejected]",
                // A `when` without subject that asks for the result.
                "edge await@27 -(timeout)-> activity@29",
                "edge await@27 -> activity@30 [not approved]",
                // The result of the third await is not asked for: one edge, without a name.
                "edge await@32 -> branch@34",
                "edge branch@34 -> activity@34 [yes]",
                "edge branch@34 -> end(main) [no]",
                "edge start@23 -> await@24",
            ),
            graph("Awaits"),
        )
    }

    @Test
    fun `an arm no outcome leads into is dropped for a version gate, and keeps the test of an await a branch`() {
        assertEquals(
            listOf(
                "node activity@25: name Undo",
                "node activity@27: name Work",
                "node activity@28: name Report",
                "node await@24: reads cancelled, paid, label paid or cancelled",
                // Both reasons at once: neither of them alone leads here, but the code may get here.
                "node branch@25: label paid && cancelled",
                "node end(main)",
                "node start@23",
                "node versionGate@26: changeId layout",
                "edge activity@25 -> versionGate@26",
                "edge activity@27 -> end(main)",
                "edge activity@28 -> end(main)",
                "edge await@24 -> branch@25",
                "edge branch@25 -> activity@25 [yes]",
                "edge branch@25 -> versionGate@26 [no]",
                "edge start@23 -> await@24",
                // The gate only ever finds one of its two versions: the `else` that throws is never taken.
                "edge versionGate@26 -> activity@27 [v1]",
                "edge versionGate@26 -> activity@28 [v2]",
            ),
            graph("DeadArms"),
        )
    }

    @Test
    fun `an operator that runs its right-hand side only sometimes is a branch if that side ends the path`() {
        assertEquals(
            listOf(
                "node activity@22: name Prepare",
                "node branch@21: label id == null",
                "node branch@22: label tasks.prepare(known).takeIf { it.isNotEmpty() } == null",
                "node end(main)",
                "node fail@21: name MissingId",
                "node start@20",
                "edge activity@22 -> branch@22",
                "edge branch@21 -> activity@22 [no]",
                "edge branch@21 -> fail@21 [yes]",
                "edge branch@22 -> end(main) [no]",
                "edge branch@22 -> end(main) [yes]",
                "edge start@20 -> branch@21",
            ),
            graph("Elvis"),
        )
    }

    @Test
    fun `a saga runs what was registered with it, last first, wherever it was registered`() {
        assertEquals(
            listOf(
                "node activity@25: name Work, parent loop@24",
                // Registered in a loop, and in a branch that holds nothing else and is dropped.
                "node activity@26: name Undo, parent compensation@33",
                "node activity@29: name CleanUp, parent compensation@33",
                "node activity@31: name Report",
                "node compensation@33",
                // Another saga, with nothing registered, compensating in a detached scope.
                "node compensation@34: label detached",
                "node end(main)",
                "node loop@24: label for each id",
                "node start@20",
                "edge activity@25 -(exception)-> compensation@33 [ActivityFailure]",
                "edge activity@29 -> activity@26",
                "edge activity@31 -(exception)-> compensation@33 [ActivityFailure]",
                "edge activity@31 -> end(main)",
                "edge compensation@33 -> compensation@34",
                "edge compensation@34 -> end(main)",
                "edge loop@24 -> activity@31",
                "edge start@20 -> loop@24",
            ),
            graph("Sagas"),
        )
    }

    @Test
    fun `functions that run a lambda for each element are loops, and one that may run it once is not`() {
        assertEquals(
            listOf(
                "node activity@21: name Work, parent loop@21",
                "node activity@22: name Report, parent loop@22",
                "node activity@23: name Check, parent loop@23",
                "node activity@24: name CleanUp, parent loop@24",
                "node activity@27: name Undo, parent loop@25",
                "node activity@30: name Prepare",
                "node branch@22: label index > 0, parent loop@22",
                // `return@forEach` ends the round, as `continue` does.
                "node branch@26: label id.isEmpty(), parent loop@25",
                "node end(main)",
                // A reference to a stub's method in place of the lambda, so the element has no name.
                "node loop@21: label for each of ids",
                // The element is the last parameter of the lambda.
                "node loop@22: label for each id",
                "node loop@23: label for each of ids",
                "node loop@24: label repeat 3 times",
                "node loop@25: label for each id",
                "node start@20",
                // Not modelled: the lambda runs if the key is missing.
                "node unknown@30: label getOrPut, reason unsupported",
                "edge activity@30 -> end(main)",
                "edge branch@22 -> activity@22 [yes]",
                // An arm that does nothing more leads to the end of the round, and so does `return@forEach`.
                "edge branch@22 -> loop@22 [no]",
                "edge branch@26 -> activity@27 [no]",
                "edge branch@26 -> loop@25 [yes]",
                "edge loop@21 -> loop@22",
                "edge loop@22 -> loop@23",
                "edge loop@23 -> loop@24",
                "edge loop@24 -> loop@25",
                "edge loop@25 -> unknown@30",
                "edge start@20 -> loop@21",
                "edge unknown@30 -> activity@30",
                "edge unknown@30 -> end(main)",
            ),
            graph("Collections"),
        )
    }

    @Test
    fun `what a cancellation scope or a scope function is given runs in place, and a return may end it early`() {
        assertEquals(
            listOf(
                "node activity@21: name Work",
                "node activity@24: name Report",
                "node activity@26: name CleanUp",
                "node branch@23: label it.isEmpty()",
                "node end(main)",
                "node start@20",
                "edge activity@21 -> branch@23",
                "edge activity@24 -> activity@26",
                "edge activity@26 -> end(main)",
                "edge branch@23 -> activity@24 [no]",
                // `return@let` leaves the lambda, and the flow goes on behind it.
                "edge branch@23 -> activity@26 [yes]",
                "edge start@20 -> activity@21",
            ),
            graph("Scopes"),
        )
    }
}
