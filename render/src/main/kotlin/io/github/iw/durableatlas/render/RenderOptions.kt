package io.github.iw.durableatlas.render

/**
 * Settings shared by every diagram renderer. They decide what is drawn and where; how single
 * elements are emphasised is a matter of [DiagramOverlay] and never changes the arrangement.
 *
 * @param maxNodes documents with more nodes than this have their regions (loops, parallel sections,
 *   compensations with their steps) drawn as single nodes
 * @param maxLabelLength longest text taken from the document for one line of a label, in
 *   characters; longer text is cut and ends in an ellipsis
 * @param simplification set to have the diagram drawn more simply than these settings ask for,
 *   after drawing it as asked took too long; see [DiagramFormat.simplifications]
 */
data class RenderOptions(
    val maxNodes: Int = DEFAULT_MAX_NODES,
    val maxLabelLength: Int = DEFAULT_MAX_LABEL_LENGTH,
    val simplification: Simplification? = null,
) {
    init {
        require(maxNodes >= 0) { "maxNodes must not be negative: $maxNodes" }
        require(maxLabelLength >= MIN_LABEL_LENGTH) { "maxLabelLength must be at least $MIN_LABEL_LENGTH: $maxLabelLength" }
    }

    companion object {
        const val DEFAULT_MAX_NODES: Int = 150
        const val DEFAULT_MAX_LABEL_LENGTH: Int = 80
        private const val MIN_LABEL_LENGTH = 4
    }
}
