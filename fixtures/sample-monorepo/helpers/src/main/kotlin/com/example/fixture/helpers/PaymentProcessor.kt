package com.example.fixture.helpers

import com.example.fixture.activities.PaymentActivities

/** Helper class that owns its activity stub. Must be constructed from workflow code. */
class PaymentProcessor {
    private val payments: PaymentActivities = activityStub()

    fun chargeAndCapture(orderId: String, amountCents: Long): String {
        val paymentId = payments.charge(orderId, amountCents)
        payments.capture(paymentId)
        return paymentId
    }
}
