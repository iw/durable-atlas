package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.SourceLocation
import java.util.Collections
import java.util.IdentityHashMap

/**
 * What the body of an entry point does, as far as its graph is concerned: the steps of the
 * workflow, and the ways the flow between them forks, repeats, runs side by side, fails and ends.
 * Code that is no step and steers nothing is not part of it.
 *
 * A flow is read off the code first ([FlowReader]) and laid out as nodes and edges afterwards
 * ([GraphLayout]), so that what a construct holds is known before anything is drawn for it.
 */
internal sealed interface Flow {
    /** Whether this gives the graph anything: a node, or a path that ends. */
    val hasContent: Boolean

    /** Whether the flow can go on after this; `true` wherever that cannot be told. */
    val completes: Boolean get() = true
}

/** Parts that run one after the other. */
internal class Series(val parts: List<Flow>) : Flow {
    override val hasContent: Boolean = parts.any { it.hasContent }
    override val completes: Boolean = parts.all { it.completes }

    companion object {
        val EMPTY = Series(emptyList())

        fun of(parts: List<Flow>): Flow = parts.singleOrNull() ?: Series(parts)
    }
}

/** One step of the workflow, which becomes one node. */
internal class Step(
    /** The node, without id and parent. */
    var node: IrNode,
    val location: SourceLocation?,
    /** The ways the step can turn out, if code after it may tell them apart. */
    val outcomes: Outcomes? = null,
    /**
     * For the place where a cancellation scope is cancelled: the scope, as a [ScopeKey], or the
     * property that holds it where only that is known; `null` if nothing says which scope it is.
     */
    val cancels: Any? = null,
    /** For a wait on a workflow queue: what it waits for there. */
    val queue: QueueWait? = null,
    /** True for a wait that raises a `TimeoutException` when its time is up, for whichever clause catches it. */
    val timesOut: Boolean = false,
    /**
     * False for a node that stands for a call nothing is known of: one of a function that no
     * class of the sources implements. A step may lie behind it; none is certain to.
     */
    val firm: Boolean = true,
    /**
     * What the call is called that the node stands for, where the node does not say so itself:
     * the function or method called. An overview names the call on every node of a call that
     * could not be read, and lists by it the calls it leaves out (see [LeftOut]).
     */
    val called: String? = null,
) : Flow {
    /** True for a child workflow whose start the flow waits for, by waiting for its execution. */
    var startAwaited = false

    /** True if that wait stands in another lane than the step: the lane of the step waits for the child to end as for any step. */
    var startAwaitedOutside = false

    /**
     * For a wait in the workflow method: the properties it waits on that the workflow method
     * itself changes while the wait may stand, in another lane. Empty where it changes none.
     */
    var wokenByFlow: List<String> = emptyList()

    override val hasContent: Boolean get() = true
    override val completes: Boolean get() = !node.kind.isTerminal
}

/** A cancellation scope, by the call that made it: the same one wherever its code is read. */
internal class ScopeKey

/** What a wait on a workflow queue waits for, which says what ends it. */
internal enum class QueueWait {
    /** Something to take: whoever puts something in ends the wait. */
    ITEMS,

    /** Room to put something in: whoever takes something out ends the wait. */
    ROOM,
}

/**
 * Lanes of which the flow waits for the first to end and no longer: those whose promises are
 * handed to `Promise.anyOf` together.
 */
internal class Race {
    val lanes: MutableSet<Fork> = Collections.newSetFromMap(IdentityHashMap())
}

/** An `if`, a `when`, or an operator that runs its right-hand side only sometimes. */
internal class Choice(
    val location: SourceLocation,
    /** What is tested, for the branch node. */
    val label: String?,
    val arms: List<Arm>,
    /**
     * The step whose outcomes alone decide which arm is taken, if there is one. Laid out right
     * after that step, the choice needs no node of its own: each arm leaves the step itself.
     */
    val outcomes: Outcomes? = null,
    /**
     * True if the code has more ways to go than [arms]: the others end in a failure that is not
     * drawn (see [Stuck]). A choice that is left with one arm is no decision to draw.
     */
    val narrowed: Boolean = false,
) : Flow {
    override val hasContent: Boolean get() = true
    override val completes: Boolean = arms.any { it.body.completes }
}

