package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Argument
import io.github.iw.durableatlas.analyser.code.Block
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CalleeKind
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.Other
import io.github.iw.durableatlas.analyser.code.ThisReference
import io.github.iw.durableatlas.analyser.code.walk
import io.github.iw.durableatlas.ir.ParentClosePolicy

/** What a stub stands for, as told by the function of `Workflow` that made it. */
internal enum class StubKind(val creator: String, val optionsAt: Int?) {
    ACTIVITY("newActivityStub", 1),
    LOCAL_ACTIVITY("newLocalActivityStub", 1),
    UNTYPED_ACTIVITY("newUntypedActivityStub", 0),
    UNTYPED_LOCAL_ACTIVITY("newUntypedLocalActivityStub", 0),
    CHILD_WORKFLOW("newChildWorkflowStub", 1),
    UNTYPED_CHILD_WORKFLOW("newUntypedChildWorkflowStub", 1),
    EXTERNAL_WORKFLOW("newExternalWorkflowStub", null),
    UNTYPED_EXTERNAL_WORKFLOW("newUntypedExternalWorkflowStub", null),
    CONTINUE_AS_NEW("newContinueAsNewStub", null),
    NEXUS_SERVICE("newNexusServiceStub", 1),
    UNTYPED_NEXUS_SERVICE("newUntypedNexusServiceStub", 1),
    ;

    val isLocalActivity: Boolean get() = this == LOCAL_ACTIVITY || this == UNTYPED_LOCAL_ACTIVITY

    /** Whether this is a stub of an activity interface: only such a stub looks at what the workflow has set for its activities. */
    val isTypedActivity: Boolean get() = this == ACTIVITY || this == LOCAL_ACTIVITY

    /** Whether the stub is made of an interface, which the creating call is given first. */
    val hasInterface: Boolean
        get() = this == ACTIVITY || this == LOCAL_ACTIVITY || this == CHILD_WORKFLOW || this == EXTERNAL_WORKFLOW ||
            this == CONTINUE_AS_NEW || this == NEXUS_SERVICE

    companion object {
        private val byCreator = entries.associateBy { "${TemporalApi.WORKFLOW}.${it.creator}" }

        fun of(call: Call): StubKind? = call.callee?.function?.name?.let(byCreator::get)

        /**
         * The kind of stub [call] makes.
         *
         * @param lenient also where the call did not resolve, as long as every function the
         *   compiler considered for it is the same maker of stubs: which of them it is makes
         *   no difference to what is made
         */
        fun of(call: Call, lenient: Boolean): StubKind? =
            of(call) ?: call.unresolved?.candidates?.takeIf { lenient }?.map { it.function.name }?.distinct()?.singleOrNull()?.let(byCreator::get)
    }
}

/**
 * The call that made a stub, with the scope its arguments are to be read in.
 *
 * @param via the way the stub was followed to that call, from the expression that names the
 *   stub where it is used: the variables and properties that hold the stub, and the calls of
 *   functions that make it and hand it back
 */
internal class StubCreation(val kind: StubKind, val call: Call, val scope: Scope, val via: Trail)

/** What the options a stub was made with say about the calls made through it. */
internal data class StubOptions(
    val taskQueue: String? = null,
    val timeout: String? = null,
    /** What becomes of a child workflow when its parent closes, where the options say that it is not ended with it. */
    val parentClosePolicy: ParentClosePolicy? = null,
)

/**
 * Finds out where stubs come from. The type of a stub says which interface it stands for, but not
 * whether an activity runs locally, or whether a workflow stub starts a child, signals another
 * workflow or continues as new; only the creating call does, and it also holds the options.
 */
