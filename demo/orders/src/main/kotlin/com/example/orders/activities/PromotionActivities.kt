package com.example.orders.activities

import io.temporal.activity.ActivityInterface

@ActivityInterface
interface PromotionActivities {
    /** The percentage a promotion code takes off an order; zero for a code that is not running. */
    fun lookUpPromotion(code: String): Int
}

/** Stands in for the promotions service. */
class PromotionActivitiesImpl : PromotionActivities {
    override fun lookUpPromotion(code: String): Int = PROMOTIONS[code] ?: 0

    private companion object {
        val PROMOTIONS = mapOf("WELCOME10" to 10, "SPRING15" to 15)
    }
}
