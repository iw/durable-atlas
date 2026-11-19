package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.ClassLiteral
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.walk

/**
 * What workers are told about the workflows they run: the options for activities that are
 * handed to `Worker.registerWorkflowImplementationTypes` together with the classes that
 * implement the workflows. A workflow starts out with them, before any of its own code runs.
 * The Kotlin extensions of that function are read as the function itself; see [KotlinExtensions].
 *
 * The calls are looked for wherever the sources hold their name: in functions, and in what
 * runs when a class or a file is set up, an `init` block, the body of a constructor or the
 * initialiser of a property. Only classes written out at the call, as in
 * `OrderWorkflowImpl::class.java`, are known to be registered there. A class that is registered
 * in several places has what all of them agree on.
 */
internal class WorkerRegistrations(
    private val backend: SourceBackend,
    private val tracer: ValueTracer,
    private val activities: ActivityOptionsReader,
) {
    private val byClass: Map<String, ActivityEnvironment> by lazy(LazyThreadSafetyMode.NONE, ::read)

    /** The options for activities that a workflow of the class [implementation] is given by the worker that runs it. */
    fun optionsFor(implementation: String): ActivityEnvironment = byClass[implementation] ?: NOTHING

    private fun read(): Map<String, ActivityEnvironment> {
        // Options are handed over only where their class is named somewhere, or where the Kotlin extensions
        // are in reach that take them as a lambda, which spares looking for the calls.
        if (OPTIONS_NAMES.none(backend::mentions)) return emptyMap()
        val found = LinkedHashMap<String, ActivityEnvironment>()
        for (body in backend.bodiesMentioning(REGISTER_NAME)) {
            val scope = Scope.of(body)
            for (call in body.walk().filterIsInstance<Call>()) {
                val function = call.callee?.function?.takeIf { it.name == TemporalApi.REGISTER_WORKFLOWS } ?: continue
                val withOptions = function.signature.startsWith("(${TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS}")
                val given = if (withOptions) call.arguments.firstOrNull()?.let { environment(it.value, scope) } ?: UNKNOWN else NOTHING
                val classes = call.arguments.drop(if (withOptions) 1 else 0).mapNotNull { className(it.value, scope) }
                for (name in classes) found.merge(name, given) { one, other -> one.orElse(other) }
            }
        }
        return found
    }

    /** The class that `Name::class.java` names. */
    private fun className(code: Code, scope: Scope): String? {
        val read = tracer.origin(code, scope).code as? PropertyRead ?: return null
        return (read.receiver as? ClassLiteral)?.takeIf { read.name == "java" }?.type?.name
    }

    /**
     * What the options [code] evaluates to give a workflow for its activities. Options that are
     * built from other options, with `newBuilder(other)` or `other.toBuilder()`, begin with
     * what those give; a default or a map the builder is then given takes the place of the one
     * it had.
     *
     * @param copies how many more times options made from other options are followed to those
     */
    private fun environment(code: Code, scope: Scope, copies: Int = MAX_COPIES): ActivityEnvironment {
        val options = tracer.origin(code, scope, settled = false)
        val start = BuilderChains.start(options, TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS, TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS_BUILDER, tracer)
        val from = start?.from
        val began = when {
            start == null -> if (builtFromNothing(options.code)) NOTHING else UNKNOWN
            start.open -> UNKNOWN
            from == null || activities.isNull(from.code, from.scope) -> NOTHING
            copies == 0 -> UNKNOWN
            else -> environment(from.code, from.scope, copies - 1)
        }
        val set = Builders.setters(options.code, TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS_BUILDER)
        fun defaults(began: ActivityDefaults, default: String, byType: String, local: Boolean): ActivityDefaults {
            var defaults = began
            set[default]?.let { defaults = defaults.copy(default = activities.settings(it.value, options.scope, local)) }
            set[byType]?.let { given ->
                val types = activities.byType(given.value, options.scope, local)
                // A map that cannot be read may hold anything for any type.
                defaults = defaults.copy(byType = types.orEmpty(), otherTypes = if (types == null) ActivitySettings.UNKNOWN else ActivitySettings.NONE)
            }
            return defaults
        }
        return ActivityEnvironment(
            plain = defaults(began.plain, "setDefaultActivityOptions", "setActivityOptions", local = false),
            local = defaults(began.local, "setDefaultLocalActivityOptions", "setLocalActivityOptions", local = true),
        )
    }

    /**
     * Whether the options [made] are built by a builder that is not seen to be made from other
     * options, where the call that makes the builder is not one this reading knows.
     */
    private fun builtFromNothing(made: Code): Boolean {
        val called = made.walk().filterIsInstance<Call>().mapNotNull { it.callee?.function }.toList()
        val copied = called.any {
            it.owner == TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS && (it.simpleName == "toBuilder" || (it.simpleName == "newBuilder" && it.signature != "()"))
        }
        return called.any { it.owner == TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS_BUILDER } && !copied
    }

    private companion object {
        /**
         * What the calls that register workflows are called where they are written: the function
         * of a worker, and the Kotlin extension that names one class as a type argument, which
         * is called the same without the last letter.
         */
        const val REGISTER_NAME = "registerWorkflowImplementationType"

        /**
         * What the sources hold where options can be handed over: the name of their class, or
         * an import of the Kotlin extensions that take them as a lambda, one by one or with
         * their package as a whole.
         */
        val OPTIONS_NAMES = listOf("WorkflowImplementationOptions", "io.temporal.worker.registerWorkflowImplementationType", "io.temporal.worker.*")

        /** How many times options made from other options are followed to those. */
        const val MAX_COPIES = 8

        val NOTHING = ActivityEnvironment()
        val UNKNOWN_DEFAULTS = ActivityDefaults(default = ActivitySettings.UNKNOWN, otherTypes = ActivitySettings.UNKNOWN)
        val UNKNOWN = ActivityEnvironment(UNKNOWN_DEFAULTS, UNKNOWN_DEFAULTS)
    }
}
