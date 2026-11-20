package io.github.iw.durableatlas.analyser.code

import io.github.iw.durableatlas.ir.SourceLocation

/**
 * A piece of executable code with its names resolved: the body of a function, the initialiser of a
 * property, or any expression or statement inside one.
 *
 * The tree keeps what graph extraction needs (calls with their resolved targets and receiver
 * types, control flow, reads and writes of properties and local variables) and folds everything
 * else into [Other], whose operands are still walked so that no call is lost.
 */
sealed interface Code {
    val location: SourceLocation
}

/** Statements executed in order. The value of a block is the value of its last statement. */
data class Block(
    val statements: List<Code>,
    override val location: SourceLocation,
) : Code

/** A call of a function or constructor. */
data class Call(
    /** What is called; `null` if the call could not be resolved. */
    val callee: Callee?,
    /** The name as written at the call site. */
    val name: String,
    /** The explicit receiver, if it is a value. The qualifier of a static or top-level function is not one. */
    val receiver: Code?,
    /**
     * Type of the object the call is made on, explicit or implicit: the dispatch receiver of a
     * member function, otherwise the extension receiver. `null` for static and top-level functions.
     */
    val receiverType: TypeRef?,
    /**
     * Type arguments, explicit or inferred, in the order of the callee's type parameters; for a
     * call that could not be resolved, those that are written out.
     */
    val typeArguments: List<TypeRef>,
    /** Arguments in the order written, trailing lambdas included. */
    val arguments: List<Argument>,
    /** Type of the value the call produces. */
    val type: TypeRef?,
    override val location: SourceLocation,
    /** What the sources still say of a call that could not be resolved; `null` for one that was. */
    val unresolved: Unresolved? = null,
    /**
     * The type arguments as written, for a call that the compiler could not resolve but took for
     * the one function it found by the name, as it does where the type of the object the call is
     * made on is not known; there [typeArguments] are those of that function. `null` for a call
     * that resolved.
     */
    val writtenTypeArguments: List<TypeRef>? = null,
) : Code

/**
 * What the sources still say of a call that could not be resolved: enough to tell a call into a
 * library that is not on the classpath from one that the analysis has to account for.
 */
data class Unresolved(
    /**
     * The functions the compiler was left with and could not choose among or apply, ordered by
     * name and signature; empty if it found none. They need not hold the function that is meant:
     * for a call on an object whose type is not known, the compiler names what happens to be
     * called the same.
     */
    val candidates: List<Callee> = emptyList(),
    /**
     * The fully qualified names that the first name of the expression may stand for, where that
     * name could not be resolved either: the name an import of the file gives it, or else that
     * name in each package the file imports as a whole, and the expression itself where it is
     * written out as a qualified name. In `Missing.make().run()` the first name is `Missing`.
     */
    val names: List<String> = emptyList(),
    /**
     * The name, as written, of the class that the object the call is made on is declared to be
     * of, where that class could not be resolved.
     */
    val receiverType: String? = null,
    /**
     * The fully qualified name of a class or function that the sources declare more than once,
     * where the call is one of a function or constructor of that class, on an object of it, or
     * of that function, and may mean another declaration than the one the analysis reads under
     * that name (see [SourceBackend.duplicateClasses]). The compiler resolved the call, or
     * could not choose; either way nothing tells which code runs.
     */
    val declaredTwice: String? = null,
)

enum class CalleeKind { FUNCTION, CONSTRUCTOR }

/** The declaration a call resolves to. */
data class Callee(
    val function: FunctionId,
    val kind: CalleeKind,
    /**
     * True if the function is declared in the analysed sources. Its body can then be asked for,
     * unless it is a constructor or has none.
     */
    val inSource: Boolean,
)

data class Argument(
    /** Name of the parameter the argument is bound to; `null` if the call could not be resolved. */
    val parameter: String?,
    /** The argument as written. */
    val text: String,
    /** The value of the argument if the compiler can evaluate it. */
    val constant: Constant?,
    val value: Code,
)

/** A lambda or anonymous function. */
data class Lambda(
    /** Parameter names; an implicit single parameter appears as `it`. */
    val parameters: List<String>,
    val body: Block,
    override val location: SourceLocation,
    /**
     * True for an anonymous function, `fun(x) { … }`. A `return` without a label inside one leaves
     * the anonymous function; inside a lambda it leaves the function the lambda is written in.
     */
    val isAnonymousFunction: Boolean = false,
    /**
     * The label written in front of it, as in `outer@{ … }`, which a `return` may name. Without
     * one, a lambda goes by the name of the function it is handed to.
     */
    val label: String? = null,
    /**
     * False if the lambda gives nothing back: what its last statement evaluates to is dropped,
     * like that of every statement before it. True where that is the value of the lambda, and
     * where it cannot be told.
     */
    val yieldsValue: Boolean = true,
) : Code

