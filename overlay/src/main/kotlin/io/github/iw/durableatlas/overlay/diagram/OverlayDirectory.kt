package io.github.iw.durableatlas.overlay.diagram

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrFormatException
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.withoutRequests
import io.github.iw.durableatlas.overlay.coverage.CoverageBuilder
import io.github.iw.durableatlas.overlay.coverage.CoverageJson
import io.github.iw.durableatlas.overlay.coverage.EventCounts
import io.github.iw.durableatlas.overlay.coverage.WorkflowCoverage
import io.github.iw.durableatlas.overlay.history.CloseStatus
import io.github.iw.durableatlas.overlay.history.ExecutionHistory
import io.github.iw.durableatlas.overlay.history.HistoryFormatException
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.history.WorkerVersion
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.version.AmbiguousCommitException
import io.github.iw.durableatlas.overlay.version.CommitChoice
import io.github.iw.durableatlas.overlay.version.CommitDirectories
import io.github.iw.durableatlas.overlay.version.VersionMap
import io.github.iw.durableatlas.render.DiagramFileNames
import io.github.iw.durableatlas.render.DiagramFiles
import io.github.iw.durableatlas.render.DiagramFormat
import io.github.iw.durableatlas.render.DiagramFormats
import io.github.iw.durableatlas.render.DiagramLink
import io.github.iw.durableatlas.render.DiagramOverlay
import io.github.iw.durableatlas.render.DrawingProgress
import io.github.iw.durableatlas.render.DrawingStep
import io.github.iw.durableatlas.render.DrawingWatch
import io.github.iw.durableatlas.render.Missing
import io.github.iw.durableatlas.render.MissingDiagram
import io.github.iw.durableatlas.render.PageFact
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.PreparedDiagram
import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.SimplifiedDiagram
import io.github.iw.durableatlas.render.svg.SvgFormat
import io.github.iw.durableatlas.render.svg.SvgHtmlFormat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * @param formats the formats to draw coverage and executions in, each to its own file; the index
 *   links them in this order
 * @param maxNodes see [RenderOptions.maxNodes]
 * @param fileNames how the files of a workflow are named; by default after its document
 * @param progress told of each workflow as its overlays are drawn
 * @param requests whether to draw what activities ask of other services, as the plain diagram
 *   does (see [io.github.iw.durableatlas.render.DirectoryOptions.requests])
 */
data class OverlayOptions(
    val formats: List<DiagramFormat> = DiagramFormats.all,
    val maxNodes: Int = RenderOptions.DEFAULT_MAX_NODES,
    val fileNames: DiagramFileNames = DiagramFileNames.SAME_AS_DOCUMENT,
    val progress: DrawingProgress = DrawingProgress.NONE,
    val requests: Boolean = true,
) {
    init {
        require(formats.isNotEmpty()) { "At least one format is needed" }
    }
}

/** What [OverlayDirectory.write] wrote. File names are relative to [outDir]. */
class OverlaySummary(
    val outDir: Path,
    /** File name of the index page. */
    val index: String,
    /**
     * The workflow types that have histories, ordered by workflow type; with a version map,
     * those of every commit, ordered by commit first.
     */
    val workflows: List<OverlaidWorkflow>,
    /** Histories that were laid over nothing, by file name. */
    val unmatched: List<UnmatchedHistory>,
    /** JSON files next to the histories that are no histories, by file name. */
    val skipped: List<String>,
    /** With a version map: the commits histories were laid over, ordered by name. Empty without one. */
    val commits: List<OverlaidCommit> = emptyList(),
)

/**
 * The histories a version map sends to one commit.
 *
 * @param commit the commit, as the directory its documents were read from is called; a version
 *   map may write less of it
 * @param directory the directory, inside the output directory, that holds everything written
 *   for the commit; the documents of the commit were read from the directory of the same name
 * @param index the index page of the commit
 * @param versions the worker versions that sent histories here, in the order of their names
 * @param unversioned how many of the histories name no worker version
 */
