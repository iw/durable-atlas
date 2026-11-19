package com.example.fixture.workflows.controlflow

import com.example.fixture.activities.InventoryActivities
import com.example.fixture.activities.NotificationActivities
import com.example.fixture.activities.PaymentActivities
import com.example.fixture.activities.ShippingActivities
import com.example.fixture.helpers.OPS_EMAIL
import com.example.fixture.helpers.WAREHOUSE_PHONE
import com.example.fixture.helpers.activityStub
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface ScopeFunctionWorkflow {
    @WorkflowMethod
    fun run(orderId: String, sku: String): String
}

class ScopeFunctionWorkflowImpl : ScopeFunctionWorkflow {
    private val inventory = activityStub<InventoryActivities>()
    private val payments = activityStub<PaymentActivities>()
    private val notifications = activityStub<NotificationActivities>()
    private val shipping = activityStub<ShippingActivities>()

    override fun run(orderId: String, sku: String): String {
        // run: the activity call uses the implicit receiver.
        val reservationId = inventory.run { reserveStock(sku, 1) }

        // let, then also.
        orderId
            .let { id -> payments.charge(id, 500) }
            .also { paymentId -> payments.capture(paymentId) }

        // with: two activity calls on the implicit receiver.
        with(notifications) {
            email(OPS_EMAIL, "order $orderId reserved")
            sms(WAREHOUSE_PHONE, "pick $sku")
        }

        // apply
        shipping.apply { shipItem(orderId, sku) }

        return reservationId
    }
}
