package io.github.iw.durableatlas.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A parameter of the target of a [Request]: a computed part of its path, or a part of its query.
 *
 * Types are written as Kotlin writes them, with fully qualified names (`kotlin.String`,
 * `kotlin.collections.List<com.example.Stock>`), `?` for a nullable type and `*` for a star
 * projection. A class whose source the analysis did not have is named as the sources write it,
 * by the name an import gives it where there is one. A type of the analysed sources names its
 * entry in the inventory's `types` table by its name without type arguments.
 *
 * Property order is the key order of the JSON form.
 */
@Serializable
data class RequestParameter(
    /** For a part of the path, the name the target writes it with, inside `{…}`; for a part of the query, its key. */
    val name: String,
    @SerialName("in")
    val location: ParameterLocation,
    /** The type of the expression that gives the value, where it is computed and its type is known. */
    val type: String? = null,
    /** For a part of the query, its value as the target writes it: fixed text, `{key}` or `{name}`. */
    val value: String? = null,
)

/** Where a [RequestParameter] is in the target. */
@Serializable
enum class ParameterLocation {
    @SerialName("path")
    PATH,

    @SerialName("query")
    QUERY,
}

/** An argument of a call of a client, other than the one that says what is asked for. */
@Serializable
data class RequestArgument(
    /** The name of the parameter it is given to, where the call names it or it resolves; unset otherwise. */
    val name: String? = null,
    /** Its place among the arguments as written, from 1. */
    val position: Int,
    /** The type of the expression handed over, where it is known; see [RequestParameter] for how types are written. */
    val type: String? = null,
)

/**
 * What a request sends or what the code makes of its answer.
 *
 * Property order is the key order of the JSON form.
 */
@Serializable
data class Payload(
    val kind: PayloadKind,
    /**
     * For [PayloadKind.TYPE], the type of the object that is written into the body or read out
     * of it; see [RequestParameter] for how types are written.
     */
    val type: String? = null,
    /** The media type the code gives the body, from a `Content-Type` or `Accept` header. */
    val mediaType: String? = null,
    /** The library that writes the object or reads it, where the code shows it. */
    val format: PayloadFormat? = null,
    /**
     * For [PayloadFormat.JACKSON], the naming strategy that `quarkus.jackson.property-naming-strategy`
     * gives in the configuration of the module that makes the request, as written there.
     */
    val naming: String? = null,
)

/** What a [Payload] is. */
@Serializable
enum class PayloadKind {
    /** No body: `BodyPublishers.noBody()`, or a method that sends none. */
    @SerialName("none")
    NONE,

    /** Text that the code puts together or keeps as it is, not an object it converts. */
    @SerialName("text")
    TEXT,

    /** An object that the code converts, of [Payload.type]. */
    @SerialName("type")
    TYPE,

    /** An answer that the code does not read: it is discarded, or its body is never asked for. */
    @SerialName("not-read")
    NOT_READ,

    /** What is sent or what becomes of the answer could not be followed. */
    @SerialName("unknown")
    UNKNOWN,
}

/** The library that converts an object to a body, or a body to an object. */
@Serializable
enum class PayloadFormat {
    /** Jackson's `ObjectMapper`, with or without jackson-module-kotlin. */
    @SerialName("jackson")
    JACKSON,

    /** kotlinx.serialization's `Json`. */
    @SerialName("kotlinx")
    KOTLINX,
}

/** A value of a configuration property, as a file or the code gives it. */
@Serializable
data class ConfiguredValue(
    val key: String,
    /** The value as written; a `${…}` expression in it is kept as written. */
    val value: String,
    /** The profile the value is given for, such as `prod`; unset for the value without a profile. */
    val profile: String? = null,
    /**
     * The file that gives the value, relative to the root of the analysed repository; unset for
     * the default that the code gives where no file gives a value.
     */
    val file: String? = null,
)
