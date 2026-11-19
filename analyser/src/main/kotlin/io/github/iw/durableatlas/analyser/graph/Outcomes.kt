package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Constant
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.Literal
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.LocalVariable
import io.github.iw.durableatlas.analyser.code.Other
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.Return
import io.github.iw.durableatlas.analyser.code.ThisReference
import io.github.iw.durableatlas.ir.EdgeKind

/**
 * One way a step can turn out: an await ends because one part of its condition holds, or because
 * its time is up; a version gate finds one version.
 */
internal class Scenario(
    /** What an edge taken in this case is called. */
    val caption: String?,
    val kind: EdgeKind,
    /** What the step returns in this case: a `Boolean` for an await, a `Long` for a version. */
    val result: Any,
    /** What is known to hold or not to hold in this case, by the [Conditions.key] of the expression. */
    val facts: Map<String, Boolean> = emptyMap(),
    /**
     * For a case in which several reasons to go on hold at once: the cases in which each of
     * them holds alone. Empty for a case that stands for one reason, or for none.
     */
    val parts: List<Scenario> = emptyList(),
)

/**
 * The ways a step can turn out, of which code after the step sees one.
 *
 * @param certain whether the step is sure to turn out in exactly one of these ways. The versions
 *   of a gate are; the reasons an await went on are only taken to exclude each other, so a path
 *   that none of them leads to may still be taken.
 */
internal class Outcomes(
    val scenarios: List<Scenario>,
    val certain: Boolean,
    /**
     * The cases in which several of the reasons an await went on hold together. No edge is
     * named after one of them, but a test after the await has to come out the same way in them
     * as its edges say, or the edges would promise what the code does not do.
     */
    val overlaps: List<Scenario> = emptyList(),
) {
    /** Every case the step can leave the flow in. */
    val states: List<Scenario> = scenarios + overlaps

    companion object {
        /** More versions than this are not told apart. */
        private const val MAX_VERSIONS = 16

        /** With more reasons than this, the cases in which several of them hold are not looked at. */
        private const val MAX_REASONS = 5

        /**
         * The outcomes of an await whose condition is [condition], the lambda given to it.
         *
         * A condition `a || b` is met because `a` holds or because `b` does, and each is a case
         * of its own, in which the other does not hold: the cases stand for the reasons to go on,
         * and the edges out of the await are named after them. Both may hold at once, though, and
         * that is one more case ([Outcomes.overlaps]), which a test after the await is tried
         * against as well. An await with a timeout has a last case, in which nothing of the
         * condition holds.
         */
        fun ofAwait(condition: Code?, timed: Boolean): Outcomes {
            val expression = expressionOf(condition)
            val reasons = expression?.let(::disjuncts).orEmpty()
            fun case(holding: List<Int>, caption: String?, parts: List<Scenario>): Scenario {
                val facts = LinkedHashMap<String, Boolean>()
                reasons.forEachIndexed { index, reason -> if (index !in holding) Conditions.assume(reason, false, facts) }
                holding.forEach { Conditions.assume(reasons[it], true, facts) }
                expression?.let { Conditions.assume(it, true, facts) }
                return Scenario(caption, EdgeKind.FLOW, result = true, facts = facts, parts = parts)
            }

            val met = when {
                reasons.isEmpty() -> listOf(Scenario(caption = null, kind = EdgeKind.FLOW, result = true))
                else -> reasons.indices.map { case(listOf(it), Captions.of(Texts.condition(reasons[it])), emptyList()) }
            }
            val overlaps = when {
                reasons.size !in 2..MAX_REASONS -> emptyList()
                else -> (1 until (1 shl reasons.size))
                    .map { bits -> reasons.indices.filter { bits and (1 shl it) != 0 } }
                    .filter { it.size > 1 }
                    .map { holding -> case(holding, caption = null, parts = holding.map(met::get)) }
            }
            if (!timed) return Outcomes(met, certain = false, overlaps = overlaps)
            val facts = LinkedHashMap<String, Boolean>()
            expression?.let { Conditions.assume(it, false, facts) }
            val timeout = Scenario(caption = null, kind = EdgeKind.TIMEOUT, result = false, facts = facts)
            return Outcomes(met + timeout, certain = false, overlaps = overlaps)
        }

        /**
         * The outcomes of a version gate that supports the versions from [min] to [max]; `null`
         * if the range is not known. Version 0 is left out of a range that starts at the default
         * version, -1: code that was never versioned records -1, and versions count from 1.
         */
        fun ofVersions(min: Long?, max: Long?): Outcomes? {
            if (min == null || max == null || max < min || max - min >= MAX_VERSIONS) return null
            val versions = (min..max).filterNot { it == 0L && min == DEFAULT_VERSION && max > 0 }
            return Outcomes(
                versions.map { version ->
                    val caption = if (version == DEFAULT_VERSION) "default" else "v$version"
                    Scenario(caption, EdgeKind.FLOW, result = version)
                },
                certain = true,
            )
        }

        /**
         * The outcomes of waiting for a queue to hold something for a time at most: it is given
         * something, or the time is up and it is given nothing.
         */
        fun ofPoll(): Outcomes =
            Outcomes(
                listOf(
                    Scenario(caption = null, kind = EdgeKind.FLOW, result = Conditions.SOMETHING),
                    Scenario(caption = null, kind = EdgeKind.TIMEOUT, result = Conditions.NOTHING),
                ),
                certain = true,
            )

        /**
         * The outcomes of waiting for a lock or a semaphore for a time at most: it is got, and
         * the call answers `true`, or the time is up and it answers `false`.
         */
        fun ofAttempt(): Outcomes =
            Outcomes(
                listOf(
                    Scenario(caption = null, kind = EdgeKind.FLOW, result = true),
                    Scenario(caption = null, kind = EdgeKind.TIMEOUT, result = false),
                ),
                certain = true,
            )

        /** The label of an await: its condition in words. */
        fun awaitLabel(condition: Code?): String? =
            expressionOf(condition)?.let { Captions.of(Texts.condition(it)) }?.ifEmpty { null }

        private const val DEFAULT_VERSION = -1L

        /** The one expression a condition lambda consists of, if it is that simple. */
        private fun expressionOf(condition: Code?): Code? {
            val statement = (condition as? Lambda)?.body?.statements?.singleOrNull() ?: return null
            return if (statement is Return) statement.value else statement
        }

        private fun disjuncts(code: Code): List<Code> =
            if (code is Other && code.operator == "||" && code.operands.size == 2) code.operands.flatMap(::disjuncts) else listOf(code)
    }
}

