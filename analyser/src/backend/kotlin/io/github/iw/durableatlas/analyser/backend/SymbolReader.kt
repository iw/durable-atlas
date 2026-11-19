package io.github.iw.durableatlas.analyser.backend

import com.intellij.psi.PsiElement
import io.github.iw.durableatlas.analyser.code.AnnotationUse
import io.github.iw.durableatlas.analyser.code.Callee
import io.github.iw.durableatlas.analyser.code.CalleeKind
import io.github.iw.durableatlas.analyser.code.ClassDecl
import io.github.iw.durableatlas.analyser.code.ClassKind
import io.github.iw.durableatlas.analyser.code.Constant
import io.github.iw.durableatlas.analyser.code.ConstructorDecl
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.ParameterDecl
import io.github.iw.durableatlas.analyser.code.PropertyDecl
import io.github.iw.durableatlas.analyser.code.PropertyId
import io.github.iw.durableatlas.analyser.code.TypeRef
import io.github.iw.durableatlas.ir.SourceLocation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.base.KaConstantValue
import org.jetbrains.kotlin.analysis.api.symbols.KaBackingFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaJavaFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/**
 * Turns the Analysis API's symbols, types and annotations into the tool's own declarations.
 *
 * Its functions take a [KaSession] receiver because symbols are only valid inside the `analyze`
 * block that produced them; nothing that leaves here refers to one.
 */
