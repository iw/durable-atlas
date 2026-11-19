package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CodePrinter
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.walk
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
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
 * The Kotlin extensions of the Temporal SDK are read as what they stand for: options written as
 * a lambda as the builder of those options, a call of a stub that takes a type argument as the
 * stub's own call, a name asked for by reference as the name. Each workflow of
 * [KotlinExtensionSources] has a twin that says the same with the SDK's own functions, and the
 * two are to give the same graph.
 *
 * The sources are read twice: as a degraded model has them, and as a model made from the build
 * does. The extensions are on the classpath of both, so both are to read them alike.
 *
 * A node is written as its kind and its line, as [IrStructure] does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KotlinExtensionsExtractionTest {
    private lateinit var backend: SourceBackend
    private lateinit var degraded: List<IrDocument>
    private lateinit var built: List<IrDocument>
    private val warnings = ArrayList<String>()

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val sources = SyntheticSources(directory)
        KotlinExtensionSources.FILES.forEach { (name, text) -> sources.file(":app", "extensions/$name.kt", text) }
        backend = sources.open()
        val discovery = TemporalDiscovery.discover(backend)
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

    /** The nodes of a workflow without ids, and without the start and the end that every one has. */
    private fun List<IrDocument>.steps(workflowType: String): List<String> =
        of(workflowType).nodeOutline().filterNot { it.startsWith("start@") || it.startsWith("end(") }

    /** The nodes and edges of a document without ids and without lines: what a workflow and its twin are to have alike. */
    private fun IrDocument.shape(): List<String> = (nodeOutline().map { "node $it" } + edgeOutline().map { "edge $it" }).map { it.replace(LINE, "") }.sorted()

    @Test
    fun `every document is well formed, both models give the same, and those of the degraded one are the golden ones`() {
        val types = KotlinExtensionSources.WRITTEN.flatMap { listOf(it, "${it}Twin") } + listOf("Nameless", "WiredBare", "WiredByType")
        assertEquals(types.sorted(), degraded.map { it.workflowType })
        for (document in degraded + built) assertWellFormed(document)
        // Nothing is left for a degraded model to guess at: every call of an extension resolves.
        assertEquals(emptyList(), degraded.flatMap { it.skippedCalls })
        assertEquals(degraded.map { it.copy(modelMode = ModelMode.GRADLE) }, built)
        for (name in KotlinExtensionSources.WRITTEN + "Nameless") {
            GoldenFiles.assertMatches("ir/extensions/$name.ir.json", IrJson.encode(degraded.of(name)))
        }
    }

    @Test
    fun `a workflow written with the extensions reads like its twin written without them`() {
        for (documents in listOf(degraded, built)) {
            for (name in KotlinExtensionSources.WRITTEN) {
                // A workflow that continues as new continues as itself: the twin as the twin.
                assertEquals(documents.of("${name}Twin").shape().map { it.replace("${name}Twin", name) }, documents.of(name).shape(), name)
            }
        }
        // Only what cannot be told at all is unknown, in the twins as in the workflows.
        assertEquals(emptyList(), degraded.filter { it.workflowType != "Nameless" }.flatMap { it.nodesOf(NodeKind.UNKNOWN) }.map { it.inWords() })
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `options written as a lambda are the options their builder makes, and a copy begins with what it copies`() {
        assertEquals(
            listOf(
                // What the lambda sets.
                "activity@70: name One, taskQueue written, timeout PT7S",
                // The queue of the options that were copied, and the timeout the copy sets.
                "activity@71: name One, taskQueue base, timeout PT9S",
                // A copy of options made in place: what the copy sets counts, not what it copies.
                "activity@72: name Two, timeout PT2S",
                "localActivity@73: name One, timeout PT3S",
                // The time for all attempts comes before the time for one.
                "localActivity@74: name Two, timeout PT8S",
            ),
            degraded.steps("Optioned"),
        )
    }

    @Test
    fun `what a lambda sets twice counts as it is set last, and what it sets where it may not run is not known`() {
        assertEquals(
            listOf(
                "activity@73: name One, timeout PT2S",
                // The timeout is one of two; the queue is set either way.
                "activity@74: name Two, taskQueue either",
                // What is set for certain after the branch is what holds.
                "activity@75: name One, timeout PT4S",
            ),
            degraded.steps("Ordered"),
        )
    }

    @Test
    fun `a step among what a lambda of options is made of is a step where the options are made`() {
        // The lambda runs where it is written: nothing stands for code that is handed away.
        assertEquals(listOf("activity@57: name One, taskQueue base, timeout PT5S", "activity@60: name Two, timeout PT4S"), degraded.steps("Routed"))
        assertEquals(listOf("activity@57 -> activity@60", "activity@60 -> end(main)", "start@52 -> activity@57"), degraded.of("Routed").edgeOutline())
    }

    @Test
    fun `options of child workflows, of Nexus operations and of retries are read through the extensions`() {
        assertEquals(
            listOf(
                "childWorkflow@64: name SubFlow, waitsFor start, parentClosePolicy abandon",
                "childWorkflow@66: name SubFlow, taskQueue children, timeout PT10M",
            ),
            degraded.steps("Parented"),
        )
        // The second stub's options are a copy that sets the time again, and that is what counts for it.
        assertEquals(
            listOf("nexusOperation Greeter hello timeout PT1M", "nexusOperation Greeter hello timeout PT5M", "end"),
            degraded.of("Greeted").steps(),
        )
        assertEquals(
            listOf("retry on failure, up to 3 attempts, for up to 5m", "retry on failure, up to 5 attempts"),
            degraded.of("Retried").nodesOf(NodeKind.LOOP).map { it.label },
        )
    }

    @Test
    fun `a saga made with a lambda compensates as the lambda says, and one made with none one step after the other`() {
        // The compensations of the first run side by side, in a region inside the compensation.
        assertEquals(1, degraded.of("Compensated").nodesOf(NodeKind.PARALLEL).size)
        assertEquals(0, degraded.of("Plain").nodesOf(NodeKind.PARALLEL).size)
        assertEquals(1, degraded.of("Plain").nodesOf(NodeKind.COMPENSATION).size)
    }

    @Test
    fun `a call of a stub that takes a type argument is the step the stub's own call is`() {
        assertEquals(
            listOf(
                "activity@54: name Named, timeout PT6S",
                "activity@55: name One, timeout PT6S, parent parallel@55",
                "activity@56: name Two, timeout PT6S, parent parallel@55",
                "activity@58: name Forgotten, timeout PT6S, waitsFor nothing",
                "activity@64: name Meanwhile, timeout PT6S, parent parallel@63",
                "childWorkflow@61: name Waited, timeout PT3M",
                "childWorkflow@63: name Beside, parent parallel@63",
                "localActivity@59: name Nearby, timeout PT2S",
                "parallel@55",
                "parallel@63",
            ),
            degraded.steps("Stubbed"),
        )
    }

    @Test
    fun `a name asked for by reference is the name the SDK gives, and no step`() {
        assertEquals(
            listOf(
                "activity@53: name One, taskQueue base, timeout PT5S",
                // The name of a query, used here for an activity: it is the name that matters.
                "activity@59: name state, taskQueue base, timeout PT5S",
                "childWorkflow@55: name SubFlow",
                "childWorkflow@57: name SubFlow",
                "signalExternal@58: name nudge",
            ),
            degraded.steps("Named"),
        )
    }

    @Test
    fun `where the SDK has no name for what a reference names, the step is one whose name is not known`() {
        // The SDK fails there when the workflow runs. Nothing is drawn of the reference itself.
        assertEquals(
            listOf(
                "unknown@53: label activityName(Bare::run), reason computed-name",
                "unknown@54: label workflowSignalName(Sub::state), reason computed-name",
                "unknown@55: label workflowName<Bare>(), reason computed-name",
            ),
            degraded.steps("Nameless"),
        )
    }

    @Test
    fun `what a worker gives its workflows is read through the extensions`() {
        assertEquals(
            listOf(
                "activity@54: name One, timeout PT12S",
                "activity@55: name Two, timeout PT13S",
                "localActivity@56: name One, timeout PT1S",
                "localActivity@57: name Two, timeout PT3S",
            ),
            degraded.steps("Wired"),
        )
        // A class named by a type argument, with options whose default is set twice: the last one holds.
        assertEquals(listOf("activity@85: name One, timeout PT15S"), degraded.steps("WiredByType"))
        assertEquals(listOf("activity@97: name One"), degraded.steps("WiredBare"))
    }

    @Test
    fun `a worker's options are found where nothing but the extensions names them`(@TempDir directory: Path) {
        // No file names the class of the options: the lambda is all there is of them.
        val sources = SyntheticSources(directory)
            .file(
                ":app", "lean/Lean.kt",
                """
                package lean

                import io.temporal.activity.ActivityInterface
                import io.temporal.worker.Worker
                import io.temporal.worker.registerWorkflowImplementationType
                import io.temporal.worker.setDefaultActivityOptions
                import io.temporal.workflow.Workflow
                import io.temporal.workflow.WorkflowInterface
                import io.temporal.workflow.WorkflowMethod
                import java.time.Duration

                @ActivityInterface
                interface Chores {
                    fun one(input: String): String
                }

                @WorkflowInterface
                interface Lean {
                    @WorkflowMethod fun run()
                }

                class LeanImpl : Lean {
                    private val chores = Workflow.newActivityStub(Chores::class.java)
                    override fun run() {
                        chores.one("a")
                    }
                }

                fun wire(worker: Worker) {
                    worker.registerWorkflowImplementationType<LeanImpl> { setDefaultActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(21)) } }
                }
                """,
            )
        sources.open().use { lean ->
            val document = WorkflowGraphs(lean, FIXED_COMMIT, ModelMode.DEGRADED).extract(TemporalDiscovery.discover(lean)).single()
            assertEquals(listOf("activity One timeout PT21S", "end"), document.steps())
        }
    }

    @Test
    fun `the code that is handed out names the functions of the SDK and none of the extensions`() {
        val function = backend.functions("extensions.OptionedImpl").single { it.name == "run" }
        val plain = backend.properties("extensions.OptionedImpl").single { it.name == "plain" }
        val made = backend.initializer(plain.id)!!
        GoldenFiles.assertMatches("code/extensions/OptionedImpl.plain.txt", CodePrinter.print(made))

        val everything = KotlinExtensionSources.WRITTEN.flatMap { name ->
            val owner = "extensions.${name}Impl"
            backend.functions(owner).mapNotNull { backend.body(it.id) } + backend.properties(owner).mapNotNull { backend.initializer(it.id) }
        }
        val called = everything.flatMap { code -> code.walk().filterIsInstance<Call>().mapNotNull { it.callee?.function } }.toSet()
        // Every extension is a function of no class in a package of the SDK.
        assertEquals(emptyList(), called.filter { it.owner == null && it.name.startsWith("io.temporal.") }.map { it.toString() })
        assertTrue(called.any { it.name == "io.temporal.activity.ActivityOptions.newBuilder" })
        assertTrue(backend.body(function.id) === backend.body(function.id), "the code is the same object each time it is asked for")
    }

    private companion object {
        val LINE = Regex("@\\d+(#\\d+)?")
    }
}
