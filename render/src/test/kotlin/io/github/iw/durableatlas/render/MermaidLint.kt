package io.github.iw.durableatlas.render

import kotlin.test.fail

/**
 * A strict check of the Mermaid text the renderer writes. It is not a Mermaid parser: it accepts
 * only the handful of statement forms the renderer uses and only the characters known to be safe
 * inside a quoted label, so anything else that slips into the output fails a test without
 * needing Mermaid itself.
 */
object MermaidLint {
    private const val ID = "[A-Za-z][A-Za-z0-9_]*"
    private const val LABEL = "\"(?:<br/>|#(?:quot|amp|lt|gt|\\d+);|[^\"#;:|`%$\\\\<>&\\p{Cntrl}\\u2028\\u2029])+\""
    private const val CSS = "[a-z-]+:[#a-z0-9 ]+(?:,[a-z-]+:[#a-z0-9 ]+)*"
    private val shapes = listOf("[" to "]", "(" to ")", "([" to "])", "[[" to "]]", "{{" to "}}", "{" to "}", "((" to "))", "[(" to ")]")

    private val node = Regex(
        "($ID)(?:" + shapes.joinToString("|") { (open, close) -> Regex.escape(open) + LABEL + Regex.escape(close) } + ")",
    )
    private val subgraph = Regex("subgraph ($ID) \\[$LABEL]")
    private val edge = Regex("($ID) (?:-->|-\\.->)(?:\\|$LABEL\\|)? ($ID)")
    private val classDef = Regex("classDef ($ID) $CSS")
    private val classUse = Regex("class ($ID(?:,$ID)*) ($ID)")
    private val style = Regex("style ($ID) $CSS")
    private val linkStyle = Regex("linkStyle (\\d+(?:,\\d+)*) $CSS")
    private val reserved = setOf(
        "end", "subgraph", "graph", "flowchart", "style", "linkStyle", "classDef", "class", "click", "href", "call",
        "default", "direction", "interpolate",
    )

    fun check(mermaid: String) {
        val problems = problems(mermaid)
        if (problems.isNotEmpty()) fail("Not the Mermaid the renderer should write:\n" + problems.joinToString("\n") { "  $it" })
    }

    fun problems(mermaid: String): List<String> {
        val problems = mutableListOf<String>()
        if (!mermaid.endsWith("\n") || mermaid.endsWith("\n\n")) problems += "must end in exactly one line break"
        if ('\r' in mermaid) problems += "contains a carriage return"
        val lines = mermaid.removeSuffix("\n").split("\n")
        if (lines.first() != "flowchart TD") problems += "line 1: expected 'flowchart TD'"

        val nodes = mutableSetOf<String>()
        val groups = mutableSetOf<String>()
        val classes = mutableSetOf<String>()
        var depth = 0
        var edges = 0
        val later = mutableListOf<() -> Unit>()
        fun declare(id: String, into: MutableSet<String>, at: Int) {
            if (id in reserved) problems += "line $at: '$id' is a Mermaid keyword"
            if (id in nodes || id in groups) problems += "line $at: '$id' is declared twice"
            into += id
        }
        lines.drop(1).forEachIndexed { index, raw ->
            val at = index + 2
            val line = raw.trimStart(' ')
            val indent = raw.length - line.length
            if (raw != raw.trimEnd()) problems += "line $at: trailing white space"
            if (line == "end") depth--
            if (indent != 4 * (depth + 1)) problems += "line $at: indented by $indent, expected ${4 * (depth + 1)}"
            when {
                line == "end" -> if (depth < 0) problems += "line $at: 'end' without 'subgraph'"
                node.matches(line) -> declare(node.matchEntire(line)!!.groupValues[1], nodes, at)
                subgraph.matches(line) -> {
                    declare(subgraph.matchEntire(line)!!.groupValues[1], groups, at)
                    depth++
                }
                edge.matches(line) -> {
                    val (from, to) = edge.matchEntire(line)!!.destructured
                    edges++
                    later += { listOf(from, to).forEach { if (it !in nodes) problems += "line $at: edge end '$it' is not a node" } }
                }
                classDef.matches(line) -> classes += classDef.matchEntire(line)!!.groupValues[1]
                classUse.matches(line) -> {
                    val (ids, name) = classUse.matchEntire(line)!!.destructured
                    if (name !in classes) problems += "line $at: class '$name' is not defined"
                    ids.split(",").forEach { if (it !in nodes) problems += "line $at: class given to '$it', which is not a node" }
                }
                style.matches(line) -> {
                    val id = style.matchEntire(line)!!.groupValues[1]
                    if (id !in groups) problems += "line $at: style for '$id', which is not a subgraph"
                }
                linkStyle.matches(line) ->
                    linkStyle.matchEntire(line)!!.groupValues[1].split(",").forEach {
                        if (it.toInt() >= edges) problems += "line $at: no edge $it"
                    }
                else -> problems += "line $at: unexpected statement: $line"
            }
        }
        if (depth != 0) problems += "'subgraph' without 'end'"
        later.forEach { it() }
        return problems
    }

    /** The diagrams inside the fenced blocks of a Markdown page. */
    fun diagramsIn(markdown: String): List<String> =
        Regex("(?m)^```mermaid\\n(.*?\\n)```$", RegexOption.DOT_MATCHES_ALL).findAll(markdown).map { it.groupValues[1] }.toList()
}
