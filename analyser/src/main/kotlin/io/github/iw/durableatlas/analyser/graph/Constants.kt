package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Argument
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Constant
import io.github.iw.durableatlas.analyser.code.Literal
import io.github.iw.durableatlas.analyser.code.PropertyRead
import java.time.Duration
import java.time.temporal.ChronoUnit

/** Reads durations and other constants out of the expressions that make them. */
internal class Constants(private val tracer: ValueTracer) {
    /**
     * The duration [code] evaluates to, if it is made from constants: `Duration.ofSeconds(30)`,
     * `Duration.parse("PT5M")`, `30.seconds.toJavaDuration()`, or a variable or property that
     * holds one of these.
     */
    fun duration(code: Code, scope: Scope): Duration? {
        val traced = tracer.origin(code, scope)
        return runCatching {
            when (val origin = traced.code) {
                is Call -> duration(origin, traced.scope)
                is PropertyRead -> when (origin.property?.name) {
                    "java.time.Duration.ZERO" -> Duration.ZERO
                    else -> kotlinDuration(origin, traced.scope)
                }
                else -> null
            }
        }.getOrNull() // An amount out of range is no duration.
    }

    private fun duration(call: Call, scope: Scope): Duration? {
        val arguments = call.arguments
        return when (call.callee?.function?.name) {
            "java.time.Duration.ofNanos" -> integer(arguments, 0, scope)?.let(Duration::ofNanos)
            "java.time.Duration.ofMillis" -> integer(arguments, 0, scope)?.let(Duration::ofMillis)
            "java.time.Duration.ofSeconds" ->
                if (arguments.size == 1) integer(arguments, 0, scope)?.let(Duration::ofSeconds) else null
            "java.time.Duration.ofMinutes" -> integer(arguments, 0, scope)?.let(Duration::ofMinutes)
            "java.time.Duration.ofHours" -> integer(arguments, 0, scope)?.let(Duration::ofHours)
            "java.time.Duration.ofDays" -> integer(arguments, 0, scope)?.let(Duration::ofDays)
            "java.time.Duration.of" -> {
                val amount = integer(arguments, 0, scope) ?: return null
                val unit = arguments.getOrNull(1)?.let { chronoUnit(it, scope) } ?: return null
                Duration.of(amount, unit)
            }
            "java.time.Duration.parse" -> text(arguments.getOrNull(0), scope)?.let(Duration::parse)
            "kotlin.time.toJavaDuration" -> call.receiver?.let { receiver ->
                val traced = tracer.origin(receiver, scope)
                (traced.code as? PropertyRead)?.let { kotlinDuration(it, traced.scope) }
            }
            else -> null
        }
    }

    private fun chronoUnit(argument: Argument, scope: Scope): ChronoUnit? = enumEntry(argument, scope, CHRONO_UNIT)?.let(ChronoUnit::valueOf)

    /** The name of the entry of the enum class [type] that [argument] is, if it is written as one or is a variable that holds one. */
    fun enumEntry(argument: Argument?, scope: Scope, type: String): String? {
        if (argument == null) return null
        return (argument.constant as? Constant.EnumEntry)?.takeIf { it.type == type }?.entry
            ?: (tracer.origin(argument.value, scope).code as? PropertyRead)?.property?.takeIf { it.owner == type }?.simpleName
    }

    /** `30.seconds` and its like: an extension property of the companion of `kotlin.time.Duration`. */
    private fun kotlinDuration(read: PropertyRead, scope: Scope): Duration? {
        val unit = KOTLIN_UNITS[read.property?.name] ?: return null
        val amount = read.receiver?.let { constant(it, scope) } as? Constant.Integer ?: return null
        return Duration.of(amount.value, unit)
    }

    private fun integer(arguments: List<Argument>, index: Int, scope: Scope): Long? =
        integer(arguments.getOrNull(index), scope)

    /** The whole number [argument] evaluates to, if it is a compile-time constant or a variable that holds one. */
    fun integer(argument: Argument?, scope: Scope): Long? = (constant(argument, scope) as? Constant.Integer)?.value

    /** Whether [argument] is true or false, if it is a compile-time constant or a variable that holds one. */
    fun bool(argument: Argument?, scope: Scope): Boolean? = (constant(argument, scope) as? Constant.Bool)?.value

    /**
     * The text [argument] evaluates to, if it is a compile-time constant or a variable that holds
     * one. Empty text names nothing and counts as unknown.
     */
    fun text(argument: Argument?, scope: Scope): String? =
        (constant(argument, scope) as? Constant.Text)?.value?.ifEmpty { null }

    /** The text [code] evaluates to, under the conditions of [text] for an argument. */
    fun text(code: Code, scope: Scope): String? = (constant(code, scope) as? Constant.Text)?.value?.ifEmpty { null }

    private fun constant(argument: Argument?, scope: Scope): Constant? =
        argument?.let { it.constant ?: constant(it.value, scope) }

    private fun constant(code: Code, scope: Scope): Constant? =
        when (val origin = tracer.origin(code, scope).code) {
            is Literal -> origin.constant
            is PropertyRead -> origin.constant
            else -> null
        }

    private companion object {
        const val CHRONO_UNIT = "java.time.temporal.ChronoUnit"

        val KOTLIN_UNITS = mapOf(
            "kotlin.time.Duration.Companion.nanoseconds" to ChronoUnit.NANOS,
            "kotlin.time.Duration.Companion.milliseconds" to ChronoUnit.MILLIS,
            "kotlin.time.Duration.Companion.seconds" to ChronoUnit.SECONDS,
            "kotlin.time.Duration.Companion.minutes" to ChronoUnit.MINUTES,
            "kotlin.time.Duration.Companion.hours" to ChronoUnit.HOURS,
            "kotlin.time.Duration.Companion.days" to ChronoUnit.DAYS,
        )
    }
}
