// In this release of the Analysis API the stable accessors of a resolved call are deprecated in
// favour of the ones on KaSingleCall, which are still marked experimental.
@file:OptIn(KaExperimentalApi::class)

package io.github.iw.durableatlas.analyser.backend

import com.intellij.psi.PsiElement
import io.github.iw.durableatlas.analyser.code.Argument
import io.github.iw.durableatlas.analyser.code.Block
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CallableReference
import io.github.iw.durableatlas.analyser.code.Callee
import io.github.iw.durableatlas.analyser.code.Catch
import io.github.iw.durableatlas.analyser.code.ClassLiteral
import io.github.iw.durableatlas.analyser.code.ClassReference
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Constant
import io.github.iw.durableatlas.analyser.code.Construction
import io.github.iw.durableatlas.analyser.code.Destructuring
import io.github.iw.durableatlas.analyser.code.If
import io.github.iw.durableatlas.analyser.code.Jump
import io.github.iw.durableatlas.analyser.code.JumpKind
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.Literal
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.LocalVariable
import io.github.iw.durableatlas.analyser.code.LocalWrite
import io.github.iw.durableatlas.analyser.code.Loop
import io.github.iw.durableatlas.analyser.code.LoopKind
import io.github.iw.durableatlas.analyser.code.Other
import io.github.iw.durableatlas.analyser.code.PropertyId
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.PropertyWrite
import io.github.iw.durableatlas.analyser.code.Return
import io.github.iw.durableatlas.analyser.code.ThisReference
import io.github.iw.durableatlas.analyser.code.Throw
import io.github.iw.durableatlas.analyser.code.Try
import io.github.iw.durableatlas.analyser.code.TypeRef
import io.github.iw.durableatlas.analyser.code.Unresolved
import io.github.iw.durableatlas.analyser.code.When
import io.github.iw.durableatlas.analyser.code.WhenBranch
import io.github.iw.durableatlas.ir.SourceLocation
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaCompoundVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSingleCall
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.singleVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulVariableAccessCall
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaJavaFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotatedExpression
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDoubleColonExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtWhenConditionInRange
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.ValueArgument
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Lowers Kotlin syntax to the tool's [Code] tree, resolving names on the way. One instance serves
 * one `analyze` block: it holds on to the [KaSession] of that block.
 *
 * @param root the declaration whose code is lowered; it decides what `this` refers to
 */