/** One way a [Choice] can go. An arm without content still stands for a path, which leads on. */
internal class Arm(
    /** The caption of the edge into the arm. */
    val label: String?,
    val body: Flow,
    /** The outcomes that take this arm, where the choice is decided by a step's outcomes. */
    val scenarios: Set<Scenario> = emptySet(),
    /**
     * What the edge into the arm is called for each of those outcomes, where that is not what
     * the outcome itself is called: several conditions of an await can hold at once, and the
     * edge then says which of them leads this way.
     */
    val captions: Map<Scenario, String?> = emptyMap(),
) {
    /** What the edge that [scenario] takes into this arm is called. */
    fun caption(scenario: Scenario): String? = if (scenario in captions) captions[scenario] else scenario.caption
}

/** A loop: a `for`, a `while`, or a function that runs a lambda for each element of something. */
internal class Repeat(
    val location: SourceLocation,
    val label: String?,
    val body: Flow,
    /** What `break` and `continue` inside [body] name. */
    val frame: Int,
    /** True if the loop is only ever left by a jump out of its body. */
    val endless: Boolean = false,
    /** True for a retry: the body runs again for as long as it fails, after a pause each time. */
    val retry: Boolean = false,
    /**
     * The number that every frame opened inside [body] has at least; see [holdsSteps]. It is
     * [frame] for a loop the code writes out, whose frame is numbered before its body is read.
     */
    val floor: Int = frame,
) : Flow {
    override val hasContent: Boolean get() = true
}

/**
 * Code started to run beside the flow: one lane of a parallel region.
 *
 * @param body what the lane does. A promise chained to the one the lane gives adds to it: what
 *   is chained runs when the lane is done.
 */
internal class Fork(val location: SourceLocation, var body: Flow) : Flow {
    /**
     * False if nothing ever waits for the promise the lane gives: the flow goes on at once, and
     * no place joins the lane.
     */
    var awaited = true

    /** The race the lane runs in, if the flow waits for it together with others and only for the first of them to end. */
    var race: Race? = null

    override val hasContent: Boolean get() = true
}

/**
 * What a cancellation scope runs, in the place where it runs. It is nothing of its own in the
 * graph: it says which nodes the scope holds, for the place where the scope is cancelled.
 */
internal class Scoped(val key: ScopeKey, val body: Flow) : Flow {
    override val hasContent: Boolean get() = body.hasContent
    override val completes: Boolean get() = body.completes
}

/** The place where the flow waits for what was started beside it. */
internal data object Join : Flow {
    override val hasContent: Boolean get() = false
}

/** A `try`: a body, the clauses that take over when it fails, and what runs on every way out. */
internal class Protected(
    val body: Flow,
    val handlers: List<Handler>,
    val finally: Flow,
) : Flow {
    override val hasContent: Boolean get() = true
    override val completes: Boolean = finally.completes && (body.completes || handlers.any { it.body.completes })
}

internal class Handler(
    /** Fully qualified name of the type caught; `null` if it did not resolve. */
    val type: String?,
    /** The type as an edge shows it. */
    val label: String,
    val body: Flow,
)

/** The body of a lambda that runs in place: a `return` that names it ends here. */
internal class LambdaBody(val frame: Int, val body: Flow) : Flow {
    override val hasContent: Boolean get() = body.hasContent
}

/**
 * The body of a function of the sources, standing where the function is called: its steps are
 * steps of the flow that calls it. A `return` in the function ends here. Only a function that
 * holds a step for certain stands in the flow like this; see [Undrawn] for the others.
 */
internal class Inlined(
    /** What a `return` out of the function names. */
    val frame: Int,
    /** The call that was followed, as the ids of the nodes inside take it in. */
    val site: String,
    val body: Flow,
) : Flow {
    override val hasContent: Boolean get() = true
    override val completes: Boolean = body.completes || body.leaves(frame)
}

