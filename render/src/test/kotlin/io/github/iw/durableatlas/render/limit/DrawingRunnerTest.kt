package io.github.iw.durableatlas.render.limit

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * No test here waits for time to pass: a drawing that is to run out of time moves the clock
 * itself, or never ends until the test lets it.
 */
class DrawingRunnerTest {
    private val ticker = ManualTicker()
    private val limit = Duration.ofSeconds(30)

    /** Long enough that no machine is too slow for a drawing that ends at once. */
    private val ample = Duration.ofMinutes(10)

    @Test
    fun `without a limit the drawing runs where it is called and may take as long as it needs`() {
        val caller = Thread.currentThread()
        val result = DrawingRunner(ticker).run(null) { deadline ->
            assertSame(caller, Thread.currentThread())
            assertNull(deadline.limit)
            ticker.advance(Duration.ofDays(1))
            deadline.check()
            "drawn"
        }
        assertEquals("drawn", result)
    }

    @Test
    fun `with a limit the drawing runs in a daemon thread of its own and its result comes back`() {
        val caller = Thread.currentThread()
        val result = DrawingRunner(ticker).run(ample) { deadline ->
            assertNotSame(caller, Thread.currentThread())
            assertTrue(Thread.currentThread().isDaemon)
            assertEquals(DrawingRunner.THREAD_NAME, Thread.currentThread().name)
            assertEquals(ample, deadline.limit)
            deadline.check()
            "drawn"
        }
        assertEquals("drawn", result)
    }

    @Test
    fun `a drawing whose time is up when it asks is given up, and nothing of it is left running`() {
        var after = false
        val failure = assertFailsWith<DrawingTimeoutException> {
            DrawingRunner(ticker).run(limit) { deadline ->
                ticker.advance(Duration.ofSeconds(31))
                deadline.check()
                after = true
            }
        }
        assertEquals(limit, failure.limit)
        assertFalse(failure.running)
        assertFalse(after, "the drawing went on after its time was up")
    }

    @Test
    fun `a drawing that does not ask is told to stop when the time is up, and is left behind if it does not`() {
        val begun = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var stopped: Boolean? = null
        // The clock that the drawing would ask stands still: only the wait of the caller ends.
        val failure = assertFailsWith<DrawingTimeoutException> {
            DrawingRunner(ticker, grace = Duration.ofMillis(1)).run(Duration.ofMillis(1)) { deadline ->
                begun.countDown()
                try {
                    // Deaf to everything, as a phase of the layout library is.
                    while (true) {
                        try {
                            if (release.await(1, TimeUnit.DAYS)) break
                        } catch (_: InterruptedException) {
                            continue
                        }
                    }
                    stopped = deadline.passed
                } finally {
                    ended.countDown()
                }
            }
        }
        assertTrue(failure.running)
        assertEquals(Duration.ofMillis(1), failure.limit)
        // The drawing is still there, and finds that it was told to stop as soon as it asks.
        assertTrue(begun.await(1, TimeUnit.MINUTES))
        assertEquals(1, ended.count)
        release.countDown()
        assertTrue(ended.await(1, TimeUnit.MINUTES))
        assertEquals(true, stopped)
    }

    @Test
    fun `a drawing that is done while it is waited for after its time is taken`() {
        val late = CountDownLatch(1)
        // The time is up at once; the drawing ends as soon as it sees that it was told to stop, with its result.
        val result = DrawingRunner(ticker, grace = ample).run(Duration.ofNanos(1)) { deadline ->
            while (!deadline.passed) Thread.onSpinWait()
            late.countDown()
            "drawn all the same"
        }
        assertEquals("drawn all the same", result)
        assertEquals(0, late.count)
    }

    @Test
    fun `what a drawing fails with reaches the caller as it is`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            DrawingRunner(ticker).run(ample) { throw IllegalArgumentException("the document contradicts itself") }
        }
        assertEquals("the document contradicts itself", failure.message)
        assertFailsWith<IllegalStateException> { DrawingRunner(ticker).run(null) { error("broken") } }
    }
}