class OverlaidCommit(
    val commit: String,
    val directory: String,
    val index: String,
    val versions: List<WorkerVersion>,
    val unversioned: Int,
    val workflows: List<OverlaidWorkflow>,
)

class OverlaidWorkflow(
    val workflowType: String,
    val implementation: String,
    /** File name of the document inside the input directory. */
    val irFile: String,
    val coverage: WorkflowCoverage,
    /** The coverage as JSON. */
    val coverageFile: String,
    /** The plain SVG diagram that the SVG overlays are arranged as; `null` if no SVG is drawn. */
    val diagramFile: String?,
    /** The coverage diagram, one file per format it was drawn in. */
    val files: List<DiagramLink>,
    /** The executions, ordered by the name of their history. */
    val executions: List<OverlaidExecution>,
    /** With a version map: the commit whose document the histories were laid over. */
    val commit: String? = null,
    /**
     * How many calls that did not resolve the document left out as calls into libraries. A step
     * of an execution that has no node may lie behind one of them.
     */
    val skippedCalls: Int = 0,
    /**
     * How many calls the document has no node for because it is an overview. A step of an
     * execution that has no node may lie behind one of them as well.
     */
    val leftOutCalls: Int = 0,
    /**
     * The formats the workflow has no overlay in, because the diagram they are drawn on was not
     * arranged in the time the format allows.
     */
    val missing: List<MissingDiagram> = emptyList(),
    /** The formats whose files show the workflow more simply than was asked for, because drawing it as asked took too long. */
    val simplified: List<SimplifiedDiagram> = emptyList(),
) {
    /** The same, with the names of its files as seen from the parent of [directory], for [commit]. */
    internal fun inside(directory: String, commit: String): OverlaidWorkflow =
        OverlaidWorkflow(
            workflowType = workflowType,
            implementation = implementation,
            irFile = "$directory/$irFile",
            coverage = coverage,
            coverageFile = "$directory/$coverageFile",
            diagramFile = diagramFile?.let { "$directory/$it" },
            files = files.map { DiagramLink(it.format, "$directory/${it.file}") },
            executions = executions.map { execution ->
                OverlaidExecution(
                    history = execution.history,
                    workflowId = execution.workflowId,
                    runId = execution.runId,
                    status = execution.status,
                    events = execution.events,
                    files = execution.files.map { DiagramLink(it.format, "$directory/${it.file}") },
                    workerVersions = execution.workerVersions,
                    workerVersion = execution.workerVersion,
                    workflowType = execution.workflowType,
                )
            },
            commit = commit,
            skippedCalls = skippedCalls,
            leftOutCalls = leftOutCalls,
            missing = missing,
            simplified = simplified,
        )
}

class OverlaidExecution(
    /** File name of the history. */
    val history: String,
    val workflowId: String?,
    val runId: String?,
    val status: CloseStatus,
    val events: EventCounts,
    /** The diagram of this execution, one file per format it was drawn in. */
    val files: List<DiagramLink>,
    /** See [ExecutionHistory.workerVersions]. */
    val workerVersions: List<WorkerVersion> = emptyList(),
    /** See [ExecutionHistory.workerVersion]. */
    val workerVersion: WorkerVersion? = null,
    /**
     * The workflow type the history names, where it is not that of the document it was laid
     * over: the type has no document of its own, and the document is that of the class that
     * runs for every such type.
     */
    val workflowType: String? = null,
)

/** A history that has no diagram, and why. */
class UnmatchedHistory(val file: String, val workflowType: String, val reason: String)

/** Lays the histories of a directory over the workflow documents of another, and writes what comes of it. */
object OverlayDirectory {
    /** Directory, inside the output directory, of the diagrams of single executions. */
    const val EXECUTIONS: String = "executions"

    /** File name of the index page. */
    const val INDEX: String = "index.md"

    private const val IR_SUFFIX = ".ir.json"
    private const val JSON_SUFFIX = ".json"

