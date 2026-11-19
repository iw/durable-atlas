package com.example.fixture.activities

class InventoryActivitiesImpl : InventoryActivities {
    override fun reserveStock(sku: String, quantity: Int): String = "res-$sku-$quantity"

    override fun release(reservationId: String) {
        // Nothing to undo in the fixture.
    }

    override fun checkAvailability(sku: String): Boolean = !sku.startsWith(OUT_OF_STOCK_PREFIX)

    companion object {
        /** SKUs starting with this prefix are reported as unavailable. */
        const val OUT_OF_STOCK_PREFIX = "OOS-"
    }
}
