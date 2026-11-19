package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.Join as LaneJoin
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.SourceLocation
import io.github.iw.durableatlas.ir.UnknownReasons
import io.github.iw.durableatlas.ir.WaitsFor
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Lays a [Flow] out as the graph of one entry point.
 *
 * Regions follow one convention. A loop, a parallel section or a compensation is a single step of
 * the flow around it: edges into it end at the region's node, edges that leave it once it is done
 * start there. Inside, only edges between its children are written; that its first children are
 * entered and its last ones left is implied. An edge from a child straight to a node outside the
 * region (an early return, a `break`, a failure) is written out.
 *
 * One more edge is written out: from a node to the node of a region around it. It says that the
 * flow goes from that node to the end of the region: of a loop, the end of the round; of a
 * parallel section, the end of the lane; of a compensation, the end of the chain. The region may
 * be the node's own or one further out, and the flow then leaves every region in between: a
 * `continue` that names an outer loop, or a `break` of a loop that is the last thing in the
 * round of the loop around it.
 *
 * A node that is no end of the flow is left to the end of its own region by implication unless
 * one of its edges says where it goes: an edge of any kind to another child, to its region or to
 * a region further out, or a flow edge to a node outside the region, which a `break` or a
 * `return` after the step gives. A failure or a timeout that is dealt with outside the region
 * says nothing about where the step goes otherwise.
 *
 * So to the end of a node's own region the edge is written where the implied way out would not
 * say it: where the edge has a caption or is no ordinary one (the arm of a branch that does
 * nothing more, a `continue`, a failure that is caught and passed over), and where the node has
 * another edge that says where it goes. To the end of a region further out it is always written.
 *
 * What is started beside the flow and never waited for holds nothing up: the flow goes on from
 * where it was started. One step stands in the flow as itself and says so; anything more is a
 * parallel region that says so, whose lanes end on their own. What fails there fails unseen.
 * Where the flow waits for a child workflow in such a region to have started, the region and
 * the child say that.
 *
 * Lanes that run a race are a parallel region that says so: the flow waits for the first of them.
 *
 * A call of a function that is not drawn from the inside, because no step is certain to lie in
 * it, is at most two nodes where it is made: one that says a step may lie behind it, if it calls
 * something that cannot be followed, and a branch that asks whether it throws, if it may fail.
 *
 * A failure that nothing catches ends at a `fail` node where it may fail the execution of the
 * workflow (see [FailureTypes.failsExecution]). Any other fails the workflow task, which is
 * tried again until the code is fixed: the flow neither fails nor goes on, and no node says
 * that. So before anything is laid out, the flow is cleared of the ways that only lead to such
 * an end and show nothing on the way ([prune]): a `throw` that no clause takes, the branch at
 * a call that only throws such failures, a clause that does nothing but throw one, and with
 * them the decisions, loops and lanes that hold nothing else. A decision that is left with one
 * way to go is no decision: the flow goes that way. The way to a clause that takes the failure
 * and does something stays. And where something that is drawn has no other way to go, as a
 * step that such a `throw` follows, the way stays as well, down to its `fail` node: a node has
 * to lead somewhere, and nothing else says that the flow ends there.
 */
