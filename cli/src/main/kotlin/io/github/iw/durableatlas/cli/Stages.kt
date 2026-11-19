package io.github.iw.durableatlas.cli

import io.github.iw.durableatlas.analyser.AnalysisRequest
import io.github.iw.durableatlas.analyser.AnalysisResult
import io.github.iw.durableatlas.analyser.Analyser
import io.github.iw.durableatlas.analyser.code.SourceAnalysisException
import io.github.iw.durableatlas.analyser.graph.IrFiles
import io.github.iw.durableatlas.ir.IrFormatException
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.modelextractor.ModelExtractionException
import io.github.iw.durableatlas.modelextractor.ModelExtractor
import io.github.iw.durableatlas.modelextractor.ModelFormatException
import io.github.iw.durableatlas.modelextractor.ModelRequest
import io.github.iw.durableatlas.modelextractor.ModelResult
import io.github.iw.durableatlas.overlay.diagram.OverlayDirectory
import io.github.iw.durableatlas.overlay.diagram.OverlayOptions
import io.github.iw.durableatlas.overlay.diagram.OverlaySummary
import io.github.iw.durableatlas.overlay.fetch.ConnectionFlags
import io.github.iw.durableatlas.overlay.fetch.ConnectionSettings
import io.github.iw.durableatlas.overlay.fetch.ConnectionSettingsException
import io.github.iw.durableatlas.overlay.fetch.FetchException
import io.github.iw.durableatlas.overlay.fetch.FetchRequest
import io.github.iw.durableatlas.overlay.fetch.FetchSummary
import io.github.iw.durableatlas.overlay.fetch.HistoryFetcher
import io.github.iw.durableatlas.overlay.history.HistoryFormatException
import io.github.iw.durableatlas.overlay.version.AmbiguousCommitException
import io.github.iw.durableatlas.overlay.version.VersionMap
import io.github.iw.durableatlas.overlay.version.VersionMapFormatException
import io.github.iw.durableatlas.overlay.version.VersionMapJson
import io.github.iw.durableatlas.render.DiagramDirectory
import io.github.iw.durableatlas.render.DirectoryOptions
import io.github.iw.durableatlas.render.RenderSummary
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** A stage could not do its work. The message says why, in words fit to show. */
internal class StageFailure(message: String?) : Exception(message ?: "unknown error")

/**
 * The stages of the pipeline, as the commands run them: each stage alone, and `run` the static
 * ones in turn. What a stage is known to fail with becomes a [StageFailure]; anything else is a
 * defect and is left to reach the top.
 */
internal class Stages(private val log: (String) -> Unit) {
    fun model(request: ModelRequest): ModelResult =
        try {
            ModelExtractor(log).extract(request)
        } catch (e: ModelExtractionException) {
            throw StageFailure(e.message)
        }

    /**
     * Writes the inventory and the IR files. If the IR of some workflow types could not be made,
     * the others are written all the same and the stage still fails, naming each of them.
     */
    fun analyse(request: AnalysisRequest): AnalysisResult {
        val result = try {
            Analyser(log).analyse(request)
        } catch (e: ModelFormatException) {
            throw StageFailure(e.message)
        } catch (e: SourceAnalysisException) {
            throw StageFailure(e.message)
        } catch (e: IOException) {
            throw StageFailure("the inventory and the IR files could not be written: $e")
        }
        if (result.inventory.modelMode == ModelMode.DEGRADED) {
            log(
                "the model is a degraded one: it holds the main Kotlin and Java sources of the commit; " +
                    "what they take from libraries other than the Temporal SDK and its Kotlin extensions is unresolved",
            )
        }
        val skipped = result.workflows.map { it.document.skippedCalls }
        if (skipped.any { it.isNotEmpty() }) {
            log(
                "${skipped.sumOf { it.size }} call(s) that did not resolve were taken for calls into libraries the model lacks and are not drawn: " +
                    "${skipped.flatten().distinct().size} place(s) in the sources, met in ${skipped.count { it.isNotEmpty() }} of ${skipped.size} workflow(s). " +
                    "Each IR file lists its own under skippedCalls",
            )
        }
        if (result.failures.isNotEmpty()) {
            result.failures.dropLast(1).forEach { log("failed: $it") }
            throw StageFailure(result.failures.last())
        }
        return result
    }

    fun render(ir: Path, out: Path, options: DirectoryOptions): RenderSummary {
        val summary = try {
            DiagramDirectory.render(ir, out, options)
        } catch (e: IrFormatException) {
            throw StageFailure(e.message)
        } catch (e: IllegalArgumentException) {
            // A document that contradicts itself, or two documents that would share a file.
            throw StageFailure(e.message)
        } catch (e: IOException) {
            throw StageFailure("the diagrams could not be written: $e")
        }
        if (summary.diagrams.isEmpty()) log("warning: there is no workflow IR in $ir; the index is empty")
        val names = Summaries.workflowNames(summary.diagrams.map { Summaries.Listed(it.workflowType, it.implementation, it.dynamic) })
        val shortfalls = summary.diagrams.mapIndexed { index, diagram -> Summaries.Shortfall(names[index], diagram.missing, diagram.simplified) }
        log("wrote ${summary.diagrams.size} diagram(s) as ${Summaries.formatsDrawn(options.formats.map { it.name }, shortfalls)} to ${summary.outDir}")
        // After the lines of progress, once more and together: what the time allowed for a diagram was not enough for.
        Summaries.shortfallLines(shortfalls).forEach { log("warning: $it") }
        return summary
    }

