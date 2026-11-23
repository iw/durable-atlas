package io.github.iw.durableatlas.analyser.requests

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CalleeKind
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.LocalVariable
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.Return
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.ThisReference
import io.github.iw.durableatlas.analyser.code.TypeRef
import io.github.iw.durableatlas.analyser.code.children
import io.github.iw.durableatlas.analyser.graph.Dispatch
import io.github.iw.durableatlas.analyser.graph.Scope
import io.github.iw.durableatlas.analyser.graph.Target
import io.github.iw.durableatlas.analyser.graph.Traced
import io.github.iw.durableatlas.analyser.graph.ValueTracer
import io.github.iw.durableatlas.ir.ConfiguredValue
import io.github.iw.durableatlas.ir.Payload
import io.github.iw.durableatlas.ir.PayloadFormat
import io.github.iw.durableatlas.ir.PayloadKind
import io.github.iw.durableatlas.ir.Request
import io.github.iw.durableatlas.ir.RequestArgument
import io.github.iw.durableatlas.ir.RequestKind

/**
 * Reads what a function of the sources asks of other services: the HTTP requests it sends with
 * `java.net.http`, and the calls it makes of the clients named in [clients]. It follows the calls
 * into functions of the sources up to [maxDepth] calls deep, each argument bound to its
 * parameter, as the workflows are read; a call that may run one of several functions is not
 * followed. What a library does inside is not seen.
 */
