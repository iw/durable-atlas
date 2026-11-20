package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.limit.Limits
import io.github.iw.durableatlas.render.markdown.Markdown

/** One line of the index: a workflow and the files that show it. */
data class IndexEntry(
    val workflowType: String,
    val implementation: String,
    val commit: String,
    val modelMode: ModelMode,
    val nodes: Int,
    val unknown: Int,
    /** One file per format the workflow was written in, in the order the formats were asked for. */
    val files: List<DiagramLink>,
    /** How many calls that did not resolve the document left out as calls into libraries. */
    val skipped: Int = 0,
    /** How many calls the document has no node for because it is an overview. */
    val leftOut: Int = 0,
    /** The formats the workflow has no file in, because no diagram of it was drawn in time. */
    val missing: List<MissingDiagram> = emptyList(),
    /** The formats whose file shows the workflow more simply than was asked for, because drawing it as asked took too long. */
    val simplified: List<SimplifiedDiagram> = emptyList(),
) {
    companion object {
        fun of(
            document: IrDocument,
            files: List<DiagramLink>,
            missing: List<MissingDiagram> = emptyList(),
            simplified: List<SimplifiedDiagram> = emptyList(),
        ): IndexEntry = IndexEntry(
            workflowType = document.workflowType,
            implementation = document.implementation,
            commit = document.commit,
            modelMode = document.modelMode,
            nodes = document.nodes.size,
            unknown = document.nodes.count { it.kind == NodeKind.UNKNOWN },
            files = files,
            skipped = document.skippedCalls.size,
            leftOut = document.leftOutCalls.size,
            missing = missing,
            simplified = simplified,
        )
    }
}

/** A diagram file and the format it is in. */
data class DiagramLink(
    /** [DiagramFormat.name] of the format. */
    val format: String,
    /** File name relative to the index. */
    val file: String,
)

/**
 * The one page that lists every workflow with its size and a link to its diagram in every format
 * that was written. It is Markdown, which Git hosts show in place, whatever the diagram formats.
 */
object IndexPage {
    const val FILE: String = "index.md"

    /** @param sections Markdown sections added below the table, for example the legend of a format */
    fun render(entries: List<IndexEntry>, sections: List<String> = emptyList()): String {
        val sorted = entries.sortedWith(compareBy({ it.workflowType }, { it.files.firstOrNull()?.file }))
        val page = StringBuilder()
        fun block(text: String) {
            page.append(text.trim('\n')).append("\n\n")
        }

        block("# Workflow diagrams")
        block(summary(sorted))
        if (sorted.any { it.modelMode == ModelMode.DEGRADED }) {
            block(
                "> **Warning:** diagrams marked `degraded` were made without the build of the analysed " +
                    "repository, so calls into other modules and libraries may be missing or unresolved.",
            )
        }
        if (sorted.isNotEmpty()) block(table(sorted))
        gaps(sorted.flatMap { entry -> entry.missing.map { entry.workflowType to it } }).forEach(::block)
        simpler(sorted.flatMap { entry -> entry.simplified.map { entry.workflowType to it } }).forEach(::block)
        skipped(sorted)?.let(::block)
        leftOut(sorted)?.let(::block)
        sections.forEach(::block)
        return page.toString().trimEnd('\n') + "\n"
    }

    private fun summary(entries: List<IndexEntry>): String {
        val count = if (entries.size == 1) "1 workflow" else "${entries.size} workflows"
        val commits = entries.map { it.commit }.distinct().sorted()
        return when (commits.size) {
            0 -> "$count."
            1 -> "$count, from commit ${Markdown.code(commits.single())}."
            else -> "$count, from commits ${commits.joinToString(", ", transform = Markdown::code)}."
        }
    }

