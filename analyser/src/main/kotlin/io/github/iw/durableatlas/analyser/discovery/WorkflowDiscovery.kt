package io.github.iw.durableatlas.analyser.discovery

import io.github.iw.durableatlas.analyser.code.ClassDecl
import io.github.iw.durableatlas.analyser.code.ClassKind
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.find
import io.github.iw.durableatlas.ir.EntryPointKind

/**
 * Finds workflow types, their entry points and their implementations.
 *
 * Everything is decided by resolved annotation types, never by simple names. The naming rules are
 * those of the Temporal Java SDK's own metadata classes:
 *
 * - the workflow type is the `name` of `@WorkflowMethod`, or else the simple name of the interface
 *   that registers the method (see [InterfaceMethods]);
 * - a signal, query or update is called by the `name` of its annotation, or else by the method name;
 * - an update validator is tied to its update by `updateName`;
 * - an empty name counts as no name.
 *
 * A class that implements the SDK's `DynamicWorkflow` is a workflow as well: a worker runs it for
 * every workflow type it has no other implementation of, and its `execute` is the workflow
 * method. It has no type name of its own, so it is listed as [DYNAMIC_PREFIX] followed by the
 * simple name of the class, as in `dynamic-FallbackWorkflow`: a name no interface gives by
 * default, which says what the workflow is and makes a plain file name.
 */
