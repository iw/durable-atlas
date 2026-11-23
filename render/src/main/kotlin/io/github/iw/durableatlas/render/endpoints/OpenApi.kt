package io.github.iw.durableatlas.render.endpoints

import io.github.iw.durableatlas.ir.ConfiguredValue
import io.github.iw.durableatlas.ir.ModelKind
import io.github.iw.durableatlas.ir.ModelType
import io.github.iw.durableatlas.ir.ParameterLocation
import io.github.iw.durableatlas.ir.Payload
import io.github.iw.durableatlas.ir.PayloadKind
import io.github.iw.durableatlas.ir.StandardTypes
import io.github.iw.durableatlas.ir.TypeName
import io.github.iw.durableatlas.ir.WireNames
import io.github.iw.durableatlas.render.endpoints.EndpointSet.Context
import io.github.iw.durableatlas.render.endpoints.EndpointSet.Operation
import io.github.iw.durableatlas.render.endpoints.EndpointSet.Service
import io.github.iw.durableatlas.render.endpoints.EndpointSet.ServiceKind
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The OpenAPI 3.1 document of one service: only what the activities call, as their code calls it,
 * not the service's own description. Its servers come from the configured hosts, with the key,
 * file and profile of each; its paths, parameters, bodies and answers are what the code shows;
 * each operation names its callers in `x-used-by`; its schemas are the models of the types table,
 * with the names the library writes. A client is written with one `post` path per operation and
 * the arguments of the call as the request body, and says so in `x-durable-atlas`.
 *
 * The text is deterministic: keys in a fixed order, two spaces of indent, `\n` line endings.
 */
internal class OpenApi(private val set: EndpointSet, private val sourceLink: (String, Int) -> String?) {
    private val shapes = Shapes(set.types)

    fun write(service: Service): String {
        val schemas = Schemas()
        val paths = paths(service, schemas)
        val document = buildJsonObject {
            put("openapi", VERSION)
            putJsonObject("info") {
                put("title", service.name)
                put("version", set.commits.joinToString(", ").ifEmpty { "unknown" })
                put(
                    "description",
                    "The operations of ${service.name} that the activities call, as their code calls them; written by Durable Atlas " +
                        "from the analysis of the sources. It is not the service's own description.",
                )
            }
            if (service.kind == ServiceKind.CLIENT) {
                putJsonObject(EXTENSION) {
                    put("kind", "client")
                    service.client?.let { put("type", it) }
                    service.call?.let { put("call", it) }
                }
            }
            val servers = servers(service)
            if (servers.isNotEmpty()) put("servers", JsonArray(servers))
            put("paths", paths)
            if (schemas.isNotEmpty()) putJsonObject("components") { put("schemas", schemas.json()) }
        }
        return FORMAT.encodeToString(JsonElement.serializer(), document) + "\n"
    }

    // ---- servers

    private fun servers(service: Service): List<JsonObject> =
        service.servers.flatMap { server ->
            when {
                server.key != null && server.values.isNotEmpty() -> server.values.map { value -> server(value, server.variable) }
                server.key != null -> listOf(
                    buildJsonObject {
                        put("url", "{${server.key}}")
                        put("description", "The value of ${server.key}, which no configuration file of the module gives.")
                        putJsonObject("variables") { putJsonObject(server.key) { put("default", "") } }
                        put("x-config-key", server.key)
                    },
                )
                server.literal != null -> listOf(buildJsonObject { put("url", server.literal) })
                else -> emptyList()
            }
        }

