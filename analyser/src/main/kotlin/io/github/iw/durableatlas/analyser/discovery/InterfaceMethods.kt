package io.github.iw.durableatlas.analyser.discovery

import io.github.iw.durableatlas.analyser.code.ClassDecl
import io.github.iw.durableatlas.analyser.code.ClassKind
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.find

/**
 * Works out which methods an annotated interface registers, the way the Temporal SDK does for
 * both `@WorkflowInterface` and `@ActivityInterface`.
 *
 * The SDK walks the interface hierarchy from the top. A method declared by an interface without
 * the marker annotation is handed down to the interfaces that extend it; the first interface with
 * the annotation registers the methods it declares and the methods handed down to it, and hands
 * nothing further down. So a method belongs to the nearest annotated interface at or below the
 * interface that declares it, and that interface's name and attributes are the ones that count
 * when a default name is derived.
 */
internal class InterfaceMethods(
    private val backend: SourceBackend,
    /** Fully qualified name of the annotation that marks an interface. */
    private val marker: String,
    /** Whether a method carries an annotation of its own that the SDK acts on. */
    private val isAnnotated: (FunctionDecl) -> Boolean,
) {
    private val classes = HashMap<String, ClassDecl?>()
    private val registered = HashMap<String, List<FunctionDecl>>()

    // Classes that do not exist are remembered too.
    fun find(name: String): ClassDecl? {
        if (name !in classes) classes[name] = backend.findClass(name)
        return classes[name]
    }

    fun isMarked(declaration: ClassDecl): Boolean =
        declaration.kind == ClassKind.INTERFACE && declaration.annotations.find(marker) != null

    /** The marked interfaces among [declaration] itself and its supertypes, nearest first. */
    fun markedInterfaces(declaration: ClassDecl): List<ClassDecl> =
        (listOf(declaration) + declaration.allSuperTypes.mapNotNull(::find)).filter(::isMarked)

    /** The methods a marked interface registers itself, in declaration order, inherited ones first. */
    fun registeredBy(declaration: ClassDecl): List<FunctionDecl> =
        registered.getOrPut(declaration.name) { collect(declaration, HashSet()) }

    private fun collect(declaration: ClassDecl, visiting: MutableSet<String>): List<FunctionDecl> {
        if (!visiting.add(declaration.name)) return emptyList()
        val declared = backend.functions(declaration.name)
        val inherited = declaration.superTypes
            .mapNotNull(::find)
            .filter { it.kind == ClassKind.INTERFACE && !isMarked(it) }
            .flatMap { collect(it, visiting) }
            // A method without an annotation of its own is represented by its redeclaration here,
            // if there is one, because that is where the SDK then looks for the annotation.
            .filter { parent -> isAnnotated(parent) || declared.none { parent.id in it.overrides } }
        visiting.remove(declaration.name)
        return (inherited + declared).distinctBy { it.id }
    }
}
