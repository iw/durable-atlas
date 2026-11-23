package io.github.iw.durableatlas.analyser.inventory

import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.ImplementedEntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.ModelType
import io.github.iw.durableatlas.ir.Request
import io.github.iw.durableatlas.ir.SourceLocation
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything Temporal-related that a commit declares: its workflow types with their entry points
 * and implementations, and its activity interfaces with the activity types they register.
 *
 * Property order is the key order of the JSON form.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Inventory(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Commit of the analysed repository. */
    val commit: String,
    val modelMode: ModelMode,
    /** Ordered by workflow type, then by interface. */
    val workflows: List<InventoryWorkflow>,
    /** Ordered by interface. */
    val activityInterfaces: List<InventoryActivityInterface>,
    /**
     * The shape of each type that the requests of the activities send or receive, and of each type
     * those types reach through their properties, by the fully qualified name of the class; a
     * class whose source was not analysed by the name the sources write it with. Ordered by name.
     */
    val types: Map<String, ModelType> = emptyMap(),
) {
    companion object {
        /** Version of the JSON format written by this module. */
        const val SCHEMA_VERSION: Int = 1

        /**
         * @param requests what the implementation of each activity type asks of other services, by activity type
         * @param types the shapes of the types the requests name, by name
         */
        fun of(
            discovery: Discovery,
            commit: String,
            modelMode: ModelMode,
            requests: Map<String, List<Request>> = emptyMap(),
            types: Map<String, ModelType> = emptyMap(),
        ): Inventory =
            Inventory(
                commit = commit,
                modelMode = modelMode,
                workflows = discovery.workflows.map { workflow ->
                    InventoryWorkflow(
                        workflowType = workflow.name,
                        interfaceName = workflow.declaration.name,
                        module = workflow.declaration.module,
                        loc = workflow.declaration.location,
                        entryPoints = workflow.entryPoints.map {
                            InventoryEntryPoint(it.kind, it.name, it.method.name)
                        },
                        implementations = workflow.implementations.map { implementation ->
                            InventoryImplementation(
                                className = implementation.declaration.name,
                                module = implementation.declaration.module,
                                loc = implementation.declaration.location,
                                additionalEntryPoints = implementation.additionalEntryPoints.map(::entryPoint),
                            )
                        },
                    )
                },
                activityInterfaces = discovery.activityInterfaces.map { activityInterface ->
                    InventoryActivityInterface(
                        interfaceName = activityInterface.declaration.name,
                        module = activityInterface.declaration.module,
                        loc = activityInterface.declaration.location,
                        namePrefix = activityInterface.namePrefix.ifEmpty { null },
                        activities = activityInterface.activities.map { InventoryActivity(it.name, it.method.name, requests[it.name].orEmpty()) },
                        implementations = activityInterface.implementations.map {
                            InventoryImplementation(it.name, it.module, it.location)
                        },
                    )
                },
                types = types.toSortedMap(),
            )

        private fun entryPoint(implemented: ImplementedEntryPoint): InventoryEntryPoint =
            implemented.entryPoint.let { InventoryEntryPoint(it.kind, it.name, it.method.name) }
    }
}

@Serializable
data class InventoryWorkflow(
    /** Workflow type name as registered with Temporal. */
    val workflowType: String,
    /** Fully qualified name of the workflow interface. */
    @SerialName("interface")
    val interfaceName: String,
    /** Path of the module that declares the interface; absent if the interface comes from a library. */
    val module: String? = null,
    /** Where the interface is declared; absent if it comes from a library. */
    val loc: SourceLocation? = null,
    /** The workflow method first, then signals, queries, updates and update validators, each by name. */
    val entryPoints: List<InventoryEntryPoint>,
    /** Classes that implement the interface, ordered by name. */
    val implementations: List<InventoryImplementation>,
)

@Serializable
data class InventoryEntryPoint(
    val kind: EntryPointKind,
    /** Workflow type, signal, query or update name as registered with Temporal. */
    val name: String,
    /** Name of the interface method. */
    val method: String,
)

@Serializable
data class InventoryImplementation(
    /** Fully qualified name of the implementing class. */
    @SerialName("class")
    val className: String,
    /** Path of the module that declares the class. */
    val module: String? = null,
    val loc: SourceLocation? = null,
    /**
     * Handlers the class registers through workflow interfaces other than the one that names the
     * workflow type and its supertypes.
     */
    val additionalEntryPoints: List<InventoryEntryPoint> = emptyList(),
)

@Serializable
data class InventoryActivityInterface(
    /** Fully qualified name of the activity interface. */
    @SerialName("interface")
    val interfaceName: String,
    /** Path of the module that declares the interface; absent if the interface comes from a library. */
    val module: String? = null,
    val loc: SourceLocation? = null,
    /** Prefix of the interface's default activity type names, if it declares one. */
    val namePrefix: String? = null,
    /** The activity types the interface registers, ordered by name. */
    val activities: List<InventoryActivity>,
    /** Classes that implement the interface, ordered by name. */
    val implementations: List<InventoryImplementation>,
)

@Serializable
data class InventoryActivity(
    /** Activity type name as registered with Temporal. */
    val name: String,
    /** Name of the interface method. */
    val method: String,
    /**
     * What the implementation of the activity type asks of other services, in the order of its
     * code, as the IR gives it for each node that runs the activity type; see [Request]. Empty
     * where it asks nothing the analysis can see, and where no class of the sources implements it.
     */
    val requests: List<Request> = emptyList(),
)
