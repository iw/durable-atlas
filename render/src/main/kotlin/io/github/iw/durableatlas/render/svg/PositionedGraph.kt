package io.github.iw.durableatlas.render.svg

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The outline a node is drawn with. */
@Serializable
internal enum class Shape {
    @SerialName("card")
    CARD,

    @SerialName("outlineCard")
    OUTLINE_CARD,

    @SerialName("pill")
    PILL,
}

/**
 * A diagram after layout: every region, card, edge and label with its place and its words, and
 * nothing about how any of it is styled. It is all that drawing needs besides the document, so
 * the same positioned graph can be drawn plain or with an overlay and nothing moves in between.
 *
 * Positions are in SVG user units from the top left corner of the diagram, without the margin
 * around it, and are rounded to one decimal place, so a positioned graph that was written out and
 * read back draws exactly as before.
 *
 * @param band the box around the handlers, if the workflow has any
 * @param rows the extent of each row of the main flow, top to bottom
 */
@Serializable
internal data class PositionedGraph(
    val width: Double,
    val height: Double,
    val band: Box? = null,
    val regions: List<PositionedRegion>,
    val nodes: List<PositionedNode>,
    val edges: List<PositionedEdge>,
    val rows: List<Box>,
) {
    fun encode(): String = FORMAT.encodeToString(serializer(), this)

    companion object {
        private val FORMAT = Json { encodeDefaults = false }

        /** @throws IllegalArgumentException if [text] is not a positioned graph */
        fun decode(text: String): PositionedGraph = try {
            FORMAT.decodeFromString(serializer(), text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw IllegalArgumentException("Not a positioned graph: ${e.message}", e)
        }
    }
}

/**
 * A box around other nodes.
 *
 * @param id id of the node in the document
 * @param kind the construct, as named in the style data
 * @param parent id of the region it lies in, if any
 * @param titleLines the title as it is drawn, if it is too long for one line
 */
@Serializable
internal data class PositionedRegion(
    val id: String,
    val kind: String,
    val title: String,
    val box: Box,
    val parent: String? = null,
    val titleLines: List<String> = emptyList(),
) {
    /** The lines the title is drawn on. */
    val heading: List<String>
        get() = titleLines.ifEmpty { listOf(title) }
}

/**
 * A card or pill.
 *
 * @param id id of the node in the document, or of the entry point for a handler's card
 * @param kind the construct, as named in the style data
 * @param name the lines below the kind label
 * @param parent id of the region it lies in, if any
 * @param handler whether it is the card of a handler
 * @param nodes further nodes of the document this card stands for, which are not drawn themselves
 * @param entryPoints the entry points of the document this card stands for
 */
@Serializable
internal data class PositionedNode(
    val id: String,
    val kind: String,
    val shape: Shape,
    val box: Box,
    val kindLabel: String,
    val name: List<String> = emptyList(),
    val parent: String? = null,
    val handler: Boolean = false,
    val nodes: List<String> = emptyList(),
    val entryPoints: List<String> = emptyList(),
)

/**
 * @param from id of the node or handler card the edge starts at
 * @param kind `flow`, `exception`, `timeout`, `wakes` or `cancels`
 * @param path where it runs, as SVG path data
 * @param sources positions, in the document's list of edges in canonical order, of the edges
 *   that are drawn as this one
 */
@Serializable
internal data class PositionedEdge(
    val from: String,
    val to: String,
    val kind: String,
    val path: String,
    val label: PositionedLabel? = null,
    val sources: List<Int> = emptyList(),
)

/**
 * @param text what the label says, on one line
 * @param inside id of the region whose fill is behind the label, if it lies in one
 * @param lines the text as it is drawn, if it is too long for one line
 */
@Serializable
internal data class PositionedLabel(val text: String, val box: Box, val inside: String? = null, val lines: List<String> = emptyList()) {
    /** The lines the label is drawn on. */
    val shown: List<String>
        get() = lines.ifEmpty { listOf(text) }
}
