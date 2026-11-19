package com.example.fixture.helpers

import com.example.fixture.activities.InventoryActivities

/** Top-level helper that loops over an activity call. */
fun reserveAll(inventory: InventoryActivities, skus: List<String>): List<String> {
    val reservationIds = mutableListOf<String>()
    for (sku in skus) {
        reservationIds += inventory.reserveStock(sku, 1)
    }
    return reservationIds
}
