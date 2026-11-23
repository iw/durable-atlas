package io.github.iw.durableatlas.ir

/**
 * A type as requests and the types table write it (see [RequestParameter]): a class name with
 * type arguments, `?` where it is nullable, or `*` for a star projection. A text that is not of
 * that form, such as a function type, is one name as it stands.
 */
data class TypeName(val name: String, val arguments: List<TypeName> = emptyList(), val nullable: Boolean = false) {
    /** The name without package and outer classes' packages: `com.example.Order.Line` gives `Line`. */
    val simpleName: String get() = name.substringAfterLast('.')

    val isStar: Boolean get() = name == STAR

    /** The type with its type parameters replaced by the types [bindings] give them. */
    fun applied(bindings: Map<String, TypeName>): TypeName {
        if (arguments.isEmpty()) {
            val bound = bindings[name] ?: return this
            return if (nullable) bound.copy(nullable = true) else bound
        }
        return copy(arguments = arguments.map { it.applied(bindings) })
    }

    /** The type with simple names throughout: `List<Stock>?`. */
    fun short(): String = write { it.simpleName }

    override fun toString(): String = write { it.name }

    private fun write(name: (TypeName) -> String): String = buildString {
        append(name(this@TypeName))
        if (arguments.isNotEmpty()) arguments.joinTo(this, ", ", "<", ">") { it.write(name) }
        if (nullable) append('?')
    }

    companion object {
        const val STAR = "*"

        /** Reads [text]; never fails, since a text it cannot take apart is a name of its own. */
        fun parse(text: String): TypeName {
            val trimmed = text.trim()
            return Parser(trimmed).type()?.takeIf { it.second == trimmed.length }?.first ?: TypeName(trimmed)
        }
    }

    private class Parser(private val text: String) {
        /** A type starting at [start], and where it ends; `null` where there is none. */
        fun type(start: Int = 0): Pair<TypeName, Int>? {
            var i = skip(start)
            if (text.startsWith(STAR, i)) return TypeName(STAR) to skip(i + 1)
            for (variance in VARIANCES) if (text.startsWith(variance, i)) i = skip(i + variance.length)
            val nameStart = i
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] in NAME_CHARACTERS)) i++
            if (i == nameStart) return null
            val name = text.substring(nameStart, i)
            i = skip(i)
            val arguments = ArrayList<TypeName>()
            if (i < text.length && text[i] == '<') {
                i++
                while (true) {
                    val (argument, end) = type(i) ?: return null
                    arguments += argument
                    i = skip(end)
                    if (i >= text.length) return null
                    if (text[i] == ',') {
                        i++
                        continue
                    }
                    if (text[i] == '>') {
                        i++
                        break
                    }
                    return null
                }
            }
            i = skip(i)
            val nullable = i < text.length && text[i] == '?'
            if (nullable) i++
            return TypeName(name, arguments, nullable) to skip(i)
        }

        private fun skip(from: Int): Int {
            var i = from
            while (i < text.length && text[i] == ' ') i++
            return i
        }

        private companion object {
            val VARIANCES = listOf("in ", "out ")
            val NAME_CHARACTERS = setOf('.', '_', '$', '`')
        }
    }
}

/**
 * The types that the JDK and the Kotlin standard library give and that serialisers write in a
 * fixed way: they are no entries of the types table. Each says what it is in JSON and which
 * OpenAPI type and format describe it.
 */
object StandardTypes {
    /** How a standard type is written in JSON. */
    sealed interface Shape {
        /** A single value: [type] and [format] as OpenAPI 3.1 and JSON Schema 2020-12 name them. */
        data class Scalar(val type: String, val format: String? = null, val contentEncoding: String? = null) : Shape

        /** An array of its one type argument, or of [items] where the type has none. */
        data class Array(val unique: Boolean = false, val items: TypeName? = null) : Shape

        /** An object whose fields are named by the keys and hold values of its second type argument. */
        data object Map : Shape

        /** Any JSON value. */
        data object Any : Shape

        /** A JSON object of any fields. */
        data object AnyObject : Shape
    }

    private val PRIMITIVE_ARRAYS = listOf(
        "kotlin.IntArray" to "kotlin.Int", "kotlin.LongArray" to "kotlin.Long", "kotlin.ShortArray" to "kotlin.Short",
        "kotlin.DoubleArray" to "kotlin.Double", "kotlin.FloatArray" to "kotlin.Float", "kotlin.BooleanArray" to "kotlin.Boolean",
        "kotlin.CharArray" to "kotlin.Char",
    )

    private val string = Shape.Scalar("string")
    private val dateTime = Shape.Scalar("string", "date-time")

