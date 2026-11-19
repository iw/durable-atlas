package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.Other

/** What the builder of some options holds before the setters that are called on it. */
internal class BuilderStart(
    /**
     * The options the builder is made from, with `newBuilder(other)` or `other.toBuilder()`: it
     * starts out with all they hold. `null` for a builder that is made from nothing.
     */
    val from: Traced?,
    /**
     * True if the builder may be given more than the expression that builds the options shows:
     * it is kept in a variable, handed back by a function, or handed to code that is not
     * written out where it is used. What the expression does not set is then not known.
     */
    val open: Boolean,
)

/**
 * Finds where the builder of options begins. The SDK's options are made by a chain of calls:
 * one that makes a builder, setters, and `build`. A builder is made from nothing, or from
 * other options, whose every option it then holds until a setter says otherwise.
 */
internal object BuilderChains {
    /**
     * Where the builder begins that makes the options [made] evaluates to; `null` if [made] is
     * no such chain, or one that begins with something this reading does not know.
     *
     * @param type the class of the options
     * @param builder the class of their builder
     */
    fun start(made: Traced, type: String, builder: String, tracer: ValueTracer): BuilderStart? {
        var current = made
        var open = false
        repeat(MAX_LINKS) {
            val call = unwrapped(current.code) as? Call ?: return null
            val function = call.callee?.function ?: return null
            val on: Code? = when {
                function.owner == type -> return when (function.simpleName) {
                    // The options that are there when nothing is asked for hold nothing.
                    "newBuilder", "getDefaultInstance" -> BuilderStart(call.arguments.singleOrNull()?.let { Traced(it.value, current.scope) }, open)
                    "toBuilder" -> call.receiver?.let { BuilderStart(Traced(it, current.scope), open) }
                    else -> null
                }
                // A setter, or `build`: the builder is what it is called on.
                function.owner == builder -> call.receiver
                function.name in GIVE_BACK_RECEIVER -> {
                    // What is not written out where it is handed over may set anything.
                    if (call.arguments.any { it.value !is Lambda }) open = true
                    call.receiver
                }
                // A function of the sources that hands the builder back may have set something on it.
                else -> {
                    val result = tracer.origin(call, current.scope, settled = false)
                    if (result.code === call) return null
                    open = true
                    current = result
                    return@repeat
                }
            }
            val next = on?.let(::unwrapped)
            if (next is Call) {
                current = Traced(next, current.scope)
                return@repeat
            }
            // A builder that goes by a name, or by none where it is `this`, can be given more wherever it is in sight.
            val kept = on?.let { tracer.origin(it, current.scope, settled = false) }
            if (kept == null || kept.code === on) return BuilderStart(from = null, open = true)
            open = true
            current = kept
        }
        return null
    }

    private fun unwrapped(code: Code): Code {
        var current = code
        while (current is Other && current.operator in TRANSPARENT && current.operands.size == 1) current = current.operands.single()
        return current
    }

    /** A chain longer than this is not followed. */
    private const val MAX_LINKS = 64

    /** Functions that give back what they are called on, after handing it to their lambda. */
    private val GIVE_BACK_RECEIVER = setOf("kotlin.apply", "kotlin.also")

    /** Operators that hand their operand on unchanged. */
    private val TRANSPARENT = setOf("as", "as?", "!!")
}
