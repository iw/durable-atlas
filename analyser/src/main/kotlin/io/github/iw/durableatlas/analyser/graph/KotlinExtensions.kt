package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Argument
import io.github.iw.durableatlas.analyser.code.Block
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CallableReference
import io.github.iw.durableatlas.analyser.code.Callee
import io.github.iw.durableatlas.analyser.code.CalleeKind
import io.github.iw.durableatlas.analyser.code.ClassLiteral
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Constant
import io.github.iw.durableatlas.analyser.code.Construction
import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.Literal
import io.github.iw.durableatlas.analyser.code.Other
import io.github.iw.durableatlas.analyser.code.PropertyId
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.ThisReference
import io.github.iw.durableatlas.analyser.code.TypeRef
import io.github.iw.durableatlas.analyser.code.rewriteCalls
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.SourceLocation

/**
 * Reads the Kotlin extensions of the Temporal SDK (`io.temporal:temporal-kotlin`) as what they
 * stand for. Each of them is a few lines around a function of the SDK itself: options written
 * as a lambda are the builder of those options with the lambda applied to it, a copy of options
 * is a builder made from them, and a call of a stub that takes the type it gives back as a type
 * argument is the stub's own call. The code that calls one is rewritten to the code the
 * extension is made of, so that everything that reads the SDK's own functions reads these
 * without knowing of them, and by the same rules.
 *
 * What is rewritten, as the extensions are written in release 1.41.1:
 * - `ActivityOptions { … }` and the like, one for each class of options: `X.newBuilder().apply { … }.build()`;
 * - `options.copy { … }`: `options.toBuilder().apply { … }.build()`, or `X.newBuilder(options)…`
 *   for the options that have no `toBuilder`;
 * - `setRetryOptions { … }` inside options, and `setDefaultActivityOptions { … }` and
 *   `setDefaultLocalActivityOptions { … }` inside the options of a worker's workflows: the
 *   setter of the builder, given the options the lambda makes;
 * - `setActivityOptions("Type" to options, …)` and `setLocalActivityOptions(…)`: the setter,
 *   given the map of those pairs;
 * - `Saga { … }` and `Saga()`: `Saga(Saga.Options.Builder().apply { … }.build())`;
 * - `stub.execute<T>(…)` and `stub.executeAsync<T>(…)` of a stub of activities or of a child
 *   workflow without an interface: the function of the stub that is handed the class;
 * - `worker.registerWorkflowImplementationType<T>()`, with or without a lambda of options, and
 *   `worker.registerWorkflowImplementationTypes(classes) { … }`: the worker's own function;
 * - `activityName(Interface::method)`, `workflowName<T>()`, `workflowName(T::class.java)`,
 *   `workflowSignalName(Interface::method)` and `workflowQueryType(Interface::method)`: the
 *   name itself, which the SDK derives by the rules discovery follows. Where those rules give
 *   none, the SDK fails when the code runs; the call is then text nothing can be read from.
 *
 * Every other function of the extensions is one a workflow does not call: they are for clients,
 * workers and activities, and are left as they are.
 */
internal class KotlinExtensions(private val names: TemporalNames) {
    /** [code] with every call of an extension replaced by the code that extension is made of. */
    fun expand(code: Code): Code = code.rewriteCalls(::expanded)

    private fun expanded(call: Call): Code {
        val callee = call.callee ?: return call
        val function = callee.function
        // The extensions are functions of no class, in the packages of the SDK.
        if (callee.inSource || callee.kind != CalleeKind.FUNCTION || function.owner != null || !function.name.startsWith(SDK)) return call
        val expansion = if (function.receiver == null) made(call, function) ?: named(call, function) else extended(call, function, function.receiver)
        return expansion ?: call
    }

    // ---- options written as a lambda

