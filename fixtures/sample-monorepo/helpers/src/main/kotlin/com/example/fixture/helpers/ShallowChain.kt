package com.example.fixture.helpers

import com.example.fixture.activities.ShippingActivities

/** Three-level helper chain ending in an activity call. */
fun quoteViaChain(shipping: ShippingActivities, sku: String): Long = quoteChainLevel2(shipping, sku)

private fun quoteChainLevel2(shipping: ShippingActivities, sku: String): Long = quoteChainLevel3(shipping, sku)

private fun quoteChainLevel3(shipping: ShippingActivities, sku: String): Long = shipping.quoteShipping(sku)
