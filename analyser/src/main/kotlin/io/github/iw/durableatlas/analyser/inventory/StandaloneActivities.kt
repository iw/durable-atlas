package io.github.iw.durableatlas.analyser.inventory

import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.SourceLocation
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The activity types a commit declares that none of its workflows calls. They have no place in
 * a workflow diagram; a Temporal server can run such activities on their own, outside any
 * workflow.
 *
 * An activity counts as called if a workflow has an activity or local activity step of its
 * name, whichever stub the step goes through. What the analysis could not follow may call an
 * activity all the same, so the workflow types that hold such calls are named.
 *
 * Property order is the key order of the JSON form.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StandaloneActivities(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Commit of the analysed repository. */
    val commit: String,
    val modelMode: ModelMode,
    /** Ordered by activity type, then by interface. */
    val activities: List<StandaloneActivity>,
    /**
     * Workflow types with a call the analysis could not follow, or whose graph could not be made
     * at all, ordered by name. A listed activity may be called from one of them.
     */
    val unresolvedWorkflows: List<String>,
) {
    companion object {
        /** Version of the JSON format written by this module. */
        const val SCHEMA_VERSION: Int = 1

        /**
         * @param documents the graphs of every workflow type of the commit
         * @param withoutGraph workflow types that have code but no graph, because making it failed
         */
        fun of(
            discovery: Discovery,
            documents: List<IrDocument>,
            withoutGraph: Collection<String>,
            commit: String,
            modelMode: ModelMode,
        ): StandaloneActivities {
            val called = documents.flatMap { it.nodes }
                .filter { it.kind == NodeKind.ACTIVITY || it.kind == NodeKind.LOCAL_ACTIVITY }
                .mapNotNull { it.name }
                .toSet()
            val activities = discovery.activityInterfaces.flatMap { declared ->
                declared.activities.filter { it.name !in called }.map { activity ->
                    StandaloneActivity(
                        name = activity.name,
                        interfaceName = declared.declaration.name,
                        method = activity.method.name,
                        module = declared.declaration.module,
                        loc = activity.method.location ?: declared.declaration.location,
                    )
                }
            }
            val unresolved = documents.filter { document -> document.nodes.any { it.kind == NodeKind.UNKNOWN } }.map { it.workflowType }
            return StandaloneActivities(
                commit = commit,
                modelMode = modelMode,
                activities = activities.sortedWith(compareBy({ it.name }, { it.interfaceName })),
                unresolvedWorkflows = (unresolved + withoutGraph).distinct().sorted(),
            )
        }
    }
}

@Serializable
data class StandaloneActivity(
    /** Activity type name as registered with Temporal. */
    val name: String,
    /** Fully qualified name of the activity interface that declares it. */
    @SerialName("interface")
    val interfaceName: String,
    /** Name of the interface method. */
    val method: String,
    /** Path of the module that declares the interface; absent if the interface comes from a library. */
    val module: String? = null,
    /** Where the method is declared; absent if the interface comes from a library. */
    val loc: SourceLocation? = null,
)

/**
 * Reads and writes the JSON form of [StandaloneActivities]. Output is deterministic: fixed key
 * order, unset optional properties left out, two-space indentation, `\n` line endings and a
 * trailing newline.
 */
object StandaloneActivitiesJson {
    /** Name of the file inside the directory the analyser writes to. */
    const val FILE_NAME: String = "standalone-activities.json"

    /** Classpath location of the JSON Schema (draft 2020-12) of the format. */
    const val SCHEMA_RESOURCE: String = "io/github/iw/durableatlas/analyser/inventory/standalone-activities.schema.json"

    @OptIn(ExperimentalSerializationApi::class)
    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
        explicitNulls = false
    }

    fun schema(): String {
        val stream = checkNotNull(StandaloneActivitiesJson::class.java.classLoader.getResourceAsStream(SCHEMA_RESOURCE)) {
            "Missing classpath resource $SCHEMA_RESOURCE"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun encode(activities: StandaloneActivities): String = format.encodeToString(StandaloneActivities.serializer(), activities) + "\n"

    /** @throws InventoryFormatException if [text] is not in this format, or in another version of it */
    fun decode(text: String): StandaloneActivities {
        val activities = try {
            format.decodeFromString(StandaloneActivities.serializer(), text)
        } catch (e: SerializationException) {
            throw InventoryFormatException("Malformed list of standalone activities: ${e.message}", e)
        }
        if (activities.schemaVersion != StandaloneActivities.SCHEMA_VERSION) {
            throw InventoryFormatException(
                "Unsupported schemaVersion ${activities.schemaVersion} of a list of standalone activities " +
                    "(supported: ${StandaloneActivities.SCHEMA_VERSION})",
            )
        }
        return activities
    }
}
