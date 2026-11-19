package io.github.iw.durableatlas.analyser.backend

import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.PropertyId
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction

/** A constructor of a class of the sources: its class, and where it is written out, if it is. */
internal class ConstructorSource(val owner: KtClassOrObject, val written: KtConstructor<*>?)

/**
 * Remembers which source declaration stands behind each identifier the backend has handed out, so
 * that a later request for a body or an initialiser can go straight to it.
 */
internal class DeclarationIndex {
    private val classIds = HashMap<String, ClassId>()
    private val seenFrom = HashMap<String, KaModule>()
    private val functions = HashMap<FunctionId, KtNamedFunction>()
    private val constructors = HashMap<FunctionId, ConstructorSource>()
    private val properties = HashMap<PropertyId, KtDeclaration>()

    /** Notes a class that was met while analysing [useSite], a module that therefore sees it. */
    fun remember(classId: ClassId, useSite: KaModule) {
        val name = classId.asFqNameString()
        classIds.putIfAbsent(name, classId)
        seenFrom.putIfAbsent(name, useSite)
    }

    /** A module that is known to see the class called [name], if the class has been met. */
    fun moduleThatSees(name: String): KaModule? = seenFrom[name]

    fun remember(id: FunctionId, declaration: KtNamedFunction) {
        functions.putIfAbsent(id, declaration)
    }

    fun remember(id: PropertyId, declaration: KtDeclaration) {
        properties.putIfAbsent(id, declaration)
    }

    fun remember(id: FunctionId, constructor: ConstructorSource) {
        constructors.putIfAbsent(id, constructor)
    }

    fun function(id: FunctionId): KtNamedFunction? = functions[id]

    fun constructor(id: FunctionId): ConstructorSource? = constructors[id]

    fun property(id: PropertyId): KtDeclaration? = properties[id]

    /**
     * The class ids a fully qualified name may stand for. A name does not say where the package
     * ends and the class begins, so unless the name has been seen before every split is a candidate,
     * longest package first.
     */
    fun classIds(name: String): List<ClassId> {
        classIds[name]?.let { return listOf(it) }
        val segments = name.split('.')
        if (segments.any { it.isEmpty() }) return emptyList()
        return (segments.size - 1 downTo 0).map { packageLength ->
            ClassId(
                FqName(segments.take(packageLength).joinToString(".")),
                FqName(segments.drop(packageLength).joinToString(".")),
                false,
            )
        }
    }
}

/** Like `getOrPut`, but a computed `null` is remembered as well. */
internal inline fun <K, V> MutableMap<K, V>.cached(key: K, compute: () -> V): V {
    if (containsKey(key)) {
        @Suppress("UNCHECKED_CAST")
        return get(key) as V
    }
    return compute().also { put(key, it) }
}
