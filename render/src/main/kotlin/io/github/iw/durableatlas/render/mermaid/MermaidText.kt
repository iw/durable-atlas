package io.github.iw.durableatlas.render.mermaid

/**
 * Writes arbitrary text as a Mermaid label. Labels are always double-quoted, which takes care of
 * brackets, braces and keywords; every character that still means something to Mermaid inside
 * quotes, to the HTML the label is rendered as, or to the Markdown fence around the diagram is
 * written as an entity code (`#35;`, `#quot;`).
 */
internal object MermaidText {
    const val LINE_BREAK: String = "<br/>"

    private val entities: Map<Char, String> = mapOf(
        '"' to "#quot;",
        '&' to "#amp;",
        '<' to "#lt;",
        '>' to "#gt;",
        // Starts an entity code.
        '#' to "#35;",
        // Ends a statement, and ends an entity code.
        ';' to "#59;",
        // Mermaid looks for `style …:…#…;` in the whole diagram text before parsing it.
        ':' to "#58;",
        '|' to "#124;",
        // A quoted label that starts with a backtick is read as Markdown.
        '`' to "#96;",
        // `%%` starts a comment or a directive.
        '%' to "#37;",
        // `$$` starts a formula.
        '$' to "#36;",
        '\\' to "#92;",
    )

    /** A quoted label with one line per element of [lines]. */
    fun quote(lines: List<String>): String =
        lines.joinToString(separator = LINE_BREAK, prefix = "\"", postfix = "\"", transform = ::escape)

    fun quote(text: String): String = quote(listOf(text))

    fun escape(text: String): String {
        val escaped = StringBuilder(text.length + 16)
        for (char in text) {
            val entity = entities[char]
            when {
                entity != null -> escaped.append(entity)
                char.isISOControl() -> escaped.append(' ')
                else -> escaped.append(char)
            }
        }
        return escaped.toString()
    }
}
