package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.render.svg.compose.LabelTidier
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where a label goes that was set in a bad place. */
class LabelTidierTest {
    // A loop with a card in it, and an edge that runs from the card down to the end of the loop.
    private val loop = LabelTidier.Frame(Box(100.0, 100.0, 220.0, 240.0), header = Box(112.0, 109.0, 50.0, 20.0))
    private val card = Box(120.0, 150.0, 160.0, 60.0)
    private val down = "M200 210V338"

    private fun edge(path: String, label: Box?, kind: String = "flow", to: String = "loop", from: String = "card"): PositionedEdge =
        PositionedEdge(from, to, kind, path, label?.let { PositionedLabel("label", it) })

    /** Where the label of the edge down the loop ends up if it is set at [label]. */
    private fun tidied(label: Box, cards: List<Box> = listOf(card), corners: List<Box> = emptyList(), path: String = down): Box =
        LabelTidier.tidy(listOf(edge(path, label)), cards, listOf(loop), corners).edges.single().label!!.box

    @Test
    fun `a label beside its edge that lies on nothing stays`() {
        val beside = Box(206.0, 250.0, 48.0, 16.0)
        assertEquals(beside, tidied(beside))
        // Close to the border is no fault, as long as it is off the line that is drawn there.
        val low = Box(206.0, 322.0, 48.0, 16.0)
        assertEquals(low, tidied(low))
    }

    @Test
    fun `a label on the border of a region is slid back into it`() {
        assertEquals(Box(206.0, 322.0, 48.0, 16.0), tidied(Box(206.0, 330.0, 48.0, 16.0)))
        // Inside the box, but on the line of the border.
        assertEquals(Box(206.0, 316.0, 48.0, 16.0), tidied(Box(206.0, 324.0, 48.0, 16.0)))
    }

    @Test
    fun `a label outside the region its edge runs in is set beside the edge inside it`() {
        // Below the loop, beside the end of an edge that never leaves it.
        assertEquals(Box(206.0, 318.0, 48.0, 16.0), tidied(Box(206.0, 342.0, 48.0, 16.0)))
    }

    @Test
    fun `a label on the header of a region is slid off it`() {
        // An edge that runs across the loop below its header, with its label set above it, on the title.
        val across = "M110 132H310"
        assertEquals(Box(166.0, 110.0, 48.0, 16.0), tidied(Box(126.0, 110.0, 48.0, 16.0), path = across))
    }

    @Test
    fun `a label far from its edge is set beside the nearest stretch of it that is free`() {
        // On the card, far from the edge that leaves it: no way of sliding it gets it beside the edge.
        assertEquals(Box(146.0, 218.0, 48.0, 16.0), tidied(Box(130.0, 170.0, 48.0, 16.0)))
    }

    @Test
    fun `a label where a badge goes is slid out of that corner`() {
        val badge = Box(230.0, 241.0, Geometry.BADGE_ROOM, Geometry.BADGE_HEIGHT)
        assertEquals(Box(206.0, 266.0, 48.0, 16.0), tidied(Box(206.0, 250.0, 48.0, 16.0), corners = listOf(badge)))
        // Between two cards there is no room further along the edge: it goes to the other side.
        val below = Box(120.0, 236.0, 160.0, 60.0)
        val corner = Geometry.badge(below, Geometry.BADGE_ROOM)
        assertEquals(Box(144.0, 215.0, 48.0, 16.0), tidied(Box(208.0, 215.0, 48.0, 16.0), listOf(card, below), listOf(corner), "M200 210V236"))
    }

    @Test
    fun `a label with no better place stays where it is`() {
        // Between two cards that leave less room than a label needs.
        val below = Box(120.0, 228.0, 160.0, 60.0)
        val label = Box(206.0, 211.0, 48.0, 16.0)
        assertEquals(label, tidied(label, listOf(card, below), path = "M200 210V228"))
    }

    @Test
    fun `a label that is left lying on something is reported`() {
        // Between two cards that leave less room than a label needs, as above; the badge of the lower one goes where the label ends.
        val below = Box(120.0, 228.0, 160.0, 60.0)
        val label = Box(206.0, 211.0, 48.0, 16.0)
        val between = listOf(edge("M200 210V228", label))
        fun cramped(corners: List<Box>): Set<Int> = LabelTidier.tidy(between, listOf(card, below), listOf(loop), corners).cramped

        assertEquals(setOf(0), cramped(listOf(Geometry.badge(below, Geometry.BADGE_ROOM))))
        // The same label with nothing under it has no better place either, and is no worse for it.
        assertEquals(emptySet(), cramped(emptyList()))
        // One that only touches the corner lies on nothing, wherever rounding puts the two.
        assertEquals(emptySet(), cramped(listOf(Box(label.right - 0.1, 219.0, Geometry.BADGE_ROOM, Geometry.BADGE_HEIGHT))))
    }

    @Test
    fun `a label that found a place is not reported, nor is one that another label has moved away from`() {
        // On a badge, with room further along its edge.
        val badge = Box(230.0, 241.0, Geometry.BADGE_ROOM, Geometry.BADGE_HEIGHT)
        val moved = LabelTidier.tidy(listOf(edge(down, Box(206.0, 250.0, 48.0, 16.0))), listOf(card), listOf(loop), listOf(badge))
        assertEquals(Box(206.0, 266.0, 48.0, 16.0), moved.edges.single().label!!.box)
        assertEquals(emptySet(), moved.cramped)

        // The first label cannot move and has the second on it; the second slides along its own edge, off the card and off the first.
        val below = Box(120.0, 228.0, 160.0, 60.0)
        val stuck = Box(206.0, 211.0, 48.0, 16.0)
        val edges = listOf(edge("M200 210V228", stuck), edge("M290 219H420", Box(250.0, 197.0, 60.0, 16.0), from = "other", to = "far"))
        val tidy = LabelTidier.tidy(edges, listOf(card, below), listOf(loop), emptyList())
        assertEquals(stuck, tidy.edges[0].label!!.box)
        assertEquals(emptyList(), listOf(card, below, stuck).filter { it.intersects(tidy.edges[1].label!!.box) })
        assertEquals(emptySet(), tidy.cramped)
    }

    @Test
    fun `the label of the edges that wake one step may sit between them, any other belongs beside its own edge`() {
        val between = Box(126.0, 40.0, 48.0, 16.0)
        fun tidied(kind: String): Box = LabelTidier.tidy(
            listOf(edge("M100 0V100", between, kind, to = "await", from = "one"), edge("M200 0V100", null, kind, to = "await", from = "other")),
            emptyList(),
            emptyList(),
            emptyList(),
        ).edges.first().label!!.box
        assertEquals(between, tidied("wakes"))
        assertEquals(Box(102.0, 40.0, 48.0, 16.0), tidied("flow"))
    }

    @Test
    fun `labels are moved in the order of their edges, each clear of those before it`() {
        // Two labels in the same place, each beside its own of two edges that run down side by side.
        val edges = listOf(edge("M200 210V338", Box(206.0, 250.0, 48.0, 16.0)), edge("M210 210V338", Box(216.0, 250.0, 48.0, 16.0), from = "other"))
        val tidy = LabelTidier.tidy(edges, listOf(card), listOf(loop), emptyList()).edges.map { it.label!!.box }
        assertEquals(emptyList(), tidy.filter { box -> tidy.any { it !== box && it.intersects(box) } })
        assertEquals(tidy, LabelTidier.tidy(edges, listOf(card), listOf(loop), emptyList()).edges.map { it.label!!.box })
    }
}
