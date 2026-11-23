package io.github.iw.durableatlas.render.svg.scene

import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.Request
import io.github.iw.durableatlas.render.layout.CollapsedRegion
import io.github.iw.durableatlas.render.layout.Durations
import io.github.iw.durableatlas.render.layout.HandlerGroup
import io.github.iw.durableatlas.render.layout.NodeText
import io.github.iw.durableatlas.render.layout.RequestTexts
import io.github.iw.durableatlas.render.layout.singleLine
import io.github.iw.durableatlas.render.svg.ConstructStyles
import io.github.iw.durableatlas.render.svg.StyleKinds

/**
 * The words on a card: its kind label and, below it, up to two parts of a name.
 *
 * @param more what follows [detail] however short it is cut, such as how many more requests an
 *   activity makes than the one the detail names
 */
internal class CardText(val kindLabel: String, val name: String?, val detail: String? = null, val more: String? = null)

/**
 * The words of the SVG notation.
 *
 * @param requests every request of the document, which name the services of those that do not
 *   name their own
 */
internal class CardTexts(private val maxLength: Int, private val requests: List<Request> = emptyList()) {
    private fun text(value: String?): String? = value?.singleLine(maxLength)?.takeIf { it.isNotEmpty() }

    /** A node's own words for itself: its label when it has one, else its name. */
    private fun display(node: IrNode): String? = text(node.label) ?: text(node.name)

    private fun duration(value: String?): String? = text(value)?.let(Durations::humanise)

    /**
     * The words of a node's card. A step that the flow does not wait for says so below its name,
     * and a child workflow says after its kind what becomes of it when its parent closes, where
     * that is not the usual. An activity that asks something of other services names the first
     * request briefly below its name, by its method and service, and how many more it makes.
     */
    fun of(node: IrNode): CardText {
        val text = plain(node)
        // A region says it in its title.
        if (node.kind.isContainer) return text
        val waiting = NodeText.waiting(node)
        val kind = joined(text.kindLabel, NodeText.parentClose(node))
        return when {
            waiting == null -> CardText(kind, text.name, text.detail, text.more)
            text.name == null -> CardText(kind, waiting)
            text.detail == null -> CardText(kind, text.name, waiting)
            else -> CardText(joined(kind, waiting), text.name, text.detail, text.more)
        }
    }

    private fun plain(node: IrNode): CardText {
        val kind = ConstructStyles.of(StyleKinds.of(node.kind)).kindLabel
        return when (node.kind) {
            NodeKind.START -> CardText(kind, display(node) ?: "Start")
            NodeKind.END -> CardText(kind, "Complete")
            NodeKind.CONTINUE_AS_NEW -> CardText(kind, "Continue-as-new")
            NodeKind.FAIL -> CardText(kind, display(node) ?: "Failure")
            NodeKind.NEXUS_OPERATION ->
                CardText(kind, text(node.label) ?: listOfNotNull(text(node.service), text(node.name)).joinToString(".").ifEmpty { null })
            NodeKind.TIMER -> CardText(joined("TIMER", duration(node.duration)), display(node))
            NodeKind.AWAIT -> CardText(
                joined("AWAIT", duration(node.timeout)?.let { "TIMEOUT ${capitals(it)}" }),
                text(node.label) ?: text(node.reads.joinToString(" or ")) ?: text(node.name),
            )
            NodeKind.VERSION_GATE -> CardText(kind, text(node.label) ?: text(node.changeId) ?: text(node.name))
            NodeKind.BRANCH -> CardText(kind, display(node) ?: "condition")
            NodeKind.UNKNOWN -> CardText(kind, text(node.reason) ?: "unknown", text(node.label))
            NodeKind.LOOP, NodeKind.PARALLEL -> CardText(regionTitle(node), null)
            NodeKind.CANCEL_SCOPE -> CardText(kind, display(node) ?: "scope")
            NodeKind.CANCEL_EXTERNAL -> CardText(kind, display(node) ?: "another workflow")
            NodeKind.UPSERT_SEARCH_ATTRIBUTES, NodeKind.UPSERT_MEMO -> CardText(kind, text(node.label) ?: NodeText.written(node, ::text))
            NodeKind.ACTIVITY, NodeKind.LOCAL_ACTIVITY -> {
                val first = RequestTexts.of(node).firstOrNull()?.let { text(RequestTexts.brief(it, requests)) }
                if (first == null) CardText(kind, display(node)) else CardText(kind, display(node) ?: "activity", first, RequestTexts.more(node))
            }
            else -> CardText(kind, display(node))
        }
    }

    /**
     * The words of the pill for the end of a handler, where that end is drawn: the handler
     * returns there, which ends nothing but the handler.
     */
    fun handlerEnd(): CardText = CardText(ConstructStyles.of(StyleKinds.of(NodeKind.END)).kindLabel, RETURN)

    /** Title of the box around a region: its kind, and its label if it has one. */
    fun regionTitle(node: IrNode): String {
        val word = when (node.kind) {
            NodeKind.LOOP -> "LOOP"
            NodeKind.PARALLEL -> "PARALLEL"
            else -> "COMPENSATION"
        }
        return joined(word, display(node) ?: NodeText.waiting(node) ?: NodeText.joining(node))
    }

    /** A region that is drawn as one card because the workflow is over the node limit. */
    fun of(region: CollapsedRegion): CardText {
        val hidden = buildString {
            append(if (region.hiddenNodes == 1) "1 node hidden" else "${region.hiddenNodes} nodes hidden")
            if (region.hiddenUnknown > 0) append(", ${region.hiddenUnknown} unknown")
        }
        return CardText(regionTitle(region.node), hidden)
    }

    fun of(group: HandlerGroup): CardText {
        val kinds = group.entryPoints.map { it.kind }.toSet()
        val primary = primary(group)
        val kind = when {
            kinds == NodeText.MERGED_UPDATE -> "UPDATE + VALIDATOR"
            primary.kind == EntryPointKind.UPDATE_VALIDATOR -> "UPDATE VALIDATOR"
            else -> ConstructStyles.of(StyleKinds.of(primary.kind)).kindLabel
        }
        return CardText(kind, text(primary.name) ?: NodeText.kindName(primary.kind))
    }

    companion object {
        const val HANDLERS = "HANDLERS"
        const val WAKES = "wakes"
        const val CANCELS = NodeText.CANCELS

        /** What the end of a handler is called. */
        const val RETURN = "Return"

        /** The kind, as the style data names it, of the pill where a flow ends. */
        val END: String = StyleKinds.of(NodeKind.END)

        /** The entry point a handler's card is named after: the update rather than its validator. */
        fun primary(group: HandlerGroup) =
            group.entryPoints.firstOrNull { it.kind != EntryPointKind.UPDATE_VALIDATOR } ?: group.entryPoints.first()

        /**
         * Kind labels are set in capitals, with a dot between their parts. A part that comes from
         * the document is set in capitals too where it is plain words, such as `for each line` or
         * `retry on failure, up to 3 attempts`. Code keeps its case, which is part of what it
         * says: `while !done.isEmpty()`.
         */
        private fun joined(word: String, detail: String?): String = if (detail == null) word else "$word · ${capitals(detail)}"

        /**
         * [text] in capitals if it is lower-case words and numbers, with commas between them at
         * most, and nothing else, so that capitals take nothing from it.
         */
        private fun capitals(text: String): String {
            val plain = text.codePoints().allMatch { it == ' '.code || it == ','.code || Character.isDigit(it) || Character.isLowerCase(it) }
            return if (plain) text.uppercase() else text
        }
    }
}
