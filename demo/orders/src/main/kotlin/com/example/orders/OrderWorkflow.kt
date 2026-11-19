package com.example.orders

import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.UpdateValidatorMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/** Takes one order from validation to the end of its returns window. */
@WorkflowInterface
interface OrderWorkflow {
    @WorkflowMethod
    fun processOrder(order: Order): OrderResult

    /** The payment provider confirmed the payment. */
    @SignalMethod
    fun approvePayment()

    /** The customer withdrew the order. It only takes effect while the order is unpaid. */
    @SignalMethod
    fun cancelOrder()

    @QueryMethod
    fun getStatus(): OrderStatus

    /** Changes where the order is shipped to and returns the address it replaces. */
    @UpdateMethod
    fun changeAddress(address: String): String

    @UpdateValidatorMethod(updateName = "changeAddress")
    fun validateChangeAddress(address: String)
}
