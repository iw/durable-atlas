package io.github.iw.durableatlas.overlay.diagram

import io.github.iw.durableatlas.render.DiagramLink
import io.github.iw.durableatlas.render.IndexPage
import io.github.iw.durableatlas.render.markdown.Markdown

/**
 * The page that lists what an overlay wrote: every workflow type with its coverage, every
 * execution with its diagram, and the histories that were left out.
 */
internal object OverlayIndex {
    private const val NONE = "–"

    /** @param sections Markdown sections added at the end, for example the legend of a format */
    fun render(summary: OverlaySummary, sections: List<String>): String {
        val page = StringBuilder()
        fun block(text: String) {
            page.append(text.trim('\n')).append("\n\n")
        }

        block("# Workflow coverage")
        val executions = summary.workflows.sumOf { it.executions.size }
        block("${count(executions, "execution")} of ${count(summary.workflows.size, "workflow type")}.")
        if (summary.workflows.isNotEmpty()) block(workflows(summary.workflows))
        IndexPage.gaps(summary.workflows.flatMap { workflow -> workflow.missing.map { workflow.workflowType to it } }).forEach(::block)
        IndexPage.simpler(summary.workflows.flatMap { workflow -> workflow.simplified.map { workflow.workflowType to it } }).forEach(::block)
        for (workflow in summary.workflows) {
            block("## ${Markdown.code(workflow.workflowType)}")
            block(executions(workflow))
            unmapped(workflow)?.let(::block)
            outside(workflow)?.let(::block)
        }
        unmatched(summary)?.let(::block)
        sections.forEach(::block)
        return page.toString().trimEnd('\n') + "\n"
    }

    /**
     * The page of an overlay that was sorted by commit: every commit with the worker versions
     * that ran its histories and a link to its own page, and the histories that were left out.
     */
    fun renderCommits(summary: OverlaySummary): String {
        val page = StringBuilder()
        fun block(text: String) {
            page.append(text.trim('\n')).append("\n\n")
        }

        block("# Workflow coverage by commit")
        val executions = summary.workflows.sumOf { it.executions.size }
        block("${count(executions, "execution")} laid over the workflows of ${count(summary.commits.size, "commit")}.")
        if (summary.commits.isNotEmpty()) {
            val rows = mutableListOf(
                Markdown.row("Commit", "Worker versions", "Workflow types", "Executions", "Events mapped", "Unmapped", "Coverage"),
                "|---|---|---:|---:|---:|---:|---|",
            )
            for (commit in summary.commits) {
                val versions = commit.versions.map { Markdown.code(it.toString()) } + listOfNotNull("no version".takeIf { commit.unversioned > 0 })
                rows += Markdown.row(
                    Markdown.code(commit.commit),
                    versions.joinToString(", "),
                    commit.workflows.size.toString(),
                    commit.workflows.sumOf { it.executions.size }.toString(),
                    commit.workflows.sumOf { it.coverage.events.mapped }.toString(),
                    commit.workflows.sumOf { it.coverage.events.unmapped }.toString(),
                    Markdown.link("index", commit.index),
                )
            }
            block(rows.joinToString("\n"))
        }
        unmatched(summary)?.let(::block)
        return page.toString().trimEnd('\n') + "\n"
    }

    private fun unmatched(summary: OverlaySummary): String? {
        if (summary.unmatched.isEmpty()) return null
        val lines = summary.unmatched.map {
            "- ${Markdown.code(it.file)}, workflow type ${Markdown.code(it.workflowType)}: ${Markdown.text(it.reason)}"
        }
        return (listOf("## Histories without a diagram", "") + lines).joinToString("\n")
    }

