package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.ClassDecl
import io.github.iw.durableatlas.analyser.code.ClassKind
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.find
import io.github.iw.durableatlas.analyser.discovery.TemporalTypes

/** The code that runs when a function of the sources is called. */
internal sealed interface Target {
    /** One function, whatever the object is. */
    class One(
        val function: FunctionDecl,
        /** The class of the object the function runs on, if only one is possible. */
        val receiverClass: String?,
    ) : Target

    /** One of several functions, depending on the class of the object: one for each of [classes]. */
    class Several(val functions: List<FunctionDecl>, val classes: List<String>) : Target

    /**
     * Nothing says what runs: [function] has no body and no class of the sources implements it,
     * or a class that may implement it is known by one of several declarations.
     */
    class Unknown(val function: FunctionDecl) : Target
}

/**
 * Finds the function that runs for a call. A function that is not a member of a class is the one
 * the call names. A member is looked up the way the object would at run time: for every class of
 * the sources the object can be an instance of, the nearest function that implements the one
 * called. If that is the same function for all of them, the call is as good as static.
 *
 * The implementation of an activity is never what runs for a workflow: it is not found here.
 */
internal class Dispatch(private val backend: SourceBackend) {
    /** For each class or interface, the classes of the sources that can be instantiated and are of that type. */
    private val instantiable: Map<String, List<ClassDecl>> by lazy(LazyThreadSafetyMode.NONE) {
        val index = HashMap<String, MutableList<ClassDecl>>()
        for (declaration in backend.sourceClasses()) {
            if (declaration.isAbstract || declaration.kind !in INSTANTIABLE) continue
            for (type in listOf(declaration.name) + declaration.allSuperTypes) index.getOrPut(type) { ArrayList() } += declaration
        }
        index
    }

    /** The names of the classes that the sources declare more than once. */
    private val twice: Set<String> by lazy(LazyThreadSafetyMode.NONE) { backend.duplicateClasses().keys }

    private val implementations = HashMap<Pair<String, FunctionId>, FunctionDecl?>()
    private val activityInterfaces = HashMap<String, Boolean>()

    /**
     * What a call of [function] runs; `null` if it is not a function written in the sources.
     *
     * @param receiverClass the class of the object the function is called on, as far as it is
     *   known: its static type, or its very class if [exact]
     * @param exact whether the object is known to be of [receiverClass] and of no subclass
     */
    fun resolve(function: FunctionId, receiverClass: String?, exact: Boolean): Target? {
        val declared = backend.function(function)?.takeUnless(::isActivity) ?: return null
        val owner = function.owner ?: return direct(declared)
        if (exact && receiverClass != null) {
            implementation(receiverClass, function)?.let { return Target.One(it, receiverClass) }
            return direct(declared)
        }
        // Only what is of the receiver's type can be the object; a type that is not one of the
        // function's own class says nothing more than that class does.
        val type = receiverClass?.takeIf { it == owner || backend.findClass(it)?.allSuperTypes?.contains(owner) == true } ?: owner
        val byClass = instantiable[type].orEmpty().mapNotNull { declaration ->
            implementation(declaration.name, function)?.let { declaration.name to it }
        }
        val functions = byClass.map { it.second }.distinctBy { it.id }
        // A class that is declared more than once is known by one of its declarations: another may implement the function
        // otherwise. The class of the function itself is no such case: whether a call means that one was told where it was read.
        if (byClass.any { it.first in twice && it.first != owner }) return Target.Unknown(declared)
        return when (functions.size) {
            0 -> direct(declared)
            1 -> Target.One(functions.single(), byClass.singleOrNull()?.first)
            else -> Target.Several(functions, byClass.map { it.first }.sorted())
        }
    }

    /** What a call that names [function] itself runs, as `super.f()` does: no object decides. */
    fun static(function: FunctionId): Target? = backend.function(function)?.takeUnless(::isActivity)?.let(::direct)

    /**
     * Whether [function] implements a method of an activity interface. What an activity does is
     * no part of a workflow, whichever way its code is reached: the call is where following ends.
     */
    private fun isActivity(function: FunctionDecl): Boolean =
        (listOf(function.id) + function.overrides).any { id ->
            val owner = id.owner ?: return@any false
            activityInterfaces.getOrPut(owner) {
                backend.findClass(owner)?.annotations?.find(TemporalTypes.ACTIVITY_INTERFACE) != null
            }
        }

    private fun direct(declared: FunctionDecl): Target = if (declared.isAbstract) Target.Unknown(declared) else Target.One(declared, null)

    /** The function of the sources that runs when [function] is called on an object of [className], if there is one. */
    private fun implementation(className: String, function: FunctionId): FunctionDecl? =
        implementations.getOrPut(className to function) {
            val declaration = backend.findClass(className) ?: return@getOrPut null
            for (type in listOf(declaration.name) + declaration.allSuperTypes) {
                val found = backend.functions(type).firstOrNull { !it.isAbstract && (it.id == function || function in it.overrides) }
                // What a library implements is not followed, and hides what lies above it.
                if (found != null) return@getOrPut found.takeIf { it.module != null }
            }
            null
        }

    private companion object {
        val INSTANTIABLE = setOf(ClassKind.CLASS, ClassKind.OBJECT, ClassKind.ENUM)
    }
}