    private fun server(value: ConfiguredValue, variable: EndpointSet.Variable?): JsonObject = buildJsonObject {
        val (url, placeholders) = expanded(value.value)
        val second = variable?.let { v -> v.values.firstOrNull { it.profile == value.profile } ?: v.values.firstOrNull { it.profile == null } ?: v.values.firstOrNull() }
        put("url", url + (variable?.let { "{${it.key}}" } ?: ""))
        put("description", listOfNotNull(value.profile?.let { "Profile $it." }, "From ${value.key}${value.file?.let { " in $it" } ?: ", as the code gives it"}.").joinToString(" "))
        if (placeholders.isNotEmpty() || variable != null) {
            putJsonObject("variables") {
                for ((name, default) in placeholders) putJsonObject(name) {
                    put("default", default)
                    put("description", "A placeholder of the configured value.")
                }
                if (variable != null) putJsonObject(variable.key) {
                    put("default", second?.value?.let { expanded(it).first }.orEmpty())
                    put("description", "The value of ${variable.key}${second?.file?.let { ", in $it" } ?: ", as the code gives it"}.")
                    put("x-config-key", variable.key)
                    second?.file?.let { put("x-config-file", it) }
                    second?.profile?.let { put("x-profile", it) }
                }
            }
        }
        put("x-config-key", value.key)
        value.file?.let { put("x-config-file", it) }
        value.profile?.let { put("x-profile", it) }
        if ('$' in value.value) put("x-config-value", value.value)
    }

    /**
     * A configured value as a URL: each `${NAME:default}` placeholder as its default, and one
     * without a default as a server variable of that name.
     */
    private fun expanded(value: String): Pair<String, List<Pair<String, String>>> {
        val placeholders = ArrayList<Pair<String, String>>()
        val url = PLACEHOLDER.replace(value) { match ->
            val inner = match.groupValues[1]
            val default = inner.substringAfter(':', "").takeIf { ':' in inner }
            if (default != null) {
                default
            } else {
                placeholders += inner to ""
                "{$inner}"
            }
        }
        return url to placeholders
    }

    // ---- paths

    private fun paths(service: Service, schemas: Schemas): JsonObject {
        val byPath = service.operations.groupBy { if (service.kind == ServiceKind.CLIENT) clientPath(it) else it.path }
        return buildJsonObject {
            for ((path, operations) in byPath.toSortedMap()) {
                putJsonObject(path) {
                    // Each method once, in the order of EndpointSet.METHODS; a method that is not known or computed under an extension.
                    for (operation in operations.sortedBy { methodOrder(it) }) {
                        val method = if (service.kind == ServiceKind.CLIENT) "post" else operation.method?.takeIf { it in EndpointSet.METHODS }?.lowercase()
                        if (method != null) {
                            put(method, operation(operation, service, schemas))
                        } else {
                            put(UNKNOWN_METHOD, operation(operation, service, schemas))
                        }
                    }
                }
            }
        }
    }

    private fun methodOrder(operation: Operation): Int = operation.method?.let { EndpointSet.METHODS.indexOf(it) }?.takeIf { it >= 0 } ?: EndpointSet.METHODS.size

    /** The path of a client's operation: its name after a `/`, with a part the code computes as a path parameter. */
    private fun clientPath(operation: Operation): String = "/" + operation.path.trimStart('/')

    private fun operation(operation: Operation, service: Service, schemas: Schemas): JsonObject = buildJsonObject {
        if (service.kind == ServiceKind.CLIENT && Targets.slots(operation.path).isEmpty()) put("operationId", operation.path)
        if (operation.method != null && operation.method !in EndpointSet.METHODS) put("x-method", operation.method)
        if (operation.constants.isNotEmpty()) put("x-constant", operation.constants.singleOrNull()?.let(::JsonPrimitive) ?: JsonArray(operation.constants.map(::JsonPrimitive)))
        val parameters = operation.parameters.map { parameter(it) }
        if (parameters.isNotEmpty()) put("parameters", JsonArray(parameters))
        if (service.kind == ServiceKind.CLIENT) {
            if (operation.arguments.isNotEmpty()) put("requestBody", arguments(operation, schemas))
        } else {
            requestBody(operation.sends, schemas)?.let { put("requestBody", it) }
        }
        putJsonObject("responses") { put("default", response(operation.receives, schemas)) }
        put("x-used-by", usedBy(operation))
    }

    private fun parameter(parameter: EndpointSet.Parameter): JsonObject = buildJsonObject {
        put("name", parameter.name)
        put("in", if (parameter.location == ParameterLocation.PATH) "path" else "query")
        put("required", true)
        put("schema", oneOf(parameter.types.map { scalar(TypeName.parse(it)) }, empty = buildJsonObject { put("type", "string") }))
        if (parameter.types.isNotEmpty()) put("x-kotlin-type", parameter.types.singleOrNull()?.let(::JsonPrimitive) ?: JsonArray(parameter.types.map(::JsonPrimitive)))
        if (parameter.values.isNotEmpty()) put("x-value", parameter.values.singleOrNull()?.let(::JsonPrimitive) ?: JsonArray(parameter.values.map(::JsonPrimitive)))
    }

