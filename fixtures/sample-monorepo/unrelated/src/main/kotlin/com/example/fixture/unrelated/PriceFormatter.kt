package com.example.fixture.unrelated

/** Formats minor-unit amounts for display. */
object PriceFormatter {
    fun format(amountCents: Long, currency: String = "EUR"): String {
        val sign = if (amountCents < 0) "-" else ""
        val absolute = kotlin.math.abs(amountCents)
        return "%s%d.%02d %s".format(sign, absolute / 100, absolute % 100, currency)
    }
}