/**
 * A step whose outcomes the code that follows may still tell apart: nothing has happened since,
 * so each way on from here belongs to one of the outcomes in [live].
 */
internal class Gate private constructor(
    val outcomes: Outcomes,
    /** The call that is the step. */
    val call: Call,
    /** The properties of the workflow that the outcomes depend on. */
    val reads: Set<String>,
    private val state: State,
    /** The outcomes that can have led to the code being looked at. */
    val live: Set<Scenario>,
) {
    private class State {
        var variable: String? = null
        var spent = false
    }

    constructor(outcomes: Outcomes, call: Call, reads: Set<String>) :
        this(outcomes, call, reads, State(), outcomes.states.toSet())

    /** The local variable that holds what the step returned. */
    var variable: String?
        get() = state.variable
        set(value) {
            state.variable = value
        }

    /** True once something was assigned that the outcomes are told apart by. */
    val spent: Boolean get() = state.spent

    fun spend() {
        state.spent = true
    }

    /** The same step, seen from code that only some outcomes lead to. */
    fun narrowedTo(scenarios: Set<Scenario>): Gate = Gate(outcomes, call, reads, state, scenarios)
}

/** Works out what a condition comes to in one outcome of a step, as far as that can be told. */
internal object Conditions {
    /** What a step returns that gives nothing back: `null`. */
    val NOTHING = Any()

    /** What a step returns that gives something back, whatever it is. */
    val SOMETHING = Any()

    private val NULL get() = NOTHING

    /** Whether [code] is `null` in [scenario]; `null` if that cannot be told. */
    fun isNothing(code: Code, gate: Gate, scenario: Scenario): Boolean? = value(code, gate, scenario)?.let { it === NOTHING }

    /** Whether [condition] holds in [scenario]; `null` if that depends on anything else. */
    fun holds(condition: Code, gate: Gate, scenario: Scenario): Boolean? = value(condition, gate, scenario) as? Boolean

