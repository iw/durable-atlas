package io.github.iw.durableatlas.analyser.requests

import io.github.iw.durableatlas.analyser.code.AnnotationUse
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.ClassReference
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Constant
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.Literal
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.Other
import io.github.iw.durableatlas.analyser.code.PropertyDecl
import io.github.iw.durableatlas.analyser.code.PropertyId
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.ThisReference
import io.github.iw.durableatlas.analyser.code.TypeRef
import io.github.iw.durableatlas.analyser.code.WrittenAnnotations
import io.github.iw.durableatlas.analyser.code.findWritten
import io.github.iw.durableatlas.analyser.graph.Scope
import io.github.iw.durableatlas.analyser.graph.Traced
import io.github.iw.durableatlas.analyser.graph.ValueTracer
import java.net.URI

/** Where code runs: the class of the object, as far as it is known, and the function. */
internal class Place(val selfClass: String?, val function: FunctionDecl?, val typeBindings: Map<String, TypeRef> = emptyMap())

/**
 * Reads the texts that requests are made with out of the code that builds them: URIs and the
 * operations of clients. A value is followed back to what made it, as [ValueTracer] does: through
 * local variables, properties, constants and the parameters of the functions it was handed to.
 * A part that comes from a configuration property is known by its key, in the three forms
 * MicroProfile Config has: `@ConfigProperty`, `@ConfigMapping` and `Config.getValue`. A part
 * that is computed is known by its name.
 */
internal class RequestValues(private val backend: SourceBackend, private val tracer: ValueTracer, private val types: StaticTypes = StaticTypes(backend)) {
    private val properties = HashMap<PropertyId, PropertyDecl?>()
    private val constructorParameters = HashMap<String, Map<String, List<AnnotationUse>>>()

    /** The parts of the text that [code] evaluates to, run at [place]. */
    fun parts(code: Code, scope: Scope, place: Place): List<Part> = parts(code, scope, place, null, 0).joined()

    /**
     * @param site the source text that stands for [code] where it is used, if the caller knows
     *   it better than [code] tells: the text of an expression in a string template
     */
    private fun parts(code: Code, scope: Scope, place: Place, site: String?, depth: Int): List<Part> {
        if (depth > MAX_DEPTH) return listOf(Part.Name(site ?: written(code), types.name(types.of(code), place, code.location.file)))
        val trail = tracer.trail(code, scope)
        val origin = trail.passed.last()
        val here = within(place, trail.passed)
        config(origin, here, depth)?.let { return listOf(it) }
        fun name(): List<Part> {
            val read = origin.code is LocalRead || origin.code is PropertyRead
            val type = types.name(types.along(trail), here, code.location.file)
            return listOf(Part.Name(if (read || site == null) written(origin.code) else site, type))
        }
        return when (val value = origin.code) {
            is Literal -> text(value.constant)?.let { listOf(Part.Text(it)) } ?: name()
            is PropertyRead -> value.constant?.let(::text)?.let { listOf(Part.Text(it)) } ?: name()
            is Other -> when {
                value.operator == TEMPLATE -> template(value, origin.scope, here, depth) ?: name()
                value.operator == "+" && value.operands.size == 2 ->
                    value.operands.flatMap { parts(it, origin.scope, here, null, depth + 1) }
                value.operator == "?:" && value.operands.isNotEmpty() -> parts(value.operands.first(), origin.scope, here, null, depth + 1)
                else -> name()
            }
            is Call -> call(value, origin.scope, here, depth) ?: name()
            else -> name()
        }
    }

    /**
     * The place a value is read at: that of the class whose initialiser or function the way to
     * it last led into.
     */
    private fun within(place: Place, passed: List<Traced>): Place {
        var self = place.selfClass
        for (step in passed.dropLast(1)) {
            when (val code = step.code) {
                is PropertyRead -> code.property?.owner?.let { self = it }
                is Call -> code.callee?.takeIf { it.inSource }?.function?.owner?.let { self = it }
                else -> Unit
            }
        }
        return if (self == place.selfClass) place else Place(self, place.function, place.typeBindings)
    }

