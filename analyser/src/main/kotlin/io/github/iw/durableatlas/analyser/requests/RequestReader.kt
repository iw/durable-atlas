package io.github.iw.durableatlas.analyser.requests

import io.github.iw.durableatlas.analyser.code.Block
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CalleeKind
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.LocalVariable
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
import io.github.iw.durableatlas.ir.Request
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
    private val values = RequestValues(backend, tracer)

    /**
     * The requests [function] makes when it runs on an object of [className], in the order of
     * the code and each once, with the hosts [configuration] gives.
     */
    fun read(function: FunctionDecl, className: String, configuration: Configuration): List<Request> {
        val body = backend.body(function.id) ?: return emptyList()
        val found = LinkedHashSet<Request>()
        Walk(found, configuration).function(body, Scope.of(body), Place(className, function), 0, listOf(function.id))
        return found.toList()
    }

    private inner class Walk(private val found: MutableSet<Request>, private val configuration: Configuration) {
        fun function(body: Block, scope: Scope, place: Place, depth: Int, stack: List<FunctionId>) {
            visit(body, scope, place, depth, stack, null)
        }

        /** Visits [code] in the order it runs: what a call is handed before the call. */
        private fun visit(code: Code, scope: Scope, place: Place, depth: Int, stack: List<FunctionId>, expected: TypeRef?) {
            when (code) {
                is Call -> {
                    code.receiver?.let { visit(it, scope, place, depth, stack, null) }
                    code.arguments.forEach { visit(it.value, scope, place, depth, stack, null) }
                    request(code, scope, place, expected)?.let(found::add)
                    follow(code, scope, place, depth, stack)
                }
                is LocalVariable -> code.initializer?.let { visit(it, scope, place, depth, stack, code.type) }
                is Return -> code.value?.let { visit(it, scope, place, depth, stack, place.function?.returnType) }
                else -> code.children().forEach { visit(it, scope, place, depth, stack, null) }
            }
        }

        /** Reads the function a call runs, where it is one function of the sources and lies within the depth. */
        private fun follow(call: Call, scope: Scope, place: Place, depth: Int, stack: List<FunctionId>) {
            val callee = call.callee ?: return
            if (callee.kind != CalleeKind.FUNCTION || !callee.inSource || depth >= maxDepth) return
            val receiver = call.receiver
            val onThis = receiver == null || receiver is ThisReference
            val made = receiver?.takeUnless { it is ThisReference }?.let { tracer.constructedClass(it, scope) }
            val target = when {
                made != null -> dispatch.resolve(callee.function, made, exact = true)
                onThis && place.selfClass != null && call.receiverType?.name != null ->
                    dispatch.resolve(callee.function, place.selfClass, exact = backend.findClass(place.selfClass)?.isAbstract == false)
                else -> dispatch.resolve(callee.function, call.receiverType?.name, exact = false)
            } ?: backend.function(callee.function)?.takeUnless { it.isAbstract }?.let { Target.One(it, null) }
            val function = (target as? Target.One)?.function ?: return
            if (function.id in stack) return
            val body = backend.body(function.id) ?: return
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
            function(body, inner, Place(self, function, bindings), depth + 1, stack + function.id)
        }

        private fun request(call: Call, scope: Scope, place: Place, expected: TypeRef?): Request? =
            http(call, scope, place) ?: client(call, scope, place, expected)

        // ---- java.net.http

        private fun http(send: Call, scope: Scope, place: Place): Request? {
            val name = send.callee?.function?.name
            if (name != SEND && name != SEND_ASYNC) return null
            val argument = send.arguments.firstOrNull() ?: return null
            val response = responseOf(send.type, async = name == SEND_ASYNC)
            val chain = chain(argument.value, scope)
                // A request made where the code that is read does not show.
                ?: return Request(kind = RequestKind.HTTP, target = unseen(argument.text), response = response, loc = send.location)
            var method: String? = null
            var uri: Traced? = null
            for (step in chain.calls) {
                val call = step.code as Call
                when (val member = memberName(call)) {
                    NEW_BUILDER -> call.arguments.singleOrNull()?.let { uri = Traced(it.value, step.scope) }
                    URI_SETTER -> call.arguments.singleOrNull()?.let { uri = Traced(it.value, step.scope) }
                    "method" -> method = call.arguments.firstOrNull()?.let { verb ->
                        val parts = values.parts(verb.value, step.scope, place)
                        parts.constant()?.uppercase() ?: parts.text()
                    } ?: method
                    in METHODS -> method = member
                    else -> Unit
                }
            }
            val target = uri?.let { values.parts(it.code, it.scope, place) }?.takeIf { it.text().isNotEmpty() }
            return Request(
                kind = RequestKind.HTTP,
                // `GET` is what a builder makes unless told otherwise, where all it is told is seen.
                method = method ?: DEFAULT_METHOD.takeIf { chain.whole },
                target = target?.text() ?: unseen(argument.text),
                response = response,
                keys = target?.keys().orEmpty(),
                host = target?.let(::host),
                loc = send.location,
            )
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

        /** The type of the body of the answer, from `HttpResponse<T>`; none where the body is discarded (`Void`). */
        private fun responseOf(type: TypeRef?, async: Boolean): String? {
            val response = if (async) type?.arguments?.firstOrNull() else type
            if (response?.name != HTTP_RESPONSE) return null
            return RequestValues.shortType(response.arguments.firstOrNull())?.takeUnless { it in NO_RESPONSE }
        }

        // ---- clients

        private fun client(call: Call, scope: Scope, place: Place, expected: TypeRef?): Request? {
            val spec = clients.firstOrNull { matches(it, call, place) } ?: return null
            val argument = operation(spec, call, scope)
            val operation = argument?.let { values.parts(it.code, it.scope, place) }
            // Where the compiler took the call for another function, only the type arguments written out are the call's.
            val response = (call.writtenTypeArguments ?: call.typeArguments).firstNotNullOfOrNull { RequestValues.shortType(it, place.typeBindings) }
                ?: RequestValues.shortType(expected, place.typeBindings)
            return Request(
                kind = RequestKind.CLIENT,
                client = spec.simpleType ?: receiverTypes(call, place).firstOrNull()?.substringAfterLast('.'),
                call = spec.method,
                operation = operation?.text()?.ifEmpty { null },
                response = response?.takeUnless { it in NO_RESPONSE },
                keys = operation?.keys().orEmpty(),
                loc = call.location,
            )
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

        /** A chain of builder calls longer than this is not followed. */
        const val MAX_CHAIN = 32
    }
}
