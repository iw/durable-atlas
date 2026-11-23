package io.github.iw.durableatlas.render.layout

import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.Payload
import io.github.iw.durableatlas.ir.PayloadKind
import io.github.iw.durableatlas.ir.Request
import io.github.iw.durableatlas.ir.RequestKind
import io.github.iw.durableatlas.ir.ServiceNames
import io.github.iw.durableatlas.ir.TypeName

/** The words for what an activity asks of other services: on its card, in its tooltip, in the tables of a page. */
internal object RequestTexts {
    /** What a request is, in full: `POST {services.inventory.url}/reservations/{id}`, `ServiceClient · inventory-reserve`. */
    fun short(request: Request): String =
        when (request.kind) {
            RequestKind.HTTP -> listOfNotNull(request.method ?: "HTTP", request.target).joinToString(" ")
            RequestKind.CLIENT -> {
                val client = request.client ?: request.call ?: "client"
                request.operation?.let { "$client · $it" } ?: listOfNotNull(request.client, request.call).joinToString(".").ifEmpty { client }
            }
        }

    /**
     * What a request is, in a few characters: the method and the service, `POST billing`, or `FN`
     * and the operation of a client, `FN pricing-quote`. The service is the one the document
     * names, or, in a document that names none, the one the requests of the document name.
     */
    fun brief(request: Request, document: List<Request>): String =
        when (request.kind) {
            RequestKind.HTTP -> {
                val method = request.method?.takeIf { it.none { char -> char == '{' } } ?: "HTTP"
                "$method ${request.service ?: ServiceNames.of(document)[request] ?: "?"}"
            }
            RequestKind.CLIENT -> "FN ${request.operation ?: request.call ?: "?"}"
        }

    /** The requests of a node that runs an activity; empty for any other. */
    fun of(node: IrNode): List<Request> =
        if (node.kind == NodeKind.ACTIVITY || node.kind == NodeKind.LOCAL_ACTIVITY) node.requests else emptyList()

    /** How many requests a node makes besides the first: `+2`; `null` where it makes no other. */
    fun more(node: IrNode): String? = of(node).size.takeIf { it > 1 }?.let { "+${it - 1}" }

    /** The line a node's card or Mermaid node gives its first request, briefly, cut at [max] characters, with [more] after it. */
    fun line(node: IrNode, max: Int, document: List<Request> = node.requests): String? {
        val first = of(node).firstOrNull() ?: return null
        return listOfNotNull(brief(first, document).singleLine(max), more(node)).joinToString(" ")
    }

    /** Every request of a node, each on a line of its own with its host, what it gives back and its class. */
    fun tooltip(node: IrNode): List<String> = of(node).map(::described)

    /**
     * A request with everything a reader may ask of it: its host, what it sends and what the code
     * reads of the answer, what it gives back, the class that makes it.
     */
    fun described(request: Request): String =
        listOfNotNull(
            short(request),
            request.host?.let { "host $it" },
            sent(request.sends)?.let { "sends $it" },
            read(request.receives),
            request.response?.let { "response $it" },
            request.className?.let { "in ${it.substringAfterLast('.')}" },
        ).joinToString(" · ").singleLine()

    private fun sent(payload: Payload?): String? =
        when (payload?.kind) {
            PayloadKind.TYPE -> payload.type?.let { TypeName.parse(it).short() }
            PayloadKind.TEXT -> "text"
            else -> null
        }

    private fun read(payload: Payload?): String? =
        when (payload?.kind) {
            PayloadKind.TYPE -> payload.type?.let { "reads ${TypeName.parse(it).short()}" }
            PayloadKind.TEXT -> "reads text"
            PayloadKind.NOT_READ -> "answer not read"
            else -> null
        }
}
