package com.example.fixture.activities

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/** Prefixed interface: default activity type names are `Payment` + the capitalised method name. */
@ActivityInterface(namePrefix = "Payment")
interface PaymentActivities {
    /** Activity type `PaymentCharge`. */
    fun charge(orderId: String, amountCents: Long): String

    /** Activity type `PaymentRefund`. */
    fun refund(paymentId: String)

    /** Activity type `CapturePayment`: an explicit name replaces the prefix as well as the method name. */
    @ActivityMethod(name = "CapturePayment")
    fun capture(paymentId: String)
}
