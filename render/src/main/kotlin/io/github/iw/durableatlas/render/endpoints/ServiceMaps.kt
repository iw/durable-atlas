package io.github.iw.durableatlas.render.endpoints

import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.endpoints.EndpointSet.Service
import io.github.iw.durableatlas.render.svg.ConstructStyles
import io.github.iw.durableatlas.render.svg.StyleKinds
import io.github.iw.durableatlas.render.svg.Xml

/**
 * The small map at the head of a service's section: the activities that call the service, drawn
 * as the diagrams draw an activity, on the left; its operations, each with its method, on the
 * right; a line from each activity to each operation it calls. The places are worked out here,
 * not by a layout engine: the operations stand in the order of the section, and each activity
 * as near the middle of the operations it calls as the ones before it leave room for, so that
 * the lines cross little. Everything is drawn from the same numbers each time.
 */
internal object ServiceMaps {
    private const val PAD = 8.0
    private const val CARD_WIDTH = 236.0
    private const val CARD_HEIGHT = 40.0
    private const val CARD_GAP = 8.0
    private const val TILE = 30.0
    private const val ROW_HEIGHT = 26.0
    private const val ROW_WIDTH = 330.0
    private const val GUTTER = 84.0
    private const val TOP = 8.0

    /** Characters that fit beside the tile of a card, and in a row beside its badge, at the sizes of the page. */
    private const val CARD_CHARACTERS = 27
    private const val ROW_CHARACTERS = 36

    fun svg(service: Service, activityIds: Map<String, String>): String {
        val operations = service.operations
        val pairs = operations.flatMap { operation -> operation.callers.map { it.activity to operation.id }.distinct() }.distinct()
        val rowOf = operations.withIndex().associate { (index, operation) -> operation.id to index }
        val activities = pairs.map { it.first }.distinct()
            .map { activity -> activity to pairs.filter { it.first == activity }.map { rowOf.getValue(it.second) }.average() }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .map { it.first }

        val rowsHeight = operations.size * ROW_HEIGHT
        // Each card wants to stand level with the middle of the rows it calls; a card never comes before the one above it.
        val wanted = activities.map { activity ->
            val rows = pairs.filter { it.first == activity }.map { rowOf.getValue(it.second) }
            TOP + (rows.average() + 0.5) * ROW_HEIGHT - CARD_HEIGHT / 2
        }
        val cardY = DoubleArray(activities.size)
        var next = TOP
        for (index in activities.indices) {
            cardY[index] = maxOf(next, wanted[index])
            next = cardY[index] + CARD_HEIGHT + CARD_GAP
        }
        val cardsBottom = if (activities.isEmpty()) TOP else cardY.last() + CARD_HEIGHT
        val height = maxOf(cardsBottom, TOP + rowsHeight) + PAD
        val rowX = PAD + CARD_WIDTH + GUTTER
        val width = rowX + ROW_WIDTH + PAD
        val style = ConstructStyles.of(StyleKinds.of(NodeKind.ACTIVITY))

        val out = StringBuilder()
        out.append("<svg class=\"map\" viewBox=\"0 0 ${n(width)} ${n(height)}\" width=\"${n(width)}\" height=\"${n(height)}\" role=\"img\" ")
            .append("aria-label=\"").append(Xml.attribute("The activities that call ${service.name} and the operations they call")).append("\">\n")
        for ((activity, operation) in pairs) {
            val y1 = cardY[activities.indexOf(activity)] + CARD_HEIGHT / 2
            val y2 = TOP + (rowOf.getValue(operation) + 0.5) * ROW_HEIGHT
            val x1 = PAD + CARD_WIDTH
            val dx = (rowX - x1) / 2
            out.append("<path class=\"edge\" d=\"M${n(x1)} ${n(y1)}C${n(x1 + dx)} ${n(y1)},${n(rowX - dx)} ${n(y2)},${n(rowX)} ${n(y2)}\"/>\n")
        }
        for ((index, activity) in activities.withIndex()) {
            val y = cardY[index]
            val calls = pairs.count { it.first == activity }
            out.append("<a href=\"#").append(Xml.attribute(activityIds.getValue(activity))).append("\">")
            out.append("<g class=\"act\"><title>").append(Xml.text("$activity calls ${if (calls == 1) "1 operation" else "$calls operations"} of ${service.name}")).append("</title>")
            out.append("<rect class=\"act-card\" x=\"${n(PAD)}\" y=\"${n(y)}\" width=\"${n(CARD_WIDTH)}\" height=\"${n(CARD_HEIGHT)}\" rx=\"6\"/>")
            out.append("<path class=\"act-tile\" d=\"M${n(PAD + 6)} ${n(y)}H${n(PAD + TILE)}V${n(y + CARD_HEIGHT)}H${n(PAD + 6)}A6 6 0 0 1 ${n(PAD)} ${n(y + CARD_HEIGHT - 6)}V${n(y + 6)}A6 6 0 0 1 ${n(PAD + 6)} ${n(y)}Z\"/>")
            out.append("<svg x=\"${n(PAD + 6)}\" y=\"${n(y + CARD_HEIGHT / 2 - 9)}\" width=\"18\" height=\"18\" viewBox=\"0 0 20 20\" class=\"act-icon\">").append(style.icon).append("</svg>")
            out.append("<text class=\"act-k\" x=\"${n(PAD + TILE + 9)}\" y=\"${n(y + 15)}\">ACTIVITY</text>")
            out.append("<text class=\"act-name\" x=\"${n(PAD + TILE + 9)}\" y=\"${n(y + 31)}\">").append(Xml.text(clip(activity, CARD_CHARACTERS))).append("</text>")
            out.append("</g></a>\n")
        }
        for ((index, operation) in operations.withIndex()) {
            val y = TOP + index * ROW_HEIGHT
            val badge = operation.badge
            val badgeWidth = 10 + badge.length * 6.6
            out.append("<a href=\"#").append(Xml.attribute(operation.id)).append("\">")
            out.append("<g class=\"op-row\"><title>").append(Xml.text(operation.title)).append("</title>")
            out.append("<rect class=\"row-bg\" x=\"${n(rowX)}\" y=\"${n(y + 1)}\" width=\"${n(ROW_WIDTH)}\" height=\"${n(ROW_HEIGHT - 2)}\" rx=\"5\"/>")
            out.append("<rect class=\"b-${badge.lowercase()}\" x=\"${n(rowX + 8)}\" y=\"${n(y + 5.5)}\" width=\"${n(badgeWidth)}\" height=\"15\" rx=\"3\"/>")
            out.append("<text class=\"bt t-${badge.lowercase()}\" x=\"${n(rowX + 8 + badgeWidth / 2)}\" y=\"${n(y + 16.5)}\" text-anchor=\"middle\">").append(badge).append("</text>")
            out.append("<text class=\"row-t\" x=\"${n(rowX + 14 + badgeWidth)}\" y=\"${n(y + 17)}\">")
                .append(Xml.text(clip(operation.path, ROW_CHARACTERS - badge.length))).append("</text>")
            out.append("</g></a>\n")
        }
        out.append("</svg>")
        return out.toString()
    }

    /** [text] cut to [max] characters, with an ellipsis where it is cut. */
    fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(maxOf(1, max - 1)) + "…"

    private fun n(value: Double): String {
        val rounded = Math.round(value * 10) / 10.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }
}