    private fun template(template: Other, scope: Scope, place: Place, depth: Int): List<Part>? {
        val pieces = Templates.pieces(template.text) ?: return null
        val expressions = pieces.filterIsInstance<Templates.Piece.Expression>()
        if (expressions.size != template.operands.size) return null
        var next = 0
        return pieces.flatMap { piece ->
            when (piece) {
                is Templates.Piece.Fixed -> listOf(Part.Text(piece.text))
                is Templates.Piece.Expression -> parts(template.operands[next++], scope, place, piece.source, depth + 1)
            }
        }
    }

    /** The parts of what a call of a library function gives, where it hands on or puts together text. */
    private fun call(call: Call, scope: Scope, place: Place, depth: Int): List<Part>? {
        fun of(code: Code?): List<Part>? = code?.let { parts(it, scope, place, null, depth + 1) }
        val arguments = call.arguments
        return when (call.callee?.function?.name ?: optionalFunction(call, scope)) {
            "java.net.URI.create", "java.net.URL.<init>", "java.net.URI.<init>" ->
                if (arguments.size == 1) of(arguments.single().value) else null
            "java.net.URL.toURI", "java.net.URI.toURL", "kotlin.Any.toString", "kotlin.toString",
            "kotlin.text.trim", "kotlin.text.trimStart", "java.lang.String.trim", "java.lang.String.strip",
            "$OPTIONAL.get", "$OPTIONAL.orElseThrow", "kotlin.text.lowercase", "kotlin.text.uppercase",
            -> of(call.receiver)
            "kotlin.text.trimEnd", "kotlin.text.removeSuffix" -> of(call.receiver)?.let { parts ->
                val suffix = arguments.singleOrNull()?.let { constantText(it.value, scope) }
                trimmedEnd(parts, suffix, once = call.name == "removeSuffix")
            }
            "$OPTIONAL.orElse" -> of(call.receiver)?.let { withDefault(it, arguments.firstOrNull()?.let { argument -> constantText(argument.value, scope) }) }
            "java.net.URI.resolve" -> {
                val base = of(call.receiver) ?: return null
                val reference = of(arguments.firstOrNull()?.value) ?: return null
                resolve(base, reference)
            }
            "kotlin.String.plus" -> listOfNotNull(of(call.receiver), of(arguments.firstOrNull()?.value)).flatten()
            // `"…".format(…)` formats its receiver; `String.format("…", …)` its parameter `format`.
            "kotlin.text.format" ->
                if (arguments.firstOrNull()?.parameter == "format") {
                    format(arguments.first().value, arguments.drop(1).map { it.value }, scope, place, depth)
                } else {
                    format(call.receiver, arguments.map { it.value }, scope, place, depth)
                }
            "java.lang.String.format" -> arguments.firstOrNull()?.let { format(it.value, arguments.drop(1).map { argument -> argument.value }, scope, place, depth) }
            else -> null
        }
    }

    /**
     * The function of `Optional` that a call which does not resolve stands for, where it is made
     * on what `getOptionalValue` gives: in a degraded model neither resolves.
     */
    private fun optionalFunction(call: Call, scope: Scope): String? {
        if (call.callee != null || call.name !in OPTIONAL_FUNCTIONS) return null
        val made = tracer.origin(call.receiver ?: return null, scope)
        val lookup = made.code as? Call ?: return null
        return if (lookup(lookup, made.scope) != null) "$OPTIONAL.${call.name}" else null
    }

    /** `"%s/orders/%s".format(base, id)` and `String.format(…)`, where the format is constant. */
    private fun format(format: Code?, arguments: List<Code>, scope: Scope, place: Place, depth: Int): List<Part>? {
        val pattern = format?.let { constantText(it, scope) } ?: return null
        val parts = ArrayList<Part>()
        var last = 0
        var next = 0
        for (match in SPECIFIER.findAll(pattern)) {
            parts += Part.Text(pattern.substring(last, match.range.first))
            last = match.range.last + 1
            val specifier = match.value
            when {
                specifier == "%%" -> parts += Part.Text("%")
                specifier == "%n" -> parts += Part.Text("\n")
                else -> {
                    val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: next++
                    val argument = arguments.getOrNull(index) ?: return null
                    parts += parts(argument, scope, place, null, depth + 1)
                }
            }
        }
        parts += Part.Text(pattern.substring(last))
        return parts
    }