    /** How much of the name of a commit a line of progress shows. */
    private const val COMMIT_SHOWN = 7

    /**
     * Reads every workflow document directly inside [irDir] and every history in [histories] (a
     * file, or the JSON files directly inside a directory), lays each history over the document
     * of its workflow type and writes to [outDir], per workflow type that has a history:
     *
     * - `<name>.coverage.json`: hit counts of every node and edge, see [WorkflowCoverage]
     * - `<name>.coverage.<format>`: the diagram with hit counts, what was never run greyed
     * - `executions/<history>.<format>`: the diagram with the path of one execution
     * - `<name>.svg`, if SVG is among the formats: the plain diagram
     *
     * and one `index.md` that links them. `<name>` is the document's file name without
     * `.ir.json`, `<history>` the history's without `.json`.
     *
     * Every SVG overlay is the plain diagram redrawn where it stands, not laid out again. If
     * [outDir] already holds `<name>.svg` for the same document, that file is kept and decides
     * the arrangement, whatever settings it was drawn with; otherwise it is drawn first.
     *
     * A history whose workflow type has no document of its own is laid over the document of the
     * class that runs for every such type (see [IrDocument.isDynamic]), if [irDir] holds exactly
     * one: that is what a worker with that class would have run. A foreign history, of a
     * workflow type some other worker runs, cannot be told from it and is laid over that
     * document as well; the index names the workflow type of each such history. Without such a
     * document, or with several, the history is reported and left out, and so is one whose
     * workflow type has several documents.
     *
     * Nothing is written unless every document and history can be read and every document is
     * consistent. After that, the files of each workflow type are written as soon as they are
     * drawn, one type after the other in the order of the summary, and the index last; with
     * [versions], commit after commit, each with its own index. Every file is written whole or
     * not at all, so a run that is interrupted leaves the workflow types it got to, complete.
     *
     * The plain SVG diagram may take longer to arrange than its format allows. It is then drawn
     * in the first of the format's simpler ways that is done in time, and the overlays with it.
     * If none is, the workflow type has no SVG diagram and no overlay drawn on one: files of
     * those names from an earlier run are removed, the coverage as JSON and the overlays in
     * other formats are written and say what is missing, and so does the index
     * (see [OverlaidWorkflow.missing]).
     *
     * With [versions], every history is laid over the documents of the commit that ran it.
     * [irDir] then holds one directory of documents per commit, named after the commit, and
     * [outDir] gets a directory of the same name for each commit that has histories, filled as
     * described above, and an `index.md` that lists the commits. The commit of a history is the
     * one the map gives for [ExecutionHistory.workerVersion]. The map may write the start of a
     * commit only: its directory is then the one whose name begins with that, see
     * [CommitDirectories]. A history is reported and left out if the map has no entry for its
     * version, if it names no version and the map has no commit for such histories, or if the
     * commit has no directory in [irDir].
     *
     * @throws IrFormatException if a workflow document cannot be read
     * @throws HistoryFormatException if a history cannot be read
     * @throws AmbiguousCommitException if [versions] names a commit that several directories in
     *   [irDir] begin with
     * @throws IllegalArgumentException if a document is inconsistent
     */
    fun write(
        irDir: Path,
        histories: Path,
        outDir: Path,
        options: OverlayOptions = OverlayOptions(),
        versions: VersionMap? = null,
    ): OverlaySummary = write(irDir, histories, outDir, options, versions, DrawingWatch(options.progress))

    internal fun write(
        irDir: Path,
        histories: Path,
        outDir: Path,
        options: OverlayOptions,
        versions: VersionMap?,
        watch: DrawingWatch,
    ): OverlaySummary {
        val (read, skipped) = histories(histories)
        if (versions == null) {
            val matched = match(irDir, read, options)
            return lay(matched, outDir, options, skipped, watch, Count(matched.overlaid.size))
        }
        return layByCommit(irDir, read, outDir, options, versions, skipped, watch)
    }

