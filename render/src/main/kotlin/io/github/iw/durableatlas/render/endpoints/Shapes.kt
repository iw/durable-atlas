package io.github.iw.durableatlas.render.endpoints

import io.github.iw.durableatlas.ir.ModelKind
import io.github.iw.durableatlas.ir.ModelType
import io.github.iw.durableatlas.ir.Payload
import io.github.iw.durableatlas.ir.PayloadFormat
import io.github.iw.durableatlas.ir.PayloadKind
import io.github.iw.durableatlas.ir.StandardTypes
import io.github.iw.durableatlas.ir.TypeName
import io.github.iw.durableatlas.ir.WireNames

/**
 * What the page and the OpenAPI documents show of a type: the fields of a model with the names a
 * library writes them with, the values of an enum, the alternatives of a sealed type; the item of
 * a collection or the value of a map in place of the collection or map.
 */
internal class Shapes(private val types: Map<String, ModelType>) {
    /** A field of an object as one library writes it. */
    class Field(val name: String, val kotlinName: String, val type: TypeName, val optional: Boolean)

    /** What is shown of a type in a panel. */
    sealed interface Shape {
        /** An object of [model], with its [fields] for the type arguments it is used with. */
        class Object(val model: String, val fields: List<Field>) : Shape

        class Enum(val model: String, val values: List<String>) : Shape

        /** One of [subtypes], told apart by [discriminator] where the sources write one. */
        class Alternatives(val model: String, val subtypes: List<Pair<String, String>>, val discriminator: String?) : Shape

        /** A type of the table whose source the analysis did not have. */
        class NotRead(val model: String) : Shape
    }

    /**
     * The shape a panel shows for [type]: that of its class where the table has it, else that of
     * the item of a collection or the value of a map; `null` for a standard type or one the table
     * does not have.
     */
    fun of(type: TypeName, context: EndpointSet.Context, depth: Int = 0): Shape? {
        if (depth > MAX_DEPTH || type.isStar) return null
        val model = types[type.name]
        if (model != null) return shape(type, model, context)
        return when (val standard = StandardTypes.shape(type.name)) {
            is StandardTypes.Shape.Array -> (type.arguments.firstOrNull() ?: standard.items)?.let { of(it, context, depth + 1) }
            is StandardTypes.Shape.Map -> type.arguments.getOrNull(1)?.let { of(it, context, depth + 1) }
            else -> null
        }
    }

    private fun shape(type: TypeName, model: ModelType, context: EndpointSet.Context): Shape =
        when (model.kind) {
            ModelKind.OBJECT -> Shape.Object(type.name, fields(type, model, context))
            ModelKind.ENUM -> Shape.Enum(type.name, model.values.map { WireNames.value(it, context.format) })
            ModelKind.ALTERNATIVES -> Shape.Alternatives(
                type.name,
                model.subtypes.map { it.type to discriminatorValue(it.type, it.jsonName, it.serialName, context.format) },
                discriminator(model, context.format),
            )
            ModelKind.NOT_READ -> Shape.NotRead(type.name)
        }

    /** The fields of an object of [model], with the type parameters of the class bound to the arguments of [type]. */
    fun fields(type: TypeName, model: ModelType, context: EndpointSet.Context): List<Field> {
        val bindings = model.typeParameters.zip(type.arguments).toMap()
        return model.properties.map { property ->
            Field(
                name = WireNames.property(model, property, context.format, context.naming),
                kotlinName = property.name,
                type = TypeName.parse(property.type).applied(bindings),
                optional = property.optional,
            )
        }
    }

    /**
     * The property that tells the alternatives apart, as the library of [format] is told it:
     * Jackson's where `@JsonTypeInfo` writes it; kotlinx.serialization's as `@JsonClassDiscriminator`
     * gives it, or its own default.
     */
    fun discriminator(model: ModelType, format: PayloadFormat?): String? =
        when (format) {
            PayloadFormat.JACKSON -> model.jsonDiscriminator
            // kotlinx.serialization writes a sealed class with the type in a property of this name, unless told another.
            PayloadFormat.KOTLINX -> model.serialDiscriminator ?: KOTLINX_DISCRIMINATOR
            null -> model.jsonDiscriminator ?: model.serialDiscriminator
        }

    /** The value the discriminator has for one alternative, as the library of [format] writes it. */
    fun discriminatorValue(type: String, jsonName: String?, serialName: String?, format: PayloadFormat?): String {
        val entry = types[type]
        return when (format) {
            PayloadFormat.JACKSON -> jsonName ?: entry?.jsonName ?: type.substringAfterLast('.')
            PayloadFormat.KOTLINX -> serialName ?: entry?.serialName ?: type
            null -> jsonName ?: serialName ?: entry?.jsonName ?: entry?.serialName ?: type.substringAfterLast('.')
        }
    }

    companion object {
        private const val MAX_DEPTH = 8

        /** The property kotlinx.serialization writes the type of a sealed class in, unless told another. */
        const val KOTLINX_DISCRIMINATOR = "type"

        /** What a payload is, in words: the type, text, no body, not read, unknown. */
        fun describe(payload: Payload?, client: Boolean = false): String =
            when (payload?.kind) {
                null -> if (client) "No arguments" else "Not seen"
                PayloadKind.NONE -> "No body"
                PayloadKind.TEXT -> "Text"
                PayloadKind.TYPE -> TypeName.parse(payload.type.orEmpty()).short()
                PayloadKind.NOT_READ -> "Not read"
                PayloadKind.UNKNOWN -> "Unknown"
            }

        /** What the page says of a payload besides its type: the media type, the library, the naming strategy. */
        fun facts(payload: Payload): List<String> = listOfNotNull(
            payload.mediaType,
            payload.format?.let { if (it == PayloadFormat.JACKSON) "Jackson" else "kotlinx.serialization" },
            payload.naming?.let { "naming $it" },
        )

        /** What a payload that is not a type means, for whoever reads the page. */
        fun explain(payload: Payload?, receiving: Boolean): String? =
            when (payload?.kind) {
                PayloadKind.NONE -> "The request sends no body."
                PayloadKind.TEXT -> if (receiving) "The code keeps the answer as text and does not convert it." else "The code puts the body together as text; its structure is not read."
                PayloadKind.NOT_READ -> "The code does not read the answer: it is discarded, or its body is never asked for."
                PayloadKind.UNKNOWN -> if (receiving) "What the code makes of the answer could not be followed." else "A body is sent, but where it comes from could not be followed."
                PayloadKind.TYPE -> null
                null -> if (receiving) null else "The code that builds the request is not seen."
            }
    }
}
