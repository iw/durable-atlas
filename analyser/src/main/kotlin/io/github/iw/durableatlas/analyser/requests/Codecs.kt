package io.github.iw.durableatlas.analyser.requests

import io.github.iw.durableatlas.analyser.code.Block
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.ClassLiteral
import io.github.iw.durableatlas.analyser.code.ClassReference
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Constant
import io.github.iw.durableatlas.analyser.code.If
import io.github.iw.durableatlas.analyser.code.Literal
import io.github.iw.durableatlas.analyser.code.LocalVariable
import io.github.iw.durableatlas.analyser.code.Other
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.Return
import io.github.iw.durableatlas.analyser.code.TypeRef
import io.github.iw.durableatlas.analyser.graph.Scope
import io.github.iw.durableatlas.analyser.graph.ValueTracer
import io.github.iw.durableatlas.ir.Payload
import io.github.iw.durableatlas.ir.PayloadFormat
import io.github.iw.durableatlas.ir.PayloadKind

/**
 * How the code turns objects into the bodies of requests and bodies into objects, with Jackson
 * (`ObjectMapper`, `ObjectWriter`, `ObjectReader`, and the functions of jackson-module-kotlin) or
 * with kotlinx.serialization (`Json`). A call of either library is known by the package of the
 * function it resolves to, or, where it does not resolve, by the name of the function: in a
 * degraded model neither library is on the classpath.
 */
internal class Codecs(private val tracer: ValueTracer, private val types: StaticTypes) {
    /**
     * What a body publisher sends: [publisher] is the argument of `POST`, `PUT` or `method`,
     * read in [scope] at [place].
     */
    fun sent(publisher: Code, scope: Scope, place: Place, depth: Int = 0): Payload {
        val origin = tracer.origin(publisher, scope)
        if (origin.code is If && depth < MAX_DEPTH) return chosen(origin.code, origin.scope, place, depth)
        val call = origin.code as? Call ?: return UNKNOWN
        val function = call.callee?.function
        val name = (if (function != null) function.simpleName.takeIf { function.owner == PUBLISHERS } else call.name) ?: return UNKNOWN
        return when (name) {
            NO_BODY -> Payload(PayloadKind.NONE)
            "ofString", "ofByteArray" -> call.arguments.firstOrNull()?.let { content(it.value, origin.scope, place, 0) } ?: UNKNOWN
            else -> UNKNOWN
        }
    }

    /**
     * The publisher an `if` chooses: the one its condition picks where the condition asks whether
     * text is empty or blank and the text is fixed, a template with fixed text in it, or what a
     * writer of JSON wrote; else the one both branches give, or unknown where they differ.
     */
    private fun chosen(choice: If, scope: Scope, place: Place, depth: Int): Payload {
        fun branch(code: Code?): Payload? = code?.let { (it as? Block)?.statements?.lastOrNull() ?: it }?.let { sent(it, scope, place, depth + 1) }
        val then = branch(choice.then)
        val otherwise = branch(choice.otherwise)
        return when (decide(choice.condition, scope)) {
            true -> then ?: UNKNOWN
            false -> otherwise ?: UNKNOWN
            null -> then?.takeIf { it == otherwise } ?: UNKNOWN
        }
    }

    /** The value of a condition that asks whether fixed text is empty or blank; `null` where it cannot be told. */
    private fun decide(condition: Code, scope: Scope): Boolean? {
        val call = condition as? Call ?: return null
        val asks = call.callee?.function?.simpleName ?: call.name
        if (asks !in EMPTINESS || call.arguments.isNotEmpty()) return null
        val text = tracer.origin(call.receiver ?: return null, scope).code
        val empty = when {
            // What a writer of either library writes is JSON, which is never empty.
            text is Call && writer(text) != null -> false
            text is Literal -> (text.constant as? Constant.Text)?.value?.let { if (asks.endsWith("Blank")) it.isBlank() else it.isEmpty() } ?: return null
            text is Other && text.operator == TEMPLATE ->
                if (Templates.pieces(text.text)?.any { it is Templates.Piece.Fixed && it.text.isNotBlank() } == true) false else return null
            else -> return null
        }
        return if (asks.startsWith("isNot")) !empty else empty
    }

