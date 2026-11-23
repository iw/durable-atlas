package io.github.iw.durableatlas.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The shape of a type that requests send or receive, as the analysis read it from the sources:
 * an entry of the inventory's `types` table, which is keyed by the fully qualified name of the
 * class without type arguments. What a serialiser writes of it depends on the library that
 * converts it; the entry says what both Jackson and kotlinx.serialization are told by the
 * sources, and [WireNames] works out the names each writes.
 *
 * Property order is the key order of the JSON form.
 */
@Serializable
data class ModelType(
    val kind: ModelKind,
    /** The names of the class's type parameters, in order, which [properties] may use as types. */
    val typeParameters: List<String> = emptyList(),
    /**
     * For [ModelKind.OBJECT], the properties a serialiser writes, in the order of the class:
     * those of the primary constructor, then the public properties with a backing field, those
     * marked `@JsonIgnore` or `@Transient` left out.
     */
    val properties: List<ModelProperty> = emptyList(),
    /** For [ModelKind.ENUM], the entries in the order of the class. */
    val values: List<ModelValue> = emptyList(),
    /** For [ModelKind.ALTERNATIVES], the types it may be, in the order the sources give them. */
    val subtypes: List<ModelSubtype> = emptyList(),
    /**
     * For [ModelKind.ALTERNATIVES], the property that Jackson writes the type in, as
     * `@JsonTypeInfo` gives it (its `property`, or the default of its `use`); unset where it is
     * not written, or the type is written without one.
     */
    val jsonDiscriminator: String? = null,
    /** For [ModelKind.ALTERNATIVES], the property that `@JsonClassDiscriminator` names for kotlinx.serialization. */
    val serialDiscriminator: String? = null,
    /**
     * The naming strategy `@JsonNaming` gives the class, by the simple name of the strategy
     * class as written, such as `SnakeCaseStrategy`.
     */
    val jsonNaming: String? = null,
    /** The name `@SerialName` gives the class itself. */
    val serialName: String? = null,
    /** The name `@JsonTypeName` gives the class itself. */
    val jsonName: String? = null,
    /** Where the class is declared; unset for a class that is not of the analysed sources. */
    val loc: SourceLocation? = null,
)

/** What kind of type a [ModelType] is. */
@Serializable
enum class ModelKind {
    /** A class whose properties are written as the fields of an object. */
    @SerialName("object")
    OBJECT,

    /** An enum class, written as the name of an entry. */
    @SerialName("enum")
    ENUM,

    /** A sealed class or interface, or one with `@JsonSubTypes`: one of [ModelType.subtypes]. */
    @SerialName("alternatives")
    ALTERNATIVES,

    /** A class whose source the analysis did not have, such as one from a jar: known by its name alone. */
    @SerialName("not-read")
    NOT_READ,
}

/** A property of a [ModelType]. */
@Serializable
data class ModelProperty(
    /** The name of the property in Kotlin. */
    val name: String,
    /** Its type, nullable where it is marked `?`; see [RequestParameter] for how types are written. */
    val type: String,
    /** True if the property has a default value, so that an object may be read without it. */
    val optional: Boolean = false,
    /** The name `@JsonProperty` gives it, on the property or any of its use-site targets. */
    val jsonName: String? = null,
    /** The name `@SerialName` gives it. */
    val serialName: String? = null,
)

/** An entry of an enum [ModelType]. */
@Serializable
data class ModelValue(
    val name: String,
    /** The name `@JsonProperty` gives the entry. */
    val jsonName: String? = null,
    /** The name `@SerialName` gives the entry. */
    val serialName: String? = null,
)

/** One of the types a [ModelKind.ALTERNATIVES] type may be. */
@Serializable
data class ModelSubtype(
    /** The type, by its fully qualified name. */
    val type: String,
    /** The name `@JsonSubTypes.Type` gives it for Jackson, or `@JsonTypeName` on the class. */
    val jsonName: String? = null,
    /** The name `@SerialName` on the class gives it for kotlinx.serialization. */
    val serialName: String? = null,
)