    /** The schema of a parameter: a standard type as such, any other as a string. */
    private fun scalar(type: TypeName): JsonObject = Schemas().schema(type, Context(null, null), parameter = true)

    private fun arguments(operation: Operation, schemas: Schemas): JsonObject = buildJsonObject {
        put("description", "The arguments of the call other than the one that names the operation, by name, or by place where the call does not name them.")
        put("required", true)
        putJsonObject("content") {
            putJsonObject(JSON) {
                putJsonObject("schema") {
                    put("type", "object")
                    putJsonArray("required") { operation.arguments.forEach { add(argumentName(it)) } }
                    putJsonObject("properties") {
                        for (argument in operation.arguments) {
                            val types = argument.types.map(TypeName::parse)
                            put(argumentName(argument), oneOf(types.map { schemas.schema(it, Context(null, null)) }, empty = JsonObject(emptyMap())))
                        }
                    }
                }
            }
        }
    }

    private fun argumentName(argument: EndpointSet.Argument): String = argument.name ?: "argument${argument.position}"

    private fun requestBody(sends: List<Payload>, schemas: Schemas): JsonObject? {
        val bodies = sends.filter { it.kind != PayloadKind.NONE }
        if (bodies.isEmpty()) return null
        return buildJsonObject {
            val explained = bodies.mapNotNull { Shapes.explain(it, receiving = false) }.distinct()
            if (explained.isNotEmpty()) put("description", explained.joinToString(" "))
            if (sends.none { it.kind == PayloadKind.NONE }) put("required", true)
            put("content", content(bodies, schemas, receiving = false))
        }
    }

    private fun response(receives: List<Payload>, schemas: Schemas): JsonObject = buildJsonObject {
        val read = receives.filter { it.kind == PayloadKind.TYPE || it.kind == PayloadKind.TEXT }
        val words = receives.map { payload ->
            when (payload.kind) {
                PayloadKind.TYPE -> "The code reads the answer as ${TypeName.parse(payload.type.orEmpty()).short()}."
                else -> Shapes.explain(payload, receiving = true) ?: ""
            }
        }.filter { it.isNotEmpty() }.distinct()
        put("description", words.joinToString(" ").ifEmpty { "Not seen: the code that makes the request is not read." })
        if (read.isNotEmpty()) put("content", content(read, schemas, receiving = true))
    }

    /** The content of a body: per media type, the schema of each payload, as alternatives where they differ. */
    private fun content(payloads: List<Payload>, schemas: Schemas, receiving: Boolean): JsonObject {
        val byMedia = payloads.groupBy { payload ->
            payload.mediaType ?: when (payload.kind) {
                PayloadKind.TYPE -> JSON
                PayloadKind.TEXT -> TEXT
                else -> ANY
            }
        }
        return buildJsonObject {
            for ((media, group) in byMedia.toSortedMap()) {
                putJsonObject(media) {
                    val alternatives = group.mapNotNull { payload ->
                        when (payload.kind) {
                            PayloadKind.TYPE -> schemas.schema(TypeName.parse(payload.type.orEmpty()), Context(payload.format, payload.naming))
                            PayloadKind.TEXT -> buildJsonObject {
                                put("type", "string")
                                put("description", if (receiving) "Kept as text." else "Text the code puts together.")
                            }
                            else -> null
                        }
                    }.distinct()
                    if (alternatives.isNotEmpty()) put("schema", oneOf(alternatives, JsonObject(emptyMap())))
                }
            }
        }
    }

    private fun usedBy(operation: Operation): JsonArray = buildJsonArray {
        for (caller in operation.callers) {
            add(
                buildJsonObject {
                    put("activity", caller.activity)
                    caller.interfaceName?.let { put("interface", it) }
                    caller.className?.let { put("class", it) }
                    putJsonArray("workflows") { caller.workflows.forEach { add(it) } }
                    put("source", "${caller.loc.file}:${caller.loc.line}")
                    sourceLink(caller.loc.file, caller.loc.line)?.let { put("x-source-url", it) }
                },
            )
        }
    }

