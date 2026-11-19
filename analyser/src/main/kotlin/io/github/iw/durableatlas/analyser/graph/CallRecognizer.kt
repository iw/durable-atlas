package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Argument
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CallableReference
import io.github.iw.durableatlas.analyser.code.Callee
import io.github.iw.durableatlas.analyser.code.CalleeKind
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.ThisReference
import io.github.iw.durableatlas.analyser.code.Throw
import io.github.iw.durableatlas.analyser.code.TypeRef
import io.github.iw.durableatlas.analyser.code.find
import io.github.iw.durableatlas.analyser.discovery.TemporalTypes
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.SkippedCall
import io.github.iw.durableatlas.ir.UnknownReasons
import java.time.Duration

/** When the lambdas and callable references among the arguments of a call run. */
internal enum class Deferred {
    /** Once, as part of the call: their code belongs where the call is. */
    INLINE,

    /** Once, as part of the call, in a scope that the cancellation of the workflow does not reach. */
    DETACHED,

    /** Outside the workflow's flow, or without doing anything that matters to it. */
    SKIP,

    /** Whenever the function called decides, if at all. */
    OPTIONAL,

    /** Once for each element of something: the call is a loop. */
    EACH,

    /** Beside the flow, which goes on without waiting: the call starts a lane of a parallel region. */
    ASIDE,

    /** If and when the saga they are registered with compensates. */
    COMPENSATION,

    /** Once, and again for as long as they fail: the call is a loop that is left when they succeed. */
    RETRY,

    /** Once the promise the call is made on is done, or only if it succeeds, or only if it fails: see [Chain]. */
    CHAINED,
}

/** When what is chained to a promise runs. */
internal enum class Chain {
    /** If the promise succeeds. */
    THEN,

    /** If the promise succeeds; what it returns is a promise that is waited for in turn. */
    COMPOSE,

    /** When the promise is done, however it ended. */
    HANDLE,

    /** If the promise fails. */
    EXCEPTIONALLY,
}

/** What a call does to the flow apart from being a step. */
internal enum class Effect {
    NONE,

    /** The flow waits for what was started beside it. */
    JOIN,

    /** A saga runs its compensations. */
    COMPENSATE,

    /** An object is registered whose functions handle signals, queries or updates from then on. */
    LISTEN,

    /** The flow waits until a child workflow that was started beside it has started. */
    STARTED,

    /** A cancellation scope is cancelled. */
    CANCEL,
}

/** What a call means for the graph. */
internal class Recognised(
    /** The node the call itself stands for, without id and location; `null` if it is no step of the workflow. */
    val node: IrNode?,
    val deferred: Deferred = Deferred.OPTIONAL,
    val effect: Effect = Effect.NONE,
    /** The ways the step can turn out, where code after it may ask which one it was. */
    val outcomes: Outcomes? = null,
    /** Tells the sagas of a workflow apart, for calls that register or run compensations. */
    val saga: String? = null,
    /**
     * For a call that runs the compensations of a saga: whether they run side by side, as the
     * options the saga was made with say; `null` if that cannot be told.
     */
    val parallel: Boolean? = null,
    /** What the region is called that the call stands for, where it is one: a retry. */
    val label: String? = null,
    /**
     * The function called, if it is one written in the analysed sources and no step itself: what
     * it does is part of the flow, to be found by following the call.
     */
    val target: Callee? = null,
    /** When what is handed over runs, for [Deferred.CHAINED]. */
    val chain: Chain? = null,
    /** True for an await on the object the call is made on: a queue or a promise that something else feeds. */
    val onReceiver: Boolean = false,
    /** For [Effect.STARTED]: the expression that made the stub of the child whose start is waited for. */
    val stub: Code? = null,
    /** True if what is started beside the flow is started again for as long as it fails; [label] says so. */
    val retried: Boolean = false,
    /** True if what is handed over runs in a cancellation scope of its own, which can be cancelled as one. */
    val scoped: Boolean = false,
    /** What the call does to the options the workflow has for its activities, if it sets any. */
    val options: OptionsChange? = null,
    /**
     * True if the call does not wait for the step it stands for, but gives a promise for it:
     * the step runs beside the flow, like what is handed to `Async`, until something waits for
     * the promise.
     */
    val promised: Boolean = false,
    /** For a wait on a workflow queue: what it waits for there. */
    val queue: QueueWait? = null,
    /** True for a wait that raises a `TimeoutException` when its time is up. */
    val timesOut: Boolean = false,
    /**
     * For a call that did not resolve: the functions of the analysed sources that the compiler
     * could not choose among. One of them runs, so what they do may be part of the flow.
     */
    val candidates: List<Callee> = emptyList(),
)

/** A function of an object handed to `Workflow.registerListener` that handles signals, queries or updates. */
internal class ListenerMethod(
    val kind: EntryPointKind,
    /** The name the handler goes by; `null` for one that takes every name that has no handler of its own. */
    val name: String?,
    /** The function that runs; `null` if the sources hold none. */
    val function: FunctionDecl?,
)

/** What is known of the exception a `throw` throws, as far as it tells whether the exception fails a workflow. */
internal class Thrower(
    /** Fully qualified name of the type of what is thrown; `null` if it is not known. */
    val type: String?,
    /** Whether what is thrown is of that very class: it is made where it is thrown, or its making can be found. */
    val exact: Boolean,
    /** The local name what is thrown is read from, if it is: the parameter of a `catch` clause, for one. */
    val variable: String?,
)