    /** The workflow types that have histories, each with what is laid over it, and the histories that fit none. */
    private class Matched(val overlaid: List<Overlaid>, val unmatched: List<UnmatchedHistory>)

    /** Counts the workflow types of a run as they are drawn. */
    private class Count(val total: Int) {
        var drawn = 0
    }

    /** Reads the documents of [irDir] and finds the one each of [histories] is laid over. Nothing is written. */
    private fun match(irDir: Path, histories: List<ExecutionHistory>, options: OverlayOptions): Matched {
        val documents = documents(irDir).map { if (options.requests) it else Source(it.file, it.document.withoutRequests()) }
        val sources = documents.groupBy { it.document.workflowType }
        // What runs for every workflow type that has no document of its own.
        val dynamic = documents.filter { it.document.isDynamic }

        val unmatched = ArrayList<UnmatchedHistory>()
        val matched = LinkedHashMap<Source, MutableList<ExecutionHistory>>()
        for (history in histories) {
            val found = sources[history.workflowType].orEmpty()
            when (found.size) {
                1 -> matched.getOrPut(found.single()) { ArrayList() } += history
                0 -> when (dynamic.size) {
                    1 -> matched.getOrPut(dynamic.single()) { ArrayList() } += history
                    0 -> unmatched += UnmatchedHistory(history.source, history.workflowType, "no workflow document for this workflow type")
                    else -> unmatched += UnmatchedHistory(
                        history.source,
                        history.workflowType,
                        "no workflow document for this workflow type, and several for every type without one: " +
                            dynamic.joinToString(", ") { it.file },
                    )
                }
                else -> unmatched += UnmatchedHistory(
                    history.source,
                    history.workflowType,
                    "several workflow documents for this workflow type: ${found.joinToString(", ") { it.file }}",
                )
            }
        }

        val overlaid = matched.entries
            .map { (source, found) -> Overlaid(source, options.fileNames.nameFor(source.file, source.document), found) }
            .sortedWith(compareBy({ it.source.document.workflowType }, { it.name }))
        return Matched(overlaid, unmatched)
    }

    /**
     * Draws what [matched] holds and writes it to [outDir], one workflow type after the other,
     * and the index of them last.
     *
     * @param count the workflow types of the whole run, of which these are some
     * @param commit the commit these are laid over, where histories are sorted by commit
     */
    private fun lay(
        matched: Matched,
        outDir: Path,
        options: OverlayOptions,
        skipped: List<String>,
        watch: DrawingWatch,
        count: Count,
        commit: String? = null,
    ): OverlaySummary {
        val workflows = matched.overlaid.map { workflow ->
            val document = workflow.source.document
            val step = DrawingStep(
                number = ++count.drawn,
                count = count.total,
                // A commit is told from the others by the start of its name, which is as much as fits a line.
                name = commit?.let { "${it.take(COMMIT_SHOWN)}/${workflow.name}" } ?: workflow.name,
                workflowType = document.workflowType,
                nodes = document.nodes.size,
                executions = workflow.traces.size,
            )
            watch.draw(step) { attempt -> draw(workflow, matched.overlaid, outDir, options, attempt) }
        }
        val summary = OverlaySummary(outDir, INDEX, workflows, matched.unmatched.sortedBy { it.file }, skipped)
        DiagramFiles.write(outDir.resolve(INDEX), OverlayIndex.render(summary, options.formats.mapNotNull { it.indexSection() }))
        return summary
    }