/** A `return`: out of the lambda or the followed function [frame], or out of the entry point if `null`. */
internal class Leave(val frame: Int?) : Flow {
    override val hasContent: Boolean get() = true
    override val completes: Boolean get() = false
}

/** A `throw`, or a call that never returns. */
internal class Raise(
    /** Fully qualified name of the type thrown; `null` if it is not known. */
    val type: String?,
    /** What the failure is called if nothing catches it. */
    val name: String?,
    val location: SourceLocation,
    /**
     * Whether the failure may fail the execution of the workflow if nothing catches it (see
     * [FailureTypes.failsExecution]). One that cannot fails the workflow task: the flow neither
     * fails nor goes on, and nothing is drawn for it but the way to a clause that catches it.
     */
    val real: Boolean = true,
) : Flow {
    override val hasContent: Boolean get() = true
    override val completes: Boolean get() = false
}

/**
 * The end of a way that nothing is drawn for: a failure that nothing catches and that fails
 * the workflow task, not the execution of the workflow. The task is tried again until the code
 * is fixed, so the flow does not go on from here, and there is no node it ends at. A flow holds
 * one only once it is known what catches what where it is laid out (see [GraphLayout]).
 */
internal data object Stuck : Flow {
    override val hasContent: Boolean get() = false
    override val completes: Boolean get() = false
}

/**
 * A call of a function of the sources that is not drawn from the inside, because no step is
 * certain to lie in it, and of which something may still matter where it is called: it may fail,
 * or it calls something that cannot be followed. The place of the call says so, unless all it
 * may fail with fails the workflow task alone and no clause takes it.
 */
internal class Undrawn(
    /** What the function is called. */
    val label: String,
    val location: SourceLocation,
    /** The failures that may come out of it, in a fixed order. */
    val raises: List<Thrown>,
    /** The functions without a body that it may call and that no class of the sources implements, in a fixed order. */
    val unseen: List<String>,
    /** True if the call never returns: it fails whenever it is made. */
    val never: Boolean = false,
) : Flow {
    override val hasContent: Boolean get() = true

    // A call that never returns and is not known to fail leaves it to what follows to say that the flow ends.
    override val completes: Boolean get() = !never || raises.isEmpty()
}

/**
 * A part of the flow that stays as it was read, with every failure that leaves it drawn as one:
 * all it comes to is a failure of the workflow task, and what leads to it is drawn and has no
 * other way to go (see [GraphLayout]).
 */
internal class Kept(val body: Flow) : Flow {
    override val hasContent: Boolean get() = body.hasContent
    override val completes: Boolean get() = body.completes
}

/** A `break` or `continue` of the loop [frame]. */
internal class LoopJump(val frame: Int, val leavesLoop: Boolean) : Flow {
    override val hasContent: Boolean get() = true
    override val completes: Boolean get() = false
}

/** A compensation handed to a saga. It is no step where it is registered; [body] runs if the saga compensates. */
internal class Register(
    val saga: String,
    val body: Flow,
    /** The calls that were followed to get to where the compensation is registered, outermost first. */
    val path: List<String> = emptyList(),
) : Flow {
    override val hasContent: Boolean get() = false
}

/** A saga runs the compensations registered with it so far: last one first, or all side by side. */
internal class Compensate(
    val saga: String,
    val location: SourceLocation,
    /** True if it runs in a scope that the cancellation of the workflow does not reach. */
    val detached: Boolean,
    /** True if the saga was made to run its compensations side by side. */
    val parallel: Boolean = false,
) : Flow {
    override val hasContent: Boolean get() = true
}

/**
 * Code handed to something that is not modelled: nothing says whether, when or how often it runs.
 * The [parts] may each follow what came before, or be passed by.
 */
