package com.example.orders.activities

import com.example.orders.Order
import io.temporal.activity.ActivityInterface
import io.temporal.failure.ApplicationFailure

@ActivityInterface
interface PaymentActivities {
    /** Fails with `FraudSuspected` if the order should not be accepted. */
    fun fraudCheck(order: Order)

    /** Reserves the amount on the customer's payment method and returns the authorisation id. */
    fun authorisePayment(orderId: String, amountCents: Long): String

    /** Releases the authorisation of an order. Does nothing if there is none. */
    fun voidPayment(orderId: String)
}

/** Stands in for the payment provider. */
class PaymentActivitiesImpl : PaymentActivities {
    override fun fraudCheck(order: Order) {
        if (order.totalCents() > FRAUD_REVIEW_THRESHOLD_CENTS) {
            throw ApplicationFailure.newNonRetryableFailure(
                "Order ${order.orderId} needs a manual review",
                "FraudSuspected",
            )
        }
    }

    override fun authorisePayment(orderId: String, amountCents: Long): String = "auth-$orderId"

    override fun voidPayment(orderId: String) = Unit

    private companion object {
        const val FRAUD_REVIEW_THRESHOLD_CENTS = 1_000_000_00L
    }
}
