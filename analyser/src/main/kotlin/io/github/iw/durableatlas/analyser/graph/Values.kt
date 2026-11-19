package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Block
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CalleeKind
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.LocalVariable
import io.github.iw.durableatlas.analyser.code.Other
import io.github.iw.durableatlas.analyser.code.PropertyId
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.PropertyWrite
import io.github.iw.durableatlas.analyser.code.Return
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.ThisReference
import io.github.iw.durableatlas.analyser.code.walk

/**
 * The local names visible at some point of a function, each with the expression it was given.
 * A name without an expression, such as a parameter, hides an outer one of the same name.
 */
internal class Scope(private val parent: Scope? = null) {
    private class Binding(val value: Traced?, val reassignable: Boolean, val state: StateRef?)

    private val names = HashMap<String, Binding>()

    /**
     * The expression that made the object the code runs on, where it is known: the one a function
     * brought in from elsewhere was called on. Inner scopes see that of the scope around them.
     */
    var self: Traced? = null
        get() = field ?: parent?.self

    /** Declares a local variable. A `var` may hold something else by the time it is read. */
    fun declare(name: String, value: Code?, reassignable: Boolean = false, state: StateRef? = null) {
        names[name] = Binding(value?.let { Traced(it, this) }, reassignable, state)
    }

    /**
     * @param state what of the workflow's state the name stands for, if it stands for any
     */
    fun bind(name: String, value: Traced?, state: StateRef? = null) {
        names[name] = Binding(value, reassignable = false, state)
    }

    /**
     * What [name] was given; `null` if that is not known.
     *
     * @param settled leave out variables that can be assigned again
     */
    fun find(name: String, settled: Boolean): Traced? {
        val binding = names[name] ?: return parent?.find(name, settled)
        return binding.value?.takeUnless { settled && binding.reassignable }
    }

    /** What of the workflow's state [name] stands for; `null` if it stands for none of it. */
    fun state(name: String): StateRef? {
        val binding = names[name] ?: return parent?.state(name)
        return binding.state
    }

    fun child(): Scope = Scope(this)

    /** Whether [other] is this scope or one inside it. */
    fun encloses(other: Scope): Boolean = generateSequence(other) { it.parent }.any { it === this }

    companion object {
        /**
         * The scope of code outside any function: the initialiser of a property.
         *
         * @param self the object the property belongs to, if the expression that made it is known
         */
        fun none(self: Traced? = null): Scope = Scope().also { it.self = self }

        /** The names [body] declares, wherever in it. Lambdas are not told apart: the first declaration wins. */
        fun of(body: Block, parent: Scope? = null): Scope {
            val scope = Scope(parent)
            for (variable in body.walk().filterIsInstance<LocalVariable>()) {
                if (variable.name !in scope.names) scope.declare(variable.name, variable.initializer, variable.isMutable)
            }
            return scope
        }
    }
}

/** An expression together with the scope its local names are to be read in. */
internal class Traced(val code: Code, val scope: Scope)

/** The way a value was followed back to the expression that made it; see [ValueTracer.trail]. */
internal class Trail(
    /** The expressions passed, from the one that was asked about to the one that made the value. */
    val passed: List<Traced>,
    /**
     * True if something kept the value on the way that outlives the first expression: a
     * property, or a local name of the code around that expression. The value may then be
     * older than the expression. A name inside a function or a lambda that the trail leads
     * through keeps nothing beyond it, and neither does a parameter the value is handed in by.
     */
    val kept: Boolean,
)

/**
 * Follows values back to the expressions that made them: through local variables, properties with
 * an initialiser, properties that are given their value once in an `init` block or in the body
 * of a constructor, casts, scope functions and functions of the sources that return one expression.
 * Where the code that is traced runs on an object whose making is known (see [Scope.self]), a
 * property that is a parameter of the object's constructor leads on to the argument it was given,
 * and `this` to the expression that made the object.
 * This is a best effort; where the trail ends, the expression reached so far is the answer.
 */
