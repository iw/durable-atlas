package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.LeftOutCall
import io.github.iw.durableatlas.ir.SourceLocation

/**
 * The calls an overview has no node for, as [GraphLayout] meets them while it lays out the entry
 * points of one workflow.
 *
 * An overview shows what a workflow does, not where the analysis ran out. Three kinds of node
 * that a full graph has say only that the analysis is not sure, and an overview leaves them out:
 *
 * - The node in front of code that was handed to a call which is not modelled ([Uncharted]).
 *   The code is drawn anyway; the node adds that nothing says whether, when or how often it
 *   runs, and a way past the code. An overview reads the code as run once, where the call is made.
 * - The node for a call of a function that no class of the sources implements ([Step.firm]).
 * - The node for a call of a function that is not drawn and that makes such a call ([Undrawn.unseen]).
 *
 * Behind the last two a step may lie, and none is certain to. A node behind which a step is
 * certain stays: a call too deep to follow, one that recurses, one that runs one of several
 * functions, a step whose name is computed, a call that did not resolve and may be a step.
 *
 * Everything else is laid out as in a full graph, and the nodes that are drawn have the ids they
 * have there. A branch none of whose ways shows anything is no branch, and a loop or a lane
 * that holds nothing is not drawn, as when the code holds none ([shows], [showsSteps]).
 */
internal class LeftOut {
    private val calls = LinkedHashSet<LeftOutCall>()

    /** The calls left out so far, each once, in the order they were met. */
    val all: List<LeftOutCall> get() = calls.toList()

    /**
     * Notes that the call which [node] stands for in a full graph has no node.
     *
     * @param called what the call is called, where the node does not say
     */
    fun note(node: IrNode, location: SourceLocation?, called: String? = null) {
        val reason = node.reason ?: return
        val label = node.label ?: called?.let(Captions::of) ?: reason
        calls += LeftOutCall(location ?: return, reason, label, node.candidates.distinct().sorted())
    }
}

/**
 * Whether an overview shows anything of the flow: a node, or a path that ends. It is
 * [Flow.hasContent] without the calls that an overview leaves out.
 */
internal fun Flow.shows(): Boolean =
    when (this) {
        is Step -> firm
        // What is left of a call that is not drawn is the question whether it throws.
        is Undrawn -> raises.isNotEmpty()
        is Leave, is Raise, is LoopJump, is Compensate -> true
        is Register, is Join, is Stuck -> false
        is Series -> parts.any { it.shows() }
        is Choice -> arms.any { it.body.shows() }
        is Repeat -> closed || body.showsSteps(floor)
        is Fork -> body.shows()
        is Protected -> body.shows() || handlers.any { it.body.shows() } || finally.shows()
        is LambdaBody -> body.shows()
        is Scoped -> body.shows()
        is Inlined -> body.shows()
        is Kept -> body.shows()
        is Uncharted -> parts.any { it.shows() }
    }

/**
 * Whether an overview shows anything of the flow from outside the construct it is the body of:
 * [Flow.holdsSteps] without the calls that an overview leaves out.
 */
internal fun Flow.showsSteps(floor: Int): Boolean =
    when (this) {
        is Step -> firm
        is Undrawn -> raises.isNotEmpty()
        is Raise, is Compensate -> true
        is Series -> parts.any { it.showsSteps(floor) }
        is Choice -> arms.any { it.body.showsSteps(floor) }
        is Repeat -> closed || body.showsSteps(floor)
        is Fork -> body.showsSteps(floor)
        is Protected -> body.showsSteps(floor) || handlers.any { it.body.showsSteps(floor) } || finally.showsSteps(floor)
        is LambdaBody -> body.showsSteps(floor)
        is Scoped -> body.showsSteps(floor)
        is Inlined -> body.showsSteps(floor)
        is Kept -> body.showsSteps(floor)
        is Uncharted -> parts.any { it.showsSteps(floor) }
        is Leave -> frame == null || frame < floor
        is LoopJump -> frame < floor
        is Register, is Join, is Stuck -> false
    }

/**
 * True for a loop that the flow never gets past: it is only ever left by a jump out of its body,
 * and its body holds no `break` of it. Nothing that follows it is reached, so an overview keeps
 * it whatever it holds: without it, what follows would look as if it were.
 */
internal val Repeat.closed: Boolean get() = endless && !body.breaks(frame)

/** Whether a `break` of the loop [frame] is somewhere in the flow. */
internal fun Flow.breaks(frame: Int): Boolean =
    when (this) {
        is LoopJump -> leavesLoop && this.frame == frame
        is Series -> parts.any { it.breaks(frame) }
        is Choice -> arms.any { it.body.breaks(frame) }
        is Repeat -> body.breaks(frame)
        is Fork -> body.breaks(frame)
        is Protected -> body.breaks(frame) || handlers.any { it.body.breaks(frame) } || finally.breaks(frame)
        is LambdaBody -> body.breaks(frame)
        is Scoped -> body.breaks(frame)
        is Inlined -> body.breaks(frame)
        is Kept -> body.breaks(frame)
        is Uncharted -> parts.any { it.breaks(frame) }
        is Step, is Raise, is Stuck, is Undrawn, is Compensate, is Leave, is Register, is Join -> false
    }