    private val shapes: Map<String, Shape> = buildMap {
        for (name in listOf("kotlin.String", "java.lang.String", "kotlin.Char", "java.lang.Character", "kotlin.CharSequence", "java.lang.CharSequence")) put(name, string)
        put("kotlin.Boolean", Shape.Scalar("boolean"))
        put("java.lang.Boolean", Shape.Scalar("boolean"))
        put("kotlin.Byte", Shape.Scalar("integer", "int8"))
        put("java.lang.Byte", Shape.Scalar("integer", "int8"))
        put("kotlin.Short", Shape.Scalar("integer", "int16"))
        put("java.lang.Short", Shape.Scalar("integer", "int16"))
        put("kotlin.Int", Shape.Scalar("integer", "int32"))
        put("java.lang.Integer", Shape.Scalar("integer", "int32"))
        put("kotlin.Long", Shape.Scalar("integer", "int64"))
        put("java.lang.Long", Shape.Scalar("integer", "int64"))
        put("kotlin.UByte", Shape.Scalar("integer", "uint8"))
        put("kotlin.UShort", Shape.Scalar("integer", "uint16"))
        put("kotlin.UInt", Shape.Scalar("integer", "uint32"))
        put("kotlin.ULong", Shape.Scalar("integer", "uint64"))
        put("kotlin.Float", Shape.Scalar("number", "float"))
        put("java.lang.Float", Shape.Scalar("number", "float"))
        put("kotlin.Double", Shape.Scalar("number", "double"))
        put("java.lang.Double", Shape.Scalar("number", "double"))
        put("kotlin.Number", Shape.Scalar("number"))
        put("java.lang.Number", Shape.Scalar("number"))
        put("java.math.BigDecimal", Shape.Scalar("number", "decimal"))
        put("java.math.BigInteger", Shape.Scalar("integer"))
        put("java.util.UUID", Shape.Scalar("string", "uuid"))
        put("kotlin.uuid.Uuid", Shape.Scalar("string", "uuid"))
        put("java.net.URI", Shape.Scalar("string", "uri"))
        put("java.net.URL", Shape.Scalar("string", "uri"))
        put("kotlin.ByteArray", Shape.Scalar("string", contentEncoding = "base64"))
        put("java.time.Instant", dateTime)
        put("java.time.OffsetDateTime", dateTime)
        put("java.time.ZonedDateTime", dateTime)
        put("java.util.Date", dateTime)
        put("java.time.LocalDate", Shape.Scalar("string", "date"))
        put("java.time.OffsetTime", Shape.Scalar("string", "time"))
        put("java.time.Duration", Shape.Scalar("string", "duration"))
        put("kotlin.time.Duration", Shape.Scalar("string", "duration"))
        put("kotlin.time.Instant", dateTime)
        put("kotlinx.datetime.Instant", dateTime)
        put("kotlinx.datetime.LocalDate", Shape.Scalar("string", "date"))
        for (name in listOf("LocalDateTime", "LocalTime", "Year", "YearMonth", "MonthDay", "Period", "ZoneId", "ZoneOffset")) put("java.time.$name", string)
        put("java.time.DayOfWeek", string)
        put("java.time.Month", string)
        put("java.util.Currency", string)
        put("java.util.Locale", string)
        for (name in listOf("List", "MutableList", "Collection", "MutableCollection", "Iterable", "MutableIterable")) put("kotlin.collections.$name", Shape.Array())
        for (name in listOf("Set", "MutableSet")) put("kotlin.collections.$name", Shape.Array(unique = true))
        put("kotlin.collections.ArrayList", Shape.Array())
        put("kotlin.collections.HashSet", Shape.Array(unique = true))
        put("kotlin.collections.LinkedHashSet", Shape.Array(unique = true))
        for (name in listOf("List", "ArrayList", "LinkedList", "Collection", "Queue", "Deque", "ArrayDeque")) put("java.util.$name", Shape.Array())
        for (name in listOf("Set", "HashSet", "LinkedHashSet", "SortedSet", "TreeSet")) put("java.util.$name", Shape.Array(unique = true))
        put("kotlin.Array", Shape.Array())
        for ((name, item) in PRIMITIVE_ARRAYS) put(name, Shape.Array(items = TypeName(item)))
        for (name in listOf("Map", "MutableMap")) put("kotlin.collections.$name", Shape.Map)
        put("kotlin.collections.HashMap", Shape.Map)
        put("kotlin.collections.LinkedHashMap", Shape.Map)
        for (name in listOf("Map", "HashMap", "LinkedHashMap", "SortedMap", "TreeMap")) put("java.util.$name", Shape.Map)
        put("kotlin.Any", Shape.Any)
        put("java.lang.Object", Shape.Any)
        put("kotlinx.serialization.json.JsonElement", Shape.Any)
        put("kotlinx.serialization.json.JsonPrimitive", Shape.Any)
        put("kotlinx.serialization.json.JsonObject", Shape.AnyObject)
        put("kotlinx.serialization.json.JsonArray", Shape.Array(items = TypeName("kotlin.Any")))
        put("com.fasterxml.jackson.databind.JsonNode", Shape.Any)
        put("com.fasterxml.jackson.databind.node.ObjectNode", Shape.AnyObject)
        put("com.fasterxml.jackson.databind.node.ArrayNode", Shape.Array(items = TypeName("kotlin.Any")))
    }

