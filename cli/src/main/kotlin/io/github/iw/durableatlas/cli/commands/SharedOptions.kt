package io.github.iw.durableatlas.cli.commands

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs

/** Threshold option shared by the commands that analyse workflows. */
internal fun ParameterHolder.maxUnresolvedPctOption(): NullableOption<Double, Double> =
    option(
        "--max-unresolved-pct",
        metavar = "n",
        help = "Exit with status 2 if more than this percentage of a workflow's nodes are unknown.",
    )
        .double()
        .restrictTo(0.0..100.0)

/** Depth option shared by the commands that analyse workflows. */
internal fun ParameterHolder.maxDepthOption(): OptionWithValues<Int, Int, Int> =
    option(
        "--max-depth",
        metavar = "n",
        help = "How many calls deep to follow the functions a workflow calls (by default " +
            "${WorkflowGraphs.DEFAULT_MAX_DEPTH}). A step that lies deeper shows as an unknown node at the call.",
    )
        .int()
        .restrictTo(min = 0)
        .default(WorkflowGraphs.DEFAULT_MAX_DEPTH)
