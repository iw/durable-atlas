package com.example.fixture.workflows.order

// Every property has a default so the default JSON converter can construct these.

data class OrderLine(
    val sku: String = "",
    val quantity: Int = 0,
    val unitPriceCents: Long = 0,
)

data class Order(
    val orderId: String = "",
    val customerEmail: String = "",
    val lines: List<OrderLine> = emptyList(),
)

data class OrderResult(
    val status: String = "",
    val paymentId: String? = null,
    val trackingIds: List<String> = emptyList(),
)