internal class Stubs(
    private val tracer: ValueTracer,
    private val constants: Constants,
    /** Whether a creating call that did not resolve counts where it can only be one maker of stubs; see [StubKind.of]. */
    private val lenient: Boolean = false,
) {
    /** Reads the options of activities, here and wherever else they are set. */
    val activities = ActivityOptionsReader(tracer, constants)

    /** The call that made the stub [code] evaluates to, if it can be found. */
    fun creation(code: Code, scope: Scope): StubCreation? {
        val trail = tracer.trail(code, scope, settled = false)
        val traced = trail.passed.last()
        val call = traced.code as? Call ?: return null
        return StubKind.of(call, lenient)?.let { StubCreation(it, call, traced.scope, trail) }
    }

    /** The text argument at [index] of the creating call: an untyped stub's workflow type or service. */
    fun name(creation: StubCreation, index: Int = 0): String? =
        constants.text(creation.call.arguments.getOrNull(index), creation.scope)

    /**
     * Task queue and timeout of an activity that is run through the stub of [creation]. The
     * timeout is the one after which the caller gives up: schedule-to-close before
     * start-to-close.
     *
     * The options are put together the way the SDK does it. A stub of an activity interface
     * that is made without options has the default the workflow had set by then; one made with
     * options has those, and the default counts for nothing. Over either lie the options for
     * the one activity type: those of the map the stub was made with, if it was made with one,
     * and otherwise those the workflow has set by the time of the call. A stub without an
     * interface has the options it was made with and nothing else.
     *
     * @param type the activity type that is run; `null` through a stub without an interface
     * @param inForce what the workflow has set for its activities where the call is made; `null` where that is not asked
     */
    fun activityOptions(creation: StubCreation, type: String?, inForce: OptionsInForce?): StubOptions {
        val local = creation.kind.isLocalActivity
        val typed = creation.kind.isTypedActivity
        fun given(index: Int?) = index?.let(creation.call.arguments::getOrNull)?.value?.takeUnless { activities.isNull(it, creation.scope) }
        val own = given(creation.kind.optionsAt)
        val base = when {
            own != null -> activities.settings(own, creation.scope, local)
            typed -> inForce?.defaults(creation, local) ?: ActivitySettings.NONE
            else -> ActivitySettings.NONE
        }
        val map = given(METHOD_OPTIONS_AT).takeIf { typed }
        val forType = when {
            !typed || type == null -> ActivitySettings.NONE
            // A map the stub was made with stands in for what the workflow has set, whatever it holds.
            map != null -> activities.byType(map, creation.scope, local)?.let { it[type] ?: ActivitySettings.NONE } ?: ActivitySettings.UNKNOWN
            else -> inForce?.forType(type, local) ?: ActivitySettings.NONE
        }
        val settings = base.under(forType)
        return StubOptions(taskQueue = settings.taskQueue.known, timeout = settings.timeout)
    }

    /**
     * Task queue and timeout from the options of the call that made the stub of a child workflow
     * or of a Nexus service. The timeout is the one after which the caller gives up: execution
     * before run for child workflows, schedule-to-close before start-to-close for Nexus
     * operations. For activities see [activityOptions].
     */
    fun options(creation: StubCreation): StubOptions {
        val index = creation.kind.optionsAt ?: return StubOptions()
        val argument = creation.call.arguments.getOrNull(index) ?: return StubOptions()
        val options = tracer.origin(argument.value, creation.scope, settled = false)
        return when (creation.kind) {
            StubKind.CHILD_WORKFLOW, StubKind.UNTYPED_CHILD_WORKFLOW -> {
                val set = setters(options, TemporalApi.CHILD_WORKFLOW_OPTIONS_BUILDER)
                StubOptions(
                    taskQueue = constants.text(set["setTaskQueue"], options.scope),
                    timeout = duration(set["setWorkflowExecutionTimeout"], options.scope)
                        ?: duration(set["setWorkflowRunTimeout"], options.scope),
                    parentClosePolicy = ParentClosePolicy.ABANDON.takeIf {
                        constants.enumEntry(set["setParentClosePolicy"], options.scope, TemporalApi.PARENT_CLOSE_POLICY) == ABANDON
                    },
                )
            }
            StubKind.NEXUS_SERVICE, StubKind.UNTYPED_NEXUS_SERVICE -> {
                val operationOptions = setters(options, TemporalApi.NEXUS_SERVICE_OPTIONS_BUILDER)["setOperationOptions"]
                    ?.let { tracer.origin(it.value, options.scope, settled = false) }
                    ?: return StubOptions()
                val set = setters(operationOptions, TemporalApi.NEXUS_OPERATION_OPTIONS_BUILDER)
                StubOptions(
                    timeout = duration(set["setScheduleToCloseTimeout"], operationOptions.scope)
                        ?: duration(set["setStartToCloseTimeout"], operationOptions.scope),
                )
            }
            else -> StubOptions()
        }
    }

    private fun duration(argument: Argument?, scope: Scope): String? =
        argument?.let { constants.duration(it.value, scope) }?.toString()

    private fun setters(options: Traced, builder: String): Map<String, Argument> = Builders.setters(options.code, builder)
}

