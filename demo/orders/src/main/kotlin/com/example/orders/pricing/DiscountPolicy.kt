package com.example.orders.pricing

import com.example.orders.Order
import com.example.orders.activities.PromotionActivities
import com.example.orders.loyalty.LoyaltyTier

/** Works out how much is taken off an order when it is invoiced. */
interface DiscountPolicy {
    fun discountCents(order: Order): Long
}

/** A fixed percentage for each loyalty tier. */
class TierDiscount(private val tier: LoyaltyTier) : DiscountPolicy {
    override fun discountCents(order: Order): Long {
        val percent = when (tier) {
            LoyaltyTier.STANDARD -> 0
            LoyaltyTier.GOLD -> 5
        }
        return order.totalCents() * percent / 100
    }
}

/** The percentage of a promotion code, as the promotions service has it at the moment. */
class PromotionDiscount(
    private val promotions: PromotionActivities,
    private val code: String,
) : DiscountPolicy {
    override fun discountCents(order: Order): Long = order.totalCents() * promotions.lookUpPromotion(code) / 100
}
