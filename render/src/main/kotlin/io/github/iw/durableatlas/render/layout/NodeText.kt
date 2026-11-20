package io.github.iw.durableatlas.render.layout

import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.Join
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.ParentClosePolicy
import io.github.iw.durableatlas.ir.WaitsFor

/** The words of the notation: what is written on nodes, regions and handler groups. */
internal object NodeText {
    const val HANDLERS = "handlers"
    const val REPEAT = "repeat"
    const val EXCEPTION = "exception"
    const val TIMEOUT = "timeout"
    const val JOIN = "join"
    const val CANCELS = "cancels"

    /** What is written in place of `join` where the lanes of a parallel section end that nothing waits for. */
    const val NOT_AWAITED = "not awaited"

    /** What a parallel section is called that the flow leaves when the first of its lanes has ended. */
    const val FIRST = "first to finish"

    /** What follows the names an upsert is known to write, where it writes others as well. */
    const val MORE = "…"

    /** How far the flow waits for something it started beside itself, in words; `null` where it waits for it to end. */
    fun waiting(node: IrNode): String? = when (node.waitsFor) {
        WaitsFor.NOTHING -> NOT_AWAITED
        WaitsFor.START -> "waits for start only"
        null -> null
    }

    /**
     * Which of the lanes of a parallel section the flow waits for, in words; `null` where it
     * waits for all of them. It is the title of such a section, and what is written in place
     * of `join` where the flow leaves it.
     */
    fun joining(node: IrNode): String? = when (node.join) {
        Join.FIRST -> FIRST
        null -> null
    }

    /** What becomes of a child workflow when its parent closes, in the words of the policy; `null` where the options do not say. */
    fun parentClose(node: IrNode): String? = when (node.parentClosePolicy) {
        ParentClosePolicy.ABANDON -> "abandon"
        null -> null
    }

    /** Label lines of a node drawn on its own, or of the head of an expanded region. */
    fun lines(node: IrNode, max: Int, caption: String? = null): List<String> {
        fun text(value: String?): String? = value?.singleLine(max)?.takeIf { it.isNotEmpty() }
        val name = text(node.name)
        val label = text(node.label)
        val detail = name ?: label
        val own = of(node, detail, name, label, caption, ::text)
        // What a region is not waited for is said by its title and where its lanes end.
        return if (node.kind.isContainer) {
            own
        } else {
            own + listOfNotNull(RequestTexts.line(node, max), waiting(node), parentClose(node)?.let { "parent close: $it" })
        }
    }

    private fun of(node: IrNode, detail: String?, name: String?, label: String?, caption: String?, text: (String?) -> String?): List<String> =
        when (node.kind) {
            NodeKind.START -> listOfNotNull("start", caption, detail)
            NodeKind.END -> listOfNotNull("end", detail)
            NodeKind.CONTINUE_AS_NEW -> listOfNotNull("continueAsNew", detail)
            NodeKind.FAIL -> listOfNotNull("fail", detail)
            NodeKind.ACTIVITY -> listOf(detail ?: "activity")
            NodeKind.LOCAL_ACTIVITY -> listOf("${detail ?: "activity"} (local)")
            NodeKind.CHILD_WORKFLOW -> listOf(detail ?: "child workflow")
            NodeKind.SIGNAL_EXTERNAL -> listOf(prefixed("signal external", detail))
            NodeKind.NEXUS_OPERATION ->
                listOf(prefixed("nexus", listOfNotNull(text(node.service), name).joinToString(".").ifEmpty { label }))
            NodeKind.TIMER -> listOf(spaced("timer", text(node.duration)?.let(Durations::humanise) ?: label))
            NodeKind.AWAIT -> listOfNotNull(
                spaced("await", text(node.reads.joinToString(", ")) ?: label),
                text(node.timeout)?.let { "timeout ${Durations.humanise(it)}" },
            )
            NodeKind.SIDE_EFFECT -> listOf(prefixed("side effect", detail))
            NodeKind.VERSION_GATE -> listOf(prefixed("version", text(node.changeId) ?: label))
            NodeKind.BRANCH -> listOf(label ?: name ?: "branch")
            NodeKind.COMPENSATION -> listOf(prefixed("compensation", detail))
            NodeKind.UNKNOWN -> listOfNotNull(
                prefixed("unknown", text(node.reason)),
                label,
                node.candidates.size.takeIf { it > 0 }?.let { if (it == 1) "1 candidate" else "$it candidates" },
            )
            NodeKind.LOOP -> listOf("loop")
            NodeKind.PARALLEL -> listOf("fork")
            NodeKind.CANCEL_SCOPE -> listOf(prefixed("cancel scope", detail))
            NodeKind.CANCEL_EXTERNAL -> listOf(prefixed("cancel external", detail))
            NodeKind.UPSERT_SEARCH_ATTRIBUTES -> listOf(prefixed("upsert search attributes", written(node, text) ?: label))
            NodeKind.UPSERT_MEMO -> listOf(prefixed("upsert memo", written(node, text) ?: label))
        }

