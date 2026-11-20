package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CallableReference
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.walk
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What becomes of the calls that do not resolve. A degraded model lacks the libraries the
 * sources use, so there a call that cannot be a step of the workflow is read as the call of a
 * library function that it is in a model made from the build: no node, and the code handed to
 * it read as for any library function. A call that may be a step keeps its node.
 *
 * The workflows of [UnresolvedCallSources] are read three ways:
 * - `degraded`: the Kotlin files alone, as a degraded model has them;
 * - `built`: the same files with the libraries they use, as a model made from the build has
 *   them, which is what the degraded reading is measured against;
 * - `strict`: the Kotlin files alone, read as if the model were made from the build. There a
 *   call that does not resolve is broken code or a missing dependency, and every one is drawn.
 *
 * A node is written as its kind and its line, as [IrStructure] does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnresolvedCallsExtractionTest {
    private lateinit var alone: SourceBackend
    private lateinit var whole: SourceBackend
    private lateinit var degraded: List<IrDocument>
    private lateinit var built: List<IrDocument>
    private lateinit var strict: List<IrDocument>

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val kotlinOnly = SyntheticSources(directory.resolve("alone"))
        val withLibraries = SyntheticSources(directory.resolve("whole"))
        UnresolvedCallSources.FILES.forEach { (name, text) ->
            kotlinOnly.file(":app", "calls/$name.kt", text)
            withLibraries.file(":app", "calls/$name.kt", text)
        }
        UnresolvedCallSources.LIBRARY.forEach { (path, text) -> withLibraries.javaFile(":app", path, text) }
        alone = kotlinOnly.open()
        whole = withLibraries.open()
        degraded = WorkflowGraphs(alone, FIXED_COMMIT, ModelMode.DEGRADED).extract(TemporalDiscovery.discover(alone))
        built = WorkflowGraphs(whole, FIXED_COMMIT, ModelMode.GRADLE).extract(TemporalDiscovery.discover(whole))
        strict = WorkflowGraphs(alone, FIXED_COMMIT, ModelMode.GRADLE).extract(TemporalDiscovery.discover(alone))
    }

    @AfterAll
    fun close() {
        alone.close()
        whole.close()
    }

    private fun List<IrDocument>.of(workflowType: String): IrDocument = single { it.workflowType == workflowType }

    /** The nodes of a workflow without ids, and without the start and the end that every one has. */
    private fun List<IrDocument>.steps(workflowType: String): List<String> =
        of(workflowType).nodeOutline().filterNot { it.startsWith("start@") || it.startsWith("end(") }

    /** The calls a document left out, as `File.kt:line text`. */
    private fun IrDocument.skipped(): List<String> = skippedCalls.map { "${it.loc.file.substringAfterLast('/')}:${it.loc.line} ${it.text}" }

    private fun List<IrDocument>.unresolved(): Int = sumOf { document -> document.nodes.count { it.reason == UnknownReasons.UNRESOLVED } }

    @Test
    fun `an overview keeps every step of the full document, and its golden file is there where it differs`() {
        // The blocks handed to two functions of a missing library stand where the functions are called.
        assertEquals(listOf("Handed"), assertOverviewsKeepTheSteps(alone, TemporalDiscovery.discover(alone), "overview/calls"))
    }

    @Test
    fun `every document is well formed, and those of the degraded model are the golden ones`() {
        val types = listOf("Foreign", "Handed", "Logged", "Looped", "Newer", "Priced", "Served", "Starred", "Supplied")
        for (documents in listOf(degraded, built, strict)) {
            assertEquals(types, documents.map { it.workflowType })
            documents.forEach { assertWellFormed(it) }
        }
        for (document in degraded) GoldenFiles.assertMatches("ir/calls/${document.workflowType}.ir.json", IrJson.encode(document))
    }

    @Test
    fun `only a degraded model leaves calls out, and far fewer of its calls are drawn as unresolved`() {
        assertEquals(emptyList(), built.flatMap { it.skippedCalls })
        assertEquals(emptyList(), strict.flatMap { it.skippedCalls })
        assertEquals(29, degraded.sumOf { it.skippedCalls.size })
        // Every call that does not resolve is a node where the model is taken to be whole.
        assertEquals(45, strict.unresolved())
        // What is left are the calls that may be steps.
        assertEquals(13, degraded.unresolved())
        // With the libraries on the classpath, what does not resolve is what nothing declares.
        assertEquals(6, built.unresolved())
    }

    // ---- calls into a library

    @Test
    fun `calls into a logging library leave nothing, in the workflow and in a helper of another file`() {
        val steps = listOf("activity@32: name One, timeout PT5S", "activity@33: name Two, timeout PT5S")
        assertEquals(steps, degraded.steps("Logged"))
        assertEquals(steps, built.steps("Logged"))
        assertEquals(degraded.of("Logged").edgeOutline(), built.of("Logged").edgeOutline())

        // None of them is lost from sight: those of the helper are listed with the workflow that reaches them.
        assertEquals(
            listOf(
                "Logged.kt:27 KotlinLogging.logger(\"logged\")",
                "Logged.kt:33 logger.info(\"done: \" + chores.two(input))",
                "LoggerExtensions.kt:22 log.info { message }",
                "LoggerExtensions.kt:23 log.atInfo()",
                "LoggerExtensions.kt:23 log.atInfo().log(message)",
                "LoggerExtensions.kt:26 debug { message }",
            ),
            degraded.of("Logged").skipped(),
        )
        // Read strictly, each of them is a node.
        assertEquals(
            listOf(
                "unknown@Logged.kt:27: label logger, reason unresolved",
                "unknown@Logged.kt:33: label info, reason unresolved",
                "unknown@LoggerExtensions.kt:22: label info, reason unresolved",
                "unknown@LoggerExtensions.kt:23#1: label atInfo, reason unresolved",
                "unknown@LoggerExtensions.kt:23#2: label log, reason unresolved",
                "unknown@LoggerExtensions.kt:26: label debug, reason unresolved",
            ),
            strict.steps("Logged").filter { it.startsWith("unknown") },
        )
    }

    @Test
    fun `a helper that only calls into a library holds nothing, however deep calls are followed`() {
        // Cut off before the helper is read, the workflow shows no call that was not followed: there is nothing behind it.
        val workflow = TemporalDiscovery.discover(alone).workflows.single { it.name == "Logged" }
        val shallow = WorkflowGraphs(alone, FIXED_COMMIT, ModelMode.DEGRADED, maxDepth = 0).extract(workflow, workflow.implementations.single())

        assertEquals(emptyList(), shallow.nodesOf(NodeKind.UNKNOWN).map { it.inWords() })
        assertEquals(degraded.of("Logged").skipped(), shallow.skipped())
    }

    @Test
    fun `code handed to a call into a library is read as for a library function`() {
        // The lambda may run or not: a node says that nothing is known of that, and what the lambda holds follows it.
        assertEquals(
            listOf(
                "activity@29: name One, timeout PT5S",
                "activity@31: name Two, timeout PT5S",
                "unknown@29: label Retry.retrying, reason unsupported",
                "unknown@31: label each, reason unsupported",
            ),
            degraded.steps("Handed"),
        )
        // The model made from the build draws the same. It knows the class of what `each` is called on,
        // and it has nothing that declares `tally`, which a degraded model takes for a library's.
        assertEquals(
            listOf(
                "activity@29: name One, timeout PT5S",
                "activity@31: name Two, timeout PT5S",
                "unknown@29: label Retry.retrying, reason unsupported",
                "unknown@31: label Money.each, reason unsupported",
                "unknown@33: label tally, reason unresolved",
            ),
            built.steps("Handed"),
        )
        assertEquals(
            listOf(
                "activity@29 -> unknown@31",
                "activity@31 -> end(main)",
                "start@28 -> unknown@29",
                "unknown@29 -> activity@29",
                "unknown@29 -> unknown@31",
                "unknown@31 -> activity@31",
                "unknown@31 -> end(main)",
            ),
            degraded.of("Handed").edgeOutline(),
        )
        assertEquals(
            listOf(
                "Handed.kt:29 Retry.retrying(3) { chores.one(input) }",
                "Handed.kt:30 Money.of(2)",
                "Handed.kt:31 money.each { chores.two(input) }",
                "Handed.kt:32 money.text()",
                "Handed.kt:33 tally(input)",
            ),
            degraded.of("Handed").skipped(),
        )
    }

    @Test
    fun `a lambda handed to what can only be a function that runs it for each element is a loop`() {
        // The collection is of a type the model lacks. The compiler still names the functions `forEach` and
        // `onEach` may be, and each of them runs its lambda once for each element.
        val steps = listOf(
            "activity@30: name One, timeout PT5S, parent loop@30",
            "activity@32: name Two, timeout PT5S, parent loop@32",
            "loop@30: label for each part",
            "loop@32: label for each of money.parts()",
        )
        assertEquals(steps, degraded.steps("Looped"))
        assertEquals(steps, built.steps("Looped"))
        assertEquals(built.of("Looped").edgeOutline(), degraded.of("Looped").edgeOutline())
        // The calls are listed all the same: none of them resolved.
        assertEquals(
            listOf(
                "Looped.kt:29 Money.of(4)",
                "Looped.kt:30 money.parts()",
                "Looped.kt:30 money.parts().forEach { part -> chores.one(part) }",
                "Looped.kt:31 money.parts()",
                "Looped.kt:31 money.parts().map { it.length }",
                "Looped.kt:32 money.parts()",
                "Looped.kt:32 money.parts().onEach { chores.two(it) }",
            ),
            degraded.of("Looped").skipped(),
        )
        // `map` may also be the `map` of a result or of a text, which run their lambda once at most: no loop is claimed for it.
        val map = calls("LoopedImpl").named("map")
        assertTrue(map.unresolved?.candidates.orEmpty().any { it.function.name == "kotlin.map" })
    }

    // ---- calls that may be steps

    @Test
    fun `a call through a stub whose interface did not resolve keeps a node that names it`() {
        assertEquals(
            listOf(
                // Whatever is called on a stub made to continue as new continues as new; only the workflow type is not known.
                "continueAsNew@37",
                "parallel@33",
                "unknown@31: label JavaJobs.charge, reason unresolved",
                "unknown@32: label JavaJobs.charge, reason unresolved",
                // A reference to a function of the stub, handed to `Async`.
                "unknown@33: label JavaJobs.charge, reason unresolved, parent parallel@33",
                // The stub as the `this` of a scope function.
                "unknown@34: label JavaJobs.refund, reason unresolved",
                "unknown@35: label JavaChild.start, reason unresolved",
                // The call that makes the stub does not resolve either: it can only be the one maker, and is no step.
                "unknown@36: label JavaChild.poke, reason unresolved",
            ),
            degraded.steps("Foreign"),
        )
        // With the interfaces, each of them is the step it is, in the same place.
        assertEquals(
            listOf(
                "activity@31: name Charge, timeout PT5S",
                "activity@33: name Charge, timeout PT5S, parent parallel@33",
                "activity@34: name Refund, timeout PT5S",
                "childWorkflow@35: name JavaChild",
                "continueAsNew@37: name JavaChild",
                "localActivity@32: name Charge",
                "parallel@33",
                "signalExternal@36: name poke, label JavaChild.poke",
            ),
            built.steps("Foreign"),
        )
        val lines = Regex("@(\\d+)")
        fun shape(edges: List<String>) = edges.map { edge -> lines.findAll(edge).joinToString(" -> ") { it.groupValues[1] } }.sorted()
        assertEquals(shape(built.of("Foreign").edgeOutline()), shape(degraded.of("Foreign").edgeOutline()))
        assertEquals(emptyList(), degraded.of("Foreign").skipped())

        // Read strictly, the reference handed to `Async` left nothing at all, and the maker of the stub was a node.
        assertEquals(
            listOf(
                "unknown@31: label charge, reason unresolved",
                "unknown@32: label charge, reason unresolved",
                "unknown@34: label refund, reason unresolved",
                "unknown@35: label start, reason unresolved",
                "unknown@36#1: label newExternalWorkflowStub, reason unresolved",
                "unknown@36#2: label poke, reason unresolved",
                "unknown@37: label start, reason unresolved",
            ),
            strict.steps("Foreign"),
        )
    }

    @Test
    fun `a stub that cannot be followed is known by the class it is declared with`() {
        // The desk is taken out of a list, so nothing says what its constructor was given. `JavaJobs` is a
        // class the sources make stubs of; `KLogger` is not.
        assertEquals(listOf("unknown@22: label JavaJobs.charge, reason unresolved"), degraded.steps("Served"))
        assertEquals(listOf("Served.kt:21 log.info(input)", "Served.kt:33 KotlinLogging.logger(\"desk\")"), degraded.of("Served").skipped())
        assertEquals(listOf("activity@22: name Charge"), built.steps("Served"))
    }

    @Test
    fun `what a library gives back that was handed a stub interface may be a stub of it`() {
        // `one` is a function of the interface; `describe` is not.
        assertEquals(listOf("unknown@29: label Chores.one, reason unresolved"), degraded.steps("Supplied"))
        assertEquals(
            listOf(
                "Supplied.kt:26 Stubs.activity(Chores::class.java)",
                "Supplied.kt:30 Stubs.activity(Chores::class.java)",
                "Supplied.kt:30 Stubs.activity(Chores::class.java).describe()",
            ),
            degraded.of("Supplied").skipped(),
        )
        assertEquals(listOf("activity@29: name One", "unknown@30: label describe, reason unresolved"), built.steps("Supplied"))
    }

    @Test
    fun `a call that names the Temporal API keeps its node, and reads as what it is where that is certain`() {
        assertEquals(
            listOf(
                "activity@32: name One, timeout PT5S",
                // `Workflow.sleep` with a length of time of a type that did not resolve: whichever `sleep` it is, a timer.
                "timer@31",
                // A function the class of the SDK does not have.
                "unknown@29: label Workflow.brandNew, reason unresolved",
                // A class that an import says is of the SDK, and what it gives back.
                "unknown@30#1: label NewThing.create, reason unresolved",
                "unknown@30#2: label launch, reason unresolved",
            ),
            degraded.steps("Newer"),
        )
        assertEquals(listOf("Newer.kt:31 Money.of(3)", "Newer.kt:31 Money.of(3).pause()"), degraded.of("Newer").skipped())
        // The model made from the build has the same nodes: the SDK it resolves against lacks the same.
        assertEquals(
            listOf(
                "activity@32: name One, timeout PT5S",
                "timer@31",
                "unknown@29: label brandNew, reason unresolved",
                "unknown@30#1: label create, reason unresolved",
                "unknown@30#2: label launch, reason unresolved",
            ),
            built.steps("Newer"),
        )
        assertEquals(built.of("Newer").edgeOutline(), degraded.of("Newer").edgeOutline())
    }

    @Test
    fun `a name that nothing declares may be of a Temporal package that is imported as a whole`() {
        val steps = listOf("timer@14: duration PT1S", "unknown@13: label somethingNew, reason unresolved")
        assertEquals(steps, degraded.steps("Starred"))
        assertEquals(steps, built.steps("Starred"))
        assertEquals(emptyList(), degraded.of("Starred").skipped())
        // Where every import names what it imports, the same kind of call is a library's: `tally` in `Handed`.
        assertTrue("Handed.kt:33 tally(input)" in degraded.of("Handed").skipped())
    }

    @Test
    fun `a call of a function of the sources is followed where it is settled, and kept where it is not`() {
        assertEquals(
            listOf(
                // `send` takes something of a type that did not resolve, and is still the one function of its name.
                "activity@20: name One, timeout PT5S",
                // Two functions are called `pick`, and what tells them apart did not resolve: one of them runs.
                "unknown@43: label pick, reason unresolved, candidates calls.Pricing.pick(kotlin.Int,calls.Chores), calls.Pricing.pick(kotlin.String,calls.Chores)",
            ),
            degraded.steps("Priced"),
        )
        // Neither `note` holds a step, so the call that may be either leaves nothing, as it does once it is settled.
        assertEquals(listOf("activity@20: name One, timeout PT5S", "activity@22: name One, timeout PT5S"), built.steps("Priced"))
        // What `send` calls on its argument is listed too: the function was read for this workflow.
        assertEquals(
            listOf("Priced.kt:20 amount.text()", "Priced.kt:41 Money.of(1)", "Priced.kt:43 money.text()", "Priced.kt:44 money.text()"),
            degraded.of("Priced").skipped(),
        )
    }

    // ---- what the sources still say of a call that does not resolve

    private fun calls(className: String, function: String = "run"): List<Call> {
        val declared = alone.functions("calls.$className").single { it.name == function }
        return assertNotNull(alone.body(declared.id)).walk().filterIsInstance<Call>().toList()
    }

    private fun List<Call>.named(name: String): Call = first { it.name == name }

    @Test
    fun `an unresolved call says what its imports and its qualifier make of the name it begins with`() {
        val newer = calls("NewerImpl")
        assertEquals(listOf("io.temporal.workflow.NewThing"), newer.named("create").unresolved?.names)
        // The call on what `create` gives back begins with the same name.
        assertEquals(listOf("io.temporal.workflow.NewThing"), newer.named("launch").unresolved?.names)
        // `Workflow` resolves: the class says where the call goes, and no name is left to explain.
        assertEquals(emptyList(), newer.named("brandNew").unresolved?.names)
        assertEquals(listOf("com.acme.util.Money"), newer.named("of").unresolved?.names)
        // No import gives the name, so it may come from the package that is imported as a whole.
        assertEquals(listOf("io.temporal.workflow.somethingNew"), calls("StarredImpl").named("somethingNew").unresolved?.names)
        assertEquals(emptyList(), calls("HandedImpl").named("tally").unresolved?.names)
        // A call that resolves has nothing to say.
        assertNull(calls("LoggedImpl").named("one").unresolved)
    }

    @Test
    fun `an unresolved call names the functions the compiler could not choose among`() {
        val pick = calls("PricedImpl").named("pick")
        assertNull(pick.callee)
        assertEquals(
            listOf("calls.Pricing.pick(kotlin.Int,calls.Chores)", "calls.Pricing.pick(kotlin.String,calls.Chores)"),
            pick.unresolved?.candidates?.map { it.function.toString() },
        )
        assertTrue(pick.unresolved?.candidates.orEmpty().all { it.inSource })
        // One function alone is what the call resolves to, whatever is missing of its argument.
        assertEquals("calls.Pricing.send", calls("PricedImpl").named("send").callee?.function?.name)
        val sleep = calls("NewerImpl").named("sleep")
        assertEquals(
            listOf("io.temporal.workflow.Workflow.sleep(java.time.Duration)", "io.temporal.workflow.Workflow.sleep(kotlin.Long)"),
            sleep.unresolved?.candidates?.map { it.function.toString() },
        )
    }

    @Test
    fun `an unresolved call says what class its object is declared to be of, where that class is missing`() {
        val foreign = calls("ForeignImpl")
        // `local` is declared as `JavaJobs`; the type of `jobs` is only what the call that made it gives.
        assertEquals(listOf(null, "JavaJobs"), foreign.filter { it.name == "charge" }.map { it.unresolved?.receiverType })
        val serve = calls("Desk", "serve")
        assertEquals("KLogger", serve.named("info").unresolved?.receiverType)
        assertEquals("JavaJobs", serve.named("charge").unresolved?.receiverType)
        // A function that extends a missing class says which.
        val step = assertNotNull(calls("LoggedImpl").named("step").callee)
        assertEquals("KLogger", alone.function(step.function)?.receiver?.unresolved)
        // A reference to a function of something whose class is missing names nothing, and says what it is on.
        val reference = assertNotNull(alone.body(alone.functions("calls.ForeignImpl").single { it.name == "run" }.id))
            .walk().filterIsInstance<CallableReference>().single()
        assertNull(reference.function)
        assertEquals("jobs::charge", reference.text)
    }
}
