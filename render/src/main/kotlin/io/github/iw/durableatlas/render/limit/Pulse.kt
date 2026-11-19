package io.github.iw.durableatlas.render.limit

import java.time.Duration
import java.util.Timer
import java.util.TimerTask

/** Says at intervals that a piece of work is still going on, to whoever would otherwise see nothing. */
interface Pulse {
    /**
     * Runs [work] and returns what it returns. While it runs, [beat] is called now and then, from
     * another thread, with the time the work has taken so far; never after [work] has ended.
     */
    fun <T> during(beat: (Duration) -> Unit, work: () -> T): T

    companion object {
        /** Never says anything. */
        val SILENT: Pulse = object : Pulse {
            override fun <T> during(beat: (Duration) -> Unit, work: () -> T): T = work()
        }

        /** Beats [first] after the work began and then every [interval], by the clock of the machine. */
        fun timed(first: Duration = Duration.ofSeconds(5), interval: Duration = Duration.ofSeconds(10)): Pulse = TimedPulse(first, interval)
    }
}

private class TimedPulse(private val first: Duration, private val interval: Duration) : Pulse {
    override fun <T> during(beat: (Duration) -> Unit, work: () -> T): T {
        val start = System.nanoTime()
        val lock = Any()
        var over = false
        // A daemon, so that a run that fails elsewhere is not kept alive by it.
        val timer = Timer("durable-atlas-progress", true)
        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    // Whoever is told that the work goes on is told so before being told that it is done.
                    synchronized(lock) { if (!over) beat(Duration.ofNanos(System.nanoTime() - start)) }
                }
            },
            first.toMillis(),
            interval.toMillis(),
        )
        try {
            return work()
        } finally {
            synchronized(lock) { over = true }
            timer.cancel()
        }
    }
}