    /** The JSON trees of both libraries, by the simple names they are written with where their classes do not resolve. */
    private val written: Map<String, String> = listOf(
        "kotlinx.serialization.json.JsonElement", "kotlinx.serialization.json.JsonObject", "kotlinx.serialization.json.JsonArray",
        "kotlinx.serialization.json.JsonPrimitive", "com.fasterxml.jackson.databind.JsonNode", "com.fasterxml.jackson.databind.node.ObjectNode",
        "com.fasterxml.jackson.databind.node.ArrayNode",
    ).associateBy { it.substringAfterLast('.') }

    /** How the class [name] is written, if it is a standard type; a name without a package may be a JSON tree written by its simple name. */
    fun shape(name: String): Shape? = shapes[name] ?: written[name]?.let(shapes::get)

    fun isStandard(name: String): Boolean = shape(name) != null

    /** Types that say that there is nothing: no answer, or no value. */
    val NOTHING: Set<String> = setOf("kotlin.Unit", "kotlin.Nothing", "java.lang.Void", "Unit", "Nothing", "Void")

}

/**
 * The names that Jackson and kotlinx.serialization write the properties and entries of a
 * [ModelType] with. Jackson takes `@JsonProperty`, else the name the naming strategy of the class
 * (`@JsonNaming`) or else of the `ObjectMapper` makes of the Kotlin name. kotlinx.serialization
 * takes `@SerialName`, else the Kotlin name. Where the library is not known, a name an
 * annotation gives is taken, else the Kotlin name with the class's `@JsonNaming`.
 */
object WireNames {
    fun property(type: ModelType, property: ModelProperty, format: PayloadFormat?, naming: String?): String =
        when (format) {
            PayloadFormat.KOTLINX -> property.serialName ?: property.name
            PayloadFormat.JACKSON -> property.jsonName ?: strategy(type.jsonNaming ?: naming)?.let { it(property.name) } ?: property.name
            null -> property.jsonName ?: property.serialName ?: strategy(type.jsonNaming)?.let { it(property.name) } ?: property.name
        }

    fun value(value: ModelValue, format: PayloadFormat?): String =
        when (format) {
            PayloadFormat.KOTLINX -> value.serialName ?: value.name
            PayloadFormat.JACKSON -> value.jsonName ?: value.name
            null -> value.jsonName ?: value.serialName ?: value.name
        }

    /** Whether Jackson knows the naming strategy [name], as `@JsonNaming` or `quarkus.jackson.property-naming-strategy` give it. */
    fun knows(name: String): Boolean = strategy(name) != null

    /**
     * The naming strategy [name] stands for: a constant of `PropertyNamingStrategies` such as
     * `SNAKE_CASE`, or a strategy class by its simple or qualified name, nested with `.` or `$`.
     * `null` for one that is not one of Jackson's own.
     */
    fun strategy(name: String?): ((String) -> String)? {
        if (name == null) return null
        return when (name.substringAfterLast('.').substringAfterLast('$')) {
            "SNAKE_CASE", "SnakeCaseStrategy", "LowerCaseWithUnderscoresStrategy", "CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES" -> ::snakeCase
            "UPPER_SNAKE_CASE", "UpperSnakeCaseStrategy" -> { input -> snakeCase(input).uppercase() }
            "LOWER_CAMEL_CASE", "LowerCamelCaseStrategy" -> { input -> input }
            "UPPER_CAMEL_CASE", "UpperCamelCaseStrategy", "PascalCaseStrategy", "PASCAL_CASE_TO_CAMEL_CASE" ->
                { input -> input.replaceFirstChar(Char::uppercaseChar) }
            "LOWER_CASE", "LowerCaseStrategy" -> { input -> input.lowercase() }
            "KEBAB_CASE", "KebabCaseStrategy" -> { input -> separated(input, '-') }
            "LOWER_DOT_CASE", "LowerDotCaseStrategy" -> { input -> separated(input, '.') }
            else -> null
        }
    }

    /** What Jackson's `SnakeCaseStrategy` makes of a name. */
    private fun snakeCase(input: String): String {
        val result = StringBuilder(input.length * 2)
        var wasPreviousTranslated = false
        input.forEachIndexed { index, char ->
            if (index == 0 && char == '_') return@forEachIndexed
            var c = char
            if (c.isUpperCase()) {
                if (!wasPreviousTranslated && result.isNotEmpty() && result.last() != '_') result.append('_')
                c = c.lowercaseChar()
                wasPreviousTranslated = true
            } else {
                wasPreviousTranslated = false
            }
            result.append(c)
        }
        return if (result.isNotEmpty()) result.toString() else input
    }

    /** What Jackson's `KebabCaseStrategy` and `LowerDotCaseStrategy` make of a name. */
    private fun separated(input: String, separator: Char): String {
        if (input.isEmpty()) return input
        val result = StringBuilder(input.length + input.length / 2)
        var upperCount = 0
        input.forEachIndexed { index, char ->
            val lower = char.lowercaseChar()
            if (lower == char) {
                if (upperCount > 1) result.insert(result.length - 1, separator)
                upperCount = 0
            } else {
                if (upperCount == 0 && index > 0) result.append(separator)
                upperCount++
            }
            result.append(lower)
        }
        return result.toString()
    }
}
