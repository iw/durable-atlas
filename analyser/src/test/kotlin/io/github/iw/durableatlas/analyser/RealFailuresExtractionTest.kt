package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.JavaCall
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.SourceLocation
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
 * A `fail` node stands where the execution of the workflow may fail. An exception that leaves a
 * workflow fails its execution only if it is a failure of Temporal or of a type a worker was
 * told to fail workflows on; any other fails the workflow task, which is tried again until the
 * code is fixed. A way that only leads to such a failure, with nothing drawn on it, is not
 * drawn: not the `throw`, not the branch at a call that only throws like that, not the decision
 * whose only other way it is. A way to a clause that takes the failure and does something
 * stays, and so does the end of a way that a step lies on, which has to lead somewhere.
 *
 * The sources of [RealFailuresSources] are read as a degraded model has them and as a model made
 * from the build does, with workers that are told of three exceptions of the domain: with the
 * builder of the options, with the options written as a lambda, and in a Java file. They are
 * read again without those workers, with one whose exceptions cannot be read, and with one that
 * fails workflows on whatever is thrown.
 *
 * Nodes are named by their kind and their line, as [IrStructure] writes them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealFailuresExtractionTest {
    private lateinit var backend: SourceBackend
    private lateinit var discovery: Discovery
    private lateinit var degraded: List<IrDocument>
    private lateinit var built: List<IrDocument>
    private val warnings = ArrayList<String>()

    @BeforeAll
    fun open(@TempDir directory: Path) {
        backend = sources(directory, workers = true).javaFile(":app", "failing/JavaWorkers.java", RealFailuresSources.JAVA_WORKERS).open()
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

    /** The sources, with or without the Kotlin file that tells workers of exceptions that fail a workflow. */
    private fun sources(directory: Path, workers: Boolean): SyntheticSources {
        val sources = SyntheticSources(directory)
        RealFailuresSources.FILES.filterKeys { workers || it != "Workers" }.forEach { (name, text) -> sources.file(":app", "failing/$name.kt", text) }
        return sources
    }

    /** The documents of [sources] in both models, which have to be the same, and what was warned of. */
    private fun read(sources: SyntheticSources): Pair<List<IrDocument>, List<String>> =
        sources.open().use { opened ->
            val found = TemporalDiscovery.discover(opened)
            val lenient = WorkflowGraphs(opened, FIXED_COMMIT, ModelMode.DEGRADED)
            val documents = lenient.extract(found)
            documents.forEach(::assertWellFormed)
            assertEquals(documents.map { it.copy(modelMode = ModelMode.GRADLE) }, WorkflowGraphs(opened, FIXED_COMMIT, ModelMode.GRADLE).extract(found))
            documents to lenient.warnings
        }

    private fun List<IrDocument>.of(workflowType: String): IrDocument = single { it.workflowType == workflowType }

    /** The nodes and edges of a workflow, without ids. */
    private fun graph(workflowType: String): List<String> = degraded.of(workflowType).graph()

    private fun IrDocument.graph(): List<String> = outline().filterNot { it.startsWith("entry point ") }

    /** The `fail` nodes and the branches of a workflow: where it may fail, and what it decides. */
    private fun IrDocument.failures(): List<String> = nodeOutline().filter { it.startsWith("fail") || it.startsWith("branch") }

    private fun failures(workflowType: String): List<String> = degraded.of(workflowType).failures()

    @Test
    fun `every document is well formed, both models give the same, and those of the degraded one are the golden ones`() {
        assertEquals(RealFailuresSources.WORKFLOWS, degraded.map { it.workflowType })
        for (document in degraded + built) assertWellFormed(document)
        assertEquals(emptyList(), degraded.flatMap { it.skippedCalls })
        assertEquals(emptyList(), (degraded + built).flatMap { it.nodes }.filter { it.reason == UnknownReasons.UNRESOLVED }.map { it.inWords() })
        assertEquals(degraded.map { it.copy(modelMode = ModelMode.GRADLE) }, built)
        assertEquals(emptyList(), warnings)
        for (name in RealFailuresSources.WORKFLOWS) GoldenFiles.assertMatches("ir/failures/$name.ir.json", IrJson.encode(degraded.of(name)))
    }

    @Test
    fun `an overview keeps every step of the full document, and its golden file is there where it differs`() {
        assertEquals(emptyList<String>(), assertOverviewsKeepTheSteps(backend, discovery, "overview/failures"))
    }

    @Test
    fun `sparing the functions that hold nothing changes no graph`() {
        assertTrue(assertSparingChangesNothing(backend, discovery) > 0)
    }

    // ---- what the workflow method throws itself

    @Test
    fun `the guards of the standard library leave nothing, as before`() {
        assertEquals(
            listOf(
                "node activity@33: name One, timeout PT5S",
                "node activity@36: name Two, timeout PT5S",
                "node end(main)",
                "node start@29",
                "edge activity@33 -> activity@36",
                "edge activity@36 -> end(main)",
                "edge start@29 -> activity@33",
            ),
            graph("Guarded"),
        )
    }

    @Test
    fun `a call that never returns and throws what fails the workflow task leaves nothing, nor does the decision that leads to it`() {
        assertEquals(
            listOf(
                "node activity@32: name One, timeout PT5S",
                "node activity@35: name Two, timeout PT5S",
                // The `when` keeps the ways that lead somewhere: `TODO` and `error` in its other arms are no such ways.
                "node branch@33: label count",
                "node end(main)",
                "node start@29",
                "edge activity@32 -> branch@33",
                "edge activity@35 -> end(main)",
                "edge branch@33 -> activity@35 [1]",
                "edge branch@33 -> end(main) [0]",
                // Neither `table[input] ?: error(…)` nor `if (count > 10) error(…)` is a decision to draw.
                "edge start@29 -> activity@32",
            ),
            graph("Erring"),
        )
    }

    @Test
    fun `a throw of an exception that fails the workflow task is not drawn, unless a step lies on the way to it`() {
        assertEquals(
            listOf(
                "node activity@30: name One, timeout PT5S",
                "node activity@33: name Two, timeout PT5S",
                "node activity@35: name Two, timeout PT5S",
                "node activity@39: name Two, timeout PT5S",
                "node activity@42: name One, timeout PT5S, parent loop@40",
                // Of the `when`, the arm that only throws is gone.
                "node branch@31",
                "node end(main)",
                // A step has to lead somewhere: what follows it is drawn as it was.
                "node fail@36: name IllegalStateException",
                "node loop@40: label for each index",
                "node start@28",
                "edge activity@30 -> branch@31",
                // `if (first.isEmpty()) throw RuntimeException(…) else jobs.two(first)` is the step alone.
                "edge activity@33 -> activity@39",
                "edge activity@35 -> fail@36",
                "edge activity@39 -> loop@40",
                "edge branch@31 -> activity@33 [count == 0]",
                "edge branch@31 -> activity@35 [count > 100]",
                "edge branch@31 -> activity@39 [else]",
                // In the loop, `if (index > 50) throw DomainFault(…)` leaves the step of the round.
                "edge loop@40 -> end(main)",
                "edge start@28 -> activity@30",
            ),
            graph("Thrown"),
        )
    }

    @Test
    fun `an exception a worker is told of fails the workflow, however the worker is told, and so does one that extends it`() {
        assertEquals(
            listOf(
                "branch@29: label input.isBlank()",
                "branch@30: label count > 5",
                "branch@32: label first == \"none\"",
                "branch@33: label first == \"unbooked\"",
                // With the builder of the options.
                "fail@29: name RejectedOrder",
                // A class that extends that one.
                "fail@30: name RejectedPayment",
                // With the options written as a lambda.
                "fail@32: name StockFault",
                // In a Java file.
                "fail@33: name LedgerFault",
                // `DomainFault`, which no worker is told of, is thrown at line 34 and leaves nothing.
            ),
            failures("Configured"),
        )
    }

    @Test
    fun `a failure of Temporal fails the workflow, by the name it is given`() {
        assertEquals(
            listOf(
                "branch@29: label input.isBlank()",
                "branch@30: label count < 0",
                "branch@32: label first.isEmpty()",
                "fail@29: name NothingGiven",
                "fail@30: name NegativeCount",
                "fail@34: name NoAnswer",
            ),
            failures("Failing"),
        )
    }

    // ---- what is caught and thrown again

    @Test
    fun `a saga that catches what its steps raise and throws it again keeps its failure`() {
        assertEquals(
            listOf(
                "node activity@31: name One, timeout PT5S",
                "node activity@32: name Two, timeout PT5S, parent compensation@35",
                "node activity@33: name Two, timeout PT5S",
                "node compensation@35",
                "node end(main)",
                // What is thrown again may be the failure of an activity.
                "node fail@36: name Exception",
                "node start@28",
                "edge activity@31 -(exception)-> compensation@35 [Exception]",
                "edge activity@31 -> activity@33",
                "edge activity@33 -(exception)-> compensation@35 [Exception]",
                "edge activity@33 -> end(main)",
                "edge compensation@35 -> fail@36",
                "edge start@28 -> activity@31",
            ),
            graph("Compensated"),
        )
    }

    @Test
    fun `what a clause throws again fails the workflow only if something that does may get to the clause`() {
        assertEquals(
            listOf(
                "node activity@41: name One, timeout PT5S",
                "node activity@46: name Two, timeout PT5S",
                "node activity@51: name One, timeout PT5S",
                "node activity@53: name Two, timeout PT5S",
                "node end(main)",
                // `Workflow.wrap(e)` hands on what a step may have raised.
                "node fail@43: name RuntimeException",
                // The failure of an activity, thrown again after a step.
                "node fail@54: name ActivityFailure",
                "node start@28",
                "edge activity@41 -(exception)-> fail@43 [Exception]",
                "edge activity@41 -> activity@46",
                // The clause at line 47 throws an exception of the domain in place of the failure: no way leads there.
                "edge activity@46 -> activity@51",
                "edge activity@51 -(exception)-> activity@53 [ActivityFailure]",
                "edge activity@51 -> end(main)",
                "edge activity@53 -> fail@54",
                // Nothing but ordinary code in the first two `try`s: what their clauses throw again fails the task.
                "edge start@28 -> activity@41",
            ),
            graph("Rethrown"),
        )
    }

    // ---- functions that are not drawn

    @Test
    fun `the branch at a call stays where something that fails the workflow may come out, or where a clause takes what does`() {
        assertEquals(
            listOf(
                "node activity@35: name One, timeout PT5S",
                "node activity@39: name Two, timeout PT5S",
                "node activity@45: name Two, timeout PT5S",
                "node activity@50: name Two, timeout PT5S",
                // A failure of Temporal.
                "node branch@32: label Checks.allowed throws?",
                // An exception a worker is told of.
                "node branch@33: label Checks.inStock throws?",
                // One of two fails the workflow.
                "node branch@34: label Checks.payable throws?",
                // What the clauses take: the branches have no way to a failure.
                "node branch@37: label Checks.positive throws?",
                "node branch@43: label Checks.known throws?",
                // A function that never returns and throws a failure of Temporal.
                "node branch@49: label again > 100",
                "node end(main)",
                "node fail@32: name CustomerBlocked",
                "node fail@33: name StockFault",
                // Only the one that fails the workflow is named.
                "node fail@34: name RejectedPayment",
                "node fail@49: name Refused",
                "node start@28",
                "edge activity@35 -> branch@37",
                "edge activity@39 -> branch@43",
                "edge activity@45 -> branch@49",
                "edge activity@50 -> end(main)",
                "edge branch@32 -> branch@33 [no]",
                "edge branch@32 -> fail@32 [yes]",
                "edge branch@33 -> branch@34 [no]",
                "edge branch@33 -> fail@33 [yes]",
                "edge branch@34 -> activity@35 [no]",
                "edge branch@34 -> fail@34 [yes]",
                "edge branch@37 -(exception)-> activity@39 [DomainFault]",
                "edge branch@37 -> branch@43 [no]",
                "edge branch@43 -(exception)-> activity@45 [CodeFault]",
                "edge branch@43 -> branch@49 [no]",
                "edge branch@49 -> activity@50 [no]",
                "edge branch@49 -> fail@49 [yes]",
                // `positive`, `known` and `parsed` at lines 29 to 31 only throw what fails the task, and so does
                // `unsupported` at line 48, which never returns.
                "edge start@28 -> branch@32",
            ),
            graph("Helped"),
        )
    }

    // ---- handlers

    @Test
    fun `a signal and an update keep what fails the workflow or the update, a validator and a query keep whatever they throw`() {
        val document = degraded.of("Handled")
        val owners = IrStructure.of(document).owners
        fun of(entryPoint: String): List<String> = document.copy(nodes = document.nodes.filter { owners[it.id] == entryPoint }).failures()

        assertEquals(listOf("branch@43: label reason.isBlank()", "fail@43: name NoReason"), of("signal-cancel"))
        // An `IllegalArgumentException` and a helper that throws an exception of the domain leave nothing.
        assertEquals(listOf("branch@49: label amount > 1000", "fail@49: name RejectedOrder"), of("signal-adjust"))
        // What fails the update; an `IllegalStateException` fails the workflow task.
        assertEquals(listOf("branch@54: label total + amount < 0", "fail@54: name BelowZero"), of("update-change"))
        assertEquals(emptyList(), of("update-rename"))
        // Any exception rejects the update.
        assertEquals(
            listOf(
                "branch@62: label amount > 1000",
                "branch@63: label Checks.positive throws?",
                "fail@62: name IllegalArgumentException",
                "fail@63: name DomainFault",
            ),
            of("validator-change"),
        )
        // Any exception fails the query.
        assertEquals(listOf("branch@72: label cancelled", "fail@72: name IllegalStateException"), of("query-total"))
    }

    // ---- lanes, the making of the workflow object, `finally`

    @Test
    fun `a lane that is waited for follows the rule, and one that nothing waits for ends where it throws, as before`() {
        assertEquals(
            listOf(
                // The first lane throws an exception of the domain: its step is all there is.
                "branch@34: label input.length > 10, parent parallel@29",
                "branch@38: label input == \"quiet\", parent parallel@37",
                "fail@34: name TooLong",
            ),
            failures("Laned"),
        )
        assertTrue("edge branch@38 -> parallel@37 [yes]" in graph("Laned"))
    }

    @Test
    fun `what making the workflow object throws follows the rule`() {
        assertEquals(listOf("branch@28: label getenv(\"REGION\") == null", "fail@28: name NoRegion"), failures("Made"))
    }

    @Test
    fun `a finally is drawn for the ways that are, and for a way that leads nowhere else`() {
        assertEquals(
            listOf(
                "node activity@32: name One, timeout PT5S",
                "node activity@34#1: name Two, timeout PT5S",
                // The copy for the failure of Temporal; the exception of the domain at line 30 gets none.
                "node activity@34#2: name Two, timeout PT5S",
                // The only way through the second `try` fails the task: its `finally` runs nowhere else, so the way stays as it was.
                "node activity@40: name Two, timeout PT5S",
                "node branch@31: label input.isBlank()",
                "node branch@36: label input == \"stop\"",
                "node end(main)",
                "node fail@31: name NothingGiven",
                "node fail@38: name DomainFault",
                "node start@28",
                "edge activity@32 -> activity@34#1",
                "edge activity@34#1 -> branch@36",
                "edge activity@34#2 -> fail@31",
                "edge activity@40 -> fail@38",
                "edge branch@31 -> activity@32 [no]",
                "edge branch@31 -> activity@34#2 [yes]",
                "edge branch@36 -> activity@40 [yes]",
                "edge branch@36 -> end(main) [no]",
                "edge start@28 -> branch@31",
            ),
            graph("Cleaning"),
        )
    }

    // ---- what is drawn has to lead somewhere

    @Test
    fun `where a step can only end in a failure of the workflow task, the way is drawn as it was`() {
        assertEquals(
            listOf(
                "node activity@30: name One, timeout PT5S",
                "node activity@35: name One, timeout PT5S",
                "node activity@44: name One, timeout PT5S",
                "node activity@50: name One, timeout PT5S",
                "node activity@58: name Two, timeout PT5S",
                "node branch@29: label count == 1",
                // Both ways of the decision after the step fail the task: it stays whole.
                "node branch@31: label input.isBlank()",
                "node branch@33: label count == 2",
                "node branch@41: label count == 3",
                "node branch@49: label count == 4",
                // And so does this one, with the branch at the call of a helper on one of its ways.
                "node branch@51: label input.isBlank()",
                "node branch@52: label Checks.positive throws?",
                "node fail@31#1: name DomainFault",
                "node fail@31#2: name IllegalStateException",
                // The clause that the `throw` after the step ends in stays with it.
                "node fail@38: name IllegalStateException",
                "node fail@52: name DomainFault",
                "node fail@53: name DomainFault",
                "node fail@55: name IllegalStateException",
                // The workflow ends in `TODO`, after a step.
                "node fail@59",
                "node start@28",
                "edge activity@30 -> branch@31",
                "edge activity@35 -(exception)-> fail@38 [DomainFault]",
                // A clause like it that only a decision leads to is gone with the decision.
                "edge activity@44 -> branch@49",
                "edge activity@50 -> branch@51",
                "edge activity@58 -> fail@59",
                "edge branch@29 -> activity@30 [yes]",
                "edge branch@29 -> branch@33 [no]",
                "edge branch@31 -> fail@31#1 [yes]",
                "edge branch@31 -> fail@31#2 [no]",
                "edge branch@33 -> activity@35 [yes]",
                "edge branch@33 -> branch@41 [no]",
                "edge branch@41 -> activity@44 [yes]",
                "edge branch@41 -> branch@49 [no]",
                "edge branch@49 -> activity@50 [yes]",
                "edge branch@49 -> activity@58 [no]",
                "edge branch@51 -> branch@52 [yes]",
                "edge branch@51 -> fail@55 [no]",
                "edge branch@52 -> fail@52 [yes]",
                "edge branch@52 -> fail@53 [no]",
                "edge start@28 -> branch@29",
            ),
            graph("Cornered"),
        )
    }

    // ---- what workers are told

    @Test
    fun `without a worker that is told of them, the exceptions of the domain fail the workflow task`(@TempDir directory: Path) {
        val (documents, warned) = read(sources(directory, workers = false))

        assertEquals(emptyList(), warned)
        assertEquals(emptyList(), documents.of("Configured").failures())
        assertEquals(
            listOf(
                "branch@32: label Checks.allowed throws?",
                "branch@37: label Checks.positive throws?",
                "branch@43: label Checks.known throws?",
                "branch@49: label again > 100",
                "fail@32: name CustomerBlocked",
                "fail@49: name Refused",
            ),
            documents.of("Helped").failures(),
        )
        assertEquals(listOf("branch@43: label reason.isBlank()", "fail@43: name NoReason"), documents.of("Handled").failures().filter { "@43" in it })
        assertEquals(0, documents.of("Handled").nodes.count { it.name == "RejectedOrder" })
        // Nothing else depends on the workers.
        val same = RealFailuresSources.WORKFLOWS - setOf("Configured", "Helped", "Handled")
        assertEquals(same.map { degraded.of(it) }, same.map { documents.of(it) })
    }

    @Test
    fun `where a worker is told its exceptions through a value, every failure is drawn as it was, and a warning says where`(@TempDir directory: Path) {
        val (unreadable, warned) = read(sources(directory.resolve("a"), workers = true).file(":app", "failing/Unreadable.kt", RealFailuresSources.UNREADABLE_WORKER))
        val (strict, quiet) = read(sources(directory.resolve("b"), workers = true).file(":app", "failing/Strict.kt", RealFailuresSources.STRICT_WORKER))

        assertEquals(
            listOf(
                "app/src/main/kotlin/failing/Unreadable.kt:8: which exceptions fail a workflow here cannot be told; " +
                    "every exception that may leave a workflow is drawn as its failure",
            ),
            warned,
        )
        assertEquals(emptyList(), quiet)
        // A worker that fails workflows on whatever is thrown gives the same.
        assertEquals(strict, unreadable)
        assertEquals(
            listOf(
                "branch@29: label input.isBlank()",
                "branch@31",
                "branch@39: label first.isEmpty()",
                "branch@41: label index > 50, parent loop@40",
                "fail@29: name DomainFault",
                "fail@32: name IllegalArgumentException",
                "fail@36: name IllegalStateException",
                "fail@39: name RuntimeException",
                "fail@41: name DomainFault",
            ),
            strict.of("Thrown").failures(),
        )
        // A call of the standard library that never returns is a failure without a name, as it was.
        assertEquals(
            listOf("branch@30: label table[input] == null", "branch@31: label count > 10", "branch@33: label count", "fail@30", "fail@31", "fail@36", "fail@37"),
            strict.of("Erring").failures(),
        )
        // The three clauses that throw what fails the task have their failures again.
        assertEquals(3, strict.of("Rethrown").nodes.count { it.kind == NodeKind.FAIL } - degraded.of("Rethrown").nodes.count { it.kind == NodeKind.FAIL })
    }

    @Test
    fun `a workflow that is registered by a factory without options is failed on whatever is thrown, in Kotlin and in Java`(@TempDir directory: Path) {
        val (strict, _) = read(sources(directory.resolve("a"), workers = true).file(":app", "failing/Strict.kt", RealFailuresSources.STRICT_WORKER))
        val (kotlin, warned) = read(sources(directory.resolve("b"), workers = true).file(":app", "failing/Factory.kt", RealFailuresSources.FACTORY_WORKER))
        val (java, _) = read(sources(directory.resolve("c"), workers = true).javaFile(":app", "failing/JavaFactories.java", RealFailuresSources.JAVA_FACTORY_WORKER))
        val (optioned, _) = read(sources(directory.resolve("d"), workers = true).file(":app", "failing/Factory.kt", RealFailuresSources.OPTIONED_FACTORY_WORKER))

        assertEquals(emptyList(), warned)
        assertEquals(strict, kotlin)
        assertEquals(strict, java)
        // A factory that is registered with options, or by the function that is not kept for tests, says nothing by itself.
        // The Java file that tells a worker of `LedgerFault` is not among these sources.
        val told = degraded.filter { it.workflowType != "Configured" }
        assertEquals(told, optioned.filter { it.workflowType != "Configured" })
        assertEquals(listOf("RejectedOrder", "RejectedPayment", "StockFault"), optioned.of("Configured").nodes.filter { it.kind == NodeKind.FAIL }.mapNotNull { it.name }.sorted())
    }

    @Test
    fun `the calls of a Java file are found with the classes they name`() {
        assertEquals(
            listOf(JavaCall(SourceLocation("app/src/main/java/failing/JavaWorkers.java", 11), listOf("failing.LedgerFault"))),
            backend.javaCalls("io.temporal.worker.WorkflowImplementationOptions.Builder", "setFailWorkflowExceptionTypes"),
        )
        assertEquals(emptyList(), backend.javaCalls("io.temporal.worker.WorkflowImplementationOptions", "<init>"))
        // Anything that is no class literal is told apart from one, with its place.
        assertEquals(
            listOf(JavaCall(SourceLocation("app/src/main/java/failing/JavaWorkers.java", 12), listOf(null, "failing.ConfiguredImpl"))),
            backend.javaCalls("io.temporal.worker.Worker", "registerWorkflowImplementationTypes"),
        )
    }
}