/** Where `newActivityStub` and `newLocalActivityStub` take the options for single activity types. */
private const val METHOD_OPTIONS_AT = 2

/** What the SDK calls the policy by which a child workflow outlives its parent. */
private const val ABANDON = "PARENT_CLOSE_POLICY_ABANDON"

/** Reads what was given to the builders that options and failures are made with. */
internal object Builders {
    /**
     * The setters of the builder class [builder] that are called on the builder which
     * [expression] makes its object with, each with what it is given. The builder is followed
     * from the call that builds back to where it begins: through the setters chained on it, and
     * through the lambdas it is handed to with `apply` and `also`, whose statements run on it in
     * their order. A setter that is called more than once counts as it is called last.
     *
     * A setter that a lambda calls where it may not run, in a branch, a loop or a `try`, may or
     * may not have been called: it is listed with an argument nothing can be read from, so that
     * what it sets is not known. What the builder began with, the options it was made from, is
     * no part of this; see [BuilderChains].
     */
    fun setters(expression: Code, builder: String): Map<String, Argument> {
        val found = LinkedHashMap<String, Argument>()
        SetterReading(builder, found).builder(expression)
        return found
    }

    private class SetterReading(private val builder: String, private val found: MutableMap<String, Argument>) {
        /** Follows the builder that [code] evaluates to, or builds with, back to where it begins, and notes its setters on the way back. */
        fun builder(code: Code?) {
            val call = unwrapped(code) as? Call ?: return
            val function = call.callee?.function ?: return
            when {
                function.owner == builder && call.callee.kind == CalleeKind.FUNCTION -> {
                    builder(call.receiver)
                    note(call, certain = true)
                }
                function.name in GIVE_BACK_RECEIVER -> {
                    builder(call.receiver)
                    for (argument in call.arguments) {
                        val lambda = argument.value as? Lambda ?: continue
                        // `apply` makes the builder `this`; `also` hands it over as the parameter.
                        statements(lambda.body, lambda.parameters.toSet() + IMPLICIT_PARAMETER, certain = true)
                    }
                }
                // Where the builder begins, or a call this reading does not know.
                else -> Unit
            }
        }

        /** The statements of a lambda that runs on the builder, in the order they run. */
        private fun statements(block: Block, parameters: Set<String>, certain: Boolean) {
            for (statement in block.statements) {
                val call = unwrapped(statement) as? Call
                when {
                    statement is Block -> statements(statement, parameters, certain)
                    call != null && call.callee?.function?.owner == builder -> own(call, parameters, certain)
                    // Anything else may or may not run what it holds.
                    else -> statement.walk().filterIsInstance<Call>().filter { it.callee?.function?.owner == builder }.forEach { own(it, parameters, certain = false) }
                }
            }
        }

        /**
         * Notes [call], a call of a function of the builder's class inside a lambda, if it is
         * made on the builder the lambda runs on: on nothing that is written, on `this`, on the
         * parameter of the lambda, or on what such a call gives back. Whether it is, is the answer.
         */
        private fun own(call: Call, parameters: Set<String>, certain: Boolean): Boolean {
            val on = unwrapped(call.receiver)
            val onBuilder = when (on) {
                null, is ThisReference -> true
                is LocalRead -> on.name in parameters
                is Call -> on.callee?.function?.owner == builder && own(on, parameters, certain)
                else -> false
            }
            if (onBuilder) note(call, certain)
            return onBuilder
        }

        private fun note(call: Call, certain: Boolean) {
            val name = call.callee?.function?.simpleName?.takeIf { it.startsWith("set") } ?: return
            val given = call.arguments.singleOrNull() ?: return
            found[name] = if (certain) given else given.copy(constant = null, value = Other(null, given.text, emptyList(), given.value.location))
        }

        private fun unwrapped(code: Code?): Code? {
            var current = code
            while (current is Other && current.operator in TRANSPARENT && current.operands.size == 1) current = current.operands.single()
            return current
        }
    }

    /** Functions that give back what they are called on, after handing it to their lambda. */
    private val GIVE_BACK_RECEIVER = setOf("kotlin.apply", "kotlin.also")

    /** Operators that hand their operand on unchanged. */
    private val TRANSPARENT = setOf("as", "as?", "!!")

    private const val IMPLICIT_PARAMETER = "it"
}
