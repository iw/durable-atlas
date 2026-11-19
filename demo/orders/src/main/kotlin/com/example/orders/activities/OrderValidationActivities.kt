package com.example.orders.activities

import com.example.orders.Order
import io.temporal.activity.ActivityInterface
import io.temporal.failure.ApplicationFailure

/** Checks that need nothing but the order itself, so they run as local activities. */
@ActivityInterface
interface OrderValidationActivities {
    /** Fails with `InvalidOrder` if the order cannot be processed as it stands. */
    fun validateOrder(order: Order)
}

class OrderValidationActivitiesImpl : OrderValidationActivities {
    override fun validateOrder(order: Order) {
        val problem = when {
            order.lines.isEmpty() -> "it has no lines"
            order.lines.any { it.quantity <= 0 } -> "it has a line without a quantity"
            order.shippingAddress.isBlank() -> "it has no shipping address"
            else -> return
        }
        throw ApplicationFailure.newNonRetryableFailure("Order ${order.orderId} is invalid: $problem", "InvalidOrder")
    }
}