internal class GraphLayout(
    entryPointId: String,
    private val failures: FailureTypes,
    /**
     * Whether an exception that leaves the entry point is drawn only where it may fail the
     * execution of the workflow. False for an entry point that every exception ends with an
     * outcome of its own: a query, which fails, and a validator, which rejects its update.
     */
    private val strict: Boolean = true,
) {
    private val graph = GraphBuilder(entryPointId)

    /** Where the flow stands: the ends an edge to the next node starts at; empty where nothing gets to. */
    private var open: List<End> = emptyList()
    private var end: String? = null

    /** The regions being laid out, innermost last. */
    private val regions = ArrayList<String>()

    /** What `return`, `break`, `continue` and failures inside the flow being laid out can refer to, innermost last. */
    private val frames = ArrayList<Frame>()

    /** The compensations registered so far, for each saga in the order of registration. */
    private val registered = LinkedHashMap<String, MutableList<Register>>()

    /** The places code was brought in from to where the layout stands, which node ids take in. */
    private var path: List<String> = emptyList()

    /** Above zero while laying out what fails as one with the region around it. */
    private var muted = 0

    /** Above zero while laying out what stays as it was read: every failure that leaves it is drawn as one. */
    private var kept = 0

    /** The child workflows being laid out in a region nothing waits for, whose start the flow outside waits for. */
    private val startAwaited: MutableSet<Step> = Collections.newSetFromMap(IdentityHashMap())

    /** The region each node is a child of, by node id. */
    private val parents = HashMap<String, String?>()

    /** The places where the flow reaches the end of a region from a node inside it. */
    private val falls = LinkedHashSet<Fall>()

    /** The flow goes from the node [from] to the end of [region], which lies around it. */
    private data class Fall(val from: String, val region: String, val kind: EdgeKind, val label: String?)

    /**
     * A place the flow goes on from.
     *
     * @param entry the flow has entered the region [from] and met no node in it yet: a node that
     *   follows inside the region is one of its first and needs no edge
     * @param scenario the outcome of the step [from] that this end stands for, while that can
     *   still be asked for
     */
    private data class End(
        val from: String,
        val kind: EdgeKind = EdgeKind.FLOW,
        val label: String? = null,
        val entry: Boolean = false,
        val scenario: Scenario? = null,
    )

    private sealed interface Frame

    private class ScopeFrame(val id: Int) : Frame {
        val exits = ArrayList<End>()
    }

    private class LoopFrame(val id: Int, val region: String) : Frame {
        val breaks = ArrayList<End>()
    }

    /**
     * Around what nothing waits for: no failure gets past it, as nothing takes the promise it
     * would be handed to.
     *
     * @param region the region that holds it
     */
    private class Unawaited(val region: String) : Frame

    private class TryFrame(val flow: Protected) : Frame {
        /** True while the body is laid out: only then do the clauses catch. */
        var catching = true

        /** While the flow is cleared: the clauses that show nothing and that something drawn may only end in. */
        val wanted = LinkedHashSet<Int>()

        /** For each clause, the ends of what it catches. */
        val caught = flow.handlers.map { ArrayList<End>() }

        /** The ways out that pass through `finally`, each with the ends that take it. */
        val leaving = LinkedHashMap<Continuation, MutableList<End>>()
    }

    /** Where a path goes when it does not simply run on. */
    private sealed interface Continuation {
        /** The frame that the path stays inside; frames within it are left. */
        val within: Frame? get() = null

        data object Return : Continuation

        data class Out(override val within: ScopeFrame) : Continuation

        data class Break(override val within: LoopFrame) : Continuation

        data class Next(override val within: LoopFrame) : Continuation

        data class Handled(override val within: TryFrame, val handler: Int) : Continuation

        /**
         * Nothing catches the failure: the workflow fails. Each place that fails is a way out of its own.
         *
         * @param name what the failure is called, if one name says it
         * @param label what the node shows where several failures may be meant
         */
        class Failed(val name: String?, val label: String?, val location: SourceLocation) : Continuation
    }

    /**
     * @param declared where the function of the entry point is, for its `start` node
     * @param body what the function does; `null` if it has no body to read
     * @param isHandler whether the entry point is a handler and not the workflow method
     */
    fun layOut(declared: SourceLocation?, body: Flow?, isHandler: Boolean = false): Graph {
        val start = graph.add(IrNode(id = "", kind = NodeKind.START), declared, anchored = false)
        open = listOf(End(start))
        if (body == null) {
            val unreadable = IrNode(id = "", kind = NodeKind.UNKNOWN, reason = UnknownReasons.UNSUPPORTED, label = if (isHandler) NO_HANDLER_BODY else NO_BODY)
            step(Step(unreadable, declared))
        } else {
            layout(cleared(body))
        }
        route(open, Continuation.Return)
        regionEnds()
        return graph.build(start)
    }

    private fun layout(flow: Flow) {
        // What nothing leads to is not part of the graph.
        if (open.isEmpty()) return
        when (flow) {
            is Series -> sequence(flow.parts)
            is Step -> step(flow)
            is Choice -> choice(flow)
            is Repeat -> repeat(flow)
            is Fork -> if (flow.awaited) parallel(listOf(flow), 0) else aside(flow)
            is Join -> Unit
            is Protected -> protect(flow)
            is LambdaBody -> scope(flow.frame, flow.body)
            is Scoped -> scoped(flow)
            is Inlined -> inlined(flow)
            is Leave -> leave(flow)
            is Raise -> raise(flow)
            // The way ends, and nothing is drawn of how.
            is Stuck -> open = emptyList()
            is Kept -> {
                kept++
                layout(flow.body)
                kept--
            }
            is Undrawn -> undrawn(flow)
            is LoopJump -> jump(flow)
            is Register -> register(flow)
            is Compensate -> compensate(flow)
            is Uncharted -> uncharted(flow)
        }
    }

    // ---- nodes and edges

    /**
     * Adds a node after the open ends and makes it the only one. A node that ends the workflow
     * belongs to no region, wherever it is reached from.
     */
    private fun emit(template: IrNode, location: SourceLocation?): String {
        val parent = if (template.kind.isTerminal) null else regions.lastOrNull()
        val id = graph.add(template.copy(parent = parent), location, path)
        parents[id] = parent
        connect(open, id, parent)
        open = if (template.kind.isTerminal) emptyList() else listOf(End(id))
        return id
    }

    private fun connect(ends: Collection<End>, to: String, parent: String?) {
        for (end in ends) {
            // The first nodes of a region are entered without an edge.
            if (end.entry && end.from == parent) continue
            graph.connect(end.from, to, end.kind, end.label)
        }
    }

    private fun step(flow: Step) {
        val id = emit(if (flow in startAwaited) flow.node.copy(waitsFor = WaitsFor.START) else flow.node, flow.location)
        flow.cancels?.let { graph.cancel(id, it, overBefore(id)) }
        graph.wait(id, flow.queue, flow.wokenByFlow)
        fail(id, flow.node.kind)
        // A wait that gives up with a failure when its time is up leaves on its timeout, for the clause that catches that.
        if (flow.timesOut && muted == 0) {
            for (handled in catching(TemporalApi.TIMEOUT_EXCEPTION).first) route(listOf(End(id, EdgeKind.TIMEOUT)), handled)
        }
        val outcomes = flow.outcomes
        if (outcomes != null && open.isNotEmpty()) open = outcomes.states.map { End(id, scenario = it) }
    }

    // ---- sequences and parallel regions

    private fun sequence(parts: List<Flow>) {
        var index = 0
        while (index < parts.size && open.isNotEmpty()) {
            val part = parts[index]
            if (part.isLane()) {
                index = parallel(parts, index)
            } else {
                layout(part)
                index++
            }
        }
    }

    /**
     * The steps that are over by the time the flow gets to the node [id], which was added last:
     * those the flow waited for on its way there. They stand before it in the flow it is part
     * of, at its own level or at that of a region around it. A step in another lane may still
     * run, and so may one that is not waited for, or the steps of a region.
     */
    private fun overBefore(id: String): Set<String> {
        val around = regionsAround(id).toSet()
        val lanes = graph.since(0).filter { it.kind == NodeKind.PARALLEL }.map { it.id }.toSet()
        return graph.since(0)
            .filter { it.id != id && !it.kind.isContainer && it.waitsFor == null && (it.parent == null || it.parent in around) && it.parent !in lanes }
            .mapTo(HashSet()) { it.id }
    }

    /** Whether this starts a lane that the flow waits for at some place. */
    private fun Flow.isLane(): Boolean = this is Fork && awaited

    /**
     * Lays out what was started beside the flow and is never waited for. A single step is
     * started where it stands, and the flow goes on from it; it says how far the flow waits
     * for it. Anything more is a parallel region that says the same: the edges that leave it
     * are taken as soon as its lanes are started, or, where the flow waits for a child
     * workflow in the region to have started, as soon as that has.
     */
    private fun aside(flow: Fork) {
        val only = flow.body.onlyStep()?.takeUnless { it.node.kind.isTerminal }
        if (only != null) {
            val id = emit(only.node.copy(waitsFor = if (only.startAwaited) WaitsFor.START else WaitsFor.NOTHING), only.location)
            graph.wait(id, only.queue, only.wokenByFlow)
            return
        }
        val direct = flow.body.direct()
        // A child the lane starts beside itself says of itself that only its start is waited for,
        // and means the lane: beside one of those, a child that means the flow outside could not be told from it.
        val mixed = direct.any { it is Fork && !it.awaited && it.body.onlyStep()?.startAwaited == true }
        val started = direct.filterIsInstance<Step>().filter { it.startAwaitedOutside && it.node.kind == NodeKind.CHILD_WORKFLOW && !mixed }
        val id = emit(IrNode(id = "", kind = NodeKind.PARALLEL, waitsFor = if (started.isEmpty()) WaitsFor.NOTHING else WaitsFor.START), flow.location)
        regions += id
        frames += Unawaited(id)
        startAwaited += started
        open = listOf(End(id, entry = true))
        layout(flow.body)
        fall(open, id)
        startAwaited -= started.toSet()
        frames.removeAt(frames.lastIndex)
        regions.removeAt(regions.lastIndex)
        open = listOf(End(id))
    }

    /**
     * Lays out what a cancellation scope runs and notes what the scope holds, for the place
     * where it is cancelled: the steps and regions that stand where the scope does. What lies
     * inside one of those regions is cancelled with it.
     */
    private fun scoped(flow: Scoped) {
        val region = regions.lastOrNull()
        val before = graph.size
        layout(flow.body)
        graph.hold(flow.key, graph.since(before).filter { it.parent == region && it.kind in CANCELLABLE }.map { it.id })
    }

    /**
     * Lays out the parallel region that begins with the fork at [first] and returns the index of
     * the part to go on with.
     *
     * The region reaches as far as the place where the flow waits for what it started. Every
     * fork up to there is a lane, and so is what the flow does itself in the meantime. Where the
     * flow waits for nothing at this level, the region ends before the next thing it does: the
     * forks that follow each other are its lanes.
     *
     * Where the lanes of the region are those of one race and nothing else, the region says
     * that the flow waits for the first of them.
     */
    private fun parallel(parts: List<Flow>, first: Int): Int {
        val rest = first + 1 until parts.size
        val last = rest.firstOrNull { parts[it] is Join }
            ?: rest.firstOrNull { !parts[it].isLane() && parts[it].hasContent }
            ?: parts.size
        val lanes = (first until last).mapNotNull { index -> (parts[index] as? Fork)?.takeIf { it.awaited } }
        val nothingElse = (first until last).none { !parts[it].isLane() && parts[it].hasContent }
        val race = lanes.first().race?.takeIf { race -> nothingElse && lanes.all { it.race === race } && race.lanes.size == lanes.size }
        val id = emit(IrNode(id = "", kind = NodeKind.PARALLEL, join = LaneJoin.FIRST.takeIf { race != null }), (parts[first] as Fork).location)
        regions += id
        val entry = listOf(End(id, entry = true))
        var own = entry
        for (index in first until last) {
            val part = parts[index]
            if (part is Fork && part.awaited) {
                open = entry
                layout(part.body)
                fall(open, id)
            } else {
                open = own
                layout(part)
                own = open
            }
        }
        fall(own, id)
        regions.removeAt(regions.lastIndex)
        // What the lanes end with leads out of the region by implication.
        open = if (own.isEmpty()) emptyList() else listOf(End(id))
        return last
    }

    // ---- branches

    private fun choice(flow: Choice) {
        val decided = flow.outcomes
        if (decided != null && open.all { it.scenario != null && it.scenario in decided.states }) return fold(flow)
        // The other ways end in a failure that is not drawn: there is nothing to decide.
        if (flow.narrowed && flow.arms.size == 1) return layout(flow.arms.single().body)
        val id = emit(IrNode(id = "", kind = NodeKind.BRANCH, label = flow.label), flow.location)
        val after = ArrayList<End>()
        for (arm in flow.arms) {
            open = listOf(End(id, label = arm.label))
            layout(arm.body)
            after += open
        }
        open = after.distinct()
    }

    /**
     * Lays out a choice that only asks how the step just before it turned out: each arm leaves
     * that step itself, on edges named after the outcomes that take the arm.
     */
    private fun fold(flow: Choice) {
        val ends = open
        val after = ArrayList<End>()
        for (arm in flow.arms) {
            open = ends.mapNotNull { end ->
                end.scenario?.takeIf { it in arm.scenarios }?.let { end.copy(kind = it.kind, label = arm.caption(it)) }
            }
            layout(arm.body)
            after += open
        }
        open = after.distinct()
    }

    // ---- loops and lambdas

    private fun repeat(flow: Repeat) {
        val id = emit(IrNode(id = "", kind = NodeKind.LOOP, label = flow.label, retry = flow.retry), flow.location)
        val frame = LoopFrame(flow.frame, id)
        frames += frame
        regions += id
        open = listOf(End(id, entry = true))
        layout(flow.body)
        fall(open, id)
        regions.removeAt(regions.lastIndex)
        frames.removeAt(frames.lastIndex)
        // What the body ends with leads back to the head by implication; a `break` leads on from where it is.
        open = ((if (flow.endless) emptyList() else listOf(End(id))) + frame.breaks).distinct()
    }

    private fun jump(flow: LoopJump) {
        val frame = frames.lastOrNull { it is LoopFrame && it.id == flow.frame } as LoopFrame?
        if (frame != null) route(open, if (flow.leavesLoop) Continuation.Break(frame) else Continuation.Next(frame))
        open = emptyList()
    }

    /** Lays out a body that a `return` naming [id] leaves: the flow goes on after it from wherever it was left. */
    private fun scope(id: Int, body: Flow) {
        val frame = ScopeFrame(id)
        frames += frame
        layout(body)
        frames.removeAt(frames.lastIndex)
        open = (open + frame.exits).distinct()
    }

    /** Lays out a function where it is called. The ids of its nodes take in the call. */
    private fun inlined(flow: Inlined) {
        val outer = path
        path = outer + flow.site
        scope(flow.frame, flow.body)
        path = outer
    }

    private fun leave(flow: Leave) {
        val frame = flow.frame?.let { id -> frames.lastOrNull { it is ScopeFrame && it.id == id } as ScopeFrame? }
        route(open, if (frame == null) Continuation.Return else Continuation.Out(frame))
        open = emptyList()
    }

    // ---- ways out

    /**
     * Sends [ends] on their way to [continuation]. If the way leads out of a `try` with a
     * `finally`, they wait there: the `finally` is laid out once for each way out, when the `try`
     * is complete, and the way goes on from its end.
     */
    private fun route(ends: List<End>, continuation: Continuation) {
        if (ends.isEmpty()) return
        val floor = continuation.within?.let(frames::indexOf) ?: -1
        val barrier = (frames.lastIndex downTo floor + 1)
            .map { frames[it] }
            .firstOrNull { it is TryFrame && it.flow.finally.hasContent } as TryFrame?
        if (barrier != null) {
            barrier.leaving.getOrPut(continuation) { ArrayList() } += ends
            return
        }
        when (continuation) {
            Continuation.Return -> {
                val id = end ?: graph.add(IrNode(id = "", kind = NodeKind.END), null).also { end = it }
                connect(ends, id, parent = null)
            }
            is Continuation.Out -> continuation.within.exits += ends
            is Continuation.Break -> continuation.within.breaks += ends
            // Going round again is what the body of a loop does anyway; from where is worth saying.
            is Continuation.Next -> fall(ends, continuation.within.region)
            is Continuation.Handled -> continuation.within.caught[continuation.handler] += ends
            is Continuation.Failed -> {
                open = ends
                emit(IrNode(id = "", kind = NodeKind.FAIL, name = continuation.name, label = continuation.label), continuation.location)
            }
        }
    }

    // ---- failures

    /** The clauses that take over when [type] is raised here, nearest first, and whether one of them is sure to. */
    private fun catching(type: String?): Pair<List<Continuation.Handled>, Boolean> {
        val found = ArrayList<Continuation.Handled>()
        for (frame in frames.asReversed()) {
            // What nothing waits for fails for nobody.
            if (frame is Unawaited) break
            if (frame !is TryFrame || !frame.catching) continue
            for ((index, handler) in frame.flow.handlers.withIndex()) {
                when (failures.match(type, handler.type)) {
                    Caught.ALWAYS -> return (found + Continuation.Handled(frame, index)) to true
                    Caught.SOMETIMES -> found += Continuation.Handled(frame, index)
                    Caught.NEVER -> Unit
                }
            }
        }
        return found to false
    }

    private fun Continuation.Handled.label(): String = within.flow.handlers[handler].label

    /** Draws the failures of the step [id] that a `catch` clause takes over. What nothing catches is not drawn. */
    private fun fail(id: String, kind: NodeKind) {
        if (muted > 0) return
        for (type in FailureTypes.raisedBy(kind)) {
            for (handled in catching(type).first) {
                route(listOf(End(id, EdgeKind.EXCEPTION, handled.label())), handled)
            }
        }
    }

    /** A `throw`: the flow goes to the clauses that catch it, and fails the workflow unless one of them is sure to. */
    private fun raise(flow: Raise) {
        raise(listOf(Thrown(flow.type, flow.name, flow.real)), flow.location)
    }

    /**
     * The flow fails where it stands, with one of [raised]: it goes to the clauses that catch
     * that, and fails the workflow unless one of them is sure to. The failures that may fail the
     * workflow share one way out: it is called after them, by the name they have in common or
     * by all their names. Those that only fail the workflow task are left out of it. Where
     * there are no others, the way is one that stays because what leads to it is drawn (see
     * [prune]), and it ends as every failure used to; not so where the flow can go on
     * otherwise.
     *
     * @param passable whether the flow can also go on from where it stands: then nothing has
     *   to say where a failure of the workflow task ends
     */
    private fun raise(raised: List<Thrown>, location: SourceLocation, passable: Boolean = false) {
        val escaping = ArrayList<Thrown>()
        for (thrown in raised) {
            val (handlers, certain) = catching(thrown.type)
            for (handled in handlers) {
                route(open.map { End(it.from, EdgeKind.EXCEPTION, handled.label(), it.entry) }, handled)
            }
            if (!certain) escaping += thrown
        }
        // A failure of what nothing waits for ends that, and not the workflow.
        val unseen = frames.lastOrNull { it is Unawaited } as Unawaited?
        when {
            escaping.isEmpty() -> Unit
            unseen != null -> fall(open, unseen.region)
            else -> {
                val failing = escaping.filter(::fails).ifEmpty { if (passable) emptyList() else escaping }
                val names = failing.map { it.name }.distinct()
                val label = names.filterNotNull().takeIf { names.size > 1 }?.joinToString(", ")?.let(Captions::of)
                if (failing.isNotEmpty()) route(open, Continuation.Failed(names.singleOrNull(), label, location))
            }
        }
        open = emptyList()
    }

    /** Whether [thrown] ends the entry point with an outcome of its own, if nothing catches it. */
    private fun fails(thrown: Thrown): Boolean = thrown.real || !strict || kept > 0

    /**
     * A call of a function of the sources that is not drawn from the inside. Where it calls
     * something that cannot be followed, a node says that a step may lie behind the call. Where
     * it may fail, the flow forks at the call: a branch asks whether the function throws, and
     * leads to the clauses that catch what it throws and to the failure of the workflow, as a
     * `throw` in its place would, and on. A call that never returns fails without being asked.
     */
    private fun undrawn(flow: Undrawn) {
        if (flow.unseen.isNotEmpty()) {
            val unseen = IrNode(id = "", kind = NodeKind.UNKNOWN, reason = UnknownReasons.UNSEEN_CODE, label = Captions.of(flow.label), candidates = flow.unseen)
            step(Step(unseen, flow.location))
        }
        if (flow.raises.isEmpty() || open.isEmpty()) return
        if (flow.never) return raise(flow.raises, flow.location)
        val id = emit(IrNode(id = "", kind = NodeKind.BRANCH, label = Captions.of(flow.label, THROWS)), flow.location)
        open = listOf(End(id, label = YES))
        raise(flow.raises, flow.location, passable = true)
        open = listOf(End(id, label = NO))
    }

    private fun protect(flow: Protected) {
        val frame = TryFrame(flow)
        val before = open
        frames += frame
        layout(flow.body)
        val normal = ArrayList(open)
        frame.catching = false
        for ((index, handler) in flow.handlers.withIndex()) {
            var sources: List<End> = frame.caught[index]
            // A clause that no step of the body is known to reach is entered from where the `try` begins.
            if (sources.isEmpty() && handler.body.hasContent) {
                sources = before.map { End(it.from, EdgeKind.EXCEPTION, handler.label, it.entry) }
            }
            open = sources.distinct()
            if (open.isEmpty()) continue
            layout(handler.body)
            normal += open
        }
        frames.removeAt(frames.lastIndex)
        open = normal.distinct()
        if (!flow.finally.hasContent) return

        // One copy of `finally` for the paths that run on, and one for every other way out.
        layout(flow.finally)
        val after = open
        for ((continuation, ends) in frame.leaving) {
            open = ends.distinct()
            layout(flow.finally)
            route(open, continuation)
        }
        open = after
    }

    // ---- what is not drawn

    /**
     * [flow] as it is laid out: without the ways that only lead to a failure of the workflow
     * task and show nothing, see [prune]. A flow that is nothing but such a way stays as it is:
     * what it is entered from has to lead somewhere.
     */
    private fun cleared(flow: Flow): Flow = prune(flow).takeUnless { it.isStuck } ?: Kept(flow)

    /**
     * [flow] without what leads to nothing but a failure of the workflow task and shows nothing
     * on the way, as it is where the layout stands: what catches what is asked of the `try`s
     * around this place and of those inside the flow. A way that ends like that ends in [Stuck];
     * a construct that holds nothing else is gone, and what the flow registers for a saga stays.
     */
    private fun prune(flow: Flow): Flow =
        when (flow) {
            is Step, is Join, is Leave, is LoopJump, is Register, is Compensate, is Stuck, is Kept -> flow
            is Raise -> if (unseen(listOf(Thrown(flow.type, flow.name, flow.real)))) Stuck else flow
            is Undrawn -> when {
                // The node for what cannot be followed behind the call has to lead somewhere.
                flow.raises.isEmpty() || !unseen(flow.raises) || (flow.never && flow.unseen.isNotEmpty()) -> flow
                flow.never -> Stuck
                flow.unseen.isEmpty() -> Series.EMPTY
                else -> Undrawn(flow.label, flow.location, emptyList(), flow.unseen)
            }
            is Series -> {
                val parts = ArrayList<Flow>()
                for (part in flow.parts) {
                    val pruned = prune(part)
                    // What is drawn has to lead somewhere: where all that follows it is such an end, that is drawn as it was.
                    parts += if (pruned.isStuck && parts.any { it.hasContent }) keep(part) else pruned
                    // Nothing gets to what follows a part that the flow does not go on from.
                    if (!pruned.completes) break
                }
                Series.of(parts)
            }
            is Choice -> {
                val arms = flow.arms.map { Arm(it.label, prune(it.body), it.scenarios, it.captions) }
                val live = arms.filterNot { it.body.isStuck }
                when {
                    live.isEmpty() -> emptied(arms.map { it.body }, completes = false)
                    live.none { it.body.hasContent } -> emptied(arms.map { it.body }, completes = true)
                    live.size == arms.size -> Choice(flow.location, flow.label, arms, flow.outcomes, flow.narrowed)
                    // Where a step decides the choice, its ways out are still named after what it decided.
                    live.size == 1 && flow.outcomes == null -> live.single().body
                    else -> Choice(flow.location, flow.label, live, flow.outcomes, narrowed = true)
                }
            }
            is Repeat -> {
                val body = prune(flow.body)
                if (body.holdsSteps(flow.floor)) {
                    Repeat(flow.location, flow.label, body, flow.frame, flow.endless, flow.retry, flow.floor)
                } else {
                    emptied(listOf(body), completes = true)
                }
            }
            is Fork -> {
                // One step that nothing waits for is no lane of its own.
                val lane = !flow.awaited && flow.body.onlyStep()?.takeUnless { it.node.kind.isTerminal } == null
                if (lane) frames += Unawaited(region = "")
                val body = prune(flow.body)
                if (lane) frames.removeAt(frames.lastIndex)
                if (!body.hasContent) {
                    emptied(listOf(body), completes = true)
                } else {
                    Fork(flow.location, body).also {
                        it.awaited = flow.awaited
                        it.race = flow.race
                    }
                }
            }
            is Protected -> protection(flow)
            is LambdaBody -> prune(flow.body).let { if (it.hasContent) LambdaBody(flow.frame, it) else emptied(listOf(it), it.completes) }
            is Scoped -> prune(flow.body).let { if (it.hasContent) Scoped(flow.key, it) else emptied(listOf(it), it.completes) }
            is Inlined -> Inlined(flow.frame, flow.site, prune(flow.body))
            is Uncharted -> {
                val parts = flow.parts.map(::prune)
                // What is not modelled may pass by what it is handed: the flow goes on either way.
                if (parts.none { it.hasContent }) emptied(parts, completes = true) else Uncharted(flow.marker, flow.location, parts)
            }
        }

    /**
     * A `try` without what is not drawn. Its clauses and its `finally` are looked at first, as
     * they stand outside it: a clause that only leads to a failure of the workflow task takes
     * what it catches with it, and nothing is drawn of either. Not so where something drawn in
     * the body may end nowhere else: that clause stays as it was.
     */
    private fun protection(flow: Protected): Flow {
        val finally = prune(flow.finally)
        // While its clauses run, the `try` no longer catches, and its `finally` is still to come.
        val around = TryFrame(Protected(Series.EMPTY, flow.handlers, finally))
        around.catching = false
        frames += around
        val cleared = flow.handlers.map { Handler(it.type, it.label, prune(it.body)) }
        frames.removeAt(frames.lastIndex)
        val revived = HashSet<Int>()
        while (true) {
            val handlers = flow.handlers.indices.map { if (it in revived) flow.handlers[it].let { whole -> Handler(whole.type, whole.label, Kept(whole.body)) } else cleared[it] }
            val frame = TryFrame(Protected(Series.EMPTY, handlers, finally))
            frames += frame
            val body = prune(flow.body)
            frames.removeAt(frames.lastIndex)
            if (revived.addAll(frame.wanted)) continue
            val shown = body.hasContent || handlers.any { it.body.hasContent }
            return when {
                // Every way out fails in the `finally`: whatever is drawn on the way there ends as it did.
                finally.isStuck -> if (shown) keep(flow) else emptied(listOf(body) + handlers.map { it.body }, completes = false)
                // The only way through runs the `finally` and no further: what that shows ends as it did.
                finally.hasContent && !shown && !body.completes -> keep(flow)
                handlers.any { it.body.hasContent } || finally.hasContent || (body.hasContent && handlers.isNotEmpty()) -> Protected(body, handlers, finally)
                body.hasContent -> body
                else -> emptied(listOf(body) + handlers.map { it.body } + finally, body.completes)
            }
        }
    }

    /**
     * [whole] as it stays, although all it comes to is a failure of the workflow task. A clause
     * around it that such a failure may get to has to show where that leads, and is noted.
     */
    private fun keep(whole: Flow): Flow {
        val thrown = whole.thrown()
        for (frame in frames) {
            if (frame !is TryFrame || !frame.catching) continue
            frame.flow.handlers.forEachIndexed { index, handler ->
                if (handler.body.isStuck && thrown.any { failures.match(it, handler.type) != Caught.NEVER }) frame.wanted += index
            }
        }
        return Kept(whole)
    }

    /** What is left of constructs that hold nothing to draw: what they register for a saga, and the end of the way if the flow does not go on. */
    private fun emptied(held: List<Flow>, completes: Boolean): Flow =
        Series.of(held.flatMap { it.registrations() } + listOfNotNull(Stuck.takeUnless { completes }))

    /** Whether the flow neither shows anything nor goes on: all it comes to is a failure of the workflow task. */
    private val Flow.isStuck: Boolean get() = !hasContent && !completes

    /**
     * Whether nothing is drawn where the flow fails with one of [raised] at the place the
     * layout stands: no clause that shows anything takes any of them, and none that gets past
     * the clauses ends the entry point with an outcome of its own. A `finally` that such a
     * failure passes is drawn for the ways that are.
     */
    private fun unseen(raised: List<Thrown>): Boolean {
        var escapes = false
        for (thrown in raised) {
            val (handlers, certain) = catching(thrown.type)
            if (handlers.any { !it.within.flow.handlers[it.handler].body.isStuck }) return false
            if (!certain) {
                if (fails(thrown)) return false
                escapes = true
            }
        }
        // What fails where nothing waits for it ends its lane, which is drawn.
        return !escapes || frames.none { it is Unawaited }
    }

    // ---- sagas

    private fun register(flow: Register) {
        val known = registered.getOrPut(flow.saga) { ArrayList() }
        if (known.none { it === flow }) known += flow
    }

    /**
     * A saga compensates: a region that holds what was registered with the saga before this
     * place, last registered first. A saga made to compensate side by side runs them all at
     * once: the region then holds a parallel section with a lane for each. A compensation that
     * fails does so as part of the region.
     */
    private fun compensate(flow: Compensate) {
        val id = emit(IrNode(id = "", kind = NodeKind.COMPENSATION, label = DETACHED.takeIf { flow.detached }), flow.location)
        val outer = path
        val region = outer + "compensation at ${flow.location.file}:${flow.location.line}"
        val registrations = registered[flow.saga].orEmpty()
        regions += id
        muted++
        open = listOf(End(id, entry = true))
        if (flow.parallel && registrations.size > 1) {
            path = region
            val lanes = emit(IrNode(id = "", kind = NodeKind.PARALLEL), flow.location)
            regions += lanes
            for (registration in registrations) {
                path = region + registration.path
                open = listOf(End(lanes, entry = true))
                layout(cleared(registration.body))
                fall(open, lanes)
            }
            regions.removeAt(regions.lastIndex)
            open = listOf(End(lanes))
        } else {
            for (registration in registrations.asReversed()) {
                // A compensation registered in a function that was followed is told from others by the calls that led there.
                path = region + registration.path
                layout(cleared(registration.body))
            }
        }
        fall(open, id)
        muted--
        regions.removeAt(regions.lastIndex)
        path = outer
        open = listOf(End(id))
        fail(id, NodeKind.COMPENSATION)
    }

    // ---- the ends of regions

    /**
     * Notes that the flow reaches the end of [region] at [ends]. An end counts if it is at a node
     * inside the region, however deep: one that lies in a region of its own in there has left
     * that one on the way, by a jump. The entry of a region stands for a way through it that
     * meets no node, and is no place an edge could start at.
     */
    private fun fall(ends: Collection<End>, region: String) {
        for (end in ends) {
            if (!end.entry && region in regionsAround(end.from)) falls += Fall(end.from, region, end.kind, end.label)
        }
    }

    /** The regions around a node, innermost first. */
    private fun regionsAround(id: String): Sequence<String> = generateSequence(parents[id]) { parents[it] }

    /**
     * Writes the edges to the ends of regions, once every other edge is known. A plain way to the
     * end of a node's own region is left to implication, but only where it is the one thing the
     * node says: any other end of a region the node reaches is written and would be taken for
     * all there is, and so would an edge to another child or a flow edge out of the region.
     */
    private fun regionEnds() {
        val ends = falls.groupingBy { it.from }.eachCount()
        for (fall in falls) {
            val implied = parents[fall.from] == fall.region && fall.label == null && fall.kind == EdgeKind.FLOW &&
                ends.getValue(fall.from) == 1 &&
                graph.outgoing(fall.from).none { parents[it.to] == fall.region || it.kind == EdgeKind.FLOW }
            if (!implied) graph.connect(fall.from, fall.region, fall.kind, fall.label)
        }
    }

    // ---- what is not modelled

    private fun uncharted(flow: Uncharted) {
        flow.marker?.let { step(Step(it, flow.location)) }
        val anchor = open
        val after = LinkedHashSet(anchor)
        for (part in flow.parts) {
            if (open.isEmpty()) open = anchor
            layout(part)
            after += open
        }
        open = after.toList()
    }

    private companion object {
        const val NO_BODY = "workflow method without a Kotlin body"
        const val NO_HANDLER_BODY = "handler without a Kotlin body"
        const val DETACHED = "detached"

        /** What the branch at a call asks that may fail, after the name of the function called. */
        const val THROWS = "throws?"
        const val YES = "yes"
        const val NO = "no"

        /** The kinds of node that the cancellation of the scope they run in reaches: what waits, and what holds such steps. */
        val CANCELLABLE = setOf(
            NodeKind.ACTIVITY, NodeKind.LOCAL_ACTIVITY, NodeKind.CHILD_WORKFLOW, NodeKind.NEXUS_OPERATION, NodeKind.SIGNAL_EXTERNAL,
            NodeKind.TIMER, NodeKind.AWAIT, NodeKind.UNKNOWN, NodeKind.LOOP, NodeKind.PARALLEL, NodeKind.COMPENSATION,
        )
    }
}
