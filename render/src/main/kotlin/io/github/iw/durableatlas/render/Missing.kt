package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.render.limit.Limits

/** What is said of a diagram that was not drawn, wherever it is said. */
object Missing {
    /** One sentence for the other files of the workflow. */
    fun notice(missing: MissingDiagram): String =
        "This workflow has no ${shown(missing.format)} diagram: drawing one took longer than the limit of ${Limits.text(missing.limit)}${alsoSimpler(missing)}."

    /** What is added to "took longer than the limit" where simpler ways of drawing were tried as well. */
    fun alsoSimpler(missing: MissingDiagram): String = if (missing.tried.isEmpty()) "" else ", also when it was drawn more simply"

    /** A format as prose names it: SVG, not svg. */
    fun shown(format: String): String = if (format.length <= 4) format.uppercase() else format
}
