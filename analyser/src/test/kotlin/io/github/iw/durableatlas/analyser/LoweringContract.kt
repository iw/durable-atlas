package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.Block
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CalleeKind
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.CodePrinter
import io.github.iw.durableatlas.analyser.code.Constant
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.Loop
import io.github.iw.durableatlas.analyser.code.LoopKind
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.PropertyWrite
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.find
import io.github.iw.durableatlas.analyser.code.walk
import io.github.iw.durableatlas.analyser.testing.SampleSources
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.SourceLocation
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the code of the sample project's functions must lower to, whichever way its model was
 * extracted: the same tree in both modes, because everything the sample project refers to is
 * either in its sources or on the classpath of a degraded model.
 */
abstract class LoweringContract(protected val mode: ModelMode) {
    protected val backend: SourceBackend get() = SampleSources.of(mode)

    private fun function(className: String, name: String): FunctionDecl =
        backend.functions("$WORKFLOWS.$className").single { it.name == name }

    private fun body(className: String, name: String = "run"): Block =
        assertNotNull(backend.body(function(className, name).id), "$className.$name has a body")

    private fun Code.calls(): List<Call> = walk().filterIsInstance<Call>().toList()

    private fun Code.call(name: String): Call = calls().single { it.name == name }

    @Test
    fun `calls are resolved to their declarations and located by file and line`() {
        val file = "workflows/src/main/kotlin/com/example/fixture/workflows/primitives/TypedActivityWorkflow.kt"
        val calls = body("primitives.TypedActivityWorkflowImpl").calls()

        assertEquals(
            listOf(
                "$ACTIVITIES.InventoryActivities.reserveStock(kotlin.String,kotlin.Int)" to 25,
                "$ACTIVITIES.PaymentActivities.charge(kotlin.String,kotlin.Long)" to 26,
                "$ACTIVITIES.PaymentActivities.capture(kotlin.String)" to 27,
                "$ACTIVITIES.InventoryActivities.release(kotlin.String)" to 28,
            ),
            calls.map { assertNotNull(it.callee).function.toString() to it.location.line },
        )
        for (call in calls) {
            val callee = assertNotNull(call.callee)
            assertEquals(file, call.location.file)
            assertEquals(CalleeKind.FUNCTION, callee.kind)
            assertTrue(callee.inSource, callee.function.toString())
            // The stub is recognised by the type of the receiver, which carries the SDK's annotation.
            val receiverType = assertNotNull(call.receiverType)
            assertEquals(callee.function.owner, receiverType.name)
            assertNotNull(receiverType.annotations.find("io.temporal.activity.ActivityInterface"))
            val receiver = assertIs<PropertyRead>(call.receiver)
            assertTrue(receiver.onThis)
            assertEquals("$WORKFLOWS.primitives.TypedActivityWorkflowImpl", receiver.property?.owner)
        }
        assertEquals(
            "Payment",
            calls[1].receiverType?.annotations?.find("io.temporal.activity.ActivityInterface")?.text("namePrefix"),
        )
        assertEquals(listOf("sku", "quantity"), calls[0].arguments.map { it.parameter })
        assertEquals(listOf("sku", "1"), calls[0].arguments.map { it.text })
        assertEquals(listOf(null, Constant.Integer(1)), calls[0].arguments.map { it.constant })
        assertEquals("kotlin.String", calls[0].type?.name)
    }

    @Test
    fun `calls into the SDK and the JDK resolve too, and a static call has no receiver`() {
        val calls = body("primitives.TimerWorkflowImpl").calls()

        assertEquals(
            listOf(
                "io.temporal.workflow.Workflow.sleep(java.time.Duration)",
                "java.time.Duration.ofSeconds(kotlin.Long)",
                "io.temporal.workflow.Workflow.sleep(kotlin.Long)",
                "io.temporal.workflow.Promise.get()",
                "io.temporal.workflow.Workflow.newTimer(java.time.Duration)",
                "java.time.Duration.ofMinutes(kotlin.Long)",
            ),
            calls.map { assertNotNull(it.callee).function.toString() },
        )
        assertTrue(calls.none { assertNotNull(it.callee).inSource })
        val sleep = calls.first()
        assertNull(sleep.receiver)
        assertNull(sleep.receiverType)
        assertEquals("io.temporal.workflow.Workflow", sleep.callee?.function?.owner)
        assertEquals(Constant.Integer(30), calls[1].arguments.single().constant)
        assertEquals(Constant.Integer(1_000), calls[2].arguments.single().constant)
        // The promise is the receiver of get(), and the timer call is that receiver.
        assertEquals("io.temporal.workflow.Promise", calls[3].receiverType?.name)
        assertEquals(calls[4], calls[3].receiver)
    }

