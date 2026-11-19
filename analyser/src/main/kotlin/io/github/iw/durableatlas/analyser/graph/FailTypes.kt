package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CalleeKind
import io.github.iw.durableatlas.analyser.code.ClassLiteral
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.walk
import io.github.iw.durableatlas.ir.SourceLocation

/**
 * The exceptions that workers are told to fail a workflow on, beside the failures of Temporal:
 * the classes handed to `WorkflowImplementationOptions.Builder.setFailWorkflowExceptionTypes`.
 * An exception of any other type that leaves a workflow fails the workflow task, which is tried
 * again until the code is fixed: the execution neither fails nor goes on.
 */
internal class FailTypes(
    /** Fully qualified names of the classes, as the sources name them. */
    val classes: Set<String>,
    /**
     * Where the sources hand over something that is no class literal, so that nothing is known
     * of what fails a workflow: any exception may. Empty where every place could be read.
     */
    val unreadable: List<SourceLocation> = emptyList(),
) {
    companion object {
        val NONE = FailTypes(emptySet())
    }
}

/**
 * Finds the exceptions that workers are told to fail a workflow on, wherever the sources say so:
 * in Kotlin and in Java, in every module, with the builder of the options or with the options
 * written as a lambda, which calls the same function of the builder. A workflow that is
 * registered with `Worker.addWorkflowImplementationFactory` and without options is failed on
 * whatever is thrown: that function, which the SDK keeps for tests, says so for it.
 *
 * They are taken together for every workflow: which worker runs which workflow with which
 * options is not followed, and a type that fails one workflow is taken to fail any. That errs
 * on the side of drawing a failure.
 *
 * Only what the sources of the model hold is seen. Options that are put together in another
 * repository, or by anything that does not call the function by its name, are not; where
 * they name further exceptions, a failure of the workflow is missing from its graph.
 */
internal class FailTypeReader(
    private val backend: SourceBackend,
    private val tracer: ValueTracer,
) {
    fun read(): FailTypes {
        val classes = LinkedHashSet<String>()
        val unreadable = ArrayList<SourceLocation>()

        fun take(given: List<String>?, place: SourceLocation) {
            if (given == null) unreadable += place else classes += given
        }
        for (body in backend.bodiesMentioning(SETTER)) {
            val scope = Scope.of(body)
            for (call in body.walk().filterIsInstance<Call>()) {
                val function = call.callee?.function
                // A call that did not resolve is taken for the one its name says.
                val sets = if (function == null) call.name == SETTER else function.owner == TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS_BUILDER && function.simpleName == SETTER
                if (sets) call.arguments.forEach { take(classesOf(it.value, scope), call.location) }
            }
        }
        // The options can be made without their builder, by a constructor that takes the types first.
        for (body in backend.bodiesMentioning(CONSTRUCTED)) {
            val scope = Scope.of(body)
            for (call in body.walk().filterIsInstance<Call>()) {
                val callee = call.callee ?: continue
                if (callee.kind != CalleeKind.CONSTRUCTOR || callee.function.owner != TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS) continue
                take(call.arguments.firstOrNull()?.let { classesOf(it.value, scope) }, call.location)
            }
        }
        for (call in backend.javaCalls(TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS_BUILDER, SETTER)) {
            take(call.classLiterals.takeIf { null !in it }?.filterNotNull(), call.location)
        }
        // In Java the constructor takes an array, which is never a class literal.
        backend.javaCalls(TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS, CONSTRUCTOR).forEach { unreadable += it.location }
        if (registersForTests()) classes += THROWABLE
        return FailTypes(classes, unreadable.distinct().sortedWith(compareBy({ it.file }, { it.line })))
    }

    /**
     * Whether a workflow is registered by the function of a worker that takes a factory and no
     * options, or by the Kotlin extension that calls it. A call that did not resolve is taken
     * for one.
     */
    private fun registersForTests(): Boolean {
        val inKotlin = backend.bodiesMentioning(FACTORY).any { body ->
            body.walk().filterIsInstance<Call>().any { call ->
                val function = call.callee?.function
                when {
                    function == null -> call.name == FACTORY
                    function.simpleName != FACTORY || OPTIONS_FIRST.any { function.signature.startsWith(it) } -> false
                    else -> function.owner == WORKER || function.name == "$WORKER_PACKAGE.$FACTORY"
                }
            }
        }
        // In Java it is the one that is handed two things: the interface and the factory.
        return inKotlin || backend.javaCalls(WORKER, FACTORY).any { it.classLiterals.size == 2 }
    }

    /**
     * The classes [code] names: the one of `Name::class.java`, or those of an array written
     * out with such literals. `null` if it is anything else.
     */
    private fun classesOf(code: Code, scope: Scope): List<String>? {
        val origin = tracer.origin(code, scope)
        return when (val made = origin.code) {
            is PropertyRead -> (made.receiver as? ClassLiteral)?.takeIf { made.name == "java" }?.type?.name?.let(::listOf)
            is Call -> made.takeIf { it.callee?.function?.name in ARRAYS }?.arguments?.flatMap { classesOf(it.value, origin.scope) ?: return null }
            else -> null
        }
    }

    private companion object {
        const val SETTER = "setFailWorkflowExceptionTypes"

        /** What the sources hold where the options are made by their constructor. */
        const val CONSTRUCTED = "WorkflowImplementationOptions("
        const val CONSTRUCTOR = "<init>"

        /** The function of a worker that registers a factory of workflows, and fails them on any exception where it is given no options. */
        const val FACTORY = "addWorkflowImplementationFactory"
        const val WORKER_PACKAGE = "io.temporal.worker"
        const val WORKER = "$WORKER_PACKAGE.Worker"
        const val THROWABLE = "kotlin.Throwable"

        /** How the parameters of a function begin that is given options. */
        val OPTIONS_FIRST = listOf("(${TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS},", "(${TemporalApi.WORKFLOW_IMPLEMENTATION_OPTIONS})")

        /** The functions that make an array of what they are handed. */
        val ARRAYS = setOf("kotlin.arrayOf", "kotlin.emptyArray")
    }
}
