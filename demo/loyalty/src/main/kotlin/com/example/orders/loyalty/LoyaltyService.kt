package com.example.orders.loyalty

import io.nexusrpc.Operation
import io.nexusrpc.Service

/** The loyalty programme's Nexus service, registered under the name `Loyalty`. */
@Service(name = "Loyalty")
interface LoyaltyService {
    /** Credits the points earned by an order and returns the customer's account afterwards. */
    @Operation
    fun awardPoints(request: AwardPointsRequest): LoyaltyAccount
}

// Every property has a default so that the default JSON converter can construct these.

data class AwardPointsRequest(
    val customerId: String = "",
    val orderId: String = "",
    val amountCents: Long = 0,
)

data class LoyaltyAccount(
    val customerId: String = "",
    val points: Long = 0,
    val tier: LoyaltyTier = LoyaltyTier.STANDARD,
)

enum class LoyaltyTier {
    STANDARD,
    GOLD,
}
