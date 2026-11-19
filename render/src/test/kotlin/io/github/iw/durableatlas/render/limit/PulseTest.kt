package io.github.iw.durableatlas.render.limit

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class PulseTest {
    @Test
    fun `work that goes on is said to go on, from beside it, until it ends`() {
        val worker = Thread.currentThread()
        val beats = AtomicInteger()
        val second = CountDownLatch(2)
        var beside: Thread? = null
        var elapsed = Duration.ZERO
        val result = Pulse.timed(Duration.ofMillis(1), Duration.ofMillis(1)).during(
            beat = {
                beside = Thread.currentThread()
                elapsed = it
                beats.incrementAndGet()
                second.countDown()
            },
            // The work ends when it has been said twice that it goes on, however long that takes.
            work = {
                assertTrue(second.await(1, TimeUnit.MINUTES))
                "done"
            },
        )
        assertEquals("done", result)
        assertNotSame(worker, beside)
        assertTrue(beside!!.isDaemon)
        assertTrue(elapsed > Duration.ZERO)

        // Nothing is said of work that has ended.
        val heard = beats.get()
        Pulse.timed(Duration.ofMillis(1), Duration.ofMillis(1)).during({ }) { }
        assertEquals(heard, beats.get())
    }

    @Test
    fun `work that is over before the first beat is never spoken of`() {
        val beats = AtomicInteger()
        val result = Pulse.timed(Duration.ofDays(1), Duration.ofDays(1)).during({ beats.incrementAndGet() }) { 7 }
        assertEquals(7, result)
        assertEquals(0, beats.get())
        assertEquals(7, Pulse.SILENT.during({ beats.incrementAndGet() }) { 7 })
        assertEquals(0, beats.get())
    }

    @Test
    fun `work that fails ends the pulse too`() {
        val failure = runCatching { Pulse.timed(Duration.ofDays(1), Duration.ofDays(1)).during({ }) { error("broken") } }
        assertEquals("broken", failure.exceptionOrNull()?.message)
    }
}