    @Test
    fun `a call into a helper can be followed to the helper's body`() {
        val run = body("inlining.HelperInliningWorkflowImpl")

        val reserveAll = assertNotNull(run.call("reserveAll").callee)
        assertTrue(reserveAll.inSource)
        assertEquals("com.example.fixture.helpers.reserveAll", reserveAll.function.name)
        assertNull(reserveAll.function.owner)
        val helper = assertNotNull(backend.function(reserveAll.function))
        assertEquals(
            SourceLocation("helpers/src/main/kotlin/com/example/fixture/helpers/InventoryHelpers.kt", 6),
            helper.location,
        )
        val loop = assertNotNull(backend.body(reserveAll.function)).walk().filterIsInstance<Loop>().single()
        assertEquals(LoopKind.FOR, loop.kind)
        assertEquals("sku in skus", loop.headerText)
        assertEquals("$ACTIVITIES.InventoryActivities.reserveStock", loop.call("reserveStock").callee?.function?.name)

        // A member of a helper class, an extension function and a private member of the workflow.
        val chargeAndCapture = assertNotNull(run.call("chargeAndCapture").callee)
        assertEquals("com.example.fixture.helpers.PaymentProcessor", chargeAndCapture.function.owner)
        assertEquals(2, assertNotNull(backend.body(chargeAndCapture.function)).calls().size)
        val notifyOps = assertNotNull(run.call("notifyOps").callee)
        assertEquals("$ACTIVITIES.NotificationActivities", notifyOps.function.receiver)
        val email = assertNotNull(backend.body(notifyOps.function)).call("email")
        assertNull(email.receiver, "the receiver is implicit")
        assertEquals("$ACTIVITIES.NotificationActivities", email.receiverType?.name)
        val alertWarehouse = run.call("alertWarehouse")
        assertEquals("$WORKFLOWS.inlining.HelperInliningWorkflowImpl", alertWarehouse.receiverType?.name)
        assertNotNull(backend.body(assertNotNull(alertWarehouse.callee).function))

        // A call through an interface names the interface's function, which has no body.
        val record = assertNotNull(run.call("record").callee)
        assertEquals("com.example.fixture.helpers.AuditTrail.record", record.function.name)
        assertTrue(record.inSource)
        assertNull(backend.body(record.function))
        assertTrue(assertNotNull(backend.function(record.function)).isAbstract)

        // Private top-level functions are told apart by their file.
        val level2 = assertNotNull(backend.body(assertNotNull(run.call("quoteViaChain").callee).function))
            .call("quoteChainLevel2").callee
        assertEquals("helpers/src/main/kotlin/com/example/fixture/helpers/ShallowChain.kt", level2?.function?.file)
    }

    @Test
    fun `stubs can be traced to the calls that create them`() {
        val owner = "$WORKFLOWS.primitives.LocalActivityWorkflowImpl"
        val properties = backend.properties(owner).associateBy { it.name }
        assertEquals(listOf("localInventory", "inventory"), properties.keys.toList())

        val local = assertIs<Call>(backend.initializer(assertNotNull(properties["localInventory"]).id))
        assertEquals("io.temporal.workflow.Workflow.newLocalActivityStub", local.callee?.function?.name)
        val ordinary = assertIs<Call>(backend.initializer(assertNotNull(properties["inventory"]).id))
        assertEquals("io.temporal.workflow.Workflow.newActivityStub", ordinary.callee?.function?.name)
        // Both stubs have the same type; only the creating call tells them apart.
        assertEquals(properties["localInventory"]?.type, properties["inventory"]?.type)
        assertEquals("$ACTIVITIES.InventoryActivities", local.typeArguments.single().name)

        // A stub made by a helper with a reified type parameter: the type argument names the interface.
        val helperMade = backend.properties("$WORKFLOWS.controlflow.BranchWorkflowImpl").single()
        val creation = assertIs<Call>(backend.initializer(helperMade.id))
        assertEquals("com.example.fixture.helpers.activityStub", creation.callee?.function?.name)
        val stubType = creation.typeArguments.single()
        assertEquals("$ACTIVITIES.NotificationActivities", stubType.name)
        assertEquals("Notify", stubType.annotations.find("io.temporal.activity.ActivityInterface")?.text("namePrefix"))

        // A stub held in a local variable is found through the variable's initialiser.
        val child = body("primitives.ParentWorkflowImpl").call("newChildWorkflowStub")
        assertEquals("$WORKFLOWS.primitives.GreetingChildWorkflow", child.type?.name)
        assertNotNull(child.type?.annotations?.find("io.temporal.workflow.WorkflowInterface"))
    }

