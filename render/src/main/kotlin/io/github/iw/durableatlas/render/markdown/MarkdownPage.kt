package io.github.iw.durableatlas.render.markdown

import io.github.iw.durableatlas.ir.SkippedCall
import io.github.iw.durableatlas.render.PageContent

/**
 * A Markdown page for one workflow: a header, the diagram in whatever form the format embeds it,
 * links to the diagrams of its child workflows, the calls that could not be resolved, and the
 * calls that were left out of the diagram as unresolved calls into libraries.
 */
internal object MarkdownPage {
    /** @param diagram Markdown that shows the diagram, for example a fenced block */
    fun render(content: PageContent, diagram: String): String {
        val page = StringBuilder()
        fun block(text: String) {
            page.append(text).append("\n\n")
        }

        block("# ${Markdown.text(content.title)}")
        content.indexFile?.let { block(Markdown.link("All workflows", it)) }
        content.warning?.let { block("> **Warning:** $it") }
        content.limitNotice?.let { block("> **Note:** $it") }
        content.simplifiedNotice?.let { block("> **Note:** $it") }
        content.notices.forEach { block("> ${Markdown.text(it)}") }
        block(properties(content.facts))
        block(diagram.trimEnd('\n'))
        childWorkflows(content.childWorkflows)?.let(::block)
        unknownNodes(content.unknownNodes)?.let(::block)
        skippedCalls(content.skippedCalls)?.let(::block)
        return page.toString().trimEnd('\n') + "\n"
    }

    private fun skippedCalls(skipped: List<SkippedCall>): String? {
        if (skipped.isEmpty()) return null
        val lines = skipped.map { "- ${Markdown.code("${it.loc.file}:${it.loc.line}")}: ${Markdown.code(it.text)}" }
        return (listOf("## Skipped calls", "", PageContent.SKIPPED_CALLS, "") + lines).joinToString("\n")
    }

    private fun properties(facts: List<PageContent.Fact>): String {
        val rows = mutableListOf(Markdown.row("Property", "Value"), "|---|---|")
        facts.forEach {
            rows += Markdown.row(Markdown.text(it.name), if (it.literal) Markdown.code(it.value) else Markdown.text(it.value))
        }
        return rows.joinToString("\n")
    }

    private fun childWorkflows(children: List<PageContent.ChildWorkflow>): String? {
        if (children.isEmpty()) return null
        val lines = children.map { child ->
            val links = if (child.files.isEmpty()) "no diagram" else child.files.joinToString(", ", transform = Markdown::fileLink)
            val stub = child.interfaceName?.let { " (${Markdown.code(it)})" }.orEmpty()
            "- ${Markdown.code(child.workflowType)}$stub: $links"
        }
        return (listOf("## Child workflows", "") + lines).joinToString("\n")
    }

    private fun unknownNodes(unknown: List<PageContent.UnknownNode>): String? {
        if (unknown.isEmpty()) return null
        val lines = unknown.map { node ->
            buildString {
                append("- ").append(Markdown.code(node.reason))
                node.label?.let { append(": ").append(Markdown.code(it)) }
                node.location?.let { append(" at ").append(Markdown.code("${it.file}:${it.line}")) }
                if (node.candidates.isNotEmpty()) {
                    append("; candidates: ").append(node.candidates.joinToString(", ", transform = Markdown::code))
                }
            }
        }
        return (listOf("## Unknown nodes", "") + lines).joinToString("\n")
    }
}
