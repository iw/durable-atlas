package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CallableReference
import io.github.iw.durableatlas.analyser.code.ClassReference
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Constant
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.Literal
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.Other
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.ThisReference
import java.time.Duration

/** Source text made fit for a label: on one line and not too long. */
internal object Captions {
    private const val MAX_LENGTH = 80
    private val WHITESPACE = Regex("\\s+")

    fun of(text: String): String {
        val oneLine = text.replace(WHITESPACE, " ").trim()
        return if (oneLine.length > MAX_LENGTH) oneLine.take(MAX_LENGTH - 1) + "…" else oneLine
    }

    /**
     * [text] made fit for a label that ends with [suffix]: on one line, and shortened where the
     * two together are too long, so that the suffix is always there in full.
     */
    fun of(text: String, suffix: String): String {
        val oneLine = text.replace(WHITESPACE, " ").trim()
        val room = MAX_LENGTH - suffix.length - 1
        return (if (oneLine.length > room) oneLine.take(room - 1) + "…" else oneLine) + " " + suffix
    }

    /**
     * A length of time as a label says it, the way the diagrams write the durations of timers:
     * `36h` reads `1d 12h`, `90s` reads `1m 30s`. One that this cannot express stays as it is.
     */
    fun duration(duration: Duration): String {
        if (duration.isNegative || duration.isZero || duration.toNanosPart() % NANOS_PER_MILLI != 0) return duration.toString()
        val parts = listOf(
            duration.toDaysPart() to "d",
            duration.toHoursPart().toLong() to "h",
            duration.toMinutesPart().toLong() to "m",
            duration.toSecondsPart().toLong() to "s",
            duration.toMillisPart().toLong() to "ms",
        )
        return parts.filter { it.first != 0L }.joinToString(" ") { "${it.first}${it.second}" }
    }

    private const val NANOS_PER_MILLI = 1_000_000

    /** A name in words: `invoiceNumber` and `invoice_number` both read `invoice number`. */
    fun words(name: String): String =
        name.replace(CAMEL_HUMP, "$1 $2").replace('_', ' ').replace(WHITESPACE, " ").trim().lowercase()

    private val CAMEL_HUMP = Regex("([a-z0-9])([A-Z])")
}

/** Expressions put back into words, for the labels of nodes and edges. */
internal object Texts {
    /** The expression roughly as it was written. */
    fun of(code: Code): String =
        when (code) {
            is Other -> code.text
            is LocalRead -> code.name
            is PropertyRead -> qualified(code.receiver, code.name)
            is Call -> qualified(code.receiver, call(code))
            is Literal -> literal(code.constant)
            is ThisReference -> "this"
            is ClassReference -> code.text
            is CallableReference -> code.text
            else -> ""
        }

    /**
     * A condition as a label says it: `a || b` reads `a or b`, `a && b` reads `a and b`, and
     * `!a` reads `not a`. Everything else stays as written.
     */
    fun condition(code: Code): String =
        when {
            code is Other && code.operator == "||" && code.operands.size == 2 ->
                code.operands.joinToString(" or ") { condition(it) }
            code is Other && code.operator == "&&" && code.operands.size == 2 ->
                code.operands.joinToString(" and ") { operand(it, setOf("||")) }
            code is Other && code.operator == "!" && code.operands.size == 1 ->
                "not " + operand(code.operands.single(), setOf("||", "&&"))
            else -> of(code)
        }

    /** A call with its arguments as they were written, a lambda at the end outside the parentheses. */
    private fun call(code: Call): String {
        val trailing = code.arguments.lastOrNull()?.takeIf { it.value is Lambda }
        val inside = code.arguments.filter { it !== trailing }.joinToString(", ") { it.text }
        return when {
            trailing == null -> "${code.name}($inside)"
            inside.isEmpty() -> "${code.name} ${trailing.text}"
            else -> "${code.name}($inside) ${trailing.text}"
        }
    }

    private fun operand(code: Code, weaker: Set<String>): String {
        val text = condition(code)
        return if (code is Other && code.operator in weaker) "($text)" else text
    }

    private fun qualified(receiver: Code?, name: String): String {
        val prefix = receiver?.takeUnless { it is ThisReference }?.let(::of).orEmpty()
        return if (prefix.isEmpty()) name else "$prefix.$name"
    }

    private fun literal(constant: Constant): String =
        when (constant) {
            is Constant.Text -> "\"${constant.value}\""
            is Constant.Integer -> constant.value.toString()
            is Constant.Decimal -> constant.value.toString()
            is Constant.Bool -> constant.value.toString()
            Constant.Null -> "null"
            is Constant.Unsupported -> constant.text
            else -> constant.toString()
        }
}
