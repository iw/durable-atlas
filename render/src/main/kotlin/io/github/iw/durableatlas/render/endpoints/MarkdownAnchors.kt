package io.github.iw.durableatlas.render.endpoints

/**
 * The anchors GitHub and GitLab give the headings of a Markdown page, worked out as both do it, so
 * that a link written to a heading works on either.
 *
 * Both take the text of the heading in lower case, drop every character other than letters,
 * digits, marks, `_`, `-` and spaces, and turn each space into a hyphen; GitLab then makes each
 * run of hyphens one. Both add `-1`, `-2` and so on to a heading whose anchor an earlier one has.
 * The headings of the endpoints page are written so that both give the same anchor: none gives
 * two hyphens in a row, and none has the anchor of another.
 */
internal object MarkdownAnchors {
    /** The anchor GitHub gives a heading of [text], before any number is added. */
    fun github(text: String): String {
        val out = StringBuilder()
        for (char in text.lowercase()) {
            when {
                char == ' ' -> out.append('-')
                char == '-' || char == '_' || char.isLetterOrDigit() || Character.getType(char).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() } -> out.append(char)
                else -> Unit
            }
        }
        return out.toString()
    }

    /** The anchor GitLab gives a heading of [text], before any number is added. */
    fun gitlab(text: String): String = github(text).replace(Regex("-{2,}"), "-")

    /**
     * Headings whose anchors are the same on both hosts and differ from each other: each text as
     * given where it is fine; else with what makes it differ dropped, or a number after it.
     */
    class Headings {
        private val taken = HashSet<String>()

        /** The text to write for a heading that would say [text], and its anchor. */
        fun take(text: String): Pair<String, String> {
            var heading = tidy(text)
            var n = 2
            while (github(heading) in taken || github(heading).isEmpty()) heading = "${tidy(text)} $n".also { n++ }
            val anchor = github(heading)
            taken += anchor
            return heading to anchor
        }

        /**
         * [text] with spaces in a row made one, and each word that is dropped whole or leaves
         * hyphens next to each other or at an end written without them: then no anchor has two
         * hyphens in a row, which GitLab would make one.
         */
        private fun tidy(text: String): String =
            text.split(' ').filter { it.isNotEmpty() }.mapNotNull { word ->
                val kept = github(word)
                when {
                    kept.isEmpty() -> null
                    "--" in kept || kept.startsWith("-") || kept.endsWith("-") -> word.filter { it != '-' }.takeIf { github(it).isNotEmpty() }
                    else -> word
                }
            }.joinToString(" ")
    }
}