    /**
     * Draws the diagrams as [render] does, into a directory that is kept for the documents of
     * [ir] alone. Files directly inside it that were not drawn now are removed, so that the page
     * of a workflow an earlier run found, and this one did not, does not stay behind. Directories
     * inside it are left alone.
     */
    fun renderAlone(ir: Path, out: Path, options: DirectoryOptions): RenderSummary {
        val summary = render(ir, out, options)
        // File names are compared without regard to case: where the file system does not tell
        // upper from lower case, a file that was replaced keeps the spelling it had.
        val drawn = (summary.diagrams.flatMap { diagram -> diagram.files.map { it.file } } + summary.index).map { it.lowercase() }.toSet()
        try {
            summary.outDir.listDirectoryEntries()
                .filter { it.isRegularFile() && it.name.lowercase() !in drawn }
                .sorted()
                .forEach { stale ->
                    Files.delete(stale)
                    log("removed ${stale.name}, which is not part of this run")
                }
        } catch (e: IOException) {
            throw StageFailure("what an earlier run left in ${summary.outDir} could not be removed: $e")
        }
        return summary
    }

    /**
     * Lays histories over workflow documents and writes coverage and diagrams. A history that
     * fits no document is logged and left out; that is no failure.
     */
    fun overlay(ir: Path, history: Path, out: Path, options: OverlayOptions, versionMap: Path? = null): OverlaySummary {
        val versions: VersionMap? = try {
            versionMap?.let(VersionMapJson::read)
        } catch (e: VersionMapFormatException) {
            throw StageFailure(e.message)
        }
        val summary = try {
            OverlayDirectory.write(ir, history, out, options, versions)
        } catch (e: IrFormatException) {
            throw StageFailure(e.message)
        } catch (e: HistoryFormatException) {
            throw StageFailure(e.message)
        } catch (e: AmbiguousCommitException) {
            // The version map abbreviates a commit to what several directories of IR files begin with.
            throw StageFailure(e.message)
        } catch (e: IllegalArgumentException) {
            // A document that contradicts itself.
            throw StageFailure(e.message)
        } catch (e: IOException) {
            throw StageFailure("the overlay could not be written: $e")
        }
        summary.skipped.forEach { log("warning: $it is not an event history; skipped") }
        summary.unmatched.forEach { log("warning: ${it.file} is left out: workflow type ${it.workflowType}: ${it.reason}") }
        if (summary.workflows.isEmpty()) {
            log("warning: no history in $history belongs to a workflow in $ir; the index is empty")
            if (versions == null && holdsCommitDirectories(ir)) {
                log("warning: $ir holds a directory of IR files per commit, as 'run' writes them: give --ir one of them, or give --version-map")
            }
        }
        for (workflow in summary.workflows) {
            val events = workflow.coverage.events
            val where = workflow.commit?.let { " (commit $it)" }.orEmpty()
            if (events.unmapped > 0) log("${workflow.workflowType}$where: ${events.unmapped} of ${events.total} events have no node")
            if (events.recognised > 0) {
                log("${workflow.workflowType}$where: ${events.recognised} event(s) say what was done to an execution from outside; they are no step of the workflow")
            }
        }
        for (commit in summary.commits) {
            val ranBy = (commit.versions.map { it.toString() } + listOfNotNull("no version".takeIf { commit.unversioned > 0 })).joinToString(", ")
            log("commit ${commit.commit}: ${commit.workflows.sumOf { it.executions.size }} execution(s), run by $ranBy")
        }
        log(
            "wrote the coverage of ${summary.workflows.size} workflow type(s) from " +
                "${summary.workflows.sumOf { it.executions.size }} execution(s) to ${summary.outDir}",
        )
        Summaries.shortfallLines(
            summary.workflows.map { Summaries.Shortfall(it.workflowType + it.commit?.let { commit -> " (commit $commit)" }.orEmpty(), it.missing, it.simplified) },
        ).forEach { log("warning: $it") }
        return summary
    }

    /**
     * Downloads histories from a Temporal server. Finding none is no failure, and neither is an
     * execution whose history is gone by the time it is asked for.
     */
    fun fetch(request: FetchRequest, flags: ConnectionFlags, environment: Map<String, String>, connectTimeout: Duration): FetchSummary {
        val summary = try {
            HistoryFetcher(log, connectTimeout).fetch(request, ConnectionSettings.resolve(flags, environment))
        } catch (e: ConnectionSettingsException) {
            throw StageFailure(e.message)
        } catch (e: FetchException) {
            throw StageFailure(e.message)
        }
        if (summary.histories.isEmpty()) {
            log("warning: no execution of workflow type ${request.workflowType} fits; nothing was written")
        } else {
            log("wrote ${summary.histories.size} history file(s) to ${summary.outDir}")
        }
        return summary
    }

    /** Whether [ir] holds no IR files of its own but directories that do, one per commit. */
    private fun holdsCommitDirectories(ir: Path): Boolean {
        fun hasIr(directory: Path): Boolean =
            Files.list(directory).use { entries -> entries.anyMatch { it.isRegularFile() && IrFiles.isIrFile(it.name) } }
        if (!Files.isDirectory(ir) || hasIr(ir)) return false
        return ir.listDirectoryEntries().any { Files.isDirectory(it) && hasIr(it) }
    }
}