    private fun workflows(workflows: List<OverlaidWorkflow>): String {
        val rows = mutableListOf(
            Markdown.row("Workflow", "Executions", "Nodes hit", "Events mapped", "Unmapped", "Uncertain", "Coverage", "Diagram"),
            "|---|---:|---:|---:|---:|---:|---|---|",
        )
        for (workflow in workflows) {
            val coverage = workflow.coverage
            rows += Markdown.row(
                Markdown.code(workflow.workflowType),
                coverage.executions.toString(),
                "${coverage.hitNodes} of ${coverage.recordedNodes}",
                coverage.events.mapped.toString(),
                coverage.events.unmapped.toString(),
                coverage.ambiguous.size.toString(),
                links(workflow.files + DiagramLink("json", workflow.coverageFile)),
                workflow.diagramFile?.let { Markdown.link("svg", it) } ?: NONE,
            )
        }
        return rows.joinToString("\n")
    }

    private fun executions(workflow: OverlaidWorkflow): String {
        // The column is there only for workflows whose workers say which version they are.
        val versioned = workflow.executions.any { it.workerVersions.isNotEmpty() }
        val version = listOf("Worker version").takeIf { versioned }.orEmpty()
        // The column is there only for a workflow that ran for workflow types without a document of their own.
        val typed = workflow.executions.any { it.workflowType != null }
        val type = listOf("Workflow type").takeIf { typed }.orEmpty()
        val rows = mutableListOf(
            Markdown.row(*(listOf("History") + type + listOf("Workflow id") + version + listOf("Status", "Events mapped", "Unmapped", "Uncertain", "Diagram")).toTypedArray()),
            "|---|${if (typed) "---|" else ""}---|${if (versioned) "---|" else ""}---|---:|---:|---:|---|",
        )
        for (execution in workflow.executions) {
            val uncertain = workflow.coverage.ambiguous.count { it.history == execution.history }
            val ranBy = execution.workerVersions.joinToString(", then ") { Markdown.code(it.toString()) }.ifEmpty { NONE }
            rows += Markdown.row(
                *(
                    listOf(Markdown.code(execution.history)) +
                        listOf(Markdown.code(execution.workflowType ?: workflow.workflowType)).takeIf { typed }.orEmpty() +
                        listOf(execution.workflowId?.let(Markdown::code) ?: NONE) +
                        listOf(ranBy).takeIf { versioned }.orEmpty() +
                        listOf(
                            Markdown.text(execution.status.words),
                            execution.events.mapped.toString(),
                            execution.events.unmapped.toString(),
                            uncertain.toString(),
                            links(execution.files),
                        )
                    ).toTypedArray(),
            )
        }
        return rows.joinToString("\n")
    }

    private fun unmapped(workflow: OverlaidWorkflow): String? {
        val events = workflow.coverage.unmapped
        if (events.isEmpty()) return null
        val lines = events.map { event ->
            buildString {
                append("- ").append(Markdown.code(event.history)).append(", event ").append(event.eventId).append(": ")
                append(Markdown.code(event.eventType))
                event.name?.let { append(' ').append(Markdown.code(it)) }
                if (event.likelySources.isNotEmpty()) {
                    append("; may lie behind ").append(event.likelySources.joinToString(", ", transform = Markdown::code))
                }
            }
        }
        val behind = listOfNotNull(
            OverlayTexts.behindSkipped(events.size, workflow.skippedCalls),
            OverlayTexts.behindLeftOut(events.size, workflow.leftOutCalls),
        ).flatMap { listOf("", "$it The IR file of the workflow lists them.") }
        return (listOf("Events without a node:", "") + lines + behind).joinToString("\n")
    }

    private fun outside(workflow: OverlaidWorkflow): String? {
        val events = workflow.coverage.recognised
        if (events.isEmpty()) return null
        val lines = events.map { "- ${Markdown.code(it.history)}, event ${it.eventId}: ${Markdown.code(it.eventType)}" }
        return (listOf("Events from outside the workflow, which are no step of it:", "") + lines).joinToString("\n")
    }

    private fun links(files: List<DiagramLink>): String = files.joinToString(" · ") { Markdown.link(it.format, it.file) }

    private fun count(number: Int, noun: String): String = if (number == 1) "1 $noun" else "$number ${noun}s"
}