internal class ValueTracer(private val backend: SourceBackend) {
    private val reassignable = HashMap<PropertyId, Boolean>()
    private val assignments = HashMap<PropertyId, Assignment?>()

    /** The one place where a property without an initialiser is given its value while its object is made. */
    private class Assignment(val value: Code, val within: Block)

    /**
     * The expression that made the value of [code].
     *
     * @param settled do not look behind a `var`, which may have been assigned since it was
     *   initialised. Names and durations need that certainty; finding out what kind of stub a
     *   variable holds does not, because a stub is not replaced by one of another kind.
     */
    fun origin(code: Code, scope: Scope, settled: Boolean = true): Traced = origin(code, scope, settled, 0)

    /**
     * The way from [code] to the expression that made its value, which is what [origin] gives:
     * the variables and properties that held the value, and the calls that handed it back.
     */
    fun trail(code: Code, scope: Scope, settled: Boolean = true): Trail {
        val passed = ArrayList<Traced>()
        val entered = HashSet<Scope>()
        origin(code, scope, settled, 0, passed, entered)
        val kept = passed.withIndex().any { (index, here) ->
            when (here.code) {
                is PropertyRead, is ThisReference -> true
                // A local name is declared in the scope it is read in or in one around it; a parameter is given its value from elsewhere.
                is LocalRead -> passed.getOrNull(index + 1)?.scope?.let { it.encloses(here.scope) && it !in entered } ?: true
                else -> false
            }
        }
        return Trail(passed, kept)
    }

    /**
     * @param passed is given every expression on the way, the one that made the value last
     * @param entered is given the scopes of the functions and lambdas whose value the way leads through
     */
    private fun origin(
        code: Code,
        scope: Scope,
        settled: Boolean,
        depth: Int,
        passed: MutableList<Traced>? = null,
        entered: MutableSet<Scope>? = null,
    ): Traced {
        val here = Traced(code, scope)
        passed?.add(here)
        if (depth >= MAX_STEPS) return here
        val next = when (code) {
            is LocalRead -> scope.find(code.name, settled)
            is PropertyRead -> property(code, scope, settled, depth)
            is ThisReference -> scope.self
            is Other -> code.operands.singleOrNull()?.takeIf { code.operator in TRANSPARENT_OPERATORS }?.let { Traced(it, scope) }
            is Call -> result(code, scope)
            else -> null
        }
        if (next == null) return here
        if (code is Call && next.scope !== scope) entered?.add(next.scope)
        return origin(next.code, next.scope, settled, depth + 1, passed, entered)
    }

    /**
     * What a property holds: its initialiser, or for a property that is a parameter of the
     * constructor, the argument given where the object was made, if that place is known.
     */
    private fun property(read: PropertyRead, scope: Scope, settled: Boolean, depth: Int): Traced? {
        val property = read.property?.takeUnless { settled && isReassignable(it) } ?: return null
        val owner = when {
            read.onThis || read.receiver is ThisReference -> scope.self
            else -> read.receiver?.let { Traced(it, scope) }
        }
        val made = owner?.let { construction(it, settled, depth) }
        backend.initializer(property)?.let { initializer ->
            // The initialiser may name the parameters of the constructor.
            val inner = Scope.none(owner)
            made?.let { bindArguments(it, inner) }
            return Traced(initializer, inner)
        }
        val call = made?.code as? Call
        call?.arguments?.firstOrNull { it.parameter == read.name }?.let { return Traced(it.value, made.scope) }
        // The names of the block the assignment stands in are those the value may use.
        return assignment(property)?.let { Traced(it.value, Scope.of(it.within, Scope.none(owner))) }
    }

