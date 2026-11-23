package io.github.iw.durableatlas.render.endpoints

import io.github.iw.durableatlas.ir.Request

/**
 * The target of an HTTP request taken apart as the analysis wrote it: where it goes (the server)
 * and what it asks for there (the path and the query). A part of the target the analysis wrote as
 * `{…}` is a configuration key where the request names it among its keys, and a computed value
 * otherwise.
 */
internal object Targets {
    sealed interface Piece {
        data class Text(val text: String) : Piece

        data class Slot(val name: String, val key: Boolean) : Piece
    }

    /**
     * @param keys the configuration keys the target starts with: one, or two where a second
     *   follows the first directly (a server variable)
     * @param literal the scheme and authority of a target that starts with fixed text, such as
     *   `https://api.example.com`
     * @param computed the `{name}` a target starts with where the code computes where it goes
     * @param path the path, starting with `/`, with each other part as `{…}`
     */
    class Split(val keys: List<String>, val literal: String?, val computed: String?, val path: String)

    /** [target] in pieces: fixed text, and each `{…}` part, which is a key where [keys] holds it. */
    fun pieces(target: String, keys: Set<String>): List<Piece> {
        val pieces = ArrayList<Piece>()
        var i = 0
        val text = StringBuilder()
        while (i < target.length) {
            val close = if (target[i] == '{') target.indexOf('}', i + 1) else -1
            if (close < 0) {
                text.append(target[i++])
                continue
            }
            if (text.isNotEmpty()) pieces += Piece.Text(text.toString()).also { text.clear() }
            val name = target.substring(i + 1, close)
            pieces += Piece.Slot(name, name in keys)
            i = close + 1
        }
        if (text.isNotEmpty()) pieces += Piece.Text(text.toString())
        return pieces
    }

    /** Where [request] goes and what it asks for there; see [Split]. */
    fun split(request: Request): Split {
        val pieces = pieces(request.target.orEmpty(), request.keys.toSet())
        val first = pieces.firstOrNull()
        var literal: String? = null
        var computed: String? = null
        val keys = ArrayList<String>()
        var rest = pieces
        when {
            first is Piece.Slot && first.key -> {
                keys += first.name
                val second = pieces.getOrNull(1)
                rest = if (second is Piece.Slot && second.key) {
                    keys += second.name
                    pieces.drop(2)
                } else {
                    pieces.drop(1)
                }
            }
            first is Piece.Slot -> {
                computed = "{${first.name}}"
                rest = pieces.drop(1)
            }
            first is Piece.Text -> {
                val authority = AUTHORITY.find(first.text)
                if (authority != null) {
                    literal = authority.value
                    rest = listOf<Piece>(Piece.Text(first.text.substring(authority.value.length))) + pieces.drop(1)
                }
            }
        }
        val path = StringBuilder()
        for (piece in rest) {
            when (piece) {
                is Piece.Text -> {
                    val question = piece.text.indexOf('?')
                    if (question >= 0) {
                        path.append(piece.text, 0, question)
                        break
                    }
                    path.append(piece.text)
                }
                is Piece.Slot -> path.append('{').append(piece.name).append('}')
            }
        }
        val written = path.toString().substringBefore('#')
        return Split(keys, literal, computed, if (written.startsWith("/")) written else "/$written")
    }

    /** The names of the `{…}` parts of a path, each once, in order. */
    fun slots(path: String): List<String> = pieces(path, emptySet()).filterIsInstance<Piece.Slot>().map { it.name }.distinct()

    /** The scheme and authority at the start of a URI. */
    private val AUTHORITY = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#{]*")
}
