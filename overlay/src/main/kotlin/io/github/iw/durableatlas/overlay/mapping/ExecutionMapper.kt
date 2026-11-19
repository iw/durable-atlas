package io.github.iw.durableatlas.overlay.mapping

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.overlay.history.CloseStatus
import io.github.iw.durableatlas.overlay.history.ExecutionHistory
import io.github.iw.durableatlas.overlay.history.HandlerCalled
import io.github.iw.durableatlas.overlay.history.HandlerEnded
import io.github.iw.durableatlas.overlay.history.Occurrence
import io.github.iw.durableatlas.overlay.history.Outcome
import io.github.iw.durableatlas.overlay.history.Outside
import io.github.iw.durableatlas.overlay.history.StepCancelled
import io.github.iw.durableatlas.overlay.history.StepEnded
import io.github.iw.durableatlas.overlay.history.StepKind
import io.github.iw.durableatlas.overlay.history.StepNoted
import io.github.iw.durableatlas.overlay.history.StepTaken
import io.github.iw.durableatlas.overlay.history.UnknownStep
import io.github.iw.durableatlas.overlay.history.WorkflowClosed
import io.github.iw.durableatlas.overlay.history.WorkflowStarted
import java.time.Duration
import java.time.format.DateTimeParseException

/**
 * Lays event histories over the graph of one workflow type.
 *
 * The history is walked in the order of its events, and the graph with it. Where the execution
 * is in the graph is kept as a set of places, one for the workflow method, one for each handler
 * that is running, and one for each lane of a parallel region that has been entered.
 *
 * **Which node an event belongs to.** An event names a kind of step and what it runs: an
 * activity type, a child workflow type, a signal, a Nexus service and operation, the change id
 * of a version, the id of a mutable side effect, the length of a timer. The candidates are the
 * nodes of that kind and name. Among them, in this order:
 *
 * 1. Those a place can come to without passing a node that would have left an event of its
 *    own. If several fit, the way has to agree with how the step at that place ended (after a
 *    failure the flow leaves on an `exception` edge, after a fired timer an await leaves on its
 *    `timeout` edge, and otherwise on neither), then the place must not be waiting for a step
 *    to end, then the name must match exactly (a timer whose length the document does not know
 *    fits any length), then the place is the one the last matched event moved.
 * 2. The same, allowing what the document cannot rule out: a compensation skips or repeats its
 *    steps, and an unknown call of the reason `recursion` leads back to any node before it.
 * 3. If an unknown call can be come to instead, the event has no node: it is reported as
 *    unmapped, with the unknown calls as its likely source.
 * 4. Otherwise the event goes to the one candidate of its name, and is reported as out of
 *    order; with several candidates it goes to all of them and is reported as ambiguous.
 *
 * If one place can come to several candidates, the walk goes on from all of them and later
 * events settle which it was; what stays open at the end counts for all of them.
 *
 * **Which edges were taken.** The moves between two matched nodes are the taken edges, and the
 * nodes passed on the way (branches, awaits without a timer, unknown calls, regions) are hit.
 * Where several ways lead from one to the other, what all of them share is certain; the rest is
 * counted too, marked uncertain, and reported.
 *
 * A version gate is left on the edge of the version its marker recorded, or on `default` if the
 * history has no marker for its change id. A `wakes` edge is taken once for every call of its
 * handler that arrives while a place waits at its await. One that starts at the workflow
 * method has no call to count: it is taken where the history shows that the wait was ended
 * and by no handler, which is where the await had started its timer, the timer was cancelled,
 * and no handler that wakes the await was called in between.
 *
 * **What is not waited for.** A step that the flow does not wait for is passed like any other,
 * and how it ends steers nothing: the flow has gone on by then. A parallel region that is not
 * waited for is entered by one place for each of its lanes, while another goes on from where the
 * region was entered; the lanes end where they end.
 *
 * **Races.** A parallel region that the flow leaves when the first of its lanes has ended is
 * left by the place of the lane that gets to its end first, and counts as left then. The
 * places of the other lanes stay where they are and go on in their lanes as the history says:
 * a lane that ends later is counted to its end, one that is cancelled is not. None of them
 * goes on past the region.
 *
 * **Cancellation.** The node where a cancellation scope is cancelled leaves no event of its
 * own. What it cancels does: a request to cancel an activity or a child workflow, a timer that
 * is cancelled. Such an event belongs to the node that cancels the step it names, if a place
 * can come to that node: the place is moved there, and the `cancels` edge to the step is taken.
 * The events for the other steps of the scope find the place there. While a step that the node
 * cancels is still running, the flow cannot have passed the node without such an event. A place
 * that waits for a step of its own to end does not come to the node, unless it stands in a lane. A step that was cancelled
 * by something that is no node, as when the workflow itself is cancelled, leaves nothing. A
 * request to cancel a workflow that is no child is a step of its own.
 *
 * **Upserts.** An event that sets search attributes or entries of the memo belongs to a node
 * of that kind which writes the same names, to one that names some of them and says that it
 * writes more, or to one of which the document does not say what it writes.
 *
 * **Retries.** A loop that is a retry leaves events no node of its own stands for. Where it
 * begins, the SDK notes its options in a marker under an id it makes up; between two attempts
 * the workflow pauses on a timer. Both belong to the loop, if a place is there: the marker
 * where the loop is entered, the timer at its head, where a step that failed leads back to.
 *
 * **What else a step leaves.** An activity that a worker has picked up, a child workflow or a
 * Nexus operation that has started, and what became of a request to cancel a child or an
 * operation, are laid on the node of that step and move nothing. A request to cancel an
 * activity, a child or an operation is a sign that a scope was cancelled; where no node
 * cancels the step, the request is laid on the step itself.
 *
 * **From outside.** A request to cancel the workflow, and the like, is no step of it and has
 * no node. It is reported as recognised, apart from the steps without a node.
 *
 * **Handlers.** A signal, or an update that was accepted, starts the graph of its handler; an
 * update also that of its validator, which let it pass. One that has no handler of its name
 * starts the handler the workflow registered for every name, if the document has one. A handler in which nothing leaves an
 * event has returned at once. An update returns when the history says it completed, any other
 * handler when the workflow ends, as far as the flow leads to its end without an event. Queries
 * are not in histories.
 *
 * **The end.** A completed execution ends at the `end` node of the workflow method, one that
 * continued as new at a `continueAsNew` node. A failed one ends at a `fail` node only if the
 * flow leads there: a failure that escapes from a step no clause catches has no node, and
 * neither has a termination, a timeout or a cancellation the workflow does not rethrow. Those
 * are no unmapped events: the execution stopped where it was.
 */