    /**
     * What `base.resolve(reference)` gives. Where both are fixed, that is computed. Otherwise the
     * reference is put after the base: a base from configuration is taken to end where its
     * path does, so that a reference that starts with `/` goes on from it rather than replace
     * its path, as it would with a base that has a path of its own.
     */
    private fun resolve(base: List<Part>, reference: List<Part>): List<Part> {
        val fixedBase = base.joined().constant()
        val fixedReference = reference.joined().constant()
        if (fixedBase != null && fixedReference != null) {
            runCatching { URI(fixedBase).resolve(fixedReference).toString() }.getOrNull()?.let { return listOf(Part.Text(it)) }
        }
        val start = reference.firstOrNull()
        val end = base.lastOrNull()
        if (start is Part.Text && start.text.startsWith("/") && end is Part.Text && end.text.endsWith("/")) {
            return base.dropLast(1) + Part.Text(end.text.dropLast(1)) + reference
        }
        return base + reference
    }

    /** What `removeSuffix` and `trimEnd` leave of fixed text at the end; a computed end is left as it is. */
    private fun trimmedEnd(parts: List<Part>, suffix: String?, once: Boolean): List<Part> {
        val last = parts.lastOrNull() as? Part.Text ?: return parts
        val trimmed = when {
            suffix == null -> if (once) return parts else last.text.trimEnd()
            once -> last.text.removeSuffix(suffix)
            else -> last.text.trimEnd { it in suffix }
        }
        return parts.dropLast(1) + Part.Text(trimmed)
    }

    private fun withDefault(parts: List<Part>, default: String?): List<Part> {
        val key = parts.singleOrNull() as? Part.Key ?: return parts
        return if (key.default == null && default != null) listOf(key.copy(default = default)) else parts
    }

    /** The text [code] evaluates to, if it is fixed. */
    fun constantText(code: Code, scope: Scope): String? =
        when (val origin = tracer.origin(code, scope).code) {
            is Literal -> text(origin.constant)
            is PropertyRead -> origin.constant?.let(::text)
            else -> null
        }

    // ---- configuration

    /** The configuration property that [origin] reads, if it reads one. */
    private fun config(origin: Traced, place: Place, depth: Int): Part.Key? =
        when (val code = origin.code) {
            is PropertyRead -> property(code) ?: mapping(code, origin.scope, depth)
            is LocalRead -> constructorParameter(code.name, place.selfClass)
            is Call -> mapping(code, origin.scope, depth) ?: lookup(code, origin.scope)
            else -> null
        }

    /** A property with `@ConfigProperty`, on the property itself, its field or the constructor parameter it is declared with. */
    private fun property(read: PropertyRead): Part.Key? =
        read.property?.let(::declaration)?.annotations?.let(::configProperty)

    /** A parameter of the constructor of [className] with `@ConfigProperty`, which the class reads where it is made. */
    private fun constructorParameter(name: String, className: String?): Part.Key? {
        if (className == null) return null
        val parameters = constructorParameters.getOrPut(className) {
            backend.constructors(className).flatMap { it.parameters }.associate { it.name to it.annotations }
        }
        return parameters[name]?.let(::configProperty)
    }

    private fun configProperty(annotations: List<AnnotationUse>): Part.Key? {
        val annotation = annotations.findWritten(CONFIG_PROPERTY) ?: return null
        val key = annotation.text("name")?.ifEmpty { null } ?: return null
        return Part.Key(key, annotation.text("defaultValue")?.takeUnless { it == UNCONFIGURED })
    }

