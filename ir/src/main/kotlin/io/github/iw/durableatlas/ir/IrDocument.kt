package io.github.iw.durableatlas.ir

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The intermediate representation of one workflow type: a flat list of nodes and a flat list of
 * edges. Every entry point (the workflow method and each handler) has a graph of its own among
 * them, which begins at the entry point's `start` node.
 *
 * Regions (loops, parallel sections, compensations) are expressed through [IrNode.parent]: the
 * nodes inside a region name the region's node as their parent. A region is one step of the flow
 * around it, and its edges follow one convention:
 *
 * - An edge into a region ends at the region's node, and an edge that leaves the region once it
 *   is done starts there.
 * - Inside a region only edges between its children are written. That the children no sibling
 *   leads to are entered first is implied. So is that a child goes on to the end of the region
 *   (in a loop: back to its head), unless it ends the flow or one of its edges says where it
 *   goes: an edge of any kind to a sibling, to its region or to a region further out, or a flow
 *   edge to a node outside the region.
 * - An edge from a child straight to a node outside the region is written out: an early return, a
 *   `break`, a failure. A flow edge among these says where the child goes, so the child does
 *   not go on to the end of the region as well; an exception or timeout edge is one more way on
 *   from the child and leaves the implied one in place.
 * - An edge from a node to the node of a region around it, its own or one further out, says that
 *   the flow goes from the node to the end of that region and leaves every region in between:
 *   the end of the round of a loop (a `continue`, also one that names an outer loop; a `break`
 *   of a loop that the round ends with), of a lane of a parallel section, of the chain of a
 *   compensation. To the end of its own region it is written where the implied way out would
 *   not say it: the edge has a label or is no plain flow edge, or the node has another edge
 *   that says where it goes. To the end of a region further out it is always written. No other
 *   edge joins a region and a node inside it.
 * - The lanes of a parallel region are the connected groups of its children.
 * - A parallel region whose [IrNode.waitsFor] is set is not waited for: its lanes are started,
 *   and the flow goes on with the edges that leave the region, at once or, for
 *   [WaitsFor.START], once the child workflows in it that say the same have started. Its lanes
 *   end at the end of the region like any others; nothing follows them there.
 * - A parallel region whose [IrNode.join] is [Join.FIRST] is left when the first of its lanes
 *   has ended: the edges that leave the region are taken then. The other lanes run on and end
 *   at the end of the region, where nothing follows them.
 * - `wakes` and `cancels` edges are no part of the flow ([EdgeKind.isFlow]) and count for none
 *   of the above: a `wakes` edge leads from a handler to an await it can end, or from the
 *   workflow method to an await that its own code ends from elsewhere; a `cancels` edge leads
 *   from the node where a cancellation scope is cancelled to what the scope holds.
 *
 * Property order is the key order of the JSON form, so it must not be rearranged casually.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class IrDocument(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Commit of the analysed repository this document was produced from. */
    val commit: String,
    val modelMode: ModelMode,
    /**
     * Workflow type name as registered with Temporal. A class that runs for every workflow type
     * without an implementation of its own has no such name: it is called `dynamic-` followed by
     * the simple name of the class, and its interface is the SDK's `DynamicWorkflow`.
     */
    val workflowType: String,
    /** Fully qualified name of the workflow interface. */
    @SerialName("interface")
    val interfaceName: String,
    /** Fully qualified name of the implementation class. */
    val implementation: String,
    /** Gradle path of the module that contains the implementation, for example `:orders:workflows`. */
    val module: String,
    val entryPoints: List<EntryPoint>,
    val nodes: List<IrNode>,
    val edges: List<IrEdge>,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val stats: IrStats = IrStats.of(nodes),
    /**
     * The calls in the code of the workflow that did not resolve and that no node stands for,
     * ordered by place. Only a document of a [ModelMode.DEGRADED] model has any; see [SkippedCall].
     */
    val skippedCalls: List<SkippedCall> = emptyList(),
) {
    /** Whether the document is that of a class that runs for every workflow type without an implementation of its own. */
    val isDynamic: Boolean get() = interfaceName == DYNAMIC_INTERFACE

    companion object {
        /** Version of the JSON format written by this module. */
        const val SCHEMA_VERSION: Int = 1

        /** The interface of a class that runs for every workflow type without an implementation of its own. */
        const val DYNAMIC_INTERFACE: String = "io.temporal.workflow.DynamicWorkflow"
    }
}

/** How the source model behind a document was obtained. */
@Serializable
enum class ModelMode {
    /** The model came from the analysed repository's own Gradle build. */
    @SerialName("gradle")
    GRADLE,

    /** The model came from a file system scan, after Gradle extraction was skipped or failed. */
    @SerialName("degraded")
    DEGRADED,
}

/**
 * A call that did not resolve and was taken for a call into a library, which is no step of a
 * workflow: the document has no node for it.
 *
 * A degraded model knows the Temporal SDK with its Kotlin extensions and the Kotlin standard
 * library, and none of the other libraries the analysed sources use, so most calls that do not
 * resolve there are calls into those. A model made from the build resolves them to library functions and draws nothing for
 * them, and a degraded model does the same for every such call that cannot be a step: one that
 * is not made through a stub, does not name the Temporal API, and names no function of the
 * analysed sources. A call that may be any of these keeps its node.
 *
 * The calls are listed so that none goes missing unseen: those in the code that was read for
 * the workflow, in whichever of its entry points, and those in the functions that were looked
 * through and found to hold nothing else. A call is listed once, wherever it was met from.
 */
@Serializable
data class SkippedCall(
    /** Where the call is written. */
    val loc: SourceLocation,
    /** The call as it is written, on one line and shortened where it is long. */
    val text: String,
)

/** Summary counters, so that consumers need not load the whole graph to report on it. */
@Serializable
data class IrStats(
    val nodes: Int,
    val unknown: Int,
) {
    companion object {
        fun of(nodes: Collection<IrNode>): IrStats =
            IrStats(nodes = nodes.size, unknown = nodes.count { it.kind == NodeKind.UNKNOWN })
    }
}
