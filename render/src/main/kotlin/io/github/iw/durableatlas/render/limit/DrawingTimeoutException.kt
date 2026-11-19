package io.github.iw.durableatlas.render.limit

import java.math.BigDecimal
import java.time.Duration

/**
 * A diagram was not drawn within the time allowed for it, and the drawing was given up.
 *
 * @param limit the time that was allowed
 * @param running whether the drawing could not be stopped: it then goes on in a thread of its
 *   own, which takes processor time until the step it is in comes to an end, and nothing is
 *   done with what it draws
 */
class DrawingTimeoutException(val limit: Duration, val running: Boolean = false) :
    RuntimeException("The diagram was not drawn within ${Limits.text(limit)}")

/** How a time limit is written for people. */
object Limits {
    /** [limit] in seconds, with as many decimal places as it needs: `30 s`, `0.5 s`. */
    fun text(limit: Duration): String {
        val seconds = BigDecimal.valueOf(limit.seconds).add(BigDecimal.valueOf(limit.nano.toLong(), NANO_DIGITS))
        // Without the zeros at its end, and never as a power of ten.
        val plain = if (seconds.signum() == 0) "0" else seconds.stripTrailingZeros().toPlainString()
        return "$plain s"
    }

    private const val NANO_DIGITS = 9
}
