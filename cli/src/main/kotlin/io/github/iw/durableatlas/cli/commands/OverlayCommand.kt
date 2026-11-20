package io.github.iw.durableatlas.cli.commands

import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.github.iw.durableatlas.cli.Completion
import io.github.iw.durableatlas.cli.ProgressLines
import io.github.iw.durableatlas.cli.StageCommand
import io.github.iw.durableatlas.cli.Stages
import io.github.iw.durableatlas.cli.Summaries
import io.github.iw.durableatlas.cli.Unmapped
import io.github.iw.durableatlas.cli.Unresolved
import io.github.iw.durableatlas.overlay.coverage.EventCounts
import io.github.iw.durableatlas.overlay.diagram.OverlayOptions
import io.github.iw.durableatlas.overlay.diagram.OverlaySummary
import io.github.iw.durableatlas.overlay.diagram.OverlaidWorkflow
import io.github.iw.durableatlas.overlay.history.WorkerVersion
import io.github.iw.durableatlas.render.DiagramLink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * Lays event histories over the IR of their workflow types.
 *
 * Events that have no node are a finding, not a failure: they are counted in the summary and
 * listed in the coverage files, and the command still exits with status 0. Only a limit set with
 * `--max-unmapped-pct` turns them into status 2. A history whose workflow type has no IR is
 * reported and left out, also with status 0. A workflow type whose SVG diagram could not be
 * arranged in the time allowed, however simply, has no SVG overlays, and the status is 2.
 */
