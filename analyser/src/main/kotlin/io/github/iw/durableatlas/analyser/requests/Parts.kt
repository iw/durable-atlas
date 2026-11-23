package io.github.iw.durableatlas.analyser.requests

import io.github.iw.durableatlas.ir.ParameterLocation
import io.github.iw.durableatlas.ir.RequestParameter

/**
 * A piece of a text that the code builds: a URI, or the operation a client is asked for. What the
 * code fixes is kept as it is; what it computes is told by where it comes from.
 */
internal sealed interface Part {
    /** Text the code fixes. */
    data class Text(val text: String) : Part

    /** The value of a configuration property. */
    data class Key(val key: String, val default: String? = null) : Part

    /**
     * A value the code computes, by the name it is written with, and with the type of the
     * expression it comes from as requests write types, where that is known.
     */
    data class Name(val written: String, val type: String? = null) : Part
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

/**
 * The parts at the start of a target that say where it goes rather than what it asks for: the
 * key or computed part it starts with, and a second key that follows that one directly; or, for
 * a target that starts with fixed text, none. How many there are.
 */
internal fun List<Part>.serverParts(): Int {
    val first = firstOrNull() ?: return 0
    if (first is Part.Text) return 0
    return if (first is Part.Key && getOrNull(1) is Part.Key) 2 else 1
}

/**
 * The parameters of a target: each computed part of its path and each key in it after the start
 * (see [serverParts]), by the name the target writes it with; each part of its query, by its key.
 */
internal fun List<Part>.parameters(): List<RequestParameter> {
    val parameters = ArrayList<RequestParameter>()
    val rest = drop(serverParts())
    val question = rest.indexOfFirst { it is Part.Text && '?' in it.text }
    val path = if (question < 0) rest else rest.take(question)
    for (part in path) {
        when (part) {
            is Part.Name -> parameters += RequestParameter(part.written, ParameterLocation.PATH, part.type)
            is Part.Key -> parameters += RequestParameter(part.key, ParameterLocation.PATH)
            is Part.Text -> Unit
        }
    }
    if (question < 0) return parameters.distinctBy { it.name }
    // The query as pieces: the fixed text after the `?`, then the parts after it, split at each `&`.
    val split = rest[question] as Part.Text
    val pieces = listOf<Part>(Part.Text(split.text.substringAfter('?'))) + rest.drop(question + 1)
    val items = ArrayList<MutableList<Part>>().apply { add(ArrayList()) }
    for (piece in pieces) {
        if (piece !is Part.Text) {
            items.last() += piece
            continue
        }
        val texts = piece.text.split('&')
        texts.forEachIndexed { index, text ->
            if (index > 0) items += ArrayList<Part>()
            if (text.isNotEmpty()) items.last() += Part.Text(text)
        }
    }
    for (item in items) {
        if (item.isEmpty()) continue
        val head = item.first()
        if (head is Part.Text && '=' in head.text) {
            val key = head.text.substringBefore('=')
            val value = listOf<Part>(Part.Text(head.text.substringAfter('='))) + item.drop(1)
            val computed = value.filterIsInstance<Part.Name>().singleOrNull()?.takeIf { value.joined().size == 1 }
            if (key.isNotEmpty()) parameters += RequestParameter(key, ParameterLocation.QUERY, computed?.type, value.joined().text())
        } else if (item.size == 1 && head is Part.Name) {
            // A query that is computed as a whole, or a part of it whose key is computed as well.
            parameters += RequestParameter(head.written, ParameterLocation.QUERY, head.type, "{${head.written}}")
        }
    }
    return parameters.distinctBy { it.location to it.name }
}

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