class ExecutionMapper(document: IrDocument) {
    private val graph = FlowGraph(document)

    /** The document the histories are laid over, in canonical order. */
    val document: IrDocument get() = graph.document

    /** The edges hits are counted for that the document does not list: those between regions and their children. */
    val impliedEdges: List<EdgeRef> get() = graph.implied

    /** The entry point whose graph each node belongs to. */
    val owners: Map<String, String> get() = graph.owner

    fun map(history: ExecutionHistory): ExecutionTrace = Walk(graph, history).run()
}

private class Counter {
    var count = 0
    var uncertain = 0
}

/** A handler that is running, or the workflow method. */
private class Activation(val entryPoint: EntryPoint, val opened: Long)

/** A parallel region that has been entered, with the places in its lanes. */
private class Fork(val region: IrNode) {
    val members = ArrayList<Token>()
}

/** A way from where a place is to a node an event may belong to. */
private class Reach(val token: Token, val from: Vertex, val target: Vertex, val arcs: Set<Arc>, val agrees: Boolean)

/** A node an event may belong to, with the ways to it from the places before. */
private class Option(val vertex: Vertex, val reaches: List<Reach>)

/** An event that fits several nodes, kept until later events tell which it was. */
private class Pending(val occurrence: Occurrence, val options: List<Option>)

/** One place the execution is at. */
private class Token(val id: Int, val thread: Activation) {
    /** Where it is; several vertices while the events so far fit several nodes. */
    var at: List<Vertex> = emptyList()

    /** For a lane that has not begun: where it may begin. */
    var lane: Set<Vertex>? = null

    /** True for the place that goes on past a region that is not waited for: it cannot enter the region it stands at. */
    var passing = false

    /** For the place of a lane that was not the first of its race to end: the region of the race, which it does not leave. */
    var confined: IrNode? = null

    /** The event of the step it waits for, until that step ends. */
    var waiting: Long? = null

    /** How the step it is at ended. */
    var outcome: Outcome? = null

    /** Position in the history of the event that brought it here. */
    var arrived = 0

    /** Position in the history from which it could go on. */
    var free = 0
    val forks = ArrayList<Fork>()
    val trail = ArrayList<Pending>()
    var alive = true
}

/**
 * A node an event may belong to.
 *
 * @param exact whether the node names what the event names
 * @param passing whether the event belongs there only if a place can come to it: something a
 *   region leaves in passing, which is not laid on it out of order
 */
private class Candidate(val vertex: Vertex, val exact: Boolean = true, val passing: Boolean = false)

/** What was counted without being certain. */
private class Doubts(val nodes: List<String>, val edges: List<EdgeRef>) {
    val none: Boolean get() = nodes.isEmpty() && edges.isEmpty()

    companion object {
        val NONE = Doubts(emptyList(), emptyList())
    }
}

private class Walk(private val graph: FlowGraph, private val history: ExecutionHistory) {
    private val tokens = ArrayList<Token>()
    private var nextToken = 0
    private var last: Token? = null
    private lateinit var current: Occurrence
    private var index = 0

    private val nodeHits = LinkedHashMap<String, Counter>()
    private val edgeHits = LinkedHashMap<EdgeRef, Counter>()
    private val entryPointHits = LinkedHashMap<String, Counter>()

    /** Positions in the history at which each handler was called. */
    private val received = HashMap<String, MutableList<Int>>()

    /** The version each change id was recorded with. */
    private val versions = HashMap<String, Int>()

    /** The steps that were taken and have not ended, by their events, each with the node it was laid on. */
    private val running = HashMap<Long, String>()

    private val matches = ArrayList<Match>()
    private val unmapped = ArrayList<UnmappedEvent>()
    private val recognised = ArrayList<RecognisedEvent>()
    private val ambiguous = ArrayList<AmbiguousMatch>()
    private var mapped = 0
    private var status = CloseStatus.RUNNING
    private var closing: Long? = null

    fun run(): ExecutionTrace {
        for ((position, occurrence) in history.occurrences.withIndex()) {
            index = position
            current = occurrence
            when (occurrence) {
                is WorkflowStarted -> start(occurrence)
                is StepTaken -> step(occurrence)
                is StepEnded -> {
                    ended(occurrence.step, occurrence.outcome)
                    // A timer that is cancelled says so as it ends. An await lets go of its timer
                    // when its condition holds: that is no cancellation of a scope.
                    if (occurrence.outcome == Outcome.CANCELED) cancelled(occurrence, occurrence.step, NodeKind.TIMER)
                }
                is StepCancelled -> if (!cancelled(occurrence, occurrence.step, only = null) && occurrence.noted) noted(occurrence, occurrence.step)
                is StepNoted -> noted(occurrence, occurrence.step)
                is Outside -> recognised += RecognisedEvent(occurrence.eventId, occurrence.eventType)
                is HandlerCalled -> call(occurrence)
                is HandlerEnded -> tokens.filter { it.thread.opened == occurrence.called && it.thread.entryPoint !== graph.main }.forEach(::finish)
                is WorkflowClosed -> close(occurrence)
                is UnknownStep -> unmapped(occurrence, unknownAhead())
            }
        }
        tokens.toList().forEach { settle(it, null) }
        return ExecutionTrace(
            history = history,
            status = status,
            closedAt = matches.lastOrNull { it.eventId == closing }?.nodes.orEmpty(),
            nodes = nodeHits.mapValues { Hits(it.value.count, it.value.uncertain) },
            edges = edgeHits.mapValues { Hits(it.value.count, it.value.uncertain) },
            entryPoints = entryPointHits.mapValues { Hits(it.value.count, it.value.uncertain) },
            matches = matches.sortedBy { it.eventId },
            unmapped = unmapped,
            ambiguous = ambiguous.sortedWith(compareBy({ it.eventId }, { it.doubt })),
            mapped = mapped,
            recognised = recognised,
        )
    }

