package io.github.iw.durableatlas.analyser.code

/** The nodes directly below this one, in the order they appear in the source. */
fun Code.children(): List<Code> =
    when (this) {
        is Block -> statements
        is Call -> listOfNotNull(receiver) + arguments.map { it.value }
        is Lambda -> listOf(body)
        is CallableReference -> listOfNotNull(receiver)
        is PropertyRead -> listOfNotNull(receiver)
        is PropertyWrite -> listOfNotNull(receiver, value)
        is LocalWrite -> listOfNotNull(value)
        is LocalVariable -> listOfNotNull(initializer)
        is Destructuring -> listOfNotNull(initializer)
        is If -> listOfNotNull(condition, then, otherwise)
        is When -> listOfNotNull(subject) + branches.flatMap { it.conditions + listOfNotNull(it.body) }
        is Loop -> if (kind == LoopKind.DO_WHILE) listOfNotNull(body, header) else listOfNotNull(header, body)
        is Try -> listOf(body) + catches.map { it.body } + listOfNotNull(finally)
        is Return -> listOfNotNull(value)
        is Throw -> listOfNotNull(value)
        is Other -> operands
        is ClassLiteral, is ClassReference, is Jump, is Literal, is LocalRead, is ThisReference -> emptyList()
    }

/** This node and everything below it, parents before their children. */
fun Code.walk(): Sequence<Code> = sequence {
    yield(this@walk)
    for (child in children()) yieldAll(child.walk())
}