    private fun oneOf(schemas: List<JsonObject>, empty: JsonObject): JsonObject =
        when (schemas.distinct().size) {
            0 -> empty
            1 -> schemas.first()
            else -> buildJsonObject { put("oneOf", JsonArray(schemas.distinct())) }
        }

    /**
     * The schemas of the models a document uses, each under a name of its own: the simple name of
     * its class, with its type arguments after `_`, made longer by its package where two would
     * share it, and by the naming strategy where one model is written two ways.
     */
    private inner class Schemas {
        private val entries = LinkedHashMap<String, JsonObject>()
        private val names = HashMap<Pair<TypeName, Context>, String>()
        private val pending = ArrayDeque<Triple<TypeName, Context, String>>()

        /** Whether a schema refers to an entry; the entries themselves are written by [json]. */
        fun isNotEmpty(): Boolean = entries.isNotEmpty() || pending.isNotEmpty()

        fun json(): JsonObject {
            while (pending.isNotEmpty()) {
                val (type, context, name) = pending.removeFirst()
                entries[name] = model(type, context)
            }
            return JsonObject(entries.toSortedMap())
        }

        /** The schema of [type]; a model of the table as a reference to its entry. */
        fun schema(type: TypeName, context: Context, parameter: Boolean = false): JsonObject {
            val plain = plain(type.copy(nullable = false), context, parameter)
            return if (type.nullable) nullable(plain) else plain
        }

        private fun nullable(schema: JsonObject): JsonObject {
            val type = schema["type"]
            return when {
                type is JsonPrimitive && type.isString -> JsonObject(schema + ("type" to JsonArray(listOf(type, JsonPrimitive("null")))))
                schema.isEmpty() -> schema
                else -> buildJsonObject { putJsonArray("anyOf") { add(schema); add(buildJsonObject { put("type", "null") }) } }
            }
        }

        private fun plain(type: TypeName, context: Context, parameter: Boolean): JsonObject {
            if (type.isStar) return JsonObject(emptyMap())
            val model = set.types[type.name]
            if (model != null && !parameter) return buildJsonObject { put("\$ref", "#/components/schemas/${reference(type, context)}") }
            return when (val standard = StandardTypes.shape(type.name)) {
                is StandardTypes.Shape.Scalar -> buildJsonObject {
                    put("type", standard.type)
                    standard.format?.let { put("format", it) }
                    standard.contentEncoding?.let { put("contentEncoding", it) }
                }
                is StandardTypes.Shape.Array -> buildJsonObject {
                    put("type", "array")
                    if (standard.unique) put("uniqueItems", true)
                    (type.arguments.firstOrNull() ?: standard.items)?.let { put("items", schema(it, context, parameter)) }
                }
                StandardTypes.Shape.Map -> buildJsonObject {
                    put("type", "object")
                    type.arguments.getOrNull(1)?.let { put("additionalProperties", schema(it, context, parameter)) }
                }
                StandardTypes.Shape.AnyObject -> buildJsonObject { put("type", "object") }
                StandardTypes.Shape.Any -> JsonObject(emptyMap())
                null -> when {
                    parameter && model?.kind == ModelKind.ENUM -> buildJsonObject {
                        put("type", "string")
                        putJsonArray("enum") { model.values.forEach { add(WireNames.value(it, null)) } }
                    }
                    parameter -> buildJsonObject { put("type", "string") }
                    // A class the types table does not have: a type parameter left open, or a class not reached.
                    else -> buildJsonObject {
                        put("description", "Not read: ${type.short()} is not among the models of the analysis.")
                        put(KOTLIN_TYPE, type.toString())
                    }
                }
            }
        }

        private fun reference(type: TypeName, context: Context): String {
            names[type to context]?.let { return it }
            val base = componentName(type)
            var name = base
            // The same model written another way gets the naming strategy or library after its name.
            if (name in entries || pending.any { it.third == name } || names.values.contains(name)) {
                name = "${base}_${(context.naming ?: context.format?.name?.lowercase() ?: "plain").replace(Regex("[^A-Za-z0-9._-]"), "_")}"
            }
            var n = 2
            val wanted = name
            while (names.values.contains(name)) name = "${wanted}_${n++}"
            names[type to context] = name
            pending += Triple(type, context, name)
            return name
        }

        /** The name of a component: the simple names of the class and its arguments, the package where two classes share a simple name. */
        private fun componentName(type: TypeName): String {
            fun part(name: TypeName): String {
                val simple = if (set.types.keys.count { it.substringAfterLast('.') == name.simpleName } > 1) name.name else name.name.substringAfterLast('.')
                val own = if (set.types.containsKey(name.name)) nestedName(name.name, simple) else simple
                return (listOf(own) + name.arguments.map(::part)).joinToString("_")
            }
            return part(type).replace(Regex("[^A-Za-z0-9._-]"), "_")
        }

        /** A nested class by its outer classes and its own name, `OrderEvent.Placed`, where the table has the outer class. */
        private fun nestedName(name: String, simple: String): String {
            if (simple.contains('.')) return simple
            var outer = name.substringBeforeLast('.', "")
            var shown = simple
            while (outer.isNotEmpty() && set.types.containsKey(outer)) {
                shown = outer.substringAfterLast('.') + "." + shown
                outer = outer.substringBeforeLast('.', "")
            }
            return shown
        }

        private fun model(type: TypeName, context: Context): JsonObject {
            val model: ModelType = set.types.getValue(type.name)
            return buildJsonObject {
                when (model.kind) {
                    ModelKind.OBJECT -> {
                        put("type", "object")
                        put(KOTLIN_TYPE, type.toString())
                        val fields = shapes.fields(type, model, context)
                        val required = fields.filter { !it.optional }.map { it.name }.distinct()
                        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
                        putJsonObject("properties") {
                            for (field in fields.distinctBy { it.name }) {
                                val schema = schema(field.type, context)
                                put(field.name, if (field.name != field.kotlinName) JsonObject(withKotlinName(schema, field.kotlinName)) else schema)
                            }
                        }
                    }
                    ModelKind.ENUM -> {
                        put("type", "string")
                        put(KOTLIN_TYPE, type.toString())
                        putJsonArray("enum") { model.values.forEach { add(WireNames.value(it, context.format)) } }
                    }
                    ModelKind.ALTERNATIVES -> {
                        put(KOTLIN_TYPE, type.toString())
                        val shape = shapes.of(type, context) as Shapes.Shape.Alternatives
                        val references = shape.subtypes.map { (subtype, value) -> value to schema(TypeName(subtype), context) }
                        putJsonArray("oneOf") { references.forEach { add(it.second) } }
                        val discriminator = shape.discriminator
                        if (discriminator != null && references.all { it.second.containsKey("\$ref") }) {
                            putJsonObject("discriminator") {
                                put("propertyName", discriminator)
                                putJsonObject("mapping") {
                                    for ((value, reference) in references) put(value, reference.getValue("\$ref"))
                                }
                            }
                        }
                    }
                    ModelKind.NOT_READ -> {
                        put("description", "Not read: the analysis did not have the source of ${type.short()}, so its fields are not known.")
                        put(KOTLIN_TYPE, type.toString())
                    }
                }
            }
        }

        /**
         * A property's schema that also names the Kotlin property, where the name the library
         * writes differs from it. OpenAPI 3.1 allows keywords beside a `$ref`.
         */
        private fun withKotlinName(schema: JsonObject, name: String): Map<String, JsonElement> = schema + (KOTLIN_NAME to JsonPrimitive(name))
    }

    companion object {
        const val VERSION = "3.1.0"
        const val EXTENSION = "x-durable-atlas"
        const val KOTLIN_TYPE = "x-kotlin-type"
        const val KOTLIN_NAME = "x-kotlin-name"
        const val UNKNOWN_METHOD = "x-unknown-method"
        private const val JSON = "application/json"
        private const val TEXT = "text/plain"
        private const val ANY = "*/*"
        private val PLACEHOLDER = Regex("\\$\\{([^}]*)}")

        @OptIn(ExperimentalSerializationApi::class)
        private val FORMAT = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
