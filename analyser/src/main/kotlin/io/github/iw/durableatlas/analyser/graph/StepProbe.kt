package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Block
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CallableReference
import io.github.iw.durableatlas.analyser.code.Catch
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.Other
import io.github.iw.durableatlas.analyser.code.PropertyId
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.PropertyWrite
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.Throw
import io.github.iw.durableatlas.analyser.code.Try
import io.github.iw.durableatlas.analyser.code.children
import io.github.iw.durableatlas.analyser.code.walk

/** A failure that may come out of a piece of code: the type thrown, and what it is called if nothing catches it. */
internal data class Thrown(
    /** Fully qualified name of the type thrown; `null` if it is not known. */
    val type: String?,
    /** What the failure is called; `null` if nothing says. */
    val name: String?,
    /**
     * Whether the failure may fail the execution of a workflow it leaves (see
     * [FailureTypes.failsExecution]); one that cannot fails the workflow task.
     */
    val real: Boolean = true,
)

/**
 * What a piece of code holds, itself and through the functions of the sources it calls, as far
 * as it matters to a graph or to the reading of the code around it.
 */
internal class Summary(
    /**
     * Whether a step of the workflow is certain to lie on some path through the code: a call
     * that is a step, a compensation that is registered or run, a handler that is registered, or
     * a call that is not followed and stands for one of these.
     */
    val certain: Boolean,
    /** Whether the code assigns to a property, or sets options for the activities of the workflow. */
    val writes: Boolean,
    /** Whether the code reads a function it was handed: it may run it, hand it on or keep it. */
    val handed: Boolean,
    /** The failures that may come out of the code: what it throws and no clause of its own is sure to catch. */
    val raises: Set<Thrown>,
    /** The functions without a body that the code may call and that no class of the sources implements. */
    val unseen: Set<String>,
) {
    /** Whether the code holds nothing at all of what a summary notes. */
    val isInert: Boolean get() = !certain && !writes && !handed && !mayMatter

    /** Whether something may happen in the code that shows in a graph although no step is certain: a failure, or a call that cannot be followed. */
    val mayMatter: Boolean get() = raises.isNotEmpty() || unseen.isNotEmpty()

    operator fun plus(other: Summary): Summary =
        when {
            other === NOTHING -> this
            this === NOTHING -> other
            else -> Summary(certain || other.certain, writes || other.writes, handed || other.handed, raises + other.raises, unseen + other.unseen)
        }

    companion object {
        val NOTHING = Summary(certain = false, writes = false, handed = false, raises = emptySet(), unseen = emptySet())
    }
}

/**
 * Tells of a function of the sources what it holds that could show in a graph, itself and
 * through the functions it calls: see [Summary]. The answer is worked out once for each function,
 * without regard to where it is called from and however deep the calls go.
 *
 * It tells apart what is certain from what is only possible. A step is certain where a call is
 * one. A `throw`, or a call of a function that no class of the sources implements, is no step:
 * it says that the function may fail, or that something may lie behind it that cannot be seen.
 * Only a function with a certain step is worth drawing from the inside; of any other, the place
 * where it is called says what may matter.
 *
 * It is asked wherever a call is met: whether to read the function at all, whether a call that
 * is cut off or may run one of several functions needs a node that says so, and what to say of
 * a function that is not drawn.
 */
