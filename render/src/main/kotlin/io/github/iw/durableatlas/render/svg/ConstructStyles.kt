package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.NodeKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** How one construct is drawn: its colour, its icon and the words that name it. */
@Serializable
internal data class ConstructStyle(
    val kind: String,
    /** Name of the construct in the legend. */
    val legend: String,
    val kindLabel: String,
    /** `card`, `outlineCard`, `pill` or `region`. */
    val shape: String,
    val colour: String,
    /** SVG markup of the icon, for a `0 0 20 20` view box. */
    val icon: String,
) {
    val outlined: Boolean
        get() = shape == "outlineCard"
}

/** The names under which constructs are styled: the kinds of nodes and of handlers. */
internal object StyleKinds {
    const val START = "start"
    const val PARALLEL = "parallel"
    const val LOOP = "loop"
    const val COMPENSATION = "compensation"
    const val SIGNAL = "signal"
    const val UPDATE = "update"
    const val QUERY = "query"

    /** The band that holds the handlers. It is drawn like a region but is no construct of its own. */
    const val HANDLERS = "handlers"

    fun of(kind: NodeKind): String = when (kind) {
        NodeKind.START -> START
        NodeKind.END -> "end"
        NodeKind.ACTIVITY -> "activity"
        NodeKind.LOCAL_ACTIVITY -> "localActivity"
        NodeKind.CHILD_WORKFLOW -> "childWorkflow"
        NodeKind.SIGNAL_EXTERNAL -> "signalExternal"
        NodeKind.NEXUS_OPERATION -> "nexusOperation"
        NodeKind.TIMER -> "timer"
        NodeKind.AWAIT -> "await"
        NodeKind.SIDE_EFFECT -> "sideEffect"
        NodeKind.VERSION_GATE -> "versionGate"
        NodeKind.BRANCH -> "branch"
        NodeKind.LOOP -> LOOP
        NodeKind.PARALLEL -> PARALLEL
        NodeKind.COMPENSATION -> COMPENSATION
        NodeKind.CONTINUE_AS_NEW -> "continueAsNew"
        NodeKind.FAIL -> "fail"
        NodeKind.UNKNOWN -> "unknown"
        NodeKind.CANCEL_SCOPE -> "cancelScope"
        NodeKind.CANCEL_EXTERNAL -> "cancelExternal"
        NodeKind.UPSERT_SEARCH_ATTRIBUTES -> "upsertSearchAttributes"
        NodeKind.UPSERT_MEMO -> "upsertMemo"
    }

    fun of(kind: EntryPointKind): String = when (kind) {
        EntryPointKind.SIGNAL -> SIGNAL
        EntryPointKind.QUERY -> QUERY
        EntryPointKind.UPDATE, EntryPointKind.UPDATE_VALIDATOR -> UPDATE
        EntryPointKind.WORKFLOW_METHOD -> START
    }

    /**
     * The order in which constructs are listed in a legend: the nodes as they come in a
     * workflow, then the regions, the ways a workflow stops, and the handlers.
     */
    val legendOrder: List<String> = listOf(
        START, "activity", "localActivity", "childWorkflow", "nexusOperation", "timer", "await",
        "signalExternal", "cancelScope", "cancelExternal", "upsertSearchAttributes", "upsertMemo", "sideEffect", "versionGate", "branch",
        PARALLEL, LOOP, COMPENSATION,
        "continueAsNew", "end", "fail", "unknown", SIGNAL, UPDATE, QUERY,
    )
}

/** The style of every construct, read from the style data shipped with this module. */
internal object ConstructStyles {
    private const val RESOURCE = "construct-styles.json"

    private val styles: Map<String, ConstructStyle> = run {
        val text = checkNotNull(ConstructStyles::class.java.getResourceAsStream(RESOURCE)) { "Missing resource $RESOURCE" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        Json.decodeFromString(ListSerializer(ConstructStyle.serializer()), text).associateBy { it.kind }
    }

    init {
        val missing = StyleKinds.legendOrder - styles.keys
        check(missing.isEmpty()) { "No style for $missing in $RESOURCE" }
    }

    fun of(kind: String): ConstructStyle =
        if (kind == StyleKinds.HANDLERS) styles.getValue(StyleKinds.SIGNAL) else styles.getValue(kind)

    /** Every construct, in the order of the legend. */
    val all: List<ConstructStyle> = StyleKinds.legendOrder.map(styles::getValue)
}
