package com.example.fixture.workflows.inlining

import com.example.fixture.activities.InventoryActivities
import com.example.fixture.activities.NotificationActivities
import com.example.fixture.activities.ShippingActivities
import com.example.fixture.helpers.ActivityAuditTrail
import com.example.fixture.helpers.AuditTrail
import com.example.fixture.helpers.PaymentProcessor
import com.example.fixture.helpers.WAREHOUSE_PHONE
import com.example.fixture.helpers.activityStub
import com.example.fixture.helpers.coolDown
import com.example.fixture.helpers.notifyOps
import com.example.fixture.helpers.quoteViaChain
import com.example.fixture.helpers.reserveAll
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface HelperInliningWorkflow {
    @WorkflowMethod
    fun run(orderId: String, skus: List<String>): String
}

class HelperInliningWorkflowImpl : HelperInliningWorkflow {
    private val inventory = activityStub<InventoryActivities>()
    private val notifications = activityStub<NotificationActivities>()
    private val shipping = activityStub<ShippingActivities>()
    private val paymentProcessor = PaymentProcessor()
    private val auditTrail: AuditTrail = ActivityAuditTrail(notifications)

    override fun run(orderId: String, skus: List<String>): String {
        // Top-level function in another module, containing a loop.
        reserveAll(inventory, skus)
        // Method of a helper class that owns its own stub.
        val paymentId = paymentProcessor.chargeAndCapture(orderId, 1_500)
        // Helper wrapping a Workflow API call.
        coolDown()
        // Three-level helper chain, well inside the depth limit.
        quoteViaChain(shipping, skus.first())
        // Extension function on an activity stub.
        notifications.notifyOps("order $orderId paid")
        // Interface with exactly one implementation.
        auditTrail.record("order $orderId paid with $paymentId")
        // Private member function.
        alertWarehouse(orderId)
        return paymentId
    }

    private fun alertWarehouse(orderId: String) {
        notifications.sms(WAREHOUSE_PHONE, "pick order $orderId")
    }
}
