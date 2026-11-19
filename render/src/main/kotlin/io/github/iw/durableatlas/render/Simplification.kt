package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.render.limit.Limits
import java.time.Duration

/**
 * A drawing that is made more simply than asked for, because drawing it as asked took longer
 * than allowed. A simpler drawing shows nothing that is wrong: it says less, or is arranged with
 * less care, and says so itself.
 *
 * @param limit the time the drawing as asked was allowed
 */
data class Simplification(val level: Level, val limit: Duration) {
    /**
     * How much simpler, each level more so than the one before. They are tried in this order,
     * each with the time the drawing as asked was given, until one is done.
     */
    enum class Level(
        /** The level in a file that records it. */
        val key: String,
        /** A few words for a line of progress, as in "drawing it with …". */
        val words: String,
    ) {
        /**
         * Rows of the main flow end where they are full. The breaks between rows are not chosen
         * to make the diagram as low and as even as it can be. Everything is drawn.
         */
        PLAIN_ROWS("rows", "plain rows"),

        /**
         * As [PLAIN_ROWS], and every region (a loop, a parallel section, a compensation with its
         * steps) is drawn as a single node, as it is in a document over the node limit.
         */
        COLLAPSED("regions", "regions as single nodes"),
        ;

        companion object {
            fun ofKey(key: String): Level? = entries.firstOrNull { it.key == key }
        }
    }

    /**
     * One sentence for the header of the diagram.
     *
     * @param nodes how the document compares with the node limit, and which regions are drawn as single nodes
     */
    fun notice(nodes: NodeLimit): String {
        val time = Limits.text(limit)
        // A document over the node limit has its regions as single nodes anyway, and says so itself.
        if (level != Level.COLLAPSED || nodes.exceeded || nodes.collapsedRegions == 0) {
            return "Drawing this diagram with the best breaks between its rows took longer than the limit of $time, " +
                "so each row ends where it is full."
        }
        val regions = if (nodes.collapsedRegions == 1) "its one region is drawn as a single node" else "its ${nodes.collapsedRegions} regions are drawn as single nodes"
        return "Drawing this diagram in full took longer than the limit of $time, so $regions (${nodes.drawn} nodes shown) " +
            "and each row ends where it is full. Regions are loops, parallel sections and compensations."
    }
}