    /**
     * Where the class of [property] gives it its value outside its declaration: the one plain
     * assignment to it in the `init` blocks and constructor bodies of the class. `null` if there
     * is none, or more than one, so that it cannot be told which one counts.
     */
    private fun assignment(property: PropertyId): Assignment? =
        assignments.getOrPut(property) {
            val owner = property.owner ?: return@getOrPut null
            val found = LinkedHashMap<Code, Assignment>()
            for (constructor in backend.constructors(owner)) {
                val made = backend.construction(constructor.id) ?: continue
                for (block in made.initializers.filterIsInstance<Block>() + listOfNotNull(made.body)) {
                    for (write in block.walk().filterIsInstance<PropertyWrite>()) {
                        val onObject = write.onThis || write.receiver is ThisReference
                        val value = write.value
                        if (write.property == property && onObject && write.operator == ASSIGNMENT && value != null) {
                            found.getOrPut(write) { Assignment(value, block) }
                        }
                    }
                }
            }
            found.values.singleOrNull()
        }

    /** The call of a constructor that made the object [value] evaluates to, if it can be found. */
    private fun construction(value: Traced, settled: Boolean, depth: Int): Traced? =
        origin(value.code, value.scope, settled, depth + 1)
            .takeIf { (it.code as? Call)?.callee?.kind == CalleeKind.CONSTRUCTOR }

    private fun bindArguments(made: Traced, scope: Scope) {
        for (argument in (made.code as Call).arguments) {
            scope.bind(argument.parameter ?: continue, Traced(argument.value, made.scope))
        }
    }

    /**
     * The class of the object [code] evaluates to, where the constructor call that made it can be
     * found: then the object is of that class and of no subclass.
     */
    fun constructedClass(code: Code, scope: Scope): String? =
        (construction(Traced(code, scope), settled = true, depth = 0)?.code as? Call)?.callee?.function?.owner

    private fun isReassignable(property: PropertyId): Boolean =
        reassignable.getOrPut(property) {
            val owner = property.owner ?: return@getOrPut false
            backend.properties(owner).firstOrNull { it.id == property }?.isMutable == true
        }

    /** The expression whose value a call returns, where that can be told without running anything. */
    private fun result(call: Call, scope: Scope): Traced? {
        val callee = call.callee ?: return null
        if (callee.kind != CalleeKind.FUNCTION) return null
        val function = callee.function
        when (function.name) {
            // The value of the lambda.
            "kotlin.run", "kotlin.let", "kotlin.with", "kotlin.lazy" -> {
                val lambda = call.arguments.lastOrNull()?.value as? Lambda ?: return null
                return valueOf(lambda.body, Scope.of(lambda.body, scope.child().also { hidden ->
                    lambda.parameters.forEach { hidden.bind(it, null) }
                }))
            }
            // The object they are called on.
            "kotlin.also", "kotlin.apply" -> return call.receiver?.let { Traced(it, scope) }
        }
        if (!callee.inSource) return null
        val body = backend.body(function) ?: return null
        val inner = Scope.of(body)
        // Inside, `this` is what the function was called on.
        inner.self = call.receiver?.takeUnless { it is ThisReference }?.let { Traced(it, scope) } ?: scope.self
        // A parameter stands for the argument it was given, or else for its default value.
        backend.function(function)?.parameters?.forEach { parameter ->
            inner.bind(parameter.name, backend.defaultValue(function, parameter.name)?.let { Traced(it, Scope.none()) })
        }
        for (argument in call.arguments) {
            val parameter = argument.parameter ?: continue
            inner.bind(parameter, Traced(argument.value, scope))
        }
        return valueOf(body, inner)
    }

    /** What a block evaluates to: what its only `return` returns, or else its last expression. */
    private fun valueOf(block: Block, scope: Scope): Traced? {
        val returned = block.walk().filterIsInstance<Return>().mapNotNull { it.value }.toList()
        val value = when (returned.size) {
            0 -> block.statements.lastOrNull()
            1 -> returned.single()
            else -> null
        }
        return value?.let { Traced(it, scope) }
    }

    private companion object {
        /** A trail longer than this is a cycle or not worth following. */
        const val MAX_STEPS = 48

        const val ASSIGNMENT = "="

        /** Operators that hand their operand on unchanged. */
        val TRANSPARENT_OPERATORS = setOf("as", "as?", "!!")
    }
}
