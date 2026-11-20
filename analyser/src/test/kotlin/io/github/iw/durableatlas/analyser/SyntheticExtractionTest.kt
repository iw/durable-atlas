package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.IrFiles
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
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Graph extraction on sources written for the purpose: the straight-line forms of what the sample
 * project only has inside branches, and cases the sample project does not contain. How control
 * flow is drawn is tried in [ControlFlowExtractionTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyntheticExtractionTest {
    private lateinit var sources: SyntheticSources
    private lateinit var backend: SourceBackend
    private lateinit var documents: List<IrDocument>

    @BeforeAll
    fun open(@TempDir directory: Path) {
        sources = SyntheticSources(directory)
            .file(":app", "linear/Linear.kt", LINEAR)
            .file(":app", "assorted/Assorted.kt", ASSORTED)
            .file(":app", "naming/first/SameName.kt", SAME_NAME_FIRST)
            .file(":app", "naming/second/SameName.kt", SAME_NAME_SECOND)
            .file(":app", "naming/Others.kt", OTHER_NAMES)
        backend = sources.open()
        documents = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.DEGRADED).extract(TemporalDiscovery.discover(backend))
    }

    @AfterAll
    fun close() {
        backend.close()
    }

    private fun document(workflowType: String): IrDocument = documents.single { it.workflowType == workflowType }

    @Test
    fun `every document is well formed`() {
        assertEquals(17, documents.size)
        documents.forEach { assertWellFormed(it) }
    }

    // ---- straight-line code

    @Test
    fun `an overview keeps every step of the full document, and its golden file is there where it differs`() {
        val golden = listOf("AwaitingWorkflow", "Poller", "RelayWorkflow", "RejectingWorkflow")
        // One workflow that has no golden file hands a block to a function that is not modelled.
        assertEquals(listOf("ReturnsWorkflow"), assertOverviewsKeepTheSteps(backend, TemporalDiscovery.discover(backend), "overview/linear", only = golden))
    }

    @Test
    fun `straight-line awaits, continue-as-new and failures give the golden IR`() {
        for (workflowType in listOf("AwaitingWorkflow", "Poller", "RelayWorkflow", "RejectingWorkflow")) {
            GoldenFiles.assertMatches("ir/linear/$workflowType.ir.json", IrJson.encode(document(workflowType)))
        }
    }

    @Test
    fun `an await without timeout has the properties of the workflow that its condition reads`() {
        // The task queue is a constant, and the schedule-to-close timeout a property of another file's scope.
        // The label is the condition in words. Nothing asks why the await went on, so one edge leaves it.
        assertEquals(
            listOf("await (approved or cancelled) reads approved, cancelled", "activity MailSend timeout PT2H on mail", "end"),
            document("AwaitingWorkflow").steps(),
        )
    }

    @Test
    fun `continuing as new ends the flow, and nothing after it is reached`() {
        // The static form continues as the workflow type itself, which here has a name of its own.
        assertEquals(listOf("timer PT15M", "continueAsNew Poller"), document("Poller").steps())
        assertEquals(listOf("continueAsNew RelayWorkflow"), document("RelayWorkflow").steps())
        for (workflowType in listOf("Poller", "RelayWorkflow")) {
            assertEquals(emptyList(), document(workflowType).nodesOf(NodeKind.END), workflowType)
        }
    }

    @Test
    fun `a failure thrown at the end is the only way out`() {
        val document = document("RejectingWorkflow")

        // The failure is called by the failure type it was given, a constant.
        assertEquals(listOf("localActivity Archive timeout PT2S", "fail Rejected"), document.steps())
        assertEquals(emptyList(), document.nodesOf(NodeKind.END))
    }

    // ---- stubs without an interface, and durations

    @Test
    fun `untyped stubs are named by the constants given to them or to the calls that made them`() {
        assertEquals(
            listOf(
                "localActivity Validate timeout PT3S",
                "childWorkflow Fulfilment timeout PT5M on fulfilment",
                "signalExternal hurry",
                "signalExternal ping",
                "unknown computed-name (dynamic)",
                "nexusOperation billing-service invoice timeout PT1M",
                "unknown computed-name (ChildWorkflowStub.execute)",
                "timer PT30S",
                "timer PT45S",
                "timer",
                "end",
            ),
            document("UntypedWorkflow").steps(),
        )
    }

    // ---- what needs care around straight-line code

    @Test
    fun `values that may have changed are not taken for constants, and unknown origins are not guessed`() {
        val document = document("EdgeCasesWorkflow")
        val unknown = document.nodesOf(NodeKind.UNKNOWN).map { it.inWords() }

        // A stub handed in from outside is an ordinary activity stub without known options.
        assertEquals(listOf("MailSend", "Archive", "Third"), document.nodesOf(NodeKind.ACTIVITY).map { it.name })
        assertTrue(document.nodesOf(NodeKind.ACTIVITY).all { it.timeout == null && it.taskQueue == null })
        // The activity named by a `var`.
        assertEquals(listOf("unknown computed-name (name)"), unknown)
        assertEquals(listOf(UnknownReasons.COMPUTED_NAME), document.nodesOf(NodeKind.UNKNOWN).map { it.reason })
    }

    @Test
    fun `a call that does not resolve is drawn in a model made from the build, and left out as a library's in a degraded one`() {
        // The call names nothing of Temporal and is made on no stub, and a degraded model lacks every other library.
        val degraded = document("EdgeCasesWorkflow")
        assertEquals(emptyList(), degraded.nodes.filter { it.reason == UnknownReasons.UNRESOLVED })
        assertEquals(listOf("mystery(value)"), degraded.skippedCalls.map { it.text })
        assertEquals(listOf("app/src/main/kotlin/assorted/Assorted.kt"), degraded.skippedCalls.map { it.loc.file })

        // A model made from the build has every library, so there the call is broken code: a node says so.
        val discovery = TemporalDiscovery.discover(backend)
        val workflow = discovery.workflows.single { it.name == "EdgeCasesWorkflow" }
        val built = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.GRADLE).extract(workflow, workflow.implementations.single())
        assertEquals(listOf("unknown unresolved (mystery)"), built.nodes.filter { it.reason == UnknownReasons.UNRESOLVED }.map { it.inWords() })
        assertEquals(emptyList(), built.skippedCalls)
        assertEquals(degraded.nodes.size + 1, built.nodes.size)
    }

    @Test
    fun `a call that never returns ends the workflow`() {
        val document = document("EdgeCasesWorkflow")
        val fails = document.nodesOf(NodeKind.FAIL)

        // The thrown failure has its failure type; `error(...)` only says that it does not return.
        assertEquals(listOf("Missing", null), fails.map { it.name })
        // `input ?: throw` is a branch: one way fails the workflow, the other goes on.
        val guard = document.nodesOf(NodeKind.BRANCH).first()
        assertEquals("input == null", guard.label)
        assertEquals(listOf("fail Missing", "activity MailSend"), document.successors(guard).map { it.inWords() })
        assertEquals(listOf("activity Third"), document.predecessors(fails.last()).map { it.inWords() })
        assertEquals(emptyList(), document.nodesOf(NodeKind.END))
    }

    @Test
    fun `a labelled return ends a lambda, not the workflow`() {
        val document = document("EdgeCasesWorkflow")
        val archive = document.nodesOf(NodeKind.ACTIVITY).single { it.name == "Archive" }

        // `return@forEach` ends one round of the loop: only the other way leads on, to the step in the loop.
        assertEquals(listOf("branch (item.isEmpty())"), document.predecessors(archive).map { it.inWords() })
        val loop = document.nodesOf(NodeKind.LOOP).single()
        assertEquals("for each item", loop.label)
        assertEquals(listOf(loop.id, loop.id), listOf(archive.parent, document.predecessors(archive).single().parent))
        assertEquals(listOf("unknown computed-name (name)"), document.successors(loop).map { it.inWords() })
        assertEquals(emptyList(), document.nodesOf(NodeKind.END))
    }

    @Test
    fun `a return leaves what its label names, and an anonymous function keeps its returns to itself`() {
        val document = document("ReturnsWorkflow")
        val end = document.nodesOf(NodeKind.END).single()

        // `return@run` inside the loop leaves the workflow method, whose name is `run`: an edge
        // from inside the loop straight to the end. The returns of the anonymous function do
        // not leave the workflow, and what follows it is still reached.
        assertEquals(
            listOf("branch (item == \"stop\")", "activity MailSend"),
            document.predecessors(end).map { it.inWords() },
        )
        assertEquals(listOf(92, 101), document.predecessors(end).map { it.loc?.line })
        assertNotNull(document.predecessors(end).first().parent)
        // A function kept in a variable is not modelled: nothing says when it runs.
        assertEquals(listOf("unknown unsupported (lambda)"), document.nodesOf(NodeKind.UNKNOWN).map { it.inWords() })
        assertEquals(listOf("MailSend", "Archive", "MailSend"), document.nodesOf(NodeKind.ACTIVITY).map { it.name })
        assertEquals(
            listOf("item == \"stop\"", "item.isEmpty()"),
            document.nodesOf(NodeKind.BRANCH).map { it.label },
        )
    }

    @Test
    fun `a reference to a stub's method is a step where it is handed over`() {
        val document = document("ReferencesWorkflow")
        val archive = document.nodesOf(NodeKind.ACTIVITY).single { it.name == "Archive" }

        // Handed to Async, which starts it beside the flow, and to a scope function, which runs it in place.
        assertEquals(listOf("MailSend", "Archive"), document.nodesOf(NodeKind.ACTIVITY).map { it.name })
        assertEquals(emptyList(), document.nodesOf(NodeKind.UNKNOWN))
        val region = document.nodesOf(NodeKind.PARALLEL).single()
        assertEquals(region.id, document.nodesOf(NodeKind.ACTIVITY).first().parent)
        assertEquals(listOf("parallel"), document.predecessors(archive).map { it.inWords() })
        assertEquals(listOf("end"), document.successors(archive).map { it.inWords() })
        // A name that is empty names nothing.
        assertEquals(listOf("unknown computed-name (\"\")"), document("EmptyNameWorkflow").steps().dropLast(1))
    }

    @Test
    fun `code that is no step of the workflow leaves nothing`() {
        // Constructors, what the compiler generates for a data class, calls into libraries, and a
        // function of the sources that holds no step.
        assertEquals(listOf("end"), document("PlainCodeWorkflow").steps())
    }

    // ---- which documents there are, and what their files are called

    @Test
    fun `a workflow type implemented twice, or declared twice, gives a document for each class`() {
        val sameName = documents.filter { it.workflowType == "SameName" }

        assertEquals(
            listOf(
                "naming.first.SameName" to "naming.first.SameNameImpl",
                "naming.second.SameName" to "naming.second.EarlyImpl",
                "naming.second.SameName" to "naming.second.LateImpl",
            ),
            sameName.map { it.interfaceName to it.implementation },
        )
        assertEquals(
            listOf(listOf("timer PT1S", "end"), listOf("timer PT2S", "end"), listOf("timer PT3S", "end")),
            sameName.map { it.steps() },
        )
    }

    @Test
    fun `file names tell apart documents of one workflow type and are safe to use`() {
        val names = documents.zip(IrFiles.names(documents)).associate { (document, name) ->
            "${document.workflowType} ${document.implementation.substringAfterLast('.')}" to name
        }

        assertEquals("SameName--naming.first.SameNameImpl.ir.json", names["SameName SameNameImpl"])
        assertEquals("SameName--naming.second.EarlyImpl.ir.json", names["SameName EarlyImpl"])
        assertEquals("SameName--naming.second.LateImpl.ir.json", names["SameName LateImpl"])
        // A workflow type is free text; one that differs from another only in case shares its fate.
        assertEquals("billing_odd_type_.ir.json", names["billing/odd type? OddImpl"])
        assertEquals("Casing--naming.UpperImpl.ir.json", names["Casing UpperImpl"])
        assertEquals("casing--naming.LowerImpl.ir.json", names["casing LowerImpl"])
        assertEquals("AwaitingWorkflow.ir.json", names["AwaitingWorkflow AwaitingWorkflowImpl"])
        assertEquals(names.size, names.values.map { it.lowercase() }.toSet().size)
    }

    @Test
    fun `a workflow method without a body to walk is said to be so`() {
        // The class leaves the method to the object it delegates to.
        val document = document("Delegated")

        assertEquals(listOf("unknown unsupported (workflow method without a Kotlin body)", "end"), document.steps())
        assertEquals("naming/Others.kt", document.nodesOf(NodeKind.UNKNOWN).single().loc?.file?.substringAfter("kotlin/"))
    }

    @Test
    fun `the analyser writes one file per document and says which workflow type has none`(@TempDir out: Path) {
        val log = mutableListOf<String>()

        val result = Analyser(log::add).analyse(AnalysisRequest(model = sources.model().directory, out = out))

        assertEquals(emptyList(), result.failures)
        assertEquals(documents, result.workflows.map { it.document })
        assertEquals(IrFiles.names(documents), result.workflows.map { it.file.name })
        assertEquals(
            (IrFiles.names(documents) + "inventory.json" + "standalone-activities.json").sorted(),
            out.listDirectoryEntries().map { it.name }.sorted(),
        )
        // The interface nobody implements is in the inventory and has no IR.
        assertTrue(result.inventory.workflows.any { it.workflowType == "Orphan" })
        assertTrue(
            log.any { it == "warning: no class in the sources implements naming.Orphan; there is no IR for workflow type Orphan" },
            log.joinToString("\n"),
        )
    }

    private companion object {
        val LINEAR = """
            package linear

            import io.temporal.activity.ActivityInterface
            import io.temporal.activity.ActivityMethod
            import io.temporal.activity.ActivityOptions
            import io.temporal.activity.LocalActivityOptions
            import io.temporal.failure.ApplicationFailure
            import io.temporal.workflow.SignalMethod
            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod
            import java.time.Duration

            @ActivityInterface(namePrefix = "Mail")
            interface MailActivities {
                fun send(to: String)

                @ActivityMethod(name = "Archive")
                fun archive(id: String)
            }

            const val MAIL_QUEUE = "mail"
            val PATIENCE: Duration = Duration.ofHours(2)

            @WorkflowInterface
            interface AwaitingWorkflow {
                @WorkflowMethod
                fun run()

                @SignalMethod
                fun approve()

                @SignalMethod
                fun cancel()
            }

            class AwaitingWorkflowImpl : AwaitingWorkflow {
                private var approved = false
                private var cancelled = false
                private val mail = Workflow.newActivityStub(
                    MailActivities::class.java,
                    ActivityOptions.newBuilder()
                        .setTaskQueue(MAIL_QUEUE)
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .setScheduleToCloseTimeout(PATIENCE)
                        .build(),
                )

                override fun run() {
                    Workflow.await { approved || cancelled }
                    mail.send("ops")
                }

                override fun approve() {
                    approved = true
                }

                override fun cancel() {
                    cancelled = true
                }
            }

            @WorkflowInterface
            interface PollingWorkflow {
                @WorkflowMethod(name = "Poller")
                fun poll(attempt: Int)
            }

            class PollingWorkflowImpl : PollingWorkflow {
                override fun poll(attempt: Int) {
                    Workflow.sleep(Duration.ofMinutes(15))
                    Workflow.continueAsNew(attempt + 1)
                }
            }

            @WorkflowInterface
            interface RelayWorkflow {
                @WorkflowMethod
                fun relay(lap: Int): Int
            }

            class RelayWorkflowImpl : RelayWorkflow {
                override fun relay(lap: Int): Int {
                    val next = Workflow.newContinueAsNewStub(RelayWorkflow::class.java)
                    return next.relay(lap + 1)
                }
            }

            @WorkflowInterface
            interface RejectingWorkflow {
                @WorkflowMethod
                fun run(reason: String)
            }

            class RejectingWorkflowImpl : RejectingWorkflow {
                private val mail = Workflow.newLocalActivityStub(
                    MailActivities::class.java,
                    LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(2)).build(),
                )

                override fun run(reason: String) {
                    mail.archive(reason)
                    throw ApplicationFailure.newFailure(reason, "Rejected")
                }
            }
        """

        val ASSORTED = """
            package assorted

            import io.temporal.activity.ActivityOptions
            import io.temporal.activity.LocalActivityOptions
            import io.temporal.failure.ApplicationFailure
            import io.temporal.workflow.Async
            import io.temporal.workflow.ChildWorkflowOptions
            import io.temporal.workflow.NexusOperationOptions
            import io.temporal.workflow.NexusServiceOptions
            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod
            import java.time.Duration
            import java.time.temporal.ChronoUnit
            import kotlin.time.Duration.Companion.seconds
            import kotlin.time.toJavaDuration
            import linear.MailActivities

            @WorkflowInterface
            interface UntypedWorkflow {
                @WorkflowMethod
                fun run(id: String, dynamic: String)
            }

            class UntypedWorkflowImpl : UntypedWorkflow {
                override fun run(id: String, dynamic: String) {
                    val local = Workflow.newUntypedLocalActivityStub(
                        LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.parse("PT3S")).build(),
                    )
                    local.execute("Validate", Void::class.java, id)
                    val child = Workflow.newUntypedChildWorkflowStub(
                        "Fulfilment",
                        ChildWorkflowOptions.newBuilder()
                            .setTaskQueue("fulfilment")
                            .setWorkflowRunTimeout(Duration.of(5, ChronoUnit.MINUTES))
                            .build(),
                    )
                    child.execute(Void::class.java, id)
                    child.signal("hurry", id)
                    val external = Workflow.newUntypedExternalWorkflowStub(id)
                    external.signal("ping")
                    external.signal(dynamic)
                    val operationOptions = NexusOperationOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofMinutes(1)).build()
                    val billing = Workflow.newUntypedNexusServiceStub(
                        "billing-service",
                        NexusServiceOptions.newBuilder().setEndpoint("billing").setOperationOptions(operationOptions).build(),
                    )
                    billing.execute("invoice", String::class.java, id)
                    val someChild = Workflow.newUntypedChildWorkflowStub(dynamic)
                    someChild.execute(Void::class.java)
                    Workflow.sleep(30.seconds.toJavaDuration())
                    val pause = Duration.ofSeconds(45)
                    Workflow.sleep(pause)
                    Workflow.sleep(dynamic.length.toLong())
                }
            }

            @WorkflowInterface
            interface EdgeCasesWorkflow {
                @WorkflowMethod
                fun run(input: String?, items: List<String>)
            }

            class EdgeCasesWorkflowImpl(private val injected: MailActivities) : EdgeCasesWorkflow {
                override fun run(input: String?, items: List<String>) {
                    val value = input ?: throw ApplicationFailure.newFailure("no input", "Missing")
                    injected.send(value)
                    items.forEach { item ->
                        if (item.isEmpty()) return@forEach
                        injected.archive(item)
                    }
                    var name = "First"
                    name = "Second"
                    val untyped = Workflow.newUntypedActivityStub(ActivityOptions.newBuilder().build())
                    untyped.execute(name, Void::class.java)
                    val fixed = "Third"
                    untyped.execute(fixed, Void::class.java)
                    mystery(value)
                    error("never gets past this")
                }
            }

            @WorkflowInterface
            interface ReturnsWorkflow {
                @WorkflowMethod
                fun run(items: List<String>)
            }

            class ReturnsWorkflowImpl(private val mail: MailActivities) : ReturnsWorkflow {
                override fun run(items: List<String>) {
                    items.forEach { item ->
                        if (item == "stop") return@run
                        mail.send(item)
                    }
                    val check = fun(item: String): Boolean {
                        if (item.isEmpty()) return false
                        mail.archive(item)
                        return true
                    }
                    check("x")
                    mail.send("done")
                }
            }

            @WorkflowInterface
            interface ReferencesWorkflow {
                @WorkflowMethod
                fun run(id: String)
            }

            class ReferencesWorkflowImpl(private val mail: MailActivities) : ReferencesWorkflow {
                override fun run(id: String) {
                    Async.procedure(mail::send, id).get()
                    id.let(mail::archive)
                }
            }

            @WorkflowInterface
            interface EmptyNameWorkflow {
                @WorkflowMethod
                fun run()
            }

            class EmptyNameWorkflowImpl : EmptyNameWorkflow {
                override fun run() {
                    Workflow.newUntypedActivityStub(ActivityOptions.newBuilder().build()).execute("", Void::class.java)
                }
            }

            data class Parcel(val id: String, val weight: Int)

            fun describe(parcel: Parcel): String = parcel.id

            @WorkflowInterface
            interface PlainCodeWorkflow {
                @WorkflowMethod
                fun run(id: String): String
            }

            class PlainCodeWorkflowImpl : PlainCodeWorkflow {
                override fun run(id: String): String {
                    val parcel = Parcel(id, 1)
                    val heavier = parcel.copy(weight = parcel.weight + 1)
                    val (first, second) = heavier
                    val text = listOf(first, second.toString()).joinToString("-") { it.uppercase() }
                    Workflow.getLogger("plain").info(text)
                    return describe(heavier) + text.takeIf { it.isNotEmpty() }
                }
            }
        """

        val SAME_NAME_FIRST = """
            package naming.first

            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod

            @WorkflowInterface
            interface SameName {
                @WorkflowMethod
                fun run()
            }

            class SameNameImpl : SameName {
                override fun run() = Workflow.sleep(1_000)
            }
        """

        val SAME_NAME_SECOND = """
            package naming.second

            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod

            @WorkflowInterface
            interface SameName {
                @WorkflowMethod
                fun run()
            }

            class EarlyImpl : SameName {
                override fun run() = Workflow.sleep(2_000)
            }

            class LateImpl : SameName {
                override fun run() = Workflow.sleep(3_000)
            }
        """

        val OTHER_NAMES = """
            package naming

            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod

            @WorkflowInterface
            interface Orphan {
                @WorkflowMethod
                fun run()
            }

            @WorkflowInterface
            interface Delegated {
                @WorkflowMethod
                fun run()
            }

            class DelegatedImpl(inner: Delegated) : Delegated by inner

            @WorkflowInterface
            interface Odd {
                @WorkflowMethod(name = "billing/odd type?")
                fun run()
            }

            class OddImpl : Odd {
                override fun run() {}
            }

            @WorkflowInterface
            interface Upper {
                @WorkflowMethod(name = "Casing")
                fun run()
            }

            class UpperImpl : Upper {
                override fun run() {}
            }

            @WorkflowInterface
            interface Lower {
                @WorkflowMethod(name = "casing")
                fun run()
            }

            class LowerImpl : Lower {
                override fun run() {}
            }
        """
    }
}