    /** Sorts [histories] by the commit [versions] gives each, and lays those of every commit over its own documents. */
    private fun layByCommit(
        irDir: Path,
        histories: List<ExecutionHistory>,
        outDir: Path,
        options: OverlayOptions,
        versions: VersionMap,
        skipped: List<String>,
        watch: DrawingWatch,
    ): OverlaySummary {
        val directories = CommitDirectories.find(irDir, versions)
        val unmatched = ArrayList<UnmatchedHistory>()
        // By the name of the commit's directory, which is what two spellings of one commit share.
        val byCommit = sortedMapOf<String, MutableList<ExecutionHistory>>()
        for (history in histories) {
            fun leftOut(reason: String) {
                unmatched += UnmatchedHistory(history.source, history.workflowType, reason)
            }
            when (val choice = versions.commitOf(history.workerVersion)) {
                is CommitChoice.Commit -> {
                    val directory = directories.of(choice.commit)
                    if (directory != null) {
                        byCommit.getOrPut(directory) { ArrayList() } += history
                    } else {
                        val whose = history.workerVersion?.let { "worker version $it" } ?: "histories without a worker version"
                        leftOut("the version map sends $whose to commit ${choice.commit}, which has no directory of workflow documents")
                    }
                }
                is CommitChoice.UnknownVersion -> leftOut("worker version ${choice.version} is not in the version map")
                CommitChoice.NoVersion ->
                    leftOut("the history names no worker version, and the version map has no commit for such histories")
            }
        }

        // The documents of every commit are read before anything is drawn for one of them.
        val matched = byCommit.map { (commit, found) -> Triple(commit, found, match(irDir.resolve(commit), found, options)) }
        val count = Count(matched.sumOf { it.third.overlaid.size })
        val commits = matched.map { (commit, found, documents) ->
            val summary = lay(documents, outDir.resolve(commit), options, emptyList(), watch, count, commit)
            unmatched += summary.unmatched
            OverlaidCommit(
                commit = commit,
                directory = commit,
                index = "$commit/${summary.index}",
                versions = found.mapNotNull { it.workerVersion }.distinct().sortedBy { it.toString() },
                unversioned = found.count { it.workerVersion == null },
                workflows = summary.workflows.map { it.inside(commit, commit) },
            )
        }
        val summary = OverlaySummary(outDir, INDEX, commits.flatMap { it.workflows }, unmatched.sortedBy { it.file }, skipped, commits)
        DiagramFiles.write(outDir.resolve(INDEX), OverlayIndex.renderCommits(summary))
        return summary
    }

    private class Source(val file: String, val document: IrDocument)

    /** A workflow type with its histories, and what its files are called. */
    private class Overlaid(val source: Source, val name: String, histories: List<ExecutionHistory>) {
        val mapper = try {
            ExecutionMapper(source.document)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("${source.file}: ${e.message}", e)
        }
        val traces: List<ExecutionTrace> = histories.sortedBy { it.source }.map(mapper::map)
        val coverage: WorkflowCoverage = CoverageBuilder.build(mapper, traces)

        fun coverageFile(format: DiagramFormat) = "$name.coverage.${format.extension}"
    }

