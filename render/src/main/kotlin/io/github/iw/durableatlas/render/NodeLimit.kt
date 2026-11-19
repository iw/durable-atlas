package io.github.iw.durableatlas.render

/** How a document compares with the node limit, and what a renderer did about it. */
class NodeLimit internal constructor(
    /** Nodes in the document. */
    val total: Int,
    /** The limit that applied. */
    val max: Int,
    /**
     * Regions (loops, parallel sections, compensations with steps) drawn as a single node; zero
     * unless the document is over the limit, or is drawn that way because drawing it in full
     * took too long (see [Simplification]).
     */
    val collapsedRegions: Int,
    /** Nodes of the document that are drawn. */
    val drawn: Int,
) {
    val exceeded: Boolean
        get() = total > max

    /** One sentence for a diagram's header, or null when the document is within the limit. */
    fun notice(): String? = when {
        !exceeded -> null
        collapsedRegions == 0 ->
            "This workflow has $total nodes, more than the limit of $max, " +
                "and no loops, parallel sections or other regions that could be drawn as single nodes."
        else ->
            "This workflow has $total nodes, more than the limit of $max, so " +
                (if (collapsedRegions == 1) "its one region is drawn as a single node" else collapsedMany()) +
                " ($drawn nodes shown). Regions are loops, parallel sections and compensations."
    }

    private fun collapsedMany(): String = "its $collapsedRegions regions are drawn as single nodes"
}
