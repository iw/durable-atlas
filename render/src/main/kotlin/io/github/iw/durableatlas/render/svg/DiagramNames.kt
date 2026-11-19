package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.render.DiagramOverlay
import io.github.iw.durableatlas.render.PageOptions

/**
 * What a diagram calls itself. Several workflows can have one type, each with a class of its
 * own that implements it, so a diagram always names that class as well. A dynamic workflow, a
 * class that runs for every workflow type without an implementation of its own, has no type to
 * be called by: the type in its document is made up from the name of the class, and a diagram
 * says what it is instead.
 */
internal object DiagramNames {
    /** Stands between the parts of a name, as it does in the kind labels of the notation. */
    const val SEPARATOR = " · "

    /** What a dynamic workflow is called in place of a type. */
    const val DYNAMIC = "Dynamic workflow"

    /** What the workflow of [document] is called: its type, or what it is if it has none. */
    fun workflow(document: IrDocument): String = if (document.isDynamic) DYNAMIC else document.workflowType

    /** The name of the diagram file as a whole, for its `title` element or the title of a page around it. */
    fun title(document: IrDocument, overlay: DiagramOverlay): String {
        // The title of an overlay names the type its executions ran as, which says nothing of the kind of workflow.
        val implementation = if (document.isDynamic && overlay.title != null) "${DYNAMIC.lowercase()} ${document.implementation}" else document.implementation
        return listOf(overlay.title ?: workflow(document), implementation).filter { it.isNotBlank() }.joinToString(SEPARATOR)
    }

    /** The same in a sentence, for readers that are read to. */
    fun spoken(document: IrDocument, overlay: DiagramOverlay): String {
        val subject = overlay.title ?: if (document.isDynamic) DYNAMIC.lowercase() else document.workflowType
        val kind = if (document.isDynamic && overlay.title != null) "a ${DYNAMIC.lowercase()} " else ""
        val name = "Workflow diagram: $subject"
        return if (document.implementation.isBlank()) name else "$name, ${kind}implemented by ${document.implementation}"
    }

    /** Whether the diagrams written together with this one include another of the same workflow type. */
    fun isShared(document: IrDocument, page: PageOptions): Boolean = page.diagramFiles[document.workflowType].orEmpty().size > 1

    /**
     * Whether the diagram needs a line above the drawing that says which workflow it shows: one
     * of several of its type, or one without a type of its own.
     */
    fun needsHeading(document: IrDocument, page: PageOptions): Boolean = document.isDynamic || isShared(document, page)
}