    /** Draws everything of one workflow type and writes it to [outDir]. */
    private fun draw(
        workflow: Overlaid,
        all: List<Overlaid>,
        outDir: Path,
        options: OverlayOptions,
        attempt: DrawingWatch.Attempt,
    ): OverlaidWorkflow {
        val document = workflow.mapper.document
        val render = RenderOptions(maxNodes = options.maxNodes)
        val coverageFile = "${workflow.name}.coverage.json"
        fun write(file: String, text: String) = DiagramFiles.write(outDir.resolve(file), text)
        write(coverageFile, CoverageJson.encode(workflow.coverage))

        // The plain SVG diagram, which every SVG overlay is redrawn from.
        val plain = options.formats.firstNotNullOfOrNull { it as? SvgFormat }
            ?: options.formats.firstNotNullOfOrNull { (it as? SvgHtmlFormat)?.let { page -> SvgFormat(page.options) } }
        val plainFile = "${workflow.name}.svg"
        val listed = options.formats.any { it is SvgFormat }
        val drawn: String? = plain?.let { format ->
            val page = PageOptions(
                render,
                all.groupBy({ it.source.document.workflowType }, { "${it.name}.svg" }),
                INDEX,
                all.groupBy({ it.source.document.interfaceName }, { "${it.name}.svg" }),
            )
            val kept = outDir.resolve(plainFile).takeIf { it.isRegularFile() }
                ?.let { Files.readAllBytes(it).toString(Charsets.UTF_8) }
                ?.takeIf { text -> arrangedFor(format, document, text, page) }
            kept ?: attempt.prepare(format, document, page)?.render()?.also { if (listed) write(plainFile, it) }
        }
        // Without the plain diagram there is nothing to draw the SVG overlays on.
        val missing = attempt.missing
        fun drawable(format: DiagramFormat) = drawn != null || (format !is SvgFormat && format !is SvgHtmlFormat)
        val formats = options.formats.filter(::drawable)
        fun noted(overlay: DiagramOverlay) = if (missing.isEmpty()) overlay else overlay.copy(notices = overlay.notices + missing.map(Missing::notice))
        fun executionFile(trace: ExecutionTrace, format: DiagramFormat) = "$EXECUTIONS/${label(trace)}.${format.extension}"

        fun prepared(format: DiagramFormat, page: PageOptions): PreparedDiagram = when (format) {
            is SvgFormat -> format.restyle(document, drawn!!, page)
            is SvgHtmlFormat -> format.restyle(document, drawn!!, page)
            else -> format.prepare(document, page)
        }

        val executions = workflow.traces.map { trace ->
            OverlaidExecution(
                history = trace.history.source,
                workflowId = trace.history.workflowId,
                runId = trace.history.runId,
                status = trace.status,
                events = CoverageBuilder.counts(trace),
                files = formats.map { DiagramLink(it.name, executionFile(trace, it)) },
                workerVersions = trace.history.workerVersions,
                workerVersion = trace.history.workerVersion,
                workflowType = trace.history.workflowType.takeIf { it != document.workflowType },
            )
        }
        val simplified = ArrayList<SimplifiedDiagram>()
        for (format in formats) {
            val coverage = prepared(
                format,
                PageOptions(
                    render,
                    all.groupBy({ it.source.document.workflowType }, { it.coverageFile(format) }),
                    INDEX,
                    all.groupBy({ it.source.document.interfaceName }, { it.coverageFile(format) }),
                ),
            )
            coverage.simplification?.let { simplified += SimplifiedDiagram(format.name, it) }
            write(
                workflow.coverageFile(format),
                coverage.render(noted(OverlayTexts.coverage(workflow.coverage, coverageFile, document.skippedCalls.size, document.leftOutCalls.size))),
            )
            val execution = prepared(
                format,
                PageOptions(
                    render,
                    all.groupBy({ it.source.document.workflowType }, { "../${it.coverageFile(format)}" }),
                    "../$INDEX",
                    all.groupBy({ it.source.document.interfaceName }, { "../${it.coverageFile(format)}" }),
                ),
            )
            for (trace in workflow.traces) {
                write(
                    executionFile(trace, format),
                    execution.render(noted(OverlayTexts.execution(trace, label(trace), document.skippedCalls.size, document.leftOutCalls.size))),
                )
            }
        }
        // What an earlier run drew of this workflow type, and this one could not, would pass for this run's.
        for (format in options.formats - formats.toSet()) {
            Files.deleteIfExists(outDir.resolve(workflow.coverageFile(format)))
            workflow.traces.forEach { Files.deleteIfExists(outDir.resolve(executionFile(it, format))) }
        }
        if (drawn == null && listed) Files.deleteIfExists(outDir.resolve(plainFile))
        return OverlaidWorkflow(
            workflowType = document.workflowType,
            implementation = document.implementation,
            irFile = workflow.source.file,
            coverage = workflow.coverage,
            coverageFile = coverageFile,
            diagramFile = plainFile.takeIf { drawn != null && listed },
            files = formats.map { DiagramLink(it.name, workflow.coverageFile(it)) },
            executions = executions,
            skippedCalls = document.skippedCalls.size,
            leftOutCalls = document.leftOutCalls.size,
            // A page that holds the diagram is missing with it.
            missing = (options.formats - formats.toSet()).map { format -> missing.first().copy(format = format.name) },
            simplified = simplified,
        )
    }

