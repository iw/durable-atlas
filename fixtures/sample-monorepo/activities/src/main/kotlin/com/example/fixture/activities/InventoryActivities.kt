package com.example.fixture.activities

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/** No prefix: activity type names are the capitalised method names unless overridden. */
@ActivityInterface
interface InventoryActivities {
    /** Activity type `ReserveStock`. */
    fun reserveStock(sku: String, quantity: Int): String

    /** Activity type `ReleaseStock` (explicit name; the default would be `Release`). */
    @ActivityMethod(name = "ReleaseStock")
    fun release(reservationId: String)

    /** Activity type `CheckAvailability`. */
    fun checkAvailability(sku: String): Boolean
}