internal class Lowering(
    private val ka: KaSession,
    private val reader: SymbolReader,
    root: KtDeclaration,
    private val failures: Failures,
) {
    private val enclosingClass: KtClassOrObject? = root as? KtClassOrObject ?: root.containingClassOrObject

    /** Where the code being lowered stands, for telling whether a name that is declared more than once can be trusted. */
    private val referrer = Referrer(reader.moduleOf(root), generateSequence(enclosingClass) { it.containingClassOrObject }.toList())

    /** How many uses of such a name were met that cannot be trusted: none of them resolves to anything. */
    private var doubts = 0

    /** The body of [function]; an expression body is returned as a block that returns the expression. */
    fun functionBody(function: KtNamedFunction): Block? {
        val body = function.bodyExpression ?: return null
        if (function.hasBlockBody() && body is KtBlockExpression) return block(body)
        val location = location(body)
        return Block(listOf(Return(lower(body), null, location)), location)
    }

    /** The body of a property accessor, in the form [functionBody] gives that of a function. */
    fun accessorBody(accessor: KtPropertyAccessor): Block? {
        val body = accessor.bodyExpression ?: return null
        if (accessor.hasBlockBody() && body is KtBlockExpression) return block(body)
        val location = location(body)
        return Block(listOf(Return(lower(body), null, location)), location)
    }

    /**
     * Code that is no function of its own, as a block: the body of an `init` block or of a
     * constructor, or the one expression a property is given.
     */
    fun standalone(expression: KtExpression): Block =
        when (val code = lower(expression)) {
            is Block -> code
            else -> Block(listOf(code), code.location)
        }

    /**
     * What a constructor of the class [owner] runs. [written] is the constructor, if it is
     * written out; otherwise it is the one the class gets without writing any.
     */
    fun construction(owner: KtClassOrObject, written: KtConstructor<*>?): Construction {
        val secondary = written as? KtSecondaryConstructor
        val delegation = secondary?.getDelegationCall()
        val delegate = when {
            delegation != null -> delegated(delegation, if (delegation.isCallToThis) "this" else "super")
            else -> owner.superTypeListEntries.filterIsInstance<KtSuperTypeCallEntry>().firstOrNull()?.let { delegated(it, "super") }
        }
        val initializers = if (delegation?.isCallToThis == true) emptyList() else owner.declarations.mapNotNull(::initialization)
        return Construction(delegate, initializers, secondary?.bodyExpression?.let(::block))
    }

    /** The constructor that another one hands over to, with the arguments it is given. */
    private fun delegated(call: KtCallElement, name: String): Call? {
        val resolved = resolve(call)?.let { it.successfulFunctionCallOrNull() ?: it.singleFunctionCallOrNull() } ?: return null
        return Call(
            callee = callee(resolved.signature.symbol),
            name = name,
            receiver = null,
            receiverType = null,
            typeArguments = emptyList(),
            arguments = call.valueArguments.map { argument(it, resolved) },
            type = null,
            location = location(call),
        )
    }

    /** What a member of a class runs when an object is made: a property that is given a value, or an `init`. */
    private fun initialization(declaration: KtDeclaration): Code? =
        when (declaration) {
            is KtAnonymousInitializer -> declaration.body?.let(::lower)
            is KtProperty -> {
                val initializer = declaration.initializer
                val property = safely { with(ka) { declaration.symbol } }?.let(::propertyId)
                when {
                    initializer == null -> declaration.delegateExpression?.let(::lower)
                    else -> PropertyWrite(
                        property = property,
                        name = declaration.name.orEmpty(),
                        receiver = null,
                        onThis = true,
                        operator = "=",
                        value = lower(initializer),
                        location = location(declaration),
                    )
                }
            }
            else -> null
        }

    fun lower(expression: KtExpression): Code =
        when (expression) {
            is KtParenthesizedExpression -> lowerOr(expression.expression, expression)
            is KtLabeledExpression -> labelled(expression)
            is KtAnnotatedExpression -> lowerOr(expression.baseExpression, expression)
            is KtBlockExpression -> block(expression)
            is KtQualifiedExpression -> qualified(expression)
            is KtCallExpression -> call(expression, null)
            is KtSimpleNameExpression -> nameReference(expression, null)
            is KtConstantExpression ->
                Literal(constant(expression) ?: Constant.Unsupported(expression.text), location(expression))
            is KtStringTemplateExpression -> template(expression)
            is KtLambdaExpression -> lambda(expression)
            is KtNamedFunction -> localFunction(expression)
            is KtCallableReferenceExpression -> callableReference(expression)
            is KtClassLiteralExpression -> ClassLiteral(classLiteralType(expression), location(expression))
            is KtThisExpression -> ThisReference(typeOf(expression), location(expression))
            is KtIfExpression -> If(
                condition = lowerOr(expression.condition, expression),
                conditionText = expression.condition?.text.orEmpty(),
                then = expression.then?.let(::lower),
                otherwise = expression.`else`?.let(::lower),
                location = location(expression),
            )
            is KtWhenExpression -> whenExpression(expression)
            is KtForExpression -> Loop(
                kind = LoopKind.FOR,
                headerText = listOfNotNull(
                    (expression.loopParameter ?: expression.destructuringDeclaration)?.text,
                    expression.loopRange?.text,
                ).joinToString(" in "),
                header = expression.loopRange?.let(::lower),
                body = expression.body?.let(::lower),
                location = location(expression),
            )
            is KtWhileExpression -> Loop(
                kind = LoopKind.WHILE,
                headerText = expression.condition?.text.orEmpty(),
                header = expression.condition?.let(::lower),
                body = expression.body?.let(::lower),
                location = location(expression),
            )
            is KtDoWhileExpression -> Loop(
                kind = LoopKind.DO_WHILE,
                headerText = expression.condition?.text.orEmpty(),
                header = expression.condition?.let(::lower),
                body = expression.body?.let(::lower),
                location = location(expression),
            )
            is KtTryExpression -> tryExpression(expression)
            is KtReturnExpression -> Return(
                value = expression.returnedExpression?.let(::lower),
                label = expression.getLabelName(),
                location = location(expression),
            )
            is KtThrowExpression -> Throw(
                value = expression.thrownExpression?.let(::lower),
                type = expression.thrownExpression?.let(::typeOf),
                location = location(expression),
            )
            is KtBreakExpression -> Jump(JumpKind.BREAK, expression.getLabelName(), location(expression))
            is KtContinueExpression -> Jump(JumpKind.CONTINUE, expression.getLabelName(), location(expression))
            is KtProperty -> LocalVariable(
                name = expression.name.orEmpty(),
                type = safely { with(ka) { expression.returnType } }?.let(::typeRef),
                initializer = (expression.initializer ?: expression.delegateExpression)?.let(::lower),
                isMutable = expression.isVar,
                location = location(expression),
            )
            is KtDestructuringDeclaration -> Destructuring(
                names = expression.entries.map { it.name.orEmpty() },
                initializer = expression.initializer?.let(::lower),
                location = location(expression),
            )
            is KtBinaryExpression -> binary(expression)
            is KtUnaryExpression -> unary(expression)
            is KtBinaryExpressionWithTypeRHS ->
                other(expression, expression.operationReference.text, listOf(expression.left))
            is KtIsExpression ->
                other(expression, expression.operationReference.text, listOf(expression.leftHandSide))
            is KtArrayAccessExpression ->
                other(expression, "[]", listOfNotNull(expression.arrayExpression) + expression.indexExpressions)
            // What an object literal or a local declaration contains is not part of the enclosing flow.
            is KtObjectLiteralExpression -> other(expression, "object", emptyList())
            is KtDeclaration -> other(expression, "declaration", emptyList())
            else -> other(expression, null, expression.children.filterIsInstance<KtExpression>())
        }

    /** A label is kept where a jump can name it: on a loop, and on a lambda or anonymous function. */
    private fun labelled(expression: KtLabeledExpression): Code {
        val lowered = lowerOr(expression.baseExpression, expression)
        val label = expression.getLabelName() ?: return lowered
        return when (lowered) {
            is Loop -> lowered.copy(label = label)
            is Lambda -> lowered.copy(label = label)
            else -> lowered
        }
    }

    private fun lowerOr(expression: KtExpression?, fallback: KtExpression): Code =
        expression?.let(::lower) ?: other(fallback, null, emptyList())

    private fun other(expression: KtExpression, operator: String?, operands: List<KtExpression>): Other =
        Other(operator, expression.text, operands.map(::lower), location(expression))

    private fun block(block: KtBlockExpression): Block = Block(block.statements.map(::lower), location(block))

    // ---- calls

    private fun qualified(expression: KtQualifiedExpression): Code =
        when (val selector = expression.selectorExpression) {
            is KtCallExpression -> call(selector, expression)
            is KtSimpleNameExpression -> nameReference(selector, expression)
            else -> other(expression, ".", listOfNotNull(expression.receiverExpression, selector))
        }

    private fun call(call: KtCallExpression, qualified: KtQualifiedExpression?): Code {
        val whole: KtExpression = qualified ?: call
        val location = location(call.calleeExpression ?: call)
        val name = call.calleeExpression?.text ?: call.text
        val info = resolve(call)
        val resolved = info?.let { it.successfulFunctionCallOrNull() ?: it.singleFunctionCallOrNull() }
        val arguments = call.valueArguments.map { argument(it, resolved) }
        if (resolved == null) {
            return Call(
                callee = null,
                name = name,
                receiver = qualified?.receiverExpression?.let(::lower),
                receiverType = null,
                typeArguments = writtenTypeArguments(call),
                arguments = arguments,
                type = typeOf(whole),
                location = location,
                unresolved = unresolved(call, qualified, info),
            )
        }
        // The class in front of a static function is a qualifier, not an object the call is made on.
        val static = isStatic(resolved.signature.symbol)
        val called = called(resolved)
        if (called.doubted != null) {
            return Call(
                callee = null,
                name = name,
                receiver = explicitReceiver(resolved)?.takeUnless { static || it === whole || it === call }?.let(::lower),
                receiverType = null,
                typeArguments = typeArguments(resolved),
                arguments = arguments,
                type = typeOf(whole),
                location = location,
                unresolved = Unresolved(declaredTwice = called.doubted),
            )
        }
        return Call(
            callee = called.callee,
            name = name,
            receiver = explicitReceiver(resolved)
                ?.takeUnless { static || it === whole || it === call }
                ?.let(::lower),
            receiverType = if (static) null else receiverTypeOf(resolved),
            typeArguments = typeArguments(resolved),
            arguments = arguments,
            type = typeOf(whole),
            location = location,
            // The compiler took the call for the one function it found by the name, which need not be the one meant.
            writtenTypeArguments = if (info.successfulFunctionCallOrNull() == null) writtenTypeArguments(call) else null,
        )
    }

    private fun writtenTypeArguments(call: KtCallExpression): List<TypeRef> =
        call.typeArguments.mapNotNull { written ->
            written.typeReference?.let { reference -> safely { with(ka) { reference.type } } }?.let(::typeRef)
        }

    // ---- calls that do not resolve

    /** What can still be said of [call], which the compiler could not resolve. */
    private fun unresolved(call: KtCallExpression, qualified: KtQualifiedExpression?, info: KaCallInfo?): Unresolved {
        val named = (info as? KaErrorCallInfo)?.candidateCalls.orEmpty()
            .filterIsInstance<KaFunctionCall<*>>()
            .mapNotNull { candidate -> safely { candidate.signature.symbol to callee(candidate.signature.symbol) } }
            .distinctBy { it.first }
        val candidates = named.map { it.second }.distinct().sortedBy { it.function.toString() }
        // Declarations of the sources that are told apart by nothing: only one of them is known under their name.
        val twice = named.map { it.second }.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 && it.key.inSource }?.key
        return Unresolved(
            candidates = candidates,
            names = writtenNames(call, qualified),
            receiverType = qualified?.receiverExpression?.let(::missingClassOf),
            declaredTwice = twice?.function?.name,
        )
    }

    /**
     * The name, as written, of the class that the value of [expression] is declared to be of,
     * where the sources declare it with a type whose class could not be resolved: the type of
     * the variable, parameter or property that is read, or the one the function called returns.
     */
    private fun missingClassOf(expression: KtExpression?): String? {
        val name = when (expression) {
            is KtParenthesizedExpression -> return missingClassOf(expression.expression)
            is KtQualifiedExpression -> return missingClassOf(expression.selectorExpression)
            is KtPostfixExpression -> return missingClassOf(expression.baseExpression)
            is KtSimpleNameExpression -> expression
            is KtCallExpression -> expression.calleeExpression as? KtSimpleNameExpression
            else -> null
        } ?: return null
        return safely { with(ka) { name.mainReference.resolveToSymbol()?.let { symbol -> with(reader) { missingClassOf(symbol) } } } }
    }

    /**
     * The fully qualified names that the first name of an unresolved call may stand for, if
     * that name does not resolve either: what the imports of the file make of it, and the
     * qualified name the call is written out as, where it is one.
     */
    private fun writtenNames(call: KtCallExpression, qualified: KtQualifiedExpression?): List<String> {
        val first = firstName(qualified ?: call) ?: return emptyList()
        val found = safely { with(ka) { first.mainReference.resolveToSymbols() } }.orEmpty()
        // A name that stands for something says nothing more; one that only stands for a package begins a qualified name.
        if (found.any { it !is KaPackageSymbol }) return emptyList()
        val names = LinkedHashSet<String>()
        if (found.isEmpty()) {
            val name = first.getReferencedName()
            val imports = first.containingKtFile.importDirectives
            for (directive in imports) {
                val imported = directive.importedFqName ?: continue
                if (!directive.isAllUnder && (directive.aliasName ?: imported.shortName().asString()) == name) names += imported.asString()
            }
            // A name that no import gives may come from any package that is imported as a whole.
            if (names.isEmpty()) {
                for (directive in imports) {
                    if (directive.isAllUnder) directive.importedFqName?.let { names += "${it.asString()}.$name" }
                }
            }
        }
        val qualifier = qualified?.receiverExpression?.let(::dottedName)
        if (qualifier != null && qualifier.contains('.')) names += "$qualifier.${call.calleeExpression?.text.orEmpty()}"
        return names.toList()
    }

    /** The name an expression begins with: that of the first thing it reads or calls. */
    private fun firstName(expression: KtExpression?): KtSimpleNameExpression? =
        when (expression) {
            is KtQualifiedExpression -> firstName(expression.receiverExpression)
            is KtCallExpression -> expression.calleeExpression as? KtSimpleNameExpression
            is KtSimpleNameExpression -> expression
            is KtParenthesizedExpression -> firstName(expression.expression)
            is KtPostfixExpression -> firstName(expression.baseExpression)
            is KtBinaryExpressionWithTypeRHS -> firstName(expression.left)
            is KtArrayAccessExpression -> firstName(expression.arrayExpression)
            else -> null
        }

    /** [expression] as a qualified name, if it is nothing but names joined by dots. */
    private fun dottedName(expression: KtExpression): String? =
        when (expression) {
            is KtSimpleNameExpression -> expression.getReferencedName()
            is KtQualifiedExpression -> {
                val selector = expression.selectorExpression as? KtSimpleNameExpression
                val before = dottedName(expression.receiverExpression)
                if (selector == null || before == null) null else "$before.${selector.getReferencedName()}"
            }
            else -> null
        }

    /** A call written with an operator or as an infix function. */
    private fun operatorCall(resolved: KaFunctionCall<*>, name: String, whole: KtExpression): Call {
        val called = called(resolved)
        val doubted = called.doubted
        return Call(
            callee = called.callee,
            name = name,
            receiver = explicitReceiver(resolved)?.takeUnless { it === whole }?.let(::lower),
            receiverType = if (doubted == null) receiverTypeOf(resolved) else null,
            typeArguments = typeArguments(resolved),
            arguments = resolved.valueArgumentMapping.map { (expression, parameter) -> argument(parameter.symbol.name.asString(), expression) },
            type = typeOf(whole),
            location = location(whole),
            unresolved = doubted?.let { Unresolved(declaredTwice = it) },
        )
    }

    /** What a call resolves to: the function, or the name that keeps the call from being trusted. */
    private class Called(val callee: Callee?, val doubted: String?)

    /**
     * What the call [resolved] calls, unless a name that the sources declare more than once
     * keeps it from being trusted: the class of the function or constructor called, the class
     * of the object it is called on, or the function itself where another declaration is read
     * under its name. Such a call is lowered as one that did not resolve.
     */
    private fun called(resolved: KaFunctionCall<*>): Called {
        val symbol = resolved.signature.symbol
        // A member of a class that is declared twice is not looked at any further: it must not be taken for the one that is read.
        val twice = safely { with(ka) { with(reader) { doubted(symbol, referrer) ?: doubted(resolved.dispatchReceiver?.type, referrer) } } }
        val callee = if (twice == null) callee(symbol) else null
        val doubted = twice ?: callee?.takeIf { it.inSource }?.let { safely { with(ka) { with(reader) { shadowed(symbol, it.function) } } } }
        if (doubted != null) doubts++
        return Called(callee.takeIf { doubted == null }, doubted)
    }

    /** The name that keeps a read or a write of the property [id] from being trusted, if there is one; see [called]. */
    private fun doubted(applied: KaVariableAccessCall, id: PropertyId): String? {
        val symbol = applied.signature.symbol
        val name = safely {
            with(ka) { with(reader) { doubted(symbol, referrer) ?: doubted(applied.dispatchReceiver?.type, referrer) ?: shadowed(symbol, id) } }
        }
        if (name != null) doubts++
        return name
    }

    /**
     * An argument. What the compiler makes of it as a constant is kept only where everything in
     * it can be trusted: a constant that is read off a declaration which may not be the one
     * meant is none.
     */
    private fun argument(parameter: String?, expression: KtExpression): Argument {
        val before = doubts
        val value = lower(expression)
        return Argument(parameter, expression.text, if (doubts == before) constant(expression) else null, value)
    }

    private fun argument(argument: ValueArgument, resolved: KaFunctionCall<*>?): Argument {
        val expression = argument.getArgumentExpression()
        val written = argument.getArgumentName()?.asName?.asString()
        if (expression == null) {
            val element = argument.asElement()
            return Argument(written, element.text, null, Other(null, element.text, emptyList(), location(element)))
        }
        val parameter = resolved?.valueArgumentMapping?.get(expression)?.symbol?.name?.asString()
        return argument(parameter ?: written, expression)
    }

    private fun typeArguments(resolved: KaFunctionCall<*>): List<TypeRef> {
        val mapping = resolved.typeArgumentsMapping
        if (mapping.isEmpty()) return emptyList()
        val parameters = when (val symbol = resolved.signature.symbol) {
            is KaNamedFunctionSymbol -> symbol.typeParameters
            is KaConstructorSymbol -> symbol.typeParameters
            else -> emptyList()
        }
        val ordered = parameters.mapNotNull { mapping[it] }
        return (if (ordered.size == mapping.size) ordered else mapping.values).map(::typeRef)
    }

    // ---- names

    private fun nameReference(name: KtSimpleNameExpression, qualified: KtQualifiedExpression?): Code {
        val whole: KtExpression = qualified ?: name
        val location = location(name)
        val access = resolve(name)?.let { it.successfulVariableAccessCall() ?: it.singleVariableAccessCall() }
        if (access != null) return read(access, name, whole, location)

        // Not a variable: a class or an object used as a value, an enum entry, or nothing known.
        return when (val target = safely { with(ka) { name.mainReference.resolveToSymbol() } }) {
            is KaClassLikeSymbol -> ClassReference(target.classId?.asFqNameString(), whole.text, location)
            is KaVariableSymbol -> {
                val property = propertyId(target)
                val doubted = property?.let { safely { with(ka) { with(reader) { doubted(target, referrer) ?: shadowed(target, it) } } } }?.also { doubts++ }
                when {
                    property == null -> LocalRead(name.getReferencedName(), typeOf(whole), location)
                    doubted != null -> PropertyRead(null, name.getReferencedName(), null, false, typeOf(whole), null, location)
                    else -> PropertyRead(property, name.getReferencedName(), null, false, typeOf(whole), constant(whole), location)
                }
            }
            else -> PropertyRead(
                property = null,
                name = name.getReferencedName(),
                receiver = qualified?.receiverExpression?.let(::lower),
                onThis = false,
                type = typeOf(whole),
                constant = null,
                location = location,
            )
        }
    }

    private fun read(
        applied: KaVariableAccessCall,
        name: KtSimpleNameExpression,
        whole: KtExpression,
        location: SourceLocation,
    ): Code {
        val symbol = applied.signature.symbol
        val property = propertyId(symbol)
            ?: return LocalRead(name.getReferencedName(), typeOf(whole), location)
        // A property of a class that is declared more than once may not be the one that is read under its name.
        val trusted = doubted(applied, property) == null
        return PropertyRead(
            property = property.takeIf { trusted },
            name = name.getReferencedName(),
            receiver = explicitReceiver(applied)?.takeUnless { isStatic(symbol) || it === whole }?.let(::lower),
            onThis = isThis(applied.dispatchReceiver ?: applied.extensionReceiver),
            type = typeOf(whole),
            constant = if (trusted) constant(whole) else null,
            location = location,
        )
    }

    private fun write(
        applied: KaVariableAccessCall?,
        target: KtExpression?,
        operator: String,
        value: Code?,
        location: SourceLocation,
    ): Code {
        val qualified = target as? KtQualifiedExpression
        val name = when (target) {
            is KtSimpleNameExpression -> target.getReferencedName()
            is KtQualifiedExpression -> (target.selectorExpression as? KtSimpleNameExpression)?.getReferencedName()
            else -> null
        } ?: target?.text.orEmpty()
        if (applied == null) {
            return PropertyWrite(null, name, qualified?.receiverExpression?.let(::lower), false, operator, value, location)
        }
        val symbol = applied.signature.symbol
        val property = propertyId(symbol) ?: return LocalWrite(name, operator, value, location)
        return PropertyWrite(
            property = property.takeIf { doubted(applied, it) == null },
            name = name,
            receiver = explicitReceiver(applied)?.takeUnless { isStatic(symbol) || it === target }?.let(::lower),
            onThis = isThis(applied.dispatchReceiver ?: applied.extensionReceiver),
            operator = operator,
            value = value,
            location = location,
        )
    }

    // ---- operators

    private fun binary(expression: KtBinaryExpression): Code {
        val operator = expression.operationReference.text
        val token = expression.operationToken
        val left = expression.left
        val right = expression.right
        val location = location(expression)

        if (token == KtTokens.EQ) {
            val target = left?.let(::withoutParentheses)
            if (target is KtArrayAccessExpression) {
                return other(expression, "[]=", listOfNotNull(target.arrayExpression) + target.indexExpressions + listOfNotNull(right))
            }
            val name = when (target) {
                is KtSimpleNameExpression -> target
                is KtQualifiedExpression -> target.selectorExpression as? KtSimpleNameExpression
                else -> null
            }
            val access = name?.let(::resolve)?.let { it.successfulVariableAccessCall() ?: it.singleVariableAccessCall() }
            return write(access, target, operator, right?.let(::lower), location)
        }

        if (token in KtTokens.AUGMENTED_ASSIGNMENTS) {
            return when (val call = resolve(expression)?.successfulCallOrNull<KaCall>()) {
                is KaCompoundVariableAccessCall ->
                    write(call.variableCall, left?.let(::withoutParentheses), operator, right?.let(::lower), location)
                is KaFunctionCall<*> -> operatorCall(call, operator, expression)
                else -> other(expression, operator, listOfNotNull(left, right))
            }
        }

        // An infix function is a call like any other. An operator is one only if the function
        // behind it is part of the sources, where it may be worth following.
        val resolved = resolve(expression)?.successfulFunctionCallOrNull()
        if (resolved != null && (token == KtTokens.IDENTIFIER || callee(resolved.signature.symbol).inSource)) {
            return operatorCall(resolved, operator, expression)
        }
        return other(expression, operator, listOfNotNull(left, right))
    }

    private fun unary(expression: KtUnaryExpression): Code {
        val operator = expression.operationReference.text
        val token = expression.operationToken
        if (token == KtTokens.PLUSPLUS || token == KtTokens.MINUSMINUS) {
            val call = resolve(expression)?.successfulCallOrNull<KaCall>()
            if (call is KaCompoundVariableAccessCall) {
                val target = expression.baseExpression?.let(::withoutParentheses)
                return write(call.variableCall, target, operator, null, location(expression))
            }
        }
        return other(expression, operator, listOfNotNull(expression.baseExpression))
    }

    // ---- other expressions

    private fun template(expression: KtStringTemplateExpression): Code {
        val interpolated = expression.entries.mapNotNull { (it as? KtStringTemplateEntryWithExpression)?.expression }
        if (interpolated.isNotEmpty()) return other(expression, "template", interpolated)
        val value = constant(expression) ?: Constant.Text(expression.entries.joinToString("") { it.text })
        return Literal(value, location(expression))
    }

    private fun lambda(expression: KtLambdaExpression): Code {
        val literal = expression.functionLiteral
        val parameters = safely { with(ka) { literal.symbol.valueParameters.map { it.name.asString() } } }
            ?: literal.valueParameters.map { it.name.orEmpty() }
        val location = location(expression)
        // What a lambda of a function type that returns nothing ends with is no value of it.
        val returnsNothing = safely { with(ka) { literal.symbol.returnType.isUnitType } } == true
        return Lambda(parameters, literal.bodyExpression?.let(::block) ?: Block(emptyList(), location), location, yieldsValue = !returnsNothing)
    }

    /** An anonymous function is a lambda in other clothes; a named local function is only noted. */
    private fun localFunction(function: KtNamedFunction): Code {
        if (function.name != null) return other(function, "declaration", emptyList())
        val location = location(function)
        val body = function.bodyExpression
        val block = when {
            body == null -> Block(emptyList(), location)
            function.hasBlockBody() && body is KtBlockExpression -> block(body)
            else -> Block(listOf(Return(lower(body), null, location(body))), location(body))
        }
        // A body that is an expression is lowered as a `return` of it, so no last statement is ever a value here.
        return Lambda(function.valueParameters.map { it.name.orEmpty() }, block, location, isAnonymousFunction = true, yieldsValue = false)
    }

    private fun callableReference(expression: KtCallableReferenceExpression): Code {
        val target = safely { with(ka) { expression.callableReference.mainReference.resolveToSymbols() } }
            ?.firstOrNull()
        return CallableReference(
            receiver = expression.receiverExpression?.let(::lower),
            receiverType = receiverType(expression)?.let { type ->
                if (type.name == null) type.copy(unresolved = missingClassOf(expression.receiverExpression)) else type
            },
            // A function of a class that is declared more than once is not known to be the one that is read under its name.
            function = (target as? KaFunctionSymbol)?.takeIf { safely { with(ka) { with(reader) { doubted(it, referrer) } } } == null }?.let(::callee),
            property = (target as? KaVariableSymbol)?.let(::propertyId),
            text = expression.text,
            location = location(expression),
        )
    }

    private fun whenExpression(expression: KtWhenExpression): Code =
        When(
            subject = (expression.subjectVariable ?: expression.subjectExpression)?.let(::lower),
            subjectText = (expression.subjectVariable ?: expression.subjectExpression)?.text,
            branches = expression.entries.map { entry ->
                val conditions = entry.conditions.mapNotNull { condition ->
                    when (condition) {
                        is KtWhenConditionWithExpression -> condition.expression
                        is KtWhenConditionInRange -> condition.rangeExpression
                        else -> null
                    }
                }
                WhenBranch(
                    conditions = conditions.map(::lower),
                    conditionText = if (entry.isElse) "else" else entry.conditions.joinToString(", ") { it.text },
                    isElse = entry.isElse,
                    body = entry.expression?.let(::lower),
                    location = location(entry),
                )
            },
            location = location(expression),
        )

    private fun tryExpression(expression: KtTryExpression): Code =
        Try(
            body = block(expression.tryBlock),
            catches = expression.catchClauses.map { clause ->
                val parameter = clause.catchParameter
                val body = clause.catchBody
                Catch(
                    parameter = parameter?.name,
                    type = parameter?.typeReference?.let { reference -> safely { with(ka) { reference.type } } }?.let(::typeRef),
                    body = when (body) {
                        null -> Block(emptyList(), location(clause))
                        is KtBlockExpression -> block(body)
                        else -> Block(listOf(lower(body)), location(body))
                    },
                    location = location(clause),
                )
            },
            finally = expression.finallyBlock?.finalExpression?.let(::block),
            location = location(expression),
        )

    // ---- receivers

    private fun explicitReceiver(applied: KaSingleCall<*, *>): KtExpression? =
        explicitExpression(applied.dispatchReceiver) ?: explicitExpression(applied.extensionReceiver)

    private fun explicitExpression(receiver: KaReceiverValue?): KtExpression? =
        when (receiver) {
            is KaSmartCastedReceiverValue -> explicitExpression(receiver.original)
            is KaExplicitReceiverValue -> receiver.expression
            else -> null
        }

    private fun isStatic(symbol: KaSymbol): Boolean =
        when (symbol) {
            is KaNamedFunctionSymbol -> symbol.isStatic
            is KaJavaFieldSymbol -> symbol.isStatic
            is KaPropertySymbol -> symbol.isStatic
            else -> false
        }

    /** The dispatch receiver of a member, otherwise the extension receiver. */
    private fun receiverTypeOf(applied: KaSingleCall<*, *>): TypeRef? =
        (applied.dispatchReceiver ?: applied.extensionReceiver)?.type?.let(::typeRef)

    /** Whether [receiver] is the object that the code being lowered runs on. */
    private fun isThis(receiver: KaReceiverValue?): Boolean =
        when (receiver) {
            null -> false
            is KaSmartCastedReceiverValue -> isThis(receiver.original)
            is KaImplicitReceiverValue -> isEnclosingClass(receiver.symbol)
            is KaExplicitReceiverValue -> {
                val expression = withoutParentheses(receiver.expression)
                expression is KtThisExpression &&
                    isEnclosingClass(safely { with(ka) { expression.instanceReference.mainReference.resolveToSymbol() } })
            }
        }

    private fun isEnclosingClass(symbol: KaSymbol?): Boolean =
        enclosingClass != null && symbol is KaClassSymbol && symbol.psi == enclosingClass

    private fun withoutParentheses(expression: KtExpression): KtExpression {
        var current = expression
        while (current is KtParenthesizedExpression) current = current.expression ?: return current
        return current
    }

    // ---- bridges to the Analysis API

    private fun location(element: PsiElement): SourceLocation = reader.location(element)

    private fun resolve(element: KtElement): KaCallInfo? = safely { with(ka) { element.resolveToCall() } }

    /** The type of [expression]; `null` if it has none or the type is in error. */
    private fun typeOf(expression: KtExpression): TypeRef? =
        safely { with(ka) { expression.expressionType } }?.takeUnless { it is KaErrorType }?.let(::typeRef)

    private fun receiverType(expression: KtDoubleColonExpression): TypeRef? =
        safely { with(ka) { expression.receiverType } }?.let(::typeRef)

    /** The type a class literal names; where its class could not be resolved, with the name as it is written. */
    private fun classLiteralType(expression: KtClassLiteralExpression): TypeRef? {
        val type = safely { with(ka) { expression.receiverType } } ?: return null
        val named = typeRef(type)
        return if (type is KaErrorType) named.copy(unresolved = expression.receiverExpression?.text?.substringAfterLast('.')) else named
    }

    private fun typeRef(type: KaType): TypeRef = with(ka) { with(reader) { typeRef(type) } }

    private fun callee(symbol: KaFunctionSymbol): Callee = with(ka) { with(reader) { callee(symbol) } }

    private fun propertyId(symbol: KaVariableSymbol): PropertyId? = with(ka) { with(reader) { propertyId(symbol) } }

    /** The value of [expression] if the compiler can work it out. Lambdas are not worth asking about. */
    private fun constant(expression: KtExpression): Constant? {
        if (expression is KtLambdaExpression || expression is KtCallableReferenceExpression) return null
        return safely { with(ka) { expression.evaluate() } }?.let(reader::constantOf)
    }

    /** A failed query leaves one name unresolved; it must not end the lowering. */
    private fun <T> safely(query: () -> T): T? = failures.guard({ "an expression, which stays unresolved" }, query)
}