    /** `X { … }` for a class of options, and `Saga { … }`. */
    private fun made(call: Call, function: FunctionId): Code? {
        if (function.signature != LAMBDA || call.arguments.size > 1) return null
        val block = call.arguments.singleOrNull()
        return when (function.name) {
            in OPTIONS -> block?.let { built(function.name, newBuilder(function.name, null, call.location), it, call) }
            SAGA -> {
                val builder = Call(callee(SAGA_OPTIONS_BUILDER, CONSTRUCTOR, "()", CalleeKind.CONSTRUCTOR), "Builder", null, null, emptyList(), emptyList(), type(SAGA_OPTIONS_BUILDER), call.location)
                val options = built(SAGA_OPTIONS, builder, block ?: nothing(call.location), call).copy(type = type(SAGA_OPTIONS))
                Call(
                    callee = callee(SAGA, CONSTRUCTOR, "($SAGA_OPTIONS)", CalleeKind.CONSTRUCTOR),
                    name = call.name,
                    receiver = null,
                    receiverType = null,
                    typeArguments = emptyList(),
                    arguments = listOf(Argument("options", block?.text ?: EMPTY_LAMBDA, null, options)),
                    type = call.type,
                    location = call.location,
                )
            }
            else -> null
        }
    }

    /** A lambda that does nothing: what an extension takes where it is handed none. */
    private fun nothing(location: SourceLocation): Argument =
        Argument("block", EMPTY_LAMBDA, null, Lambda(emptyList(), Block(emptyList(), location), location, yieldsValue = false))

    /** `X.newBuilder()`, or `X.newBuilder(from)`. */
    private fun newBuilder(options: String, from: Argument?, location: SourceLocation): Call =
        Call(
            callee = callee(options, "newBuilder", if (from == null) "()" else "($options)"),
            name = "newBuilder",
            receiver = null,
            receiverType = null,
            typeArguments = emptyList(),
            arguments = listOfNotNull(from),
            type = type("$options.$BUILDER"),
            location = location,
        )

    /**
     * `builder.apply(block).build()`: the options of the class [options] that [builder] makes
     * once [block] has run on it. The call that applies the block goes by the name of [call], so
     * that a `return` in the block that names the extension still names the block.
     */
    private fun built(options: String, builder: Call, block: Argument, call: Call): Call {
        val builderType = builder.type ?: type("$options.$BUILDER")
        val applied = Call(
            callee = APPLY,
            name = call.name,
            receiver = builder,
            receiverType = builderType,
            typeArguments = listOf(builderType),
            arguments = listOf(block.copy(parameter = "block")),
            type = builderType,
            location = call.location,
        )
        val builderName = builderType.name ?: "$options.$BUILDER"
        return Call(callee(builderName, "build", "()"), "build", applied, builderType, emptyList(), emptyList(), call.type ?: type(options), call.location)
    }

    // ---- what extends a class of the SDK

    private fun extended(call: Call, function: FunctionId, receiver: String): Code? =
        when (function.simpleName) {
            "copy" -> copied(call, function, receiver)
            "setRetryOptions" -> setter(call, function, receiver, RETRIED, RETRY_OPTIONS)
            "setDefaultActivityOptions" -> setter(call, function, receiver, WORKFLOW_OPTIONS_BUILDERS, TemporalApi.ACTIVITY_OPTIONS)
            "setDefaultLocalActivityOptions" -> setter(call, function, receiver, WORKFLOW_OPTIONS_BUILDERS, TemporalApi.LOCAL_ACTIVITY_OPTIONS)
            "setActivityOptions", "setLocalActivityOptions" -> mapSetter(call, function, receiver)
            "execute", "executeAsync" -> stubCall(call, function, receiver)
            "registerWorkflowImplementationTypes", "registerWorkflowImplementationType" -> registration(call, function, receiver)
            else -> null
        }

    /** `options.copy { … }`: a builder made from the options, with the lambda applied to it. */
    private fun copied(call: Call, function: FunctionId, options: String): Code? {
        val block = call.arguments.singleOrNull()
        if (function.signature != LAMBDA || block == null || function.name != "${packageOf(options)}.copy") return null
        val location = call.location
        val builder = when (options) {
            in COPIED_BY_BUILDER ->
                Call(callee(options, "toBuilder", "()"), "toBuilder", call.receiver, call.receiverType, emptyList(), emptyList(), type("$options.$BUILDER"), location)
            in COPIED_INTO_BUILDER -> {
                val copied = call.receiver ?: ThisReference(call.receiverType, location)
                newBuilder(options, Argument("options", Texts.of(copied), null, copied), location)
            }
            else -> return null
        }
        return built(options, builder, block, call)
    }