    /**
     * What an upsert of search attributes or of the memo writes, where the document names it:
     * the keys, in its order, and a sign that there are more where the document says so.
     */
    fun written(node: IrNode, text: (String?) -> String? = { it }): String? =
        text((if (node.otherKeys) node.keys + MORE else node.keys).joinToString(", ").ifEmpty { null })

    /** Title of the box around an expanded region. */
    fun regionTitle(node: IrNode, max: Int): String {
        val label = node.label?.singleLine(max)?.takeIf { it.isNotEmpty() }
        return when (node.kind) {
            NodeKind.LOOP -> label ?: "loop"
            NodeKind.PARALLEL -> prefixed("parallel", label ?: waiting(node) ?: joining(node))
            else -> regionWord(node.kind)
        }
    }

    fun laneTitle(number: Int): String = "branch $number"

    /** Label lines of a region that is drawn as a single node. */
    fun collapsed(region: CollapsedRegion, max: Int): List<String> {
        val node = region.node
        val first = when (node.kind) {
            NodeKind.LOOP, NodeKind.PARALLEL ->
                prefixed(regionWord(node.kind), node.label?.singleLine(max)?.takeIf { it.isNotEmpty() } ?: waiting(node) ?: joining(node))
            else -> lines(node, max).first()
        }
        val hidden = buildString {
            append(if (region.hiddenNodes == 1) "1 node hidden" else "${region.hiddenNodes} nodes hidden")
            if (region.hiddenUnknown > 0) append(", ${region.hiddenUnknown} unknown")
        }
        return listOf(first, hidden)
    }

    private fun regionWord(kind: NodeKind): String = when (kind) {
        NodeKind.LOOP -> "loop"
        NodeKind.PARALLEL -> "parallel"
        NodeKind.COMPENSATION -> "compensation"
        else -> "region"
    }

    /** Title of the group that holds the graphs of one handler. */
    fun handlerTitle(group: HandlerGroup, max: Int): String {
        val kinds = group.entryPoints.map { it.kind }.toSet()
        val kind = if (kinds == MERGED_UPDATE) "update + validator" else kindName(group.entryPoints.first().kind)
        val reads = group.entryPoints.flatMap { it.reads }.distinct().sorted()
        val writes = group.entryPoints.flatMap { it.writes }.distinct().sorted()
        val parts = mutableListOf("$kind ${group.entryPoints.first().name}".singleLine(max))
        if (reads.isNotEmpty()) parts += "reads: ${reads.joinToString(", ")}".singleLine(max)
        if (writes.isNotEmpty()) parts += "writes: ${writes.joinToString(", ")}".singleLine(max)
        return parts.joinToString(" · ")
    }

    /** What tells the two start nodes of a merged update group apart. */
    fun startCaption(kind: EntryPointKind): String = if (kind == EntryPointKind.UPDATE_VALIDATOR) "validator" else kindName(kind)

    val MERGED_UPDATE: Set<EntryPointKind> = setOf(EntryPointKind.UPDATE, EntryPointKind.UPDATE_VALIDATOR)

    fun kindName(kind: EntryPointKind): String = when (kind) {
        EntryPointKind.WORKFLOW_METHOD -> "workflow method"
        EntryPointKind.SIGNAL -> "signal"
        EntryPointKind.QUERY -> "query"
        EntryPointKind.UPDATE -> "update"
        EntryPointKind.UPDATE_VALIDATOR -> "update validator"
    }

    private fun prefixed(prefix: String, detail: String?): String = if (detail == null) prefix else "$prefix: $detail"

    private fun spaced(prefix: String, detail: String?): String = if (detail == null) prefix else "$prefix $detail"
}
