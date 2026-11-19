package io.github.iw.durableatlas.cli

import io.github.iw.durableatlas.ir.IrStats
import io.github.iw.durableatlas.overlay.coverage.EventCounts
import java.util.Locale

/** How much of a workflow is unknown, and the limit `--max-unresolved-pct` puts on it. */
internal object Unresolved {
    /** The share of unknown nodes among all nodes of a workflow, in percent. */
    fun percentage(stats: IrStats): Double = if (stats.nodes <= 0) 0.0 else 100.0 * stats.unknown / stats.nodes

    /** True if more than [limit] percent of the nodes are unknown. Without a limit nothing is too much. */
    fun exceeds(stats: IrStats, limit: Double?): Boolean = limit != null && percentage(stats) > limit

    /** A sentence for the log about a workflow that is over the limit. */
    fun describe(workflow: String, stats: IrStats, limit: Double): String =
        "$workflow: ${stats.unknown} of ${stats.nodes} nodes are unknown (${percent(percentage(stats))}), " +
            "more than the ${percent(limit)} allowed"

    /** [value] with one decimal place, the same in every locale. */
    fun rounded(value: Double): Double = Math.round(value * 10) / 10.0

    fun percent(value: Double): String = String.format(Locale.ROOT, "%.1f %%", value)
}

/** How many events of a workflow's histories have no node, and the limit `--max-unmapped-pct` puts on it. */
internal object Unmapped {
    /** The share of events without a node among the events that are steps of the workflow, in percent. */
    fun percentage(events: EventCounts): Double = if (events.total <= 0) 0.0 else 100.0 * events.unmapped / events.total

    /** True if more than [limit] percent of the events have no node. Without a limit nothing is too much. */
    fun exceeds(events: EventCounts, limit: Double?): Boolean = limit != null && percentage(events) > limit

    /** A sentence for the log about a workflow that is over the limit. */
    fun describe(workflow: String, events: EventCounts, limit: Double): String =
        "$workflow: ${events.unmapped} of ${events.total} events have no node (${Unresolved.percent(percentage(events))}), " +
            "more than the ${Unresolved.percent(limit)} allowed"
}

/** The exit status of a command that did all of its work. */
internal object Completion {
    /**
     * A model that had to be made without the repository's build comes first: with it, calls into
     * libraries are unresolved, so a count of unknown nodes over the limit says little by itself.
     *
     * A diagram that was given up when its time ran out counts like a workflow over a limit the
     * caller set: everything else was written, and the workflow lacks a file that was asked for.
     *
     * @param degradedFallback the model is a degraded one although no mode was asked for
     * @param overThreshold at least one workflow has more unknown nodes, or more events without a node, than allowed
     * @param notDrawn at least one workflow has no diagram in a format that was asked for
     */
    fun status(degradedFallback: Boolean, overThreshold: Boolean, notDrawn: Boolean = false): Int =
        when {
            degradedFallback -> ExitCode.DEGRADED
            overThreshold || notDrawn -> ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED
            else -> ExitCode.SUCCESS
        }
}
