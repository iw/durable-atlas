package io.github.iw.durableatlas.render.limit

import java.time.Duration

/** Where a drawing takes the time from. Only differences between two readings mean anything. */
fun interface Ticker {
    fun nanos(): Long

    companion object {
        val SYSTEM: Ticker = Ticker(System::nanoTime)
    }
}

/** Ends a drawing that was told to stop. Never leaves the code that draws. */
internal class DrawingStopped : RuntimeException() {
    // It is thrown to unwind, not to be read.
    override fun fillInStackTrace(): Throwable = this
}

/**
 * What the steps of one drawing ask whether to go on. The time starts when it is made.
 *
 * A drawing stops in two ways. Its own steps ask [check] before and after everything that takes
 * long, and so does the layout library between its phases. And whoever waits for the drawing may
 * [stop] it from another thread, which is the only way to end one that sits in a step that asks
 * nothing.
 *
 * @param limit how long the drawing may take; null for as long as it needs
 */
internal class Deadline(val limit: Duration?, private val ticker: Ticker = Ticker.SYSTEM) {
    private val start = ticker.nanos()

    @Volatile
    private var stopped = false

    /** Whether the drawing is to stop: its time is up, or it was told to. */
    val passed: Boolean
        get() = stopped || (limit != null && ticker.nanos() - start >= limit.toNanos())

    fun stop() {
        stopped = true
    }

    /** @throws DrawingStopped if the drawing is to stop */
    fun check() {
        if (passed) throw DrawingStopped()
    }

    companion object {
        /** For a drawing that may take as long as it needs. */
        fun none(): Deadline = Deadline(null)
    }
}