    @Test
    fun `the default value of a parameter is lowered like any other expression`() {
        val creation = assertIs<Call>(backend.initializer(backend.properties("$WORKFLOWS.controlflow.BranchWorkflowImpl").single().id))
        val helper = assertNotNull(creation.callee).function

        // The stub is made with the helper's options left out, so the default is what it gets.
        assertEquals(emptyList(), creation.arguments)
        val default = assertIs<Call>(backend.defaultValue(helper, "options"))
        assertEquals("com.example.fixture.helpers.defaultActivityOptions", default.callee?.function?.name)
        assertEquals("helpers/src/main/kotlin/com/example/fixture/helpers/Stubs.kt", default.location.file)
        assertNull(backend.defaultValue(helper, "no-such-parameter"))

        val reserveAll = assertNotNull(body("inlining.HelperInliningWorkflowImpl").call("reserveAll").callee).function
        assertNull(backend.defaultValue(reserveAll, "skus"), "a parameter without a default")
    }

    @Test
    fun `compile-time constants are evaluated wherever they are declared`() {
        val execute = body("primitives.UntypedActivityWorkflowImpl").calls().filter { it.name == "execute" }
        assertEquals(
            listOf(Constant.Text("ReserveStock"), Constant.Text("ReleaseStock")),
            execute.map { it.arguments.first().constant },
        )
        assertEquals(listOf("\"ReserveStock\"", "RELEASE_STOCK_ACTIVITY"), execute.map { it.arguments.first().text })

        // A constant of a Java class, and one that needs string concatenation at run time.
        val getVersion = body("primitives.VersionGateWorkflowImpl").call("getVersion")
        assertEquals(
            listOf(Constant.Text("express-shipping"), Constant.Integer(-1), Constant.Integer(1)),
            getVersion.arguments.map { it.constant },
        )
        val computed = body("hardcases.ComputedActivityNameWorkflowImpl").call("execute")
        assertEquals("activityName", computed.arguments.first().text)
        assertNull(computed.arguments.first().constant)
    }

    @Test
    fun `reads and writes of the workflow's own properties are told from other accesses`() {
        val owner = "$WORKFLOWS.handlers.ApprovalWorkflowImpl"

        fun writes(name: String): List<Pair<String, String>> =
            body("handlers.ApprovalWorkflowImpl", name).walk().filterIsInstance<PropertyWrite>()
                .onEach { assertTrue(it.onThis, it.name) }
                .map { assertNotNull(it.property).toString().removePrefix("$owner.") to it.operator }
                .toList()

        assertEquals(listOf("approvals" to "+="), writes("onApprove"))
        assertEquals(listOf("rejectionReason" to "="), writes("reject"))
        assertEquals(listOf("requiredApprovals" to "="), writes("setRequiredApprovals"))
        assertEquals(listOf("lastNote" to "="), writes("addNote"))
        assertEquals(emptyList(), writes("validateRequiredApprovals"))

        // The condition of the await is a lambda; what it reads are properties of the workflow.
        val condition = assertIs<Lambda>(body("handlers.ApprovalWorkflowImpl").call("await").arguments.last().value)
        assertEquals(
            listOf("approvals", "requiredApprovals", "rejectionReason"),
            condition.walk().filterIsInstance<PropertyRead>().filter { it.onThis }.map { it.name }.toList(),
        )

        // A property of some other object is not the workflow's, and `this.x = x` still is.
        val order = body("order.OrderWorkflowImpl", "processOrder")
        val lines = order.walk().filterIsInstance<PropertyRead>().first { it.name == "lines" }
        assertFalse(lines.onThis)
        assertEquals("$WORKFLOWS.order.Order", lines.property?.owner)
        val explicitThis = body("order.OrderWorkflowImpl", "paymentReceived").walk().filterIsInstance<PropertyWrite>().single()
        assertTrue(explicitThis.onThis)
        assertEquals("$WORKFLOWS.order.OrderWorkflowImpl.paymentReference", explicitThis.property?.toString())
    }

    @Test
    fun `control flow and lambdas keep their shape`() {
        for ((className, name) in GOLDEN_FUNCTIONS) {
            val simpleName = className.substringAfterLast('.')
            GoldenFiles.assertMatches("code/$simpleName.$name.txt", CodePrinter.print(body(className, name)))
        }
    }

    @Test
    fun `lowering a function twice gives the same tree`() {
        val id = function("order.OrderWorkflowImpl", "processOrder").id
        assertEquals(backend.body(id), backend.body(id))
        assertEquals(CodePrinter.print(assertNotNull(backend.body(id))), CodePrinter.print(assertNotNull(backend.body(id))))
    }

    private companion object {
        const val WORKFLOWS = "com.example.fixture.workflows"
        const val ACTIVITIES = "com.example.fixture.activities"

        /** Functions whose whole tree is pinned by a golden file. */
        val GOLDEN_FUNCTIONS = listOf(
            "order.OrderWorkflowImpl" to "processOrder",
            "controlflow.BranchWorkflowImpl" to "run",
            "controlflow.LoopWorkflowImpl" to "run",
            "controlflow.ScopeFunctionWorkflowImpl" to "run",
            "controlflow.TryCatchWorkflowImpl" to "run",
            "primitives.NexusOperationWorkflowImpl" to "run",
            "primitives.ParallelWorkflowImpl" to "run",
        )
    }
}
