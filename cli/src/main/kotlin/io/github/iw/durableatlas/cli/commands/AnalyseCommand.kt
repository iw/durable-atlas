package io.github.iw.durableatlas.cli.commands

import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import io.github.iw.durableatlas.analyser.AnalysedWorkflow
import io.github.iw.durableatlas.analyser.AnalysisRequest
import io.github.iw.durableatlas.analyser.AnalysisResult
import io.github.iw.durableatlas.analyser.requests.ClientSpec
import io.github.iw.durableatlas.cli.Completion
import io.github.iw.durableatlas.cli.StageCommand
import io.github.iw.durableatlas.cli.Stages
import io.github.iw.durableatlas.cli.Summaries
import io.github.iw.durableatlas.cli.Unresolved
import io.github.iw.durableatlas.ir.Detail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

class AnalyseCommand : StageCommand(
    name = "analyse",
    summary = "Find the workflows described by a model; write an inventory of them, one IR file per workflow type, " +
        "and the list of activity types that no workflow calls.",
) {
    private val model: Path by option("--model", metavar = "dir", help = "Directory of manifests written by 'model'.")
        .path(mustExist = true, canBeFile = false)
        .required()
    private val out: Path by option("--out", metavar = "ir-dir", help = "Directory to write the inventory and the IR files to.")
        .path(canBeFile = false)
        .required()
    private val workflow: String? by option("--workflow", metavar = "type", help = "Write the IR of this workflow type only.")
    private val maxDepth: Int by maxDepthOption()
    private val detail: Detail by detailOption()
    private val maxUnresolvedPct: Double? by maxUnresolvedPctOption()
    private val clients: List<ClientSpec> by clientOption()
    private val jdkHome: Path? by option(
        "--jdk-home",
        metavar = "dir",
        help = "Home of the JDK whose classes the sources are resolved against. By default, the JDK that runs the tool.",
    )
        .path(mustExist = true, canBeFile = false)

    override fun run() {
        val request = AnalysisRequest(
            model = model,
            out = out,
            jdkHome = jdkHome,
            workflowType = workflow,
            maxDepth = maxDepth,
            detail = detail,
            clients = clients,
        )
        val result = attempt { Stages(::log).analyse(request) }
        val selected = workflow
        if (selected != null && result.inventory.workflows.none { it.workflowType == selected }) {
            fail("the model has no workflow type '$selected'")
        }

        val limit = maxUnresolvedPct
        val overThreshold = result.workflows.filter { Unresolved.exceeds(it.document.stats, limit) }
        if (limit != null) {
            overThreshold.forEach { log(Unresolved.describe(it.document.workflowType, it.document.stats, limit)) }
        }

        if (json) echo(Summaries.format(summary(result))) else echo(describe(result))
        complete(Completion.status(degradedFallback = false, overThreshold = overThreshold.isNotEmpty()))
    }

    private fun summary(result: AnalysisResult): JsonObject =
        buildJsonObject {
            val inventory = result.inventory
            put("command", commandName)
            put("commit", inventory.commit)
            put("mode", inventory.modelMode.name.lowercase())
            // Only an overview says what it is, as its IR files do.
            if (detail == Detail.OVERVIEW) put("detail", detail.name.lowercase())
            put("inventory", result.inventoryFile.toString())
            put(
                "workflows",
                buildJsonArray {
                    inventory.workflows.forEach { workflow ->
                        add(
                            buildJsonObject {
                                put("workflowType", workflow.workflowType)
                                put("interface", workflow.interfaceName)
                                put("implementations", buildJsonArray { workflow.implementations.forEach { add(it.className) } })
                                put("entryPoints", workflow.entryPoints.size)
                                put(
                                    "ir",
                                    buildJsonArray {
                                        result.workflows
                                            .filter { it.document.interfaceName == workflow.interfaceName }
                                            .forEach { add(summary(it)) }
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put("activityInterfaces", buildJsonArray { inventory.activityInterfaces.forEach { add(it.interfaceName) } })
            put(
                "standaloneActivities",
                buildJsonObject {
                    put("file", result.standaloneActivitiesFile.toString())
                    put("activities", buildJsonArray { result.standaloneActivities.activities.forEach { add(it.name) } })
                    put("unresolvedWorkflows", buildJsonArray { result.standaloneActivities.unresolvedWorkflows.forEach { add(it) } })
                },
            )
            // What discovery warns of, then what reading the workflows does, as they are logged.
            put("warnings", buildJsonArray { (result.discovery.warnings + result.warnings).forEach { add(it) } })
        }

    private fun summary(workflow: AnalysedWorkflow): JsonObject =
        buildJsonObject {
            put("implementation", workflow.document.implementation)
            put("file", workflow.file.toString())
            put("nodes", workflow.document.stats.nodes)
            put("unknown", workflow.document.stats.unknown)
            // Only a document that left calls out says how many.
            if (workflow.document.skippedCalls.isNotEmpty()) put("skipped", workflow.document.skippedCalls.size)
            if (workflow.document.leftOutCalls.isNotEmpty()) put("leftOut", workflow.document.leftOutCalls.size)
        }

    private fun describe(result: AnalysisResult): String =
        buildString {
            val inventory = result.inventory
            append("${inventory.workflows.size} workflow type(s) and ")
            append("${inventory.activityInterfaces.size} activity interface(s) ")
            append("in the ${inventory.modelMode.name.lowercase()} model of ${inventory.commit}\n")
            append(result.inventoryFile).append('\n')
            append("${result.standaloneActivities.activities.size} activity type(s) that no workflow calls\n")
            append(result.standaloneActivitiesFile).append('\n')
            append("${result.workflows.size} IR file(s) in ${result.inventoryFile.parent}")
            val skipped = result.workflows.sumOf { it.document.skippedCalls.size }
            if (skipped > 0) append("\n$skipped unresolved call(s) taken for calls into libraries and not drawn, listed in the IR files")
            val leftOut = result.workflows.sumOf { it.document.leftOutCalls.size }
            if (leftOut > 0) append("\n${Summaries.leftOut(leftOut)}, listed in the IR files")
        }
}
