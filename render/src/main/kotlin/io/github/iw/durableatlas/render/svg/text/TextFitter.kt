package io.github.iw.durableatlas.render.svg.text

private const val ELLIPSIS = "…"

/** Makes text fit a given width by breaking it into lines and cutting what is left over. */
internal class TextFitter(private val measurer: TextMeasurer) {
    fun width(text: String, style: TextStyle): Double = measurer.width(text, style)

    /** [text] if it fits [maxWidth], else its longest beginning that does once an ellipsis is added. */
    fun truncate(text: String, style: TextStyle, maxWidth: Double): String {
        if (width(text, style) <= maxWidth) return text
        val points = text.codePoints().toArray()
        var low = 0
        var high = points.size
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (width(cut(points, middle), style) <= maxWidth) low = middle else high = middle - 1
        }
        return cut(points, low)
    }

    /**
     * [text] if it fits [maxWidth], else its longest ending that does once an ellipsis is put in
     * front of it. An ending that starts after a dot is taken if there is one, so that a
     * qualified name loses whole parts. Null if not even one character fits.
     */
    fun ending(text: String, style: TextStyle, maxWidth: Double): String? {
        if (width(text, style) <= maxWidth) return text
        val points = text.codePoints().toArray()
        fun from(start: Int) = ELLIPSIS + String(points, start, points.size - start).trimStart()
        // The later an ending starts, the shorter it is.
        val fitting = (1 until points.size).filter { width(from(it), style) <= maxWidth }
        val start = fitting.firstOrNull { points[it - 1] == DOT } ?: fitting.firstOrNull() ?: return null
        return from(start)
    }

    /**
     * [text] on one line if it fits [maxWidth], else on two. See [lines].
     */
    fun twoLines(text: String, style: TextStyle, maxWidth: Double): List<String> = lines(text, style, maxWidth, 2)

    /**
     * [text] on as few lines of at most [maxWidth] as hold it, [maxLines] at most. A line ends
     * where a reader would break it: between two words, which is at a space or after an
     * operator. A word is broken only if the text does not fit its lines otherwise, and then
     * after a dot if it has one, else after other punctuation or before a capital inside it.
     * Every line is as full as that allows. Text that takes more than [maxLines] has the last
     * one cut.
     */
    fun lines(text: String, style: TextStyle, maxWidth: Double, maxLines: Int): List<String> {
        if (maxLines <= 1 || width(text, style) <= maxWidth) return listOf(if (maxLines <= 1) truncate(text, style, maxWidth) else text)
        val points = text.codePoints().toArray()
        fun piece(from: Int, to: Int) = String(points, from, to - from).trim()
        fun fits(line: String) = width(line, style) <= maxWidth
        val places = (1 until points.size).groupBy { breakAt(points, it) }

        /** Where a line that starts at [from] ends, at a place of [kind]: the last one at which the line still fits. */
        fun end(from: Int, kind: Break): Int? = places[kind]?.lastOrNull { it > from && piece(from, it).isNotEmpty() && fits(piece(from, it)) }

        /** The rest of the text from [from] on [count] lines that hold all of it; null if they do not. */
        fun whole(from: Int, count: Int): List<String>? {
            val rest = piece(from, points.size)
            if (fits(rest)) return listOf(rest)
            if (count == 1) return null
            // A line that ends earlier leaves more for the others, so of each kind of place only the last is worth a try.
            return Break.entries.firstNotNullOfOrNull { kind ->
                end(from, kind)?.let { to -> whole(to, count - 1)?.let { listOf(piece(from, to)) + it } }
            }
        }
        whole(0, maxLines)?.let { return it }

        // The lines do not hold it. Each ends at the best kind of place there is, and the last is cut.
        val lines = ArrayList<String>()
        var from = 0
        while (lines.size < maxLines - 1) {
            val to = Break.entries.firstNotNullOfOrNull { end(from, it) }
                // No good place: fill the line.
                ?: (from + 1 until points.size).lastOrNull { fits(String(points, from, it - from)) }
                ?: (from + 1)
            lines += piece(from, to)
            from = to
        }
        val rest = piece(from, points.size)
        return if (rest.isEmpty()) lines else lines + truncate(rest, style, maxWidth)
    }

    private fun cut(points: IntArray, count: Int): String = String(points, 0, count).trimEnd() + ELLIPSIS

    /** The places a line may end, the best first. */
    private enum class Break { BETWEEN_WORDS, AFTER_DOT, IN_WORD }

    /** What kind of place the gap before the character at [index] is for a line to end; null for none. */
    private fun breakAt(points: IntArray, index: Int): Break? {
        val before = points[index - 1]
        val after = points[index]
        return when {
            after == SPACE || before == SPACE -> Break.BETWEEN_WORDS
            before in OPERATORS -> Break.BETWEEN_WORDS.takeIf { afterOperator(points, index) }
            before == DOT -> Break.AFTER_DOT.takeIf { after != DOT }
            before in CLOSING -> Break.IN_WORD.takeIf { after !in CLOSING && after !in OPERATORS && after != DOT }
            Character.isLowerCase(before) && Character.isUpperCase(after) -> Break.IN_WORD
            else -> null
        }
    }

    /**
     * Whether a line may end before the character at [index], which follows an operator: after
     * operators that stand between two things, as in `a>=b`, and not after one that stands in
     * front of a thing, as in `!done`, `-1` or the `!` of `a&&!b`.
     */
    private fun afterOperator(points: IntArray, index: Int): Boolean {
        val after = points[index]
        val before = points[index - 1]
        // In `a&&!b` the line may end in front of the `!`, which belongs to `b`; `!!` and `!=` are one operator each.
        val negation = after == NOT && before != NOT && points.getOrNull(index + 1).let { it != null && it !in OPERATORS }
        if (!negation && (after in OPERATORS || after == DOT)) return false
        if (before == NOT && index >= 2 && points[index - 2] in OPERATORS && points[index - 2] != NOT) return false
        var start = index - 1
        while (start >= 0 && points[start] in OPERATORS) start--
        return start >= 0 && points[start] != SPACE && points[start] !in OPENING
    }

    private companion object {
        const val SPACE = ' '.code
        const val DOT = '.'.code
        const val NOT = '!'.code
        val OPERATORS: Set<Int> = "+-*/%=<>!&|?:,;".map { it.code }.toSet()
        val OPENING: Set<Int> = "([{".map { it.code }.toSet()
        val CLOSING: Set<Int> = "_)]}".map { it.code }.toSet()
    }
}
