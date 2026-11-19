package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CalleeKind
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.Throw
import io.github.iw.durableatlas.analyser.code.walk
import io.github.iw.durableatlas.ir.NodeKind

/** Whether a `catch` clause takes over when something fails. */
internal enum class Caught {
    /** Whatever is raised is of the type the clause catches. */
    ALWAYS,

    /** The clause catches a narrower type than is known to be raised: it may or may not take over. */
    SOMETIMES,

    NEVER,
}

/**
 * Knows which failures the steps of a workflow raise, which `catch` clauses they reach, and
 * which of the exceptions that leave a workflow fail its execution. Types are compared through
 * the class hierarchy of the sources and their libraries.
 *
 * An exception that leaves the workflow method, or a signal handler, fails the execution of
 * the workflow only if it is a failure of Temporal (`TemporalFailure`: an `ApplicationFailure`,
 * the failure of an activity or of a child workflow, a cancellation) or of a type a worker was
 * told to fail workflows on ([FailTypes]). Thrown by the handler of an update, such an
 * exception fails the update, and the workflow goes on. Any other exception fails the workflow
 * task: the SDK takes it for a bug, and tries the task again until the code is fixed.
 */
internal class FailureTypes(
    private val backend: SourceBackend,
    /** The exceptions that workers are told to fail a workflow on. */
    private val configured: FailTypes = FailTypes.NONE,
) {
    private val superTypes = HashMap<String, Set<String>?>()

    /** The types an exception has to be of, or to extend, to fail the execution of a workflow. */
    private val failing: Set<String> = (listOf(TEMPORAL_FAILURE) + configured.classes).mapTo(LinkedHashSet(), ::canonical)
    private val verdicts = HashMap<Pair<String, Boolean>, Boolean>()

    /**
     * Whether code that is not of the sources may throw what fails a workflow, beside the
     * steps of the workflow: a type that fails a workflow is declared outside the sources, or
     * the types could not be read.
     */
    val failsFromOutside: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        configured.unreadable.isNotEmpty() || configured.classes.any { backend.findClass(it)?.isSource != true }
    }

    /**
     * Whether [body], the body of a `try`, may throw what fails a workflow from code that is
     * not read: from outside the sources, or, where a worker is told of exceptions of its own,
     * from the constructor of a class of the sources, which may throw one of them.
     */
    fun mayThrowUnseen(body: Code): Boolean {
        if (failsFromOutside) return true
        if (configured.classes.isEmpty()) return false
        val thrown = body.walk().filterIsInstance<Throw>().mapNotNull { it.value }.toList()
        return body.walk().any { made -> made is Call && made.callee?.kind == CalleeKind.CONSTRUCTOR && made.callee.inSource && thrown.none { it === made } }
    }

    /**
     * Whether an exception may fail the execution of the workflow it leaves, and not just the
     * workflow task. It may if it is of a type that does, or if it cannot be ruled out that it
     * is: where the type is not known, where nothing is known of the class, and where what is
     * known is only a type that such failures extend, as of a variable of type `Exception`.
     *
     * @param type the type of what is thrown, as the code says it; `null` if it is not known
     * @param exact whether what is thrown is of that very class and of no subclass: it is made
     *   where it is thrown
     */
    fun failsExecution(type: String?, exact: Boolean): Boolean {
        if (configured.unreadable.isNotEmpty()) return true
        val thrown = canonical(type ?: return true)
        return verdicts.getOrPut(thrown to exact) {
            val above = superTypesOf(thrown) ?: return@getOrPut true
            when {
                thrown in failing || failing.any { it in above } -> true
                exact -> false
                // What is thrown may be of any class that extends the type.
                else -> failing.any { superTypesOf(it)?.contains(thrown) ?: true }
            }
        }
    }

    /**
     * Whether a clause that catches [caught] takes over when [raised] is thrown.
     *
     * @param raised the type known to be thrown, which what is really thrown may be a subtype
     *   of; `null` if nothing is known but that it is thrown
     * @param caught the type the clause names; `null` if it did not resolve
     */
    fun match(raised: String?, caught: String?): Caught {
        val clause = canonical(caught ?: return Caught.SOMETIMES)
        val thrown = canonical(raised ?: RUNTIME_EXCEPTION)
        if (clause == thrown || clause == THROWABLE) return Caught.ALWAYS
        val above = superTypesOf(thrown)
        return when {
            above != null && clause in above -> Caught.ALWAYS
            superTypesOf(clause)?.contains(thrown) == true -> Caught.SOMETIMES
            // Nothing is known about what is thrown: a clause for what any code may throw is the best guess.
            above == null && clause in CATCH_ALL -> Caught.ALWAYS
            else -> Caught.NEVER
        }
    }

    private fun superTypesOf(type: String): Set<String>? =
        superTypes.getOrPut(type) { backend.findClass(type)?.allSuperTypes?.map(::canonical)?.toSet() }

    companion object {
        private const val THROWABLE = "kotlin.Throwable"
        private const val RUNTIME_EXCEPTION = "java.lang.RuntimeException"
        private const val TEMPORAL_FAILURE = "io.temporal.failure.TemporalFailure"
        private const val ACTIVITY_FAILURE = "io.temporal.failure.ActivityFailure"
        private const val CANCELED_FAILURE = "io.temporal.failure.CanceledFailure"
        private const val CHILD_WORKFLOW_FAILURE = "io.temporal.failure.ChildWorkflowFailure"
        private const val NEXUS_OPERATION_FAILURE = "io.temporal.failure.NexusOperationFailure"
        private const val SIGNAL_EXTERNAL_EXCEPTION = "io.temporal.workflow.SignalExternalWorkflowException"
        private const val COMPENSATION_EXCEPTION = "io.temporal.workflow.Saga.CompensationException"

        private val CATCH_ALL = setOf(THROWABLE, "java.lang.Exception", RUNTIME_EXCEPTION)

        /** One name for each class that Kotlin and Java call differently. */
        private val CANONICAL: Map<String, String> = buildMap {
            put("java.lang.Throwable", THROWABLE)
            val inJavaLang = listOf(
                "Error", "Exception", "RuntimeException", "IllegalArgumentException", "IllegalStateException",
                "IndexOutOfBoundsException", "UnsupportedOperationException", "ArithmeticException",
                "NumberFormatException", "NullPointerException", "ClassCastException", "AssertionError",
            )
            for (name in inJavaLang) put("kotlin.$name", "java.lang.$name")
            for (name in listOf("NoSuchElementException", "ConcurrentModificationException")) put("kotlin.$name", "java.util.$name")
        }

        private fun canonical(type: String): String = CANONICAL[type] ?: type

        /**
         * The failures a step of kind [kind] is known to raise when it does not succeed. Anything
         * that blocks is interrupted by the cancellation of the workflow. A step that could not
         * be resolved may raise any failure Temporal knows.
         */
        fun raisedBy(kind: NodeKind): List<String> =
            when (kind) {
                NodeKind.ACTIVITY, NodeKind.LOCAL_ACTIVITY -> listOf(ACTIVITY_FAILURE, CANCELED_FAILURE)
                NodeKind.CHILD_WORKFLOW -> listOf(CHILD_WORKFLOW_FAILURE, CANCELED_FAILURE)
                NodeKind.NEXUS_OPERATION -> listOf(NEXUS_OPERATION_FAILURE, CANCELED_FAILURE)
                NodeKind.TIMER, NodeKind.AWAIT -> listOf(CANCELED_FAILURE)
                NodeKind.SIGNAL_EXTERNAL -> listOf(SIGNAL_EXTERNAL_EXCEPTION)
                NodeKind.COMPENSATION -> listOf(COMPENSATION_EXCEPTION)
                NodeKind.UNKNOWN -> listOf(TEMPORAL_FAILURE)
                else -> emptyList()
            }
    }
}
