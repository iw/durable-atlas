package io.github.iw.durableatlas.analyser.code

/**
 * This tree with every call in it replaced by what [transform] makes of it, innermost calls
 * first: a call is handed over once what it is made on and what it is given have been replaced.
 * Whatever holds no call that changes is the object it was, so a tree that stays as it is costs
 * nothing, and nothing that is known by its identity is lost.
 */
fun Code.rewriteCalls(transform: (Call) -> Code): Code = Rewriting(transform).code(this)

private class Rewriting(private val transform: (Call) -> Code) {
    fun code(code: Code): Code =
        when (code) {
            is Block -> block(code)
            is Call -> {
                val receiver = code.receiver?.let(::code)
                val arguments = code.arguments.same { argument -> code(argument.value).let { if (it === argument.value) argument else argument.copy(value = it) } }
                transform(if (receiver === code.receiver && arguments === code.arguments) code else code.copy(receiver = receiver, arguments = arguments))
            }
            is Lambda -> block(code.body).let { if (it === code.body) code else code.copy(body = it) }
            is CallableReference -> code.receiver?.let(::code).let { if (it === code.receiver) code else code.copy(receiver = it) }
            is PropertyRead -> code.receiver?.let(::code).let { if (it === code.receiver) code else code.copy(receiver = it) }
            is PropertyWrite -> {
                val receiver = code.receiver?.let(::code)
                val value = code.value?.let(::code)
                if (receiver === code.receiver && value === code.value) code else code.copy(receiver = receiver, value = value)
            }
            is LocalWrite -> code.value?.let(::code).let { if (it === code.value) code else code.copy(value = it) }
            is LocalVariable -> code.initializer?.let(::code).let { if (it === code.initializer) code else code.copy(initializer = it) }
            is Destructuring -> code.initializer?.let(::code).let { if (it === code.initializer) code else code.copy(initializer = it) }
            is If -> {
                val condition = code(code.condition)
                val then = code.then?.let(::code)
                val otherwise = code.otherwise?.let(::code)
                if (condition === code.condition && then === code.then && otherwise === code.otherwise) {
                    code
                } else {
                    code.copy(condition = condition, then = then, otherwise = otherwise)
                }
            }
            is When -> {
                val subject = code.subject?.let(::code)
                val branches = code.branches.same { branch ->
                    val conditions = branch.conditions.same(::code)
                    val body = branch.body?.let(::code)
                    if (conditions === branch.conditions && body === branch.body) branch else branch.copy(conditions = conditions, body = body)
                }
                if (subject === code.subject && branches === code.branches) code else code.copy(subject = subject, branches = branches)
            }
            is Loop -> {
                val header = code.header?.let(::code)
                val body = code.body?.let(::code)
                if (header === code.header && body === code.body) code else code.copy(header = header, body = body)
            }
            is Try -> {
                val body = block(code.body)
                val catches = code.catches.same { clause -> block(clause.body).let { if (it === clause.body) clause else clause.copy(body = it) } }
                val finally = code.finally?.let(::block)
                if (body === code.body && catches === code.catches && finally === code.finally) code else code.copy(body = body, catches = catches, finally = finally)
            }
            is Return -> code.value?.let(::code).let { if (it === code.value) code else code.copy(value = it) }
            is Throw -> code.value?.let(::code).let { if (it === code.value) code else code.copy(value = it) }
            is Other -> code.operands.same(::code).let { if (it === code.operands) code else code.copy(operands = it) }
            is ClassLiteral, is ClassReference, is Jump, is Literal, is LocalRead, is ThisReference -> code
        }

    private fun block(block: Block): Block = block.statements.same(::code).let { if (it === block.statements) block else block.copy(statements = it) }

    /** The list with [change] applied to each element; the list itself if no element changed. */
    private inline fun <T> List<T>.same(change: (T) -> T): List<T> {
        var changed = false
        val result = map { element -> change(element).also { if (it !== element) changed = true } }
        return if (changed) result else this
    }
}
