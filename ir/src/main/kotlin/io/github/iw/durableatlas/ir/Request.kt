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
     * The name of the service the request goes to, as the endpoints page and its OpenAPI
     * documents name it: for an HTTP request, the first label of the host its target starts
     * with, or more labels where two hosts would otherwise share a name, or the key or name the
     * target starts with where no host is configured; for a client call, the simple name of the
     * client. Every request of a commit is named against all the others, so that a diagram
     * needs no other document to say it. Unset in documents written before services were named.
     */
    val service: String? = null,
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
    /**
     * The parameters of [target]: each computed part of its path, by the name the target gives
     * it, and each part of its query, by its key; with the type of the expression that gives the
     * value, where it is known.
     */
    val parameters: List<RequestParameter> = emptyList(),
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
    /**
     * The constant of the sources that gives [operation], by its fully qualified name, where one
     * does: `com.example.Functions.PRICE_QUOTE`.
     */
    val constant: String? = null,
    /**
     * The arguments of a [RequestKind.CLIENT] call other than the one that says the operation,
     * in the order written, each with the type of the expression handed over.
     */
    val arguments: List<RequestArgument> = emptyList(),
    /** What an [RequestKind.HTTP] request sends as its body, where the code that builds it is seen. */
    val sends: Payload? = null,
    /** The type of what the request gives back, with simple names, where the code says it. */
    val response: String? = null,
    /** What the code makes of the answer: how it reads the body, or that it does not. */
    val receives: Payload? = null,
    /** The configuration properties the request is made with, in the order the code uses them. */
    val keys: List<String> = emptyList(),
    /**
     * The value configured for the key that [target] starts with: the host, or the start of the
     * URI, that the request goes to. A `${…}` expression in it is kept as written.
     */
    val host: String? = null,
    /**
     * Every value configured for the key that [target] starts with, and for a second key that
     * follows it directly: the value without a profile and that of the `prod` profile, each
     * from the file that gives it, or the default the code gives where no file gives one.
     * [host] is one of them.
     */
    val hosts: List<ConfiguredValue> = emptyList(),
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
