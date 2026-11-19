package io.github.iw.durableatlas.render.limit

import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs a drawing and gives it up when it takes longer than allowed.
 *
 * With a limit, the drawing runs in a thread of its own while the caller waits. When the time is
 * up the drawing is told to stop, which it does as soon as one of its steps asks. A drawing that
 * is deep in a step that asks nothing gets [grace] to come out of it; after that the caller goes
 * on without it. The thread is a daemon, so it never keeps the process alive, and it ends by
 * itself at the next step that asks.
 *
 * Without a limit the drawing runs where it is called.
 *
 * @param grace how long a drawing that was told to stop is waited for
 */
internal class DrawingRunner(private val ticker: Ticker = Ticker.SYSTEM, private val grace: Duration = GRACE) {
    /**
     * @param work the drawing; it is handed what to ask whether to go on
     * @throws DrawingTimeoutException if the drawing was not done within [limit]
     */
    fun <T> run(limit: Duration?, work: (Deadline) -> T): T {
        if (limit == null) return work(Deadline.none())
        val deadline = Deadline(limit, ticker)
        val task = FutureTask { work(deadline) }
        val thread = Thread(task, THREAD_NAME).apply { isDaemon = true }
        thread.start()
        try {
            return result(task, limit.toNanos(), limit)
        } catch (_: TimeoutException) {
            deadline.stop()
        }
        try {
            // A drawing that was all but done when its time ran out is taken, rather than drawn again more simply.
            return result(task, grace.toNanos(), limit)
        } catch (_: TimeoutException) {
            // Whatever it waits for, it should not go on waiting.
            thread.interrupt()
            throw DrawingTimeoutException(limit, running = true)
        }
    }

    /** What [task] came to, waiting [nanos] at most. A drawing that stopped itself has run out of time. */
    private fun <T> result(task: FutureTask<T>, nanos: Long, limit: Duration): T =
        try {
            task.get(nanos, TimeUnit.NANOSECONDS)
        } catch (e: ExecutionException) {
            throw if (e.cause is DrawingStopped) DrawingTimeoutException(limit) else e.cause ?: e
        }

    companion object {
        val GRACE: Duration = Duration.ofSeconds(1)
        const val THREAD_NAME: String = "durable-atlas-drawing"
    }
}
