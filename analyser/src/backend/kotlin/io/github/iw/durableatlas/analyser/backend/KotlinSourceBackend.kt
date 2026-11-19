package io.github.iw.durableatlas.analyser.backend

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.util.PsiTreeUtil
import io.github.iw.durableatlas.analyser.code.Block
import io.github.iw.durableatlas.analyser.code.ClassDecl
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Construction
import io.github.iw.durableatlas.analyser.code.ConstructorDecl
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.JavaCall
import io.github.iw.durableatlas.analyser.code.PropertyDecl
import io.github.iw.durableatlas.analyser.code.PropertyId
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.ir.SourceLocation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationContainer
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor

/** [SourceBackend] on top of an [AnalysisSession]. Results are cached, so asking twice is cheap. */
internal class KotlinSourceBackend(
    private val session: AnalysisSession,
    private val log: (String) -> Unit,
) : SourceBackend {
    private class SourceClass(val psi: KtClassOrObject, val file: SourceFile)

    private val declarations = DeclarationIndex()
    private val duplicates = lazy(LazyThreadSafetyMode.NONE) { Duplicates(session) }
    private val reader = SymbolReader(session, declarations, duplicates)
    private val failures = Failures(log)

    /** Classes of the sources by fully qualified name; the first declaration wins if a name is declared twice. */
    private val sourceClassIndex: Map<String, SourceClass> by lazy {
        val index = LinkedHashMap<String, SourceClass>()
        for (file in session.files) {
            for (psi in classesIn(file.psi)) {
                val name = psi.fqName?.asString() ?: continue
                index.putIfAbsent(name, SourceClass(psi, file))
            }
        }
        index
    }

    private val classes = HashMap<String, ClassDecl?>()
    private val functionsByClass = HashMap<String, List<FunctionDecl>>()
    private val propertiesByClass = HashMap<String, List<PropertyDecl>>()
    private val functionDecls = HashMap<FunctionId, FunctionDecl?>()
    private val bodies = HashMap<FunctionId, Block?>()
    private val initializers = HashMap<PropertyId, Code?>()
    private val defaults = HashMap<Pair<FunctionId, String>, Code?>()
    private val getters = HashMap<PropertyId, Block?>()
    private val constructorsByClass = HashMap<String, List<ConstructorDecl>>()
    private val constructions = HashMap<FunctionId, Construction?>()
    private val mentioning = HashMap<String, List<Block>>()
    private var allSourceClasses: List<ClassDecl>? = null
    private var allJavaClasses: List<ClassDecl>? = null

    override fun sourceClasses(): List<ClassDecl> =
        allSourceClasses ?: run {
            // One analysis per file rather than per class.
            for ((file, group) in sourceClassIndex.entries.groupBy { it.value.file }) {
                analyze(file.psi) {
                    for ((name, source) in group) {
                        classes.cached(name) { failures.guard({ "class $name" }) { sourceClassDecl(source) } }
                    }
                }
            }
            warnOfUnresolvedAnnotations()
            warnOfDuplicates()
            sourceClassIndex.keys.mapNotNull { classes[it] }.sortedBy { it.name }.also { allSourceClasses = it }
        }

    override fun duplicateClasses(): Map<String, List<SourceLocation>> =
        duplicates.value.classes.toSortedMap().mapValues { (_, declared) -> declared.map { it.location } }

    private fun warnOfDuplicates() {
        val twice = duplicateClasses()
        if (twice.isEmpty()) return
        val (name, places) = twice.entries.first()
        log(
            "warning: ${twice.size} class name(s) are declared more than once in the sources, for example $name " +
                "(${places.joinToString(", ") { "${it.file}:${it.line}" }}); only one declaration of each is read, " +
                "and a call or a read that may mean another one is left unresolved",
        )
    }

    private fun warnOfUnresolvedAnnotations() {
        val unresolved = reader.takeUnresolvedAnnotations()
        if (unresolved.isEmpty()) return
        log(
            "warning: ${unresolved.size} annotation(s) in the sources did not resolve, for example " +
                "${unresolved.first()}; what depends on them is not recognised",
        )
    }

    override fun findClass(name: String): ClassDecl? =
        classes.cached(name) {
            val source = sourceClassIndex[name]
            if (source != null) analyze(source.psi) { sourceClassDecl(source) } else libraryClass(name)
        }

    private fun KaSession.sourceClassDecl(source: SourceClass): ClassDecl? =
        with(reader) {
            val symbol = source.psi.classSymbol ?: return null
            classDecl(symbol, source.file.module, location(source.psi as KtDeclaration))
        }

    /** A class that is not declared in the Kotlin sources: one of a library, or of the Java sources. */
    private fun libraryClass(name: String): ClassDecl? =
        inLibrary(name) { symbol -> with(reader) { classDecl(symbol, javaModule(symbol), javaLocation(symbol)) } }

    override fun javaClasses(): List<ClassDecl> =
        allJavaClasses ?: session.javaFiles
            .flatMap { file -> file.psi.classes.flatMap(::withNested) }
            .mapNotNull { it.qualifiedName }
            .distinct()
            // A name that Kotlin declares as well stands for the Kotlin class.
            .filter { it !in sourceClassIndex }
            .mapNotNull { name -> failures.guard({ "class $name" }) { findClass(name) } }
            .filter { it.isSource }
            .sortedBy { it.name }
            .also { allJavaClasses = it }

    private fun withNested(declared: PsiClass): List<PsiClass> = listOf(declared) + declared.innerClasses.flatMap(::withNested)

    override fun javaCalls(owner: String, method: String): List<JavaCall> {
        val simpleName = owner.substringAfterLast('.')
        return session.javaFiles.filter { it.psi.text.contains(if (method == CONSTRUCTOR) simpleName else method) }.flatMap { file ->
            failures.guard({ "the calls of $method in ${file.path}" }) {
                val calls = if (method == CONSTRUCTOR) {
                    PsiTreeUtil.collectElementsOfType(file.psi, PsiNewExpression::class.java)
                        // A class that does not resolve may be the one that is looked for.
                        .filter { made -> made.classReference?.let { it.qualifiedName == owner || (it.resolve() == null && it.referenceName == simpleName) } == true }
                        .map { it to it.argumentList }
                } else {
                    PsiTreeUtil.collectElementsOfType(file.psi, PsiMethodCallExpression::class.java)
                        .filter { call -> call.methodExpression.referenceName == method }
                        // And so may a call that does not resolve.
                        .filter { call -> call.resolveMethod()?.let { it.containingClass?.qualifiedName == owner } ?: true }
                        .map { it to it.argumentList }
                }
                calls.sortedBy { it.first.textOffset }.map { (call, arguments) ->
                    JavaCall(SourceLocation(file.path, file.line(call.textOffset)), arguments?.expressions.orEmpty().map(::classLiteral))
                }
                // A file that cannot be read may hold a call, with anything handed to it.
            } ?: listOf(JavaCall(SourceLocation(file.path, 1), listOf(null)))
        }
    }

    /** The class that `Type.class` names; `null` if [expression] is anything else. */
    private fun classLiteral(expression: PsiExpression): String? {
        val type = (expression as? PsiClassObjectAccessExpression)?.operand?.type ?: return null
        return (type as? PsiClassType)?.resolve()?.qualifiedName ?: type.canonicalText
    }

    /**
     * Reads something from a class that is not declared in the sources. Modules see different
     * libraries, so the class is looked for from a module known to see it, if there is one, and
     * then from each module in turn.
     */
    private fun <T : Any> inLibrary(name: String, read: KaSession.(KaClassSymbol) -> T?): T? {
        val candidates = declarations.classIds(name)
        val modules = (listOfNotNull(declarations.moduleThatSees(name)) + session.modules.values).distinct()
        for (module in modules) {
            val found = analyze(module) {
                candidates.firstNotNullOfOrNull { classId -> findClass(classId) }?.let { read(it) }
            }
            if (found != null) return found
        }
        return null
    }

    override fun functions(className: String): List<FunctionDecl> =
        functionsByClass.getOrPut(className) {
            val source = sourceClassIndex[className]
            if (source != null) {
                analyze(source.psi) {
                    source.psi.declarations.filterIsInstance<KtNamedFunction>().mapNotNull { function ->
                        failures.guard({ "function ${function.name} of $className" }) {
                            (function.symbol as? KaNamedFunctionSymbol)?.let { with(reader) { functionDecl(it) } }
                        }
                    }
                }
            } else {
                libraryMembers(className) { symbol ->
                    symbol.declaredMemberScope.callables
                        .filterIsInstance<KaNamedFunctionSymbol>()
                        .map { with(reader) { functionDecl(it) } }
                        .toList()
                        .sortedBy { it.id.toString() }
                }
            }.onEach { functionDecls.putIfAbsent(it.id, it) }
        }

    override fun properties(className: String): List<PropertyDecl> =
        propertiesByClass.getOrPut(className) {
            val source = sourceClassIndex[className]
            if (source != null) {
                analyze(source.psi) {
                    val constructorProperties = source.psi.primaryConstructorParameters.filter { it.hasValOrVar() }
                    val bodyProperties = source.psi.declarations.filterIsInstance<KtProperty>()
                    (constructorProperties + bodyProperties).mapNotNull { declaration ->
                        val symbol = when (declaration) {
                            is KtParameter ->
                                (declaration.symbol as? KaValueParameterSymbol)?.generatedPrimaryConstructorProperty
                            else -> (declaration as KtProperty).symbol as? KaPropertySymbol
                        }
                        symbol?.let { with(reader) { propertyDecl(it) } }
                    }
                }
            } else {
                libraryMembers(className) { symbol ->
                    symbol.declaredMemberScope.callables
                        .filterIsInstance<KaPropertySymbol>()
                        .mapNotNull { with(reader) { propertyDecl(it) } }
                        .toList()
                        .sortedBy { it.id.toString() }
                }
            }
        }

    private fun <T> libraryMembers(className: String, read: KaSession.(KaClassSymbol) -> List<T>): List<T> {
        if (findClass(className) == null) return emptyList()
        return inLibrary(className, read).orEmpty()
    }

    override fun function(id: FunctionId): FunctionDecl? =
        functionDecls.cached(id) {
            val declaration = declarations.function(id) ?: return@cached null
            analyze(declaration) {
                (declaration.symbol as? KaNamedFunctionSymbol)?.let { with(reader) { functionDecl(it) } }
            }
        }

    override fun body(id: FunctionId): Block? =
        bodies.cached(id) {
            val declaration = declarations.function(id) ?: return@cached null
            failures.guard({ "the body of $id" }) {
                analyze(declaration) { Lowering(this, reader, declaration, failures).functionBody(declaration) }
            }
        }

    override fun initializer(id: PropertyId): Code? =
        initializers.cached(id) {
            val declaration = declarations.property(id) as? KtProperty ?: return@cached null
            val expression = declaration.initializer ?: declaration.delegateExpression ?: return@cached null
            failures.guard({ "the initialiser of $id" }) {
                analyze(declaration) { Lowering(this, reader, declaration, failures).lower(expression) }
            }
        }

    override fun defaultValue(id: FunctionId, parameter: String): Code? =
        defaults.cached(id to parameter) {
            val declaration = declarations.function(id) ?: return@cached null
            val expression = declaration.valueParameters.firstOrNull { it.name == parameter }?.defaultValue
                ?: return@cached null
            failures.guard({ "the default value of $parameter of $id" }) {
                analyze(declaration) { Lowering(this, reader, declaration, failures).lower(expression) }
            }
        }

    override fun getter(id: PropertyId): Block? =
        getters.cached(id) {
            val declaration = declarations.property(id) as? KtProperty ?: return@cached null
            val getter = declaration.getter?.takeIf { it.hasBody() } ?: return@cached null
            failures.guard({ "the getter of $id" }) {
                analyze(declaration) { Lowering(this, reader, declaration, failures).accessorBody(getter) }
            }
        }

    override fun constructors(className: String): List<ConstructorDecl> =
        constructorsByClass.getOrPut(className) {
            val owner = sourceClassIndex[className]?.psi ?: return@getOrPut emptyList()
            failures.guard({ "the constructors of $className" }) {
                analyze(owner) {
                    val symbol = owner.classSymbol
                    if (symbol == null || symbol.classKind == KaClassKind.INTERFACE) {
                        emptyList()
                    } else {
                        symbol.declaredMemberScope.constructors
                            .map { constructor -> constructor.isPrimary to with(reader) { constructorDecl(constructor, owner) } }
                            .toList()
                            .sortedWith(compareBy({ !it.first }, { it.second.location?.line ?: 0 }, { it.second.id.signature }))
                            .map { it.second }
                    }
                }
            }.orEmpty()
        }

    override fun construction(id: FunctionId): Construction? =
        constructions.cached(id) {
            val source = declarations.constructor(id) ?: return@cached null
            failures.guard({ "what $id runs" }) {
                analyze(source.owner) { Lowering(this, reader, source.owner, failures).construction(source.owner, source.written) }
            }
        }

    override fun mentions(text: String): Boolean = session.files.any { it.psi.text.contains(text) }

    override fun bodiesMentioning(text: String): List<Block> =
        mentioning.getOrPut(text) {
            session.files.filter { it.psi.text.contains(text) }.flatMap { file ->
                val functions = PsiTreeUtil.collectElementsOfType(file.psi, KtNamedFunction::class.java)
                    .filter { function -> function.text.contains(text) && PsiTreeUtil.getParentOfType(function, KtNamedFunction::class.java) == null }
                // What a class or a file runs when it is set up is part of no function, and no function that is lowered holds it.
                val setUp = PsiTreeUtil.collectElementsOfType(file.psi, KtDeclaration::class.java)
                    .filter { declaration -> setUpCode(declaration)?.text?.contains(text) == true }
                (functions + setUp).sortedBy { it.textOffset }.mapNotNull { declaration ->
                    failures.guard({ "the code of ${declaration.name ?: "an initialiser"} in ${file.path}" }) {
                        analyze(declaration) {
                            val lowering = Lowering(this, reader, declaration, failures)
                            if (declaration is KtNamedFunction) lowering.functionBody(declaration) else setUpCode(declaration)?.let(lowering::standalone)
                        }
                    }
                }
            }
        }

    /**
     * The code [declaration] runs when its class or its file is set up, if it is one that runs
     * any: the body of an `init` block or of a constructor, or what a property is given, where
     * the property is no local variable.
     */
    private fun setUpCode(declaration: KtDeclaration): KtExpression? =
        when (declaration) {
            is KtAnonymousInitializer -> declaration.body
            is KtSecondaryConstructor -> declaration.bodyExpression
            is KtProperty -> if (declaration.isLocal) null else declaration.initializer ?: declaration.delegateExpression
            else -> null
        }

    override fun close() {
        warnOfUnresolvedAnnotations()
        failures.summarise()
        session.close()
    }

    /** Classes declared at the top level of [container] or nested in such classes, enum entries excluded. */
    private fun classesIn(container: KtDeclarationContainer): List<KtClassOrObject> =
        container.declarations
            .filterIsInstance<KtClassOrObject>()
            .filter { it !is KtEnumEntry }
            .flatMap { listOf(it) + classesIn(it) }

    private companion object {
        /** What a constructor is called where [javaCalls] is asked for the places an object is made. */
        const val CONSTRUCTOR = "<init>"
    }
}
