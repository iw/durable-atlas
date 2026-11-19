package io.github.iw.durableatlas.render.limit

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A clock that only moves when a test moves it, and counts how often it is read. */
class ManualTicker : Ticker {
    private val now = AtomicLong()
    private val asked = AtomicInteger()

    /** How often the time was read. */
    val readings: Int
        get() = asked.get()

    override fun nanos(): Long {
        asked.incrementAndGet()
        return now.get()
    }

    fun advance(by: Duration) {
        now.addAndGet(by.toNanos())
    }
}

/** A pulse that beats when a test says so: while the work runs, [beat] tells the listener that so much time has passed. */
class ManualPulse : Pulse {
    private var listener: ((Duration) -> Unit)? = null

    /** How many pieces of work were watched. */
    var watched = 0
        private set

    fun beat(elapsed: Duration) {
        checkNotNull(listener) { "No work is going on" }(elapsed)
    }

    override fun <T> during(beat: (Duration) -> Unit, work: () -> T): T {
        watched++
        listener = beat
        try {
            return work()
        } finally {
            listener = null
        }
    }
}