    /** Whether [text] is an SVG diagram of [document] that overlays can be drawn on. */
    private fun arrangedFor(format: SvgFormat, document: IrDocument, text: String, page: PageOptions): Boolean =
        try {
            format.restyle(document, text, page)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    /** What an execution is called: its history's file name without the extension. */
    private fun label(trace: ExecutionTrace): String = trace.history.source.removeSuffix(JSON_SUFFIX)

    private fun documents(irDir: Path): List<Source> {
        val sources = ArrayList<Source>()
        for (file in jsonFiles(irDir)) {
            val text = Files.readAllBytes(file).toString(Charsets.UTF_8)
            if (!file.name.endsWith(IR_SUFFIX) && !holds(text) { "workflowType" in it && "nodes" in it }) continue
            val document = try {
                IrJson.decode(text)
            } catch (e: IrFormatException) {
                throw IrFormatException("${file.name}: ${e.message}", e)
            }
            sources += Source(file.name, document)
        }
        return sources
    }

    private fun histories(path: Path): Pair<List<ExecutionHistory>, List<String>> {
        if (!path.isDirectory()) return listOf(HistoryJson.read(path)) to emptyList()
        val read = ArrayList<ExecutionHistory>()
        val skipped = ArrayList<String>()
        for (file in jsonFiles(path)) {
            val text = Files.readAllBytes(file).toString(Charsets.UTF_8)
            if (holds(text) { it["events"] is JsonArray }) read += HistoryJson.decode(text, file.name) else skipped += file.name
        }
        return read to skipped
    }

    private fun jsonFiles(directory: Path): List<Path> =
        Files.list(directory).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(JSON_SUFFIX) }.toList()
        }.sortedBy { it.name }

    /** Whether [text] is a JSON object that [looks] right: tells the files a directory is read for from others in it. */
    private fun holds(text: String, looks: (JsonObject) -> Boolean): Boolean {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return false
        }
        return root is JsonObject && looks(root)
    }
}

/** What overlays say in words, next to what they draw. */
internal object OverlayTexts {
    private const val LISTED = 5

    /**
     * What an event without a node may come from where the document left calls out: `null` if
     * it left none out or every event has its node.
     *
     * @param skipped how many calls that did not resolve the document has no node for
     */
    fun behindSkipped(unmapped: Int, skipped: Int): String? =
        when {
            unmapped == 0 || skipped == 0 -> null
            skipped == 1 -> "A step without a node may lie behind the 1 call that did not resolve and is not drawn."
            else -> "A step without a node may lie behind one of the $skipped calls that did not resolve and are not drawn."
        }

    /**
     * What an event without a node may come from where the document is an overview: `null` if it
     * left no call out or every event has its node.
     *
     * @param leftOut how many calls the document has no node for because it is an overview
     */
    fun behindLeftOut(unmapped: Int, leftOut: Int): String? =
        when {
            unmapped == 0 || leftOut == 0 -> null
            leftOut == 1 -> "A step without a node may lie behind the 1 call that this overview leaves out."
            else -> "A step without a node may lie behind one of the $leftOut calls that this overview leaves out."
        }