    /**
     * What an accessor of a `@ConfigMapping` interface reads: the prefix of the interface and the
     * name of the accessor in kebab case, or the name `@WithName` gives it. A property is read
     * through its getter, so `val baseUrl` reads `get-base-url`, as SmallRye Config names it. An
     * accessor of a nested group goes on from the key of the accessor that leads to the group.
     */
    private fun mapping(code: Code, scope: Scope, depth: Int): Part.Key? {
        if (depth > MAX_DEPTH) return null
        val owner: String
        val member: String
        val annotations: List<AnnotationUse>
        val receiver: Code?
        when (code) {
            is Call -> {
                val function = code.callee?.function ?: return null
                if (code.arguments.isNotEmpty()) return null
                owner = function.owner ?: return null
                member = function.simpleName
                annotations = backend.function(function)?.annotations.orEmpty()
                receiver = code.receiver
            }
            is PropertyRead -> {
                val property = code.property ?: return null
                owner = property.owner ?: return null
                member = getter(property.simpleName)
                annotations = declaration(property)?.annotations.orEmpty()
                receiver = code.receiver
            }
            else -> return null
        }
        val declared = backend.findClass(owner) ?: return null
        val name = annotations.findWritten(WITH_NAME)?.text(VALUE) ?: kebab(member)
        val default = annotations.findWritten(WITH_DEFAULT)?.text(VALUE)
        declared.annotations.findWritten(CONFIG_MAPPING)?.let { mapping ->
            val prefix = mapping.text("prefix").orEmpty()
            return Part.Key(if (prefix.isEmpty()) name else "$prefix.$name", default)
        }
        // A group is known by the accessor it is reached through.
        if (receiver == null || receiver is ThisReference) return null
        val outer = tracer.origin(receiver, scope)
        val group = mapping(outer.code, outer.scope, depth + 1) ?: return null
        return Part.Key("${group.key}.$name", default)
    }

    /** `ConfigProvider.getConfig().getValue("key", …)`, `getOptionalValue`, and the same on a `Config` that was injected. */
    private fun lookup(call: Call, scope: Scope): Part.Key? {
        val name = call.callee?.function?.simpleName ?: call.name
        if (name != "getValue" && name != "getOptionalValue" || call.name != name) return null
        val config = call.callee?.function?.owner == CONFIG ||
            writtenType(call.receiverType) == CONFIG_SIMPLE ||
            call.unresolved?.receiverType?.substringAfterLast('.') == CONFIG_SIMPLE ||
            call.receiver?.let { isConfig(tracer.origin(it, scope).code) } == true
        if (!config) return null
        val key = call.arguments.firstOrNull()?.let { argument -> argument.constant?.let(::text) ?: constantText(argument.value, scope) } ?: return null
        return Part.Key(key)
    }

    /** Whether [code] gives the configuration: `ConfigProvider.getConfig()`, or a value declared to be a `Config`. */
    private fun isConfig(code: Code): Boolean =
        when (code) {
            is Call -> code.callee?.function?.name == "$CONFIG_PROVIDER.getConfig" ||
                (code.callee == null && code.name == "getConfig" && code.receiver.let { it != null && writtenName(it) == CONFIG_PROVIDER_SIMPLE }) ||
                code.unresolved?.names?.any { it.endsWith(".$CONFIG_PROVIDER_SIMPLE") || it == CONFIG_PROVIDER_SIMPLE } == true &&
                code.name == "getConfig"
            is PropertyRead -> code.type?.name == CONFIG || writtenType(code.type) == CONFIG_SIMPLE
            is LocalRead -> code.type?.name == CONFIG || writtenType(code.type) == CONFIG_SIMPLE
            else -> false
        }

    private fun declaration(property: PropertyId): PropertyDecl? =
        properties.getOrPut(property) { property.owner?.let { owner -> backend.properties(owner).firstOrNull { it.id == property } } }

