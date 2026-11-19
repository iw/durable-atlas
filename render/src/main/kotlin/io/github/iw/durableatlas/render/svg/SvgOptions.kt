package io.github.iw.durableatlas.render.svg

import java.time.Duration

/** Which constructs the legend below a diagram explains. */
enum class LegendMode {
    /** Those that appear in the diagram. */
    PRESENT,

    /** All of them. */
    FULL,

    /** No legend. */
    NONE,
}

enum class Background {
    /** The warm off-white the notation is designed on. */
    PAPER,

    /** None, for pages that bring their own. */
    TRANSPARENT,
}

/**
 * Settings of the SVG diagrams.
 *
 * @param wrapWidth width, in SVG user units, above which the main flow is broken into rows; zero
 *   keeps it in one row however long it gets
 * @param legend which constructs the legend below the diagram explains
 * @param sourceBaseUrl when set, every card whose node has a source location links to
 *   `<sourceBaseUrl>/<file>#L<line>`. `{commit}` in it stands for the commit of the document, as
 *   in `https://example.org/repo/blob/{commit}`
 * @param background what is behind the diagram
 * @param embedFont whether the file carries the typeface it is designed for, so that it looks the
 *   same wherever it is opened. It makes every file more than a megabyte larger
 * @param timeLimit how long arranging one diagram may take; null for as long as it needs. A
 *   diagram that takes longer is given up, see [SvgFormat]
 */
data class SvgOptions(
    val wrapWidth: Int = DEFAULT_WRAP_WIDTH,
    val legend: LegendMode = LegendMode.PRESENT,
    val sourceBaseUrl: String? = null,
    val background: Background = Background.PAPER,
    val embedFont: Boolean = false,
    val timeLimit: Duration? = DEFAULT_TIME_LIMIT,
) {
    init {
        require(wrapWidth >= 0) { "wrapWidth must not be negative: $wrapWidth" }
        require(timeLimit == null || (!timeLimit.isNegative && !timeLimit.isZero)) { "timeLimit must be more than nothing: $timeLimit" }
    }

    companion object {
        const val DEFAULT_WRAP_WIDTH: Int = 1700

        /**
         * Many times what a diagram takes whose flow can be broken into rows, also where the
         * flow has hundreds of steps and the machine is slow; what reaches it is a long flow that
         * has to be placed whole. Little enough that a run in which several diagrams reach it is
         * still over in minutes.
         */
        val DEFAULT_TIME_LIMIT: Duration = Duration.ofSeconds(30)
    }
}