    /** What text or bytes [code] gives that a body is made of. */
    private fun content(code: Code, scope: Scope, place: Place, depth: Int): Payload {
        if (depth > MAX_DEPTH) return UNKNOWN
        val trail = tracer.trail(code, scope)
        val origin = trail.passed.last()
        val value = origin.code
        if (value is Call) {
            writer(value)?.let { format -> return written(value, origin.scope, place, format) }
            if (value.name in TO_BYTES && value.receiver != null && value.arguments.size <= 1) return content(value.receiver, origin.scope, place, depth + 1)
        }
        if (value is Literal || value is Other && value.operator == TEMPLATE) return Payload(PayloadKind.TEXT)
        val type = types.along(trail)?.name
        return when (type) {
            STRING -> Payload(PayloadKind.TEXT)
            BYTES -> Payload(PayloadKind.TYPE, BYTES)
            else -> UNKNOWN
        }
    }

    /** What a call of a writer of either library writes. */
    private fun written(call: Call, scope: Scope, place: Place, format: PayloadFormat): Payload {
        val arguments = call.arguments
        val written = call.writtenTypeArguments ?: call.typeArguments.takeIf { call.callee == null }
        val type: TypeRef? = when (format) {
            PayloadFormat.JACKSON -> arguments.firstOrNull()?.let { typeOf(it.value, scope) }
            PayloadFormat.KOTLINX -> when {
                arguments.size >= 2 -> serializerType(arguments.first().value, scope) ?: typeOf(arguments[1].value, scope)
                else -> written?.firstOrNull()?.takeIf(types::telling) ?: arguments.firstOrNull()?.let { typeOf(it.value, scope) }
            }
        }
        val name = types.name(type, place, call.location.file)?.takeIf { type != null && types.telling(type) }
        return if (name == null) Payload(PayloadKind.UNKNOWN, format = format) else Payload(PayloadKind.TYPE, name, format = format)
    }

    /** The static type of the object [code] evaluates to; see [StaticTypes.along]. */
    private fun typeOf(code: Code, scope: Scope): TypeRef? = types.along(tracer.trail(code, scope))

    /** The library whose writer [call] calls, if it calls one. */
    fun writer(call: Call): PayloadFormat? = format(call, JACKSON_WRITERS, KOTLINX_WRITERS)

    /** The library whose reader [call] calls, if it calls one. */
    fun reader(call: Call): PayloadFormat? = format(call, JACKSON_READERS, KOTLINX_READERS)

    private fun format(call: Call, jackson: Set<String>, kotlinx: Set<String>): PayloadFormat? {
        val function = call.callee?.function
        if (function != null && call.writtenTypeArguments == null) {
            return when {
                function.name.startsWith(JACKSON) && function.simpleName in jackson -> PayloadFormat.JACKSON
                function.name.startsWith(KOTLINX) && function.simpleName in kotlinx -> PayloadFormat.KOTLINX
                else -> null
            }
        }
        return when (call.name) {
            in jackson -> PayloadFormat.JACKSON
            in kotlinx -> PayloadFormat.KOTLINX
            else -> null
        }
    }

    /**
     * The type a call of a reader reads [call]'s content as: the type it is said to give, its
     * type argument, the class or `TypeReference` it is handed, the serializer it is handed, or
     * else the type that is expected where its value goes ([expected]).
     */
    fun readType(call: Call, scope: Scope, expected: TypeRef?): TypeRef? {
        when (call.callee?.function?.simpleName ?: call.name) {
            "readTree" -> return TypeRef(JSON_NODE, JSON_NODE)
            "parseToJsonElement" -> return TypeRef(JSON_ELEMENT, JSON_ELEMENT)
        }
        val resolved = call.callee != null && call.writtenTypeArguments == null
        if (resolved) call.type?.takeIf(types::telling)?.let { return it }
        (call.writtenTypeArguments ?: call.typeArguments).firstOrNull()?.takeIf(types::telling)?.let { return it }
        for (argument in call.arguments) {
            val value = tracer.origin(argument.value, scope)
            classOf(value.code)?.let { return it }
            (value.code as? Other)?.takeIf { it.operator == OBJECT }?.type?.arguments?.firstOrNull()?.let { return it }
            serializerType(argument.value, scope)?.let { return it }
        }
        return expected?.takeIf(types::telling)
    }

