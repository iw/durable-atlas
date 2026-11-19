package com.example.fixture.activities

import io.temporal.activity.ActivityInterface

@ActivityInterface
interface ShippingActivities {
    /** Activity type `ShipItem`. */
    fun shipItem(orderId: String, sku: String): String

    /** Activity type `ShipExpress`. */
    fun shipExpress(orderId: String, sku: String): String

    /** Activity type `QuoteShipping`. */
    fun quoteShipping(sku: String): Long
}