internal class WorkflowDiscovery(
    private val backend: SourceBackend,
    private val warnings: MutableList<String>,
) {
    private val methods = InterfaceMethods(backend, TemporalTypes.WORKFLOW_INTERFACE) { kindOf(it) != null }
    private val entryPoints = HashMap<String, List<WorkflowEntryPoint>>()

    fun discover(sourceClasses: List<ClassDecl>): List<WorkflowType> {
        val concrete = sourceClasses.filter { it.kind == ClassKind.CLASS && !it.isAbstract }

        // Workflow interfaces declared in the sources, and those from libraries that a class of
        // the sources implements.
        val interfaces = LinkedHashMap<String, ClassDecl>()
        sourceClasses.filter(methods::isMarked).forEach { interfaces[it.name] = it }
        concrete.flatMap(methods::markedInterfaces).forEach { interfaces.putIfAbsent(it.name, it) }

        return (interfaces.values.mapNotNull { declaration -> workflowType(declaration, concrete) } + dynamicTypes(concrete))
            .sortedWith(compareBy({ it.name }, { it.declaration.name }))
    }

    /** One workflow type for each class that implements `DynamicWorkflow`; its interface is that of the SDK. */
    private fun dynamicTypes(concrete: List<ClassDecl>): List<WorkflowType> {
        val implementing = concrete.filter { TemporalTypes.DYNAMIC_WORKFLOW in it.allSuperTypes }
        if (implementing.isEmpty()) return emptyList()
        val declaration = methods.find(TemporalTypes.DYNAMIC_WORKFLOW) ?: return emptyList()
        val execute = backend.functions(declaration.name).firstOrNull { it.name == TemporalTypes.DYNAMIC_EXECUTE } ?: return emptyList()
        return implementing.map { implementation ->
            val name = DYNAMIC_PREFIX + implementation.simpleName
            val own = listOf(WorkflowEntryPoint(EntryPointKind.WORKFLOW_METHOD, name, execute))
            WorkflowType(name, declaration, own, listOf(implementation(implementation, own)))
        }
    }

    /** The workflow type [declaration] defines, if it registers a workflow method itself. */
    private fun workflowType(declaration: ClassDecl, concrete: List<ClassDecl>): WorkflowType? {
        val workflowMethods = registeredBy(declaration).filter { it.kind == EntryPointKind.WORKFLOW_METHOD }
        val workflowMethod = workflowMethods.firstOrNull() ?: return null
        if (workflowMethods.size > 1) {
            warnings += "${declaration.name} declares more than one @WorkflowMethod; " +
                "using ${workflowMethod.method.name}"
        }

        // Handlers come from the interface and from every workflow interface above it.
        val all = methods.markedInterfaces(declaration).flatMap(::registeredBy)
        val own = canonical(listOf(workflowMethod) + all.filter { it.kind != EntryPointKind.WORKFLOW_METHOD })
        val implementations = concrete
            .filter { declaration.name in it.allSuperTypes }
            .map { implementation(it, own) }
            .sortedBy { it.declaration.name }
        return WorkflowType(workflowMethod.name, declaration, own, implementations)
    }

    /**
     * The entry point that a call of [method] on a stub of the workflow interface [interfaceName]
     * reaches; `null` if there is no such interface or [method] is not one of its entry points.
     * This also answers for interfaces that come from a library.
     */
    fun entryPoint(interfaceName: String, method: FunctionId): WorkflowEntryPoint? {
        val declaration = methods.find(interfaceName) ?: return null
        val registered = methods.markedInterfaces(declaration).flatMap(::registeredBy)
        return registered.firstOrNull { it.method.id == method || method in it.method.overrides }
            ?: registered.firstOrNull { it.method.name == method.simpleName }
    }

    /**
     * The workflow type that a stub of the workflow interface [interfaceName] stands for; `null`
     * if there is no such interface or it registers no workflow method.
     */
    fun workflowType(interfaceName: String): String? {
        val declaration = methods.find(interfaceName) ?: return null
        return methods.markedInterfaces(declaration)
            .flatMap(::registeredBy)
            .firstOrNull { it.kind == EntryPointKind.WORKFLOW_METHOD }
            ?.name
    }

    /**
     * The handlers that an object of the class [className] registers when a workflow hands it
     * to `Workflow.registerListener`: the signal, query and update methods of every workflow
     * interface the class implements, each with the function that runs for it.
     */
    fun listenerEntryPoints(className: String): List<ImplementedEntryPoint> {
        val declaration = methods.find(className) ?: return emptyList()
        return implementation(declaration, emptyList()).additionalEntryPoints
    }

    private fun implementation(declaration: ClassDecl, own: List<WorkflowEntryPoint>): WorkflowImplementation {
        // A class registers the handlers of every workflow interface it implements, not only
        // those of the interface that names the workflow type.
        val ownMethods = own.map { it.method.id }.toSet()
        val additional = methods.markedInterfaces(declaration)
            .flatMap(::registeredBy)
            .filter { it.kind != EntryPointKind.WORKFLOW_METHOD && it.method.id !in ownMethods }
        val candidates = implementingFunctions(declaration)
        fun implemented(entryPoint: WorkflowEntryPoint): ImplementedEntryPoint {
            val function = candidates.firstOrNull { entryPoint.method.id in it.overrides }
                ?: entryPoint.method.takeUnless { it.isAbstract }
            if (function == null) {
                warnings += "${declaration.name} has no function for ${entryPoint.method.id}"
            }
            return ImplementedEntryPoint(entryPoint, function)
        }
        return WorkflowImplementation(declaration, own.map(::implemented), canonical(additional).map(::implemented))
    }

    /** Functions of [declaration] and of its superclasses, nearest first. */
    private fun implementingFunctions(declaration: ClassDecl): List<FunctionDecl> {
        val superclasses = declaration.allSuperTypes.mapNotNull(methods::find).filter { it.kind == ClassKind.CLASS }
        return (listOf(declaration) + superclasses).flatMap { backend.functions(it.name) }
    }

    private fun registeredBy(declaration: ClassDecl): List<WorkflowEntryPoint> =
        entryPoints.getOrPut(declaration.name) {
            methods.registeredBy(declaration).mapNotNull { method -> entryPoint(method, declaration) }
        }

    private fun entryPoint(method: FunctionDecl, registrar: ClassDecl): WorkflowEntryPoint? {
        val present = ANNOTATIONS.filter { (type, _) -> method.annotations.find(type) != null }
        if (present.size > 1) {
            warnings += "${method.id} carries more than one workflow annotation; using ${present.first().first}"
        }
        val (type, kind) = present.firstOrNull() ?: return null
        val annotation = checkNotNull(method.annotations.find(type))
        val attribute = if (kind == EntryPointKind.UPDATE_VALIDATOR) TemporalTypes.UPDATE_NAME else TemporalTypes.NAME
        val default = if (kind == EntryPointKind.WORKFLOW_METHOD) registrar.simpleName else method.name
        val name = annotation.text(attribute).orEmpty().ifEmpty { default }
        return WorkflowEntryPoint(kind, name, method)
    }

    private fun kindOf(method: FunctionDecl): EntryPointKind? =
        ANNOTATIONS.firstOrNull { (type, _) -> method.annotations.find(type) != null }?.second

    /** Workflow method first, then handlers by kind and name; one entry per kind and name. */
    private fun canonical(entryPoints: List<WorkflowEntryPoint>): List<WorkflowEntryPoint> =
        entryPoints
            .distinctBy { it.kind to it.name }
            .sortedWith(compareBy({ it.kind }, { it.name }, { it.method.name }))

    companion object {
        /** What the name begins with under which a class that implements `DynamicWorkflow` is listed. */
        const val DYNAMIC_PREFIX: String = "dynamic-"

        private val ANNOTATIONS = listOf(
            TemporalTypes.WORKFLOW_METHOD to EntryPointKind.WORKFLOW_METHOD,
            TemporalTypes.SIGNAL_METHOD to EntryPointKind.SIGNAL,
            TemporalTypes.QUERY_METHOD to EntryPointKind.QUERY,
            TemporalTypes.UPDATE_METHOD to EntryPointKind.UPDATE,
            TemporalTypes.UPDATE_VALIDATOR_METHOD to EntryPointKind.UPDATE_VALIDATOR,
        )
    }
}