internal class Uncharted(
    /** The node that stands for what is not modelled; `null` if a step in front already does. */
    val marker: IrNode?,
    val location: SourceLocation,
    val parts: List<Flow>,
    /** The step in front that stands for what is not modelled, where that is a call which is not followed. */
    val behind: Step? = null,
) : Flow {
    override val hasContent: Boolean get() = true
}

/** The one step a flow consists of, if it is no more than that. */
internal fun Flow.onlyStep(): Step? =
    when (this) {
        is Step -> this
        is LambdaBody -> body.onlyStep()
        is Kept -> body.onlyStep()
        is Series -> parts.singleOrNull()?.onlyStep()
        else -> null
    }

/**
 * The compensations registered inside a flow that is dropped for holding nothing else, in order.
 * They must outlive the construct that held them.
 */
internal fun Flow.registrations(): List<Register> =
    when (this) {
        is Register -> listOf(this)
        is Series -> parts.flatMap { it.registrations() }
        is LambdaBody -> body.registrations()
        is Scoped -> body.registrations()
        is Inlined -> body.registrations()
        is Kept -> body.registrations()
        is Choice -> arms.flatMap { it.body.registrations() }
        is Repeat -> body.registrations()
        is Fork -> body.registrations()
        is Protected -> body.registrations() + handlers.flatMap { it.body.registrations() } + finally.registrations()
        is Uncharted -> parts.flatMap { it.registrations() }
        is Step, is Join, is Leave, is Raise, is Stuck, is Undrawn, is LoopJump, is Compensate -> emptyList()
    }

/**
 * Whether the flow holds anything that shows from outside the construct it is the body of: a
 * step, a failure, or a jump that leaves the construct. Frames are numbered as they are met, so
 * whatever was opened inside the construct has a number of [floor] or above, and a `return` or a
 * jump that only names one of those stays inside. A loop or a followed function that holds
 * nothing in this sense leaves nothing behind.
 */
internal fun Flow.holdsSteps(floor: Int): Boolean =
    when (this) {
        is Step, is Raise, is Undrawn, is Compensate -> true
        is Series -> parts.any { it.holdsSteps(floor) }
        is Choice -> arms.any { it.body.holdsSteps(floor) }
        is Repeat -> body.holdsSteps(floor)
        is Fork -> body.holdsSteps(floor)
        is Protected -> body.holdsSteps(floor) || handlers.any { it.body.holdsSteps(floor) } || finally.holdsSteps(floor)
        is LambdaBody -> body.holdsSteps(floor)
        is Scoped -> body.holdsSteps(floor)
        is Inlined -> body.holdsSteps(floor)
        is Kept -> body.holdsSteps(floor)
        is Uncharted -> marker != null || parts.any { it.holdsSteps(floor) }
        is Leave -> frame == null || frame < floor
        is LoopJump -> frame < floor
        is Register, is Join, is Stuck -> false
    }

/**
 * Whether a step of the workflow is certain to lie somewhere in the flow: a step, a
 * compensation that runs, or a node that stands for one of these where a call is not followed.
 * What may fail, and what calls something nothing is known of, holds none for certain. Only the
 * body of a function with such a step is drawn where the function is called.
 */
internal fun Flow.holdsCertainStep(): Boolean =
    when (this) {
        is Step -> firm
        is Compensate -> true
        is Series -> parts.any { it.holdsCertainStep() }
        is Choice -> arms.any { it.body.holdsCertainStep() }
        is Repeat -> body.holdsCertainStep()
        is Fork -> body.holdsCertainStep()
        is Protected -> body.holdsCertainStep() || handlers.any { it.body.holdsCertainStep() } || finally.holdsCertainStep()
        is LambdaBody -> body.holdsCertainStep()
        is Scoped -> body.holdsCertainStep()
        is Inlined -> body.holdsCertainStep()
        is Kept -> body.holdsCertainStep()
        // The node that stands for what is not modelled is there for what follows it.
        is Uncharted -> parts.any { it.holdsCertainStep() }
        is Raise, is Stuck, is Undrawn, is Leave, is LoopJump, is Register, is Join -> false
    }

