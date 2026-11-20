package io.github.iw.durableatlas.cli.commands

import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import io.github.iw.durableatlas.analyser.AnalysisRequest
import io.github.iw.durableatlas.cli.Completion
import io.github.iw.durableatlas.cli.ProgressLines
import io.github.iw.durableatlas.cli.StageCommand
import io.github.iw.durableatlas.cli.Stages
import io.github.iw.durableatlas.cli.Summaries
import io.github.iw.durableatlas.cli.Unresolved
import io.github.iw.durableatlas.ir.Detail
import io.github.iw.durableatlas.ir.IrStats
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.modelextractor.ModelRequest
import io.github.iw.durableatlas.modelextractor.ModelResult
import io.github.iw.durableatlas.render.RenderSummary
import io.github.iw.durableatlas.render.RenderedDiagram
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * The static pipeline in one go. Everything goes below `--out`, each kind of output in a
 * directory named after the full SHA of the commit: the model to `model/<commit>`, the inventory
 * and the IR files to `ir/<commit>`, the diagrams and their index to `diagrams/<commit>`.
 *
 * Runs on several commits can therefore share one `--out`, and its `ir` directory is what
 * `overlay --version-map` reads. A run on a commit that was run before replaces what was written
 * for that commit: IR files and diagrams of the earlier run that this one does not write again
 * are removed.
 */