    /** `setX { … }` on a builder of [builders]: the builder's own `setX`, given the options of the class [options] that the lambda makes. */
    private fun setter(call: Call, function: FunctionId, builder: String, builders: Set<String>, options: String): Code? {
        val block = call.arguments.singleOrNull()
        if (function.signature != LAMBDA || block == null || builder !in builders || function.name != "${packageOf(builder)}.${function.simpleName}") return null
        val given = built(options, newBuilder(options, null, call.location), block, call).copy(type = type(options))
        return call.copy(
            callee = callee(builder, function.simpleName, "($options)"),
            typeArguments = emptyList(),
            arguments = listOf(block.copy(value = given)),
        )
    }

    /** `setActivityOptions("Type" to options, …)`: the builder's own setter, given the map those pairs make. */
    private fun mapSetter(call: Call, function: FunctionId, builder: String): Code? {
        if (function.signature != PAIRS || builder !in WORKFLOW_OPTIONS_BUILDERS || function.name != "${packageOf(builder)}.${function.simpleName}") return null
        val map = Call(
            callee = MAP_OF,
            name = "mapOf",
            receiver = null,
            receiverType = null,
            typeArguments = emptyList(),
            arguments = call.arguments,
            type = type("kotlin.collections.Map"),
            location = call.location,
        )
        val written = call.arguments.joinToString(", ") { it.text }
        return call.copy(
            callee = callee(builder, function.simpleName, "(kotlin.collections.MutableMap)"),
            typeArguments = emptyList(),
            arguments = listOf(Argument(call.arguments.firstOrNull()?.parameter, written, null, map)),
        )
    }

    /**
     * `stub.execute<T>(…)` and `stub.executeAsync<T>(…)`: the stub's own function of that name,
     * which is handed the class and the type of what it gives back where these take a type argument.
     */
    private fun stubCall(call: Call, function: FunctionId, stub: String): Code? {
        if (function.name != "$WORKFLOW_PACKAGE.${function.simpleName}") return null
        val named = when {
            stub == TemporalApi.ACTIVITY_STUB && function.signature == "(kotlin.String,$ARGUMENTS)" -> 1
            stub == TemporalApi.CHILD_WORKFLOW_STUB && function.signature == "($ARGUMENTS)" -> 0
            else -> return null
        }
        if (call.arguments.size < named) return null
        val given = call.typeArguments.firstOrNull() ?: TypeRef(name = null, text = "T")
        val result = listOf(
            classArgument("resultClass", given, call.location),
            Argument("resultType", "typeOf<${given.text}>().javaType", null, Other(null, "typeOf<${given.text}>().javaType", emptyList(), call.location)),
        )
        val leading = if (named == 1) "kotlin.String," else ""
        return call.copy(
            callee = callee(stub, function.simpleName, "(${leading}java.lang.Class,java.lang.reflect.Type,$ARGUMENTS)"),
            arguments = call.arguments.take(named) + result + call.arguments.drop(named),
        )
    }

    /** `T::class.java`, as the lowering writes it. */
    private fun classArgument(parameter: String, type: TypeRef, location: SourceLocation): Argument {
        val text = "${type.text}::class.java"
        val read = PropertyRead(
            property = PropertyId("kotlin.jvm.java", null),
            name = "java",
            receiver = ClassLiteral(type, location),
            onThis = false,
            type = TypeRef("java.lang.Class", "java.lang.Class<${type.text}>", arguments = listOf(type)),
            constant = null,
            location = location,
        )
        return Argument(parameter, text, null, read)
    }

    /** The functions of a worker that register the classes of workflows, by type argument or with a lambda of options. */
    private fun registration(call: Call, function: FunctionId, worker: String): Code? {
        if (worker != WORKER || function.name != "${packageOf(WORKER)}.${function.simpleName}") return null
        val block = (call.arguments.firstOrNull { it.parameter == "options" } ?: call.arguments.lastOrNull())?.takeIf { function.signature.endsWith(LAMBDA_LAST) }
        val classes = when (function.simpleName) {
            "registerWorkflowImplementationType" -> {
                if (function.signature != "()" && function.signature != LAMBDA) return null
                listOf(classArgument(CLASSES, call.typeArguments.firstOrNull() ?: return null, call.location))
            }
            else -> {
                if (function.signature != "(java.lang.Class...,kotlin.Function1)") return null
                call.arguments.filter { it !== block }
            }
        }
        val options = block?.let {
            val made = built(TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS, newBuilder(TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS, null, call.location), it, call)
            Argument("options", it.text, null, made.copy(type = type(TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS)))
        }
        val signature = if (options == null) "(java.lang.Class...)" else "(${TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS},java.lang.Class...)"
        return call.copy(
            callee = callee(WORKER, "registerWorkflowImplementationTypes", signature),
            typeArguments = emptyList(),
            arguments = listOfNotNull(options) + classes,
        )
    }