    /** Whether [left] equals [right] in [scenario]; `null` if that cannot be told. */
    fun same(left: Code, right: Code, gate: Gate, scenario: Scenario): Boolean? {
        val a = value(left, gate, scenario) ?: return null
        val b = value(right, gate, scenario) ?: return null
        return a == b
    }

    /** Notes in [facts] what follows from [code] being [value]. */
    fun assume(code: Code, value: Boolean, facts: MutableMap<String, Boolean>) {
        facts[key(code)] = value
        if (code !is Other) return
        when {
            code.operator == "!" && code.operands.size == 1 -> assume(code.operands.single(), !value, facts)
            code.operator == "||" && !value -> code.operands.forEach { assume(it, false, facts) }
            code.operator == "&&" && value -> code.operands.forEach { assume(it, true, facts) }
        }
    }

    /** A boolean, a whole number or null if [code] is known to be that in [scenario]; otherwise `null`. */
    private fun value(code: Code, gate: Gate, scenario: Scenario): Any? {
        scenario.facts[key(code)]?.let { return it }
        return when (code) {
            is Call -> scenario.result.takeIf { code === gate.call }
            is LocalRead -> scenario.result.takeIf { code.name == gate.variable }
            // `when (val version = …)`: the subject is the variable's value.
            is LocalVariable -> code.initializer?.let { value(it, gate, scenario) }
            is Literal -> constant(code.constant)
            is PropertyRead -> code.constant?.let(::constant)
            is Other -> operation(code, gate, scenario)
            else -> null
        }
    }

    private fun constant(constant: Constant): Any? =
        when (constant) {
            is Constant.Bool -> constant.value
            is Constant.Integer -> constant.value
            Constant.Null -> NULL
            else -> null
        }

    private fun operation(code: Other, gate: Gate, scenario: Scenario): Any? {
        val operands = code.operands
        val operator = code.operator
        if (operator == "!" && operands.size == 1) return (value(operands[0], gate, scenario) as? Boolean)?.not()
        if (operands.size != 2) return null
        val left = value(operands[0], gate, scenario)
        val right = value(operands[1], gate, scenario)
        return when (operator) {
            "||" -> when {
                left == true || right == true -> true
                left == false && right == false -> false
                else -> null
            }
            "&&" -> when {
                left == false || right == false -> false
                left == true && right == true -> true
                else -> null
            }
            "==", "!=" ->
                if (left != null && right != null) {
                    (left == right) == (operator == "==")
                } else {
                    opposite(code, scenario)?.not()
                }
            "<", "<=", ">", ">=" -> {
                val a = left as? Long ?: return null
                val b = right as? Long ?: return null
                when (operator) {
                    "<" -> a < b
                    "<=" -> a <= b
                    ">" -> a > b
                    else -> a >= b
                }
            }
            else -> null
        }
    }

    /** What is known about the same comparison with the other of `==` and `!=`. */
    private fun opposite(code: Other, scenario: Scenario): Boolean? {
        val other = if (code.operator == "==") "!=" else "=="
        return scenario.facts[key(other, code.operands)]
    }

    /**
     * Identifies an expression by what it refers to, so that the same test written in a condition
     * and again after it is seen to be the same.
     */
    fun key(code: Code): String =
        when (code) {
            is PropertyRead -> {
                val receiver = code.receiver?.takeUnless { code.onThis || it is ThisReference }?.let { key(it) + "." }.orEmpty()
                "property $receiver${code.property ?: code.name}"
            }
            is LocalRead -> "local ${code.name}"
            is Literal -> "literal ${code.constant}"
            is Other -> if (code.operator != null && code.operands.isNotEmpty()) key(code.operator, code.operands) else "text ${Captions.of(code.text)}"
            is Call -> {
                val receiver = code.receiver?.let { key(it) + "." }.orEmpty()
                "call $receiver${code.callee?.function ?: code.name}(${code.arguments.joinToString(",") { key(it.value) }})"
            }
            else -> "text ${Captions.of(Texts.of(code))} at ${code.location.line}"
        }

    private fun key(operator: String, operands: List<Code>): String = "($operator ${operands.joinToString(" ") { key(it) }})"
}
