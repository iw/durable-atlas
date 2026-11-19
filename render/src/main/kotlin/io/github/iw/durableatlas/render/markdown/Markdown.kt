package io.github.iw.durableatlas.render.markdown

import io.github.iw.durableatlas.render.layout.singleLine

/** Writes arbitrary text as Markdown that shows that text and nothing else. */
object Markdown {
    private const val ESCAPED = "\\`*_[]<>#~&"
    private val backticks = Regex("`+")
    private val unreserved = (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '.', '_', '~', '/')).toSet()

    /** Plain text, with the characters Markdown would act on escaped. */
    fun text(text: String): String =
        text.singleLine().map { if (it in ESCAPED) "\\$it" else it.toString() }.joinToString("")

    /** An inline code span, whatever [text] contains. */
    fun code(text: String): String {
        val line = text.singleLine()
        val longestRun = backticks.findAll(line).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(longestRun + 1)
        if (line.isEmpty()) return "` `"
        val padding = if (line.startsWith("`") || line.endsWith("`")) " " else ""
        return fence + padding + line + padding + fence
    }

    /** A link to a file next to the page, with the file name as its text. */
    fun fileLink(file: String): String = "[${text(file)}](${target(file)})"

    fun link(label: String, file: String): String = "[${text(label)}](${target(file)})"

    /** Content of a table cell; pipes end a cell even inside a code span. */
    fun cell(markdown: String): String = markdown.replace("|", "\\|")

    fun row(vararg cells: String): String = cells.joinToString(separator = " | ", prefix = "| ", postfix = " |", transform = ::cell)

    private fun target(file: String): String {
        val encoded = StringBuilder()
        for (byte in file.toByteArray(Charsets.UTF_8)) {
            val char = byte.toInt().toChar()
            if (byte >= 0 && char in unreserved) encoded.append(char) else encoded.append("%%%02X".format(byte.toInt() and 0xFF))
        }
        return encoded.toString()
    }
}
