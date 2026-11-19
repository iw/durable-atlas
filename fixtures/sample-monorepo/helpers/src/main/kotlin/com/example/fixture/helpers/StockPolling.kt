package com.example.fixture.helpers

import com.example.fixture.activities.InventoryActivities
import io.temporal.workflow.Workflow
import java.time.Duration

/** Directly recursive helper: polls availability, sleeping between attempts. */
fun waitForStock(inventory: InventoryActivities, sku: String, attemptsLeft: Int): Boolean {
    if (inventory.checkAvailability(sku)) {
        return true
    }
    if (attemptsLeft <= 0) {
        return false
    }
    Workflow.sleep(Duration.ofSeconds(30))
    return waitForStock(inventory, sku, attemptsLeft - 1)
}
