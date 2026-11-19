package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Argument
import io.github.iw.durableatlas.analyser.code.Block
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CallableReference
import io.github.iw.durableatlas.analyser.code.Callee
import io.github.iw.durableatlas.analyser.code.ClassKind
import io.github.iw.durableatlas.analyser.code.ClassLiteral
import io.github.iw.durableatlas.analyser.code.ClassReference
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Constant
import io.github.iw.durableatlas.analyser.code.Construction
import io.github.iw.durableatlas.analyser.code.Destructuring
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.If
import io.github.iw.durableatlas.analyser.code.Jump
import io.github.iw.durableatlas.analyser.code.JumpKind
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.Literal
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.LocalVariable
import io.github.iw.durableatlas.analyser.code.LocalWrite
import io.github.iw.durableatlas.analyser.code.Loop
import io.github.iw.durableatlas.analyser.code.LoopKind
import io.github.iw.durableatlas.analyser.code.Other
import io.github.iw.durableatlas.analyser.code.PropertyId
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.PropertyWrite
import io.github.iw.durableatlas.analyser.code.Return
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.ThisReference
import io.github.iw.durableatlas.analyser.code.Throw
import io.github.iw.durableatlas.analyser.code.Try
import io.github.iw.durableatlas.analyser.code.TypeRef
import io.github.iw.durableatlas.analyser.code.When
import io.github.iw.durableatlas.analyser.code.find
import io.github.iw.durableatlas.analyser.code.walk
import io.github.iw.durableatlas.analyser.discovery.TemporalTypes
import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.SourceLocation
import io.github.iw.durableatlas.ir.UnknownReasons
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Reads the [Flow] of a function off its code: the steps in the order the code reaches them, and
 * the constructs between them that steer the flow. Straight-line code that is no step leaves
 * nothing, and neither does a branch, a loop or a `try` that holds no step and ends no path.
 *
 * How the constructs are told:
 * - `if` and `when` are choices. An `if` is labelled with its condition and its arms with `yes`
 *   and `no`; a `when` is labelled with its subject and each arm with its condition, or `else`.
 *   `a ?: b`, `a && b` and `a || b` are choices too if `b` holds something.
 * - `for`, `while` and `do`-`while` are loops, and so are the functions of the standard library
 *   that run a lambda for each element of a collection. A loop is labelled `for each x` after
 *   its variable or the lambda's parameter, `for each of xs` where that has no name, and
 *   `while c` or `do while c` with the condition.
 * - `try` keeps its clauses and its `finally`; `return`, `throw`, `break` and `continue` end the
 *   path they are on. A `throw` says whether what it throws may fail the execution of the
 *   workflow (see [FailureTypes.failsExecution]): what a clause throws again as it caught it
 *   may only if something that does may have got to the clause.
 * - What is handed to `Async` runs beside the flow, what is handed to a saga runs when the saga
 *   compensates, and what is handed to a scope function or a cancellation scope runs in place.
 *   What is chained to a promise runs after what the promise stands for, in the same lane.
 * - A call that gives a promise for the step it stands for, as `executeAsync` of a stub without
 *   an interface, `Workflow.newTimer` and `Async.await` do, starts that step beside the flow
 *   like what is handed to `Async`, unless the promise is waited for where it is made.
 * - The promise of what was started beside the flow is followed to where it is waited for. One
 *   that is dropped where it is made, or kept in a variable nothing ever waits on, is not
 *   awaited: the flow goes on at once. A promise that goes anywhere this reading does not
 *   follow, into a property or another function, is taken to be waited for. Promises that are
 *   put into a collection are found again where the collection is handed to `Promise.anyOf`.
 * - The lanes whose promises are handed to `Promise.anyOf` together run a race: the flow waits
 *   for the first of them and no longer.
 * - A cancellation scope is known by the call that made it, so the place where it is cancelled
 *   can say what it cancels.
 * - A call of a function written in the analysed sources is followed where a step of the
 *   workflow is certain to lie in the function or behind it (see [StepProbe]): what the function
 *   does stands where it is called, and so does the getter of a property where the property is
 *   read. A lambda handed to such a function stands where the function runs it. Where such a
 *   call is not followed, a node says why: the calls are nested deeper than the limit, the
 *   function is already being followed, or the call may run one of several functions (see
 *   [Dispatch]).
 * - A function in which no step is certain is not drawn from the inside, whatever it branches
 *   and loops over: it is ordinary code that the workflow calls, like that of a library. If it
 *   may fail, or calls a function that no class of the sources implements, the place where it
 *   is called says so (see [Undrawn]); otherwise it leaves nothing. It is still read where it
 *   may read or write the state of the workflow, for that alone.
 * - Lambdas handed to anything else are not followed: a node says so, and what the lambdas hold
 *   follows it.
 * - A call that does not resolve is a node that says so. In a degraded model, which lacks the
 *   libraries the sources use, it is one only where it may be a step (see [UnresolvedCalls]);
 *   anywhere else it is read like the call of a library function that it most likely is.
 * - What the workflow sets for its activities apart from the options of a stub, with
 *   `Workflow.setDefaultActivityOptions`, `applyActivityOptions` and their like, is kept as the
 *   reading goes: a call through a stub is given the options that hold where it stands, put
 *   together the way the SDK does it (see [Stubs.activityOptions]). What is set where the code
 *   may not run, in a branch or a loop, leaves only what holds either way.
 *
 * On the way the reader notes which properties of the workflow object the code reads and writes
 * (see [StateRef]): what an await waits for, and what a handler changes.
 */
