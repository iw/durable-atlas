package com.example.fixture.workflows.order

import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.UpdateValidatorMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface OrderWorkflow {
    @WorkflowMethod
    fun processOrder(order: Order): OrderResult

    /** Signal `payment-received` (explicit name). */
    @SignalMethod(name = "payment-received")
    fun paymentReceived(paymentReference: String)

    /** Signal `cancelOrder` (default name). */
    @SignalMethod
    fun cancelOrder(reason: String)

    /** Query `order-status` (explicit name). */
    @QueryMethod(name = "order-status")
    fun status(): String

    /** Update `change-address` (explicit name), guarded by a validator. */
    @UpdateMethod(name = "change-address")
    fun changeAddress(address: String): String

    @UpdateValidatorMethod(updateName = "change-address")
    fun validateAddress(address: String)
}
