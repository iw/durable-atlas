package io.github.iw.durableatlas.render.layout

import io.github.iw.durableatlas.render.Decoration
import io.github.iw.durableatlas.render.DiagramDecorator
import io.github.iw.durableatlas.render.Emphasis

/**
 * Answers how the elements of a [DiagramLayout] are decorated. It is asked while drawing, never
 * while laying out, so the same positioned layout can be drawn plain and decorated.
 */
internal class LayoutDecorations(private val decorator: DiagramDecorator) {
    fun of(item: Item): Decoration? = decorator.node(item.node)?.normalised()

    fun of(group: HandlerGroup): Decoration? = combine(group.entryPoints.map(decorator::entryPoint))

    fun of(edge: LaidEdge): Decoration? = combine(edge.sources.map(decorator::edge))

    /**
     * One decoration for several things that are drawn as one. The strongest emphasis wins, where
     * a thing without decoration counts as normal, and the notes are listed.
     */
    fun combine(decorations: List<Decoration?>): Decoration? {
        val emphasis = decorations.maxOf { it?.emphasis ?: Emphasis.NORMAL }
        val notes = decorations.mapNotNull { it?.note?.singleLine() }.filter { it.isNotEmpty() }.distinct()
        return Decoration(emphasis, notes.joinToString(", ").takeIf { it.isNotEmpty() }).takeUnless { it == PLAIN }
    }

    private fun Decoration.normalised(): Decoration? = combine(listOf(this))

    private companion object {
        val PLAIN = Decoration()
    }
}
