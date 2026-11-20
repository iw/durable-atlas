package io.github.iw.durableatlas.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A request to another service that the implementation of an activity type makes, as read from
 * its code: an HTTP request sent with `java.net.http`, or a call of a client that the run was
 * told of. A part of it that the code computes is written as `{name}`, by the name the code
 * gives it; a part that comes from a configuration property is written as `{key}`.
 *
 * Property order is the key order of the JSON form.
 */
@Serializable
data class Request(
    val kind: RequestKind,
    /**
     * The HTTP method of an [RequestKind.HTTP] request: `GET`, `POST` and so on, or the
     * expression that gives it as `{name}` where it is computed. Unset where the code that
     * builds the request is not seen or does not say it: a builder kept in a variable may be
     * told it in a statement of its own, which is not read.
     */
    val method: String? = null,
    /**
     * The URI of an [RequestKind.HTTP] request: its constant parts as written, each part from a
     * configuration property as `{key}`, and each other computed part as `{name}`.
     */
    val target: String? = null,
    /** The simple name of the class of a [RequestKind.CLIENT] call, where it is known. */
    val client: String? = null,
    /** The name of the function a [RequestKind.CLIENT] call calls. */
    val call: String? = null,
    /**
     * What a [RequestKind.CLIENT] call asks for: the value of the argument the run named, as
     * text where it is constant, as `{key}` where it comes from a configuration property, and
     * as `{name}` where it is computed.
     */
    val operation: String? = null,
    /** The type of what the request gives back, with simple names, where the code says it. */
    val response: String? = null,
    /** The configuration properties the request is made with, in the order the code uses them. */
    val keys: List<String> = emptyList(),
    /**
     * The value configured for the key that [target] starts with: the host, or the start of the
     * URI, that the request goes to. A `${…}` expression in it is kept as written.
     */
    val host: String? = null,
    /**
     * The fully qualified name of the class that makes the request, where several classes of the
     * sources implement the activity type.
     */
    @SerialName("class")
    val className: String? = null,
    /** Where the request is made: the call that sends it, or the call of the client. */
    val loc: SourceLocation,
)

/**
 * This document with no [IrNode.requests]: what it is without what its activities ask of other
 * services. The document itself where it has none.
 */
fun IrDocument.withoutRequests(): IrDocument =
    if (nodes.none { it.requests.isNotEmpty() }) this else copy(nodes = nodes.map { if (it.requests.isEmpty()) it else it.copy(requests = emptyList()) })

/** What a [Request] is made with. */
@Serializable
enum class RequestKind {
    /** An HTTP request sent with `java.net.http.HttpClient`. */
    @SerialName("http")
    HTTP,

    /** A call of a client that the run was told of with `--client`. */
    @SerialName("client")
    CLIENT,
}
