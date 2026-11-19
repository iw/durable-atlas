package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.TypeRef
import io.github.iw.durableatlas.analyser.code.find
import io.github.iw.durableatlas.analyser.discovery.ActivityDiscovery
import io.github.iw.durableatlas.analyser.discovery.ImplementedEntryPoint
import io.github.iw.durableatlas.analyser.discovery.TemporalTypes
import io.github.iw.durableatlas.analyser.discovery.WorkflowDiscovery
import io.github.iw.durableatlas.analyser.discovery.WorkflowEntryPoint

/**
 * The names Temporal knows the methods of stub interfaces by. The rules are those of discovery,
 * applied here to whatever interface a stub has, whether it is declared in the sources or not.
 */
internal class TemporalNames(private val backend: SourceBackend) {
    // What these find to warn about is reported when the sources are discovered, not again here.
    private val activities = ActivityDiscovery(backend, ArrayList())
    private val workflows = WorkflowDiscovery(backend, ArrayList())

    private val activityTypes = HashMap<Pair<String, FunctionId>, String>()
    private val entryPoints = HashMap<Pair<String, FunctionId>, WorkflowEntryPoint?>()
    private val operations = HashMap<Pair<String, FunctionId>, String>()
    private val workflowTypes = HashMap<String, String?>()

    /** The activity type a call of [method] on a stub of [interfaceName] runs. */
    fun activityType(interfaceName: String, method: FunctionId): String =
        activityTypes.getOrPut(interfaceName to method) {
            // A method the interface does not register still gets the name the SDK would derive.
            activities.activityType(interfaceName, method) ?: method.simpleName.replaceFirstChar { it.uppercase() }
        }

    /**
     * The activity type that the interface [interfaceName] registers [method] as; `null` if it
     * is no activity interface or registers no such method.
     */
    fun registeredActivityType(interfaceName: String, method: FunctionId): String? = activities.activityType(interfaceName, method)

    /** The entry point a call of [method] on a stub of the workflow interface [interfaceName] reaches. */
    fun workflowEntryPoint(interfaceName: String, method: FunctionId): WorkflowEntryPoint? =
        entryPoints.getOrPut(interfaceName to method) { workflows.entryPoint(interfaceName, method) }

    /** The handlers that an object of the class [className] registers when the workflow makes it a listener. */
    fun listenerEntryPoints(className: String): List<ImplementedEntryPoint> = workflows.listenerEntryPoints(className)

    /** The workflow type that a stub of the workflow interface [interfaceName] stands for. */
    fun workflowType(interfaceName: String): String? =
        workflowTypes.getOrPut(interfaceName) { workflows.workflowType(interfaceName) }

    /** The workflow interfaces of the analysed sources, by the workflow type each registers. */
    private val interfaces: Map<String, Set<String>> by lazy(LazyThreadSafetyMode.NONE) {
        workflows.discover(backend.sourceClasses()).groupBy({ it.name }, { it.declaration.name }).mapValues { it.value.toSet() }
    }

    /**
     * Whether the workflow type [workflowType] does not say that the interface [interfaceName]
     * is meant: the analysed sources hold another workflow interface that registers that type.
     */
    fun isShared(workflowType: String, interfaceName: String): Boolean = interfaces[workflowType].orEmpty().any { it != interfaceName }

    /** The name of the Nexus service that the interface [service] declares. */
    fun nexusService(service: TypeRef): String? {
        val name = service.name ?: return null
        return service.annotations.find(TemporalApi.NEXUS_SERVICE)?.text(TemporalTypes.NAME)?.ifEmpty { null }
            ?: backend.findClass(name)?.simpleName
            ?: name.substringAfterLast('.')
    }

    /** The name of the Nexus operation that [method] of the service interface [interfaceName] declares. */
    fun nexusOperation(interfaceName: String, method: FunctionId): String =
        operations.getOrPut(interfaceName to method) {
            val owner = method.owner ?: interfaceName
            val declaration = backend.functions(owner).firstOrNull { it.id == method }
            declaration?.annotations?.find(TemporalApi.NEXUS_OPERATION)?.text(TemporalTypes.NAME)?.ifEmpty { null }
                ?: method.simpleName
        }
}
