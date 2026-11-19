package com.example.orders

// Every property has a default so that the default JSON converter can construct these.

data class OrderLine(
    val sku: String = "",
    val quantity: Int = 0,
    val unitPriceCents: Long = 0,
)

/** The part of an order that leaves one warehouse in one parcel. */
data class Shipment(
    val shipmentId: String = "",
    val warehouseId: String = "",
    val skus: List<String> = emptyList(),
)

data class Order(
    val orderId: String = "",
    val customerId: String = "",
    val shippingAddress: String = "",
    val lines: List<OrderLine> = emptyList(),
    val shipments: List<Shipment> = emptyList(),
    val promotionCode: String? = null,
) {
    fun totalCents(): Long = lines.sumOf { it.quantity * it.unitPriceCents }
}

enum class OrderStatus {
    RECEIVED,
    AWAITING_PAYMENT,
    SHIPPING,
    RETURNS_WINDOW,
    COMPLETED,
    CANCELLED,
}

data class OrderResult(
    val orderId: String = "",
    val authorisationId: String = "",
    val invoiceNumber: String = "",
    val invoicedCents: Long = 0,
    val loyaltyPoints: Long = 0,
    val trackingNumbers: List<String> = emptyList(),
)