    companion object {
        private const val MAX_DEPTH = 24
        private const val OPTIONAL = "java.util.Optional"
        private val OPTIONAL_FUNCTIONS = setOf("get", "orElse", "orElseThrow")
        private const val TEMPLATE = "template"
        private const val VALUE = "value"

        const val CONFIG_PROPERTY = WrittenAnnotations.CONFIG_PROPERTY
        const val CONFIG_MAPPING = WrittenAnnotations.CONFIG_MAPPING
        const val WITH_NAME = WrittenAnnotations.WITH_NAME
        const val WITH_DEFAULT = WrittenAnnotations.WITH_DEFAULT
        const val CONFIG = "org.eclipse.microprofile.config.Config"
        const val CONFIG_PROVIDER = "org.eclipse.microprofile.config.ConfigProvider"
        private const val CONFIG_SIMPLE = "Config"
        private const val CONFIG_PROVIDER_SIMPLE = "ConfigProvider"

        /** What `@ConfigProperty` holds where no default is given. */
        private const val UNCONFIGURED = "org.eclipse.microprofile.config.configproperty.unconfigureddvalue"

        /** A specifier of `String.format`: `%s`, `%1$s`, `%-5d` and so on. */
        private val SPECIFIER = Regex("%(?:(\\d+)\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?[a-zA-Z%]")

        private val ERROR_CLASS = Regex("ERROR CLASS: Symbol not found for ([\\w.]+)")
        private val PACKAGE = Regex("\\b(?:[a-z_][A-Za-z0-9_]*\\.)+(?=[A-Z])")

        private fun text(constant: Constant): String? =
            when (constant) {
                is Constant.Text -> constant.value
                is Constant.Integer -> constant.value.toString()
                is Constant.Decimal -> constant.value.toString()
                is Constant.Bool -> constant.value.toString()
                is Constant.EnumEntry -> constant.entry
                else -> null
            }

        /** The name of a property's getter: `isOpen` keeps its name, `baseUrl` becomes `getBaseUrl`. */
        fun getter(property: String): String =
            if (property.length > 2 && property.startsWith("is") && !property[2].isLowerCase()) {
                property
            } else {
                "get" + property.replaceFirstChar { it.uppercaseChar() }
            }

        /**
         * An accessor's name as MicroProfile Config names the property in kebab case: a capital
         * starts a new word, written in lower case, and a run of capitals is one word.
         */
        fun kebab(name: String): String = buildString {
            name.forEachIndexed { index, c ->
                if (c.isUpperCase()) {
                    val previous = name.getOrNull(index - 1)
                    val next = name.getOrNull(index + 1)
                    val starts = previous != null && previous != '-' &&
                        (previous.isLowerCase() || previous.isDigit() || (previous.isUpperCase() && next?.isLowerCase() == true))
                    if (starts) append('-')
                    append(c.lowercaseChar())
                } else {
                    append(c)
                }
            }
        }

        /**
         * The simple name of the class a type names: of the class it resolves to, or as written
         * where it does not resolve.
         */
        fun writtenType(type: TypeRef?): String? {
            if (type == null) return null
            type.name?.let { return it.substringAfterLast('.') }
            type.unresolved?.let { return it.substringAfterLast('.') }
            return ERROR_CLASS.find(type.text)?.groupValues?.get(1)?.substringAfterLast('.')
        }

        /**
         * A type as a request names it: with simple names, and with each name that did not
         * resolve as written; `null` for a type that cannot be told.
         */
        fun shortType(type: TypeRef?, bindings: Map<String, TypeRef> = emptyMap()): String? {
            if (type == null) return null
            if (type.name == null && type.arguments.isEmpty()) bindings[type.text.removeSuffix("?")]?.let { return shortType(it) }
            val text = ERROR_CLASS.replace(type.text) { it.groupValues[1].substringAfterLast('.') }
            if ("ERROR" in text || text.isEmpty()) return null
            return PACKAGE.replace(text, "").replace("!", "")
        }

        /** The code as it is written, as far as the tree tells it. */
        fun written(code: Code): String =
            when (code) {
                is LocalRead -> code.name
                is PropertyRead -> code.receiver?.takeUnless { it is ThisReference }?.let { "${written(it)}.${code.name}" } ?: code.name
                is Call -> (code.receiver?.takeUnless { it is ThisReference }?.let { "${written(it)}." }.orEmpty()) + code.name + "()"
                is Other -> code.text
                is Literal -> text(code.constant) ?: "…"
                is ThisReference -> "this"
                is ClassReference -> code.text
                else -> "…"
            }

        private fun writtenName(code: Code): String? =
            when (code) {
                is PropertyRead -> code.name
                is ClassReference -> code.text.substringAfterLast('.')
                is LocalRead -> code.name
                else -> null
            }
    }
}
