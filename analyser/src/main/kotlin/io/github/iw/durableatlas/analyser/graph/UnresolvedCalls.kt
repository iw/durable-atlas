package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CallableReference
import io.github.iw.durableatlas.analyser.code.Callee
import io.github.iw.durableatlas.analyser.code.CalleeKind
import io.github.iw.durableatlas.analyser.code.ClassLiteral
import io.github.iw.durableatlas.analyser.code.ClassReference
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.ThisReference
import io.github.iw.durableatlas.analyser.code.TypeRef
import io.github.iw.durableatlas.analyser.code.Unresolved
import io.github.iw.durableatlas.analyser.code.children
import io.github.iw.durableatlas.analyser.code.find
import io.github.iw.durableatlas.analyser.code.walk
import io.github.iw.durableatlas.analyser.discovery.TemporalTypes
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.SkippedCall
import io.github.iw.durableatlas.ir.UnknownReasons

/** What a call that does not resolve is taken for, in a model whose classpath is known to be incomplete. */
internal sealed interface Unsettled {
    /** A step, or something that may be one: [node] stands for it, without id and location. */
    class Kept(val node: IrNode) : Unsettled

    /** One of these functions of the sources, which the compiler could not choose among. */
    class Sources(val functions: List<Callee>) : Unsettled

    /** A call into a library that is not on the classpath: no step, like any call of a library function. */
    data object Library : Unsettled
}

/** An object that is `this` for some code without being named there, of a type that could not be resolved. */
internal class Untyped(
    /** The expression that names the object, with the scope to read it in, where the code says it. */
    val value: Traced?,
    /** The name, as written, of the class the object is declared to be of, where it is declared with one. */
    val type: String? = null,
    /**
     * True where the object cannot be followed from here although a reading of the code that
     * knows where it is called from could: it is then taken for anything it may be.
     */
    val guessed: Boolean = false,
)

/**
 * Judges the calls that do not resolve in a degraded model. Such a model has the Temporal SDK,
 * its Kotlin extensions and the Kotlin standard library on its classpath and nothing else, and
 * its sources are the Kotlin and Java files of the repository's main source directories, so most
 * of what does not resolve is a call into some other library. A model made from the build has that library, and
 * its calls are calls of library functions: no step, and nothing that is followed. A call that
 * cannot be a step of the workflow is taken for that here as well, and noted (see [skipped]).
 *
 * A call is kept, as a node, wherever it may be more than that:
 * - It is made on a stub: the object it is called on, named or as the `this` of the code
 *   around it, can be followed to a call of `Workflow` that makes a stub, or to a call that
 *   was handed such a stub and may hand it back. The interface of the stub did not resolve,
 *   or the call would have. What is called on a stub made to continue as new continues as
 *   new, whatever the function; any other call through a stub is a step whose kind and name
 *   only the interface could say.
 * - It is made on an object that is declared to be of a class which did not resolve and which
 *   the sources hand to a maker of stubs somewhere, where the object itself cannot be followed.
 * - It names the Temporal API: one of the functions the compiler considered for it is of
 *   `io.temporal`, an import or a qualifier gives the name it begins with as one of
 *   `io.temporal`, the file imports a whole package of `io.temporal` and no import gives
 *   that name, or what it is called on is of a type of `io.temporal` or was made by such a call.
 * - It is made on an object of a stub interface that did resolve, where the function is
 *   one the interface gets from elsewhere.
 * - It is made on what an unresolved call gave back that was handed a stub interface which
 *   has a function of that name: a library that makes stubs.
 * - The compiler names functions of the analysed sources for it and could not choose among
 *   them: one of those runs.
 *
 * Where the compiler names functions for a call that all read alike, the call is read as any
 * of them would be (see [CallRecognizer]): functions of the Temporal API as what they are, and
 * functions that run a lambda for each element as a loop.
 */