internal class RequestReader(
    private val backend: SourceBackend,
    private val clients: List<ClientSpec>,
    private val maxDepth: Int,
) {
    private val tracer = ValueTracer(backend)
    private val dispatch = Dispatch(backend)
    private val types = StaticTypes(backend)
    private val values = RequestValues(backend, tracer, types)
    private val parents = Parents()
    private val codecs = Codecs(tracer, types)
    private val answers = Answers(parents, codecs, types, ::enter)

    /**
     * The requests [function] makes when it runs on an object of [className], in the order of
     * the code and each once, with the hosts [configuration] gives.
     */
    fun read(function: FunctionDecl, className: String, configuration: Configuration): List<Request> {
        val body = backend.body(function.id) ?: return emptyList()
        val found = LinkedHashSet<Request>()
        Walk(found, configuration).function(Frame(body, Scope.of(body), Place(className, function), null, null, 0, listOf(function.id)))
        return found.toList()
    }

    /**
     * The frame of the function [call] runs, called in [frame]: where it is one function of the
     * sources, lies within the depth and is not one the reading is already in. Each parameter
     * stands for the argument it was given, or else for its default value.
     */
    private fun enter(call: Call, frame: Frame): Frame? {
        val callee = call.callee ?: return null
        if (callee.kind != CalleeKind.FUNCTION || !callee.inSource || frame.depth >= maxDepth) return null
        val scope = frame.scope
        val place = frame.place
        val receiver = call.receiver
        val onThis = receiver == null || receiver is ThisReference
        val made = receiver?.takeUnless { it is ThisReference }?.let { tracer.constructedClass(it, scope) }
        val target = when {
            made != null -> dispatch.resolve(callee.function, made, exact = true)
            onThis && place.selfClass != null && call.receiverType?.name != null ->
                dispatch.resolve(callee.function, place.selfClass, exact = backend.findClass(place.selfClass)?.isAbstract == false)
            else -> dispatch.resolve(callee.function, call.receiverType?.name, exact = false)
        } ?: backend.function(callee.function)?.takeUnless { it.isAbstract }?.let { Target.One(it, null) }
        val function = (target as? Target.One)?.function ?: return null
        if (function.id in frame.stack) return null
        val body = backend.body(function.id) ?: return null
        val inner = Scope.of(body)
        inner.self = receiver?.takeUnless { it is ThisReference }?.let { Traced(it, scope) } ?: scope.self
        // A parameter stands for the argument it was given, or else for its default value.
        for (parameter in function.parameters) {
            inner.bind(parameter.name, backend.defaultValue(function.id, parameter.name)?.let { Traced(it, Scope.none()) })
        }
        for (argument in call.arguments) inner.bind(argument.parameter ?: continue, Traced(argument.value, scope))
        val bindings = function.typeParameters.zip(call.typeArguments).associate { (name, type) ->
            name to (place.typeBindings[type.text.removeSuffix("?")]?.takeIf { type.name == null } ?: type)
        }
        val self = when {
            onThis && function.id.owner != null && place.selfClass != null -> place.selfClass
            else -> target.receiverClass ?: made ?: function.id.owner ?: place.selfClass
        }
        return Frame(body, inner, Place(self, function, bindings), call, frame, frame.depth + 1, frame.stack + function.id)
    }

    private inner class Walk(private val found: MutableSet<Request>, private val configuration: Configuration) {
        fun function(frame: Frame) {
            visit(frame.body, frame, null)
        }

        /** Visits [code] in the order it runs: what a call is handed before the call. */
        private fun visit(code: Code, frame: Frame, expected: TypeRef?) {
            when (code) {
                is Call -> {
                    code.receiver?.let { visit(it, frame, null) }
                    code.arguments.forEach { visit(it.value, frame, null) }
                    request(code, frame, expected)?.let(found::add)
                    enter(code, frame)?.let(::function)
                }
                is LocalVariable -> code.initializer?.let { visit(it, frame, code.type) }
                is Return -> code.value?.let { visit(it, frame, frame.place.function?.returnType) }
                else -> code.children().forEach { visit(it, frame, null) }
            }
        }

        private fun request(call: Call, frame: Frame, expected: TypeRef?): Request? =
            http(call, frame) ?: client(call, frame, expected)

        // ---- java.net.http

        private fun http(send: Call, frame: Frame): Request? {
            val scope = frame.scope
            val place = frame.place
            val name = send.callee?.function?.name
            if (name != SEND && name != SEND_ASYNC) return null
            val argument = send.arguments.firstOrNull() ?: return null
            val async = name == SEND_ASYNC
            val response = responseOf(send.type, async)
            val receives = receives(send, frame, async)
            val chain = chain(argument.value, scope)
                // A request made where the code that is read does not show.
                ?: return Request(
                    kind = RequestKind.HTTP,
                    target = unseen(argument.text),
                    sends = UNKNOWN,
                    response = response,
                    receives = described(receives, null),
                    loc = send.location,
                )
            var method: String? = null
            var uri: Traced? = null
            var publisher: Traced? = null
            val headers = LinkedHashMap<String, String>()
            for (step in chain.calls) {
                val call = step.code as Call
                when (val member = memberName(call)) {
                    NEW_BUILDER -> call.arguments.singleOrNull()?.let { uri = Traced(it.value, step.scope) }
                    URI_SETTER -> call.arguments.singleOrNull()?.let { uri = Traced(it.value, step.scope) }
                    "method" -> {
                        method = call.arguments.firstOrNull()?.let { verb ->
                            val parts = values.parts(verb.value, step.scope, place)
                            parts.constant()?.uppercase() ?: parts.text()
                        } ?: method
                        publisher = call.arguments.getOrNull(1)?.let { Traced(it.value, step.scope) }
                    }
                    "POST", "PUT" -> {
                        method = member
                        publisher = call.arguments.firstOrNull()?.let { Traced(it.value, step.scope) }
                    }
                    in METHODS -> {
                        method = member
                        publisher = null
                    }
                    "header", "setHeader" -> header(call.arguments.map { Traced(it.value, step.scope) }, headers)
                    "headers" -> call.arguments.map { Traced(it.value, step.scope) }.chunked(2).forEach { header(it, headers) }
                    else -> Unit
                }
            }
            val target = uri?.let { values.parts(it.code, it.scope, place) }?.takeIf { it.text().isNotEmpty() }
            val shownMethod = method ?: DEFAULT_METHOD.takeIf { chain.whole }
            val sent = publisher?.let { codecs.sent(it.code, it.scope, place) }
                ?: if (shownMethod != null && shownMethod in METHODS) Payload(PayloadKind.NONE) else UNKNOWN
            return Request(
                kind = RequestKind.HTTP,
                // `GET` is what a builder makes unless told otherwise, where all it is told is seen.
                method = shownMethod,
                target = target?.text() ?: unseen(argument.text),
                parameters = target?.parameters().orEmpty(),
                sends = described(sent, headers[CONTENT_TYPE]),
                response = response,
                receives = described(receives, headers[ACCEPT]),
                keys = target?.keys().orEmpty(),
                host = target?.let(::host),
                hosts = target?.let(::hosts).orEmpty(),
                loc = send.location,
            )
        }

        /** What the code makes of the answer to [send]. */
        private fun receives(send: Call, frame: Frame, async: Boolean): Payload {
            val handler = send.arguments.getOrNull(1)?.let { tracer.origin(it.value, frame.scope).code as? Call }
            val handled = handler?.callee?.function?.takeIf { it.owner == HANDLERS }?.simpleName ?: handler?.name?.takeIf { handler.callee == null }
            if (handled in DISCARDING) return Payload(PayloadKind.NOT_READ)
            val body = (if (async) send.type?.arguments?.firstOrNull() else send.type)?.takeIf { it.name == HTTP_RESPONSE }?.arguments?.firstOrNull()
            if (body?.name in VOID) return Payload(PayloadKind.NOT_READ)
            val text = body?.name == Codecs.STRING || handled == "ofString"
            return answers.of(send, frame, if (async) Answers.State.FUTURE else Answers.State.RESPONSE, text)
        }

        /** A header the builder is told, where its name and value are fixed; the first of each name counts. */
        private fun header(arguments: List<Traced>, into: MutableMap<String, String>) {
            if (arguments.size != 2) return
            val name = values.constantText(arguments[0].code, arguments[0].scope)?.lowercase() ?: return
            val value = values.constantText(arguments[1].code, arguments[1].scope) ?: return
            into.putIfAbsent(name, value.substringBefore(';').trim())
        }

        /**
         * [payload] with the media type a header gives it, where it has a body, and, where Jackson
         * writes or reads it, the naming strategy the module's configuration gives Quarkus's
         * `ObjectMapper`.
         */
        private fun described(payload: Payload, mediaType: String?): Payload {
            val named = configuration.jacksonNaming?.takeIf { payload.format == PayloadFormat.JACKSON }?.let { payload.copy(naming = it) } ?: payload
            return if (mediaType == null || payload.kind == PayloadKind.NONE || payload.kind == PayloadKind.NOT_READ) named else named.copy(mediaType = mediaType)
        }

        /**
         * The calls that made the request [code] evaluates to, from `HttpRequest.newBuilder` to
         * `build()`, each with the scope it is read in; `null` if it was not made that way, or
         * where the code that is read does not show.
         */
        private fun chain(code: Code, scope: Scope): Chain? {
            val calls = ArrayList<Traced>()
            var whole = true
            var current = tracer.origin(code, scope)
            repeat(MAX_CHAIN) {
                val call = current.code as? Call ?: return null
                val member = memberName(call) ?: return null
                calls += current
                if (member == NEW_BUILDER) return Chain(calls.reversed(), whole)
                if (member !in BUILDER_METHODS) return null
                val receiver = call.receiver ?: return null
                val trail = tracer.trail(receiver, current.scope)
                // A builder kept in a variable may be told more in statements of their own, which are not read.
                if (trail.kept) whole = false
                current = trail.passed.last()
            }
            return null
        }

        /** What a request whose making is not seen is known by: what the code sends, as `{text}`. */
        private fun unseen(text: String): String = "{${text.trim()}}"

        /** The name of a function of `HttpRequest` or its builder that [call] calls, as written where it does not resolve. */
        private fun memberName(call: Call): String? {
            val function = call.callee?.function ?: return call.name.takeIf { it in BUILDER_METHODS || it == NEW_BUILDER }
            return when {
                function.name == "$HTTP_REQUEST.$NEW_BUILDER" -> NEW_BUILDER
                function.owner == BUILDER -> function.simpleName
                else -> null
            }
        }

        /** The host of a request: the value configured for the key its target starts with. */
        private fun host(target: List<Part>): String? {
            val key = target.firstOrNull() as? Part.Key ?: return null
            return configuration.value(key.key) ?: key.default
        }

        /** The values of the keys at the start of a target, per profile (see [serverParts]). */
        private fun hosts(target: List<Part>): List<ConfiguredValue> =
            target.take(target.serverParts()).filterIsInstance<Part.Key>().flatMap { key ->
                configuration.values(key.key).ifEmpty { listOfNotNull(key.default?.let { ConfiguredValue(key.key, it) }) }
            }

        /** The type of the body of the answer, from `HttpResponse<T>`; none where the body is discarded (`Void`). */
        private fun responseOf(type: TypeRef?, async: Boolean): String? {
            val response = if (async) type?.arguments?.firstOrNull() else type
            if (response?.name != HTTP_RESPONSE) return null
            return RequestValues.shortType(response.arguments.firstOrNull())?.takeUnless { it in NO_RESPONSE }
        }

        // ---- clients

        private fun client(call: Call, frame: Frame, expected: TypeRef?): Request? {
            val scope = frame.scope
            val place = frame.place
            val spec = clients.firstOrNull { matches(it, call, place) } ?: return null
            val argument = operation(spec, call, scope)
            val operation = argument?.let { values.parts(it.code, it.scope, place) }
            // Where the compiler took the call for another function, only the type arguments written out are the call's.
            val written = (call.writtenTypeArguments ?: call.typeArguments).firstOrNull { RequestValues.shortType(it, place.typeBindings) != null }
            val response = written?.let { RequestValues.shortType(it, place.typeBindings) } ?: RequestValues.shortType(expected, place.typeBindings)
            val answered = written ?: expected?.takeIf { RequestValues.shortType(it, place.typeBindings) != null }
            return Request(
                kind = RequestKind.CLIENT,
                client = spec.simpleType ?: receiverTypes(call, place).firstOrNull()?.substringAfterLast('.'),
                call = spec.method,
                operation = operation?.text()?.ifEmpty { null },
                constant = argument?.let(::constant),
                arguments = arguments(call, argument, frame),
                response = response?.takeUnless { it in NO_RESPONSE },
                receives = clientAnswer(call, answered, response, frame),
                keys = operation?.keys().orEmpty(),
                loc = call.location,
            )
        }

        /** The constant of the sources that the value of [argument] is read from, by its fully qualified name. */
        private fun constant(argument: Traced): String? =
            tracer.trail(argument.code, argument.scope).passed
                .firstNotNullOfOrNull { step -> (step.code as? PropertyRead)?.takeIf { it.constant != null }?.property?.name }
                ?.replace(COMPANION, "")

        /** The arguments of a call of a client other than [operation], each with the type of what it is handed. */
        private fun arguments(call: Call, operation: Traced?, frame: Frame): List<RequestArgument> =
            call.arguments.mapIndexedNotNull { index, argument ->
                if (operation != null && argument.value === operation.code) return@mapIndexedNotNull null
                val type = types.along(tracer.trail(argument.value, frame.scope))
                RequestArgument(argument.parameter, index + 1, types.name(type, frame.place, call.location.file))
            }

        /** What a call of a client gives back, as the code takes it. */
        private fun clientAnswer(call: Call, type: TypeRef?, written: String?, frame: Frame): Payload {
            if (written != null && written in NO_RESPONSE) return Payload(PayloadKind.NOT_READ)
            types.name(type, frame.place, call.location.file)?.let { name ->
                if (type != null && (types.telling(type) || type.name != null)) return Payload(PayloadKind.TYPE, name)
            }
            return if (answers.dropped(call, frame)) Payload(PayloadKind.NOT_READ) else UNKNOWN
        }

        private fun matches(spec: ClientSpec, call: Call, place: Place): Boolean {
            val called = call.callee?.function?.simpleName
            // `client<T>(…)` is a call of `invoke`; where it does not resolve, it is named after the client.
            val operator = call.callee == null && call.receiver == null && spec.method == INVOKE && declaredType(call.name, place) != null
            if (call.name != spec.method && called != spec.method && !operator) return false
            // A call of a function value is no call of a client.
            val receiver = call.receiverType?.name
            if (receiver != null && FUNCTION_TYPES.any { receiver.startsWith(it) }) return false
            val type = spec.type ?: return true
            return receiverTypes(call, place).any { candidate ->
                if ('.' in type) candidate == type else candidate.substringAfterLast('.') == type
            }
        }

        /**
         * The classes the object [call] is made on may be named by: the one it resolves to and its
         * supertypes, the class that declares the function, and the name as written where the
         * class does not resolve.
         */
        private fun receiverTypes(call: Call, place: Place): List<String> {
            val types = ArrayList<String>()
            call.receiverType?.name?.let { name ->
                types += name
                backend.findClass(name)?.allSuperTypes?.let(types::addAll)
            }
            call.callee?.function?.owner?.takeIf { call.receiverType?.name != null }?.let(types::add)
            RequestValues.writtenType(call.receiverType)?.takeIf { call.receiverType?.name == null }?.let(types::add)
            call.unresolved?.receiverType?.let(types::add)
            if (call.callee == null && call.receiver == null) declaredType(call.name, place)?.let(types::add)
            return types.distinct()
        }

        /** The class, by name, that [name] is declared to be of, as a property of the object or a parameter of the function. */
        private fun declaredType(name: String, place: Place): String? {
            val type = place.function?.parameters?.firstOrNull { it.name == name }?.type
                ?: place.selfClass?.let { owner -> backend.properties(owner).firstOrNull { it.name == name }?.type }
                ?: return null
            return type.name ?: RequestValues.writtenType(type)
        }

        /** The argument that says what a call of a client asks for. */
        private fun operation(spec: ClientSpec, call: Call, scope: Scope): Traced? {
            spec.position?.let { position -> return call.arguments.getOrNull(position - 1)?.let { Traced(it.value, scope) } }
            val name = spec.parameter ?: return null
            call.arguments.firstOrNull { it.parameter == name }?.let { return Traced(it.value, scope) }
            // Or an argument or property of what the call is handed: an object made with it, or a builder told it.
            for (argument in call.arguments) {
                nested(Traced(argument.value, scope), name)?.let { return it }
            }
            return null
        }

        /**
         * The value [name] is given in the making of what [value] evaluates to: an argument of
         * that name of a constructor, or of a call of a builder named after it (`name`,
         * `setName`, `withName`). The calls are read as written, from the last one of a chain to
         * the first; where the chain starts with a variable, it goes on with what the variable
         * was given.
         */
        private fun nested(value: Traced, name: String): Traced? {
            val setters = setOf(name, "set" + name.replaceFirstChar(Char::uppercaseChar), "with" + name.replaceFirstChar(Char::uppercaseChar))
            var current = value
            repeat(MAX_CHAIN) {
                val call = current.code as? Call
                if (call == null) {
                    val origin = tracer.origin(current.code, current.scope)
                    if (origin.code === current.code) return null
                    current = origin
                    return@repeat
                }
                call.arguments.firstOrNull { it.parameter == name }?.let { return Traced(it.value, current.scope) }
                if (call.name in setters) call.arguments.firstOrNull()?.let { return Traced(it.value, current.scope) }
                current = Traced(call.receiver ?: return null, current.scope)
            }
            return null
        }
    }

    /**
     * The calls that build a request, in the order they run. [whole] is false where the builder
     * was kept in a variable on the way, so that statements of their own may have told it more.
     */
    private class Chain(val calls: List<Traced>, val whole: Boolean)

    private companion object {
        const val HTTP_REQUEST = "java.net.http.HttpRequest"
        const val BUILDER = "java.net.http.HttpRequest.Builder"
        const val HTTP_RESPONSE = "java.net.http.HttpResponse"
        const val SEND = "java.net.http.HttpClient.send"
        const val SEND_ASYNC = "java.net.http.HttpClient.sendAsync"
        const val NEW_BUILDER = "newBuilder"
        const val URI_SETTER = "uri"
        const val INVOKE = "invoke"

        /** What the name of a constant of a companion object has in it that the code does not write. */
        const val COMPANION = ".Companion"

        /** What a builder makes where no method is set. */
        const val DEFAULT_METHOD = "GET"

        val METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD")

        /** The functions of `HttpRequest.Builder` that give the builder back. */
        val BUILDER_METHODS = METHODS + setOf(
            "method", URI_SETTER, "header", "headers", "setHeader", "timeout", "version", "expectContinue", "copy", "build",
        )

        /** Types of what is not called on an object of a client: functions and lambdas. */
        val FUNCTION_TYPES = listOf("kotlin.Function", "kotlin.jvm.functions.", "kotlin.reflect.", "kotlin.coroutines.")

        /** Types that say that nothing comes back. */
        val NO_RESPONSE = setOf("Unit", "Nothing", "Void")

        /** The same, by their qualified names. */
        val VOID = setOf("kotlin.Unit", "kotlin.Nothing", "java.lang.Void")

        const val HANDLERS = "java.net.http.HttpResponse.BodyHandlers"

        /** Body handlers that read no body. */
        val DISCARDING = setOf("discarding", "replacing")

        const val CONTENT_TYPE = "content-type"
        const val ACCEPT = "accept"

        val UNKNOWN = Payload(PayloadKind.UNKNOWN)

        /** A chain of builder calls longer than this is not followed. */
        const val MAX_CHAIN = 32
    }
}
