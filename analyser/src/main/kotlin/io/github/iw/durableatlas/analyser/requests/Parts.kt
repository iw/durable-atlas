package io.github.iw.durableatlas.analyser.requests

/**
 * A piece of a text that the code builds: a URI, or the operation a client is asked for. What the
 * code fixes is kept as it is; what it computes is told by where it comes from.
 */
internal sealed interface Part {
    /** Text the code fixes. */
    data class Text(val text: String) : Part

    /** The value of a configuration property. */
    data class Key(val key: String, val default: String? = null) : Part

    /** A value the code computes, by the name it is written with. */
    data class Name(val written: String) : Part
}

/** The parts as one text: a key as `{key}`, a computed value as `{name}`. */
internal fun List<Part>.text(): String = joinToString("") { part ->
    when (part) {
        is Part.Text -> part.text
        is Part.Key -> "{${part.key}}"
        is Part.Name -> "{${part.written}}"
    }
}

/** The text, if every part is fixed. */
internal fun List<Part>.constant(): String? = if (all { it is Part.Text }) joinToString("") { (it as Part.Text).text } else null

/** The configuration properties among the parts, each once, in order. */
internal fun List<Part>.keys(): List<String> = filterIsInstance<Part.Key>().map { it.key }.distinct()

/** Neighbouring texts joined, empty texts left out. */
internal fun List<Part>.joined(): List<Part> {
    val joined = ArrayList<Part>()
    for (part in this) {
        val last = joined.lastOrNull()
        when {
            part is Part.Text && part.text.isEmpty() -> Unit
            part is Part.Text && last is Part.Text -> joined[joined.size - 1] = Part.Text(last.text + part.text)
            else -> joined += part
        }
    }
    return joined
}

/**
 * The pieces of the source text of a string template, `"…"` or `"""…"""`: its fixed texts with
 * escapes undone, and in between the source text of each expression it holds, in order. `null`
 * if the text cannot be read as a template.
 */
internal object Templates {
    sealed interface Piece {
        data class Fixed(val text: String) : Piece

        data class Expression(val source: String) : Piece
    }

    fun pieces(source: String): List<Piece>? {
        val raw = source.startsWith("\"\"\"") && source.endsWith("\"\"\"") && source.length >= 6
        val body = when {
            raw -> source.substring(3, source.length - 3)
            source.length >= 2 && source.startsWith("\"") && source.endsWith("\"") -> source.substring(1, source.length - 1)
            else -> return null
        }
        val pieces = ArrayList<Piece>()
        val fixed = StringBuilder()
        fun flush() {
            if (fixed.isNotEmpty()) pieces += Piece.Fixed(fixed.toString())
            fixed.setLength(0)
        }
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && !raw && i + 1 < body.length -> {
                    val next = body[i + 1]
                    if (next == 'u' && i + 5 < body.length) {
                        fixed.append(body.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: return null)
                        i += 6
                    } else {
                        fixed.append(ESCAPES[next] ?: next)
                        i += 2
                    }
                }
                c == '$' && i + 1 < body.length && body[i + 1] == '{' -> {
                    val end = closing(body, i + 1) ?: return null
                    flush()
                    pieces += Piece.Expression(body.substring(i + 2, end).trim())
                    i = end + 1
                }
                c == '$' && i + 1 < body.length && (body[i + 1].isLetter() || body[i + 1] == '_') -> {
                    var end = i + 1
                    while (end < body.length && (body[end].isLetterOrDigit() || body[end] == '_')) end++
                    flush()
                    pieces += Piece.Expression(body.substring(i + 1, end))
                    i = end
                }
                else -> {
                    fixed.append(c)
                    i++
                }
            }
        }
        flush()
        return pieces
    }

    /** The index of the brace that closes the one at [open], over nested braces and strings. */
    private fun closing(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        var quoted = false
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '\\' -> i++
                c == '"' -> quoted = !quoted
                !quoted && c == '{' -> depth++
                !quoted && c == '}' -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }

    private val ESCAPES = mapOf('t' to '\t', 'b' to '\b', 'n' to '\n', 'r' to '\r', '\'' to '\'', '"' to '"', '\\' to '\\', '$' to '$')
}
