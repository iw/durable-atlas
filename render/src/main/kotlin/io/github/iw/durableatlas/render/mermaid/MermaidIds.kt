package io.github.iw.durableatlas.render.mermaid

/**
 * Hands out the identifiers used in Mermaid text. A node id of the usual form (`n` followed by
 * letters, digits and underscores) is used as it is, so diagrams can be matched to the document
 * by eye. Any other id is rewritten to that form. Identifiers for things that are not nodes of
 * the document are derived from those, and none is given out twice.
 */
internal class MermaidIds(nodeIds: Collection<String>) {
    private val taken = HashSet<String>()
    private val nodes = HashMap<String, String>()

    init {
        val sorted = nodeIds.sorted()
        val (plain, other) = sorted.partition { PLAIN.matches(it) }
        plain.forEach { nodes[it] = claim(it) }
        other.forEach { nodes[it] = claim(PREFIX + it.replace(UNSAFE, "_")) }
    }

    fun node(id: String): String = nodes.getValue(id)

    /** A new identifier that starts with [base]. */
    fun derived(base: String): String = claim(base.replace(UNSAFE, "_"))

    private fun claim(base: String): String {
        var candidate = base
        var attempt = 2
        while (!taken.add(candidate)) candidate = "${base}_${attempt++}"
        return candidate
    }

    private companion object {
        const val PREFIX = "n_"
        val PLAIN = Regex("n[A-Za-z0-9_]*")
        val UNSAFE = Regex("[^A-Za-z0-9_]")
    }
}