internal class StepProbe(
    private val backend: SourceBackend,
    private val recognizer: CallRecognizer,
    private val dispatch: Dispatch,
    private val failures: FailureTypes,
) {
    private val known = HashMap<FunctionId, Summary>()

    /** Answers that rest on a guess, kept while the question they were worked out for is open: see [summary]. */
    private val provisional = HashMap<FunctionId, Summary>()
    private val open = HashSet<FunctionId>()

    /** True while an answer rests on the guess made for a function that is still being looked at. */
    private var guessed = false

    private val nowhere = CallSite(Scope()) { null }

    /** What [function] holds, itself and behind the calls it makes. */
    fun summary(function: FunctionDecl): Summary = summary(function.id, function) { backend.body(function.id) }

    /** What the getter of [property] holds; see [summary]. */
    fun summary(property: PropertyId, getter: Block): Summary = summary(getterId(property), null) { getter }

    /**
     * What lies behind a call that runs [target]. Where it may run one of several functions, one
     * of them does: a step is certain if one of them holds one, since a node then stands for the
     * call, and what may happen in any of them may happen. Where nothing says what runs, the
     * function called is one that cannot be followed.
     */
    fun summary(target: Target?): Summary =
        when (target) {
            null -> Summary.NOTHING
            is Target.One -> summary(target.function)
            is Target.Several -> summary(target.functions)
            is Target.Unknown -> Summary(certain = false, writes = false, handed = false, raises = emptySet(), unseen = setOf(target.function.id.toString()))
        }

    /** What a call holds that may run any of [functions], where each of them is a function with a body. */
    fun summary(functions: List<FunctionDecl>): Summary = functions.fold(Summary.NOTHING) { all, function -> all + summary(function) }

    /**
     * What [code] holds that is no function of its own: a lambda, where it is written.
     *
     * @param site where the code stands, for the calls in it
     * @param handed whether the local name stands for code that was handed in and is certain to hold a step
     */
    fun summary(code: Code, site: CallSite, handed: (name: String) -> Boolean): Summary =
        Finding().also { scan(code, Looking(site, site.scope, handed), it, emptyList()) }.summary()

    /** What a scan is looking at: where its calls stand, and what its local names say. */
    private class Looking(
        val site: CallSite,
        /** The scope to follow a thrown value in, for what the failure is called. */
        val naming: Scope,
        val handed: (name: String) -> Boolean = { false },
        /**
         * The names of what the `catch` clauses around the code have caught, each with whether
         * a failure that fails a workflow may have got to its clause.
         */
        val caught: Map<String, Boolean> = emptyMap(),
    ) {
        /** For the body of [clause], which a failure that fails a workflow may get to or not. */
        fun catching(clause: Catch, real: Boolean): Looking =
            if (clause.parameter == null) this else Looking(site, naming, handed, caught + (clause.parameter to real))
    }

    /** The clauses of a `try` around the code that is looked at, and what gets to them. */
    private class Guard(val clauses: List<Catch>) {
        /** For each clause, whether a failure that fails a workflow is known to get to it. */
        val reached = BooleanArray(clauses.size)
    }

    private class Finding {
        var certain = false
        var writes = false
        var handed = false
        val raises = LinkedHashSet<Thrown>()
        val unseen = LinkedHashSet<String>()

        fun summary(): Summary =
            if (!certain && !writes && !handed && raises.isEmpty() && unseen.isEmpty()) Summary.NOTHING else Summary(certain, writes, handed, raises, unseen)
    }

    /**
     * Where the calls of a body are looked at from. Nothing is known of where the function is
     * called from, so the local names of the body are all there is to follow an object by, and
     * that only matters for a call that does not resolve: what it is made on decides whether it
     * may be a step. Where an object cannot be followed here although a reading of the function
     * could follow it, it is taken for anything it may be.
     *
     * @param function the function the body is of, if it is one
     */
    private fun site(function: FunctionDecl?, body: Block, scope: Scope): CallSite {
        if (!recognizer.lenient) return nowhere
        val calls = body.walk().filterIsInstance<Call>().toList()
        // What a scope function makes `this` in its lambda, where the type of that object did not resolve.
        val subjects = calls.mapNotNull { call ->
            val name = call.callee?.function?.name?.takeIf { it in TemporalApi.RECEIVER_SCOPE_FUNCTIONS } ?: return@mapNotNull null
            if (call.typeArguments.firstOrNull()?.name != null) return@mapNotNull null
            val subject = if (name == WITH) call.arguments.firstOrNull()?.value else call.receiver
            subject?.let { Untyped(Traced(it, scope), guessed = true) }
        }
        // The object an extension function runs on is whatever it is called on.
        val extended = function?.receiver?.takeIf { it.name == null }?.let { Untyped(null, it.unresolved) }
        // What `let` and `also` hand to their lambda is the object they are called on.
        val handed = calls.filter { it.callee?.function?.name in HAND_ON_RECEIVER }.flatMap { call ->
            call.arguments.mapNotNull { it.value as? Lambda }.map { it.parameters.firstOrNull() ?: IMPLICIT_PARAMETER }
        }.toSet()
        return CallSite(Scope(), tracing = scope, untyped = { subjects + listOfNotNull(extended) }, unseen = handed::contains) { null }
    }

    /**
     * The summary of the function or getter [id]. A function that leads back to itself adds
     * nothing to what it holds otherwise, so it is taken to hold nothing while it is being
     * looked at. An answer that rests on such a guess is only final for the function the
     * question began with, which has seen everything; for the others it holds until that
     * question is settled, and is worked out afresh when they are asked about themselves.
     */
    private fun summary(id: FunctionId, function: FunctionDecl?, body: () -> Block?): Summary {
        known[id]?.let { return it }
        provisional[id]?.let {
            guessed = true
            return it
        }
        if (!open.add(id)) {
            guessed = true
            return Summary.NOTHING
        }
        val outer = guessed
        guessed = false
        val found = body()?.let { block ->
            val scope = Scope.of(block)
            Finding().also { scan(block, Looking(site(function, block, scope), scope), it, emptyList()) }.summary()
        } ?: Summary.NOTHING
        open.remove(id)
        val final = !guessed || open.isEmpty()
        if (final) known[id] = found else provisional[id] = found
        if (open.isEmpty()) provisional.clear()
        guessed = outer || !final
        return found
    }

    /**
     * @param guards the clauses of the `try`s around [code], outermost first: a failure that one
     *   of them is sure to catch does not come out
     */
    private fun scan(code: Code, looking: Looking, found: Finding, guards: List<Guard>) {
        when (code) {
            is Try -> {
                val guard = Guard(code.catches)
                val unseen = found.unseen.size
                scan(code.body, looking, found, guards + guard)
                // A step fails with a failure of Temporal, and what cannot be followed or was handed in may fail with anything.
                val open = found.certain || found.handed || found.unseen.size > unseen || failures.mayThrowUnseen(code.body)
                // A clause runs when the body fails, which any code may: what it throws comes out.
                code.catches.forEachIndexed { index, clause -> scan(clause.body, looking.catching(clause, open || guard.reached[index]), found, guards) }
                code.finally?.let { scan(it, looking, found, guards) }
                return
            }
            is Throw -> raise(thrown(code, looking), found, guards)
            is PropertyWrite -> found.writes = true
            is Call -> call(code, looking, found, guards)
            is CallableReference -> reference(code, looking, found, guards)
            is PropertyRead -> code.property?.let { getter(it, found, guards) }
            // Code that was handed in may be run or handed on: what it holds is for the place to say that wrote it.
            is LocalRead -> if (code.type?.name?.startsWith(TemporalApi.FUNCTION_TYPE) == true) {
                found.handed = true
                if (looking.handed(code.name)) found.certain = true
            }
            else -> Unit
        }
        code.children().forEach { scan(it, looking, found, guards) }
    }

    /**
     * What a `throw` raises. What is thrown again as a clause caught it fails a workflow only
     * if something that does may have got to the clause.
     */
    private fun thrown(code: Throw, looking: Looking): Thrown {
        val site = CallSite(looking.naming) { null }
        val thrower = recognizer.thrower(code, site)
        val real = failures.failsExecution(thrower.type, thrower.exact) && thrower.variable?.let { looking.caught[it] } != false
        return Thrown(code.type?.name, recognizer.failureName(code.value, code.type, site), real)
    }

    /** Notes that [thrown] may be raised where the clauses [guards] stand around it. */
    private fun raise(thrown: Thrown, found: Finding, guards: List<Guard>) {
        for (guard in guards.asReversed()) {
            val caught = guard.clauses.map { failures.match(thrown.type, it.type?.name) }
            if (thrown.real) caught.forEachIndexed { index, by -> if (by != Caught.NEVER) guard.reached[index] = true }
            if (Caught.ALWAYS in caught) return
        }
        found.raises += thrown
    }

    private fun call(call: Call, looking: Looking, found: Finding, guards: List<Guard>) {
        val recognised = recognizer.call(call, looking.site)
        val isStep = recognised.node != null ||
            recognised.effect == Effect.COMPENSATE ||
            // A handler registered in a function is an entry point of the workflow: the function has to be read.
            recognised.effect == Effect.LISTEN ||
            recognised.deferred == Deferred.COMPENSATION
        if (isStep) {
            found.certain = true
            return
        }
        // What a function sets for the activities of the workflow counts where it is called, so it has to be read.
        if (recognised.options != null) found.writes = true
        val callee = recognised.target
        val raises = when {
            // One of the functions the compiler could not choose among runs.
            recognised.candidates.isNotEmpty() -> recognised.candidates.map { take(dispatch.resolve(it.function, null, exact = false), found, guards) }.any { it }
            callee == null -> false
            isSuper(call.receiver) -> take(dispatch.static(callee.function), found, guards)
            else -> take(dispatch.resolve(callee.function, call.receiverType?.name, exact = false), found, guards)
        }
        // A call that never returns fails, whatever it is; a function of the sources says itself with what.
        if (call.type?.name == NOTHING && !raises) {
            val thrown = TemporalApi.thrownBy(call)
            raise(Thrown(thrown, null, failures.failsExecution(thrown, exact = true)), found, guards)
        }
    }

    private fun reference(reference: CallableReference, looking: Looking, found: Finding, guards: List<Guard>) {
        if (recognizer.reference(reference, looking.site) != null) {
            found.certain = true
            return
        }
        val callee = recognizer.target(reference)
        if (callee == null) {
            reference.property?.let { getter(it, found, guards) }
            return
        }
        take(dispatch.resolve(callee.function, reference.receiverType?.name, exact = false), found, guards)
    }

    /**
     * Takes what a call that runs [target] holds as part of what the calling code holds. What
     * the function called does with code it is handed is said where that code is written.
     *
     * @return whether a failure may come out of what runs
     */
    private fun take(target: Target?, found: Finding, guards: List<Guard>): Boolean {
        val summary = summary(target)
        found.certain = found.certain || summary.certain
        found.writes = found.writes || summary.writes
        found.unseen += summary.unseen
        summary.raises.forEach { raise(it, found, guards) }
        return summary.raises.isNotEmpty()
    }

    private fun getter(property: PropertyId, found: Finding, guards: List<Guard>) {
        val getter = backend.getter(property) ?: return
        val summary = summary(getterId(property), null) { getter }
        found.certain = found.certain || summary.certain
        found.writes = found.writes || summary.writes
        found.unseen += summary.unseen
        summary.raises.forEach { raise(it, found, guards) }
    }

    companion object {
        private const val NOTHING = "kotlin.Nothing"
        private const val WITH = "kotlin.with"
        private const val IMPLICIT_PARAMETER = "it"

        /** Functions that hand what they are called on to their lambda as its parameter. */
        private val HAND_ON_RECEIVER = setOf("kotlin.also", "kotlin.let")

        /** The name the getter of a property goes by among the functions that are followed. */
        fun getterId(property: PropertyId): FunctionId = FunctionId("${property.name}.<get>", property.owner, "()", file = property.file)

        /** Whether [function] is the `invoke` of a function type: the call of a lambda or a reference kept in a variable. */
        fun isInvocation(function: FunctionId): Boolean =
            function.simpleName == "invoke" && function.owner?.startsWith(TemporalApi.FUNCTION_TYPE) == true

        /** Whether [receiver] is `super`: a call on it names the function that runs. */
        fun isSuper(receiver: Code?): Boolean = receiver is Other && receiver.operator == null && receiver.text.startsWith("super")
    }
}
