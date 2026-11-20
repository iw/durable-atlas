package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.LeftOutCall
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.SkippedCall
import io.github.iw.durableatlas.ir.SourceLocation
import io.github.iw.durableatlas.ir.UnknownReasons

/**
 * Everything a diagram file says about its workflow besides the drawing, as plain data: every
 * format shows the same header, child-workflow links and list of unknown nodes in its own way,
 * and says of the calls the document left out, as unresolved calls into libraries or because it
 * is an overview, what it has room for.
 */
internal class PageContent(
    val title: String,
    val indexFile: String?,
    /** Set when the document comes from a degraded model. */
    val warning: String?,
    /** What was done because the document is over the node limit, if anything. */
    val limitNotice: String?,
    val notices: List<String>,
    val facts: List<Fact>,
    val childWorkflows: List<ChildWorkflow>,
    val unknownNodes: List<UnknownNode>,
    /** The calls that did not resolve and that the document has no node for, in its order. */
    val skippedCalls: List<SkippedCall> = emptyList(),
    /** What is drawn more simply than asked for because drawing it as asked took too long, if anything. */
    val simplifiedNotice: String? = null,
    /** The calls that the document has no node for because it is an overview, in its order. */
    val leftOutCalls: List<LeftOutCall> = emptyList(),
) {
    /** What a drawing without room for the list says of the calls that were left out; `null` if none was. */
    val skippedNotice: String?
        get() = skippedNotice(skippedCalls.size)

    /** What a drawing without room for the list says of the calls that an overview has no node for; `null` if there is none. */
    val leftOutNotice: String?
        get() = leftOutNotice(leftOutCalls)

    /** @param literal whether the value is a name or identifier rather than prose */
    class Fact(val name: String, val value: String, val literal: Boolean)

    /**
     * A workflow type started as a child, with the files that show workflows of that type.
     *
     * @param interfaceName the workflow interface the child's stub was made for, where the
     *   document names it: the files are then those of that interface alone
     */
    class ChildWorkflow(val workflowType: String, val files: List<String>, val interfaceName: String? = null)

    class UnknownNode(val reason: String, val label: String?, val location: SourceLocation?, val candidates: List<String>)

    companion object {
        const val DEGRADED_WARNING: String =
            "this diagram comes from a degraded model. The build of the analysed repository was not used, " +
                "so calls into other modules and libraries may be missing or unresolved."

        /** What the calls that are left out are, for whoever reads the list of them. */
        const val SKIPPED_CALLS: String =
            "These calls did not resolve and were taken for calls into libraries that the degraded model lacks: " +
                "none is made through a Temporal stub or names the Temporal API, so none is drawn."

        /** One sentence that says how many calls were left out; `null` for none. */
        fun skippedNotice(count: Int): String? =
            when (count) {
                0 -> null
                1 -> "1 call that did not resolve is not drawn: it was taken for a call into a library that the degraded model lacks."
                else -> "$count calls that did not resolve are not drawn: they were taken for calls into libraries that the degraded model lacks."
            }

        /** What the calls that an overview leaves out are, for whoever reads the list of them. */
        const val LEFT_OUT_CALLS: String =
            "This diagram is an overview: it has no node for a call that the analysis could not read and behind which a step " +
                "of the workflow is only possible. A call that is `unsupported` is not modelled and was handed code: what that " +
                "code holds is drawn where the call is made, as if the call ran it once. A call that is `unseen-code` or " +
                "`dynamic-dispatch` leads to a function that no class of the analysed sources implements. " +
                "An analysis with `--detail full` draws each of them as an unknown node."

        /** One sentence that says how many calls an overview has no node for, and why; `null` for none. */
        fun leftOutNotice(calls: List<LeftOutCall>): String? {
            if (calls.isEmpty()) return null
            val handed = calls.count { it.reason == UnknownReasons.UNSUPPORTED }
            val unseen = calls.size - handed
            fun handed(which: String, one: Boolean): String =
                if (one) "$which is not modelled, and the code handed to it is drawn as run once where it stands"
                else "$which are not modelled, and the code handed to them is drawn as run once where they stand"
            fun unseen(which: String, one: Boolean): String =
                if (one) "$which leads to code that is not in the analysed sources" else "$which lead to code that is not in the analysed sources"
            val all = if (calls.size == 1) "it" else "they"
            val why = when {
                unseen == 0 -> handed(all, calls.size == 1)
                handed == 0 -> unseen(all, calls.size == 1)
                else -> "${handed(handed.toString(), handed == 1)}; ${unseen(unseen.toString(), unseen == 1)}"
            }
            return if (calls.size == 1) {
                "Overview: 1 call that could not be read is not drawn: $why. An analysis with --detail full draws it as an unknown node."
            } else {
                "Overview: ${calls.size} calls that could not be read are not drawn: $why. An analysis with --detail full draws each as an unknown node."
            }
        }

        fun of(document: IrDocument, limit: NodeLimit, options: PageOptions, overlay: DiagramOverlay): PageContent =
            PageContent(
                title = overlay.title ?: document.workflowType,
                indexFile = options.indexFile,
                warning = DEGRADED_WARNING.takeIf { document.modelMode == ModelMode.DEGRADED },
                limitNotice = limit.notice(),
                notices = overlay.notices,
                facts = listOf(
                    Fact("Workflow type", document.workflowType, literal = true),
                    Fact("Interface", document.interfaceName, literal = true),
                    Fact("Implementation", document.implementation, literal = true),
                    Fact("Module", document.module, literal = true),
                    Fact("Commit", document.commit, literal = true),
                    Fact("Model mode", modeName(document.modelMode), literal = false),
                    Fact("Nodes", document.nodes.size.toString(), literal = false),
                    Fact("Unknown nodes", document.nodes.count { it.kind == NodeKind.UNKNOWN }.toString(), literal = false),
                ) + listOfNotNull(
                    Fact("Skipped calls", document.skippedCalls.size.toString(), literal = false).takeIf { document.skippedCalls.isNotEmpty() },
                    Fact("Calls left out", document.leftOutCalls.size.toString(), literal = false).takeIf { document.leftOutCalls.isNotEmpty() },
                ) + overlay.facts.map { Fact(it.name, it.value, literal = false) },
                childWorkflows = document.nodes
                    .filter { it.kind == NodeKind.CHILD_WORKFLOW && it.name != null }
                    .map { it.name!! to it.interfaceName }
                    .distinct()
                    .sortedWith(compareBy({ it.first }, { it.second.orEmpty() }))
                    .map { (type, stub) ->
                        val files = if (stub == null) options.diagramFiles[type] else options.interfaceFiles[stub]
                        ChildWorkflow(type, files.orEmpty().sorted(), stub)
                    },
                unknownNodes = document.nodes
                    .filter { it.kind == NodeKind.UNKNOWN }
                    .sortedWith(compareBy<IrNode>({ it.loc == null }, { it.loc?.file }, { it.loc?.line }, { it.id }))
                    .map { UnknownNode(it.reason ?: "unknown", it.label, it.loc, it.candidates.sorted()) },
                skippedCalls = document.skippedCalls,
                simplifiedNotice = options.render.simplification?.notice(limit),
                leftOutCalls = document.leftOutCalls,
            )

        fun modeName(mode: ModelMode): String = when (mode) {
            ModelMode.GRADLE -> "gradle"
            ModelMode.DEGRADED -> "degraded"
        }
    }
}
