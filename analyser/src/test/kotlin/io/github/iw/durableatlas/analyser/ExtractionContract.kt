package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.IrFiles
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.analyser.testing.SampleSources
import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrStats
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.UnknownReasons
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What graph extraction must make of the sample project, whichever way its model was extracted.
 * The expectations are those written down in the sample project's `workflows/README.md`.
 *
 * Golden files pin the document of every workflow type byte for byte, and the tests here say in
 * words what the README promises of each. In those tests a node is written as its kind and its
 * line, as [IrStructure] does, with the name of its file in front of the line where a workflow
 * calls into functions of other files.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ExtractionContract(protected val mode: ModelMode) {
    protected val backend: SourceBackend get() = SampleSources.of(mode)
    protected val discovery: Discovery by lazy { TemporalDiscovery.discover(backend) }
    protected val documents: List<IrDocument> by lazy {
        WorkflowGraphs(backend, FIXED_COMMIT, mode).extract(discovery)
    }

    protected fun document(workflowType: String): IrDocument = documents.single { it.workflowType == workflowType }

    /** Compares the document of a workflow whose IR is final with its golden file. */
    protected abstract fun assertGolden(workflowType: String, document: IrDocument)

    /** The document of a workflow type when calls are followed [maxDepth] deep. */
    protected fun document(workflowType: String, maxDepth: Int): IrDocument {
        val workflow = discovery.workflows.single { it.name == workflowType }
        return WorkflowGraphs(backend, FIXED_COMMIT, mode, maxDepth).extract(workflow, workflow.implementations.single())
    }

    @Test
    fun `every workflow type gives the golden IR`() {
        assertEquals(documents.map { it.workflowType }.sorted(), GOLDEN.sorted())
        for (workflowType in GOLDEN) assertGolden(workflowType, document(workflowType))
    }

    @Test
    fun `every workflow type gets one document, which names it and the class that implements it`() {
        assertEquals(discovery.workflows.map { it.name }, documents.map { it.workflowType })
        assertEquals(30, documents.size)
        for (document in documents) {
            assertEquals(FIXED_COMMIT, document.commit)
            assertEquals(mode, document.modelMode)
            assertEquals("${document.interfaceName}Impl", document.implementation)
            assertTrue(document.interfaceName.startsWith("com.example.fixture.workflows."), document.interfaceName)
            // The workflow method comes first, and is called after the workflow type.
            assertEquals(
                EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, document.workflowType, document.start().id),
                document.entryPoints.first(),
            )
        }
        assertEquals("com.example.fixture.workflows.order.ShipmentWorkflow", document("Shipment").interfaceName)
        // Three workflow types have handlers; every other document holds the workflow method alone.
        assertEquals(
            listOf("ApprovalWorkflow", "AwaitWorkflow", "OrderWorkflow"),
            documents.filter { it.entryPoints.size > 1 }.map { it.workflowType },
        )
    }

    @Test
    fun `every document matches the schema, is consistent and keeps to the convention for regions`() {
        val schema = JsonSchemaChecker.forWorkflowIr()
        for (document in documents) {
            assertWellFormed(document, schema)
            assertEquals(IrStats.of(document.nodes), document.stats, document.workflowType)
            // A node is where its step is written: in the workflow's own module, or in the library it calls into.
            for (node in document.nodes.filter { it.loc != null }) {
                val file = node.loc!!.file
                assertTrue(SOURCE_ROOTS.any { file.startsWith(it) }, node.loc.toString())
            }
        }
    }

    @Test
    fun `extracting twice gives the same documents and the same file names`() {
        val again = WorkflowGraphs(backend, FIXED_COMMIT, mode).extract(discovery)

        assertEquals(documents, again)
        assertEquals(IrFiles.names(documents), IrFiles.names(again))
        // No two workflow types of the sample project share a name, so each file is called after its type.
        assertEquals(documents.map { "${it.workflowType}.ir.json" }, IrFiles.names(documents))
    }

    // ---- primitives in straight-line code

    @Test
    fun `calls on typed activity stubs are activities, named by the rules of the SDK`() {
        assertEquals(
            listOf(
                "activity ReserveStock timeout PT30S",
                "activity PaymentCharge timeout PT30S",
                "activity CapturePayment timeout PT30S",
                "activity ReleaseStock timeout PT30S",
                "end",
            ),
            document("TypedActivityWorkflow").steps(),
        )
    }

    @Test
    fun `the call that made a stub tells a local activity from an ordinary one`() {
        // Both stubs have the type of the same interface, and each has its own options.
        assertEquals(
            listOf("localActivity CheckAvailability timeout PT5S", "activity ReserveStock timeout PT30S", "end"),
            document("LocalActivityWorkflow").steps(),
        )
    }

    @Test
    fun `an untyped stub gives an activity when the name is a compile-time constant`() {
        assertEquals(
            listOf("activity ReserveStock timeout PT30S", "activity ReleaseStock timeout PT30S", "end"),
            document("UntypedActivityWorkflow").steps(),
        )
    }

    @Test
    fun `an activity name made at run time gives an unknown node and no activity`() {
        val document = document("ComputedActivityNameWorkflow")

        assertEquals(listOf("unknown computed-name (activityName)", "end"), document.steps())
        val unknown = document.nodesOf(NodeKind.UNKNOWN).single()
        assertEquals(UnknownReasons.COMPUTED_NAME, unknown.reason)
        assertEquals(20, unknown.loc?.line)
        assertEquals(emptyList(), document.nodesOf(NodeKind.ACTIVITY))
        assertEquals(IrStats(nodes = 3, unknown = 1), document.stats)
    }

    @Test
    fun `a call on a child stub is a child workflow, known by its workflow type only`() {
        assertEquals(listOf("childWorkflow GreetingChildWorkflow", "end"), document("ParentWorkflow").steps())
        // The child has no step of its own, and nothing of it is part of the parent.
        assertEquals(listOf("end"), document("GreetingChildWorkflow").steps())
        assertEquals(2, document("GreetingChildWorkflow").nodes.size)
    }

    @Test
    fun `a signal method called on an external stub is named as the target interface names it`() {
        // The label adds the workflow type the signal goes to.
        assertEquals(listOf("signalExternal approve (ApprovalWorkflow.approve)", "end"), document("SignalExternalWorkflow").steps())
    }

    @Test
    fun `sleeping and timers are timers, with their duration`() {
        // Workflow.sleep(Duration), Workflow.sleep(Long) and Workflow.newTimer; get() on the promise joins nothing.
        assertEquals(listOf("timer PT30S", "timer PT1S", "timer PT5M", "end"), document("TimerWorkflow").steps())
    }

    @Test
    fun `side effects are nodes, labelled with the variable they are kept in or named by their id`() {
        assertEquals(listOf("sideEffect (region)", "sideEffect batch-size", "end"), document("SideEffectWorkflow").steps())
    }

    @Test
    fun `Nexus operations are named by service and operation, called or started through a handle`() {
        assertEquals(
            listOf(
                "nexusOperation GreetingService greet timeout PT30S",
                "nexusOperation GreetingService say-goodbye timeout PT30S",
                "end",
            ),
            document("NexusOperationWorkflow").steps(),
        )
    }

    @Test
    fun `scope functions are walked in place, also where the stub is the implicit receiver`() {
        // The stubs are made by a helper with its options left at their default, which holds the timeout.
        assertEquals(
            listOf(
                "activity ReserveStock timeout PT30S",
                "activity PaymentCharge timeout PT30S",
                "activity CapturePayment timeout PT30S",
                "activity NotifyEmail timeout PT30S",
                "activity NotifySms timeout PT30S",
                "activity ShipItem timeout PT30S",
                "end",
            ),
            document("ScopeFunctionWorkflow").steps(),
        )
    }

    // ---- primitives that steer the flow

    @Test
    fun `an await has its condition as its label, and the test of its result is its timeout edge`() {
        val document = document("AwaitWorkflow")

        assertEquals(
            listOf("await reads released", "await timeout PT10M reads released"),
            document.nodesOf(NodeKind.AWAIT).map { it.copy(label = null).inWords() },
        )
        assertEquals(listOf("released", "released"), document.nodesOf(NodeKind.AWAIT).map { it.label })
        // `if (!releasedAgain)` asks only how the second await ended: it is no branch.
        assertEquals(emptyList(), document.nodesOf(NodeKind.BRANCH))
        assertEquals(
            listOf(
                "await@23 -> await@27",
                "await@27 -(timeout)-> timer@29",
                "await@27 -> end(main) [released]",
                "start@21 -> await@23",
                "timer@29 -> end(main)",
            ),
            document.mainFlow().edgeOutline(),
        )
        // The signal handler writes what both awaits wait for.
        assertEquals(listOf("signal-release writes released"), document.handlers())
        assertEquals(listOf("signal-release -> await@23", "signal-release -> await@27"), document.wakes())
    }

    @Test
    fun `a version gate has its change id, and the test of the version is its edges`() {
        val document = document("VersionGateWorkflow")

        assertEquals(listOf("express-shipping"), document.nodesOf(NodeKind.VERSION_GATE).map { it.changeId })
        assertEquals(emptyList(), document.nodesOf(NodeKind.BRANCH))
        assertEquals(
            listOf(
                "activity@25 -> end(main)",
                "activity@27 -> end(main)",
                "start@22 -> versionGate@23",
                // Version 0 is left out: the gate supports the default version, -1, and version 1.
                "versionGate@23 -> activity@25 [default]",
                "versionGate@23 -> activity@27 [v1]",
            ),
            document.edgeOutline(),
        )
        assertEquals(listOf("ShipItem", "ShipExpress"), document.nodesOf(NodeKind.ACTIVITY).map { it.name })
    }

    @Test
    fun `continuing as new is a terminal, in its static form and through a stub`() {
        val document = document("ContinueAsNewWorkflow")
        val terminals = document.nodesOf(NodeKind.CONTINUE_AS_NEW)

        assertEquals(listOf(22, 26), terminals.map { it.loc?.line })
        assertEquals(listOf("ContinueAsNewWorkflow", "ContinueAsNewWorkflow"), terminals.map { it.name })
        assertEquals(listOf("iteration >= limit", "iteration % 2 == 0"), document.nodesOf(NodeKind.BRANCH).map { it.label })
        assertEquals(
            listOf(
                "branch@17 -> branch@20 [no]",
                // The guard clause holds no step, and stays because it leaves the workflow.
                "branch@17 -> end(main) [yes]",
                "branch@20 -> continueAsNew@22 [yes]",
                // The static form does not return, so the stub form is only reached the other way.
                "branch@20 -> continueAsNew@26 [no]",
                "start@15 -> timer@16",
                "timer@16 -> branch@17",
            ),
            document.edgeOutline(),
        )
    }

    @Test
    fun `a thrown application failure is a terminal named by its failure type`() {
        val document = document("ApplicationFailureWorkflow")
        val fail = document.nodesOf(NodeKind.FAIL).single()

        assertEquals("InvalidAmount", fail.name)
        assertEquals(25, fail.loc?.line)
        assertEquals(
            listOf(
                "activity@27 -> end(main)",
                "branch@24 -> activity@27 [no]",
                "branch@24 -> fail@25 [yes]",
                "start@23 -> branch@24",
            ),
            document.edgeOutline(),
        )
        assertEquals(listOf("amountCents <= 0"), document.nodesOf(NodeKind.BRANCH).map { it.label })
    }

    @Test
    fun `code started with Async is a parallel region with a lane for each start, up to where it is joined`() {
        val document = document("ParallelWorkflow")

        assertEquals(
            listOf(
                "activity@32: name ReserveStock, timeout PT30S, parent parallel@32",
                "activity@33: name QuoteShipping, timeout PT30S, parent parallel@32",
                "activity@37: name NotifyEmail, timeout PT30S, parent parallel@37",
                "activity@38: name NotifySms, timeout PT30S, parent parallel@37",
                "activity@42: name NotifyAudit, timeout PT30S, parent parallel@42",
                "end(main)",
                "parallel@32",
                // `Promise.anyOf`: the flow waits for the first of the two notices, and no longer.
                "parallel@37: join first",
                "parallel@42",
                "start@30",
            ),
            document.nodeOutline(),
        )
        // The lanes of a region are not connected to each other; the final get() joins nothing.
        assertEquals(
            listOf("parallel@32 -> parallel@37", "parallel@37 -> parallel@42", "parallel@42 -> end(main)", "start@30 -> parallel@32"),
            document.edgeOutline(),
        )
    }

    @Test
    fun `a saga's compensations are no steps where they are registered, and a region where the saga compensates`() {
        val document = document("SagaWorkflow")

        assertEquals(
            listOf(
                "activity@31: name ReserveStock, timeout PT30S",
                // What the compensation lambdas hold, at the lines of the lambdas.
                "activity@32: name ReleaseStock, timeout PT30S, parent compensation@39",
                "activity@34: name PaymentCharge, timeout PT30S",
                "activity@35: name PaymentRefund, timeout PT30S, parent compensation@39",
                "activity@37: name ShipItem, timeout PT30S",
                "compensation@39",
                "end(main)",
                "fail@40: name ActivityFailure",
                "start@28",
            ),
            document.nodeOutline(),
        )
        assertEquals(
            listOf(
                "activity@31 -(exception)-> compensation@39 [ActivityFailure]",
                "activity@31 -> activity@34",
                "activity@34 -(exception)-> compensation@39 [ActivityFailure]",
                "activity@34 -> activity@37",
                // Last registered, first run.
                "activity@35 -> activity@32",
                "activity@37 -(exception)-> compensation@39 [ActivityFailure]",
                "activity@37 -> end(main)",
                "compensation@39 -> fail@40",
                "start@28 -> activity@31",
            ),
            document.edgeOutline(),
        )
    }

    @Test
    fun `references to stub methods handed to Async and to a saga are what lambdas calling them would be`() {
        val document = document("MethodReferenceWorkflow")

        assertEquals(
            listOf(
                "activity@39: name ReserveStock, timeout PT30S, parent parallel@39",
                "activity@40: name NotifyEmail, timeout PT30S, parent parallel@39",
                "activity@44: name ReleaseStock, timeout PT30S, parent compensation@48",
                "activity@46: name PaymentCharge, timeout PT30S",
                "compensation@48",
                "end(main)",
                "fail@49: name ActivityFailure",
                "parallel@39",
                "start@35",
            ),
            document.nodeOutline(),
        )
        assertEquals(
            listOf(
                // A failure inside a lane goes straight from there to the clause that catches it.
                "activity@39 -(exception)-> compensation@48 [ActivityFailure]",
                "activity@40 -(exception)-> compensation@48 [ActivityFailure]",
                "activity@46 -(exception)-> compensation@48 [ActivityFailure]",
                "activity@46 -> end(main)",
                "compensation@48 -> fail@49",
                "parallel@39 -> activity@46",
                "start@35 -> parallel@39",
            ),
            document.edgeOutline(),
        )
    }

    // ---- control flow

    @Test
    fun `if and when are branches whose edges say which way, and a branch that holds nothing is dropped`() {
        val document = document("BranchWorkflow")

        assertEquals(listOf("urgent", "channel"), document.nodesOf(NodeKind.BRANCH).map { it.label })
        assertEquals(
            listOf(
                "activity@24 -> branch@30",
                "activity@31 -> end(main)",
                "activity@32 -> end(main)",
                "branch@23 -> activity@24 [yes]",
                "branch@23 -> timer@26 [no]",
                "branch@30 -> activity@31 [\"email\"]",
                "branch@30 -> activity@32 [\"sms\"]",
                // The arm that does nothing leads on; the `if` on line 37 holds no step and is gone.
                "branch@30 -> end(main) [else]",
                "start@21 -> branch@23",
                "timer@26 -> branch@30",
            ),
            document.edgeOutline(),
        )
        // `if (available) 1 else 0` as an argument leaves nothing either.
        assertEquals(emptyList(), document("LocalActivityWorkflow").nodesOf(NodeKind.BRANCH))
    }

    @Test
    fun `loops are regions around their bodies, as statements and as functions that take a lambda`() {
        val document = document("LoopWorkflow")

        assertEquals(
            listOf(
                "activity@24: name ReserveStock, timeout PT30S, parent loop@23",
                "activity@37: name CheckAvailability, timeout PT30S, parent loop@36",
                "activity@42: name ShipItem, timeout PT30S, parent loop@41",
                "activity@46: name QuoteShipping, timeout PT30S, parent loop@46",
                "end(main)",
                "loop@23: label for each sku",
                "loop@29: label while pauses < 2",
                "loop@36: label do while !available",
                "loop@41: label for each sku",
                "loop@46: label for each sku",
                "start@21",
                "timer@30: duration PT10S, parent loop@29",
            ),
            document.nodeOutline(),
        )
        // A region is one step of the flow around it; the `for` on line 50 holds no step and is gone.
        assertEquals(
            listOf(
                "loop@23 -> loop@29",
                "loop@29 -> loop@36",
                "loop@36 -> loop@41",
                "loop@41 -> loop@46",
                "loop@46 -> end(main)",
                "start@21 -> loop@23",
            ),
            document.edgeOutline(),
        )
        assertEquals(listOf("loop (for each sku)", "end"), document("Shipment").steps())
    }

    @Test
    fun `a failure goes to the clause that catches it, and finally is on every way out`() {
        val document = document("TryCatchWorkflow")

        assertEquals(
            listOf(
                "activity@24: name PaymentCharge, timeout PT30S",
                "activity@26: name NotifyEmail, timeout PT30S",
                "activity@31#1: name NotifyAudit, timeout PT30S",
                "activity@31#2: name NotifyAudit, timeout PT30S",
                "end(main)",
                "fail@29: name CanceledFailure",
                "start@22",
            ),
            document.nodeOutline(),
        )
        assertEquals(
            listOf(
                "activity@24 -(exception)-> activity@26 [ActivityFailure]",
                // The clause that rethrows holds no step: the failure passes through `finally` and ends the workflow.
                "activity@24 -(exception)-> activity@31#1 [CanceledFailure]",
                // The two paths that return share one copy of `finally`.
                "activity@24 -> activity@31#2",
                "activity@26 -> activity@31#2",
                "activity@31#1 -> fail@29",
                "activity@31#2 -> end(main)",
                "start@22 -> activity@24",
            ),
            document.edgeOutline(),
        )
    }

    @Test
    fun `an early return leads to the end, and a guard clause stays although it holds no step`() {
        val document = document("EarlyReturnWorkflow")

        assertEquals(listOf("sku.isBlank()", "!available"), document.nodesOf(NodeKind.BRANCH).map { it.label })
        assertEquals(
            listOf(
                "activity@26 -> branch@27",
                "activity@28 -> end(main)",
                "activity@32 -> end(main)",
                "branch@22 -> activity@26 [no]",
                "branch@22 -> end(main) [yes]",
                "branch@27 -> activity@28 [yes]",
                "branch@27 -> activity@32 [no]",
                "start@20 -> branch@22",
            ),
            document.edgeOutline(),
        )
    }

    @Test
    fun `code started with Async inside a loop is a parallel region inside the loop`() {
        val document = document("DynamicFanOutWorkflow")

        assertEquals(
            listOf(
                "activity@21: name QuoteShipping, timeout PT30S, parent parallel@21",
                "end(main)",
                "loop@21: label for each sku",
                "parallel@21: parent loop@21",
                "start@19",
            ),
            document.nodeOutline(),
        )
        // The loop that reads the joined promises holds no step.
        assertEquals(listOf("loop@21 -> end(main)", "start@19 -> loop@21"), document.edgeOutline())
    }

    // ---- calls into functions of the sources

    @Test
    fun `calls into functions of the sources are followed, and what they hold stands where they are called`() {
        val document = document("HelperInliningWorkflow")

        assertEquals(
            listOf(
                // A top-level function of another module, which holds a loop.
                "loop (for each sku)",
                // A function of a helper class that has a stub of its own, made with the default options.
                "activity PaymentCharge timeout PT30S",
                "activity CapturePayment timeout PT30S",
                // A function that wraps a call of the Workflow API.
                "timer PT5S",
                // Three functions deep.
                "activity QuoteShipping timeout PT30S",
                // An extension function of a stub's interface: the stub is the object it is called on.
                "activity NotifyEmail timeout PT30S",
                // An interface with one implementation, whose stub was given to its constructor.
                "activity NotifyAudit timeout PT30S",
                // A private function of the workflow class.
                "activity NotifySms timeout PT30S",
                "end",
            ),
            document.steps(),
        )
        assertEquals(emptyList(), document.nodesOf(NodeKind.UNKNOWN))
        // A node is at the line of its step, in whichever file that is.
        assertEquals(
            listOf(
                "AuditTrail.kt:12",
                "InventoryHelpers.kt:9",
                "NotificationExtensions.kt:7",
                "PaymentProcessor.kt:10",
                "PaymentProcessor.kt:11",
                "ShallowChain.kt:10",
                "HelperInliningWorkflow.kt:50",
            ),
            document.nodesOf(NodeKind.ACTIVITY).map { "${it.loc!!.file.substringAfterLast('/')}:${it.loc!!.line}" },
        )
        val loop = document.nodesOf(NodeKind.LOOP).single()
        assertEquals(listOf("activity ReserveStock timeout PT30S"), document.nodes.filter { it.parent == loop.id }.map { it.inWords() })
    }

    @Test
    fun `only the hard cases have unknown nodes, one each, with a reason and a place`() {
        assertEquals(
            listOf(
                "ComputedActivityNameWorkflow: computed-name at ComputedActivityNameWorkflow.kt:20",
                "DeepHelperChainWorkflow: depth-limit at DeepChain.kt:24",
                "DynamicDispatchWorkflow: dynamic-dispatch at DynamicDispatchWorkflow.kt:23",
                "RecursionWorkflow: recursion at StockPolling.kt:16",
            ),
            documents.flatMap { document ->
                document.nodesOf(NodeKind.UNKNOWN).map { unknown ->
                    "${document.workflowType}: ${unknown.reason} at ${unknown.loc!!.file.substringAfterLast('/')}:${unknown.loc!!.line}"
                }
            },
        )
        assertEquals(4, documents.sumOf { it.stats.unknown })
    }

    @Test
    fun `a call through an interface with two implementations is an unknown node that lists them`() {
        val document = document("DynamicDispatchWorkflow")

        // The `if` that picks the implementation holds no step and is dropped.
        assertEquals(listOf("unknown dynamic-dispatch", "end"), document.steps())
        assertEquals(
            listOf("com.example.fixture.helpers.EmailNotifier", "com.example.fixture.helpers.SmsNotifier"),
            document.nodesOf(NodeKind.UNKNOWN).single().candidates,
        )
        // Neither implementation is followed: no activity shows.
        assertEquals(emptyList(), document.nodesOf(NodeKind.ACTIVITY))
    }

    @Test
    fun `a function that calls itself is followed once, and its call of itself is an unknown node`() {
        val document = document("RecursionWorkflow")

        assertEquals(
            listOf(
                "activity@StockPolling.kt:9: name CheckAvailability, timeout PT30S",
                "branch@StockPolling.kt:12: label attemptsLeft <= 0",
                "branch@StockPolling.kt:9: label inventory.checkAvailability(sku)",
                "end(main)",
                "start@RecursionWorkflow.kt:18",
                "timer@StockPolling.kt:15: duration PT30S",
                "unknown@StockPolling.kt:16: label waitForStock, reason recursion",
            ),
            document.nodeOutline(),
        )
        assertEquals(
            listOf(
                "activity@StockPolling.kt:9 -> branch@StockPolling.kt:9",
                // A `return` in the function ends where the function was called, which here is the end of the workflow.
                "branch@StockPolling.kt:12 -> end(main) [yes]",
                "branch@StockPolling.kt:12 -> timer@StockPolling.kt:15 [no]",
                "branch@StockPolling.kt:9 -> branch@StockPolling.kt:12 [no]",
                "branch@StockPolling.kt:9 -> end(main) [yes]",
                "start@RecursionWorkflow.kt:18 -> activity@StockPolling.kt:9",
                "timer@StockPolling.kt:15 -> unknown@StockPolling.kt:16",
                "unknown@StockPolling.kt:16 -> end(main)",
            ),
            document.edgeOutline(),
        )
    }

    @Test
    fun `calls nested deeper than the limit are cut off where the limit is reached`() {
        // Ten functions deep lies an activity. Eight calls are followed: the ninth is the unknown node.
        val document = document("DeepHelperChainWorkflow")
        assertEquals(listOf("unknown depth-limit (deepChainLevel9)", "end"), document.steps())
        assertEquals(emptyList(), document.nodesOf(NodeKind.ACTIVITY))
        assertEquals(document, document("DeepHelperChainWorkflow", maxDepth = WorkflowGraphs.DEFAULT_MAX_DEPTH))

        // Followed nine deep, the tenth call is cut off; followed ten deep, the activity is reached.
        assertEquals(
            listOf("unknown depth-limit (deepChainLevel10)", "end"),
            document("DeepHelperChainWorkflow", maxDepth = 9).steps(),
        )
        for (depth in listOf(10, 11, 100)) {
            assertEquals(listOf("activity ReserveStock timeout PT30S", "end"), document("DeepHelperChainWorkflow", depth).steps(), "depth $depth")
        }
        // Not followed at all, the first call is where the flow is cut off.
        val shallow = document("DeepHelperChainWorkflow", maxDepth = 0)
        assertEquals(listOf("unknown depth-limit (deepChainLevel1)", "end"), shallow.steps())
        assertEquals("DeepHelperChainWorkflow.kt", shallow.nodesOf(NodeKind.UNKNOWN).single().loc!!.file.substringAfterLast('/'))
    }

    @Test
    fun `the depth only matters where calls are nested deeper than it`() {
        // The deepest of the other workflows follows three calls.
        val shallower = WorkflowGraphs(backend, FIXED_COMMIT, mode, maxDepth = 3).extract(discovery)

        assertEquals(
            documents.filter { it.workflowType != "DeepHelperChainWorkflow" },
            shallower.filter { it.workflowType != "DeepHelperChainWorkflow" },
        )
        assertEquals(
            listOf("unknown depth-limit (quoteChainLevel3)"),
            document("HelperInliningWorkflow", maxDepth = 2).nodesOf(NodeKind.UNKNOWN).map { it.inWords() },
        )
        // A function that holds no step is no unknown node, however deep the calls are followed.
        assertEquals(listOf("unknown computed-name (activityName)", "end"), document("ComputedActivityNameWorkflow", maxDepth = 0).steps())
    }

    @Test
    fun `sparing the functions that hold nothing changes no graph`() {
        // Every node of every workflow method of the sample project was compared.
        assertEquals(documents.sumOf { it.mainFlow().nodes.size }, assertSparingChangesNothing(backend, discovery))
    }

    // ---- handlers

    @Test
    fun `every handler is an entry point with a graph of its own, and wakes the awaits on what it writes`() {
        val document = document("ApprovalWorkflow")

        assertEquals(
            listOf(
                "main",
                "signal-approve",
                "signal-reject",
                "query-approvalCount",
                "query-status",
                "update-addNote",
                "update-set-required-approvals",
                "validator-set-required-approvals",
            ),
            document.entryPoints.map { it.id },
        )
        // A handler is named as Temporal knows it; the validator, after the update it guards.
        assertEquals(
            listOf("approve", "reject", "approvalCount", "status", "addNote", "set-required-approvals", "set-required-approvals"),
            document.entryPoints.drop(1).map { it.name },
        )
        assertEquals(
            listOf(
                // `approvals += 1` reads what it writes.
                "signal-approve reads approvals writes approvals",
                "signal-reject writes rejectionReason",
                "query-approvalCount reads approvals",
                "query-status reads approvals, rejectionReason, requiredApprovals",
                // The stub the handler calls is no state of the workflow.
                "update-addNote reads lastNote writes lastNote",
                "update-set-required-approvals reads requiredApprovals writes requiredApprovals",
                "validator-set-required-approvals",
            ),
            document.handlers(),
        )
        // Only the handler that blocks holds a step; the others are a start and an end.
        val handlers = document.outline().filter { it.startsWith("edge start@") && !it.startsWith("edge start@17 ") }
        assertEquals(
            listOf(
                "edge start@34 -> end(signal-approve)",
                "edge start@38 -> end(signal-reject)",
                "edge start@42 -> end(query-status)",
                "edge start@48 -> end(query-approvalCount)",
                "edge start@50 -> end(update-set-required-approvals)",
                "edge start@56 -> end(validator-set-required-approvals)",
                "edge start@60 -> activity@62",
            ),
            handlers,
        )
        assertTrue("edge activity@62 -> end(update-addNote)" in document.outline())
        // `addNote` writes nothing the await reads.
        assertEquals(
            listOf("signal-approve -> await@20", "signal-reject -> await@20", "update-set-required-approvals -> await@20"),
            document.wakes(),
        )
    }

    @Test
    fun `the main flow of the workflow with handlers folds both tests of its await into the await's edges`() {
        val document = document("ApprovalWorkflow").mainFlow()

        assertEquals(
            listOf(
                "activity@18: name NotifyEmail, timeout PT30S",
                "activity@24: name NotifyEmail, timeout PT30S",
                "activity@30: name NotifyAudit, timeout PT30S",
                "await@20: timeout PT24H, reads approvals, rejectionReason, requiredApprovals, " +
                    "label approvals >= requiredApprovals or rejectionReason != null",
                "end(main)",
                "start@17",
            ),
            document.nodeOutline(),
        )
        assertEquals(
            listOf(
                "activity@18 -> await@20",
                "activity@24 -> end(main)",
                "activity@30 -> end(main)",
                "await@20 -(timeout)-> activity@24",
                "await@20 -> activity@30 [approvals >= requiredApprovals]",
                "await@20 -> end(main) [rejectionReason != null]",
                "start@17 -> activity@18",
            ),
            document.edgeOutline(),
        )
    }

    // ---- the composite

    @Test
    fun `the composite workflow has every construct in its place`() {
        val order = document("OrderWorkflow")
        // Most of it is written in one file; the lines of that file stand without its name.
        fun List<String>.inOwnFile() = map { it.replace("OrderWorkflowImpl.kt:", "") }
        val main = order.mainFlow()

        assertEquals(
            listOf(
                "activity@43: name ReserveStock, timeout PT30S, parent loop@39",
                // The same compensation, once in each region that may run it.
                "activity@44#1: name ReleaseStock, timeout PT30S, parent compensation@54",
                "activity@44#2: name ReleaseStock, timeout PT30S, parent compensation@94",
                "activity@49: name NotifyEmail, timeout PT30S",
                "activity@55: name NotifyEmail, timeout PT30S",
                "activity@63: name PaymentRefund, timeout PT30S, parent compensation@94",
                "activity@68: name NotifyAudit, timeout PT30S",
                "activity@73: name NotifyEmail, timeout PT30S, parent parallel@72",
                "activity@76: name NotifySms, timeout PT30S, parent parallel@72",
                // Charging and capturing are steps of the helper class the workflow calls.
                "activity@PaymentProcessor.kt:10: name PaymentCharge, timeout PT30S",
                "activity@PaymentProcessor.kt:11: name CapturePayment, timeout PT30S",
                "await@50: timeout PT30M, reads cancelReason, paymentReference, label paymentReference != null or cancelReason != null",
                "branch@40: label !localInventory.checkAvailability(line.sku), parent loop@39",
                "childWorkflow@89: name Shipment, taskQueue shipping",
                "compensation@54",
                "compensation@94",
                "end(main)",
                "fail@96: name TemporalFailure",
                "localActivity@40: name CheckAvailability, timeout PT5S, parent loop@39",
                "loop@39: label for each line",
                "parallel@72",
                "start@35",
                "versionGate@66: changeId order-audit",
            ),
            main.nodeOutline().inOwnFile(),
        )
        assertEquals(
            listOf(
                "activity@49 -> await@50",
                "activity@55 -> end(main)",
                "activity@63 -> activity@44#2",
                "activity@68 -> parallel@72",
                "activity@PaymentProcessor.kt:10 -> activity@PaymentProcessor.kt:11",
                "activity@PaymentProcessor.kt:11 -> versionGate@66",
                "await@50 -(timeout)-> compensation@54",
                "await@50 -> activity@PaymentProcessor.kt:10 [paymentReference != null]",
                "await@50 -> compensation@54 [cancelReason != null]",
                "branch@40 -> activity@43 [no]",
                "childWorkflow@89 -> end(main)",
                "compensation@54 -> activity@55",
                "compensation@94 -> fail@96",
                "localActivity@40 -> branch@40",
                "loop@39 -> activity@49",
                "parallel@72 -> childWorkflow@89",
                "start@35 -> loop@39",
                "versionGate@66 -> activity@68 [v1]",
                "versionGate@66 -> parallel@72 [default]",
            ),
            main.copy(edges = main.edges.filter { it.kind != EdgeKind.EXCEPTION }).edgeOutline().inOwnFile(),
        )
        // Everything in the `try` that can fail with a Temporal failure leads to the clause that catches it;
        // the failure thrown in the loop is caught by it too and is no terminal of its own.
        val caught = order.edges.filter { it.kind == EdgeKind.EXCEPTION }
        assertEquals(setOf("TemporalFailure"), caught.map { it.label }.toSet())
        assertEquals(setOf(order.nodesOf(NodeKind.COMPENSATION).last().id), caught.map { it.to }.toSet())
        assertEquals(
            listOf(
                "activity@43", "activity@49", "activity@55", "activity@68", "activity@73", "activity@76",
                "activity@PaymentProcessor.kt:10", "activity@PaymentProcessor.kt:11", "await@50", "branch@40",
                "childWorkflow@89", "localActivity@40",
            ),
            caught.map { IrStructure.of(order).handles.getValue(it.from) }.sorted().inOwnFile(),
        )
        assertEquals(listOf("TemporalFailure"), order.nodesOf(NodeKind.FAIL).map { it.name })
        assertEquals(emptyList(), order.nodesOf(NodeKind.UNKNOWN))
    }

    @Test
    fun `the handlers of the composite workflow wake its await, and those that change nothing it reads do not`() {
        val order = document("OrderWorkflow")

        assertEquals(
            listOf(
                "signal-cancelOrder writes cancelReason",
                // `this.paymentReference = paymentReference`: the parameter is no property.
                "signal-payment-received writes paymentReference",
                "query-order-status reads status",
                "update-change-address reads shippingAddress writes shippingAddress",
                // What `check` is given reads the status; what `require` is given reads the parameter.
                "validator-change-address reads status",
            ),
            order.handlers(),
        )
        assertEquals(
            listOf("signal-cancelOrder -> await@OrderWorkflowImpl.kt:50", "signal-payment-received -> await@OrderWorkflowImpl.kt:50"),
            order.wakes(),
        )
        // No handler holds a step: each is a start and an end.
        val handlerNodes = order.nodes - order.mainFlow().nodes.toSet()
        assertEquals(setOf(NodeKind.START, NodeKind.END), handlerNodes.map { it.kind }.toSet())
        assertEquals(10, handlerNodes.size)
    }

    protected companion object {
        /** Where the nodes of the sample project's workflows come from. */
        val SOURCE_ROOTS = listOf(
            "workflows/src/main/kotlin/com/example/fixture/workflows/",
            "helpers/src/main/kotlin/com/example/fixture/helpers/",
        )

        /** Every workflow type of the sample project: each document is pinned by a golden file. */
        val GOLDEN = listOf(
            // One primitive each.
            "ApplicationFailureWorkflow",
            "AwaitWorkflow",
            "ContinueAsNewWorkflow",
            "GreetingChildWorkflow",
            "LocalActivityWorkflow",
            "MethodReferenceWorkflow",
            "NexusOperationWorkflow",
            "ParallelWorkflow",
            "ParentWorkflow",
            "SagaWorkflow",
            "SideEffectWorkflow",
            "SignalExternalWorkflow",
            "TimerWorkflow",
            "TypedActivityWorkflow",
            "UntypedActivityWorkflow",
            "VersionGateWorkflow",
            // One kind of control flow each.
            "BranchWorkflow",
            "DynamicFanOutWorkflow",
            "EarlyReturnWorkflow",
            "LoopWorkflow",
            "ScopeFunctionWorkflow",
            "TryCatchWorkflow",
            // Handlers, calls into the helper library, and the composite with its child.
            "ApprovalWorkflow",
            "HelperInliningWorkflow",
            "OrderWorkflow",
            "Shipment",
            // What cannot be resolved.
            "ComputedActivityNameWorkflow",
            "DeepHelperChainWorkflow",
            "DynamicDispatchWorkflow",
            "RecursionWorkflow",
        )
    }
}