class OverlayCommand : StageCommand(
    name = "overlay",
    summary = "Map workflow event histories onto IR files to show which paths executions took.",
) {
    private val ir: Path by option("--ir", metavar = "ir-dir", help = "Directory of IR files written by 'analyse'.")
        .path(mustExist = true, canBeFile = false)
        .required()
    private val history: Path by option(
        "--history",
        metavar = "file|dir",
        help = "A JSON event history as 'temporal workflow show --output json' prints it, or a directory of them.",
    )
        .path(mustExist = true)
        .required()
    private val out: Path by option(
        "--out",
        metavar = "dir",
        help = "Directory to write coverage and diagrams to. An SVG diagram of a workflow that is already " +
            "there is kept, and its overlays are arranged as it is.",
    )
        .path(canBeFile = false)
        .required()
    private val versionMap: Path? by option(
        "--version-map",
        metavar = "file",
        help = "JSON file that says which commit each worker version was built from. With it, --ir holds one " +
            "directory of IR files per commit, named after the commit, as 'run' writes them to 'ir' in its output " +
            "directory; every history is laid over the IR of the commit that ran it, and --out gets one directory " +
            "per commit. The file may abbreviate a commit's SHA to its first four or more characters.",
    )
        .path(mustExist = true, canBeDir = false)
    private val maxUnmappedPct: Double? by option(
        "--max-unmapped-pct",
        metavar = "n",
        help = "Exit with status 2 if more than this percentage of the events of a workflow's histories have no node.",
    )
        .double()
        .restrictTo(0.0..100.0)
    private val diagrams by DiagramOptions()

    override fun run() {
        val options = OverlayOptions(
            formats = diagrams.configuredFormats,
            maxNodes = diagrams.maxNodes,
            progress = ProgressLines(::log),
            requests = !diagrams.noRequests,
        )
        val summary = attempt { Stages(::log).overlay(ir, history, Summaries.shown(out), options, versionMap) }

        val limit = maxUnmappedPct
        val over = summary.workflows.filter { Unmapped.exceeds(it.coverage.events, limit) }
        if (limit != null) over.forEach { log(Unmapped.describe(named(it), it.coverage.events, limit)) }
        val status = Completion.status(degradedFallback = false, overThreshold = over.isNotEmpty(), notDrawn = summary.workflows.any { it.missing.isNotEmpty() })

        if (json) echo(Summaries.format(summary(summary, over, status))) else echo(describe(summary))
        complete(status)
    }

    private fun summary(summary: OverlaySummary, over: List<OverlaidWorkflow>, status: Int): JsonObject =
        buildJsonObject {
            put("command", commandName)
            put("directory", summary.outDir.toString())
            put("index", summary.outDir.resolve(summary.index).toString())
            put("formats", buildJsonArray { diagrams.configuredFormats.forEach { add(it.name) } })
            maxUnmappedPct?.let { put("maxUnmappedPct", it) }
            versionMap?.let { map ->
                put("versionMap", Summaries.shown(map).toString())
                put(
                    "commits",
                    buildJsonArray {
                        summary.commits.forEach { commit ->
                            add(
                                buildJsonObject {
                                    put("commit", commit.commit)
                                    put("directory", summary.outDir.resolve(commit.directory).toString())
                                    put("index", summary.outDir.resolve(commit.index).toString())
                                    put("workerVersions", buildJsonArray { commit.versions.forEach { add(version(it)) } })
                                    put("unversioned", commit.unversioned)
                                    put("workflows", commit.workflows.size)
                                    put("executions", commit.workflows.sumOf { it.executions.size })
                                },
                            )
                        }
                    },
                )
            }
            put(
                "workflows",
                buildJsonArray {
                    summary.workflows.forEach { workflow ->
                        val coverage = workflow.coverage
                        add(
                            buildJsonObject {
                                put("workflowType", workflow.workflowType)
                                workflow.commit?.let { put("commit", it) }
                                put("implementation", workflow.implementation)
                                put("ir", workflow.irFile)
                                put("executions", coverage.executions)
                                put("nodes", coverage.recordedNodes)
                                put("nodesHit", coverage.hitNodes)
                                put("events", events(coverage.events))
                                put("uncertain", coverage.ambiguous.size)
                                put("unmappedPct", Unresolved.rounded(Unmapped.percentage(coverage.events)))
                                put("overThreshold", workflow in over)
                                put("coverage", summary.outDir.resolve(workflow.coverageFile).toString())
                                workflow.diagramFile?.let { put("diagram", summary.outDir.resolve(it).toString()) }
                                put("files", files(summary, workflow.files))
                                Summaries.shortfall(this, workflow.missing, workflow.simplified)
                                put(
                                    "histories",
                                    buildJsonArray {
                                        workflow.executions.forEach { execution ->
                                            add(
                                                buildJsonObject {
                                                    put("history", execution.history)
                                                    execution.workflowId?.let { put("workflowId", it) }
                                                    execution.runId?.let { put("runId", it) }
                                                    execution.workerVersion?.let { put("workerVersion", version(it)) }
                                                    if (execution.workerVersions.size > 1 || execution.workerVersions.firstOrNull() != execution.workerVersion) {
                                                        put("workerVersions", buildJsonArray { execution.workerVersions.forEach { add(version(it)) } })
                                                    }
                                                    put("status", execution.status.text)
                                                    put("events", events(execution.events))
                                                    put("files", files(summary, execution.files))
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put(
                "unmatched",
                buildJsonArray {
                    summary.unmatched.forEach {
                        add(
                            buildJsonObject {
                                put("history", it.file)
                                put("workflowType", it.workflowType)
                                put("reason", it.reason)
                            },
                        )
                    }
                },
            )
            put("skipped", buildJsonArray { summary.skipped.forEach { add(it) } })
            put("exitCode", status)
        }

    /** A workflow type as the log names it: with its commit, where histories are sorted by commit. */
    private fun named(workflow: OverlaidWorkflow): String = workflow.workflowType + workflow.commit?.let { " (commit $it)" }.orEmpty()

    private fun version(version: WorkerVersion): JsonObject =
        buildJsonObject {
            version.deployment?.let { put("deployment", it) }
            put("buildId", version.buildId)
        }

    private fun events(events: EventCounts): JsonObject =
        buildJsonObject {
            put("mapped", events.mapped)
            put("ambiguous", events.ambiguous)
            put("unmapped", events.unmapped)
            // Events that are no step of the workflow; the key is left out where there are none.
            if (events.recognised > 0) put("recognised", events.recognised)
        }

    private fun files(summary: OverlaySummary, files: List<DiagramLink>): JsonArray =
        buildJsonArray {
            files.forEach { link ->
                add(
                    buildJsonObject {
                        put("format", link.format)
                        put("file", summary.outDir.resolve(link.file).toString())
                    },
                )
            }
        }

    private fun describe(summary: OverlaySummary): String =
        buildString {
            if (summary.workflows.isNotEmpty()) {
                val byCommit = versionMap != null
                val rows = summary.workflows.map { workflow ->
                    val coverage = workflow.coverage
                    listOfNotNull(
                        workflow.commit.takeIf { byCommit },
                        workflow.workflowType,
                        coverage.executions.toString(),
                        "${coverage.hitNodes}/${coverage.recordedNodes}",
                        coverage.events.mapped.toString(),
                        coverage.events.unmapped.toString(),
                        coverage.ambiguous.size.toString(),
                        summary.outDir.resolve(workflow.coverageFile).toString(),
                    )
                }
                val header = listOf("Workflow type", "Executions", "Nodes hit", "Mapped", "Unmapped", "Uncertain", "Coverage")
                append(
                    if (byCommit) {
                        Summaries.table(listOf("Commit") + header, rows, rightAligned = setOf(2, 3, 4, 5, 6))
                    } else {
                        Summaries.table(header, rows, rightAligned = setOf(1, 2, 3, 4, 5))
                    },
                )
                append('\n')
            }
            val executions = summary.workflows.sumOf { it.executions.size }
            if (versionMap != null) {
                append("$executions execution(s) laid over ${summary.commits.size} commit(s), drawn as ")
            } else {
                append("$executions execution(s) of ${summary.workflows.size} workflow type(s) drawn as ")
            }
            val shortfalls = summary.workflows.map { Summaries.Shortfall(named(it), it.missing, it.simplified) }
            append(Summaries.formatsDrawn(diagrams.configuredFormats.map { it.name }, shortfalls))
            append('\n')
            Summaries.shortfallLines(shortfalls).forEach { append(it).append('\n') }
            if (summary.unmatched.isNotEmpty()) {
                append("${summary.unmatched.size} history file(s) ${if (versionMap != null) "left out" else "without a workflow document"}: ")
                append(summary.unmatched.joinToString(", ") { it.file })
                append('\n')
            }
            append(summary.outDir.resolve(summary.index))
        }
}
