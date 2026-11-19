package io.github.iw.durableatlas.cli

import io.github.iw.durableatlas.render.DrawingProgress
import io.github.iw.durableatlas.render.DrawingStep
import io.github.iw.durableatlas.render.MissingDiagram
import io.github.iw.durableatlas.render.SimplifiedDiagram
import io.github.iw.durableatlas.render.Simplification
import io.github.iw.durableatlas.render.limit.Limits
import java.time.Duration
import java.util.Locale

/**
 * Says on standard error how the drawing gets on: one line for every workflow when it is drawn,
 * with its place in the run, its size and the time it took. A workflow that takes long gets a
 * line while it is being drawn, and one for each drawing of it that is given up, so a run that
 * stalls has named the workflow it stalls on. Lines are short enough to be read off a screen.
 *
 * @param log writes one line; it is called from the thread that draws and from one beside it
 */
internal class ProgressLines(private val log: (String) -> Unit) : DrawingProgress {
    override fun stillDrawing(step: DrawingStep, elapsed: Duration) {
        log("${head(step)}: still drawing after ${elapsed.seconds} s (${size(step)})")
    }

    override fun timedOut(step: DrawingStep, format: String, limit: Duration, running: Boolean, next: Simplification.Level?) {
        val runsOn = if (running) "; its layout did not stop and runs on in the background" else ""
        val then = if (next == null) "giving it up" else "drawing it with ${next.words}"
        log("${head(step)}: $format not drawn within ${Limits.text(limit)}$runsOn; $then")
    }

    override fun drawn(step: DrawingStep, elapsed: Duration, missing: List<MissingDiagram>, simplified: List<SimplifiedDiagram>) {
        val left = missing.joinToString("") { ", no ${it.format}" }
        val simpler = simplified.joinToString("") { ", ${it.format} with ${it.simplification.level.words}" }
        log("${head(step)}: ${size(step)}, ${seconds(elapsed)}$left$simpler")
    }

    private fun head(step: DrawingStep): String = "${step.number}/${step.count} ${step.name}"

    private fun size(step: DrawingStep): String =
        count(step.nodes, "node") + step.executions?.let { ", ${count(it, "execution")}" }.orEmpty()

    private fun count(number: Int, noun: String): String = if (number == 1) "1 $noun" else "$number ${noun}s"

    companion object {
        /** [elapsed] in seconds with one decimal place, the same in every locale. */
        fun seconds(elapsed: Duration): String = String.format(Locale.ROOT, "%.1f s", elapsed.toMillis() / 1000.0)
    }
}
