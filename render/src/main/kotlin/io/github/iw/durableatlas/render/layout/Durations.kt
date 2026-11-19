package io.github.iw.durableatlas.render.layout

import java.time.Duration
import java.time.format.DateTimeParseException

internal object Durations {
    private const val NANOS_PER_MILLI = 1_000_000

    /**
     * Turns an ISO-8601 duration such as `PT1H30M` into `1h 30m`. Text that is not a duration, or
     * one that the short form cannot express, is returned as it is.
     */
    fun humanise(text: String): String {
        val duration = try {
            Duration.parse(text.trim())
        } catch (_: DateTimeParseException) {
            return text
        }
        if (duration.isNegative || duration.toNanosPart() % NANOS_PER_MILLI != 0) return text
        if (duration.isZero) return "0s"
        val parts = listOf(
            duration.toDaysPart() to "d",
            duration.toHoursPart().toLong() to "h",
            duration.toMinutesPart().toLong() to "m",
            duration.toSecondsPart().toLong() to "s",
            duration.toMillisPart().toLong() to "ms",
        )
        return parts.filter { it.first != 0L }.joinToString(" ") { "${it.first}${it.second}" }
    }
}
