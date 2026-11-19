package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.inventory.Inventory
import io.github.iw.durableatlas.analyser.inventory.InventoryEntryPoint
import io.github.iw.durableatlas.analyser.inventory.InventoryJson
import io.github.iw.durableatlas.analyser.inventory.InventorySchema
import io.github.iw.durableatlas.analyser.inventory.InventoryWorkflow
import io.github.iw.durableatlas.analyser.testing.SampleModels
import io.github.iw.durableatlas.analyser.testing.SampleSources
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What discovery must find in the sample project, whichever way its model was extracted. The
 * expectations are those written down in the sample project's `workflows/README.md`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DiscoveryContract(protected val mode: ModelMode) {
    protected val backend: SourceBackend get() = SampleSources.of(mode)
    protected val discovery: Discovery by lazy { TemporalDiscovery.discover(backend) }
    protected val inventory: Inventory by lazy {
        Inventory.of(discovery, SampleModels.of(mode).index.commit, mode)
    }

    private fun workflow(type: String): InventoryWorkflow = inventory.workflows.single { it.workflowType == type }

    private fun InventoryWorkflow.handlers(): List<InventoryEntryPoint> =
        entryPoints.filter { it.kind != EntryPointKind.WORKFLOW_METHOD }

    @Test
    fun `every workflow type is found with its interface and its implementation`() {
        assertEquals(WORKFLOW_TYPES, inventory.workflows.map { it.workflowType })
        for (workflow in inventory.workflows) {
            // In the sample project a workflow type is its interface's simple name, with one exception.
            val interfaceName = workflow.interfaceName.substringAfterLast('.')
            val expectedType = if (interfaceName == "ShipmentWorkflow") "Shipment" else interfaceName
            assertEquals(expectedType, workflow.workflowType)
            assertTrue(workflow.interfaceName.startsWith("com.example.fixture.workflows."), workflow.interfaceName)
            assertEquals(
                listOf("${workflow.interfaceName}Impl"),
                workflow.implementations.map { it.className },
                workflow.workflowType,
            )
            assertEquals(EntryPointKind.WORKFLOW_METHOD, workflow.entryPoints.first().kind, workflow.workflowType)
            assertEquals(workflow.workflowType, workflow.entryPoints.first().name)
            for (implementation in workflow.implementations) {
                assertEquals(emptyList(), implementation.additionalEntryPoints, implementation.className)
            }
        }
    }

    @Test
    fun `the workflow type is the name given on the workflow method if there is one`() {
        val shipment = workflow("Shipment")
        assertEquals("com.example.fixture.workflows.order.ShipmentWorkflow", shipment.interfaceName)
        assertEquals(listOf(InventoryEntryPoint(EntryPointKind.WORKFLOW_METHOD, "Shipment", "ship")), shipment.entryPoints)
        assertFalse(inventory.workflows.any { it.workflowType == "ShipmentWorkflow" })
    }

    @Test
    fun `handlers are called by the name on their annotation, or else by their method`() {
        assertEquals(
            listOf(
                InventoryEntryPoint(EntryPointKind.SIGNAL, "approve", "onApprove"),
                InventoryEntryPoint(EntryPointKind.SIGNAL, "reject", "reject"),
                InventoryEntryPoint(EntryPointKind.QUERY, "approvalCount", "approvalCount"),
                InventoryEntryPoint(EntryPointKind.QUERY, "status", "currentStatus"),
                InventoryEntryPoint(EntryPointKind.UPDATE, "addNote", "addNote"),
                InventoryEntryPoint(EntryPointKind.UPDATE, "set-required-approvals", "setRequiredApprovals"),
                InventoryEntryPoint(EntryPointKind.UPDATE_VALIDATOR, "set-required-approvals", "validateRequiredApprovals"),
            ),
            workflow("ApprovalWorkflow").handlers(),
        )
        assertEquals(
            listOf(
                InventoryEntryPoint(EntryPointKind.SIGNAL, "cancelOrder", "cancelOrder"),
                InventoryEntryPoint(EntryPointKind.SIGNAL, "payment-received", "paymentReceived"),
                InventoryEntryPoint(EntryPointKind.QUERY, "order-status", "status"),
                InventoryEntryPoint(EntryPointKind.UPDATE, "change-address", "changeAddress"),
                InventoryEntryPoint(EntryPointKind.UPDATE_VALIDATOR, "change-address", "validateAddress"),
            ),
            workflow("OrderWorkflow").handlers(),
        )
        assertEquals(
            listOf(InventoryEntryPoint(EntryPointKind.SIGNAL, "release", "release")),
            workflow("AwaitWorkflow").handlers(),
        )
        val withHandlers = setOf("ApprovalWorkflow", "AwaitWorkflow", "OrderWorkflow")
        for (workflow in inventory.workflows.filter { it.workflowType !in withHandlers }) {
            assertEquals(emptyList(), workflow.handlers(), workflow.workflowType)
        }
    }

    @Test
    fun `each entry point is tied to the function of the implementation that runs for it`() {
        val order = discovery.workflows.single { it.name == "OrderWorkflow" }
        val implementation = order.implementations.single()
        assertEquals(
            listOf(
                "processOrder", "cancelOrder", "paymentReceived", "status", "changeAddress", "validateAddress",
            ).map { "com.example.fixture.workflows.order.OrderWorkflowImpl.$it" },
            implementation.entryPoints.map { assertNotNull(it.function).id.name },
        )
        for (implemented in implementation.entryPoints) {
            val function = assertNotNull(implemented.function)
            assertEquals(listOf(implemented.entryPoint.method.id), function.overrides)
            assertEquals(
                "workflows/src/main/kotlin/com/example/fixture/workflows/order/OrderWorkflowImpl.kt",
                function.location?.file,
            )
            assertNotNull(backend.body(function.id), function.id.toString())
        }
    }

    @Test
    fun `an annotation that only shares its simple name with the SDK's makes nothing a workflow`() {
        assertFalse(inventory.workflows.any { it.workflowType == "ReportWorkflow" })
        assertFalse(inventory.workflows.any { "unrelated" in it.interfaceName })
        assertFalse(inventory.workflows.any { workflow -> workflow.implementations.any { "unrelated" in it.className } })
    }

    @Test
    fun `activity types follow the interface's prefix and the method's annotation`() {
        fun activities(name: String): List<Pair<String, String>> =
            inventory.activityInterfaces
                .single { it.interfaceName == "com.example.fixture.activities.$name" }
                .activities.map { it.method to it.name }

        assertEquals(
            listOf("InventoryActivities", "NotificationActivities", "PaymentActivities", "ShippingActivities"),
            inventory.activityInterfaces.map { it.interfaceName.substringAfterLast('.') },
        )
        assertEquals(
            listOf("checkAvailability" to "CheckAvailability", "release" to "ReleaseStock", "reserveStock" to "ReserveStock"),
            activities("InventoryActivities"),
        )
        // An explicit name replaces the prefix as well.
        assertEquals(
            listOf("capture" to "CapturePayment", "charge" to "PaymentCharge", "refund" to "PaymentRefund"),
            activities("PaymentActivities"),
        )
        assertEquals(
            listOf("quoteShipping" to "QuoteShipping", "shipExpress" to "ShipExpress", "shipItem" to "ShipItem"),
            activities("ShippingActivities"),
        )
        assertEquals(
            listOf("audit" to "NotifyAudit", "email" to "NotifyEmail", "sms" to "NotifySms"),
            activities("NotificationActivities"),
        )
        assertEquals(
            listOf(null, "Notify", "Payment", null),
            inventory.activityInterfaces.map { it.namePrefix },
        )
        for (activityInterface in inventory.activityInterfaces) {
            assertEquals(
                listOf("${activityInterface.interfaceName}Impl"),
                activityInterface.implementations.map { it.className },
            )
        }
    }

    @Test
    fun `nothing in the sample project gives rise to a warning`() {
        assertEquals(emptyList(), discovery.warnings)
    }

    @Test
    fun `the inventory matches its schema and survives a round trip`() {
        val json = InventoryJson.encode(inventory)
        JsonSchemaChecker.forSchema(InventorySchema.text()).assertValid(json)
        assertEquals(inventory, InventoryJson.decode(json))
        assertEquals(json, InventoryJson.encode(InventoryJson.decode(json)))
    }

    @Test
    fun `discovering twice gives the same result`() {
        assertEquals(discovery, TemporalDiscovery.discover(backend))
    }

    protected companion object {
        /** Every workflow type of the sample project, in the order of the inventory. */
        val WORKFLOW_TYPES = listOf(
            "ApplicationFailureWorkflow",
            "ApprovalWorkflow",
            "AwaitWorkflow",
            "BranchWorkflow",
            "ComputedActivityNameWorkflow",
            "ContinueAsNewWorkflow",
            "DeepHelperChainWorkflow",
            "DynamicDispatchWorkflow",
            "DynamicFanOutWorkflow",
            "EarlyReturnWorkflow",
            "GreetingChildWorkflow",
            "HelperInliningWorkflow",
            "LocalActivityWorkflow",
            "LoopWorkflow",
            "MethodReferenceWorkflow",
            "NexusOperationWorkflow",
            "OrderWorkflow",
            "ParallelWorkflow",
            "ParentWorkflow",
            "RecursionWorkflow",
            "SagaWorkflow",
            "ScopeFunctionWorkflow",
            "Shipment",
            "SideEffectWorkflow",
            "SignalExternalWorkflow",
            "TimerWorkflow",
            "TryCatchWorkflow",
            "TypedActivityWorkflow",
            "UntypedActivityWorkflow",
            "VersionGateWorkflow",
        )
    }
}
