package com.example.orders.loyalty

import io.nexusrpc.handler.OperationHandler
import io.nexusrpc.handler.OperationImpl
import io.nexusrpc.handler.ServiceImpl
import java.util.concurrent.ConcurrentHashMap

/** Handles [LoyaltyService] with balances kept in memory: one point per whole unit of currency. */
@ServiceImpl(service = LoyaltyService::class)
class LoyaltyServiceHandler {
    private val balances = ConcurrentHashMap<String, Long>()

    @OperationImpl
    fun awardPoints(): OperationHandler<AwardPointsRequest, LoyaltyAccount> =
        OperationHandler.sync { _, _, request -> award(requireNotNull(request) { "awardPoints needs a request" }) }

    private fun award(request: AwardPointsRequest): LoyaltyAccount {
        val points = balances.merge(request.customerId, request.amountCents / CENTS_PER_POINT, Long::plus) ?: 0
        return LoyaltyAccount(
            customerId = request.customerId,
            points = points,
            tier = if (points >= GOLD_THRESHOLD) LoyaltyTier.GOLD else LoyaltyTier.STANDARD,
        )
    }

    private companion object {
        const val CENTS_PER_POINT = 100L
        const val GOLD_THRESHOLD = 1_000L
    }
}