internal class FlowReader(
    private val backend: SourceBackend,
    private val recognizer: CallRecognizer,
    private val tracer: ValueTracer,
    /** How many calls deep functions of the sources are followed. */
    private val maxDepth: Int,
    /** Is told what the code holds that no graph shows. */
    private val warn: (String) -> Unit = {},
    /** The options for activities that the workflow is given where a worker registers its class. */
    private val registered: ActivityEnvironment = ActivityEnvironment(),
    /** Tells which exceptions fail the execution of a workflow, and which only its task. */
    private val failures: FailureTypes = FailureTypes(backend),
) {
    private val dispatch = Dispatch(backend)
    private val probe = StepProbe(backend, recognizer, dispatch, failures)

    /** The cancellation scopes met so far, by the call that makes each. */
    private val scopeKeys = IdentityHashMap<Code, ScopeKey>()

    /** The scopes each property of the workflow was given, in any entry point. */
    private val scopesHeld = HashMap<PropertyId, MutableSet<ScopeKey>>()

    /**
     * What each stub of an activity interface was given as its default options when it was
     * made, by the call that made it, for the stubs whose making was read. The SDK hands a stub
     * the default as it stands then; what the workflow sets later does not reach the stub. A
     * stub that a function makes and hands back is made where that function is called, so the
     * call of the function is noted as well. A call is known by what it is and where it
     * stands, not by the object that holds it: the initialiser of a property is read once as
     * part of what makes the workflow object, and once more when a call through the property
     * is traced back to it.
     */
    private val stubDefaults = HashMap<Code, ActivitySettings>()

    /**
     * The options for activities that are certain when a handler runs: those the making of the
     * workflow object left, where the workflow method leaves them as they are. A handler may run
     * at any time after that, so what the workflow method sets on its way may or may not hold.
     */
    private var forHandlers: ActivityEnvironment = registered

    /** The scopes that the property [property] holds at one time or another, as far as the code read so far says. */
    fun scopesHeldBy(property: PropertyId): Set<ScopeKey> = scopesHeld[property].orEmpty()

    /** What an entry point does, and what of the workflow's state it reads and writes on the way. */
    class Result(
        val flow: Flow,
        val state: StateUse,
        /** The handlers registered while the entry point runs, in the order they are met. */
        val listeners: List<Listener>,
        /** What the function of the entry point changes as it runs, without what making the object set before. */
        val changes: StateUse,
    )

    /**
     * A handler that the workflow registers itself while it runs, by handing an object to
     * `Workflow.registerListener`: an entry point like those its class declares.
     */
    class Listener(
        val kind: EntryPointKind,
        /** The name the handler goes by; `null` for one that takes every name that has no handler of its own. */
        val name: String?,
        /** Where the handler is written. */
        val location: SourceLocation?,
        /** What the handler does; `null` if it has no body to read. */
        val flow: Flow?,
        val state: StateUse,
    )

    /**
     * The flow of [function], the function that runs for an entry point of the class
     * [implementation]; `null` if it has no body to read.
     *
     * @param thorough read everything the function reaches, also what holds no step: what a
     *   handler reads and writes matters wherever it does so
     * @param constructor the constructor that makes the object before the function runs, if
     *   what it does is part of the entry point: its steps come first
     * @param main whether the entry point is the workflow method, which runs before any handler
     */
    fun read(function: FunctionDecl, implementation: String, thorough: Boolean, constructor: FunctionId? = null, main: Boolean = true): Result? {
        val body = backend.body(function.id) ?: return null
        val entry = Entry(thorough, options = if (main) registered else forHandlers, properties = propertiesOf(implementation))
        val self = Self(null, StateRef.Workflow, implementation)
        val made = constructor?.let { constructed(entry, it, self, emptyList(), null, HashSet()) }
        val constructedWith = entry.options
        // What making the object sets is there before anything waits: it ends no wait.
        entry.lanes.uses.clear()
        entry.stack += function.id
        val reading = Reading(entry, function.name, depth = 0, frame = null, self)
        function.parameters.forEach { reading.scope.bind(it.name, null) }
        val flow = reading.read(body)
        if (main) forHandlers = constructedWith.orElse(entry.options)
        entry.settle()
        if (main) entry.lanes.wakeOwn()
        return Result(
            Series.of(listOfNotNull(made?.takeIf { it.hasContent || it.registrations().isNotEmpty() }, flow)),
            entry.state,
            entry.listeners,
            entry.lanes.all(),
        )
    }

    /** The names of the properties a class of the sources declares, which a local variable of the same name hides. */
    private fun propertiesOf(className: String): Set<String> = backend.properties(className).mapTo(HashSet()) { it.name }

    /** Whether making an object through [constructor] holds anything a graph would show. */
    fun constructionHoldsSteps(constructor: FunctionId, implementation: String): Boolean =
        // The constructor is only looked at: what it would skip is not skipped by anything that runs.
        recognizer.unrecorded {
            constructed(Entry(thorough = false, options = registered), constructor, Self(null, StateRef.Workflow, implementation), emptyList(), null, HashSet()).hasContent
        }

    /**
     * What making an object through the constructor [id] does: what the constructor it hands
     * over to does, then the initialisers and `init` blocks of the class, then its own body. A
     * `return` in the body ends the constructor. Without a step in it, it leaves nothing but
     * the compensations registered on the way.
     *
     * @param arguments what the constructor is handed, where another one hands over to it
     * @param caller the scope to read [arguments] in
     */
    private fun constructed(entry: Entry, id: FunctionId, self: Self, arguments: List<Argument>, caller: Reading?, seen: MutableSet<FunctionId>): Flow {
        if (!seen.add(id)) return Series.EMPTY
        // Asking for the constructors of the class is what makes this one known.
        val declared = id.owner?.let(backend::constructors).orEmpty().firstOrNull { it.id == id }
        val made = backend.construction(id) ?: return Series.EMPTY
        val frame = ++entry.frames
        entry.stack += id
        try {
            val reading = Reading(entry, CONSTRUCTOR, depth = 0, frame, self)
            declared?.parameters?.forEach { reading.scope.bind(it.name, null) }
            if (caller != null) {
                for (argument in arguments) reading.scope.bind(argument.parameter ?: continue, Traced(argument.value, caller.scope), caller.stateOf(argument.value))
            }
            val flow = reading.construct(made) { next, handed -> constructed(entry, next, self, handed, reading, seen) }
            return if (flow.holdsSteps(frame)) LambdaBody(frame, flow) else Series.of(flow.registrations())
        } finally {
            entry.stack.removeAt(entry.stack.lastIndex)
        }
    }

    /** What the readings of one entry point share. */
    private class Entry(
        var thorough: Boolean,
        val listening: MutableSet<String> = HashSet(),
        /** The options the workflow has for its activities where the reading stands, as far as they are certain. */
        var options: ActivityEnvironment = ActivityEnvironment(),
        /** The names of the properties of the workflow's class. */
        val properties: Set<String> = emptySet(),
    ) {
        /** The options as they were when the entry point began. */
        val began: ActivityEnvironment = options

        /** Above zero while reading code that may not run, or not before what follows it. */
        var unsure = 0

        /** The handlers registered so far. */
        val listeners = ArrayList<Listener>()

        /** Frames are numbered across all functions read for the entry point, in the order they are met. */
        var frames = 0
        var state = StateUse()

        /** The functions being read, outermost first: the entry point, and the calls followed from it. */
        val stack = ArrayList<FunctionId>()

        /** The calls followed to get to the code being read, as the ids of nodes take them in. */
        val path = ArrayList<String>()

        /** What was started beside the flow, by the call that gives its promise; a call read again stands for what it started last. */
        val started = IdentityHashMap<Code, Started>()

        /** Everything that was started beside the flow, in the order it was met. */
        val begun = ArrayList<Started>()

        /** Which lane the code being read runs in, and what each lane changes and waits on. */
        val lanes = Lanes()

        /** Above zero while reading what may run more than once: the body of a loop. */
        var rounds = 0

        /** The calls that start something beside the flow and are read as if they ran it in place: what waits for them is the flow itself. */
        val inPlace: MutableSet<Code> = Collections.newSetFromMap(IdentityHashMap())

        /**
         * The calls that give a promise for a step and whose promise is waited for where it is
         * made, as in `Workflow.newTimer(d).get()`: the step is one the flow waits for there.
         */
        val direct: MutableSet<Code> = Collections.newSetFromMap(IdentityHashMap())

        /** The child workflows started so far, each with the expression that made its stub and the lane it runs in. */
        val children = ArrayList<Child>()

        /** The cancellation scopes whose code is being read, innermost last. */
        val scopes = ArrayList<ScopeKey>()

        /** True once code was met that this reading cannot look into, where a promise may be waited for unseen. */
        var opaque = false

        /**
         * Decides for everything started beside the flow whether anything waits for it, once
         * all the code is read. Where the code holds something that was not looked into, a
         * promise kept in a variable may be waited for there.
         */
        fun settle() {
            for (lane in begun) lane.fork.awaited = lane.waited || lane.escaped || (opaque && lane.kept)
        }
    }

    /** A child workflow that was started: its step, the expression that made its stub, and the lane the step stands in. */
    private class Child(val stub: Code, val step: Step, val lane: Any)

    /** What was started beside the flow, and what became of the promise that stands for it. */
    private class Started(
        val fork: Fork,
        /** What tells the lane from the others, for what it changes and waits on; see [Lanes]. */
        val lane: Any,
        /** True if it was started where the code may run more than once: it then stands for a lane of each round. */
        val repeated: Boolean,
    ) {
        /** True once something waits for the promise. */
        var waited = false

        /** True once the promise went where this reading does not follow it: it may be waited for there. */
        var escaped = false

        /** True if the promise was put in a variable. */
        var kept = false

        /** The collections the promise was put into, each by the expression that made it or the property that holds it. */
        val collected = ArrayList<Any>()
    }

    /**
     * Which code of an entry point runs beside which. What is started beside the flow is a
     * lane, and the flow itself is one; what one of them changes can end a wait in another,
     * because it may happen while that wait stands. Within one lane the code runs in order, and
     * what it changed before a wait began ends nothing. Neither does a lane that the flow has
     * waited for by the time it comes to a wait of its own: that lane is over.
     */
    private class Lanes {
        /** The lane the code being read runs in. */
        var current: Any = OWN
            private set

        /** What the code of each lane changes. */
        val uses = IdentityHashMap<Any, StateUse>()

        /** The waits met, each with the lane it stands in and the lanes that are over by the time it begins. */
        private val waits = ArrayList<Triple<Step, Any, Set<Any>>>()

        fun <T> within(lane: Any, block: () -> T): T {
            val outer = current
            current = lane
            try {
                return block()
            } finally {
                current = outer
            }
        }

        fun use(): StateUse = uses.getOrPut(current) { StateUse() }

        /** @param over the lanes that have ended before the wait begins, as far as that is certain */
        fun waits(step: Step, lane: Any = current, over: Collection<Any> = emptyList()) {
            val ended: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
            ended.addAll(over)
            waits += Triple(step, lane, ended)
        }

        /** Whether the code being read is that of the flow itself, and of no lane beside it. */
        val own: Boolean get() = current === OWN

        /** What all the lanes change together. */
        fun all(): StateUse = StateUse().also { all -> uses.values.forEach { all += it } }

        /** Tells every wait what the other lanes change of what it waits on. */
        fun wakeOwn() {
            for ((step, lane, over) in waits) {
                val others = StateUse()
                uses.forEach { (other, use) -> if (other !== lane && other !in over) others += use }
                step.wokenByFlow = others.wakes(step.node.reads, step.queue)
            }
        }

        private companion object {
            /** The lane of the flow itself. */
            val OWN = Any()
        }
    }

    /** The object a function runs on. */
    private class Self(
        /** The expression that made the object, with the scope to read it in, if that is known. */
        val value: Traced?,
        /** What of the workflow's state the object is. */
        val state: StateRef?,
        /** The class of the object, if it is known to be of that class and of no subclass. */
        val type: String?,
    )

    /** A place where a function of the sources is called or referred to. */
    private class Use(
        val location: SourceLocation,
        /** The name as written, which a lambda handed over there is labelled with. */
        val name: String,
        val receiver: Code?,
        val receiverType: TypeRef?,
        val arguments: List<Argument> = emptyList(),
        /** True if what is called there never returns. */
        val never: Boolean = false,
    )

    /** One reading of one function: that of an entry point, or one whose call is being followed. */
    private inner class Reading(
        private val entry: Entry,
        /** The name of the function, which a `return@name` in it names. */
        private val name: String,
        /** How many calls were followed to get here. */
        private val depth: Int,
        /** What a `return` out of the function names; `null` for the entry point. */
        private val frame: Int?,
        private val self: Self,
        /** The scope the code is written in, for code that is no function of its own: a lambda that sees the names around it. */
        around: Scope? = null,
    ) {
        /** How many functions are being read down to this one: it is the last of them. */
        private val stackSize = entry.stack.size

        /** The parts of the sequence being collected. */
        private var parts = ArrayList<Flow>()
        var scope = (around?.child() ?: Scope()).also { if (around == null || self.value != null) it.self = self.value }
            private set

        /** The lambdas and references the function was handed as arguments, by parameter. */
        private val handed = HashMap<String, Handed>()

        /** The lambdas being read, innermost last. */
        private val lambdas = ArrayList<LambdaFrame>()

        /** The loops being read, innermost last; a lambda in between, which is `null` here, hides the loops around it. */
        private val loops = ArrayList<LoopFrame?>()
        private val implicitReceivers = ArrayList<ImplicitReceiver>()

        /** The objects that are `this` for the code being read and whose type could not be resolved, innermost last. */
        private val untypedReceivers = ArrayList<Untyped>()

        /** Above zero inside a scope that the cancellation of the workflow does not reach. */
        private var detached = 0

        /**
         * The names of what the `catch` clauses around the code being read have caught, innermost
         * last, each with whether a failure that fails the workflow may have got to its clause.
         */
        private val caught = ArrayList<Pair<String, Boolean>>()

        /** The step whose outcomes the code being read may still ask for. */
        private var gate: Gate? = null

        /** The step added last, with the call that it stands for. */
        private var lastStep: Pair<Call, Step>? = null

        /**
         * The code about to be visited whose value is dropped: a statement. It hands that on to
         * what it ends with, where it is a block, a branch or a `try`.
         */
        private var statement: Code? = null

        /**
         * The code about to be visited whose value is kept where this reading follows it: in a
         * local variable, or as what a function of a promise is called on.
         */
        private var held: Code? = null

        /** What the workflow has set for its activities where this reading stands, for whoever reads a call of a stub. */
        private val inForce = object : OptionsInForce {
            override fun defaults(creation: StubCreation, local: Boolean): ActivitySettings {
                val now = entry.options.of(local).default
                // A stub that is made where it is called is made now.
                if (madeInPlace(creation)) return now
                // One that was kept since has what was in force where it was made: at the call that is nearest to where it is kept.
                return creation.via.passed.firstNotNullOfOrNull { stubDefaults[it.code] }
                    // Where nothing says when the stub was made, it may have been before or after anything set since the entry point began.
                    ?: entry.began.of(local).default.orElse(now)
            }

            override fun forType(type: String, local: Boolean): ActivitySettings = entry.options.of(local).forType(type)
        }

        /** What the call or reference being recognised is made on, which is only read once it is known what the call is. */
        private var calledOn: Code? = null

        /**
         * Whether the stub of [creation] is made by the expression the call being recognised
         * is made on: that expression holds the call that makes the stub, or leads to it
         * through nothing that kept the stub before, as a function does that makes the stub
         * and hands it back.
         */
        private fun madeInPlace(creation: StubCreation): Boolean {
            val on = calledOn ?: return false
            if (on.walk().any { it == creation.call }) return true
            return creation.via.passed.first().code === on && !creation.via.kept
        }

        /**
         * The kind of stub of an activity interface that [call] gives, where the stub is made
         * as part of the call: by the call itself, or by a function it runs that hands the
         * stub back.
         */
        private fun stubMadeBy(call: Call): StubKind? {
            StubKind.of(call)?.let { kind -> return kind.takeIf { it.isTypedActivity } }
            // Only a call that gives an object of an activity interface is worth following.
            if (call.type?.annotations?.find(TemporalTypes.ACTIVITY_INTERFACE) == null) return null
            val made = tracer.trail(call, scope, settled = false)
            if (made.kept) return null
            return (made.passed.last().code as? Call)?.let(StubKind::of)?.takeIf { it.isTypedActivity }
        }

        /** Reads code that may not run, or not before what follows it: what it sets for activities is not certain to hold afterwards. */
        private fun <T> perhaps(block: () -> T): T {
            entry.unsure++
            try {
                return block()
            } finally {
                entry.unsure--
            }
        }

        /** The body of a function: what its statements evaluate to is dropped, a value is given back with `return`. */
        fun read(body: Block): Flow = collect { drop(body) }

        /** Reads code that was handed over as a handler: a lambda, or a reference to a function. */
        fun handler(code: Code, label: String?): Flow = collect { later(code, label) }

        /**
         * Reads what a constructor runs, in the order it runs it.
         *
         * @param delegated gives what the constructor does that this one hands over to, with the arguments it is handed
         */
        fun construct(made: Construction, delegated: (FunctionId, List<Argument>) -> Flow): Flow =
            collect {
                made.delegate?.let { call ->
                    call.arguments.forEach { visit(it.value) }
                    call.callee?.function?.let { add(delegated(it, call.arguments)) }
                }
                for (initializer in made.initializers) {
                    // A lambda that a property is given is kept, not run.
                    val kept = initializer is PropertyWrite && initializer.value?.let { isCode(wrapped(it)) } == true
                    if (!kept) drop(initializer)
                }
                made.body?.let(::drop)
            }

        // ---- collecting

        private fun add(flow: Flow) {
            // Once something has happened, the outcomes of the step before it are no longer the only way here.
            if (flow.hasContent) gate = null
            parts += flow
        }

        /** Reads what [block] visits as a flow of its own. */
        private fun collect(block: () -> Unit): Flow {
            val outer = parts
            parts = ArrayList()
            try {
                block()
                return Series.of(parts)
            } finally {
                parts = outer
            }
        }

        /** Like [collect], for code that starts afresh: no step before it is asked about inside. */
        private fun collectApart(block: () -> Unit): Flow {
            val outer = gate
            gate = null
            try {
                return collect(block)
            } finally {
                gate = outer?.takeUnless { it.spent }
            }
        }

        /** Keeps the compensations registered in constructs that are dropped for holding nothing else. */
        private fun keepRegistrations(vararg dropped: Flow) {
            dropped.flatMap { it.registrations() }.forEach(::add)
        }

        // ---- the walk

        /** Visits code whose value is dropped: a statement, or a block of them that stands for no value. */
        private fun drop(code: Code) {
            statement = code
            visit(code)
        }

        /** Visits code whose value is kept where this reading can follow it. */
        private fun hold(code: Code) {
            held = code
            visit(code)
        }

        private fun visit(code: Code) {
            val dropped = statement === code
            val kept = held === code
            statement = null
            held = null
            when (code) {
                // A block stands for what its last statement evaluates to; the others are only run.
                is Block -> code.statements.forEachIndexed { index, each -> if (dropped || index < code.statements.lastIndex) drop(each) else visit(each) }
                is Call -> call(code, kept, dropped)
                is CallableReference -> {
                    code.receiver?.let(::visit)
                    calledOn = code.receiver
                    val node = recognizer.reference(code, site())
                    calledOn = null
                    if (node != null) add(Step(node, code.location).also { started(it, code.receiver) }) else refer(code)
                }
                // A lambda kept as a value: nothing says when it runs.
                is Lambda -> uncharted(notModelled("lambda"), code.location, listOf(code), lambdaLabel = null)
                is PropertyRead -> {
                    code.receiver?.let(::visit)
                    (stateOf(code) as? StateRef.Property)?.let { entry.state.reads += it.name }
                    getter(code)
                }
                is PropertyWrite -> {
                    code.receiver?.let(::visitNamed)
                    code.value?.let(::visit)
                    // A scope that a property is given can be cancelled by whatever reads the property.
                    val scope = code.value?.let(::scopeMade)
                    if (scope != null && code.property != null) scopesHeld.getOrPut(code.property) { LinkedHashSet() } += scope
                    val written = holder(code.receiver, code.onThis, code.property)?.member(code.name)
                    if (written != null) {
                        wrote(written.name)
                        // A compound assignment reads what it writes.
                        if (code.operator != ASSIGNMENT) entry.state.reads += written.name
                    }
                    if (gate?.reads?.contains(written?.name ?: code.name.takeIf { code.onThis }) == true) spendGate()
                }
                is LocalWrite -> {
                    code.value?.let(::visit)
                    if (!bindResult(code.name, code.value) && code.name == gate?.variable) spendGate()
                }
                is LocalVariable -> {
                    code.initializer?.let(::hold)
                    scope.declare(code.name, code.initializer, code.isMutable, stateOf(code.initializer) ?: localState(code))
                    if (!bindResult(code.name, code.initializer) && code.name == gate?.variable) spendGate()
                    nameSideEffect(code.name, code.initializer)
                }
                is Destructuring -> {
                    code.initializer?.let(::visit)
                    code.names.forEach { scope.bind(it, null) }
                }
                is If -> branch(code, dropped)
                is When -> branch(code, dropped)
                is Loop -> loop(code)
                is Try -> protect(code, dropped)
                is Return -> {
                    code.value?.let(::visit)
                    add(Leave(returnedFrom(code.label)))
                }
                is Throw -> {
                    code.value?.let(::visit)
                    val thrower = recognizer.thrower(code, site())
                    // What is thrown again as a clause caught it fails the workflow only if something that does may get to the clause.
                    val reached = thrower.variable?.let { name -> caught.lastOrNull { it.first == name }?.second }
                    val real = failures.failsExecution(thrower.type, thrower.exact) && reached != false
                    add(Raise(code.type?.name, recognizer.failureName(code.value, code.type, site()), code.location, real))
                }
                is Jump -> jumpedFrom(code.label)?.let { add(LoopJump(it.id, leavesLoop = code.kind == JumpKind.BREAK)) }
                is Other -> operation(code, kept)
                is ClassLiteral, is ClassReference, is Literal, is LocalRead, is ThisReference -> Unit
            }
            if (!dropped && !kept) passOn(code)
        }

        // ---- the promises of what was started beside the flow

        /**
         * What was started beside the flow and gave the promise that [code] evaluates to, as far
         * as that can be followed: through local variables, casts, and the functions that give
         * back what they are called on.
         */
        private fun startedBy(code: Code?): Started? {
            if (entry.started.isEmpty()) return null
            var current = Traced(code ?: return null, scope)
            repeat(MAX_ALIASES) {
                val here = unwrapped(current.code) ?: return null
                entry.started[here]?.let { return it }
                current = when {
                    here is LocalRead -> current.scope.find(here.name, settled = false) ?: return null
                    here is Call && here.callee?.function?.name in GIVE_BACK_RECEIVER -> Traced(here.receiver ?: return null, current.scope)
                    else -> return null
                }
            }
            return null
        }

        /**
         * Notes that what [code] evaluates to goes somewhere this reading does not follow. A
         * promise among it may be waited for there, so what it stands for is not taken to be
         * left alone.
         */
        private fun passOn(code: Code) {
            if (code is Call || code is LocalRead || code is Other) startedBy(code)?.escaped = true
        }

        /**
         * Notes that [fork] was started by [call], which gives the promise for it.
         *
         * @param lane what the code of the lane was read as, see [Lanes]
         */
        private fun track(call: Call, fork: Fork, kept: Boolean, lane: Any) {
            val started = Started(fork, lane, repeated = entry.rounds > 0)
            started.kept = kept
            entry.started[call] = started
            entry.begun += started
        }

        /**
         * What tells the collection apart that [code] evaluates to: the expression that made
         * it, through the local variables it was put in, or the property that holds it.
         */
        private fun collection(code: Code): Any? {
            var current = Traced(code, scope)
            repeat(MAX_ALIASES) {
                val here = unwrapped(current.code) ?: return null
                current = when (here) {
                    is PropertyRead -> return here.property
                    is LocalRead -> current.scope.find(here.name, settled = false) ?: return null
                    else -> return here
                }
            }
            return null
        }

        /** Notes that the promises among [added] are put into the collection [into]. */
        private fun collect(added: List<Code>, into: Code) {
            if (entry.started.isEmpty()) return
            val key = collection(into) ?: return
            added.forEach { startedBy(it)?.collected?.add(key) }
        }

        /**
         * What was started beside the flow and gave the promises that [code] evaluates to: the
         * promise itself, or a collection that promises were put into.
         */
        private fun promisesOf(code: Code): List<Started> {
            startedBy(code)?.let { return listOf(it) }
            val key = collection(code) ?: return emptyList()
            // A property is the same wherever it is read; an expression is the one that was met.
            return entry.begun.filter { lane -> lane.collected.any { if (key is PropertyId) it == key else it === key } }
        }

        /**
         * `Promise.anyOf`: the flow waits for the first of the promises it is handed, and no
         * longer. Where each of them stands for a lane, and none of those is over already, the
         * lanes run a race. One lane alone is no race, unless it is started round by round.
         */
        private fun race(operands: List<List<Started>>) {
            val lanes = operands.flatten().distinct()
            if (operands.isEmpty() || operands.any { it.isEmpty() } || lanes.any { it.waited }) return
            if (lanes.size == 1 && !lanes.single().repeated) return
            val race = Race()
            for (lane in lanes) {
                lane.fork.race = race
                race.lanes += lane.fork
            }
        }

        /**
         * A queue, a promise the workflow completes itself, a lock or a semaphore that the
         * entry point keeps in a variable of its own is state like one kept in a property: a
         * handler it registers as it runs may feed or free it, and so may another lane.
         */
        private fun localState(variable: LocalVariable): StateRef? {
            val maker = (variable.initializer?.let(::unwrapped) as? Call)?.callee?.function?.name
            return StateRef.Property(variable.name).takeIf { depth == 0 && maker in TemporalApi.STATE_MAKERS && variable.name !in entry.properties }
        }

        /** Notes that the code being read changes the property [name], in the lane it runs in. */
        private fun wrote(name: String, queue: QueueWait? = null) {
            for (use in listOf(entry.state, entry.lanes.use())) {
                use.writes += name
                when (queue) {
                    QueueWait.ITEMS -> use.drains += name
                    QueueWait.ROOM -> use.fills += name
                    null -> Unit
                }
            }
        }

        /** The scope that [code] evaluates to, if it is one whose making this reading has met or can find. */
        private fun scopeMade(code: Code): ScopeKey? {
            var current = Traced(code, scope)
            repeat(MAX_ALIASES) {
                val here = unwrapped(current.code) ?: return null
                scopeKeys[here]?.let { return it }
                current = when {
                    here is LocalRead -> current.scope.find(here.name, settled = false) ?: return null
                    here is Call && here.callee?.function?.name in GIVE_BACK_RECEIVER -> Traced(here.receiver ?: return null, current.scope)
                    else -> return null
                }
            }
            return null
        }

        /** The property that [code] reads, itself or through the local variables it was put in. */
        private fun held(code: Code): PropertyRead? {
            var current = Traced(code, scope)
            repeat(MAX_ALIASES) {
                val here = unwrapped(current.code) ?: return null
                if (here is PropertyRead) return here
                current = (here as? LocalRead)?.let { current.scope.find(it.name, settled = false) } ?: return null
            }
            return null
        }

        /**
         * What the call [call], which cancels a cancellation scope, cancels: the scope, or the
         * property that holds it; `null` if it is the scope of the workflow itself, or if
         * nothing says which scope it is, of which a warning is given.
         */
        private fun cancelled(call: Call): Any? {
            val receiver = call.receiver?.let(::unwrapped)
            if (receiver == null || recognizer.isCurrentScope(receiver)) return entry.scopes.lastOrNull()
            scopeMade(receiver)?.let { return it }
            // A property that is given its scope where it is declared.
            val origin = tracer.origin(receiver, scope, settled = false).code
            scopeKeys[origin]?.let { return it }
            (origin as? Call)?.takeIf(recognizer::makesScope)?.let { return scopeKeys.getOrPut(it) { ScopeKey() } }
            // A property that is given its scope as the workflow runs: which one is known once all of it is read.
            (held(receiver) ?: origin as? PropertyRead)?.property?.let { return it }
            warn("${call.location.at()}: which cancellation scope this cancels cannot be told; the place is drawn without what it cancels")
            return null
        }

        /**
         * @param kept whether what the call gives back is put in a local variable
         * @param dropped whether what the call gives back is dropped: the call is a statement
         */
        private fun call(call: Call, kept: Boolean = false, dropped: Boolean = false) {
            if (ran(call)) return
            calledOn = call.receiver
            val recognised = recognizer.call(call, site())
            calledOn = null
            // A stub of an activity interface is given the default options as they stand where it is made.
            stubMadeBy(call)?.let { stubDefaults[call] = entry.options.of(it.isLocalActivity).default }
            recognised.options?.let { entry.options = it.on(entry.options, certain = entry.unsure == 0) }
            // Lambdas and references are code handed over, not values worked out before the call.
            val (handedOver, evaluated) = call.arguments.map { wrapped(it.value) }.partition(::isCode)
            if (recognised.deferred == Deferred.CHAINED) return chain(call, recognised.chain!!, handedOver, evaluated)
            val function = call.callee?.function
            // A promise that a function of promises is called on, or that is handed to one that waits for it, stays in sight.
            val ofPromise = function?.owner in TemporalApi.PROMISES
            val waits = recognised.effect == Effect.JOIN
            // A promise that is waited for where it is made stands for a step the flow waits for there.
            if (ofPromise && waits) (call.receiver?.let(::unwrapped) as? Call)?.let { entry.direct += it }
            // What the object the call is made on has to do with the state is said below, once it is known what the call does.
            call.receiver?.let { visitNamed(it, kept = (ofPromise && waits) || function?.name in FOLLOW_RECEIVER) }
            if (waits) startedBy(call.receiver)?.waited = true
            // The object given to `with` is there to be worked on, like the one a scope function is called on.
            val worked = evaluated.firstOrNull().takeIf { function?.name == WITH }
            val joined = ArrayList<List<Started>>()
            evaluated.forEach {
                when {
                    it === worked -> visitNamed(it)
                    waits && call.receiver == null -> {
                        hold(it)
                        joined += promisesOf(it)
                    }
                    else -> visit(it)
                }
            }
            if (function?.name == TemporalApi.ANY_OF) race(joined)
            joined.flatten().forEach { it.waited = true }
            // A promise that is added to a collection can be found there by what waits for the collection.
            if (call.receiver != null && function?.simpleName in COLLECTORS && call.callee?.inSource == false) collect(evaluated, call.receiver)
            // The part of the workflow's state the call is made on, named or as the `this` of the code around it.
            val subject = when {
                call.receiver != null -> stateOf(call.receiver)
                call.receiverType != null -> subjectOf(null, call.receiverType).state
                else -> null
            } as? StateRef.Property
            var followed = false

            val node = recognised.node?.let {
                when {
                    it.kind != NodeKind.AWAIT -> it
                    // What a queue or a promise is waited on for is the property that holds it.
                    recognised.onReceiver -> it.copy(reads = listOfNotNull(subject?.name))
                    else -> awaited(it, handedOver, call.name)
                }
            }
            // The step of the call: in the flow, or beside it where the call only gives a promise for it.
            fun place(node: IrNode, cancels: Any? = null) {
                if (!recognised.promised || call in entry.direct || call in entry.inPlace) {
                    return step(call, node, recognised.outcomes, cancels, recognised.queue, recognised.timesOut)
                }
                val step = Step(node, call.location)
                val lane = Any()
                if (node.kind == NodeKind.AWAIT) entry.lanes.waits(step, lane)
                started(step, call.receiver, lane)
                add(Fork(call.location, step).also { track(call, it, kept, lane) })
            }
            when (recognised.deferred) {
                Deferred.SKIP, Deferred.CHAINED -> Unit
                // What a scope function gives back is what its lambda does: where the one is dropped, so is the other.
                Deferred.INLINE -> if (recognised.scoped) scoped(call, handedOver) else inPlace(call, handedOver, dropped && function?.name in GIVE_BACK_RESULT)
                Deferred.DETACHED -> {
                    detached++
                    try {
                        if (recognised.scoped) scoped(call, handedOver) else handedOver.forEach { later(it, call.name) }
                    } finally {
                        detached--
                    }
                }
                Deferred.EACH -> {
                    val floor = entry.frames + 1
                    val before = entry.begun.size
                    val body = round { perhaps { collectApart { handedOver.forEach { later(it, call.name) } } } }
                    // The promises of what each round starts are what the call gives back, where it gives back a collection.
                    entry.begun.subList(before, entry.begun.size).forEach { it.collected += call }
                    if (body.holdsSteps(floor)) {
                        add(Repeat(call.location, iterationLabel(call, handedOver), body, frame = ++entry.frames, floor = floor))
                    } else {
                        keepRegistrations(body)
                    }
                }
                Deferred.RETRY -> {
                    val floor = entry.frames + 1
                    val body = collectApart { handedOver.forEach { later(it, call.name) } }
                    if (body.holdsSteps(floor)) {
                        add(Repeat(call.location, recognised.label, body, frame = ++entry.frames, retry = true, floor = floor))
                    } else {
                        keepRegistrations(body)
                    }
                }
                Deferred.ASIDE -> {
                    val floor = entry.frames + 1
                    val lane = if (call in entry.inPlace) entry.lanes.current else Any()
                    val handed = entry.lanes.within(lane) {
                        perhaps { collectApart { handedOver.forEach { if (recognised.retried) composed(it, call.name) else later(it, call.name) } } }
                    }
                    // What is started again whenever it fails is a loop that is left when it succeeds.
                    val body = when {
                        !recognised.retried -> handed
                        handed.holdsSteps(floor) -> Repeat(call.location, recognised.label, handed, frame = ++entry.frames, retry = true, floor = floor)
                        else -> Series.of(handed.registrations())
                    }
                    when {
                        !body.hasContent -> keepRegistrations(body)
                        // What is handed to something that waits for it runs as part of that.
                        call in entry.inPlace -> add(body)
                        else -> add(Fork(call.location, body).also { track(call, it, kept, lane) })
                    }
                }
                Deferred.COMPENSATION -> {
                    val body = perhaps { collectApart { handedOver.forEach { later(it, call.name) } } }
                    if (body.hasContent) add(Register(recognised.saga.orEmpty(), body, entry.path.toList()))
                }
                Deferred.OPTIONAL -> {
                    val target = recognised.target
                    val use = Use(call.location, call.name, call.receiver, call.receiverType, call.arguments, never = call.type?.name == NOTHING)
                    when {
                        node != null -> {
                            place(node)
                            if (!node.kind.isTerminal) uncharted(null, call.location, handedOver, call.name)
                        }
                        target != null && follow(use, target, handedOver).also { followed = it == Followed.READ } != Followed.NOT -> Unit
                        recognised.candidates.isNotEmpty() && oneOf(use, recognised.candidates, handedOver) -> Unit
                        else -> uncharted(notModelled(recognizer.label(call)), call.location, handedOver, call.name)
                    }
                }
            }
            // What is handed over and not read here may wait for a promise unseen.
            val unread = recognised.deferred == Deferred.SKIP && handedOver.isNotEmpty() && node?.kind != NodeKind.AWAIT
            if (unread || recognised.effect == Effect.LISTEN) entry.opaque = true
            if (subject != null) {
                when {
                    StateRules.mutates(call) -> wrote(subject.name, StateRules.queueUse(call))
                    // A function that was read says itself what it reads of the object, and a scope function's lambda does.
                    followed || recognised.deferred == Deferred.INLINE -> Unit
                    else -> entry.state.reads += subject.name
                }
            }
            if (node != null && recognised.deferred != Deferred.OPTIONAL) {
                place(node, cancels = if (recognised.effect == Effect.CANCEL) cancelled(call) else null)
            }
            when (recognised.effect) {
                Effect.NONE, Effect.CANCEL -> Unit
                Effect.JOIN -> add(Join)
                // The child is running by then; whether it is waited for any further is for its promise to say.
                Effect.STARTED -> entry.children.filter { it.stub === recognised.stub }.forEach {
                    it.step.startAwaited = true
                    if (it.lane !== entry.lanes.current) it.step.startAwaitedOutside = true
                }
                Effect.COMPENSATE -> {
                    if (recognised.parallel == null) {
                        warn("${call.location.at()}: whether this saga compensates side by side cannot be told; its compensations are drawn one after the other")
                    }
                    add(Compensate(recognised.saga.orEmpty(), call.location, detached > 0, recognised.parallel == true))
                }
                Effect.LISTEN -> listen(call)
            }
            // A call that never returns ends the flow, whatever it is; of one of the standard library it is known with what.
            if (call.type?.name == NOTHING) {
                val thrown = TemporalApi.thrownBy(call)
                add(Raise(thrown, name = null, location = call.location, real = failures.failsExecution(thrown, exact = true)))
            }
        }

        // ---- handlers registered on the way

        /**
         * `Workflow.registerListener`: what is handed over handles signals, queries or updates
         * from here on. A lambda or a reference dressed as one of the SDK's handlers for every
         * name is one handler; an object brings those of its class.
         */
        private fun listen(call: Call) {
            val argument = call.arguments.singleOrNull()?.value ?: return unheard(call)
            val code = wrapped(argument)
            if (isCode(code)) {
                val dressed = argument as? Call
                val kind = TemporalApi.DYNAMIC_HANDLERS[dressed?.type?.name] ?: return unheard(call)
                if (!entry.listening.add("$kind ${code.location.at()}")) return
                val heard = Entry(thorough = true, entry.listening, entry.options)
                heard.stack += entry.stack
                val reading = Reading(heard, name, depth, frame = null, self, around = scope)
                reading.implicitReceivers += implicitReceivers
                reading.untypedReceivers += untypedReceivers
                val handled = reading.handler(code, dressed?.name)
                heard.settle()
                entry.listeners += Listener(kind, name = null, code.location, handled, heard.state)
                entry.listeners += heard.listeners
                return
            }
            // The workflow object itself is registered before any of its code runs.
            val state = stateOf(argument)
            if (state == StateRef.Workflow) return
            val type = tracer.constructedClass(argument, scope) ?: typeOf(argument)?.name ?: return unheard(call)
            val handlers = recognizer.listeners(type)
            if (handlers.isEmpty()) return unheard(call)
            for (handler in handlers) {
                val function = handler.function
                val place = function?.location ?: call.location
                if (!entry.listening.add("${handler.kind} ${handler.name} ${place.at()}")) continue
                val heard = Entry(thorough = true, entry.listening, entry.options)
                val flow = function?.let { backend.body(it.id) }?.let { body ->
                    heard.stack += function.id
                    val reading = Reading(heard, function.name, depth = 0, frame = null, Self(Traced(argument, scope), state, type))
                    function.parameters.forEach { reading.scope.bind(it.name, null) }
                    reading.read(body)
                }
                heard.settle()
                entry.listeners += Listener(handler.kind, handler.name, place, flow, heard.state)
                entry.listeners += heard.listeners
            }
        }

        private fun unheard(call: Call) {
            warn("${call.location.at()}: what the object given to Workflow.registerListener handles cannot be told; its handlers are not drawn")
        }

        /** The type of the value of [code], where the code says it. */
        private fun typeOf(code: Code): TypeRef? =
            when (code) {
                is Call -> code.type
                is PropertyRead -> code.type
                is LocalRead -> code.type
                is ThisReference -> code.type
                is Other -> code.operands.singleOrNull()?.takeIf { code.operator in TRANSPARENT }?.let(::typeOf)
                else -> null
            }

        /**
         * The node of an await, with what it waits for: the properties its condition reads,
         * wherever the condition does so. An await whose condition is a lambda handed to the
         * function it is written in is labelled with that lambda.
         */
        private fun awaited(node: IrNode, handedOver: List<Code>, label: String?): IrNode {
            val reads = readBy(handedOver, label)
            val passedIn = handedOver.lastOrNull()?.let(::handedThrough)
            return node.copy(reads = reads, label = passedIn?.let { Outcomes.awaitLabel(it.code) } ?: node.label)
        }

        /** The code this function was handed, if [code] is that code or a lambda that does nothing but run it. */
        private fun handedThrough(code: Code): Handed? {
            if (code is LocalRead) return handed[code.name]
            val only = (code as? Lambda)?.body?.statements?.singleOrNull()
            val call = (if (only is Return) only.value else only) as? Call ?: return null
            if (call.arguments.isNotEmpty() || call.callee?.function?.let(StepProbe::isInvocation) != true) return null
            return (call.receiver as? LocalRead)?.let { handed[it.name] }
        }

        /** Whether [code] is code handed over to be run: a lambda, a reference, or one of those handed to this function. */
        private fun isCode(code: Code): Boolean =
            code is Lambda || code is CallableReference || (code is LocalRead && code.name in handed)

        /**
         * @param cancels for the place where a scope is cancelled: what says which scope, see [Step.cancels]
         * @param queue for a wait on a workflow queue: what it waits for there
         * @param timesOut whether the step raises a `TimeoutException` when its time is up
         */
        private fun step(call: Call, node: IrNode, outcomes: Outcomes?, cancels: Any? = null, queue: QueueWait? = null, timesOut: Boolean = false) {
            val step = Step(node, call.location, outcomes, cancels, queue, timesOut)
            add(step)
            lastStep = call to step
            if (outcomes != null) gate = Gate(outcomes, call, node.reads.toSet())
            if (node.kind == NodeKind.AWAIT) entry.lanes.waits(step, over = finished())
            started(step, call.receiver)
        }

        /**
         * The lanes that are over where the reading stands: those the flow itself has waited
         * for. That is only certain for code of the flow itself that runs once; in a loop, a
         * lane of the round before may still run.
         */
        private fun finished(): List<Any> =
            if (entry.lanes.own && entry.rounds == 0) entry.begun.filter { it.waited }.map { it.lane } else emptyList()

        /**
         * Notes a child workflow that was started through the stub [stub], for the place that waits for it to have started.
         *
         * @param lane the lane the step stands in, see [Lanes]
         */
        private fun started(step: Step, stub: Code?, lane: Any = entry.lanes.current) {
            if (step.node.kind == NodeKind.CHILD_WORKFLOW && stub != null) {
                entry.children += Child(tracer.origin(stub, scope, settled = false).code, step, lane)
            }
        }

        /**
         * A promise is handed what is to run once it is done. That runs after what the promise
         * stands for, in the same lane: if that succeeded, if it failed, or either way. A
         * promise of which nothing is known says nothing of when: what it is handed is then
         * left to follow a node that says so.
         */
        private fun chain(call: Call, kind: Chain, handedOver: List<Code>, evaluated: List<Code>) {
            val receiver = call.receiver
            // Where the promise that comes of this is waited for in place, so is the one it is made from.
            val inPlace = call in entry.inPlace
            if (inPlace) receiver?.let(::unwrapped)?.let { entry.inPlace += it }
            val before = collect { receiver?.let { visitNamed(it, kept = true) } }
            evaluated.forEach(::visit)
            val lane = startedBy(receiver)
            val handed = entry.lanes.within(lane?.lane ?: entry.lanes.current) {
                perhaps { collectApart { handedOver.forEach { if (kind == Chain.COMPOSE) composed(it, call.name) else later(it, call.name) } } }
            }
            if (!inPlace || lane != null) (if (before is Series) before.parts else listOf(before)).forEach(::add)
            when {
                lane != null -> {
                    // The promise that comes of this stands for the lane as a whole.
                    entry.started[call] = lane
                    if (handed.hasContent) lane.fork.body = chained(lane.fork.body, handed, kind) else keepRegistrations(handed)
                }
                inPlace -> if (before.hasContent || handed.hasContent) add(chained(before, handed, kind)) else keepRegistrations(before, handed)
                handed.hasContent -> add(Uncharted(notModelled(recognizer.label(call)), call.location, listOf(handed)))
                else -> keepRegistrations(handed)
            }
        }

        /** [body], and after it [handed], which was chained to the promise that stands for it. */
        private fun chained(body: Flow, handed: Flow, kind: Chain): Flow =
            when (kind) {
                Chain.THEN, Chain.COMPOSE -> Series(listOf(body, handed))
                // A failure is handed over like a result, and goes no further.
                Chain.HANDLE -> Series(listOf(Protected(body, listOf(Handler(RUNTIME_EXCEPTION, "RuntimeException", Series.EMPTY)), Series.EMPTY), handed))
                Chain.EXCEPTIONALLY -> Protected(body, listOf(Handler(THROWABLE, "Throwable", handed)), Series.EMPTY)
            }

        /**
         * Reads a lambda that gives a promise to something that waits for it: what that promise
         * stands for runs in place, as part of what waits for it.
         */
        private fun composed(code: Code, label: String?) {
            val last = (code as? Lambda)?.body?.statements?.lastOrNull()
            val result = unwrapped(if (last is Return) last.value else last)
            if (result is Call) entry.inPlace += result
            later(code, label)
        }

        /**
         * Reads what a cancellation scope is given, where the scope is made: it runs when the
         * scope does, which is as a rule at once. The scope is known by the call that makes it.
         */
        private fun scoped(call: Call, handedOver: List<Code>) {
            val key = scopeKeys.getOrPut(call) { ScopeKey() }
            entry.scopes += key
            val body = try {
                // The code is handed its scope, which it may cancel.
                collect { handedOver.forEach { later(it, call.name, Self(Traced(call, scope), null, null)) } }
            } finally {
                entry.scopes.removeAt(entry.scopes.lastIndex)
            }
            if (body.hasContent) add(Scoped(key, body)) else keepRegistrations(body)
        }

        /** @param kept whether what the operation evaluates to is kept where this reading follows it */
        private fun operation(code: Other, kept: Boolean = false) {
            val operands = code.operands
            // What an object literal or a local declaration holds is not looked into.
            if (code.operator in OPAQUE) entry.opaque = true
            // With these operators the right-hand side only runs sometimes.
            if (code.operator !in SHORT_CIRCUIT || operands.size != 2) {
                // `items[key] = value` changes `items`, and reads nothing of it.
                val changed = operands.firstOrNull().takeIf { code.operator == INDEXED_ASSIGNMENT }
                // A cast hands on what it is given: where that is kept, so is this.
                val handedOn = operands.singleOrNull().takeIf { kept && code.operator in TRANSPARENT }
                operands.forEach {
                    when {
                        it === changed -> visitNamed(it)
                        it === handedOn -> hold(it)
                        else -> visit(it)
                    }
                }
                (stateOf(changed) as? StateRef.Property)?.let { wrote(it.name) }
                return
            }
            visit(operands[0])
            // A step that gives nothing back when its time is up: `?:` then asks which way it went.
            val asked = gate.takeIf { code.operator == "?:" }
            val right = perhaps { collectApart { visit(operands[1]) } }
            if (!right.hasContent) return keepRegistrations(right)
            val tested = Texts.of(operands[0])
            val missing = asked?.let { gate -> gate.live.associateWith { Conditions.isNothing(operands[0], gate, it) } }
            if (asked != null && missing != null && missing.values.none { it == null }) {
                val arms = listOf(
                    Arm(YES, right, missing.filterValues { it == true }.keys),
                    Arm(NO, Series.EMPTY, missing.filterValues { it == false }.keys),
                )
                return add(Choice(code.location, Captions.of("$tested == null"), arms, asked.outcomes.takeIf { covers(asked, arms) }))
            }
            val runs = Arm(YES, right)
            val skips = Arm(NO, Series.EMPTY)
            add(
                when (code.operator) {
                    "?:" -> Choice(code.location, Captions.of("$tested == null"), listOf(runs, skips))
                    "&&" -> Choice(code.location, Captions.of(tested), listOf(runs, skips))
                    else -> Choice(code.location, Captions.of(tested), listOf(Arm(YES, Series.EMPTY), Arm(NO, right)))
                },
            )
        }

        // ---- branches

        /** @param dropped whether what the `if` evaluates to is dropped, and with it what its arms end with */
        private fun branch(code: If, dropped: Boolean) {
            visit(code.condition)
            val asked = gate
            val verdicts = asked?.let { gate -> gate.live.associateWith { Conditions.holds(code.condition, gate, it) } }
            val taken = verdicts.orEmpty().filterValues { it == true }.keys
            val rest = asked?.live.orEmpty() - taken
            // The arm that is tested for is called what the test says, where no outcome says the same.
            val named = asked?.takeIf { verdicts != null && verdicts.values.none { it == null } }?.let { gate ->
                val yes = captions(gate, taken, Captions.of(Texts.condition(code.condition))) ?: return@let null
                val no = captions(gate, rest, own = null) ?: return@let null
                listOf(yes, no)
            }
            val decided = asked.takeIf { named != null }
            val arms = listOf(
                arm(YES, code.then, decided, taken, named?.get(0).orEmpty(), dropped),
                arm(NO, code.otherwise, decided, rest, named?.get(1).orEmpty(), dropped),
            )
            choice(code.location, question(code.condition, code.conditionText), arms, decided.takeIf { covers(it, arms) })
        }

        /**
         * What the edges into an arm are called that the cases [states] of the step [gate] lead
         * into; `null` if the outcomes of the step cannot say what leads there.
         *
         * An edge is called after an outcome: a reason the await went on. Where several reasons
         * hold at once, the code may still go one way only, so an edge named after a reason
         * promises something: wherever that reason holds, this is the way. An arm keeps the
         * names of its reasons if that is true of them. Otherwise an arm that the code tests for
         * is called what the test says, [own]. An arm that is merely what is left over keeps the
         * names of its reasons as long as one of them holds in each of its cases: it reads as
         * "otherwise", after the arms that were tested for. An arm that is only taken when
         * several reasons hold together has no outcome to be named after.
         */
        private fun captions(gate: Gate, states: Set<Scenario>, own: String?): Map<Scenario, String?>? {
            val several = states.filter { it.parts.isNotEmpty() }
            val covered = several.all { case -> case.parts.any { it in states } }
            val promisesMore = gate.live.any { case -> case !in states && case.parts.any { it in states } }
            return when {
                covered && (!promisesMore || own == null) -> several.associateWith { case -> case.parts.first { it in states }.caption }
                own != null && several.size < states.size -> states.filter { it.kind == EdgeKind.FLOW }.associateWith { own }
                else -> null
            }
        }

        private fun branch(code: When, dropped: Boolean) {
            code.subject?.let(::visit)
            val asked = gate
            val indices = asked?.let { gate -> gate.live.associateWith { armTaken(code, gate, it) } }
            fun taking(index: Int): Set<Scenario> = indices.orEmpty().filterValues { it == index }.keys
            // Without an `else`, a `when` that need not cover every case can be passed by.
            val passable = code.branches.none { it.isElse } && !isExhaustive(code)
            val named = asked?.takeIf { indices != null && indices.values.none { it == null } }?.let { gate ->
                val tested = code.branches.mapIndexed { index, branch ->
                    val own = when {
                        branch.isElse -> null
                        code.subject != null -> Captions.of("${code.subjectText} == ${branch.conditionText}")
                        else -> Captions.of(branch.conditions.joinToString(" or ") { Texts.condition(it) })
                    }
                    captions(gate, taking(index), own) ?: return@let null
                }
                if (passable) tested + (captions(gate, taking(code.branches.size), own = null) ?: return@let null) else tested
            }
            val decided = asked.takeIf { named != null }
            fun scenarios(index: Int): Set<Scenario> = if (decided == null) emptySet() else taking(index)

            val arms = code.branches.mapIndexed { index, branch ->
                val body = Block(branch.conditions + listOfNotNull(branch.body), branch.location)
                arm(Captions.of(branch.conditionText), body, decided, scenarios(index), named?.get(index).orEmpty(), dropped)
            }.toMutableList()
            if (passable) arms += arm(ELSE, null, decided, scenarios(code.branches.size), named?.last().orEmpty())
            choice(code.location, code.subjectText?.let(Captions::of), arms, decided.takeIf { covers(it, arms) })
        }

        /** The index of the branch of [code] that is taken in [scenario], or the number of branches if none is; `null` if that cannot be told. */
        private fun armTaken(code: When, gate: Gate, scenario: Scenario): Int? {
            for ((index, branch) in code.branches.withIndex()) {
                if (branch.isElse) return index
                // `in` and `is` conditions are not evaluated.
                if (branch.conditions.isEmpty() || TESTS.any { branch.conditionText.trimStart().startsWith(it) }) return null
                val verdicts = branch.conditions.map { condition ->
                    if (code.subject == null) Conditions.holds(condition, gate, scenario) else Conditions.same(code.subject, condition, gate, scenario)
                }
                if (verdicts.any { it == true }) return index
                if (verdicts.any { it == null }) return null
            }
            return code.branches.size
        }

        /** Whether a `when` without `else` covers every value of its subject: an enum or a boolean. */
        private fun isExhaustive(code: When): Boolean {
            val type = when (val subject = code.subject) {
                is LocalRead -> subject.type
                is PropertyRead -> subject.type
                is Call -> subject.type
                is LocalVariable -> subject.type
                else -> null
            }?.name ?: return false
            return type == BOOLEAN || backend.findClass(type)?.kind == ClassKind.ENUM
        }

        /**
         * Whether the outcomes of the step [decided] account for every arm that holds something.
         * An arm that no outcome leads into is dead code if the outcomes are certain. If they are
         * not, the arm may be reached all the same, and the choice has to stay a branch to show it.
         */
        private fun covers(decided: Gate?, arms: List<Arm>): Boolean =
            decided != null && (decided.outcomes.certain || arms.none { it.body.hasContent && it.scenarios.isEmpty() })

        /** Reads one arm of a choice. Where a step decides the choice, the arm may ask about the outcomes that lead into it. */
        private fun arm(
            label: String,
            body: Code?,
            decided: Gate?,
            scenarios: Set<Scenario>,
            captions: Map<Scenario, String?> = emptyMap(),
            dropped: Boolean = false,
        ): Arm {
            val outer = gate
            gate = decided?.narrowedTo(scenarios)
            try {
                return Arm(label, perhaps { collect { body?.let { if (dropped) drop(it) else visit(it) } } }, if (decided == null) emptySet() else scenarios, captions)
            } finally {
                gate = outer
            }
        }

        private fun choice(location: SourceLocation, label: String?, arms: List<Arm>, decided: Gate?) {
            if (arms.none { it.body.hasContent }) {
                keepRegistrations(*arms.map { it.body }.toTypedArray())
                if (gate?.spent == true) gate = null
                return
            }
            add(Choice(location, label, arms, decided?.outcomes))
            // The outcomes that pass the choice by can still be asked for, if nothing else comes out of it.
            val passing = arms.filter { !it.body.hasContent }.flatMap { it.scenarios }.toSet()
            val merges = arms.any { it.body.hasContent && it.body.completes }
            gate = decided?.takeIf { !it.spent && !merges && passing.isNotEmpty() }?.narrowedTo(passing)
        }

        /** What a branch asks, as its node says it. */
        private fun question(condition: Code, text: String): String {
            val asked = when (condition) {
                is PropertyRead -> condition.property?.name
                is Call -> condition.callee?.function?.name
                else -> null
            }
            return if (asked == CONTINUE_AS_NEW_SUGGESTED) CONTINUE_AS_NEW_QUESTION else Captions.of(text)
        }

        // ---- loops

        private fun loop(loop: Loop) {
            val frame = LoopFrame(++entry.frames, loop.label)
            val body = when (loop.kind) {
                LoopKind.FOR -> {
                    // What is iterated over is worked out once, before the loop.
                    loop.header?.let(::visit)
                    looping(frame) { loop.body?.let(::drop) }
                }
                LoopKind.WHILE -> looping(frame) {
                    loop.header?.let(::visit)
                    loop.body?.let(::drop)
                }
                LoopKind.DO_WHILE -> looping(frame) {
                    loop.body?.let(::drop)
                    loop.header?.let(::visit)
                }
            }
            if (!body.holdsSteps(frame.id)) return keepRegistrations(body)
            val label = when (loop.kind) {
                LoopKind.FOR -> "for each ${loopVariable(loop.headerText)}"
                LoopKind.WHILE -> "while ${loop.headerText}"
                LoopKind.DO_WHILE -> "do while ${loop.headerText}"
            }
            val endless = loop.kind != LoopKind.FOR && (loop.header as? Literal)?.constant == Constant.Bool(true)
            add(Repeat(loop.location, Captions.of(label), body, frame.id, endless))
        }

        /** The variable of a `for` as its header names it, without a type: `item`, or `(key, value)`. */
        private fun loopVariable(header: String): String {
            val declared = header.substringBefore(" in ").trim()
            return if (declared.startsWith("(")) declared else declared.substringBefore(':').trim()
        }

        /**
         * The loop a `break` or `continue` belongs to: the nearest one, or the nearest one with
         * [label] if there is such a loop. A jump does not leave the lambda it is written in.
         */
        private fun jumpedFrom(label: String?): LoopFrame? {
            val reachable = loops.asReversed().takeWhile { it != null }.filterNotNull()
            return reachable.firstOrNull { label != null && it.label == label } ?: reachable.firstOrNull()
        }

        private fun looping(frame: LoopFrame, block: () -> Unit): Flow {
            loops += frame
            try {
                return round { perhaps { collectApart(block) } }
            } finally {
                loops.removeAt(loops.lastIndex)
            }
        }

        /** Reads what may run more than once: what it starts beside the flow, it starts in each round. */
        private fun <T> round(block: () -> T): T {
            entry.rounds++
            try {
                return block()
            } finally {
                entry.rounds--
            }
        }

        /** The label of a loop made by a function that is handed a lambda. */
        private fun iterationLabel(call: Call, handedOver: List<Code>): String {
            if (call.callee?.function?.name == TemporalApi.REPEAT) {
                return Captions.of("repeat ${call.arguments.firstOrNull()?.text.orEmpty()} times")
            }
            // The element is the last parameter: `forEachIndexed { index, item -> }`, `fold(0) { sum, item -> }`.
            val element = handedOver.filterIsInstance<Lambda>().firstOrNull()?.parameters?.lastOrNull()
                ?.takeIf { it != IMPLICIT_PARAMETER && IDENTIFIER.matches(it) }
            val iterated = call.receiver?.let(Texts::of).orEmpty()
            return Captions.of(
                when {
                    element != null -> "for each $element"
                    iterated.isNotEmpty() -> "for each of $iterated"
                    else -> "for each"
                },
            )
        }

        // ---- try

        /** @param dropped whether what the `try` evaluates to is dropped, and with it what its body and its clauses end with */
        private fun protect(code: Try, dropped: Boolean) {
            val body = collectApart { if (dropped) drop(code.body) else visit(code.body) }
            // Whether a failure that fails the workflow may get to a clause: one of a step, or one that is thrown.
            val reached = body.mayFailExecution() || failures.mayThrowUnseen(code.body)
            val handlers = code.catches.map { clause ->
                val outer = scope
                scope = outer.child().also { inner -> clause.parameter?.let { inner.bind(it, null) } }
                clause.parameter?.let { caught += it to reached }
                try {
                    Handler(clause.type?.name, clause.type.simpleName(), perhaps { collectApart { if (dropped) drop(clause.body) else visit(clause.body) } })
                } finally {
                    if (clause.parameter != null) caught.removeAt(caught.lastIndex)
                    scope = outer
                }
            }
            val finally = code.finally?.let { block -> collectApart { drop(block) } } ?: Series.EMPTY
            when {
                // A clause with nothing in it still says where a failure of the body goes: on.
                handlers.any { it.body.hasContent } || finally.hasContent || (body.hasContent && handlers.isNotEmpty()) ->
                    add(Protected(body, handlers, finally))
                body.hasContent -> add(body)
                else -> keepRegistrations(body, *handlers.map { it.body }.toTypedArray(), finally)
            }
        }

        private fun TypeRef?.simpleName(): String = this?.name?.substringAfterLast('.') ?: this?.text?.substringAfterLast('.') ?: "exception"

        // ---- what is not modelled

        private fun notModelled(label: String): IrNode =
            IrNode(id = "", kind = NodeKind.UNKNOWN, reason = UnknownReasons.UNSUPPORTED, label = Captions.of(label))

        /**
         * Reads code handed to something that decides whether and when it runs. If it holds
         * anything, [marker] stands for what is not modelled and the code follows it.
         */
        private fun uncharted(marker: IrNode?, location: SourceLocation, handedOver: List<Code>, lambdaLabel: String?) {
            val read = handedOver.map { code -> perhaps { collectApart { later(code, lambdaLabel) } } }
            if (read.any { it.hasContent }) add(Uncharted(marker, location, read)) else keepRegistrations(*read.toTypedArray())
        }

        // ---- lambdas

        /**
         * The lambda or reference inside [argument] if it is one dressed as an object of an
         * interface with a single method, as in `Runnable { … }` or, for an interface declared
         * inside a class, `Functions.Func1(stub::method)`; otherwise [argument] itself.
         */
        private fun wrapped(argument: Code): Code {
            val callee = (argument as? Call)?.callee ?: return argument
            val function = callee.function
            val only = argument.arguments.singleOrNull()?.value
            // What dresses it is called like the interface it gives, which no other function of a library is.
            val converts = !callee.inSource && function.name == argument.type?.name
            return if (converts && (only is Lambda || only is CallableReference)) only else argument
        }

        /**
         * Reads code that was handed over to be run: the body of a lambda, what a reference names,
         * or what this function was handed itself and hands on.
         *
         * @param label the name of the function a lambda was handed to, which is its label unless
         *   one is written in front of it
         * @param given the object the lambda is given as its first parameter, if that is known
         * @param dropped whether what the lambda evaluates to is dropped by what it is handed to
         */
        private fun later(code: Code, label: String?, given: Self? = null, dropped: Boolean = false) {
            (code as? LocalRead)?.let { handed[it.name] }?.let { return add(it.run()) }
            if (code !is Lambda) return visit(code)
            val outer = scope
            scope = outer.child().also { inner ->
                code.parameters.forEachIndexed { index, parameter ->
                    val first = given.takeIf { index == 0 }
                    inner.bind(parameter, first?.value, first?.state)
                }
            }
            val frame = LambdaFrame(++entry.frames, code.label ?: label, code.isAnonymousFunction)
            lambdas += frame
            // `break` and `continue` do not reach through a lambda.
            loops += null
            try {
                add(LambdaBody(frame.id, collect { if (code.yieldsValue && !dropped) visit(code.body) else drop(code.body) }))
            } finally {
                loops.removeAt(loops.lastIndex)
                lambdas.removeAt(lambdas.lastIndex)
                scope = outer
            }
        }

        /**
         * What a `return` leaves: a lambda, or the function being read, whose frame is `null` if
         * it is the entry point. Without a label that is the nearest anonymous function, or else
         * the function. With one it is the nearest lambda that goes by it: the one the label is
         * written in front of, or one handed to the function of that name. A label that names
         * neither, and is not the name of the function being read, is taken for the nearest lambda.
         */
        private fun returnedFrom(label: String?): Int? {
            val lambda = when {
                label == null -> lambdas.lastOrNull { it.isAnonymousFunction }
                else -> lambdas.lastOrNull { it.label == label } ?: lambdas.lastOrNull().takeIf { label != name }
            }
            return if (lambda != null) lambda.id else frame
        }

        /**
         * Reads code of this function while a function it called is being read: a lambda that was
         * handed to that function, at the place where the function runs it. The lambda is code of
         * this function, so the calls in it are nested as deep as this function is.
         */
        fun resume(block: () -> Unit): Flow {
            val suspended = ArrayList(entry.stack.subList(stackSize, entry.stack.size))
            while (entry.stack.size > stackSize) entry.stack.removeAt(entry.stack.lastIndex)
            try {
                return collectApart(block)
            } finally {
                entry.stack += suspended
            }
        }

        /** Reads what a lambda or reference handed over by this function holds, where it is run. */
        fun run(handed: Handed): Flow = resume { later(handed.code, handed.label) }

        /** A call of a lambda or reference this function was handed: what it holds runs here. */
        private fun ran(call: Call): Boolean {
            val code = (call.receiver as? LocalRead)?.let { handed[it.name] } ?: return false
            if (call.callee?.function?.let(StepProbe::isInvocation) != true) return false
            call.arguments.forEach { visit(it.value) }
            add(code.run())
            return true
        }

        // ---- functions of the sources

        /**
         * Follows a call of [callee], a function of the sources. Where a step of the workflow
         * is certain to lie in the function, what the function does is read as part of this
         * flow, and where such a call is not followed, a node says so. Where no step is certain,
         * the function is not drawn from the inside: the place of the call says what may matter
         * of it, see [note], and the function is only read for what it reads and writes of the
         * workflow's state. The answer is [Followed.NOT] if there is nothing to follow and
         * nothing to say about it, so that the call is treated like that of any function that is
         * no step.
         */
        private fun follow(use: Use, callee: Callee, handedOver: List<Code>): Followed {
            val declared = backend.function(callee.function) ?: return Followed.NOT
            // An extension function that is a member of a class runs on two objects: the one it
            // extends, which is the one named at the call, and an object of its class, which is not.
            val isMemberExtension = declared.receiver != null && callee.function.owner != null
            val subject = if (isMemberExtension) subjectOf(null, use.receiverType) else subjectOf(use.receiver, use.receiverType)
            val extended = when {
                declared.receiver == null -> null
                isMemberExtension -> subjectOf(use.receiver, null)
                else -> subject
            }
            val target = when {
                StepProbe.isSuper(use.receiver) -> dispatch.static(callee.function)
                else -> dispatch.resolve(callee.function, subject.type ?: use.receiverType?.name, exact = subject.type != null)
            }
            when (target) {
                null -> return Followed.NOT
                // Nothing is known of what runs: a step may lie behind the call, and none is certain to.
                is Target.Unknown -> cutOff(UnknownReasons.DYNAMIC_DISPATCH, null, emptyList(), use, handedOver, firm = false)
                is Target.Several -> {
                    // Whichever function runs, what it reads and writes is read and written here.
                    if (entry.thorough) {
                        target.functions.filter { cutOffReason(it.id) == null }.forEach { apart { enter(use, it, subject, extended, Summary.NOTHING, handedOver) } }
                    }
                    val held = probe.summary(target.functions)
                    if (!holdsStep(held, handedOver)) return undrawn(use, recognizer.label(callee.function), held, handedOver)
                    cutOff(UnknownReasons.DYNAMIC_DISPATCH, null, target.classes, use, handedOver)
                }
                is Target.One -> {
                    val function = target.function
                    val reason = cutOffReason(function.id)
                    val held = probe.summary(function)
                    // What is handed nothing of the workflow's state can neither read nor write any of it.
                    val seesState = subject.state != null || extended?.state != null || use.arguments.any { stateOf(it.value) != null }
                    val touchesState = entry.thorough && seesState
                    when {
                        // A function that holds nothing need not be read to know what it leaves.
                        !touchesState && held.isInert -> return if (handedOver.isEmpty()) Followed.SKIPPED else Followed.NOT
                        reason != null -> {
                            if (!holdsStep(held, handedOver)) return undrawn(use, recognizer.label(function.id), held, handedOver)
                            cutOff(reason, recognizer.label(function.id), emptyList(), use, handedOver)
                        }
                        // Neither is one that holds no step and leaves nothing behind where it is called.
                        !held.certain && !held.writes && !touchesState && handedOver.isEmpty() -> return undrawn(use, recognizer.label(function.id), held, handedOver)
                        else -> {
                            enter(use, function, Self(subject.value, subject.state, target.receiverClass ?: subject.type), extended, held, handedOver)
                            return Followed.READ
                        }
                    }
                }
            }
            return Followed.SKIPPED
        }

        /** Why a call of the function [id] is not followed from here; `null` if it is. */
        private fun cutOffReason(id: FunctionId): String? =
            when {
                id in entry.stack -> UnknownReasons.RECURSION
                depth >= maxDepth -> UnknownReasons.DEPTH_LIMIT
                else -> null
            }

        /**
         * Whether a step is certain to lie behind a call of what holds [held] that is handed
         * [handedOver]: in the function itself, or in code it is handed and runs or hands on.
         */
        private fun holdsStep(held: Summary, handedOver: List<Code>): Boolean =
            held.certain || (held.handed && handedOver.any { holds(it).certain })

        /** What code holds that is handed over where the reading stands: a lambda, a reference, or what this function was handed itself. */
        fun holds(code: Code): Summary =
            when (code) {
                is LocalRead -> handed[code.name]?.holds ?: Summary.NOTHING
                else -> probe.summary(code, site()) { name -> handed[name]?.holds?.certain == true }
            }

        /**
         * A call that did not resolve and that runs one of [candidates], functions of the
         * sources the compiler could not choose among. Which one cannot be told, so none is
         * followed: a node says so if a step is certain to lie in one of them, and names them.
         * Where none holds a step for certain, the place says what may matter of them, as for
         * a function that is not drawn. The answer is whether anything stands for the call;
         * if nothing does, it is treated like that of any function that is no step.
         */
        private fun oneOf(use: Use, candidates: List<Callee>, handedOver: List<Code>): Boolean {
            val functions = candidates.mapNotNull { backend.function(it.function) }
            val held = functions.fold(Summary.NOTHING) { all, function -> all + probe.summary(dispatch.resolve(function.id, null, exact = false)) }
            if (!holdsStep(held, handedOver)) return undrawn(use, use.name, held, handedOver) != Followed.NOT
            cutOff(UnknownReasons.UNRESOLVED, use.name, functions.map { it.id.toString() }, use, handedOver)
            return true
        }

        /**
         * Says that a call is not followed, and why; what was handed over to it may run all the same.
         *
         * @param firm whether a step is certain to lie behind the call, see [Step.firm]
         */
        private fun cutOff(reason: String, label: String?, candidates: List<String>, use: Use, handedOver: List<Code>, firm: Boolean = true) {
            val node = IrNode(id = "", kind = NodeKind.UNKNOWN, reason = reason, label = label?.let(Captions::of), candidates = candidates)
            add(Step(node, use.location, firm = firm))
            uncharted(null, use.location, handedOver, use.name)
        }

        /**
         * Says of a call that is not drawn from the inside what may matter of it where it is
         * made: that it may fail, and with what, or that it calls something that cannot be
         * followed. What the function may do with code it is handed and reads counts as its
         * own. The answer is whether there is anything to say.
         */
        private fun note(use: Use, label: String, held: Summary, handedOver: List<Code>): Boolean {
            val all = if (held.handed) handedOver.fold(held) { sum, code -> sum + holds(code) } else held
            if (!all.mayMatter) return false
            val raises = all.raises.sortedWith(compareBy<Thrown, String?>(nullsLast()) { it.name }.thenBy(nullsLast()) { it.type })
            add(Undrawn(label, use.location, raises, all.unseen.sorted(), use.never))
            return true
        }

        /**
         * A call that is neither read nor drawn: no step is certain to lie behind it. What may
         * matter of it is said where it is made, and what it is handed is read for what that
         * reads and writes alone. If there is nothing to say, the call is treated like that of
         * any function that is no step.
         */
        private fun undrawn(use: Use, label: String, held: Summary, handedOver: List<Code>): Followed {
            if (!note(use, label, held, handedOver)) return if (handedOver.isEmpty()) Followed.SKIPPED else Followed.NOT
            if (held.handed) {
                apart { handedOver.forEach { later(it, use.name) } }
            } else {
                uncharted(notModelled(label), use.location, handedOver, use.name)
            }
            return Followed.SKIPPED
        }

        /**
         * Reads [function] at the place [use] where it is called: as part of this flow if a
         * step is certain to lie in it, and otherwise only for what it reads and writes.
         *
         * @param self the object of the function's class that it runs on
         * @param extended the object the function extends, if it is an extension function
         * @param held what the function holds, for what to say of it where it is not drawn
         * @param handedOver the code the call hands over
         */
        private fun enter(use: Use, function: FunctionDecl, self: Self, extended: Self?, held: Summary, handedOver: List<Code>) {
            val body = backend.body(function.id) ?: return
            val label = recognizer.label(function.id)
            var made = emptyList<Handed>()
            val drawn = enter(function.id, function.name, label, body, use.location, self) { inner ->
                inner.take(function, use, this, extended).also { made = it }
            }
            val unused = made.filterNot { it.used }.map { it.code }
            // What the function did with the code it ran or handed on is part of what it holds.
            if (!drawn) note(use, label, held, handedOver.filterNot { code -> unused.any { it === code } })
            // Code that was handed over and neither run nor handed on: nothing says what becomes of it.
            uncharted(notModelled(label), use.location, unused, use.name)
        }

        /**
         * Reads the body of a function or getter. Where it holds a step for certain, it becomes
         * part of this flow. Any other body leaves nothing but the compensations registered in it.
         *
         * @param prepare gives the new reading what the function is handed
         * @return whether the body is part of this flow now
         */
        private fun enter(
            id: FunctionId,
            name: String,
            label: String,
            body: Block,
            location: SourceLocation,
            self: Self,
            prepare: (Reading) -> Unit,
        ): Boolean {
            val frame = ++entry.frames
            val site = "$label at ${location.file}:${location.line}"
            entry.stack += id
            entry.path += site
            val flow = try {
                val inner = Reading(entry, name, depth + 1, frame, self)
                prepare(inner)
                inner.read(body)
            } finally {
                entry.path.removeAt(entry.path.lastIndex)
                entry.stack.removeAt(entry.stack.lastIndex)
            }
            val drawn = flow.holdsCertainStep()
            if (drawn) add(Inlined(frame, site, flow)) else keepRegistrations(flow)
            return drawn
        }

        /**
         * Takes the arguments of the call [use] as what the parameters of [function] stand for in
         * this reading. Returns the lambdas and references among them.
         */
        fun take(function: FunctionDecl, use: Use, caller: Reading, extended: Self?): List<Handed> {
            val made = ArrayList<Handed>()
            for (parameter in function.parameters) {
                val default = backend.defaultValue(function.id, parameter.name)
                scope.bind(parameter.name, default?.let { Traced(it, Scope.none(self.value)) })
            }
            for (argument in use.arguments) {
                val parameter = argument.parameter ?: continue
                val value = caller.wrapped(argument.value)
                val handedOn = (value as? LocalRead)?.let { caller.handed[it.name] }
                when {
                    handedOn != null -> handed[parameter] = handedOn
                    value is Lambda || value is CallableReference -> handed[parameter] = Handed(value, caller, use.name, caller.holds(value)).also { made += it }
                    else -> scope.bind(parameter, Traced(argument.value, caller.scope), caller.stateOf(argument.value))
                }
            }
            // Inside an extension function, `this` is the object it extends.
            val extension = function.receiver?.name
            if (extension != null && extended != null) implicitReceivers += ImplicitReceiver(extension, extended.value, extended.state)
            // Where the type it extends did not resolve, the object is known all the same.
            if (extension == null && function.receiver != null) untypedReceivers += Untyped(extended?.value, function.receiver.unresolved)
            return made
        }

        /** A reference to a function of the sources, where it is handed to something that calls it: the function is followed. */
        private fun refer(reference: CallableReference) {
            val callee = recognizer.target(reference) ?: return
            val use = Use(reference.location, callee.function.simpleName, reference.receiver, reference.receiverType)
            follow(use, callee, emptyList())
        }

        /** A read of a property whose getter is written out in the sources: the getter is followed. */
        private fun getter(read: PropertyRead) {
            val property = read.property ?: return
            val body = backend.getter(property) ?: return
            val id = StepProbe.getterId(property)
            val label = listOfNotNull(property.owner?.substringAfterLast('.'), property.simpleName).joinToString(".")
            val reason = cutOffReason(id)
            val use = Use(read.location, read.name, read.receiver, null)
            val self = when {
                read.onThis -> self
                read.receiver != null -> subjectOf(read.receiver, null)
                else -> implicit(property.owner) ?: NOBODY
            }
            val held = probe.summary(property, body)
            val touchesState = entry.thorough && self.state != null
            when {
                !touchesState && held.isInert -> Unit
                reason != null -> if (held.certain) cutOff(reason, label, emptyList(), use, emptyList()) else note(use, label, held, emptyList())
                !held.certain && !held.writes && !touchesState -> note(use, label, held, emptyList())
                else -> if (!enter(id, read.name, label, body, read.location, self) {}) note(use, label, held, emptyList())
            }
        }

        // ---- the objects code runs on, and the state of the workflow

        /**
         * Visits an expression that names an object in order to do something to it: to call a
         * function on it, or to assign to one of its properties. Naming a part of the workflow's
         * state that way is no read of it; whether it is read or written is for what is done to say.
         */
        private fun visitNamed(code: Code, kept: Boolean = false) {
            when {
                code is PropertyRead -> {
                    code.receiver?.let(::visitNamed)
                    getter(code)
                }
                code is Other && code.operator in TRANSPARENT && code.operands.size == 1 -> visitNamed(code.operands.single(), kept)
                // What a function of promises is called on stays in sight: the call says what becomes of it.
                kept -> hold(code)
                else -> visit(code)
            }
        }

        /** The object a function is called on, as far as it can be told. */
        private fun subjectOf(receiver: Code?, receiverType: TypeRef?): Self =
            when {
                // Inside a lambda with a receiver, `this` is that receiver.
                receiver is ThisReference -> implicit(receiver.type?.name) ?: self
                StepProbe.isSuper(receiver) -> self
                receiver != null -> Self(Traced(receiver, scope), stateOf(receiver), tracer.constructedClass(receiver, scope))
                receiverType == null -> NOBODY
                else -> implicit(receiverType.name) ?: self
            }

        private fun implicit(type: String?): Self? =
            implicitReceivers.lastOrNull { it.type == type }?.let { Self(it.value, it.state, null) }

        /** What of the workflow's state [code] stands for; `null` if it stands for none of it. */
        fun stateOf(code: Code?): StateRef? =
            when (code) {
                is ThisReference -> (implicit(code.type?.name) ?: self).state
                is LocalRead -> scope.state(code.name)
                is PropertyRead ->
                    if (StateRules.isState(code.type)) holder(code.receiver, code.onThis, code.property)?.member(code.name) else null
                // What a call on a part of the state returns is taken to be of that part.
                is Call -> code.receiver?.let(::stateOf) as? StateRef.Property
                is Other -> code.operands.singleOrNull()?.takeIf { code.operator in TRANSPARENT }?.let(::stateOf)
                else -> null
            }

        /** What of the workflow's state the object is that a property is read or written on. */
        private fun holder(receiver: Code?, onThis: Boolean, property: PropertyId?): StateRef? =
            when {
                onThis -> self.state
                receiver != null -> stateOf(receiver)
                else -> implicit(property?.owner)?.state
            }

        /**
         * The properties of the workflow read by code that is no part of the flow: the condition
         * of an await. Everything the code reaches is read for it.
         */
        private fun readBy(codes: List<Code>, label: String?): List<String> {
            val outer = entry.state
            val thorough = entry.thorough
            entry.state = StateUse()
            entry.thorough = true
            try {
                collectApart { codes.forEach { later(it, label) } }
                return entry.state.reads.toList()
            } finally {
                // An entry point reads what its awaits wait for.
                outer += entry.state
                entry.state = outer
                entry.thorough = thorough
            }
        }

        /** Reads something only for what it reads and writes: the flow it gives is no part of this one. */
        private fun apart(block: () -> Unit) {
            collectApart(block)
        }

        // ---- what a step returned, and what it is assigned to

        /** Notes that [name] holds what the step in question returned, if [value] is that step. */
        private fun bindResult(name: String, value: Code?): Boolean {
            val asked = gate ?: return false
            if (unwrapped(value) !== asked.call) return false
            asked.variable = name
            return true
        }

        private fun spendGate() {
            gate?.spend()
            gate = null
        }

        /** A side effect is called what its value is called: the variable it is assigned to, in words. */
        private fun nameSideEffect(name: String, value: Code?) {
            val (call, step) = lastStep ?: return
            val node = step.node
            if (unwrapped(value) !== call || node.kind != NodeKind.SIDE_EFFECT || node.name != null || node.label != null) return
            step.node = node.copy(label = Captions.words(name).ifEmpty { null })
        }

        /** [code] without the casts and assertions around it. */
        private fun unwrapped(code: Code?): Code? {
            var current = code
            while (current is Other && current.operator in TRANSPARENT && current.operands.size == 1) current = current.operands.single()
            return current
        }

        // ---- scope functions

        /**
         * Reads the lambda of a scope function where the function is called. Inside the lambda of
         * `run`, `apply` and `with`, `this` is the object given to the function; the lambda of
         * `let` and `also` gets it as its parameter.
         *
         * @param dropped whether what the lambda evaluates to is dropped: the function gives it
         *   back, and nothing takes it
         */
        private fun inPlace(call: Call, handedOver: List<Code>, dropped: Boolean = false) {
            val function = call.callee?.function
            val subject = when {
                function == null || function.name !in TemporalApi.SCOPE_FUNCTIONS -> null
                function.receiver != null -> call.receiver
                function.name == "kotlin.with" -> call.arguments.firstOrNull()?.value
                else -> null
            }
            val given = subject?.let { Self(Traced(it, scope), stateOf(it), null) }
            val type = call.typeArguments.firstOrNull()?.name
            when {
                given == null -> handedOver.forEach { later(it, call.name, dropped = dropped) }
                function?.name !in TemporalApi.RECEIVER_SCOPE_FUNCTIONS -> handedOver.forEach { later(it, call.name, given, dropped) }
                type == null -> {
                    // The object is `this` inside the lambda, of whatever type it is.
                    untypedReceivers += Untyped(given.value)
                    try {
                        handedOver.forEach { later(it, call.name, dropped = dropped) }
                    } finally {
                        untypedReceivers.removeAt(untypedReceivers.lastIndex)
                    }
                }
                else -> {
                    implicitReceivers += ImplicitReceiver(type, given.value, given.state)
                    try {
                        handedOver.forEach { later(it, call.name, dropped = dropped) }
                    } finally {
                        implicitReceivers.removeAt(implicitReceivers.lastIndex)
                    }
                }
            }
        }

        private fun site(): CallSite =
            CallSite(scope, inForce, untyped = { untypedReceivers.asReversed() }) { type -> implicitReceivers.lastOrNull { it.type == type }?.value }
    }

    /** An object that is `this` for some of the code being read without being named there. */
    private class ImplicitReceiver(val type: String, val value: Traced?, val state: StateRef?)

    /** A lambda or reference handed to a function that is being followed, which runs it or hands it on. */
    private inner class Handed(
        val code: Code,
        /** The reading of the function that wrote the code and handed it over. */
        private val owner: Reading,
        val label: String?,
        /** What the code holds, as far as it can be told without reading it. */
        val holds: Summary,
    ) {
        var used = false
            private set

        /** What the code holds, read as code of the function that wrote it. */
        fun run(): Flow {
            used = true
            return owner.run(this)
        }
    }

    /** What became of a call of a function of the sources. */
    private enum class Followed {
        /** Nothing: it is to be treated like the call of any function that is no step. */
        NOT,

        /**
         * It was not read as part of the flow: the function holds nothing, a node says why the
         * call is not followed, or the place of the call says what may matter of a function
         * that is not drawn.
         */
        SKIPPED,

        /** The function was read as part of the flow. */
        READ,
    }

    private class LambdaFrame(val id: Int, val label: String?, val isAnonymousFunction: Boolean)

    private class LoopFrame(val id: Int, val label: String?)

    private fun SourceLocation.at(): String = "$file:$line"

    private companion object {
        /** What a function goes by that is no function: a `return` in a constructor names nothing. */
        const val CONSTRUCTOR = "<init>"

        const val NOTHING = "kotlin.Nothing"
        const val BOOLEAN = "kotlin.Boolean"
        const val YES = "yes"
        const val NO = "no"
        const val ELSE = "else"
        const val IMPLICIT_PARAMETER = "it"
        const val WITH = "kotlin.with"
        const val ASSIGNMENT = "="
        const val INDEXED_ASSIGNMENT = "[]="

        /** The object of a function that runs on none, or on one nothing is known of. */
        val NOBODY = Self(null, null, null)
        const val CONTINUE_AS_NEW_SUGGESTED = "${TemporalApi.WORKFLOW_INFO}.isContinueAsNewSuggested"
        const val CONTINUE_AS_NEW_QUESTION = "continue-as-new suggested?"
        val SHORT_CIRCUIT = setOf("&&", "||", "?:")
        val TRANSPARENT = setOf("as", "as?", "!!")

        /** What the lowering calls an object literal and a local declaration, whose code it leaves out. */
        val OPAQUE = setOf("object", "declaration")

        /** Functions that give back what they are called on. */
        val GIVE_BACK_RECEIVER = setOf("kotlin.also", "kotlin.apply")

        /** Functions that hand what they are called on to their lambda as its parameter, where it can be followed. */
        val FOLLOW_RECEIVER = setOf("kotlin.also", "kotlin.let")

        /** Scope functions that give back what their lambda evaluates to. */
        val GIVE_BACK_RESULT = setOf("kotlin.run", "kotlin.let", "kotlin.with")

        /** Functions of collections that add what they are given to the collection they are called on. */
        val COLLECTORS = setOf("add", "addFirst", "addLast", "push", "offer", "plusAssign")

        /** How many variables a value is followed through. */
        const val MAX_ALIASES = 16
        const val THROWABLE = "kotlin.Throwable"
        const val RUNTIME_EXCEPTION = "java.lang.RuntimeException"
        val TESTS = listOf("in ", "!in ", "is ", "!is ")
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
