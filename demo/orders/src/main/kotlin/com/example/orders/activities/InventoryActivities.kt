package com.example.orders.activities

import com.example.orders.OrderLine
import io.temporal.activity.ActivityInterface

@ActivityInterface
interface InventoryActivities {
    /** Sets the stock for an order aside and returns the reservation id. */
    fun reserveStock(orderId: String, lines: List<OrderLine>): String

    /** Returns the stock reserved for an order. Does nothing if there is none. */
    fun releaseStock(orderId: String)
}

/** Stands in for the inventory system. */
class InventoryActivitiesImpl : InventoryActivities {
    override fun reserveStock(orderId: String, lines: List<OrderLine>): String = "res-$orderId"

    override fun releaseStock(orderId: String) = Unit
}
