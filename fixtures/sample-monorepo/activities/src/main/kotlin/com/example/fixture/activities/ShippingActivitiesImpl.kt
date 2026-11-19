package com.example.fixture.activities

class ShippingActivitiesImpl : ShippingActivities {
    override fun shipItem(orderId: String, sku: String): String = "trk-$orderId-$sku"

    override fun shipExpress(orderId: String, sku: String): String = "exp-$orderId-$sku"

    override fun quoteShipping(sku: String): Long = 499L
}