    /** The class `Foo::class.java` or `Foo::class` names. */
    private fun classOf(code: Code): TypeRef? =
        when (code) {
            is ClassLiteral -> code.type
            is PropertyRead -> (code.receiver as? ClassLiteral)?.takeIf { code.name == "java" || code.name == "javaObjectType" }?.type
            else -> null
        }

    /**
     * The type a kotlinx.serialization serializer stands for: `Foo.serializer()`,
     * `serializer<Foo>()`, `ListSerializer(…)`, `SetSerializer(…)`, `MapSerializer(…, …)` and
     * `.nullable`.
     */
    private fun serializerType(code: Code, scope: Scope, depth: Int = 0): TypeRef? {
        if (depth > MAX_DEPTH) return null
        val value = tracer.origin(code, scope)
        fun inner(index: Int): TypeRef? = (value.code as? Call)?.arguments?.getOrNull(index)?.let { serializerType(it.value, value.scope, depth + 1) }
        return when (val made = value.code) {
            is Call -> when (made.name) {
                "serializer" -> (made.writtenTypeArguments ?: made.typeArguments).firstOrNull()?.takeIf(types::telling)
                    ?: classNamed(made.receiver)
                "ListSerializer" -> inner(0)?.let { collection(LIST, it) }
                "SetSerializer" -> inner(0)?.let { collection(SET, it) }
                "MapSerializer" -> inner(0)?.let { key -> inner(1)?.let { TypeRef(MAP, "$MAP<${key.text}, ${it.text}>", arguments = listOf(key, it)) } }
                else -> null
            }
            is PropertyRead -> if (made.name == "nullable") made.receiver?.let { serializerType(it, value.scope, depth + 1) }?.let { it.copy(nullable = true, text = it.text + "?") } else null
            else -> null
        }
    }

    private fun collection(name: String, item: TypeRef) = TypeRef(name, "$name<${item.text}>", arguments = listOf(item))

    /** The class [code] names where it stands for a class: `Foo` in `Foo.serializer()`. */
    private fun classNamed(code: Code?): TypeRef? =
        when (code) {
            is ClassReference -> code.name?.let { TypeRef(it.removeSuffix(".Companion"), it.removeSuffix(".Companion")) }
                ?: TypeRef(null, code.text, unresolved = code.text)
            is PropertyRead -> code.type?.name?.removeSuffix(".Companion")?.let { TypeRef(it, it) }
            else -> null
        }

    /** The type the place [code] stands in expects of its value, where it says one. */
    fun expected(parent: Code?, place: Place): TypeRef? =
        when (parent) {
            is LocalVariable -> parent.type
            is Return -> place.function?.returnType
            is Other -> parent.type.takeIf { parent.operator == "as" || parent.operator == "as?" }
            else -> null
        }

    companion object {
        private const val JACKSON = "com.fasterxml.jackson."
        private const val KOTLINX = "kotlinx.serialization."
        private const val PUBLISHERS = "java.net.http.HttpRequest.BodyPublishers"
        private const val NO_BODY = "noBody"
        private const val TEMPLATE = "template"
        private const val OBJECT = "object"
        private const val MAX_DEPTH = 8
        const val STRING = "kotlin.String"
        const val BYTES = "kotlin.ByteArray"
        private const val LIST = "kotlin.collections.List"
        private const val SET = "kotlin.collections.Set"
        private const val MAP = "kotlin.collections.Map"
        private const val JSON_NODE = "com.fasterxml.jackson.databind.JsonNode"
        private const val JSON_ELEMENT = "kotlinx.serialization.json.JsonElement"

        private val UNKNOWN = Payload(PayloadKind.UNKNOWN)

        /** What a condition may ask of text. */
        private val EMPTINESS = setOf("isEmpty", "isNotEmpty", "isBlank", "isNotBlank")

        /** Functions that turn text into bytes and keep what it is. */
        private val TO_BYTES = setOf("toByteArray", "encodeToByteArray", "getBytes")

        val JACKSON_WRITERS = setOf("writeValueAsString", "writeValueAsBytes")
        val KOTLINX_WRITERS = setOf("encodeToString")
        val JACKSON_READERS = setOf("readValue", "readTree")
        val KOTLINX_READERS = setOf("decodeFromString", "decodeFromStream", "parseToJsonElement")
    }
}
