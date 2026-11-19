package io.github.iw.durableatlas.render.layout

private const val ELLIPSIS = "…"
private const val REPLACEMENT = 0xFFFD

/**
 * Reduces text from a document to one line: line breaks, tabs, control characters and runs of
 * spaces become a single space, and text longer than [max] characters is cut and ends in an
 * ellipsis. Unpaired surrogates, which cannot be written as UTF-8, become U+FFFD.
 */
internal fun String.singleLine(max: Int = Int.MAX_VALUE): String {
    val points = ArrayList<Int>(length)
    var gap = false
    codePoints().forEach { point ->
        if (Character.isWhitespace(point) || Character.isSpaceChar(point) || Character.isISOControl(point)) {
            gap = points.isNotEmpty()
        } else {
            if (gap) points += ' '.code
            gap = false
            points += if (point in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) REPLACEMENT else point
        }
    }
    val kept = if (points.size > max) points.subList(0, max - 1).dropLastWhile { it == ' '.code } else points
    val text = StringBuilder(kept.size + 1)
    kept.forEach(text::appendCodePoint)
    if (kept.size < points.size) text.append(ELLIPSIS)
    return text.toString()
}
