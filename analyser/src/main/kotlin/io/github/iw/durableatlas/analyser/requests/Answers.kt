package io.github.iw.durableatlas.analyser.requests

import io.github.iw.durableatlas.analyser.code.Block
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CallableReference
import io.github.iw.durableatlas.analyser.code.CalleeKind
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.If
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.LocalVariable
import io.github.iw.durableatlas.analyser.code.LocalWrite
import io.github.iw.durableatlas.analyser.code.Other
import io.github.iw.durableatlas.analyser.code.PropertyWrite
import io.github.iw.durableatlas.analyser.code.Return
import io.github.iw.durableatlas.analyser.code.Throw
import io.github.iw.durableatlas.analyser.code.Try
import io.github.iw.durableatlas.analyser.code.When
import io.github.iw.durableatlas.analyser.code.walk
import io.github.iw.durableatlas.ir.Payload
import io.github.iw.durableatlas.ir.PayloadFormat
import io.github.iw.durableatlas.ir.PayloadKind

/**
 * Follows the answer to a request forward from the call that sends it, to find out what the code
 * makes of it. Where a value goes is read from the code it is written in: the variable it is
 * kept in and each later read of that variable; the call it is handed to; the `return` that hands
 * it back to the code that called the function, whose frame the reading came down through. A call
 * of a function of the sources it is handed to is followed into, as the requests are read: within
 * the depth, to one function only, never into one the reading is already in.
 *
 * Every way the answer goes ends in one of four ways: it is read into an object of a type by
 * Jackson or kotlinx.serialization; it is kept or handed back as text; it is dropped; or it goes
 * where it cannot be followed. Together they say what the code makes of the answer: the type
 * where the ways that read it agree on one, else unknown where one was lost or several types were
 * read, else text where it was kept as text, else not read.
 *
 * @param enter the frame of the function a call runs, where it is followed into; `null` where it is not
 */