internal class SymbolReader(
    private val session: AnalysisSession,
    private val declarations: DeclarationIndex,
    /** The classes the sources declare more than once; asked for only where a name is used. */
    duplicateClasses: Lazy<Duplicates>,
) {
    private val duplicates by duplicateClasses

    private val classAnnotations = HashMap<ClassId, List<AnnotationUse>>()
    private val unresolved = LinkedHashSet<String>()

    /**
     * The annotations met in the sources so far whose class did not resolve, each as written and
     * with its place. Reading this forgets them.
     */
    fun takeUnresolvedAnnotations(): List<String> = unresolved.toList().also { unresolved.clear() }

    // ---- locations

    fun location(declaration: KtDeclaration): SourceLocation? {
        val file = session.sourceFile(declaration.containingKtFile) ?: return null
        // The line of the name, not of the annotations or documentation in front of it.
        return SourceLocation(file.path, file.line(declaration.textOffset))
    }

    fun location(element: PsiElement): SourceLocation {
        val psiFile = element.containingFile
        val file = (psiFile as? KtFile)?.let(session::sourceFile)
        return if (file != null) {
            SourceLocation(file.path, file.line(element.startOffset))
        } else {
            SourceLocation(psiFile?.name ?: "<unknown>", 1)
        }
    }

    /** Where a declaration of the Java sources is; `null` for anything else. */
    fun javaLocation(symbol: KaSymbol): SourceLocation? {
        // Only a symbol of the Java sources has PSI at hand; a library's would have to be decompiled.
        if (symbol.origin != KaSymbolOrigin.JAVA_SOURCE) return null
        val element = symbol.psi ?: return null
        val file = session.javaFile(element.containingFile) ?: return null
        return SourceLocation(file.path, file.line(element.textOffset))
    }

    /** The module whose Java sources declare [symbol]; `null` for anything else. */
    fun javaModule(symbol: KaSymbol): String? {
        if (symbol.origin != KaSymbolOrigin.JAVA_SOURCE) return null
        return session.javaFile(symbol.psi?.containingFile)?.module
    }

    /** The module whose sources hold [declaration]; `null` if it is not of the analysed sources. */
    fun moduleOf(declaration: KtDeclaration): String? = session.sourceFile(declaration.containingKtFile)?.module

    // ---- names that are declared more than once

    /**
     * The name of a class that the sources declare more than once, if [symbol] is a member or a
     * constructor of such a class and its use in [from] may mean another declaration than the
     * one that is read under that name; see [Duplicates.doubted].
     */
    fun KaSession.doubted(symbol: KaCallableSymbol, from: Referrer): String? {
        if (duplicates.classes.isEmpty()) return null
        val original = symbol.fakeOverrideOriginal
        val declaring = (original as? KaConstructorSymbol)?.containingClassId ?: original.callableId?.classId
        return doubted(declaring, original, from)
    }

    /** The same for an object of the type [type], which a function is called on or a property is read of. */
    fun KaSession.doubted(type: KaType?, from: Referrer): String? {
        if (duplicates.classes.isEmpty()) return null
        val classType = (if (type is KaFlexibleType) type.lowerBound else type) as? KaClassType ?: return null
        return doubted(classType.classId, classType.symbol, from)
    }

    private fun doubted(classId: ClassId?, declared: KaSymbol, from: Referrer): String? {
        var current = classId
        while (current != null) {
            val name = current.asFqNameString()
            // Only what is of the sources is asked for where it is written: a library's class would have to be decompiled.
            if (name in duplicates.classes) return duplicates.doubted(name, declared.psi.takeIf { declared.origin in SOURCE_ORIGINS }, from)
            current = current.outerClassId
        }
        return null
    }

    /**
     * Whether [declaration] is one that is read under its name: it is no member of a later
     * declaration of a class that the sources declare more than once. Such a member shares its
     * identity with the member of the first declaration and must never be remembered in its
     * place, whichever of the two is met first.
     */
    private fun isRead(declaration: KtDeclaration): Boolean {
        if (duplicates.classes.isEmpty()) return true
        var owner = declaration.containingClassOrObject
        while (owner != null) {
            val declared = owner.fqName?.asString()?.let(duplicates.classes::get)
            if (declared != null && declared.first().psi !== owner) return false
            owner = owner.containingClassOrObject
        }
        return true
    }

    /**
     * The name of [symbol] if it is a function of the sources that another declaration shares
     * its identity with, name and parameters alike, and that other one is the one whose body is
     * read: two modules that do not see each other may each declare one.
     *
     * @param id the identity of [symbol], as [callee] gave it
     */
    fun KaSession.shadowed(symbol: KaFunctionSymbol, id: FunctionId): String? {
        val function = symbol.fakeOverrideOriginal as? KaFunctionSymbol ?: symbol
        val source = sourceDeclaration(function) as? KtNamedFunction ?: return null
        val read = declarations.function(id) ?: return null
        return id.name.takeIf { read !== source }
    }

    /**
     * The same for a property of the sources.
     *
     * @param id the identity of [symbol], as [propertyId] gave it
     */
    fun KaSession.shadowed(symbol: KaVariableSymbol, id: PropertyId): String? {
        val source = sourceDeclaration(if (symbol is KaBackingFieldSymbol) symbol.owningProperty else symbol.fakeOverrideOriginal) ?: return null
        val read = declarations.property(id) ?: return null
        return id.name.takeIf { read !== source }
    }

    // ---- annotations and constants

    fun KaSession.annotationsOf(owner: KaAnnotated): List<AnnotationUse> = owner.annotations.mapNotNull { annotationUse(it) }

    private fun KaSession.annotationUse(annotation: KaAnnotation): AnnotationUse? {
        // An annotation whose class does not resolve has no identity to report. In the sources
        // that usually means a jar is missing from the classpath, which is worth a warning; on
        // a library class it only means that the library was compiled against more than it ships.
        val classId = annotation.classId?.takeUnless { it.shortClassName.isSpecial }
        if (classId == null) {
            val written = annotation.psi
            val file = (written?.containingFile as? KtFile)?.let(session::sourceFile)
            if (written != null && file != null) {
                unresolved += "${written.text} at ${file.path}:${file.line(written.startOffset)}"
            }
            return null
        }
        declarations.remember(classId, useSiteModule)
        return AnnotationUse(
            type = classId.asFqNameString(),
            arguments = annotation.arguments.associate { it.name.asString() to constantOf(it.expression) },
        )
    }

    private fun KaSession.constantOf(value: KaAnnotationValue): Constant =
        when (value) {
            is KaAnnotationValue.ConstantValue -> constantOf(value.value)
            is KaAnnotationValue.EnumEntryValue -> {
                val callableId = value.callableId
                val type = callableId?.classId?.asFqNameString()
                if (callableId == null || type == null) {
                    Constant.Unsupported("")
                } else {
                    Constant.EnumEntry(type, callableId.callableName.asString())
                }
            }
            is KaAnnotationValue.ClassLiteralValue ->
                value.classId?.let { Constant.ClassReference(it.asFqNameString()) } ?: Constant.Unsupported("")
            is KaAnnotationValue.ArrayValue -> Constant.Array(value.values.map { constantOf(it) })
            is KaAnnotationValue.NestedAnnotationValue ->
                annotationUse(value.annotation)?.let { Constant.Annotation(it) } ?: Constant.Unsupported("")
            else -> Constant.Unsupported("")
        }

    fun constantOf(value: KaConstantValue): Constant =
        when (value) {
            is KaConstantValue.StringValue -> Constant.Text(value.value)
            is KaConstantValue.CharValue -> Constant.Text(value.value.toString())
            is KaConstantValue.BooleanValue -> Constant.Bool(value.value)
            is KaConstantValue.NullValue -> Constant.Null
            is KaConstantValue.FloatValue -> Constant.Decimal(value.value.toDouble())
            is KaConstantValue.DoubleValue -> Constant.Decimal(value.value)
            is KaConstantValue.ErrorValue -> Constant.Unsupported(value.render())
            else -> when (val number = value.value) {
                is Number -> Constant.Integer(number.toLong())
                is UByte -> Constant.Integer(number.toLong())
                is UShort -> Constant.Integer(number.toLong())
                is UInt -> Constant.Integer(number.toLong())
                is ULong -> Constant.Integer(number.toLong())
                else -> Constant.Unsupported(value.render())
            }
        }

    // ---- types

    fun KaSession.typeRef(type: KaType): TypeRef =
        when (type) {
            is KaFlexibleType -> typeRef(type.lowerBound)
            is KaErrorType -> TypeRef(name = null, text = type.toString())
            is KaClassType -> {
                val classId = type.classId
                declarations.remember(classId, useSiteModule)
                val name = classId.asFqNameString()
                val arguments = type.typeArguments.map { projection ->
                    projection.type?.let { typeRef(it) } ?: TypeRef(name = null, text = "*")
                }
                val nullable = type.isMarkedNullable
                val text = buildString {
                    append(name)
                    if (arguments.isNotEmpty()) arguments.joinTo(this, ", ", "<", ">") { it.text }
                    if (nullable) append('?')
                }
                TypeRef(name, text, nullable, arguments, classAnnotations.getOrPut(classId) { annotationsOf(type.symbol) })
            }
            is KaTypeParameterType -> {
                val nullable = type.isMarkedNullable
                TypeRef(name = null, text = type.name.asString() + if (nullable) "?" else "", nullable = nullable)
            }
            else -> TypeRef(name = null, text = type.toString())
        }

    /**
     * The name, as written, of the class that the type [reference] names, where that class
     * could not be resolved; `null` for any other type. Only a type that is written out names a
     * class: the type of an expression that is in error for another reason, such as a call that
     * does not resolve, names none.
     */
    fun KaSession.missingClass(reference: KtTypeReference): String? {
        if (reference.type !is KaErrorType) return null
        var written = reference.typeElement
        while (written is KtNullableType) written = written.innerType
        return (written as? KtUserType)?.referencedName
    }

    /**
     * The name, as written, of the class that [symbol] is declared to be of, or to return, where
     * the sources declare it with a type and the class of that type could not be resolved.
     */
    fun KaSession.missingClassOf(symbol: KaSymbol): String? =
        (sourceDeclaration(symbol) as? KtCallableDeclaration)?.typeReference?.let { missingClass(it) }

    /** The name a type contributes to a signature: its class without type arguments. */
    private fun KaSession.erasedName(type: KaType): String =
        when (type) {
            is KaFlexibleType -> erasedName(type.lowerBound)
            is KaErrorType -> type.toString()
            is KaClassType -> type.classId.asFqNameString()
            is KaTypeParameterType -> type.name.asString()
            else -> type.toString()
        }

    private fun KaSession.className(type: KaType): String? =
        when (type) {
            is KaFlexibleType -> className(type.lowerBound)
            is KaErrorType -> null
            is KaClassType -> type.classId.also { declarations.remember(it, useSiteModule) }.asFqNameString()
            else -> null
        }

    // ---- classes

    fun KaSession.classDecl(symbol: KaClassSymbol, module: String?, location: SourceLocation?): ClassDecl? {
        val classId = symbol.classId ?: return null
        declarations.remember(classId, useSiteModule)
        val direct = symbol.superTypes.mapNotNull { className(it) }.filter { it != ANY }.distinct()

        // Breadth first, so that nearer supertypes come before farther ones.
        val all = LinkedHashSet<String>()
        var level = symbol.superTypes
        while (level.isNotEmpty()) {
            val next = ArrayList<KaType>()
            for (type in level) {
                val name = className(type) ?: continue
                if (name == ANY || !all.add(name)) continue
                next += type.directSupertypes
            }
            level = next
        }

        return ClassDecl(
            name = classId.asFqNameString(),
            simpleName = classId.shortClassName.asString(),
            kind = when (symbol.classKind) {
                KaClassKind.CLASS -> ClassKind.CLASS
                KaClassKind.INTERFACE -> ClassKind.INTERFACE
                KaClassKind.ENUM_CLASS -> ClassKind.ENUM
                KaClassKind.ANNOTATION_CLASS -> ClassKind.ANNOTATION
                KaClassKind.OBJECT, KaClassKind.COMPANION_OBJECT, KaClassKind.ANONYMOUS_OBJECT -> ClassKind.OBJECT
            },
            isAbstract = symbol.modality == KaSymbolModality.ABSTRACT || symbol.modality == KaSymbolModality.SEALED,
            module = module,
            location = location,
            annotations = annotationsOf(symbol),
            superTypes = direct,
            allSuperTypes = all.toList(),
        )
    }

    // ---- functions

    /**
     * The identity of a function. A member inherited without being overridden is identified by
     * its original declaration, so that a call through a subclass names the function that runs.
     */
    fun KaSession.functionId(symbol: KaFunctionSymbol): FunctionId {
        val function = symbol.fakeOverrideOriginal as? KaFunctionSymbol ?: symbol
        val callableId = function.callableId
        val owner: String?
        val name: String
        when {
            function is KaConstructorSymbol -> {
                owner = function.containingClassId?.also { declarations.remember(it, useSiteModule) }?.asFqNameString()
                name = "${owner ?: ANONYMOUS}.<init>"
            }
            callableId != null -> {
                owner = callableId.classId?.also { declarations.remember(it, useSiteModule) }?.asFqNameString()
                name = callableId.asSingleFqName().asString()
            }
            else -> {
                owner = null
                name = "$LOCAL.${(function as? KaNamedSymbol)?.name?.asString() ?: ANONYMOUS}"
            }
        }
        val parameters = function.valueParameters.joinToString(",", "(", ")") { parameter ->
            erasedName(parameter.returnType) + if (parameter.isVararg) "..." else ""
        }
        // A name does not identify a private top-level function, which another file may declare
        // as well, nor a local one.
        val file = when {
            callableId == null && function !is KaConstructorSymbol ->
                sourceDeclaration(function)?.let { location(it) }?.let { "${it.file}:${it.line}" }
            owner == null &&
                function.location == KaSymbolLocation.TOP_LEVEL &&
                function.visibility == KaSymbolVisibility.PRIVATE ->
                sourceDeclaration(function)?.let { session.sourceFile(it.containingKtFile)?.path }
            else -> null
        }
        return FunctionId(name, owner, parameters, function.receiverParameter?.let { erasedName(it.returnType) }, file)
    }

    /** The function a call resolves to; functions of the sources are recorded so their bodies can be found. */
    fun KaSession.callee(symbol: KaFunctionSymbol): Callee {
        val function = symbol.fakeOverrideOriginal as? KaFunctionSymbol ?: symbol
        val id = functionId(function)
        val source = sourceDeclaration(function)
        if (source is KtNamedFunction && isRead(source)) declarations.remember(id, source)
        return Callee(
            function = id,
            kind = if (function is KaConstructorSymbol) CalleeKind.CONSTRUCTOR else CalleeKind.FUNCTION,
            inSource = source != null,
        )
    }

    fun KaSession.functionDecl(symbol: KaNamedFunctionSymbol): FunctionDecl {
        val id = functionId(symbol)
        val source = sourceDeclaration(symbol) as? KtNamedFunction
        if (source != null && isRead(source)) declarations.remember(id, source)
        val sourceFile = source?.let { session.sourceFile(it.containingKtFile) }
        return FunctionDecl(
            id = id,
            name = symbol.name.asString(),
            module = sourceFile?.module,
            location = source?.let { location(it as KtDeclaration) } ?: javaLocation(symbol),
            annotations = annotationsOf(symbol),
            receiver = symbol.receiverParameter?.let { typeRef(it.returnType) }
                ?.let { type -> type.copy(unresolved = source?.receiverTypeReference?.let { missingClass(it) }) },
            parameters = symbol.valueParameters.map { ParameterDecl(it.name.asString(), typeRef(it.returnType)) },
            returnType = typeRef(symbol.returnType),
            overrides = symbol.allOverriddenSymbols
                .filterIsInstance<KaFunctionSymbol>()
                .map { functionId(it) }
                .distinct()
                .toList(),
            isAbstract = if (source != null) !source.hasBody() else symbol.modality == KaSymbolModality.ABSTRACT,
        )
    }

    /**
     * A constructor of the class [owner] of the sources. It is recorded, so that what it runs
     * can be found.
     */
    fun KaSession.constructorDecl(symbol: KaConstructorSymbol, owner: KtClassOrObject): ConstructorDecl {
        val id = functionId(symbol)
        val written = sourceDeclaration(symbol) as? KtConstructor<*>
        declarations.remember(id, ConstructorSource(owner, written))
        return ConstructorDecl(
            id = id,
            location = location((written ?: owner) as KtDeclaration),
            annotations = annotationsOf(symbol),
            parameters = symbol.valueParameters.map { ParameterDecl(it.name.asString(), typeRef(it.returnType)) },
            takesNoArguments = symbol.valueParameters.all { it.hasDefaultValue },
        )
    }

    // ---- properties

    /** The identity of a property, Java field or enum entry; `null` for local variables and parameters. */
    fun KaSession.propertyId(symbol: KaVariableSymbol): PropertyId? {
        val property: KaCallableSymbol = when (symbol) {
            is KaBackingFieldSymbol -> symbol.owningProperty
            is KaPropertySymbol, is KaJavaFieldSymbol, is KaEnumEntrySymbol -> symbol.fakeOverrideOriginal
            else -> return null
        }
        val callableId = property.callableId ?: return null
        val declared = sourceDeclaration(property)
        val owner = callableId.classId?.also { declarations.remember(it, useSiteModule) }?.asFqNameString()
        // A name does not identify a private top-level property, which another file may declare as well.
        val private = owner == null && property is KaPropertySymbol && property.visibility == KaSymbolVisibility.PRIVATE
        val id = PropertyId(
            name = callableId.asSingleFqName().asString(),
            owner = owner,
            file = declared?.takeIf { private }?.let { session.sourceFile(it.containingKtFile)?.path },
        )
        when (val source = declared) {
            is KtProperty -> if (isRead(source)) declarations.remember(id, source)
            is KtParameter -> if (isRead(source)) declarations.remember(id, source)
            else -> Unit
        }
        return id
    }

    fun KaSession.propertyDecl(symbol: KaPropertySymbol): PropertyDecl? {
        val id = propertyId(symbol) ?: return null
        val source = sourceDeclaration(symbol)
        return PropertyDecl(
            id = id,
            name = symbol.name.asString(),
            location = source?.let { location(it) },
            annotations = annotationsOf(symbol),
            type = typeRef(symbol.returnType),
            isMutable = !symbol.isVal,
            isConstructorParameter = source is KtParameter,
            hasInitializer = source is KtProperty && (source.initializer != null || source.delegateExpression != null),
        )
    }

    /** The declaration of [symbol] in the analysed sources, if that is where it comes from. */
    private fun sourceDeclaration(symbol: KaSymbol): KtDeclaration? {
        // Asking a library symbol for its PSI would decompile the class it belongs to. Members
        // the compiler generates, such as a default constructor, are declared by their class.
        if (symbol.origin != KaSymbolOrigin.SOURCE && symbol.origin != KaSymbolOrigin.SOURCE_MEMBER_GENERATED) return null
        val declaration = symbol.psi as? KtDeclaration ?: return null
        return declaration.takeIf { session.sourceFile(it.containingKtFile) != null }
    }

    private companion object {
        const val ANY = "kotlin.Any"
        const val ANONYMOUS = "<anonymous>"
        const val LOCAL = "<local>"

        /** Where a symbol comes from that is written in the Kotlin or Java sources. */
        val SOURCE_ORIGINS = setOf(KaSymbolOrigin.SOURCE, KaSymbolOrigin.SOURCE_MEMBER_GENERATED, KaSymbolOrigin.JAVA_SOURCE)
    }
}