    /**
     * @param skipped how many calls that did not resolve the document has no node for
     * @param leftOut how many calls the document has no node for because it is an overview
     */
    fun coverage(coverage: WorkflowCoverage, coverageFile: String, skipped: Int = 0, leftOut: Int = 0): DiagramOverlay {
        val executions = if (coverage.executions == 1) "1 execution" else "${coverage.executions} executions"
        val uncertain = coverage.ambiguous.size
        return DiagramOverlay(
            decorator = CoverageDecorator(coverage),
            title = "${coverage.workflowType}: coverage",
            notices = listOfNotNull(
                "Hit counts from $executions. What is grey was never run.",
                "${events(coverage.unmapped.size)} of the histories ${have(coverage.unmapped.size)} no node in this diagram (see $coverageFile)."
                    .takeIf { coverage.unmapped.isNotEmpty() },
                behindSkipped(coverage.unmapped.size, skipped),
                behindLeftOut(coverage.unmapped.size, leftOut),
                "${matches(uncertain)} ${are(uncertain)} not certain, and what is in doubt was counted (see $coverageFile)."
                    .takeIf { uncertain > 0 },
                (
                    "${events(coverage.recognised.size)} of the histories ${say(coverage.recognised.size)} what was done to an execution from outside, " +
                        "which is no step of the workflow (see $coverageFile)."
                    ).takeIf { coverage.recognised.isNotEmpty() },
            ),
            facts = listOfNotNull(
                PageFact("Executions", coverage.executions.toString()),
                PageFact("Nodes hit", "${coverage.hitNodes} of ${coverage.recordedNodes}"),
                PageFact("Events mapped", coverage.events.mapped.toString()),
                PageFact("Unmapped events", coverage.events.unmapped.toString()),
                PageFact("Uncertain matches", uncertain.toString()),
                PageFact("Events from outside", coverage.events.recognised.toString()).takeIf { coverage.recognised.isNotEmpty() },
            ),
        )
    }


    /**
     * @param skipped how many calls that did not resolve the document has no node for
     * @param leftOut how many calls the document has no node for because it is an overview
     */
    fun execution(trace: ExecutionTrace, label: String, skipped: Int = 0, leftOut: Int = 0): DiagramOverlay {
        val unmapped = trace.unmapped.take(LISTED).map { event ->
            val what = listOfNotNull(event.eventType, event.name).joinToString(" ")
            val source = when (event.likelySources.size) {
                0 -> ""
                1 -> " It may lie behind the unknown call ${event.likelySources.single()}."
                else -> " It may lie behind one of the unknown calls ${event.likelySources.joinToString(", ")}."
            }
            "Event ${event.eventId}, $what, has no node in this diagram.$source"
        }
        val more = trace.unmapped.size - unmapped.size
        // What was done to the execution from outside is no step of it: it is said, and has no place in the diagram.
        val outside = trace.recognised.take(LISTED).map { "Event ${it.eventId}, ${it.eventType}, came from outside the workflow: it is no step of it." }
        return DiagramOverlay(
            decorator = ExecutionDecorator(trace),
            title = "${trace.history.workflowType}: ${trace.history.workflowId ?: label}",
            notices = listOf("The path of one execution. What is grey was not run.") + unmapped +
                listOfNotNull(
                    "${events(more)} more ${have(more)} no node.".takeIf { more > 0 },
                    behindSkipped(trace.unmapped.size, skipped),
                    behindLeftOut(trace.unmapped.size, leftOut),
                ) + outside,
            facts = listOfNotNull(
                PageFact("History", trace.history.source),
                trace.history.workflowId?.let { PageFact("Workflow id", it) },
                trace.history.runId?.let { PageFact("Run id", it) },
                trace.history.workerVersions.takeIf { it.isNotEmpty() }?.let { PageFact("Worker version", it.joinToString(", then ")) },
                PageFact("Status", trace.status.words),
                PageFact("Events mapped", trace.mapped.toString()),
                PageFact("Unmapped events", trace.unmapped.size.toString()),
                PageFact("Uncertain matches", trace.ambiguous.size.toString()),
                PageFact("Events from outside", trace.recognised.size.toString()).takeIf { trace.recognised.isNotEmpty() },
            ),
        )
    }

    private fun say(count: Int) = if (count == 1) "says" else "say"

    private fun events(count: Int) = if (count == 1) "1 event" else "$count events"

    private fun matches(count: Int) = if (count == 1) "1 match" else "$count matches"

    private fun have(count: Int) = if (count == 1) "has" else "have"

    private fun are(count: Int) = if (count == 1) "is" else "are"
}
