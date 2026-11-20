package io.github.iw.durableatlas.render.layout

import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.Request
import io.github.iw.durableatlas.ir.RequestKind

/** The words for what an activity asks of other services: on its card, in its tooltip, in the tables of a page. */
internal object RequestTexts {
    /** What a request is, short: `POST {services.inventory.url}/reservations/{id}`, `ServiceClient · inventory-reserve`. */
    fun short(request: Request): String =
        when (request.kind) {
            RequestKind.HTTP -> listOfNotNull(request.method ?: "HTTP", request.target).joinToString(" ")
            RequestKind.CLIENT -> {
                val client = request.client ?: request.call ?: "client"
                request.operation?.let { "$client · $it" } ?: listOfNotNull(request.client, request.call).joinToString(".").ifEmpty { client }
            }
        }

    /** The requests of a node that runs an activity; empty for any other. */
    fun of(node: IrNode): List<Request> =
        if (node.kind == NodeKind.ACTIVITY || node.kind == NodeKind.LOCAL_ACTIVITY) node.requests else emptyList()

    /** How many requests a node makes besides the first, in words; `null` where it makes no other. */
    fun more(node: IrNode): String? = of(node).size.takeIf { it > 1 }?.let { "+${it - 1} more" }

    /** The line a node's card or Mermaid node gives its first request, cut at [max] characters, with [more] after it. */
    fun line(node: IrNode, max: Int): String? {
        val first = of(node).firstOrNull() ?: return null
        return listOfNotNull(short(first).singleLine(max), more(node)).joinToString(" ")
    }

    /** Every request of a node, each on a line of its own with its host, what it gives back and its class. */
    fun tooltip(node: IrNode): List<String> = of(node).map(::described)

    /** A request with everything a reader may ask of it: its host, what it gives back, the class that makes it. */
    fun described(request: Request): String =
        listOfNotNull(
            short(request),
            request.host?.let { "host $it" },
            request.response?.let { "response $it" },
            request.className?.let { "in ${it.substringAfterLast('.')}" },
        ).joinToString(" · ").singleLine()
}