/** A reference to a function or property, such as `stub::method`. */
data class CallableReference(
    /** The bound receiver, if the reference has one and it is a value. */
    val receiver: Code?,
    val receiverType: TypeRef?,
    /** The function referred to, if the reference resolves to one. */
    val function: Callee?,
    /** The property referred to, if the reference resolves to one. */
    val property: PropertyId?,
    val text: String,
    override val location: SourceLocation,
) : Code

/** A class literal, `Type::class`. */
data class ClassLiteral(
    val type: TypeRef?,
    override val location: SourceLocation,
) : Code

/** A read of a property, a Java field or an enum entry. */
data class PropertyRead(
    /** The property read; `null` if it could not be resolved. */
    val property: PropertyId?,
    val name: String,
    /** The explicit receiver, if it is a value. */
    val receiver: Code?,
    /** True if the property belongs to the object the enclosing function runs on. */
    val onThis: Boolean,
    val type: TypeRef?,
    /** The value of the property if the compiler can evaluate it. */
    val constant: Constant?,
    override val location: SourceLocation,
) : Code

/** An assignment to a property, a compound assignment, or an increment or decrement. */
data class PropertyWrite(
    val property: PropertyId?,
    val name: String,
    val receiver: Code?,
    /** True if the property belongs to the object the enclosing function runs on. */
    val onThis: Boolean,
    /** The operator as written: `=`, `+=`, `++` and so on. */
    val operator: String,
    /** The right-hand side; `null` for an increment or decrement. */
    val value: Code?,
    override val location: SourceLocation,
) : Code

/** A read of a local variable or a parameter. */
data class LocalRead(
    val name: String,
    val type: TypeRef?,
    override val location: SourceLocation,
) : Code

/** An assignment to a local variable, a compound assignment, or an increment or decrement. */
data class LocalWrite(
    val name: String,
    val operator: String,
    val value: Code?,
    override val location: SourceLocation,
) : Code

/** The declaration of a local variable. */
data class LocalVariable(
    val name: String,
    val type: TypeRef?,
    /** The initialiser or delegate expression. */
    val initializer: Code?,
    val isMutable: Boolean,
    override val location: SourceLocation,
) : Code

/** A destructuring declaration, `val (a, b) = value`. */
data class Destructuring(
    val names: List<String>,
    val initializer: Code?,
    override val location: SourceLocation,
) : Code

/** `this`, written out. */
data class ThisReference(
    val type: TypeRef?,
    override val location: SourceLocation,
) : Code

/** A reference to a class or object by name, where it is used as a value. */
data class ClassReference(
    /** Fully qualified name; `null` if unresolved. */
    val name: String?,
    val text: String,
    override val location: SourceLocation,
) : Code

data class If(
    val condition: Code,
    /** The condition as written. */
    val conditionText: String,
    val then: Code?,
    val otherwise: Code?,
    override val location: SourceLocation,
) : Code

data class When(
    val subject: Code?,
    /** The subject as written; `null` for a `when` without one. */
    val subjectText: String?,
    val branches: List<WhenBranch>,
    override val location: SourceLocation,
) : Code

data class WhenBranch(
    val conditions: List<Code>,
    /** The conditions as written, or `else`. */
    val conditionText: String,
    val isElse: Boolean,
    val body: Code?,
    val location: SourceLocation,
)

enum class LoopKind { FOR, WHILE, DO_WHILE }

/** A `for`, `while` or `do`-`while` loop. Loops made of collection functions and lambdas are [Call]s. */
data class Loop(
    val kind: LoopKind,
    /** What controls the loop, as written: `item in items`, or the condition. */
    val headerText: String,
    /** The collection iterated over, or the condition. */
    val header: Code?,
    val body: Code?,
    override val location: SourceLocation,
    /** The label of the loop, which a `break` or `continue` may name. */
    val label: String? = null,
) : Code

data class Try(
    val body: Block,
    val catches: List<Catch>,
    val finally: Block?,
    override val location: SourceLocation,
) : Code

data class Catch(
    val parameter: String?,
    /** The exception type caught. */
    val type: TypeRef?,
    val body: Block,
    val location: SourceLocation,
)

data class Return(
    val value: Code?,
    /** The label of `return@label`. */
    val label: String?,
    override val location: SourceLocation,
) : Code

data class Throw(
    val value: Code?,
    /** Type of the exception thrown. */
    val type: TypeRef?,
    override val location: SourceLocation,
) : Code

enum class JumpKind { BREAK, CONTINUE }

data class Jump(
    val kind: JumpKind,
    val label: String?,
    override val location: SourceLocation,
) : Code

/** A literal, or a string template without interpolation. */
data class Literal(
    val constant: Constant,
    override val location: SourceLocation,
) : Code

/**
 * Any other expression: an operator, a string template, a cast, an object literal. Its [operands]
 * are the sub-expressions in evaluation order; with `&&`, `||` and `?:` the later ones may not run.
 */
data class Other(
    /** The operator, where there is one: `+`, `==`, `?:`, `as`, `template` and so on. */
    val operator: String?,
    /** The expression as written. */
    val text: String,
    val operands: List<Code>,
    override val location: SourceLocation,
) : Code