    // --- events ---

    private fun start(occurrence: WorkflowStarted) {
        val main = graph.main ?: return unmapped(occurrence, emptyList())
        mapped++
        matches += Match(occurrence.eventId, occurrence.eventType, main.name, listOf(main.start))
        enter(main, occurrence)
    }

    private fun step(step: StepTaken) {
        if (step.kind == StepKind.VERSION && step.name != null && step.version != null) versions[step.name] = step.version
        place(step, candidates(step), step.waits, step.outcome)
    }

    /** The step taken with the event [step] ended: the place that waited for it can go on. */
    private fun ended(step: Long, outcome: Outcome) {
        running -= step
        val token = tokens.firstOrNull { it.waiting == step } ?: return
        token.waiting = null
        token.outcome = outcome
        token.free = index
        if (token.confined != null) runOut(token)
    }

    /**
     * The step that [token] stood at in a lane that lost its race has ended. If the lane ends
     * with it, the place has come to the end of the region, where nothing follows it; if
     * nothing leads on from how the step ended, the lane ends there. Otherwise the place waits
     * for what its lane does next.
     */
    private fun runOut(token: Token) {
        val region = token.confined ?: return
        val done = search(listOf(token), setOf(graph.exit(region.id)), shortcuts = false).filter { it.agrees }
        val stuck = token.at.all { from -> agreeing(token, from, leaving(token, from, shortcuts = false)).isEmpty() }
        if (done.isNotEmpty()) take(token, done, wakes = false)
        if (done.isNotEmpty() || stuck) end(token)
    }

    /** The lane of [token], which lost its race, is over: the place goes, with whatever it started in its lane. */
    private fun end(token: Token) {
        val region = token.confined ?: return
        // The regions around the race are not its own to end.
        kill(token, token.forks.lastOrNull { graph.inside(graph.exit(region.id), it.region) })
    }

    /**
     * The step taken with the event [taken] was asked to cancel by the workflow itself. That is
     * what the node does that cancels the scope the step runs in: a place comes to that node,
     * if one can, and the edge from it to the step is taken. The events for the other steps of
     * the scope find the place there.
     *
     * @param only the kind of node the step has to be for the event to say that; `null` for any
     * @return whether the event was laid on a node that cancels the step
     */
    private fun cancelled(cancel: Occurrence, taken: Long, only: NodeKind?): Boolean {
        val step = matches.lastOrNull { it.eventId == taken }?.nodes?.singleOrNull()?.takeIf { it in graph.nodeIds } ?: return false
        if (only != null && graph.node(step).kind != only) return false
        val edges = graph.cancelling(step)
        val points = edges.map { graph.arrival(it.from) }.distinct()
        if (points.isEmpty()) return false
        fun standing(): Vertex? = tokens.firstNotNullOfOrNull { token -> token.at.singleOrNull()?.takeIf { it in points } }
        val there = standing()
        if (there != null) {
            mapped++
            matches += Match(cancel.eventId, cancel.eventType, null, listOf(there.node.id))
        } else if (!reach(cancel, points.map { Candidate(it) }, waits = false, outcome = null, among = ::free)) {
            // No place can come to a node that cancels the step: something that is no node cancelled it.
            return false
        }
        val at = standing() ?: return true
        edges.filter { it.from == at.node.id }.forEach { hit(edgeHits, EdgeRef(it), sure = true) }
        return true
    }

    /**
     * The history says more about the step taken with the event [taken], which goes on. The
     * event is laid on the node of that step, if the step has one, and moves nothing.
     */
    private fun noted(occurrence: Occurrence, taken: Long) {
        val step = matches.lastOrNull { it.eventId == taken } ?: return
        val node = step.nodes.singleOrNull()?.takeIf { it in graph.nodeIds } ?: return
        mapped++
        matches += Match(occurrence.eventId, occurrence.eventType, step.name, listOf(node))
    }

    /**
     * Whether the place of [reach] is free to go on to a node that cancels a scope. One that
     * waits for a step to end is not, unless it stands in a lane: the flow around a lane may
     * have gone on without it, as it does once the first of several lanes is done.
     */
    private fun free(reach: Reach): Boolean = reach.token.waiting == null || reach.token.forks.isNotEmpty()

    private fun call(call: HandlerCalled) {
        // What has no handler of its name goes to the one the workflow registered for every name, if it did.
        val name = if (graph.entryPoints.any { it.kind == call.kind && it.name == call.name }) call.name else EntryPoint.ANY_NAME
        val called = graph.entryPoints.filter { it.kind == call.kind && it.name == name }
        if (called.isEmpty()) return unmapped(call, emptyList())
        mapped++
        matches += Match(call.eventId, call.eventType, call.name, called.map { it.id })
        // A validator leaves no event of its own: it ran, and let the update pass.
        val validators = graph.entryPoints.filter { call.kind == EntryPointKind.UPDATE && it.kind == EntryPointKind.UPDATE_VALIDATOR && it.name == name }
        (validators + called).forEach { enter(it, call) }
    }