    // ---- names asked for by reference

    /**
     * The functions that give the name Temporal knows an activity, a workflow, a signal or a
     * query by: the name, where the rules of the SDK give one for what the call names.
     */
    private fun named(call: Call, function: FunctionId): Code? {
        if (function.name !in NAMES) return null
        val given = call.arguments.singleOrNull()?.value
        val referred = (given as? CallableReference)?.function?.function
        val name = when (function.name) {
            ACTIVITY_NAME -> referred?.let { method -> method.owner?.let { names.registeredActivityType(it, method) } }
            WORKFLOW_NAME -> when (function.signature) {
                "()" -> call.typeArguments.singleOrNull()?.name
                else -> ((given as? PropertyRead)?.takeIf { it.name == "java" }?.receiver as? ClassLiteral)?.type?.name
            }?.let(names::workflowType)
            SIGNAL_NAME -> referred?.let { handlerName(it, EntryPointKind.SIGNAL) }
            else -> referred?.let { handlerName(it, EntryPointKind.QUERY) }
        }
        // Without a name the SDK fails here. The reference that was handed over calls nothing either way.
        return name?.let { Literal(Constant.Text(it), call.location) }
            ?: Other(null, "${call.name}(${call.arguments.joinToString(", ") { it.text }})", emptyList(), call.location)
    }

    private fun handlerName(method: FunctionId, kind: EntryPointKind): String? =
        method.owner?.let { names.workflowEntryPoint(it, method) }?.takeIf { it.kind == kind }?.name

    private companion object {
        const val SDK = "io.temporal."
        const val WORKFLOW_PACKAGE = "io.temporal.workflow"
        const val BUILDER = "Builder"
        const val CONSTRUCTOR = "<init>"
        const val EMPTY_LAMBDA = "{}"

        /** What the function that takes one lambda, and the one that takes some pairs, are known by. */
        const val LAMBDA = "(kotlin.Function1)"
        const val LAMBDA_LAST = "kotlin.Function1)"
        const val PAIRS = "(kotlin.Pair...)"

        /** The arguments of what a stub without an interface runs, as a signature names them. */
        const val ARGUMENTS = "kotlin.Any..."

        /** The parameter of a worker's function that takes the classes of workflows. */
        const val CLASSES = "workflowImplementationClasses"

        const val RETRY_OPTIONS = "io.temporal.common.RetryOptions"
        const val SAGA = TemporalApi.SAGA
        const val SAGA_OPTIONS = "${TemporalApi.SAGA}.Options"
        const val SAGA_OPTIONS_BUILDER = TemporalApi.SAGA_OPTIONS_BUILDER
        const val WORKER = "io.temporal.worker.Worker"

        const val ACTIVITY_NAME = "io.temporal.common.metadata.activityName"
        const val WORKFLOW_NAME = "io.temporal.common.metadata.workflowName"
        const val SIGNAL_NAME = "io.temporal.common.metadata.workflowSignalName"
        const val QUERY_NAME = "io.temporal.common.metadata.workflowQueryType"
        val NAMES = setOf(ACTIVITY_NAME, WORKFLOW_NAME, SIGNAL_NAME, QUERY_NAME)

        /** The options whose copy is made with `toBuilder()`. */
        val COPIED_BY_BUILDER = setOf(
            TemporalApi.ACTIVITY_OPTIONS,
            TemporalApi.LOCAL_ACTIVITY_OPTIONS,
            "io.temporal.workflow.ChildWorkflowOptions",
            "io.temporal.workflow.NexusOperationOptions",
            "io.temporal.workflow.NexusServiceOptions",
            RETRY_OPTIONS,
            "io.temporal.client.WorkflowOptions",
            "io.temporal.client.WorkflowClientOptions",
            "io.temporal.worker.WorkerFactoryOptions",
        )

        /** The options whose copy is made with `newBuilder(options)`. */
        val COPIED_INTO_BUILDER = setOf(
            "io.temporal.workflow.ContinueAsNewOptions",
            "io.temporal.worker.WorkerOptions",
            "io.temporal.serviceclient.RpcRetryOptions",
            "io.temporal.serviceclient.WorkflowServiceStubsOptions",
        )

        /** The classes of options that have a function of their name which takes the setters as a lambda. */
        val OPTIONS = COPIED_BY_BUILDER + COPIED_INTO_BUILDER + TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS

        /** The builders that have a `setRetryOptions` which takes the retry options as a lambda. */
        val RETRIED = setOf(
            TemporalApi.ACTIVITY_OPTIONS_BUILDER,
            TemporalApi.LOCAL_ACTIVITY_OPTIONS_BUILDER,
            TemporalApi.CHILD_WORKFLOW_OPTIONS_BUILDER,
            "io.temporal.client.WorkflowOptions.Builder",
        )

        val WORKFLOW_OPTIONS_BUILDERS = setOf(TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS_BUILDER)

        val APPLY = Callee(FunctionId("kotlin.apply", null, LAMBDA, receiver = "T"), CalleeKind.FUNCTION, inSource = false)
        val MAP_OF = Callee(FunctionId("kotlin.collections.mapOf", null, PAIRS), CalleeKind.FUNCTION, inSource = false)

        fun type(name: String): TypeRef = TypeRef(name, name)

        fun callee(owner: String, name: String, signature: String, kind: CalleeKind = CalleeKind.FUNCTION): Callee =
            Callee(FunctionId("$owner.$name", owner, signature), kind, inSource = false)

        /** The package of a class of the SDK, or of a builder nested in one: what comes before the first name that begins with a capital. */
        fun packageOf(className: String): String = className.split('.').takeWhile { it.firstOrNull()?.isLowerCase() == true }.joinToString(".")
    }
}