/** Where a call is made: what its local names and its implicit receiver stand for. */
internal class CallSite(
    val scope: Scope,
    /** The options the workflow has set for its activities where the call is made; `null` where nothing is known of them. */
    val options: OptionsInForce? = null,
    /** The scope to follow an object in on which a call is made that does not resolve; as a rule [scope]. */
    val tracing: Scope = scope,
    /** The objects that are `this` for the code around the call and whose type could not be resolved, innermost first. */
    val untyped: () -> List<Untyped> = { emptyList() },
    /** Whether the local name stands for something that cannot be told here, though a reading of the code would know it. */
    val unseen: (name: String) -> Boolean = { false },
    /** The expression that `this` of the given type refers to inside a scope function, if any. */
    val implicitReceiver: (typeName: String) -> Traced?,
)

/**
 * Recognises the calls that are steps of a workflow. Everything is decided by what a call
 * resolves to (the function called and the type of the object it is called on), never by the
 * text of a name.
 */
internal class CallRecognizer(
    private val backend: SourceBackend,
    private val names: TemporalNames,
    private val stubs: Stubs,
    private val constants: Constants,
    private val tracer: ValueTracer,
    /** The workflow type whose graph is being made; `Workflow.continueAsNew` continues as it. */
    private val workflowType: String,
    /**
     * Judges the calls that do not resolve, where the model is known to lack the libraries the
     * sources use. Without it, a call that does not resolve is broken code or a missing
     * dependency, and a node says so.
     */
    private val unresolved: UnresolvedCalls? = null,
) {
    private val maps = MapLiterals(tracer, constants)

    /** Whether calls that do not resolve are judged rather than all drawn; see [UnresolvedCalls]. */
    val lenient: Boolean get() = unresolved != null

    /** The calls that did not resolve and were taken for calls into a library, in the order they were met. */
    val skipped: List<SkippedCall> get() = unresolved?.skipped.orEmpty()

    /** Recognises what [block] asks about without noting what is skipped: code that is looked at and may never run. */
    fun <T> unrecorded(block: () -> T): T = unresolved?.unrecorded(block) ?: block()

    fun call(call: Call, site: CallSite): Recognised {
        val callee = call.callee ?: return unresolved(call, site)
        return resolved(call, callee, site)
    }

    /**
     * A call that did not resolve. In a model made from the build that is broken code or a
     * missing dependency, and a node says that it is not known what the call does. In a
     * degraded model it is judged: see [UnresolvedCalls]. In either, a call that may mean any of
     * several declarations the sources hold under one name is a node that says so: it is no
     * call into a library, and which code it runs is not known.
     */
    private fun unresolved(call: Call, site: CallSite): Recognised {
        // A call that names what the sources declare more than once runs code of the sources, and nothing tells which.
        call.unresolved?.declaredTwice?.let { return Recognised(unknown(UnknownReasons.UNRESOLVED, declaredTwiceLabel(call, it))) }
        val judge = unresolved ?: return Recognised(unknown(UnknownReasons.UNRESOLVED, call.name))
        val candidates = call.unresolved?.candidates.orEmpty()
        // The compiler was left with functions of the Temporal API alone, and it makes no difference which is meant.
        if (candidates.all { it.function.name.startsWith(TEMPORAL) }) agreed(call, candidates, site)?.let { return it }
        return when (val verdict = judge.judge(call, site)) {
            is Unsettled.Kept -> Recognised(verdict.node)
            is Unsettled.Sources -> Recognised(null, candidates = verdict.functions)
            // A call into a library, and every function it may be runs what it is handed once for each element: a loop.
            Unsettled.Library -> agreed(call, candidates, site)?.takeIf { it.deferred == Deferred.EACH } ?: Recognised(null)
        }
    }

    /**
     * What a call is labelled that may mean either of several declarations of one name: its
     * name after that of the class, as in `Limits.check`; the name alone for a function of no
     * class, and for a constructor, which is called like its class.
     */
    private fun declaredTwiceLabel(call: Call, declared: String): String {
        val simple = declared.substringAfterLast('.')
        return if (simple == call.name) call.name else "$simple.${call.name}"
    }

    /**
     * What an unresolved call means where it makes no difference which of the functions the
     * compiler was left with is meant: the call reads the same as each of them. So a
     * `Workflow.sleep` whose argument is of a type that did not resolve is still a timer, a
     * call that makes a stub is still no step, and a `forEach` on a collection of a type that
     * did not resolve is still a loop. `null` if there is no such function, or the readings differ.
     */
    private fun agreed(call: Call, candidates: List<Callee>, site: CallSite): Recognised? {
        if (candidates.isEmpty()) return null
        val readings = candidates.map { resolved(call.copy(callee = it, unresolved = null), it, site) }
        val first = readings.first()
        // Only what a reading is made of in full is compared: a reading that carries more is left to the judge.
        val plain = readings.all { it.outcomes == null && it.options == null && it.stub == null && it.saga == null && it.target == null }
        val same = readings.all {
            it.node == first.node && it.deferred == first.deferred && it.effect == first.effect && it.label == first.label &&
                it.chain == first.chain && it.promised == first.promised && it.retried == first.retried && it.scoped == first.scoped &&
                it.onReceiver == first.onReceiver && it.queue == first.queue && it.timesOut == first.timesOut
        }
        return first.takeIf { plain && same }
    }

    private fun resolved(call: Call, callee: Callee, site: CallSite): Recognised {
        val function = callee.function
        if (function.name in TemporalApi.SCOPE_FUNCTIONS) return Recognised(null, Deferred.INLINE)
        if (isIteration(function)) return Recognised(null, Deferred.EACH)
        when (function.owner) {
            TemporalApi.WORKFLOW -> workflowApi(call, function, site)?.let { return it }
            TemporalApi.ASYNC -> when (function.simpleName) {
                in TemporalApi.ASYNC_STARTERS -> return Recognised(null, Deferred.ASIDE)
                // What is handed over is started at once, and again whenever the promise it gives fails.
                TemporalApi.ASYNC_RETRY -> return Recognised(null, Deferred.ASIDE, label = retryLabel(call.arguments, site), retried = true)
                // The wait of `Workflow.await`, for which the flow is given a promise.
                TemporalApi.ASYNC_AWAIT -> return await(call, function, site, promised = true)
            }
            in TemporalApi.PROMISES -> promise(call, function, site)?.let { return it }
            in TemporalApi.QUEUE_ENDS -> queue(call, function, site)?.let { return it }
            in TemporalApi.GUARDS -> guard(call, function, site)?.let { return it }
            TemporalApi.CANCELLATION_SCOPE ->
                if (function.simpleName == "cancel") {
                    val scope = call.receiver?.takeUnless { isCurrentScope(it) }?.let(Texts::of)?.ifEmpty { null } ?: CURRENT_SCOPE
                    return Recognised(IrNode(id = "", kind = NodeKind.CANCEL_SCOPE, label = Captions.of(scope)), Deferred.SKIP, Effect.CANCEL)
                }
            TemporalApi.SAGA -> when (function.simpleName) {
                "addCompensation" -> return Recognised(null, Deferred.COMPENSATION, saga = sagaOf(call, site))
                "compensate" ->
                    return Recognised(null, Deferred.SKIP, Effect.COMPENSATE, saga = sagaOf(call, site), parallel = compensatesInParallel(call, site))
            }
        }
        // What a stub without an interface is asked to start is given a promise for.
        untypedStub(call, function, site)?.let { return Recognised(it, promised = function.simpleName == TemporalApi.EXECUTE_ASYNC) }
        stubMethod(callee, call.receiver, call.receiverType, site)?.let { return Recognised(it) }
        return Recognised(null, target = callee.takeIf { isWritten(it) && !isStubMethod(it, call.receiverType) })
    }

    /**
     * The node a reference to a function stands for where it is handed to something that will
     * call it; `null` if the function is no step of a workflow.
     */
    fun reference(reference: CallableReference, site: CallSite): IrNode? {
        val callee = reference.function
            // A reference that names nothing the compiler knows may still name a function of a stub.
            ?: return reference.takeIf { it.property == null }?.let { unresolved?.judge(it, site) }
        return stubMethod(callee, reference.receiver, reference.receiverType, site)
    }

    /** The function [reference] names, if it is one written in the analysed sources. */
    fun target(reference: CallableReference): Callee? =
        reference.function?.takeIf { isWritten(it) && !isStubMethod(it, reference.receiverType) }

    /**
     * The handlers an object of the class [className] brings when it is handed to
     * `Workflow.registerListener`: the one function of a handler for every name, or else the
     * signal, query and update methods of the workflow interfaces the class implements. Empty
     * if the class is not known or brings none.
     */
    fun listeners(className: String): List<ListenerMethod> {
        val declaration = backend.findClass(className) ?: return emptyList()
        val types = listOf(className) + declaration.allSuperTypes
        for ((handler, kind) in TemporalApi.DYNAMIC_HANDLERS) {
            if (handler !in types) continue
            // The function of the sources that runs for a function of the handler's interface, nearest first.
            fun running(name: String): FunctionDecl? =
                types.firstNotNullOfOrNull { type ->
                    backend.functions(type).firstOrNull { function ->
                        !function.isAbstract && function.name == name &&
                            (function.id.owner == handler || function.overrides.any { it.owner == handler })
                    }
                }?.takeIf { it.module != null }
            val handles = ListenerMethod(kind, name = null, running(if (kind == EntryPointKind.UPDATE) "handleExecute" else "handle"))
            val validates = running("handleValidate")?.takeIf { kind == EntryPointKind.UPDATE }
            return listOfNotNull(handles, validates?.let { ListenerMethod(EntryPointKind.UPDATE_VALIDATOR, name = null, it) })
        }
        return names.listenerEntryPoints(className).map { ListenerMethod(it.entryPoint.kind, it.entryPoint.name, it.function) }
    }

    /** Whether [code] asks for the cancellation scope that the code around it runs in. */
    fun isCurrentScope(code: Code): Boolean = (code as? Call)?.callee?.function?.name == TemporalApi.CURRENT_SCOPE

    /** Whether [call] makes a cancellation scope. */
    fun makesScope(call: Call): Boolean = call.callee?.function?.name in TemporalApi.SCOPE_MAKERS

    // ---- promises and queues

    /**
     * A function of a promise. Waiting for a promise is no step: what it waits for is. A promise
     * that the workflow completes itself is another matter: nothing was started for it, and the
     * flow stands still until something, as a rule a handler, completes it. Waiting for the
     * execution of a child workflow waits until the child has started.
     */
    private fun promise(call: Call, function: FunctionId, site: CallSite): Recognised? {
        val name = function.simpleName
        TemporalApi.PROMISE_CHAINS[name]?.let { return Recognised(null, Deferred.CHAINED, chain = it) }
        if (name !in TemporalApi.PROMISE_JOINS) return null
        if (name in TemporalApi.PROMISE_WAITS) {
            val made = call.receiver?.let { tracer.origin(it, site.scope, settled = false) }
            val maker = made?.code as? Call
            val makerName = maker?.callee?.function?.name
            // `stub.execution`, as Kotlin lets the execution of a child be asked for through a stub without an interface.
            val asked = (made?.code as? PropertyRead)?.takeIf { it.name == EXECUTION }?.receiver?.let { stubs.creation(it, made.scope) }
            if (asked?.kind == StubKind.UNTYPED_CHILD_WORKFLOW) return Recognised(null, Deferred.SKIP, Effect.STARTED, stub = asked.call)
            when {
                (makerName == TemporalApi.NEW_PROMISE && maker.arguments.isEmpty()) || call.receiverType?.name == TemporalApi.COMPLETABLE_PROMISE -> {
                    val node = IrNode(id = "", kind = NodeKind.AWAIT, timeout = timeLimit(call.arguments, site)?.toString(), label = waitLabel(call))
                    // With an amount of time and its unit, the wait gives up when the time is up, and says so with a failure.
                    return Recognised(node, Deferred.SKIP, onReceiver = true, timesOut = call.arguments.size == TIMED)
                }
                makerName == TemporalApi.GET_WORKFLOW_EXECUTION ->
                    return Recognised(null, Deferred.SKIP, Effect.STARTED, stub = maker.arguments.firstOrNull()?.let { tracer.origin(it.value, made.scope, settled = false).code })
                makerName == TemporalApi.CHILD_EXECUTION ->
                    return Recognised(null, Deferred.SKIP, Effect.STARTED, stub = maker.receiver?.let { tracer.origin(it, made.scope, settled = false).code })
            }
        }
        return Recognised(null, Deferred.SKIP, Effect.JOIN)
    }

    /**
     * A function of the receiving end of a workflow queue that waits until the queue holds
     * something: the flow stands still until something, as a rule a handler, puts it there. With
     * a time to wait for at most, it goes on when that is up, and is given nothing.
     */
    private fun queue(call: Call, function: FunctionId, site: CallSite): Recognised? {
        val limit = call.arguments.lastOrNull()?.takeIf { function.parameterTypes().lastOrNull() == TemporalApi.DURATION }
        fun wait(waitsFor: QueueWait, outcomes: Outcomes? = null): Recognised {
            val timeout = limit?.let { constants.duration(it.value, site.scope) }
            val node = IrNode(id = "", kind = NodeKind.AWAIT, timeout = timeout?.toString(), label = waitLabel(call))
            return Recognised(node, Deferred.SKIP, outcomes = outcomes, onReceiver = true, queue = waitsFor)
        }
        return when (function.simpleName) {
            in TemporalApi.QUEUE_TAKES -> wait(QueueWait.ITEMS)
            // Without a time to wait, the queue is only looked at.
            in TemporalApi.QUEUE_POLLS -> if (limit == null) null else wait(QueueWait.ITEMS, Outcomes.ofPoll())
            // A queue that holds as much as there can be never makes anyone wait for room.
            in TemporalApi.QUEUE_PUTS -> if (isUnbounded(call, site)) null else wait(QueueWait.ROOM)
            // Without a time to wait, what does not fit is turned away at once.
            in TemporalApi.QUEUE_OFFERS -> if (limit == null || isUnbounded(call, site)) null else wait(QueueWait.ROOM, Outcomes.ofAttempt())
            else -> null
        }
    }

    /**
     * Whether the queue [call] is made on was made to hold as much as there can be, so that
     * putting something in never waits. A queue whose making cannot be found, or whose size is
     * no constant, is taken to fill up.
     */
    private fun isUnbounded(call: Call, site: CallSite): Boolean {
        val made = call.receiver?.let { tracer.origin(it, site.scope, settled = false) } ?: return false
        val maker = (made.code as? Call)?.takeIf { it.callee?.function?.name in TemporalApi.QUEUE_MAKERS } ?: return false
        return constants.integer(maker.arguments.firstOrNull(), made.scope) == Int.MAX_VALUE.toLong()
    }

    /**
     * A wait for a condition: `Workflow.await`, which stands still until the condition holds,
     * or `Async.await`, which gives a promise for that and goes on.
     */
    private fun await(call: Call, function: FunctionId, site: CallSite, promised: Boolean): Recognised {
        val arguments = call.arguments
        val timed = function.parameterTypes().firstOrNull() == TemporalApi.DURATION
        val timeout = arguments.firstOrNull()?.takeIf { timed }?.let { constants.duration(it.value, site.scope) }
        val condition = arguments.lastOrNull()?.value
        // What the condition reads is for whoever reads the condition to say.
        val node = IrNode(id = "", kind = NodeKind.AWAIT, timeout = timeout?.toString(), label = Outcomes.awaitLabel(condition))
        // The condition is evaluated by the SDK whenever it sees fit; it is not a step.
        // How a promised wait turned out is only known where its promise is asked, which is not followed.
        return Recognised(node, Deferred.SKIP, outcomes = if (promised) null else Outcomes.ofAwait(condition, timed), promised = promised)
    }

    /**
     * A function of a workflow lock or semaphore that waits until it is free: the flow stands
     * still until whoever holds it lets go, as a rule another run of the handler that waits.
     * With a time to wait for at most, it goes on when that is up, and is told that it has
     * nothing. Asking without a time never waits, and letting go is no step either.
     */
    private fun guard(call: Call, function: FunctionId, site: CallSite): Recognised? {
        val limit = call.arguments.lastOrNull()?.takeIf { function.parameterTypes().lastOrNull() == TemporalApi.DURATION }
        return when (function.simpleName) {
            in TemporalApi.GUARD_WAITS -> Recognised(IrNode(id = "", kind = NodeKind.AWAIT, label = waitLabel(call)), Deferred.SKIP, onReceiver = true)
            in TemporalApi.GUARD_TRIES -> {
                if (limit == null) return null
                val timeout = constants.duration(limit.value, site.scope)
                Recognised(
                    IrNode(id = "", kind = NodeKind.AWAIT, timeout = timeout?.toString(), label = waitLabel(call)),
                    Deferred.SKIP,
                    outcomes = Outcomes.ofAttempt(),
                    onReceiver = true,
                )
            }
            else -> null
        }
    }

    /** What an await on a queue, a promise, a lock or a semaphore is called: the call that waits, without what it is given. */
    private fun waitLabel(call: Call): String {
        val on = call.receiver?.let(Texts::of).orEmpty()
        return Captions.of(if (on.isEmpty()) "${call.name}()" else "$on.${call.name}()")
    }

    /** The time a call waits for at most, where it is given as an amount and a unit that are constants. */
    private fun timeLimit(arguments: List<Argument>, site: CallSite): Duration? {
        if (arguments.size != 2) return null
        val amount = constants.integer(arguments[0], site.scope) ?: return null
        val unit = constants.enumEntry(arguments[1], site.scope, TemporalApi.TIME_UNIT) ?: return null
        return runCatching { Duration.of(amount, java.util.concurrent.TimeUnit.valueOf(unit).toChronoUnit()) }.getOrNull()
    }

    /**
     * A short name for what [call] calls, for the node that stands in for a construct not
     * modelled. A call that did not resolve is called as it is written: by its name, after the
     * name it is made on where that names nothing known either, as a class of a library does.
     */
    fun label(call: Call): String =
        call.callee?.function?.let(::label)
            ?: listOfNotNull((call.receiver as? PropertyRead)?.takeIf { it.property == null && it.receiver == null }?.name, call.name).joinToString(".")

    /** A short name for [function]: its class, if it has one, and its name. */
    fun label(function: FunctionId): String =
        listOfNotNull(function.owner?.substringAfterLast('.'), function.simpleName).joinToString(".")

    /**
     * What a thrown failure is called: the failure type given to an `ApplicationFailure`, if it
     * is a constant, and otherwise the class of what is thrown.
     */
    fun failureName(thrown: Code?, type: TypeRef?, site: CallSite): String? {
        val origin = thrown?.let { tracer.origin(it, site.scope) }
        val call = origin?.code as? Call
        val function = call?.callee?.function
        if (call != null && function != null) {
            val given = when {
                function.owner == TemporalApi.APPLICATION_FAILURE && function.simpleName in TemporalApi.APPLICATION_FAILURE_FACTORIES ->
                    call.arguments.firstOrNull { it.parameter == "type" } ?: call.arguments.getOrNull(1)
                else -> Builders.setters(call, TemporalApi.APPLICATION_FAILURE_BUILDER)["setType"]
            }
            constants.text(given, origin.scope)?.let { return it }
        }
        return type?.name?.substringAfterLast('.')
    }

    /**
     * What a `throw` throws, as far as it tells whether the exception fails a workflow. An
     * exception that is handed to `Workflow.wrap` on the way leaves the workflow as it was
     * handed over: the SDK takes the wrapping off before it looks at it.
     */
    fun thrower(thrown: Throw, site: CallSite): Thrower {
        val value = thrown.value ?: return Thrower(thrown.type?.name, exact = false, variable = null)
        val wrapped = (value as? Call)?.takeIf { it.callee?.function?.name in TemporalApi.WRAPPERS }?.arguments?.singleOrNull()?.value
        val origin = tracer.origin(wrapped ?: value, site.scope).code
        val made = (origin as? Call)?.callee?.takeIf { it.kind == CalleeKind.CONSTRUCTOR }?.function?.owner
        if (made != null) return Thrower(made, exact = true, variable = null)
        val type = if (wrapped == null) thrown.type else typeOf(origin) ?: typeOf(wrapped)
        return Thrower(type?.name, exact = false, variable = (origin as? LocalRead)?.name)
    }

    /**
     * Whether [function] runs the lambda it is given once for each element of what it is called
     * on. A function that takes no function, such as the `+=` that adds a lambda to a list, does not.
     */
    private fun isIteration(function: FunctionId): Boolean =
        when (val owner = function.owner) {
            null -> function.name == TemporalApi.REPEAT ||
                (
                    TemporalApi.ITERATION_PACKAGES.any { function.name.startsWith(it) } &&
                        function.simpleName !in TemporalApi.NOT_ITERATIONS &&
                        function.parameterTypes().any { it.startsWith(TemporalApi.FUNCTION_TYPE) }
                    )
            else -> function.simpleName == "forEach" && owner in TemporalApi.ITERATED_TYPES
        }

    /**
     * Sagas are told apart by the expression that made them, so that a saga is the same one
     * wherever it is handed to; one whose making cannot be found, by the expression it is called on.
     */
    private fun sagaOf(call: Call, site: CallSite): String {
        val receiver = call.receiver ?: return "saga"
        val made = tracer.origin(receiver, site.scope, settled = false).code
        return if (made is Call) "${Conditions.key(made)} at ${made.location.file}:${made.location.line}" else Conditions.key(made)
    }

    /**
     * Whether the saga [call] is made on runs its compensations side by side: what the options
     * it was made with say, which leave it at one after the other unless they are told
     * otherwise. `null` if the making of the saga or of its options cannot be found, or the
     * option is no constant.
     */
    private fun compensatesInParallel(call: Call, site: CallSite): Boolean? {
        val receiver = call.receiver ?: return null
        val made = tracer.origin(receiver, site.scope, settled = false)
        val constructor = (made.code as? Call)?.takeIf { it.callee?.function?.name == SAGA_CONSTRUCTOR } ?: return null
        val given = constructor.arguments.singleOrNull() ?: return null
        val options = tracer.origin(given.value, made.scope, settled = false)
        val build = (options.code as? Call)?.takeIf { it.callee?.function?.owner == TemporalApi.SAGA_OPTIONS_BUILDER } ?: return null
        val set = Builders.setters(build, TemporalApi.SAGA_OPTIONS_BUILDER)["setParallelCompensation"] ?: return false
        return constants.bool(set, options.scope)
    }

    // ---- the static functions of Workflow

    private fun workflowApi(call: Call, function: FunctionId, site: CallSite): Recognised? {
        val arguments = call.arguments
        val first = arguments.firstOrNull()
        val firstType = function.parameterTypes().firstOrNull()
        return when (function.simpleName) {
            "sleep", "newTimer" -> {
                val duration = when (firstType) {
                    TemporalApi.DURATION -> first?.let { constants.duration(it.value, site.scope) }
                    else -> constants.integer(first, site.scope)?.let(Duration::ofMillis)
                }
                // `sleep` waits for the timer; `newTimer` gives a promise for it.
                Recognised(
                    IrNode(id = "", kind = NodeKind.TIMER, duration = duration?.toString(), label = timerSummary(call, function, site)),
                    promised = function.simpleName == TemporalApi.NEW_TIMER,
                )
            }
            "await" -> await(call, function, site, promised = false)
            // What a side effect computes runs outside the workflow's rules and is not followed.
            "sideEffect" -> Recognised(IrNode(id = "", kind = NodeKind.SIDE_EFFECT), Deferred.SKIP)
            "mutableSideEffect" ->
                Recognised(IrNode(id = "", kind = NodeKind.SIDE_EFFECT, name = constants.text(first, site.scope)), Deferred.SKIP)
            "getVersion" -> {
                val changeId = constants.text(first, site.scope) ?: return Recognised(computedName(first))
                val versions = Outcomes.ofVersions(
                    constants.integer(arguments.getOrNull(1), site.scope),
                    constants.integer(arguments.getOrNull(2), site.scope),
                )
                Recognised(IrNode(id = "", kind = NodeKind.VERSION_GATE, changeId = changeId), outcomes = versions)
            }
            "continueAsNew" -> {
                val continuedAs = when (firstType) {
                    "kotlin.String" -> constants.text(first, site.scope)
                    "java.util.Optional" -> null
                    else -> workflowType
                }
                Recognised(IrNode(id = "", kind = NodeKind.CONTINUE_AS_NEW, name = continuedAs))
            }
            // What is handed over runs at once, and again whenever it fails, until the options say to give up.
            "retry" -> Recognised(null, Deferred.RETRY, label = retryLabel(arguments, site))
            "registerListener" -> Recognised(null, Deferred.SKIP, Effect.LISTEN)
            // The operation starts where the call is; what is handed over only names it.
            "startNexusOperation" -> Recognised(null, Deferred.INLINE)
            // What a cancellation scope is given runs when the scope does, which is as a rule at once.
            "newCancellationScope" -> Recognised(null, Deferred.INLINE, scoped = true)
            "newDetachedCancellationScope" -> Recognised(null, Deferred.DETACHED, scoped = true)
            // The activity runs where the call is: what is handed over is the call of a stub, which names it.
            "executeActivity" -> Recognised(null, Deferred.INLINE)
            // The same, started beside the flow, which is given a promise for it.
            "executeActivityAsync" -> Recognised(null, Deferred.ASIDE)
            "upsertTypedSearchAttributes" -> Recognised(upsert(NodeKind.UPSERT_SEARCH_ATTRIBUTES, searchAttributes(arguments, site)))
            "upsertSearchAttributes" -> Recognised(upsert(NodeKind.UPSERT_SEARCH_ATTRIBUTES, first?.let { maps.known(it.value, site.scope) }))
            "upsertMemo" -> Recognised(upsert(NodeKind.UPSERT_MEMO, first?.let { maps.known(it.value, site.scope) }))
            "setDefaultActivityOptions", "setDefaultLocalActivityOptions" -> {
                val local = function.simpleName == "setDefaultLocalActivityOptions"
                val settings = first?.let { stubs.activities.settings(it.value, site.scope, local) } ?: ActivitySettings.UNKNOWN
                Recognised(null, Deferred.SKIP, options = OptionsChange(local) { it.withDefault(settings) })
            }
            // `setActivityOptions` is the older name of `applyActivityOptions`.
            "applyActivityOptions", "setActivityOptions", "applyLocalActivityOptions" -> {
                val local = function.simpleName == "applyLocalActivityOptions"
                val types = first?.let { stubs.activities.byType(it.value, site.scope, local) }
                Recognised(null, Deferred.SKIP, options = OptionsChange(local) { it.withTypes(types) })
            }
            else -> null
        }
    }

    /**
     * The node of an upsert that writes [written]: the names the code gives, and that there are
     * more where it does not give them all. Where it gives none, the node says nothing of names.
     */
    private fun upsert(kind: NodeKind, written: KnownKeys?): IrNode {
        val keys = written?.keys.orEmpty()
        return IrNode(id = "", kind = kind, keys = keys, otherKeys = keys.isNotEmpty() && written?.more == true)
    }

    /**
     * The search attributes that the updates handed to `upsertTypedSearchAttributes` set or
     * remove, by name: one for each update that is made where the code can be read, of a key
     * whose name is a constant. Any other update writes a name that is not known.
     */
    private fun searchAttributes(updates: List<Argument>, site: CallSite): KnownKeys {
        val names = updates.map { searchAttribute(it, site) }
        return KnownKeys(names.filterNotNull().distinct(), more = names.any { it == null })
    }

    private fun searchAttribute(update: Argument, site: CallSite): String? {
        val made = tracer.origin(update.value, site.scope)
        val call = (made.code as? Call)?.takeIf { it.callee?.function?.simpleName in TemporalApi.SEARCH_ATTRIBUTE_UPDATES } ?: return null
        // `key.valueSet(value)`, or `SearchAttributeUpdate.valueSet(key, value)`.
        val key = when (call.callee?.function?.owner) {
            TemporalApi.SEARCH_ATTRIBUTE_KEY -> call.receiver
            TemporalApi.SEARCH_ATTRIBUTE_UPDATE -> call.arguments.firstOrNull()?.value
            else -> null
        } ?: return null
        val named = tracer.origin(key, made.scope)
        // `SearchAttributeKey.forKeyword("name")` and its like, one for each type of value.
        val factory = (named.code as? Call)?.takeIf { it.callee?.function?.owner == TemporalApi.SEARCH_ATTRIBUTE_KEY } ?: return null
        return constants.text(factory.arguments.firstOrNull(), named.scope)
    }

    /**
     * What a retry is called: that it is one, and when it gives up, as far as the retry options
     * and the time allowed are constants. Without either, it goes on until it succeeds.
     */
    private fun retryLabel(arguments: List<Argument>, site: CallSite): String {
        val options = arguments.getOrNull(0)?.let { tracer.origin(it.value, site.scope, settled = false) }
        val attempts = options
            ?.let { constants.integer(Builders.setters(it.code, TemporalApi.RETRY_OPTIONS_BUILDER)["setMaximumAttempts"], it.scope) }
            ?.takeIf { it > 0 }
        val allowed = arguments.getOrNull(1)
            ?.let { tracer.origin(it.value, site.scope).code as? Call }
            ?.takeIf { it.callee?.function?.name == OPTIONAL_OF }
            ?.arguments?.singleOrNull()
            ?.let { constants.duration(it.value, site.scope) }
        return listOfNotNull(
            RETRY,
            attempts?.let { if (it == 1L) "1 attempt" else "up to $it attempts" },
            allowed?.let { "for up to ${Captions.duration(it)}" },
        ).joinToString(", ")
    }

    /** The summary given to a timer in its options, which says what the timer is for. */
    private fun timerSummary(call: Call, function: FunctionId, site: CallSite): String? {
        val index = function.parameterTypes().indexOf(TemporalApi.TIMER_OPTIONS).takeIf { it >= 0 } ?: return null
        val options = call.arguments.getOrNull(index)?.let { tracer.origin(it.value, site.scope, settled = false) } ?: return null
        return constants.text(Builders.setters(options.code, TemporalApi.TIMER_OPTIONS_BUILDER)["setSummary"], options.scope)
    }

    // ---- stubs without an interface

    private fun untypedStub(call: Call, function: FunctionId, site: CallSite): IrNode? {
        val first = call.arguments.firstOrNull()
        val creation by lazy(LazyThreadSafetyMode.NONE) { call.receiver?.let { stubs.creation(it, site.scope) } }
        return when (function.owner) {
            TemporalApi.ACTIVITY_STUB -> when (function.simpleName) {
                "execute", "executeAsync" -> {
                    val name = constants.text(first, site.scope) ?: return computedName(first)
                    val made = creation?.takeIf { it.kind == StubKind.UNTYPED_ACTIVITY || it.kind == StubKind.UNTYPED_LOCAL_ACTIVITY }
                    val options = made?.let { stubs.activityOptions(it, type = null, inForce = null) } ?: StubOptions()
                    IrNode(
                        id = "",
                        kind = if (made?.kind?.isLocalActivity == true) NodeKind.LOCAL_ACTIVITY else NodeKind.ACTIVITY,
                        name = name,
                        taskQueue = options.taskQueue,
                        timeout = options.timeout,
                    )
                }
                else -> null
            }
            TemporalApi.CHILD_WORKFLOW_STUB -> when (function.simpleName) {
                "execute", "executeAsync" -> {
                    // The workflow type was given when the stub was made.
                    val made = creation?.takeIf { it.kind == StubKind.UNTYPED_CHILD_WORKFLOW }
                    val name = made?.let { stubs.name(it) }
                        ?: return unknown(UnknownReasons.COMPUTED_NAME, label(function))
                    val options = stubs.options(made)
                    IrNode(
                        id = "",
                        kind = NodeKind.CHILD_WORKFLOW,
                        name = name,
                        taskQueue = options.taskQueue,
                        timeout = options.timeout,
                        parentClosePolicy = options.parentClosePolicy,
                    )
                }
                "signal" -> signal(first, site)
                else -> null
            }
            TemporalApi.EXTERNAL_WORKFLOW_STUB -> when (function.simpleName) {
                "signal" -> signal(first, site)
                "cancel" -> cancelExternal(call, site)
                else -> null
            }
            TemporalApi.NEXUS_SERVICE_STUB -> when (function.simpleName) {
                "execute", "executeAsync", "start" -> {
                    val operation = constants.text(first, site.scope) ?: return computedName(first)
                    val made = creation?.takeIf { it.kind == StubKind.UNTYPED_NEXUS_SERVICE }
                    IrNode(
                        id = "",
                        kind = NodeKind.NEXUS_OPERATION,
                        name = operation,
                        service = made?.let { stubs.name(it) },
                        timeout = made?.let(stubs::options)?.timeout,
                    )
                }
                else -> null
            }
            else -> null
        }
    }

    private fun signal(name: Argument?, site: CallSite): IrNode {
        val signalName = constants.text(name, site.scope) ?: return computedName(name)
        return IrNode(id = "", kind = NodeKind.SIGNAL_EXTERNAL, name = signalName)
    }

    /**
     * Another workflow is asked to cancel. Which workflow type it is of is known where the stub
     * was made from one of a workflow interface.
     */
    private fun cancelExternal(call: Call, site: CallSite): IrNode {
        val made = call.receiver?.let { tracer.origin(it, site.scope, settled = false) }
        val typed = (made?.code as? Call)?.takeIf { it.callee?.function?.name == TemporalApi.EXTERNAL_FROM_TYPED }?.arguments?.firstOrNull()?.value
        val type = typed?.let(::typeOf)?.takeIf { it.annotations.find(TemporalTypes.WORKFLOW_INTERFACE) != null }?.name
        return IrNode(id = "", kind = NodeKind.CANCEL_EXTERNAL, name = type?.let(names::workflowType))
    }

    /** The type of the value of [code], where the code says it. */
    private fun typeOf(code: Code): TypeRef? =
        when (code) {
            is Call -> code.type
            is PropertyRead -> code.type
            is LocalRead -> code.type
            else -> null
        }

    // ---- stubs of an interface

    /**
     * The node for a call of a method of an activity, workflow or Nexus service interface. The
     * interface is known from the type of the object the method is called on; where that object
     * came from is looked at only for what the type cannot say.
     */
    private fun stubMethod(callee: Callee, receiver: Code?, receiverType: TypeRef?, site: CallSite): IrNode? {
        val function = callee.function
        val interfaceName = receiverType?.name ?: return null
        // An extension function of a stub's interface is ordinary code, and so is what every object has.
        if (callee.kind != CalleeKind.FUNCTION || function.receiver != null) return null
        if (function.owner == null || function.owner in TemporalApi.ROOT_TYPES) return null

        val annotations = receiverType.annotations
        val creation by lazy(LazyThreadSafetyMode.NONE) {
            // `this`, written out inside a scope function, is the object that function was given.
            val stub = receiver?.takeUnless { it is ThisReference }?.let { Traced(it, site.scope) }
                ?: site.implicitReceiver(interfaceName)
                ?: receiver?.let { Traced(it, site.scope) }
            stub?.let { stubs.creation(it.code, it.scope) }
        }
        return when {
            annotations.find(TemporalTypes.ACTIVITY_INTERFACE) != null -> {
                val made = creation?.takeIf { it.kind.isTypedActivity }
                val type = names.activityType(interfaceName, function)
                val options = made?.let { stubs.activityOptions(it, type, site.options) } ?: StubOptions()
                IrNode(
                    id = "",
                    kind = if (made?.kind?.isLocalActivity == true) NodeKind.LOCAL_ACTIVITY else NodeKind.ACTIVITY,
                    name = type,
                    taskQueue = options.taskQueue,
                    timeout = options.timeout,
                )
            }
            annotations.find(TemporalTypes.WORKFLOW_INTERFACE) != null -> {
                val entryPoint = names.workflowEntryPoint(interfaceName, function) ?: return null
                when (entryPoint.kind) {
                    EntryPointKind.WORKFLOW_METHOD ->
                        if (creation?.kind == StubKind.CONTINUE_AS_NEW) {
                            IrNode(id = "", kind = NodeKind.CONTINUE_AS_NEW, name = entryPoint.name)
                        } else {
                            val options = creation?.takeIf { it.kind == StubKind.CHILD_WORKFLOW }?.let(stubs::options) ?: StubOptions()
                            IrNode(
                                id = "",
                                kind = NodeKind.CHILD_WORKFLOW,
                                name = entryPoint.name,
                                // The type alone names the workflow unless another interface of the sources is of that type too.
                                interfaceName = interfaceName.takeIf { names.isShared(entryPoint.name, it) },
                                taskQueue = options.taskQueue,
                                timeout = options.timeout,
                                parentClosePolicy = options.parentClosePolicy,
                            )
                        }
                    // The label says which workflow type the signal goes to; the name is the signal's alone.
                    EntryPointKind.SIGNAL -> IrNode(
                        id = "",
                        kind = NodeKind.SIGNAL_EXTERNAL,
                        name = entryPoint.name,
                        label = names.workflowType(interfaceName)?.let { "$it.${entryPoint.name}" },
                    )
                    // A workflow cannot query or update another one through a stub.
                    else -> null
                }
            }
            annotations.find(TemporalApi.NEXUS_SERVICE) != null -> {
                val options = creation?.takeIf { it.kind == StubKind.NEXUS_SERVICE }?.let(stubs::options) ?: StubOptions()
                IrNode(
                    id = "",
                    kind = NodeKind.NEXUS_OPERATION,
                    name = names.nexusOperation(interfaceName, function),
                    service = names.nexusService(receiverType),
                    timeout = options.timeout,
                )
            }
            else -> null
        }
    }

    // ---- functions of the sources

    /**
     * Whether a function is written out in the analysed sources. What the compiler generates for
     * a class of the sources, such as `copy`, is declared nowhere, and a constructor has no body
     * the analysis reads.
     */
    private fun isWritten(callee: Callee): Boolean =
        callee.inSource && callee.kind == CalleeKind.FUNCTION && backend.function(callee.function) != null

    /**
     * Whether a call of [callee] on an object of type [receiverType] is one made through a stub.
     * What runs then runs elsewhere, also where the call is no step of this workflow, as a query
     * of another workflow is not: the class that implements the interface is not this workflow's code.
     */
    private fun isStubMethod(callee: Callee, receiverType: TypeRef?): Boolean {
        val function = callee.function
        if (callee.kind != CalleeKind.FUNCTION || function.receiver != null || function.owner == null) return false
        return STUB_INTERFACES.any { receiverType?.annotations?.find(it) != null }
    }

    private fun computedName(name: Argument?): IrNode = unknown(UnknownReasons.COMPUTED_NAME, name?.text)

    private fun unknown(reason: String, label: String?): IrNode =
        IrNode(id = "", kind = NodeKind.UNKNOWN, reason = reason, label = label?.let(Captions::of))

    private fun FunctionId.parameterTypes(): List<String> =
        signature.removeSurrounding("(", ")").split(',').filter { it.isNotEmpty() }

    private companion object {
        /** The annotations of the interfaces that stubs are made of. */
        val STUB_INTERFACES = listOf(TemporalTypes.ACTIVITY_INTERFACE, TemporalTypes.WORKFLOW_INTERFACE, TemporalApi.NEXUS_SERVICE)

        const val SAGA_CONSTRUCTOR = "${TemporalApi.SAGA}.<init>"

        /** What the execution of a child workflow is called where it is read like a property of its stub. */
        const val EXECUTION = "execution"

        /** How many arguments a wait on a promise takes that is given a time to wait for at most: an amount and its unit. */
        const val TIMED = 2
        const val OPTIONAL_OF = "java.util.Optional.of"
        const val RETRY = "retry on failure"

        /** What the scope is called that the code around a call runs in, where the call names no other. */
        const val CURRENT_SCOPE = "current scope"

        /** What the name of everything in the Temporal SDK begins with. */
        const val TEMPORAL = "io.temporal."
    }
}