class RunCommand : StageCommand(
    name = "run",
    summary = "Run 'model', 'analyse' and 'render' in turn and print a summary of the diagrams written.",
) {
    private val repo: Path by option("--repo", metavar = "path", help = "Repository to read. It is never modified.")
        .path(mustExist = true, canBeFile = false)
        .required()
    private val commit: String by option("--commit", metavar = "sha", help = "Commit to diagram.").required()
    private val out: Path by option(
        "--out",
        metavar = "dir",
        help = "Directory to write all outputs to: the model to '$MODEL_DIRECTORY/<commit>', the IR files to " +
            "'$IR_DIRECTORY/<commit>' and the diagrams to '$DIAGRAMS_DIRECTORY/<commit>' inside it, where <commit> is " +
            "the full SHA of the commit. Runs on other commits can use the same directory.",
    )
        .path(canBeFile = false)
        .required()
    private val mode: ModelMode? by option(
        "--mode",
        help = "How to obtain the model. By default the repository's Gradle build is tried first, and a " +
            "degraded model is built (exit status 3) if that fails.",
    )
        .enum<ModelMode> { it.name.lowercase() }
    private val force: Boolean by option("--force", help = "Extract the model again even if one for the commit exists.")
        .flag()
    private val jdkHome: Path? by option(
        "--jdk-home",
        metavar = "dir",
        help = "Home of the JDK whose classes the sources are resolved against. By default, the JDK that runs the tool.",
    )
        .path(mustExist = true, canBeFile = false)
    private val maxDepth: Int by maxDepthOption()
    private val detail: Detail by detailOption()
    private val maxUnresolvedPct: Double? by maxUnresolvedPctOption()
    private val diagrams by DiagramOptions()

    override fun run() {
        val stages = Stages(::log)
        val root = Summaries.shown(out)
        val model = attempt {
            stages.model(
                ModelRequest(repository = repo, commit = commit, out = root.resolve(MODEL_DIRECTORY), mode = mode, force = force),
            )
        }
        // The model knows the commit by its full SHA, however --commit named it.
        val irDirectory = root.resolve(IR_DIRECTORY).resolve(model.commit)
        attempt { stages.analyse(AnalysisRequest(model = model.directory, out = irDirectory, jdkHome = jdkHome, maxDepth = maxDepth, detail = detail)) }
        val diagramsDirectory = root.resolve(DIAGRAMS_DIRECTORY).resolve(model.commit)
        val rendered = attempt { stages.renderAlone(irDirectory, diagramsDirectory, diagrams.directoryOptions(ProgressLines(::log))) }

        val limit = maxUnresolvedPct
        val overThreshold = rendered.diagrams.filter { Unresolved.exceeds(it.stats(), limit) }
        if (limit != null) overThreshold.forEach { log(Unresolved.describe(it.workflowType, it.stats(), limit)) }
        // Without an explicit mode the caller expected the Gradle model, so a degraded one is flagged.
        val degradedFallback = mode == null && model.mode == ModelMode.DEGRADED
        if (degradedFallback) log("completed in degraded mode")
        val status = Completion.status(degradedFallback, overThreshold.isNotEmpty(), notDrawn = rendered.diagrams.any { it.missing.isNotEmpty() })

        if (json) {
            echo(Summaries.format(summary(model, irDirectory, rendered, overThreshold, status)))
        } else {
            echo(describe(model, rendered))
        }
        complete(status)
    }

    private fun RenderedDiagram.stats(): IrStats = IrStats(nodes = nodes, unknown = unknown)

    private fun summary(
        model: ModelResult,
        irDirectory: Path,
        rendered: RenderSummary,
        overThreshold: List<RenderedDiagram>,
        status: Int,
    ): JsonObject =
        buildJsonObject {
            put("command", commandName)
            put("commit", model.commit)
            put("mode", model.mode.name.lowercase())
            // Only an overview says what it is, as its IR files do.
            if (detail == Detail.OVERVIEW) put("detail", detail.name.lowercase())
            put("cached", model.cached)
            put("fallback", model.fallback)
            put("model", model.directory.toString())
            put("ir", irDirectory.toString())
            put("diagrams", rendered.outDir.toString())
            put("index", rendered.outDir.resolve(rendered.index).toString())
            put("formats", buildJsonArray { diagrams.formats.forEach { add(it.name) } })
            maxUnresolvedPct?.let { put("maxUnresolvedPct", it) }
            put(
                "workflows",
                buildJsonArray {
                    rendered.diagrams.forEach { diagram ->
                        add(
                            buildJsonObject {
                                put("workflowType", diagram.workflowType)
                                put("implementation", diagram.implementation)
                                put("nodes", diagram.nodes)
                                put("unknown", diagram.unknown)
                                put("unknownPct", Unresolved.rounded(Unresolved.percentage(diagram.stats())))
                                // Only a workflow whose document left calls out says how many.
                                if (diagram.skippedCalls > 0) put("skipped", diagram.skippedCalls)
                                if (diagram.leftOutCalls > 0) put("leftOut", diagram.leftOutCalls)
                                put("overThreshold", diagram in overThreshold)
                                put("ir", irDirectory.resolve(diagram.irFile).toString())
                                put("files", Summaries.files(rendered, diagram))
                                Summaries.shortfall(this, diagram.missing, diagram.simplified)
                            },
                        )
                    }
                },
            )
            put("exitCode", status)
        }

    /** The table of what was drawn, then a sentence about where it came from and the page that lists it all. */
    private fun describe(model: ModelResult, rendered: RenderSummary): String =
        buildString {
            val names = Summaries.workflowNames(rendered.diagrams.map { Summaries.Listed(it.workflowType, it.implementation, it.dynamic) })
            // Each of the two columns is there only where calls were left out: as calls into libraries, which a
            // degraded model alone does, or as calls an overview has no node for.
            val skipping = rendered.diagrams.any { it.skippedCalls > 0 }
            val leaving = rendered.diagrams.any { it.leftOutCalls > 0 }
            val rows = rendered.diagrams.mapIndexed { index, diagram ->
                listOf(names[index], diagram.nodes.toString(), diagram.unknown.toString()) +
                    listOf(diagram.skippedCalls.toString()).takeIf { skipping }.orEmpty() +
                    listOf(diagram.leftOutCalls.toString()).takeIf { leaving }.orEmpty() +
                    diagram.files.joinToString(", ") { rendered.outDir.resolve(it.file).toString() }
            }
            val counted = listOf(SKIPPED_COLUMN).takeIf { skipping }.orEmpty() + listOf(LEFT_OUT_COLUMN).takeIf { leaving }.orEmpty()
            val header = TABLE_HEADER.dropLast(1) + counted + TABLE_HEADER.last()
            append(Summaries.table(header, rows, rightAligned = (1..2 + counted.size).toSet())).append('\n')
            append("${rendered.diagrams.size} workflow(s) drawn from the ${model.mode.name.lowercase()} model of ${model.commit}\n")
            if (skipping) {
                append("${rendered.diagrams.sumOf { it.skippedCalls }} unresolved call(s) taken for calls into libraries and not drawn, listed on the page of each workflow\n")
            }
            if (leaving) append("${Summaries.leftOut(rendered.diagrams.sumOf { it.leftOutCalls })}, listed on the page of each workflow\n")
            Summaries.shortfallLines(rendered.diagrams.mapIndexed { index, diagram -> Summaries.Shortfall(names[index], diagram.missing, diagram.simplified) })
                .forEach { append(it).append('\n') }
            append(rendered.outDir.resolve(rendered.index))
        }

    companion object {
        /** Where the outputs go inside `--out`: each in a directory of its own named after the commit, inside one of these. */
        const val MODEL_DIRECTORY: String = "model"
        const val IR_DIRECTORY: String = "ir"
        const val DIAGRAMS_DIRECTORY: String = "diagrams"

        private val TABLE_HEADER = listOf("Workflow type", "Nodes", "Unknown", "File")
        private const val SKIPPED_COLUMN = "Skipped"
        private const val LEFT_OUT_COLUMN = "Left out"
    }
}