/**
 * A [SourceBackend] whose code has the Kotlin extensions of the Temporal SDK replaced by what
 * they stand for; see [KotlinExtensions]. Declarations are handed on as they are. Code is
 * rewritten once and kept, so that asking for it again gives the very same objects.
 */
internal class ExtensionReadingBackend(private val sources: SourceBackend) : SourceBackend by sources {
    private val extensions = KotlinExtensions(TemporalNames(sources))
    private val bodies = HashMap<FunctionId, Block?>()
    private val initializers = HashMap<PropertyId, Code?>()
    private val defaults = HashMap<Pair<FunctionId, String>, Code?>()
    private val getters = HashMap<PropertyId, Block?>()
    private val constructions = HashMap<FunctionId, Construction?>()
    private val mentioning = HashMap<String, List<Block>>()

    override fun body(id: FunctionId): Block? = bodies.kept(id) { sources.body(id)?.let(::block) }

    override fun initializer(id: PropertyId): Code? = initializers.kept(id) { sources.initializer(id)?.let(extensions::expand) }

    override fun defaultValue(id: FunctionId, parameter: String): Code? =
        defaults.kept(id to parameter) { sources.defaultValue(id, parameter)?.let(extensions::expand) }

    override fun getter(id: PropertyId): Block? = getters.kept(id) { sources.getter(id)?.let(::block) }

    override fun construction(id: FunctionId): Construction? =
        constructions.kept(id) {
            sources.construction(id)?.let { made ->
                Construction(
                    // A constructor is no extension: only what it is handed can change.
                    delegate = made.delegate?.let { extensions.expand(it) as? Call ?: it },
                    initializers = made.initializers.map(extensions::expand),
                    body = made.body?.let(::block),
                )
            }
        }

    override fun bodiesMentioning(text: String): List<Block> = mentioning.getOrPut(text) { sources.bodiesMentioning(text).map(::block) }

    // A block is no call, so it stays a block.
    private fun block(block: Block): Block = extensions.expand(block) as Block

    /** Like `getOrPut`, but a `null` that was worked out is kept as well. */
    private inline fun <K, V> MutableMap<K, V>.kept(key: K, compute: () -> V): V {
        if (containsKey(key)) {
            @Suppress("UNCHECKED_CAST")
            return get(key) as V
        }
        return compute().also { put(key, it) }
    }
}