    private fun close(closed: WorkflowClosed) {
        status = closed.status
        closing = closed.eventId
        when (closed.status) {
            CloseStatus.COMPLETED -> {
                val ends = graph.arrivals.filter { it.node.kind == NodeKind.END && graph.owner[it.node.id] == graph.main?.id }
                if (!reach(closed, ends.map { Candidate(it) }, waits = false, outcome = null)) {
                    when (ends.size) {
                        0 -> unmapped(closed, unknownAhead())
                        1 -> outOfOrder(closed, ends.single(), waits = false, outcome = null)
                        else -> several(closed, ends)
                    }
                }
            }
            CloseStatus.CONTINUED_AS_NEW ->
                place(closed, graph.arrivals.filter { it.node.kind == NodeKind.CONTINUE_AS_NEW }.map { Candidate(it) }, waits = false, outcome = null)
            // A failure ends at a `fail` node only if the flow leads there; one that escapes from a step has no node.
            CloseStatus.FAILED ->
                reach(closed, fails { true }.map { Candidate(it, exact = it.node.name in closed.failure) }, waits = false, outcome = null, agreeing = true)
            CloseStatus.CANCELED ->
                reach(closed, fails { it.name in closed.failure }.map { Candidate(it) }, waits = false, outcome = null, agreeing = true)
            CloseStatus.TIMED_OUT, CloseStatus.TERMINATED, CloseStatus.RUNNING -> Unit
        }
        // Handlers that were still running are taken to have returned, where nothing stood in their way.
        tokens.filter { it.thread.entryPoint !== graph.main }.forEach(::finish)
    }

    private fun fails(named: (IrNode) -> Boolean): List<Vertex> =
        graph.arrivals.filter { it.node.kind == NodeKind.FAIL && named(it.node) }

    // --- candidates ---

    private fun candidates(step: StepTaken): List<Candidate> = nodes(step) + retries(step)

    /**
     * The retries an event may belong to that no node of its own stands for. Where a retry
     * begins, the SDK notes its options as a mutable side effect under an id it makes up; that
     * is where the loop is entered. Between two attempts the workflow pauses on a timer whose
     * length grows with the attempts; that is at the head of the loop.
     */
    private fun retries(step: StepTaken): List<Candidate> = when (step.kind) {
        StepKind.MUTABLE_SIDE_EFFECT ->
            if (step.name?.let(RETRY_ID::matches) == true) graph.retries.map { Candidate(graph.arrival(it.id), exact = false, passing = true) } else emptyList()
        StepKind.TIMER -> graph.retries.map { Candidate(graph.head(it.id), exact = false, passing = true) }
        else -> emptyList()
    }

    private fun nodes(step: StepTaken): List<Candidate> = graph.arrivals.mapNotNull { vertex ->
        val node = vertex.node
        fun named(kind: NodeKind) = Candidate(vertex).takeIf { node.kind == kind && node.name == step.name }
        when (step.kind) {
            StepKind.ACTIVITY -> named(NodeKind.ACTIVITY)
            StepKind.LOCAL_ACTIVITY -> named(NodeKind.LOCAL_ACTIVITY)
            StepKind.CHILD_WORKFLOW -> named(NodeKind.CHILD_WORKFLOW)
            StepKind.SIGNAL_EXTERNAL -> named(NodeKind.SIGNAL_EXTERNAL)
            // The event names the workflow by its id, which says nothing of its type.
            StepKind.CANCEL_EXTERNAL -> Candidate(vertex).takeIf { node.kind == NodeKind.CANCEL_EXTERNAL }
            StepKind.NEXUS_OPERATION -> named(NodeKind.NEXUS_OPERATION)?.takeIf { node.service == null || node.service == step.service }
            StepKind.VERSION -> Candidate(vertex).takeIf { node.kind == NodeKind.VERSION_GATE && node.changeId == step.name }
            StepKind.SIDE_EFFECT -> Candidate(vertex).takeIf { node.kind == NodeKind.SIDE_EFFECT && node.name == null }
            StepKind.MUTABLE_SIDE_EFFECT -> when {
                node.kind != NodeKind.SIDE_EFFECT -> null
                node.name == null -> Candidate(vertex, exact = false)
                else -> Candidate(vertex).takeIf { node.name == step.name }
            }
            StepKind.TIMER -> when {
                node.kind == NodeKind.TIMER -> timed(vertex, node.duration, step.duration)
                graph.timed(vertex) -> timed(vertex, node.timeout, step.duration)
                else -> null
            }
            StepKind.UPSERT_SEARCH_ATTRIBUTES -> upsert(vertex, NodeKind.UPSERT_SEARCH_ATTRIBUTES, step.keys)
            StepKind.UPSERT_MEMO -> upsert(vertex, NodeKind.UPSERT_MEMO, step.keys)
        }
    }

    /**
     * An upsert fits a node that writes the same names, one that names some of them and says
     * that it writes more, or one of which the document does not say what it writes.
     */
    private fun upsert(vertex: Vertex, kind: NodeKind, keys: Set<String>): Candidate? = when {
        vertex.node.kind != kind -> null
        vertex.node.keys.isEmpty() -> Candidate(vertex, exact = false)
        vertex.node.otherKeys -> Candidate(vertex, exact = false).takeIf { keys.containsAll(vertex.node.keys) }
        vertex.node.keys.toSet() == keys -> Candidate(vertex)
        else -> null
    }

