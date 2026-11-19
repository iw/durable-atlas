package io.github.iw.durableatlas.render.limit

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeadlineTest {
    private val ticker = ManualTicker()

    @Test
    fun `a deadline passes when its time is up, by the clock it is given`() {
        val deadline = Deadline(Duration.ofSeconds(30), ticker)
        assertFalse(deadline.passed)
        deadline.check()

        ticker.advance(Duration.ofSeconds(29))
        assertFalse(deadline.passed)

        ticker.advance(Duration.ofSeconds(1))
        assertTrue(deadline.passed)
        assertFailsWith<DrawingStopped> { deadline.check() }
    }

    @Test
    fun `the time starts when the deadline is made`() {
        ticker.advance(Duration.ofHours(1))
        val deadline = Deadline(Duration.ofSeconds(30), ticker)
        ticker.advance(Duration.ofSeconds(10))
        assertFalse(deadline.passed)
    }

    @Test
    fun `a deadline that is stopped has passed, whatever the clock says`() {
        val deadline = Deadline(Duration.ofSeconds(30), ticker)
        deadline.stop()
        assertTrue(deadline.passed)
        assertFailsWith<DrawingStopped> { deadline.check() }
    }

    @Test
    fun `without a limit nothing passes until it is stopped`() {
        val deadline = Deadline(null, ticker)
        ticker.advance(Duration.ofDays(365))
        assertFalse(deadline.passed)
        deadline.stop()
        assertTrue(deadline.passed)
        // Each deadline without a limit is its own: stopping one stops no other.
        assertFalse(Deadline.none().passed)
    }

    @Test
    fun `a limit is written in seconds, with the decimals it needs`() {
        assertEquals("30 s", Limits.text(Duration.ofSeconds(30)))
        assertEquals("0.5 s", Limits.text(Duration.ofMillis(500)))
        assertEquals("2.25 s", Limits.text(Duration.ofMillis(2250)))
        assertEquals("0.001 s", Limits.text(Duration.ofMillis(1)))
        assertEquals("0.000000001 s", Limits.text(Duration.ofNanos(1)))
        assertEquals("600 s", Limits.text(Duration.ofMinutes(10)))
        assertEquals("The diagram was not drawn within 30 s", DrawingTimeoutException(Duration.ofSeconds(30)).message)
    }
}
