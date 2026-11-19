package io.github.iw.durableatlas.analyser.discovery

import io.github.iw.durableatlas.analyser.code.ClassDecl
import io.github.iw.durableatlas.analyser.code.ClassKind
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.find

/**
 * Finds activity interfaces and the activity types they register.
 *
 * As in the Temporal Java SDK, an activity is called by the `name` of `@ActivityMethod` if that
 * is given and not empty, and that name is used as it is. Otherwise the name is the `namePrefix`
 * of the `@ActivityInterface` that registers the method (see [InterfaceMethods]) followed by the
 * method name with its first character in upper case.
 */
internal class ActivityDiscovery(
    backend: SourceBackend,
    private val warnings: MutableList<String>,
) {
    private val methods = InterfaceMethods(backend, TemporalTypes.ACTIVITY_INTERFACE) {
        it.annotations.find(TemporalTypes.ACTIVITY_METHOD) != null
    }
    private val described = HashMap<String, ActivityInterface>()

    /** @param sourceClasses the classes of the sources, those of its Java files among them */
    fun discover(sourceClasses: List<ClassDecl>): List<ActivityInterface> {
        val concrete = sourceClasses.filter { it.kind == ClassKind.CLASS && !it.isAbstract }

        val interfaces = LinkedHashMap<String, ClassDecl>()
        sourceClasses.filter(methods::isMarked).forEach { interfaces[it.name] = it }
        concrete.flatMap(methods::markedInterfaces).forEach { interfaces.putIfAbsent(it.name, it) }

        return interfaces.values
            .map { declaration -> activityInterface(declaration, concrete) }
            .sortedBy { it.declaration.name }
    }

    /**
     * The activity type that a call of [method] on a stub of the interface [interfaceName] runs;
     * `null` if there is no such interface or it registers no such method. This also answers for
     * interfaces that come from a library.
     */
    fun activityType(interfaceName: String, method: FunctionId): String? {
        val declaration = methods.find(interfaceName) ?: return null
        val activities = methods.markedInterfaces(declaration).flatMap { marked ->
            described.getOrPut(marked.name) { activityInterface(marked, emptyList()) }.activities
        }
        val activity = activities.firstOrNull { it.method.id == method || method in it.method.overrides }
            ?: activities.firstOrNull { it.method.name == method.simpleName }
        return activity?.name
    }

    private fun activityInterface(declaration: ClassDecl, concrete: List<ClassDecl>): ActivityInterface {
        val prefix = declaration.annotations.find(TemporalTypes.ACTIVITY_INTERFACE)
            ?.text(TemporalTypes.NAME_PREFIX)
            .orEmpty()
        val registered = methods.registeredBy(declaration)
        // The SDK refuses overloads; if there are any, the annotated one is the most telling.
        val unique = registered.groupBy { it.name }.map { (name, overloads) ->
            if (overloads.size > 1) warnings += "${declaration.name} declares $name more than once"
            overloads.firstOrNull { activityMethodName(it) != null } ?: overloads.first()
        }
        val activities = unique
            .map { method -> ActivityType(activityMethodName(method) ?: (prefix + capitalised(method.name)), method) }
            .sortedWith(compareBy({ it.name }, { it.method.name }))
        val implementations = concrete.filter { declaration.name in it.allSuperTypes }.sortedBy { it.name }
        return ActivityInterface(declaration, prefix, activities, implementations)
    }

    private fun activityMethodName(method: FunctionDecl): String? =
        method.annotations.find(TemporalTypes.ACTIVITY_METHOD)?.text(TemporalTypes.NAME)?.ifEmpty { null }

    // The SDK upper-cases the first character in the default locale of the JVM that runs the
    // worker; the result is assumed not to depend on it.
    private fun capitalised(name: String): String =
        if (name.isEmpty()) name else name.substring(0, 1).uppercase() + name.substring(1)
}
