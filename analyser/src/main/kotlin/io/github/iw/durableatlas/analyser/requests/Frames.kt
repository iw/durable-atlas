package io.github.iw.durableatlas.analyser.requests

import io.github.iw.durableatlas.analyser.code.Block
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.children
import io.github.iw.durableatlas.analyser.graph.Scope
import java.util.IdentityHashMap

/**
 * A function being read, as the reading of an activity's requests got there: its body, the names
 * its code sees, where it runs, and the call in the code of the function before it that runs it.
 * The frames of a reading make a chain from the activity's own function down to the one read.
 */
internal class Frame(
    val body: Block,
    val scope: Scope,
    val place: Place,
    /** The call that runs this function, in the code of [caller]; `null` for the activity's own function. */
    val call: Call?,
    val caller: Frame?,
    /** How many calls deep the function is from the activity's own. */
    val depth: Int,
    /** The functions of the chain, the activity's own first. */
    val stack: List<FunctionId>,
)

/** What holds each piece of code of a body: the code it is written in. Asked for once per body. */
internal class Parents {
    private val bodies = IdentityHashMap<Block, IdentityHashMap<Code, Code>>()

    /** The code that [code] is written in, inside [body]; `null` for [body] itself and for code outside it. */
    fun of(body: Block, code: Code): Code? = bodies.getOrPut(body) { map(body) }[code]

    private fun map(body: Block): IdentityHashMap<Code, Code> {
        val parents = IdentityHashMap<Code, Code>()
        val pending = ArrayDeque<Code>().apply { add(body) }
        while (pending.isNotEmpty()) {
            val code = pending.removeLast()
            for (child in code.children()) {
                parents[child] = code
                pending += child
            }
        }
        return parents
    }
}
