package io.github.iw.durableatlas.cli.commands

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.github.iw.durableatlas.render.DiagramFormat
import io.github.iw.durableatlas.render.DiagramFormats
import io.github.iw.durableatlas.render.DirectoryOptions
import io.github.iw.durableatlas.render.DrawingProgress
import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.svg.Background
import io.github.iw.durableatlas.render.svg.LegendMode
import io.github.iw.durableatlas.render.svg.SvgOptions
import java.time.Duration
import kotlin.math.roundToLong

/**
 * The options that decide how diagrams are drawn, shared by the commands that draw them. An
 * option that only one format understands belongs here too, next to the choice of formats.
 */
class DiagramOptions : OptionGroup(name = "Diagram options") {
    /** The formats to write, in the order of the registry. */
    val formats: List<DiagramFormat> by option(
        "--format",
        metavar = choices().joinToString("|"),
        help = "Diagram format to write. The default, '$ALL', writes every format.",
    )
        .convert { name ->
            select(name) ?: fail("'$name' is not a format this tool can write (choose from ${choices().joinToString(", ")})")
        }
        .default(DiagramFormats.all)

    val maxNodes: Int by option(
        "--max-nodes",
        metavar = "n",
        help = "Draw the regions of a workflow with more nodes than this (by default " +
            "${RenderOptions.DEFAULT_MAX_NODES}) as single nodes: loops, parallel sections and the like.",
    )
        .int()
        .restrictTo(min = 1)
        .default(RenderOptions.DEFAULT_MAX_NODES)

    val html: Boolean by option("--html", help = "Also write an HTML page per workflow that holds its SVG diagram.")
        .flag()

    val wrapWidth: Int by option(
        "--wrap-width",
        metavar = "n",
        help = "Break the main flow of an SVG diagram into rows when it is wider than this " +
            "(by default ${SvgOptions.DEFAULT_WRAP_WIDTH}); 0 keeps it in one row.",
    )
        .int()
        .restrictTo(min = 0)
        .default(SvgOptions.DEFAULT_WRAP_WIDTH)

    val legend: LegendMode by option(
        "--legend",
        help = "What the legend below an SVG diagram explains: the constructs it shows ('present', the " +
            "default), all of them ('full'), or nothing ('none').",
    )
        .enum<LegendMode> { it.name.lowercase() }
        .default(LegendMode.PRESENT)

    val sourceBaseUrl: String? by option(
        "--source-base-url",
        metavar = "url",
        help = "Link each card of an SVG diagram to <url>/<file>#L<line>. '{commit}' in the URL stands " +
            "for the commit the diagram was drawn from.",
    )

    val background: Background by option(
        "--background",
        help = "Background of the SVG diagrams: 'paper' (the default) or 'transparent'.",
    )
        .enum<Background> { it.name.lowercase() }
        .default(Background.PAPER)

    val embedFont: Boolean by option(
        "--embed-font",
        help = "Put the typeface into each SVG file, so that it looks the same everywhere. Adds over a megabyte per file.",
    )
        .flag()

    val svgTimeout: Double by option(
        "--svg-timeout",
        metavar = "seconds",
        help = "Give up arranging the SVG diagram of a workflow after this many seconds (by default " +
            "${SvgOptions.DEFAULT_TIME_LIMIT.seconds}) and draw it more simply: with each row ended where it is full, " +
            "then with regions as single nodes too, each try with the same time. A workflow that neither " +
            "of them draws gets no SVG diagram, and the exit status is 2. 0 sets no limit.",
    )
        .double()
        .restrictTo(min = 0.0)
        .default(SvgOptions.DEFAULT_TIME_LIMIT.toNanos() / NANOS)

    fun svgOptions(): SvgOptions = SvgOptions(
        wrapWidth = wrapWidth,
        legend = legend,
        sourceBaseUrl = sourceBaseUrl,
        background = background,
        embedFont = embedFont,
        // A limit shorter than the clock can tell is as short as a limit gets.
        timeLimit = if (svgTimeout == 0.0) null else Duration.ofNanos((svgTimeout * NANOS).roundToLong().coerceAtLeast(1)),
    )

    /**
     * The formats to write with their settings applied, the HTML pages among them if asked for.
     * They are made once for a command: a format keeps what it made of the workflow it drew last.
     */
    val configuredFormats: List<DiagramFormat> by lazy { DiagramFormats.configured(formats, svgOptions(), html) }

    /** @param progress told of each workflow as it is drawn */
    fun directoryOptions(progress: DrawingProgress = DrawingProgress.NONE): DirectoryOptions =
        DirectoryOptions(formats = configuredFormats, maxNodes = maxNodes, progress = progress)

    companion object {
        /** Stands for every format the tool can write. */
        const val ALL: String = "all"

        private const val NANOS = 1e9

        /** What `--format` accepts: the name of each registered format, and [ALL]. */
        fun choices(): List<String> = DiagramFormats.all.map { it.name } + ALL

        /** The formats [name] stands for; `null` if it names none. */
        fun select(name: String): List<DiagramFormat>? =
            if (name == ALL) DiagramFormats.all else DiagramFormats.named(name)?.let(::listOf)
    }
}
