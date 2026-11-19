package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Argument
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Other

/**
 * What can be told of the names something is written under.
 *
 * @param keys the names the code gives as constant text, in the order written and each once
 * @param more whether there are further names that the code does not say
 */
internal class KnownKeys(val keys: List<String>, val more: Boolean = false) {
    companion object {
        /** Nothing is known of the names. */
        val UNKNOWN = KnownKeys(emptyList(), more = true)
    }
}

/**
 * Reads maps that the code writes out where they are made: `mapOf("a" to x)`, `Map.of("a", x)`
 * and their like, also through the variables and properties that hold them. A map that is put
 * together step by step, or handed in from elsewhere, is not one of these.
 */
internal class MapLiterals(private val tracer: ValueTracer, private val constants: Constants) {
    /**
     * The entries of the map [code] evaluates to, in the order written: each key with the
     * expression of its value. `null` if the map is not written out where it can be found, or
     * if one of its keys is no constant text.
     */
    fun entries(code: Code, scope: Scope): List<Pair<String, Traced>>? {
        val made = tracer.origin(code, scope)
        val call = made.code as? Call ?: return null
        val arguments = call.arguments
        return when (call.callee?.function?.name) {
            in PAIRS -> arguments.map { pair(it.value, made.scope) ?: return null }
            in NOTHING -> emptyList()
            in KEYS_AND_VALUES ->
                if (arguments.size % 2 != 0) null else arguments.chunked(2).map { (key, value) -> entry(key, value, made.scope) ?: return null }
            else -> null
        }
    }

    /** The keys of the map [code] evaluates to, in the order written and each once; `null` under the conditions of [entries]. */
    fun keys(code: Code, scope: Scope): List<String>? = entries(code, scope)?.map { it.first }?.distinct()

    /**
     * What can be told of the keys of the map [code] evaluates to: those that are written out
     * as constant text, in the order written and each once, and whether the map holds more
     * than those. A map that is put together from others with `+` has what each of them has.
     */
    fun known(code: Code, scope: Scope): KnownKeys {
        val made = tracer.origin(code, scope)
        val sum = made.code as? Other
        // `a + b`, where what is asked for is a map: a map and a further map or pair.
        if (sum != null && sum.operator == PLUS_SIGN && sum.operands.size == 2) {
            val parts = sum.operands.map { part -> pair(part, made.scope)?.let { KnownKeys(listOf(it.first)) } ?: known(part, made.scope) }
            return KnownKeys(parts.flatMap { it.keys }.distinct(), more = parts.any { it.more })
        }
        val call = made.code as? Call ?: return KnownKeys.UNKNOWN
        val arguments = call.arguments
        val parts: List<KnownKeys> = when (call.callee?.function?.name) {
            in PAIRS -> arguments.map { argument -> pair(argument.value, made.scope)?.let { KnownKeys(listOf(it.first)) } ?: KnownKeys.UNKNOWN }
            in NOTHING -> emptyList()
            in KEYS_AND_VALUES -> arguments.chunked(2).map { chunk -> constants.text(chunk.first(), made.scope)?.let { KnownKeys(listOf(it)) } ?: KnownKeys.UNKNOWN }
            else -> return KnownKeys.UNKNOWN
        }
        return KnownKeys(parts.flatMap { it.keys }.distinct(), more = parts.any { it.more })
    }

    /** `key to value`, or `Pair(key, value)`. */
    private fun pair(code: Code, scope: Scope): Pair<String, Traced>? {
        val made = tracer.origin(code, scope)
        val call = made.code as? Call ?: return null
        return when (call.callee?.function?.name) {
            TO -> {
                val key = constants.text(call.receiver ?: return null, made.scope) ?: return null
                key to Traced(call.arguments.singleOrNull()?.value ?: return null, made.scope)
            }
            PAIR -> if (call.arguments.size == 2) entry(call.arguments[0], call.arguments[1], made.scope) else null
            else -> null
        }
    }

    private fun entry(key: Argument, value: Argument, scope: Scope): Pair<String, Traced>? =
        constants.text(key, scope)?.let { it to Traced(value.value, scope) }

    private companion object {
        /** Functions that make a map of the pairs they are given. */
        val PAIRS = setOf(
            "kotlin.collections.mapOf", "kotlin.collections.mutableMapOf", "kotlin.collections.hashMapOf",
            "kotlin.collections.linkedMapOf", "kotlin.collections.sortedMapOf",
        )

        /** Functions that make a map without entries. */
        val NOTHING = setOf("kotlin.collections.emptyMap", "java.util.Collections.emptyMap")

        /** Functions that make a map of keys and values given in turn. */
        val KEYS_AND_VALUES = setOf("java.util.Map.of", "java.util.Collections.singletonMap")

        /** The operator that makes a map of another one and a further map or pair. */
        const val PLUS_SIGN = "+"

        const val TO = "kotlin.to"
        const val PAIR = "kotlin.Pair.<init>"
    }
}
