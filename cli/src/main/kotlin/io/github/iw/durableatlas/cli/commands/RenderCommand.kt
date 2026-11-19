package io.github.iw.durableatlas.cli.commands

import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import io.github.iw.durableatlas.cli.Completion
import io.github.iw.durableatlas.cli.ProgressLines
import io.github.iw.durableatlas.cli.StageCommand
import io.github.iw.durableatlas.cli.Stages
import io.github.iw.durableatlas.cli.Summaries
import io.github.iw.durableatlas.render.RenderSummary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

class RenderCommand : StageCommand(
    name = "render",
    summary = "Turn IR files into one diagram per workflow type and format, plus an index page.",
) {
    private val ir: Path by option("--ir", metavar = "ir-dir", help = "Directory of IR files written by 'analyse'.")
        .path(mustExist = true, canBeFile = false)
        .required()
    private val out: Path by option("--out", metavar = "dir", help = "Directory to write the diagrams to.")
        .path(canBeFile = false)
        .required()
    private val diagrams by DiagramOptions()

    override fun run() {
        val summary = attempt { Stages(::log).render(ir, Summaries.shown(out), diagrams.directoryOptions(ProgressLines(::log))) }

        if (json) echo(Summaries.format(summary(summary))) else echo(describe(summary))
        complete(Completion.status(degradedFallback = false, overThreshold = false, notDrawn = summary.diagrams.any { it.missing.isNotEmpty() }))
    }

    private fun summary(summary: RenderSummary): JsonObject =
        buildJsonObject {
            put("command", commandName)
            put("directory", summary.outDir.toString())
            put("index", summary.outDir.resolve(summary.index).toString())
            put("formats", buildJsonArray { diagrams.formats.forEach { add(it.name) } })
            put(
                "diagrams",
                buildJsonArray {
                    summary.diagrams.forEach { diagram ->
                        add(
                            buildJsonObject {
                                put("workflowType", diagram.workflowType)
                                put("implementation", diagram.implementation)
                                put("ir", diagram.irFile)
                                put("nodes", diagram.nodes)
                                put("unknown", diagram.unknown)
                                // Only a workflow whose document left calls out says how many.
                                if (diagram.skippedCalls > 0) put("skipped", diagram.skippedCalls)
                                put("collapsedRegions", diagram.collapsedRegions)
                                put("files", Summaries.files(summary, diagram))
                                Summaries.shortfall(this, diagram.missing, diagram.simplified)
                            },
                        )
                    }
                },
            )
            put("skipped", buildJsonArray { summary.skipped.forEach { add(it) } })
        }

    private fun describe(summary: RenderSummary): String =
        buildString {
            val names = Summaries.workflowNames(summary.diagrams.map { Summaries.Listed(it.workflowType, it.implementation, it.dynamic) })
            val shortfalls = summary.diagrams.mapIndexed { index, diagram -> Summaries.Shortfall(names[index], diagram.missing, diagram.simplified) }
            append("${summary.diagrams.size} workflow(s) drawn as ${Summaries.formatsDrawn(diagrams.formats.map { it.name }, shortfalls)}\n")
            Summaries.shortfallLines(shortfalls).forEach { append(it).append('\n') }
            append(summary.outDir.resolve(summary.index))
        }
}