    /** A timer fits if it runs as long as the event says, or if the document does not know how long it runs. */
    private fun timed(vertex: Vertex, written: String?, length: Duration?): Candidate? {
        val known = written?.let {
            try {
                Duration.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        }
        return when (known) {
            null -> Candidate(vertex, exact = false)
            length -> Candidate(vertex)
            else -> null
        }
    }

    // --- placing an event ---

    private fun place(occurrence: Occurrence, candidates: List<Candidate>, waits: Boolean, outcome: Outcome?) {
        if (reach(occurrence, candidates, waits, outcome)) return
        val sources = unknownAhead()
        // What a region leaves in passing is not laid on it where no place is there.
        val firm = candidates.filterNot { it.passing }
        val named = firm.filter { it.exact }.ifEmpty { firm }
        when {
            sources.isNotEmpty() || named.isEmpty() -> unmapped(occurrence, sources)
            named.size == 1 -> outOfOrder(occurrence, named.single().vertex, waits, outcome)
            else -> several(occurrence, named.map { it.vertex })
        }
    }

    /**
     * Moves a place to the candidate it can come to, first as the document says, then with what
     * the document cannot rule out. False if no place can come to any candidate.
     *
     * @param agreeing whether only ways count that agree with how the step before ended
     * @param among which of the ways count at all
     */
    private fun reach(
        occurrence: Occurrence,
        candidates: List<Candidate>,
        waits: Boolean,
        outcome: Outcome?,
        agreeing: Boolean = false,
        among: (Reach) -> Boolean = { true },
    ): Boolean {
        val targets = candidates.mapTo(LinkedHashSet()) { it.vertex }
        if (targets.isEmpty()) return false
        val exact = candidates.filter { it.exact }.mapTo(HashSet()) { it.vertex }
        for (shortcuts in listOf(false, true)) {
            val found = search(tokens.toList(), targets, shortcuts).filter { (!agreeing || it.agrees) && among(it) }
            if (found.isEmpty()) continue
            val order = compareBy<Reach>({ !it.agrees }, { it.token.waiting != null }, { it.target !in exact }, { it.token !== last })
            val best = found.minWith(order)
            val winners = found.filter { order.compare(it, best) == 0 }
            val targetsWon = winners.map { it.target }.distinct()
            val owners = winners.map { it.token }.distinct()
            when {
                targetsWon.size == 1 -> {
                    val token = owners.minBy { it.id }
                    arrive(token, winners.filter { it.token === token }, occurrence, waits, outcome)
                }
                owners.size == 1 && winners.none(::crossesFork) -> defer(owners.single(), winners, occurrence, waits, outcome)
                else -> several(occurrence, targetsWon)
            }
            return true
        }
        return false
    }

    private fun search(among: List<Token>, targets: Set<Vertex>, shortcuts: Boolean): List<Reach> {
        val found = ArrayList<Reach>()
        for (token in among) {
            for (from in token.at) {
                val all = leaving(token, from, shortcuts)
                val agreeing = agreeing(token, from, all)
                val first = explore(token, agreeing, targets, shortcuts)
                first.reached.forEach { found += Reach(token, from, it, first.towards(from, it), agrees = true) }
                if (agreeing.size == all.size) continue
                val second = explore(token, all, targets, shortcuts)
                second.reached.filter { it !in first.reached }.forEach { found += Reach(token, from, it, second.towards(from, it), agrees = false) }
            }
        }
        return found
    }

    private fun explore(token: Token, first: List<Arc>, targets: Set<Vertex>, shortcuts: Boolean): Exploration =
        Exploration.run(first, targets, ::silent) { if (ends(token, it)) emptyList() else passing(it, targets, shortcuts) }

    /** Whether [vertex] is where the lane of [token] ends for good: the end of the region of a race it did not win. */
    private fun ends(token: Token, vertex: Vertex): Boolean = vertex.port == Port.OUT && vertex.node.id == token.confined?.id

    /**
     * Whether the flow can pass [vertex] without an event, as things stand. The node where a
     * scope is cancelled leaves an event for whatever still runs in the scope: while a step it
     * cancels is running, the flow cannot have passed it unseen.
     */
    private fun silent(vertex: Vertex): Boolean {
        if (!graph.silent(vertex)) return false
        if (vertex.port != Port.NODE || vertex.node.kind != NodeKind.CANCEL_SCOPE) return true
        return running.values.none { step -> graph.cancelling(step).any { it.from == vertex.node.id } }
    }

    /** The moves open to a place. A lane that has not begun can only begin. */
    private fun leaving(token: Token, from: Vertex, shortcuts: Boolean): List<Arc> {
        if (ends(token, from)) return emptyList()
        var arcs = if (shortcuts) from.out + from.shortcuts else from.out
        val lane = token.lane
        if (lane != null && from.port == Port.IN) arcs = arcs.filter { it.to in lane }
        if (token.passing && from.port == Port.IN) arcs = arcs.filter { !graph.inside(it.to, from.node) }
        return if (from.port == Port.NODE && from.node.kind == NodeKind.VERSION_GATE) versioned(from, arcs) else arcs
    }

    /** The moves that agree with how the step at [from] ended. */
    private fun agreeing(token: Token, from: Vertex, arcs: List<Arc>): List<Arc> = when {
        from.port != Port.NODE -> arcs
        // The flow did not wait for the step, so how it ended steers nothing.
        graph.aside(from.node) -> arcs
        // A timer that fired is the timeout of the await; one that was cancelled is not.
        from.node.kind == NodeKind.AWAIT ->
            if (token.outcome == Outcome.COMPLETED) {
                arcs.filter { it.kind == EdgeKind.TIMEOUT }.ifEmpty { arcs.filter { it.kind != EdgeKind.EXCEPTION } }
            } else {
                arcs.filter { it.kind != EdgeKind.TIMEOUT }
            }
        token.outcome == Outcome.COMPLETED -> arcs.filter { it.kind != EdgeKind.EXCEPTION }
        // After a failure the flow takes the way of the failure; in a retry that is also the way back to the head of the loop.
        token.outcome != null -> arcs.filter { it.kind == EdgeKind.EXCEPTION || retries(it) }
        else -> arcs
    }

    /** Whether [arc] leads a step of a retry back to the head of its loop, as it does when the step fails and the retry tries again. */
    private fun retries(arc: Arc): Boolean = arc.to.port == Port.HEAD && arc.to.node.retry && graph.inside(arc.from, arc.to.node)

    /** The moves on from a vertex the flow passes without an event. */
    private fun passing(vertex: Vertex, targets: Set<Vertex>, shortcuts: Boolean): List<Arc> {
        val arcs = if (shortcuts) vertex.out + vertex.shortcuts else vertex.out
        if (vertex.port != Port.NODE) return arcs
        return when (vertex.node.kind) {
            // No timer was started, so the time was not up.
            NodeKind.AWAIT -> arcs.filter { it.kind != EdgeKind.TIMEOUT }
            NodeKind.VERSION_GATE -> versioned(vertex, arcs)
            NodeKind.UNKNOWN ->
                if (shortcuts && graph.isRecursion(vertex)) {
                    val before = graph.upstream(vertex)
                    arcs + targets.filter { it in before }.map { Arc(vertex, it, EdgeKind.FLOW, null, null) }
                } else {
                    arcs
                }
            else -> arcs
        }
    }

    /** The moves out of a version gate for the version the history recorded, if they are told apart. */
    private fun versioned(gate: Vertex, arcs: List<Arc>): List<Arc> {
        val version = versions[gate.node.changeId] ?: DEFAULT_VERSION
        val label = if (version == DEFAULT_VERSION) "default" else "v$version"
        return arcs.filter { it.label == label }.ifEmpty { arcs }
    }

    private fun crossesFork(reach: Reach): Boolean {
        val enclosing = reach.token.forks.lastOrNull()?.region
        return reach.arcs.any { arc ->
            (arc.from.port == Port.IN && arc.from.node.kind == NodeKind.PARALLEL) ||
                (arc.to.port == Port.OUT && arc.to.node.kind == NodeKind.PARALLEL) ||
                (enclosing != null && !graph.inside(arc.to, enclosing))
        }
    }

    /** Unknown calls that some place can come to without an event: where a step without a node may hide. */
    private fun unknownAhead(): List<String> {
        val found = sortedSetOf<String>()
        for (token in tokens) {
            for (from in token.at) {
                explore(token, leaving(token, from, shortcuts = false), emptySet(), shortcuts = false).visited
                    .filter { it.port == Port.NODE && it.node.kind == NodeKind.UNKNOWN }
                    .forEach { found += it.node.id }
            }
        }
        return found.toList()
    }

    // --- moving ---

    /** [token] comes to the one node the event belongs to. */
    private fun arrive(token: Token, reaches: List<Reach>, occurrence: Occurrence, waits: Boolean, outcome: Outcome?) {
        val target = reaches.first().target
        settle(token, reaches.mapTo(HashSet()) { it.from })
        record(occurrence, listOf(target), take(token, reaches, wakes = true))
        advance(token, Ways.shortest(reaches.first().arcs, reaches.first().from, target))
        stand(token, listOf(target), occurrence, waits, outcome)
    }

    /** [token] may have come to any of several nodes; the events that follow will tell. */
    private fun defer(token: Token, reaches: List<Reach>, occurrence: Occurrence, waits: Boolean, outcome: Outcome?) {
        val options = reaches.groupBy { it.target }.map { (vertex, ways) -> Option(vertex, ways) }
        token.trail += Pending(occurrence, options)
        stand(token, options.map { it.vertex }, occurrence, waits, outcome)
    }

    private fun stand(token: Token, at: List<Vertex>, occurrence: Occurrence, waits: Boolean, outcome: Outcome?) {
        if (occurrence is StepTaken && waits && at.size == 1) running[occurrence.eventId] = at.single().node.id
        token.at = at
        token.lane = null
        token.passing = false
        // What the flow does not wait for holds no place.
        token.waiting = occurrence.eventId.takeIf { waits && at.none { it.port == Port.NODE && graph.aside(it.node) } }
        token.outcome = outcome
        token.arrived = index
        token.free = index
        last = token
    }

    /**
     * Counts the events [token] has kept open. Of the nodes the last of them may belong to, only
     * [standing] are still possible; `null` if nothing has narrowed them down. From there it
     * follows backwards which nodes the events before can have belonged to.
     */
    private fun settle(token: Token, standing: Set<Vertex>?) {
        if (token.trail.isEmpty()) return
        var possible = standing ?: token.trail.last().options.mapTo(HashSet()) { it.vertex }
        val kept = ArrayList<List<Option>>()
        for (pending in token.trail.asReversed()) {
            val options = pending.options.filter { it.vertex in possible }
            kept += options
            possible = options.flatMapTo(HashSet()) { option -> option.reaches.map { it.from } }
        }
        for ((pending, options) in token.trail.zip(kept.asReversed())) {
            record(pending.occurrence, options.map { it.vertex }, take(token, options.flatMap { it.reaches }, wakes = false))
        }
        token.trail.clear()
        if (standing == null) token.at = kept.first().map { it.vertex }
    }

    /**
     * Counts what lies on the ways the flow took. With one way, everything on it is certain;
     * with several, what all of them share. Returns what is not certain.
     */
    private fun take(token: Token, reaches: List<Reach>, wakes: Boolean): Doubts {
        val certain = reaches.map { Ways.certain(it.arcs, it.from, it.target) }
        val targets = reaches.mapTo(LinkedHashSet()) { it.target }
        val arcs = reaches.flatMapTo(LinkedHashSet()) { it.arcs }
        val doubtfulEdges = ArrayList<EdgeRef>()
        val doubtfulNodes = ArrayList<String>()
        for (arc in arcs) {
            val edge = arc.edge ?: continue
            val sure = certain.all { arc in it.first }
            hit(edgeHits, edge, sure)
            if (!sure) doubtfulEdges += edge
        }
        for (vertex in arcs.mapTo(LinkedHashSet()) { it.to }) {
            if (vertex.port != Port.NODE && vertex.port != Port.IN) continue
            val sure = if (vertex in targets) targets.size == 1 else certain.all { vertex in it.second }
            hit(nodeHits, vertex.node.id, sure)
            if (!sure && vertex !in targets) doubtfulNodes += vertex.node.id
        }
        if (wakes) woken(token, reaches)
        return Doubts(doubtfulNodes.distinct().sorted(), doubtfulEdges.distinct().sortedWith(EDGE_ORDER))
    }

    /** Counts the `wakes` edges of the awaits [token] waited at before it went on. */
    private fun woken(token: Token, reaches: List<Reach>) {
        val since = LinkedHashMap<String, Int>()
        for (reach in reaches) {
            // An await it stood at had started its timer: handlers called since then woke it.
            if (reach.from.port == Port.NODE && reach.from.node.kind == NodeKind.AWAIT) since.putIfAbsent(reach.from.node.id, token.arrived)
            // An await it passed began once the step before had ended.
            reach.arcs.map { it.to }
                .filter { it !== reach.target && it.port == Port.NODE && it.node.kind == NodeKind.AWAIT }
                .forEach { since.putIfAbsent(it.node.id, token.free) }
        }
        // An await it stood at had to wait. If its timer was cancelled, something ended the wait.
        val ended = reaches.map { it.from }.filter { it.port == Port.NODE && it.node.kind == NodeKind.AWAIT && token.outcome == Outcome.CANCELED }
        for ((await, from) in since) {
            val (own, handlers) = graph.wakes[await].orEmpty().partition { it.from == graph.main?.id }
            var calls = 0
            for (edge in handlers) {
                val called = received[edge.from].orEmpty().count { it > from && it <= index }
                repeat(called) { hit(edgeHits, EdgeRef(edge), sure = true) }
                calls += called
            }
            // No handler was called meanwhile: what the workflow method itself did ended it.
            if (calls == 0 && ended.any { it.node.id == await }) own.forEach { hit(edgeHits, EdgeRef(it), sure = true) }
        }
    }

    /** Follows [path] for what it does to parallel regions: entering one starts its lanes, leaving one ends them. */
    private fun advance(token: Token, path: List<Arc>) {
        var begins = token.lane != null || token.passing
        for (arc in path) {
            val to = arc.to
            while (token.forks.isNotEmpty()) {
                val fork = token.forks.last()
                val joined = to.port == Port.OUT && to.node.id == fork.region.id
                if (!joined && graph.inside(to, fork.region)) break
                token.forks.removeLast()
                leave(fork, token, joined)
            }
            val from = arc.from
            if (!begins && from.port == Port.IN && from.node.kind == NodeKind.PARALLEL) {
                when {
                    graph.unawaited(from.node) -> aside(token, from, to.takeIf { graph.inside(it, from.node) })
                    graph.inside(to, from.node) -> fork(token, from, to)
                }
            }
            begins = false
        }
    }

    private fun fork(token: Token, entrance: Vertex, entry: Vertex) {
        val fork = Fork(entrance.node)
        fork.members += token
        token.forks += fork
        for (lane in graph.lanes(entrance.node)) {
            if (entry in lane.entries || lane.entries.isEmpty()) continue
            val other = Token(nextToken++, token.thread)
            other.at = listOf(entrance)
            other.lane = lane.entries
            other.forks += token.forks
            other.arrived = index
            other.free = index
            fork.members += other
            tokens += other
        }
    }

    /**
     * [token] came to a parallel region that is not waited for. Its lanes begin, each with a
     * place of its own, and one more place goes on past the region. If [token] entered a lane
     * at [entry], it is the place of that lane; if it went on, it is the one that goes on.
     */
    private fun aside(token: Token, entrance: Vertex, entry: Vertex?) {
        fun spawn(): Token {
            val other = Token(nextToken++, token.thread)
            other.at = listOf(entrance)
            other.arrived = index
            other.free = index
            // It lies in whatever region the place it comes of lies in, and ends with that.
            other.forks += token.forks
            token.forks.forEach { it.members += other }
            tokens += other
            return other
        }
        for (lane in graph.lanes(entrance.node)) {
            if (lane.entries.isEmpty() || (entry != null && entry in lane.entries)) continue
            spawn().lane = lane.entries
        }
        if (entry != null) spawn().passing = true
    }

    /** [token] left a parallel region: at its end, where the other lanes end too, or on an edge out of the middle of it. */
    private fun leave(fork: Fork, token: Token, joined: Boolean) {
        val exit = graph.exit(fork.region.id)
        // The first lane of a race to end takes the flow on. The others run on in their lanes, and none of them leaves the region.
        if (joined && graph.raced(fork.region)) {
            val others = fork.members.filter { it !== token && it.alive }
            fork.members.clear()
            for (member in others) {
                member.forks -= fork
                member.confined = fork.region
                // One whose step is over already has come to where its lane goes next.
                if (member.waiting == null && member.lane == null) runOut(member)
            }
            return
        }
        for (member in fork.members.toList()) {
            if (member === token || !member.alive) continue
            if (joined) follow(member, setOf(exit))
            kill(member, fork)
        }
        // What still ran of a race inside the region ends with the region.
        tokens.filter { other -> other.confined?.let { graph.inside(graph.exit(it.id), fork.region) } == true }.forEach(::end)
    }

    /** Counts the way from where [token] is to one of [targets], if it can come there without an event. */
    private fun follow(token: Token, targets: Set<Vertex>) {
        settle(token, null)
        val found = search(listOf(token), targets, shortcuts = false)
        if (found.isEmpty()) return
        val agreed = found.filter { it.agrees }.ifEmpty { found }
        val doubts = take(token, agreed, wakes = false)
        val ends = agreed.map { it.target.node.id }.distinct().sorted()
        if (ends.size > 1 || !doubts.none) {
            ambiguous += AmbiguousMatch(Doubt.PATHS, current.eventId, current.eventType, nameOf(current), (ends.takeIf { it.size > 1 }.orEmpty() + doubts.nodes).distinct(), doubts.edges)
        }
    }

    private fun kill(token: Token, below: Fork?) {
        settle(token, null)
        token.alive = false
        tokens -= token
        if (last === token) last = null
        val inner = token.forks.drop(if (below == null) 0 else token.forks.indexOf(below) + 1)
        for (fork in inner) for (other in fork.members.toList()) if (other.alive && other !== token) kill(other, fork)
    }

    // --- handlers ---

    private fun enter(entryPoint: EntryPoint, occurrence: Occurrence) {
        hit(entryPointHits, entryPoint.id, sure = true)
        received.getOrPut(entryPoint.id) { ArrayList() } += index
        val start = graph.arrival(entryPoint.start)
        hit(nodeHits, start.node.id, sure = true)
        val token = Token(nextToken++, Activation(entryPoint, occurrence.eventId))
        token.at = listOf(start)
        token.arrived = index
        token.free = index
        tokens += token
        val handler = entryPoint !== graph.main
        // A handler that can leave no event has run by the time the next event is written.
        if (handler && (entryPoint.kind == EntryPointKind.UPDATE_VALIDATOR || !graph.observableFrom(start))) {
            finish(token)
            return
        }
        last = token
        // Signals leave no event when their handler returns. Of a handler that is called over and over, the oldest calls have.
        val open = tokens.filter { it.thread.entryPoint === entryPoint }.map { it.thread }.distinct()
        if (handler && open.size > MAX_OPEN_CALLS) tokens.filter { it.thread === open.first() }.forEach(::finish)
    }

    /** The handler [token] runs returned. */
    private fun finish(token: Token) {
        if (!token.alive) return
        val ends = graph.arrivals.filterTo(LinkedHashSet()) { it.node.kind == NodeKind.END && graph.owner[it.node.id] == token.thread.entryPoint.id }
        if (ends.isNotEmpty()) follow(token, ends)
        kill(token, null)
    }

    // --- results ---

    /**
     * Notes that [occurrence] was laid on [vertices]: on one node, or on several that nothing
     * tells apart.
     *
     * @param doubts what on the way there is not certain
     */
    private fun record(occurrence: Occurrence, vertices: List<Vertex>, doubts: Doubts = Doubts.NONE) {
        val nodes = vertices.map { it.node.id }.sorted()
        val name = nameOf(occurrence)
        matches += Match(occurrence.eventId, occurrence.eventType, name, nodes)
        if (nodes.size == 1) {
            mapped++
            if (!doubts.none) ambiguous += AmbiguousMatch(Doubt.PATHS, occurrence.eventId, occurrence.eventType, name, doubts.nodes, doubts.edges)
        } else {
            ambiguous += AmbiguousMatch(Doubt.CANDIDATES, occurrence.eventId, occurrence.eventType, name, nodes, doubts.edges)
        }
    }

    /** Several nodes fit and no place tells them apart: each is counted, and nothing moves. */
    private fun several(occurrence: Occurrence, vertices: List<Vertex>) {
        record(occurrence, vertices)
        vertices.forEach { hit(nodeHits, it.node.id, sure = false) }
    }

    /** The one node of the event's name, which no place can come to. The place of its graph is put there. */
    private fun outOfOrder(occurrence: Occurrence, vertex: Vertex, waits: Boolean, outcome: Outcome?) {
        mapped++
        matches += Match(occurrence.eventId, occurrence.eventType, nameOf(occurrence), listOf(vertex.node.id))
        ambiguous += AmbiguousMatch(Doubt.OUT_OF_ORDER, occurrence.eventId, occurrence.eventType, nameOf(occurrence), listOf(vertex.node.id))
        hit(nodeHits, vertex.node.id, sure = true)
        val owner = graph.owner[vertex.node.id]
        val running = tokens.filter { it.thread.entryPoint.id == owner }
        val newest = running.maxOfOrNull { it.thread.opened }
        val token = running.filter { it.thread.opened == newest }.singleOrNull() ?: return
        settle(token, null)
        while (token.forks.isNotEmpty() && !graph.inside(vertex, token.forks.last().region)) leave(token.forks.removeLast(), token, joined = false)
        stand(token, listOf(vertex), occurrence, waits, outcome)
    }

    private fun unmapped(occurrence: Occurrence, sources: List<String>) {
        unmapped += UnmappedEvent(occurrence.eventId, occurrence.eventType, nameOf(occurrence), sources)
    }

    private fun <K> hit(hits: MutableMap<K, Counter>, key: K, sure: Boolean) {
        val counter = hits.getOrPut(key) { Counter() }
        counter.count++
        if (!sure) counter.uncertain++
    }

    private fun nameOf(occurrence: Occurrence): String? = when (occurrence) {
        is StepTaken -> when (occurrence.kind) {
            StepKind.TIMER -> occurrence.duration?.toString()
            StepKind.NEXUS_OPERATION -> listOfNotNull(occurrence.service?.ifEmpty { null }, occurrence.name).joinToString(".")
            else -> occurrence.name
        }
        is HandlerCalled -> occurrence.name
        is UnknownStep -> occurrence.name
        else -> null
    }

    private companion object {
        /** What `Workflow.getVersion` returns for code that ran before the gate was added. */
        const val DEFAULT_VERSION = -1

        /** What the SDK calls the mutable side effect in which it notes the options of a retry: an id it makes up. */
        val RETRY_ID = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

        /** How many calls of one handler are followed at a time. */
        const val MAX_OPEN_CALLS = 16

        val EDGE_ORDER: Comparator<EdgeRef> = compareBy<EdgeRef>({ it.from }, { it.to }, { it.kind }).thenBy(nullsFirst()) { it.label }
    }
}
