package com.example.fixture.activities

import io.temporal.failure.ApplicationFailure

class PaymentActivitiesImpl : PaymentActivities {
    override fun charge(orderId: String, amountCents: Long): String {
        if (amountCents > CHARGE_LIMIT_CENTS) {
            throw ApplicationFailure.newNonRetryableFailure("card declined for $orderId", "CardDeclined")
        }
        return "pay-$orderId"
    }

    override fun refund(paymentId: String) {
        // Nothing to undo in the fixture.
    }

    override fun capture(paymentId: String) {
        // Nothing to capture in the fixture.
    }

    companion object {
        /** Charges above this amount are declined with a non-retryable failure. */
        const val CHARGE_LIMIT_CENTS = 1_000_000L
    }
}