internal class UnresolvedCalls(
    private val backend: SourceBackend,
    private val tracer: ValueTracer,
    private val stubs: Stubs,
    /**
     * Gives the names, as written, of the classes that did not resolve and that the sources
     * hand to a call of `Workflow` which makes a stub of an interface; see [stubClasses]. An
     * object declared to be of such a class may be such a stub.
     */
    private val stubClasses: () -> Set<String>,
) {
    private val taken = LinkedHashSet<SkippedCall>()
    private var recording = true

    /** The calls taken for calls into a library so far, each once, in the order they were met. */
    val skipped: List<SkippedCall> get() = taken.toList()

    /** Judges what [block] asks about without noting it: code that is looked at and may never run. */
    fun <T> unrecorded(block: () -> T): T {
        val outer = recording
        recording = false
        try {
            return block()
        } finally {
            recording = outer
        }
    }

    /** What [call], which did not resolve, is taken for. */
    fun judge(call: Call, site: CallSite): Unsettled {
        val verdict = verdict(call, site)
        if (verdict == Unsettled.Library && recording) taken += SkippedCall(call.location, Captions.of(Texts.of(call)))
        return verdict
    }

    /**
     * The node that a reference which names nothing the compiler knows stands for, where it
     * is handed to something that will call it: a function of a stub, as for a call.
     */
    fun judge(reference: CallableReference, site: CallSite): IrNode? {
        val receiver = reference.receiver ?: return null
        val name = reference.text.substringAfterLast("::")
        return (onObject(receiver, reference.receiverType?.unresolved, name, site) as? Unsettled.Kept)?.node
    }

    private fun verdict(call: Call, site: CallSite): Unsettled {
        val evidence = call.unresolved ?: Unresolved()
        val written = evidence.candidates.filter { it.inSource && it.kind == CalleeKind.FUNCTION }
        if (written.isNotEmpty()) return Unsettled.Sources(written)
        temporal(call, evidence)?.let { return kept(it) }

        val receiver = call.receiver
        if (receiver is ClassReference) return Unsettled.Library
        val type = receiver?.let(::typeOf)
        if (type?.name != null) {
            // The interface resolved and the function did not: one it is given by an interface that is missing.
            if (isStubInterface(type)) return kept("${type.name.substringAfterLast('.')}.${call.name}")
            return Unsettled.Library
        }
        if (receiver != null && receiver !is ThisReference) return onObject(receiver, evidence.receiverType, call.name, site)
        // Without a name for it, the object is whatever `this` stands for around the call.
        for (implicit in site.untyped()) {
            val found = onObject(implicit, call.name, site)
            if (found is Unsettled.Kept) return found
        }
        return Unsettled.Library
    }

    /** A call of [name] on what [receiver] evaluates to, an object of a type that did not resolve. */
    private fun onObject(receiver: Code, declared: String?, name: String, site: CallSite): Unsettled {
        // What a lambda is given that the reading binds and this place cannot may be anything.
        val guessed = receiver is LocalRead && site.unseen(receiver.name)
        return onObject(Untyped(Traced(receiver, site.tracing), declared, guessed), name, site)
    }

    private fun onObject(target: Untyped, name: String, site: CallSite): Unsettled {
        val origin = target.value?.let { made(it, name, 0) } ?: Origin.Unknown
        return when (origin) {
            is Origin.Stub -> Unsettled.Kept(stubCall(origin.creation, target.value, name))
            is Origin.Named -> kept(origin.label)
            Origin.Temporal -> kept(listOfNotNull(target.value?.code?.let(Texts::of)?.ifEmpty { null }, name).joinToString("."))
            Origin.Other -> Unsettled.Library
            Origin.Unknown -> when {
                target.type != null && target.type in stubClasses() -> kept("${target.type}.$name")
                target.guessed -> kept(name)
                else -> Unsettled.Library
            }
        }
    }

    // ---- where an object came from

    /** What is known of the making of an object. */
    private sealed interface Origin {
        /** A stub: the call of `Workflow` that made it was found. */
        class Stub(val creation: StubCreation) : Origin

        /** What a library gave back that was handed a stub interface: [label] says what the call is then. */
        class Named(val label: String) : Origin

        /** What a call that names the Temporal API and did not resolve gave back. */
        data object Temporal : Origin

        /** Something else that is known: what a library gave back, an object that was constructed, a value. */
        data object Other : Origin

        /** Nothing says what it is: a parameter, a property that is given its value elsewhere. */
        data object Unknown : Origin
    }

    /** @param name the function that is called on the object, for what is found to say */
    private fun made(value: Traced, name: String, depth: Int): Origin {
        stubs.creation(value.code, value.scope)?.let { return Origin.Stub(it) }
        val origin = tracer.origin(value.code, value.scope, settled = false)
        return when (val code = origin.code) {
            is Call -> madeBy(code, origin.scope, name)
            is LocalRead, is ThisReference -> Origin.Unknown
            is PropertyRead -> when {
                code.property != null -> Origin.Unknown
                // What an object holds is of the same making as the object: a library's, or nobody knows whose.
                code.receiver != null && depth < MAX_DEPTH ->
                    when (val holder = made(Traced(code.receiver, origin.scope), name, depth + 1)) {
                        is Origin.Stub -> Origin.Other
                        else -> holder
                    }
                else -> Origin.Unknown
            }
            else -> holding(code, origin.scope) ?: Origin.Other
        }
    }

    /** What an object is that the call [maker] gave back. */
    private fun madeBy(maker: Call, scope: Scope, name: String): Origin {
        val callee = maker.callee
        if (callee == null) {
            val evidence = maker.unresolved ?: Unresolved()
            if (evidence.candidates.any { it.inSource }) return Origin.Unknown
            if (temporal(maker, evidence) != null) return Origin.Temporal
            stubInterface(maker, name)?.let { return Origin.Named(it) }
            return holding(maker, scope) ?: Origin.Other
        }
        if (callee.kind == CalleeKind.CONSTRUCTOR) return Origin.Other
        holding(maker, scope)?.let { return it }
        // What a function of the sources gives back that could not be followed is anybody's guess.
        return if (callee.inSource) Origin.Unknown else Origin.Other
    }

    /**
     * A stub among what [code] is made of, apart from the code handed over in it: a call that
     * is given a stub may give it back, and so may an operator.
     */
    private fun holding(code: Code, scope: Scope): Origin? {
        var looked = 0
        fun search(part: Code): Origin? {
            if (part is Lambda || looked++ > MAX_PARTS) return null
            if (part !== code && (part is Call || part is LocalRead || part is PropertyRead)) {
                stubs.creation(part, scope)?.let { return Origin.Stub(it) }
            }
            return part.children().firstNotNullOfOrNull(::search)
        }
        return search(code)
    }

    /**
     * What a call of [name] is called on what [maker] gave back, where [maker] was handed a
     * stub interface that has a function of that name: as a class literal, or as a type argument.
     */
    private fun stubInterface(maker: Call, name: String): String? {
        val literals = maker.arguments.flatMap { argument -> argument.value.walk().filterIsInstance<ClassLiteral>().mapNotNull { it.type } }
        return (maker.typeArguments + literals).firstNotNullOfOrNull { type ->
            val interfaceName = type.name?.takeIf { isStubInterface(type) } ?: return@firstNotNullOfOrNull null
            val types = listOf(interfaceName) + backend.findClass(interfaceName)?.allSuperTypes.orEmpty()
            "${interfaceName.substringAfterLast('.')}.$name".takeIf { types.any { each -> backend.functions(each).any { it.name == name } } }
        }
    }

    // ---- what a call names

    /**
     * What an unresolved call is called if it names the Temporal API: by a function the
     * compiler considered for it, by what its imports or a qualifier say of its first name, by
     * the class it is called on, or by the type of the object it is called on.
     */
    private fun temporal(call: Call, evidence: Unresolved): String? {
        evidence.candidates.firstOrNull { isTemporal(it.function.name) }?.let { candidate ->
            return listOfNotNull(candidate.function.owner?.substringAfterLast('.'), candidate.function.simpleName).joinToString(".")
        }
        val receiver = call.receiver
        if (evidence.names.any(::isTemporal)) {
            val before = (receiver as? PropertyRead)?.takeIf { it.property == null }?.name
            return listOfNotNull(before, call.name).joinToString(".")
        }
        if (receiver is ClassReference) {
            return receiver.name?.takeIf(::isTemporal)?.let { "${it.substringAfterLast('.')}.${call.name}" }
        }
        return receiver?.let(::typeOf)?.name?.takeIf(::isTemporal)?.let { "${it.substringAfterLast('.')}.${call.name}" }
    }

    private fun isTemporal(name: String): Boolean = name.startsWith(TEMPORAL)

    private fun isStubInterface(type: TypeRef): Boolean = STUB_INTERFACES.any { type.annotations.find(it) != null }

    /** The type of the value of [code], where the code says it. */
    private fun typeOf(code: Code): TypeRef? =
        when (code) {
            is Call -> code.type
            is PropertyRead -> code.type
            is LocalRead -> code.type
            is ThisReference -> code.type
            else -> null
        }

    // ---- what a call through a stub is

    /**
     * The node for a call of [name] through the stub of [creation], whose interface did not
     * resolve. The SDK runs any function that is called on a stub made to continue as new as
     * the workflow to continue as: the workflow ends there, and only its type is not known.
     * Through any other stub the interface alone says what the function is (which activity,
     * the workflow itself or a signal to it) and what it is called.
     */
    private fun stubCall(creation: StubCreation, receiver: Traced?, name: String): IrNode {
        if (creation.kind == StubKind.CONTINUE_AS_NEW) return IrNode(id = "", kind = NodeKind.CONTINUE_AS_NEW)
        val on = interfaceOf(creation.call)?.let { it.unresolved ?: it.name?.substringAfterLast('.') }
            ?: receiver?.code?.let(Texts::of)?.ifEmpty { null }
        return unknown(listOfNotNull(on, name).joinToString("."))
    }

    private fun kept(label: String): Unsettled = Unsettled.Kept(unknown(label))

    private fun unknown(label: String): IrNode =
        IrNode(id = "", kind = NodeKind.UNKNOWN, reason = UnknownReasons.UNRESOLVED, label = Captions.of(label))

    companion object {
        /**
         * The names, as written, of the classes that did not resolve and that the sources of
         * [backend] hand to a call of `Workflow` which makes a stub of an interface.
         */
        fun stubClasses(backend: SourceBackend): Set<String> {
            val names = HashSet<String>()
            for (block in backend.bodiesMentioning(MAKER)) {
                for (made in block.walk().filterIsInstance<Call>()) interfaceOf(made)?.unresolved?.let(names::add)
            }
            return names
        }

        /** The interface that [maker] is handed to make a stub of, if it is a call that makes one of an interface. */
        private fun interfaceOf(maker: Call): TypeRef? {
            if (StubKind.of(maker, lenient = true)?.hasInterface != true) return null
            return maker.arguments.firstOrNull()?.value?.walk()?.filterIsInstance<ClassLiteral>()?.firstOrNull()?.type
        }

        /** What the name of every function of `Workflow` that makes a stub holds. */
        private const val MAKER = "Stub"
        private const val TEMPORAL = "io.temporal."

        /** How far an object is followed through the objects that hold it. */
        private const val MAX_DEPTH = 8

        /** How much of an expression is searched for a stub. */
        private const val MAX_PARTS = 64

        /** The annotations of the interfaces that stubs are made of. */
        private val STUB_INTERFACES = listOf(TemporalTypes.ACTIVITY_INTERFACE, TemporalTypes.WORKFLOW_INTERFACE, TemporalApi.NEXUS_SERVICE)
    }
}
