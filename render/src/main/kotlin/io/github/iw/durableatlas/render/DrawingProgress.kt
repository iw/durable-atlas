package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.render.limit.DrawingTimeoutException
import io.github.iw.durableatlas.render.limit.Pulse
import io.github.iw.durableatlas.render.limit.Ticker
import java.time.Duration

/**
 * One workflow of a run that draws diagrams, as whoever watches the run is told of it.
 *
 * @param number its place in the run, from one
 * @param count how many workflows the run draws
 * @param name what its files are called, which no other workflow of the run is called
 * @param nodes how many nodes its document has
 * @param executions for an overlay, how many executions are laid over it
 */
data class DrawingStep(
    val number: Int,
    val count: Int,
    val name: String,
    val workflowType: String,
    val nodes: Int,
    val executions: Int? = null,
)

/**
 * A format in which a workflow has no diagram, because none was drawn in the time allowed.
 *
 * @param format [DiagramFormat.name] of the format
 * @param limit the time each attempt was allowed
 * @param tried the simpler ways of drawing it that were tried as well, each with the same time
 */
data class MissingDiagram(val format: String, val limit: Duration, val tried: List<Simplification.Level> = emptyList())

/** A format in which the diagram of a workflow is drawn more simply than was asked for, and how. */
data class SimplifiedDiagram(val format: String, val simplification: Simplification)

/**
 * Hears how a run that draws diagrams gets on, one workflow after the other. A run that stalls
 * has then said which workflow it stalls on.
 */
interface DrawingProgress {
    /** The workflow is still being drawn after [elapsed]. Called from another thread than the one that draws. */
    fun stillDrawing(step: DrawingStep, elapsed: Duration) {}

    /**
     * A drawing of the workflow in [format] took longer than [limit] and was given up.
     *
     * @param running whether the drawing could not be stopped and goes on in the background, see [DrawingTimeoutException.running]
     * @param next the simpler way in which it is drawn next; null if there is none left, so that the workflow has no diagram in that format
     */
    fun timedOut(step: DrawingStep, format: String, limit: Duration, running: Boolean, next: Simplification.Level?) {}

    /**
     * Everything of the workflow is drawn and written.
     *
     * @param missing the formats it has no diagram in
     * @param simplified the formats in which its diagram is simpler than was asked for
     */
    fun drawn(step: DrawingStep, elapsed: Duration, missing: List<MissingDiagram>, simplified: List<SimplifiedDiagram>) {}

    companion object {
        val NONE: DrawingProgress = object : DrawingProgress {}
    }
}

/**
 * Draws one workflow after the other and tells a [DrawingProgress] of each: now and then that it
 * is still being drawn, each time a drawing is given up, and in the end how long it took.
 *
 * @param pulse when to say that a workflow is still being drawn
 * @param ticker where the time a workflow took is read from
 */
class DrawingWatch(
    private val progress: DrawingProgress = DrawingProgress.NONE,
    private val pulse: Pulse = Pulse.timed(),
    private val ticker: Ticker = Ticker.SYSTEM,
) {
    /** Runs [work], which draws and writes everything of [step], and reports on it. */
    fun <T> draw(step: DrawingStep, work: (Attempt) -> T): T {
        val start = ticker.nanos()
        val attempt = Attempt(step)
        val result = pulse.during({ elapsed -> progress.stillDrawing(step, elapsed) }) { work(attempt) }
        progress.drawn(step, Duration.ofNanos(ticker.nanos() - start), attempt.missing, attempt.simplified)
        return result
    }

    /** The drawing of one workflow: prepares its diagrams and keeps note of those that are missing or simpler than asked for. */
    inner class Attempt internal constructor(private val step: DrawingStep) {
        private val gaps = ArrayList<MissingDiagram>()
        private val simpler = ArrayList<SimplifiedDiagram>()

        /** The formats in which [prepare] drew no diagram, so far. */
        val missing: List<MissingDiagram>
            get() = gaps.toList()

        /** The formats in which [prepare] drew a diagram more simply than was asked for, so far. */
        val simplified: List<SimplifiedDiagram>
            get() = simpler.toList()

        /**
         * The diagram of [document] in [format]: as [options] ask for it if that can be drawn in
         * the time the format allows, else in the first of the format's simpler ways that can
         * (see [DiagramFormat.simplifications]), each of which is given that time again.
         *
         * @return null if none was drawn in time: the workflow then has no diagram in this format
         * @throws IllegalArgumentException if the document is not consistent
         */
        fun prepare(format: DiagramFormat, document: IrDocument, options: PageOptions): PreparedDiagram? {
            val ways = format.simplifications(document, options)
            var level: Simplification.Level? = null
            var next = 0
            while (true) {
                try {
                    val page = if (level == null) options else options.copy(render = options.render.copy(simplification = Simplification(level, limitOf(format))))
                    val diagram = format.prepare(document, page)
                    diagram.simplification?.let { simpler += SimplifiedDiagram(format.name, it) }
                    return diagram
                } catch (e: DrawingTimeoutException) {
                    limits[format] = e.limit
                    val following = ways.getOrNull(next++)
                    progress.timedOut(step, format.name, e.limit, e.running, following)
                    if (following == null) {
                        gaps += MissingDiagram(format.name, e.limit, ways)
                        return null
                    }
                    level = following
                }
            }
        }

        /** The time [format] allowed the drawing that was given up last. */
        private val limits = HashMap<DiagramFormat, Duration>()

        private fun limitOf(format: DiagramFormat): Duration = limits.getValue(format)
    }
}