internal class Answers(
    private val parents: Parents,
    private val codecs: Codecs,
    private val types: StaticTypes,
    private val enter: (Call, Frame) -> Frame?,
) {
    /** What the reading holds at a point: a future of the response, the response, or its body. */
    enum class State { FUTURE, FUTURE_BODY, RESPONSE, BODY }

    private sealed interface End {
        data class Read(val type: String?, val format: PayloadFormat) : End

        data object Text : End

        data object Dropped : End

        data object Lost : End
    }

    /**
     * What the code makes of what [start] evaluates to, in [frame]: a response, its future or its
     * body, as [state] says. [text] tells whether the body is text, so that keeping it is keeping text.
     */
    fun of(start: Code, frame: Frame, state: State, text: Boolean): Payload {
        val ends = ArrayList<End>()
        Way(ends, text).go(start, frame, state)
        val read = ends.filterIsInstance<End.Read>().distinct()
        val formats = read.map { it.format }.distinct()
        val format = formats.singleOrNull()
        return when {
            read.isNotEmpty() && read.mapNotNull { it.type }.distinct().size == 1 && read.all { it.type != null } ->
                Payload(PayloadKind.TYPE, read.first().type, format = format)
            read.isNotEmpty() -> Payload(PayloadKind.UNKNOWN, format = format)
            End.Lost in ends -> Payload(PayloadKind.UNKNOWN)
            End.Text in ends -> Payload(PayloadKind.TEXT)
            else -> Payload(PayloadKind.NOT_READ)
        }
    }

    /** Whether the value of [call] is dropped where it is made: a statement of its own. */
    fun dropped(call: Call, frame: Frame): Boolean {
        val parent = parents.of(frame.body, call) as? Block ?: return false
        if (parent === frame.body) return true
        val holder = parents.of(frame.body, parent)
        return !(parent.statements.lastOrNull() === call && (holder is Lambda && holder.yieldsValue || holder is If || holder is When || holder is Try))
    }

    private inner class Way(private val ends: MutableList<End>, private val text: Boolean) {
        private val seen = HashSet<Visit>()
        private var steps = 0

        fun go(value: Code, frame: Frame, state: State) {
            if (!seen.add(Visit(value, frame, state))) return
            if (++steps > MAX_STEPS) {
                ends += End.Lost
                return
            }
            when (val parent = parents.of(frame.body, value)) {
                null -> ends += End.Lost
                is Block -> statement(parent, value, frame, state)
                is LocalVariable -> reads(parent.name, parent, frame, state)
                is LocalWrite -> reads(parent.name, parent, frame, state)
                is Return -> returned(frame, state)
                is PropertyWrite -> ends += kept(state)
                is Throw -> ends += kept(state)
                is Call -> call(parent, value, frame, state)
                is Other -> when (parent.operator) {
                    "as", "as?", "!!", "?:" -> go(parent, frame, state)
                    "template", "+" -> ends += kept(state)
                    "==", "!=", "is", "!is", "in", "!in" -> ends += End.Dropped
                    else -> ends += End.Lost
                }
                is If -> if (parent.condition === value) ends += End.Dropped else go(parent, frame, state)
                is When -> if (parent.subject === value) ends += End.Lost else go(parent, frame, state)
                is Try -> go(parent, frame, state)
                else -> ends += End.Lost
            }
        }

        /** A value that is kept somewhere the reading does not follow: text, if it is the body as text. */
        private fun kept(state: State): End = if (state == State.BODY && text) End.Text else End.Lost

        /** [value] is a statement of [block]: dropped, unless it is what the block evaluates to where that is used. */
        private fun statement(block: Block, value: Code, frame: Frame, state: State) {
            if (block.statements.lastOrNull() !== value || block === frame.body) {
                ends += End.Dropped
                return
            }
            when (val holder = parents.of(frame.body, block)) {
                is Lambda -> if (holder.yieldsValue) go(holder, frame, state) else ends += End.Dropped
                is If, is When, is Try -> go(holder, frame, state)
                else -> ends += End.Dropped
            }
        }

        /** Each read of the variable [name] after [declared], or of the parameter where [declared] is `null`. */
        private fun reads(name: String, declared: Code?, frame: Frame, state: State) {
            var after = declared == null
            val reads = ArrayList<LocalRead>()
            val skip = declared?.walk()?.toHashSet()
            for (code in frame.body.walk()) {
                if (!after) {
                    if (code === declared) after = true
                    continue
                }
                if (skip != null && code in skip) continue
                if (code is LocalRead && code.name == name) reads += code
            }
            if (reads.isEmpty()) ends += End.Dropped
            for (read in reads) go(read, frame, state)
        }

        /** The function hands the value back: on to the code that called it, or out of the activity. */
        private fun returned(frame: Frame, state: State) {
            val call = frame.call
            val caller = frame.caller
            if (call != null && caller != null) go(call, caller, state) else ends += kept(state)
        }

        private fun call(call: Call, value: Code, frame: Frame, state: State) {
            if (call.receiver === value) {
                member(call, frame, state)
                return
            }
            val argument = call.arguments.firstOrNull { it.value === value }
            if (argument == null) {
                ends += End.Lost
                return
            }
            if (value is Lambda || value is CallableReference) {
                // The value of the lambda is what `let`, `run` and `use` give.
                val name = call.callee?.function?.simpleName ?: call.name
                if (value is Lambda && name in GIVES_LAMBDA && call.arguments.last() === argument) go(call, frame, state) else ends += End.Lost
                return
            }
            codecs.reader(call)?.let { format ->
                if (state == State.BODY) {
                    val type = codecs.readType(call, frame.scope, codecs.expected(parents.of(frame.body, call), frame.place))
                    ends += End.Read(types.name(type, frame.place, call.location.file)?.takeIf { type != null && types.telling(type) }, format)
                } else {
                    ends += End.Lost
                }
                return
            }
            val callee = call.callee
            when {
                callee == null -> ends += if (call.name in NEUTRAL) End.Dropped else End.Lost
                callee.function.name in TEXT_OF_BYTES && state == State.BODY -> go(call, frame, state)
                callee.inSource && callee.kind == CalleeKind.FUNCTION -> into(call, argument.parameter, frame, state)
                callee.inSource -> ends += kept(state)
                callee.function.simpleName in NEUTRAL -> ends += End.Dropped
                else -> ends += End.Lost
            }
        }

        /** [value] is handed to a function of the sources as [parameter]: each read of it there. */
        private fun into(call: Call, parameter: String?, frame: Frame, state: State) {
            val inner = parameter?.let { enter(call, frame) }
            if (inner == null) {
                ends += End.Lost
                return
            }
            reads(parameter, null, inner, state)
        }

        /** A function called on the value. */
        private fun member(call: Call, frame: Frame, state: State) {
            val name = call.callee?.function?.simpleName ?: call.name
            if (name in SCOPE_FUNCTIONS) {
                scoped(call, name, frame, state)
                return
            }
            when (state) {
                State.FUTURE -> when (name) {
                    "join", "get", "await" -> go(call, frame, State.RESPONSE)
                    "thenApply", "thenApplyAsync" -> if (givesBody(call)) go(call, frame, State.FUTURE_BODY) else ends += End.Lost
                    else -> ends += End.Lost
                }
                State.FUTURE_BODY -> when (name) {
                    "join", "get", "await" -> go(call, frame, State.BODY)
                    else -> ends += End.Lost
                }
                State.RESPONSE -> when (name) {
                    "body" -> go(call, frame, State.BODY)
                    in RESPONSE_PARTS -> ends += End.Dropped
                    else -> ends += End.Lost
                }
                State.BODY -> when {
                    name in TEXT_FUNCTIONS -> go(call, frame, state)
                    name in NEUTRAL || name in QUESTIONS -> ends += End.Dropped
                    else -> ends += End.Lost
                }
            }
        }

        /** `let`, `also`, `use`, `takeIf` and the like: the lambda's parameter is the value, and so may be what the call gives. */
        private fun scoped(call: Call, name: String, frame: Frame, state: State) {
            val lambda = call.arguments.lastOrNull()?.value as? Lambda
            if (lambda == null) {
                ends += End.Lost
                return
            }
            val parameter = lambda.parameters.firstOrNull() ?: IT
            for (read in lambda.body.walk().filterIsInstance<LocalRead>().filter { it.name == parameter }) go(read, frame, state)
            // The lambda's value is what `let` and `use` give; `also` and `takeIf` give the value they are called on.
            if (name in GIVES_RECEIVER) go(call, frame, state)
        }

        /** Whether the function handed to `thenApply` gives the body of the response: `HttpResponse::body`, or a lambda that does. */
        private fun givesBody(call: Call): Boolean =
            when (val function = call.arguments.firstOrNull()?.value) {
                is CallableReference -> function.text.endsWith("::body")
                is Lambda -> (function.body.statements.lastOrNull() as? Call)?.let { it.name == "body" && it.arguments.isEmpty() } == true
                else -> false
            }
    }

    /** A place the reading has been, the value and the frame told by identity. */
    private class Visit(val value: Code, val frame: Frame, val state: State) {
        override fun equals(other: Any?): Boolean = other is Visit && other.value === value && other.frame === frame && other.state == state

        override fun hashCode(): Int = (System.identityHashCode(value) * 31 + System.identityHashCode(frame)) * 31 + state.hashCode()
    }

    private companion object {
        /** How far one answer is followed. */
        const val MAX_STEPS = 256
        const val IT = "it"

        val SCOPE_FUNCTIONS = setOf("let", "also", "use", "takeIf", "takeUnless")
        val GIVES_RECEIVER = setOf("also", "takeIf", "takeUnless")
        val GIVES_LAMBDA = setOf("let", "run", "use", "with")

        /** What a response tells besides its body. */
        val RESPONSE_PARTS = setOf("statusCode", "headers", "uri", "request", "version", "previousResponse", "sslSession", "toString")

        /** Functions of text that give text made of it. */
        val TEXT_FUNCTIONS = setOf("trim", "trimIndent", "trimStart", "trimEnd", "strip", "decodeToString", "toString", "byteInputStream", "inputStream", "reader", "orEmpty")

        /** Functions that ask something of the text without keeping it. */
        val QUESTIONS = setOf("isEmpty", "isBlank", "isNotEmpty", "isNotBlank", "length", "contains", "startsWith", "endsWith", "equals", "hashCode")

        /** Functions that look at a value without keeping it: logging and checks. */
        val NEUTRAL = setOf("println", "print", "trace", "debug", "info", "warn", "warning", "error", "log", "require", "check", "requireNotNull", "checkNotNull", "assert")

        /** Functions that make text of bytes. */
        val TEXT_OF_BYTES = setOf("kotlin.text.String", "java.lang.String.<init>", "kotlin.text.decodeToString", "kotlin.io.readText", "kotlin.io.readBytes")
    }
}
