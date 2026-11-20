package io.github.iw.durableatlas.cli.commands

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.analyser.requests.ClientSpec
import io.github.iw.durableatlas.ir.Detail

/** Threshold option shared by the commands that analyse workflows. */
internal fun ParameterHolder.maxUnresolvedPctOption(): NullableOption<Double, Double> =
    option(
        "--max-unresolved-pct",
        metavar = "n",
        help = "Exit with status 2 if more than this percentage of a workflow's nodes are unknown.",
    )
        .double()
        .restrictTo(0.0..100.0)

/** Detail option shared by the commands that analyse workflows. */
internal fun ParameterHolder.detailOption(): OptionWithValues<Detail, Detail, Detail> =
    option(
        "--detail",
        help = "How much of what the analysis could not read to show. 'overview' (the default) has no unknown node for a " +
            "call behind which a step is only possible: code handed to a call that is not modelled is drawn as run once " +
            "where the call is made, a call into code that is not in the sources is left out, and each IR file lists " +
            "what was left out. 'full' gives every such call its unknown node.",
    )
        .enum<Detail> { it.name.lowercase() }
        .default(Detail.OVERVIEW)

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

/** Client option shared by the commands that analyse workflows. */
internal fun ParameterHolder.clientOption(): OptionWithValues<List<ClientSpec>, ClientSpec, ClientSpec> =
    option(
        "--client",
        metavar = "spec",
        help = "A client of the repository's own whose calls are requests of the activities that make them, written " +
            "${ClientSpec.FORM}, for example 'ServiceClient.invoke:functionName': a call of 'invoke' on a ServiceClient, " +
            "which asks for what its argument 'functionName' says. Without a type, a call of the function on any object; " +
            "a number after ':' names an argument by its place. Repeat the option for several clients. HTTP requests sent " +
            "with java.net.http are read without it.",
    )
        .convert { text ->
            try {
                ClientSpec.parse(text)
            } catch (e: IllegalArgumentException) {
                fail(e.message ?: "'$text' does not name a client")
            }
        }
        .multiple()