/**
 * Whether something in the flow may raise a failure that fails the execution of a workflow:
 * a step, which raises a failure of Temporal when it does not succeed or is cancelled, a place
 * that waits for what runs beside the flow, a call of something that cannot be followed, or
 * code that throws such a failure. A clause around a flow without any of these catches only
 * what fails a workflow task, as far as the code that is read says.
 */
internal fun Flow.mayFailExecution(): Boolean =
    when (this) {
        is Step, is Compensate, is Join -> true
        is Raise -> real
        is Undrawn -> unseen.isNotEmpty() || raises.any { it.real }
        is Series -> parts.any { it.mayFailExecution() }
        is Choice -> arms.any { it.body.mayFailExecution() }
        is Repeat -> body.mayFailExecution()
        is Fork -> body.mayFailExecution()
        is Protected -> body.mayFailExecution() || handlers.any { it.body.mayFailExecution() } || finally.mayFailExecution()
        is LambdaBody -> body.mayFailExecution()
        is Scoped -> body.mayFailExecution()
        is Inlined -> body.mayFailExecution()
        is Kept -> body.mayFailExecution()
        is Uncharted -> marker != null || parts.any { it.mayFailExecution() }
        is Leave, is LoopJump, is Register, is Stuck -> false
    }

/** The types of the failures that the code of the flow throws itself, wherever in it; `null` for one whose type is not known. */
internal fun Flow.thrown(): List<String?> =
    when (this) {
        is Raise -> listOf(type)
        is Undrawn -> raises.map { it.type }
        is Series -> parts.flatMap { it.thrown() }
        is Choice -> arms.flatMap { it.body.thrown() }
        is Repeat -> body.thrown()
        is Fork -> body.thrown()
        is Protected -> body.thrown() + handlers.flatMap { it.body.thrown() } + finally.thrown()
        is LambdaBody -> body.thrown()
        is Scoped -> body.thrown()
        is Inlined -> body.thrown()
        is Kept -> body.thrown()
        is Uncharted -> parts.flatMap { it.thrown() }
        is Step, is Stuck, is Compensate, is Leave, is LoopJump, is Register, is Join -> emptyList()
    }

/** Whether a `return` out of [frame] is somewhere in the flow. */
internal fun Flow.leaves(frame: Int): Boolean =
    when (this) {
        is Leave -> this.frame == frame
        is Series -> parts.any { it.leaves(frame) }
        is Choice -> arms.any { it.body.leaves(frame) }
        is Repeat -> body.leaves(frame)
        is Fork -> body.leaves(frame)
        is Protected -> body.leaves(frame) || handlers.any { it.body.leaves(frame) } || finally.leaves(frame)
        is LambdaBody -> body.leaves(frame)
        is Scoped -> body.leaves(frame)
        is Inlined -> body.leaves(frame)
        is Kept -> body.leaves(frame)
        is Uncharted -> parts.any { it.leaves(frame) }
        is Step, is Raise, is Stuck, is Undrawn, is Compensate, is LoopJump, is Register, is Join -> false
    }

/**
 * The steps and lanes of a flow that stand in the region the flow is laid out in, and not in a
 * region of their own inside it: what a branch, a `try`, a lambda or a followed function holds
 * counts, what a loop, a lane or a compensation holds does not.
 */
internal fun Flow.direct(): List<Flow> =
    when (this) {
        is Step, is Fork -> listOf(this)
        is Series -> parts.flatMap { it.direct() }
        is Choice -> arms.flatMap { it.body.direct() }
        is Protected -> body.direct() + handlers.flatMap { it.body.direct() } + finally.direct()
        is LambdaBody -> body.direct()
        is Scoped -> body.direct()
        is Inlined -> body.direct()
        is Kept -> body.direct()
        is Uncharted -> parts.flatMap { it.direct() }
        is Repeat, is Join, is Leave, is Raise, is Stuck, is Undrawn, is LoopJump, is Register, is Compensate -> emptyList()
    }