    /**
     * A paragraph for each format and time limit that left workflows without a diagram, naming
     * them: the table shows that a diagram is missing, and this says why.
     *
     * @param missing each missing diagram with the type of its workflow
     */
    fun gaps(missing: List<Pair<String, MissingDiagram>>): List<String> =
        // Which simpler ways were tried does not part them: only whether any was.
        missing.groupBy({ it.second.copy(tried = it.second.tried.take(1)) }, { it.first }).map { (diagram, workflows) ->
            val format = Missing.shown(diagram.format)
            val whose = if (workflows.size == 1) "1 workflow has no $format diagram" else "${workflows.size} workflows have no $format diagram"
            "Not drawn: $whose, because drawing one took longer than the limit of ${Limits.text(diagram.limit)}${Missing.alsoSimpler(diagram)}: " +
                "${workflows.joinToString(", ", transform = Markdown::code)}. Their other files are complete."
        }

    /**
     * A paragraph for each format, time limit and way of drawing more simply that workflows were
     * drawn in, naming them.
     *
     * @param simplified each simpler diagram with the type of its workflow
     */
    fun simpler(simplified: List<Pair<String, SimplifiedDiagram>>): List<String> =
        simplified.groupBy({ it.second }, { it.first }).map { (diagram, workflows) ->
            val format = Missing.shown(diagram.format)
            val whose = if (workflows.size == 1) "the $format diagram of 1 workflow is" else "the $format diagrams of ${workflows.size} workflows are"
            val how = when (diagram.simplification.level) {
                Simplification.Level.PLAIN_ROWS -> "with each row ended where it is full"
                Simplification.Level.COLLAPSED -> "with regions as single nodes and each row ended where it is full"
            }
            "Drawn more simply: $whose drawn $how, because drawing them as the others are took longer than the limit of " +
                "${Limits.text(diagram.simplification.limit)}: ${workflows.joinToString(", ", transform = Markdown::code)}. Nothing in them is wrong; each says what was done."
        }

    /** What the column of skipped calls counts, where there is one. */
    private fun skipped(entries: List<IndexEntry>): String? {
        val total = entries.sumOf { it.skipped }
        if (total == 0) return null
        val calls = if (total == 1) "1 call that did not resolve was" else "$total calls that did not resolve were"
        return "Skipped: $calls taken for calls into libraries that the degraded model lacks, and left out of the diagrams. " +
            "The page of each workflow lists its own, and so does its IR file."
    }

    /** What the column of calls left out counts, where there is one. */
    private fun leftOut(entries: List<IndexEntry>): String? {
        val total = entries.sumOf { it.leftOut }
        if (total == 0) return null
        val calls = if (total == 1) "1 call that could not be read has" else "$total calls that could not be read have"
        return "Left out: the diagrams are overviews, and $calls no node in them: calls that were handed code, which is drawn " +
            "as run once where the call is made, and calls that lead to code that is not in the analysed sources. " +
            "The page of each workflow lists its own, and so does its IR file; an analysis with `--detail full` draws each as an unknown node."
    }

    private fun table(entries: List<IndexEntry>): String {
        // Each of the two columns is there only where something was skipped, or left out.
        val skipping = entries.any { it.skipped > 0 }
        val leaving = entries.any { it.leftOut > 0 }
        val counted = listOf("Skipped").takeIf { skipping }.orEmpty() + listOf("Left out").takeIf { leaving }.orEmpty()
        val rows = mutableListOf(
            Markdown.row(*(listOf("Workflow", "Implementation", "Model", "Nodes", "Unknown") + counted + "Diagram").toTypedArray()),
            "|---|---|---|---:|---:|${"---:|".repeat(counted.size)}---|",
        )
        entries.forEach { entry ->
            rows += Markdown.row(
                *(
                    listOf(
                        Markdown.code(entry.workflowType),
                        Markdown.code(entry.implementation),
                        if (entry.modelMode == ModelMode.DEGRADED) "`degraded`" else PageContent.modeName(entry.modelMode),
                        entry.nodes.toString(),
                        entry.unknown.toString(),
                    ) + listOf(entry.skipped.toString()).takeIf { skipping }.orEmpty() + listOf(entry.leftOut.toString()).takeIf { leaving }.orEmpty() +
                        (entry.files.map { Markdown.link(it.format, it.file) } + entry.missing.map { "no ${it.format}" }).joinToString(" · ")
                    ).toTypedArray(),
            )
        }
        return rows.joinToString("\n")
    }
}
