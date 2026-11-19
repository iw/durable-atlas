package io.github.iw.durableatlas.render.svg.compose

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.render.limit.Deadline
import io.github.iw.durableatlas.render.svg.Geometry
import io.github.iw.durableatlas.render.svg.engine.Flow
import io.github.iw.durableatlas.render.svg.scene.Scene
import io.github.iw.durableatlas.render.svg.scene.SceneCard
import io.github.iw.durableatlas.render.svg.scene.SceneEdge
import io.github.iw.durableatlas.render.svg.scene.SceneItem
import io.github.iw.durableatlas.render.svg.scene.SceneRegion
import java.util.BitSet

/**
 * One row of the main flow.
 *
 * @param entry the edge that leads into this row from the one above; null for the first row
 */
internal class Row(val items: List<SceneItem>, val placed: Placed, val entry: SceneEdge?)

/** How the breaks between the rows of a flow are found. */
internal enum class RowSearch {
    /**
     * The best breaks, with all that is left of the flow placed for every row to learn where it
     * can end. The time that takes grows much faster than the flow does: minutes for a `try`
     * around a hundred steps. No diagram is drawn this way. It is here as what [NEAR] is
     * measured against: the two are tested to arrange the same documents alike.
     */
    WHOLE,

    /**
     * The best breaks, with only the steps near the start of a row placed to learn where it can
     * end. This is how the rows of every diagram are found.
     */
    NEAR,

    /** No search: every row ends where it is full. For a diagram whose search takes longer than it is allowed. */
    PLAIN,
}

/**
 * Breaks the main flow into rows no wider than a limit.
 *
 * A row ends on the main path: at an edge that is the way on from its source, the longest where
 * the flow forks. Everything that edge leads to moves to the rows below, and everything else
 * stays, so a branch that ends soon is drawn out in the row of its fork. The edge becomes the
 * connector between the two rows. Other edges that lead from the part that stays to the part
 * that moves, such as those to a shared failure handler, are drawn across as well, those to one
 * step as one bundle; a break that needs many bundles is avoided. Regions are single steps of
 * the flow, so no break falls inside one.
 *
 * Of the possible breaks, the ones are taken that make the whole diagram least tall, which
 * keeps each row to a single line of steps where it can and never uses more rows than needed.
 * Rows of unequal width count a little against a plan, so that of two plans that come out
 * about equally tall the one with the more balanced rows is taken.
 *
 * To learn where a row can end, the search places the steps that follow where the row starts:
 * that tells how wide a row is that ends at each of them. It does so once for every place a row
 * can start at. Only the steps near that start are placed (see [near]): one further along lies
 * beyond the end of the row whatever is placed with it, so it has no say in where the row ends.
 * Placing all that is left instead ([RowSearch.WHOLE]) takes a time that grows much faster than
 * the flow does, because an edge from each of many steps to one far along, as a `try` around
 * them gives, makes each such placement slow. What the steps near the start say can differ by a
 * few units from what they say with everything placed, so a break may, seldom, fall elsewhere
 * than it would then. The diagram is right either way, and the two are tested to find the same
 * breaks in a set of documents.
 *
 * Without a search ([RowSearch.PLAIN]) every row is as long as fits, which is read off the steps
 * near its start as well. A row then also ends where edges to many steps cross the break, which
 * the search avoids. The breaks are of the kind the search makes and the rows are drawn the same
 * way.
 *
 * @param search how the breaks are found
 * @param deadline asked before every placement whether to go on
 */
internal class RowWrapper(
    private val scene: Scene,
    private val placer: FlowPlacer,
    private val wrapWidth: Double,
    private val search: RowSearch = RowSearch.NEAR,
    private val deadline: Deadline = Deadline.none(),
) {
    /** The item of the main flow that each item is, or is nested in. */
    val topOf: Map<String, String> = HashMap<String, String>().also { map ->
        fun mark(item: SceneItem, top: String) {
            map[item.id] = top
            if (item is SceneRegion) item.children.forEach { mark(it, top) }
        }
        scene.main.forEach { mark(it, it.id) }
    }

    /** An edge of the main flow as a connection between two items of its top level. */
    private class Link(val edge: SceneEdge, val from: String, val to: String)

    private val links: List<Link> = scene.mainEdges
        .map { Link(it, topOf.getValue(it.from), topOf.getValue(it.to)) }
        .filter { it.from != it.to }

    private val leaving: Map<String, List<Link>> = links.groupBy { it.from }

    private val position: Map<String, Int> = scene.main.withIndex().associate { (index, item) -> item.id to index }

    /** How many steps follow each item at most. The longest way on is the main path. */
    private val reach = HashMap<String, Int>()

    /** Among the ways on from a step: normal flow first, then the longest way, then the order of the layout. */
    private val successorOrder: Comparator<Link> =
        compareBy<Link>({ if (it.edge.kind == EdgeKind.FLOW) 0 else 1 }, { -reach(it.to) }, { position.getValue(it.to) })

    private val ordered: List<SceneItem> = order()
    private val rank: Map<String, Int> = ordered.withIndex().associate { (index, item) -> item.id to index }

    /** The edges of the main flow in the order they are handed to a placement: by where their ends lie on the main path. */
    private val edgesInOrder: List<SceneEdge> = scene.mainEdges
        .sortedWith(compareBy({ rank.getValue(topOf.getValue(it.from)) }, { rank.getValue(topOf.getValue(it.to)) }))

    fun rows(): List<Row> = when {
        ordered.isEmpty() -> emptyList()
        search == RowSearch.PLAIN -> plainRows()
        else -> plan(ordered, null).rows
    }

    /**
     * A way of breaking what is left of the flow into rows.
     *
     * @param cost how tall it comes out, with something added for every edge drawn across rows besides the connectors
     */
    private class Plan(val rows: List<Row>, val cost: Double) {
        val widest: Double
            get() = rows.maxOf { it.placed.width }

        /** What the plan is judged by: its cost, and a little for rows that differ in width, so that a few units of height do not decide between a balanced plan and a lopsided one. */
        val score: Double
            get() = cost + (widest - rows.minOf { it.placed.width }) * UNEVEN_COST

        /** The same plan for a flow that [entry] leads into. */
        fun enteredBy(entry: SceneEdge?): Plan {
            val first = rows.first()
            return if (first.entry === entry) this else Plan(listOf(Row(first.items, first.placed, entry)) + rows.drop(1), cost)
        }
    }

    /**
     * The best plan for each part of the flow that is left when a row ends, by the steps it
     * holds. Edges that lead to the same step leave the same steps for the rows below, so what
     * is found for one of them serves them all.
     */
    private val plans = HashMap<BitSet, Plan>()

    /** The steps of [items] as a set, which is what tells one part of the flow from another. */
    private fun key(items: List<SceneItem>): BitSet = BitSet(ordered.size).also { set -> items.forEach { set.set(rank.getValue(it.id)) } }

    private fun plan(items: List<SceneItem>, entry: SceneEdge?): Plan = plans.getOrPut(key(items)) {
        val near = if (search == RowSearch.NEAR && wrapWidth > 0) near(items) else null
        var placed = place(near ?: items)
        // Null where everything fits one row. With steps left out, more than a row holds is known to follow.
        var breaks = if (near == null && (wrapWidth <= 0 || placed.width <= wrapWidth)) null else cuts(items, placed, CHOICES)
        if (near != null && breaks.isNullOrEmpty()) {
            // No row can end among the steps near the start, so the others have their say as well.
            placed = place(items)
            breaks = if (placed.width <= wrapWidth) null else cuts(items, placed, CHOICES)
        }
        // Without a place to break it at, the flow is one row however wide; all of it is placed by then.
        breaks.orEmpty()
            .map { cut ->
                val rest = plan(cut.after, cut.edge)
                Plan(
                    rows = listOf(Row(cut.before, cut.placed, null)) + rest.rows,
                    cost = cut.placed.height + Geometry.ROW_GAP + cut.across * ACROSS_COST + rest.cost,
                )
            }
            .minWithOrNull(compareBy({ it.score }, { it.rows.size }, { it.widest }))
            ?: Plan(listOf(Row(items, placed, null)), placed.height)
    }.enteredBy(entry)

    /**
     * The rows without a search: each ends at the widest break that fits. Where a row can end is
     * read off a placement of the steps near its start alone, see [near].
     */
    private fun plainRows(): List<Row> {
        val rows = ArrayList<Row>()
        var items = ordered
        var entry: SceneEdge? = null
        while (true) {
            val near = if (wrapWidth > 0) near(items) else null
            var placed = place(near ?: items)
            val whole = near == null && (wrapWidth <= 0 || placed.width <= wrapWidth)
            // A row is ended where it is full, however many bundles of edges cross the break: it is drawn like any break,
            // and a row that could only end where few cross might have to hold all that is left.
            var cut = if (whole) null else cuts(items, placed, 1, anyAcross = true).firstOrNull()
            if (cut == null && near != null) {
                // No row can end among the steps near the start, so the others have their say as well.
                placed = place(items)
                cut = if (placed.width <= wrapWidth) null else cuts(items, placed, 1, anyAcross = true).firstOrNull()
            }
            if (cut == null) {
                rows += Row(items, placed, entry)
                return rows
            }
            rows += Row(cut.before, cut.placed, entry)
            items = cut.after
            entry = cut.edge
        }
    }

    /**
     * The steps of [items] that can lie in a row that starts where they do, and a few more: those
     * that fewer steps lead to than a row holds of the narrowest and [NEAR_STEPS] more, on the
     * longest way there is from where the flow comes into [items]. Null if that is all of them.
     *
     * A step that lies further along is further to the right than a row is wide, whatever else
     * is placed with it: every step before it takes a column of its own, and the narrowest step
     * with the room beside it takes more than a row has to spare for so many. The two cards of
     * a stack stay together.
     */
    private fun near(items: List<SceneItem>): List<SceneItem>? {
        val steps = (wrapWidth * ESTIMATE_SLACK / NARROWEST_COLUMN).toInt() + NEAR_STEPS
        if (items.size <= steps) return null
        val ids = items.mapTo(HashSet()) { it.id }
        // How many steps lead to each, counted along the edges in the order of the main path; an edge that leads back counts for nothing.
        val depth = HashMap<String, Int>()
        for (item in items) {
            val own = depth.getOrPut(item.id) { 0 }
            for (link in leaving[item.id].orEmpty()) {
                if (link.to !in ids || rank.getValue(link.to) <= rank.getValue(item.id)) continue
                depth[link.to] = maxOf(depth[link.to] ?: 0, own + 1)
            }
        }
        val near = placer.withWholeStacks(items.filter { depth.getValue(it.id) < steps }.mapTo(HashSet()) { it.id })
        return items.filter { it.id in near }.takeIf { it.size < items.size }
    }

    private fun place(items: List<SceneItem>): Placed {
        deadline.check()
        val ids = items.mapTo(HashSet()) { it.id }
        val inside = links.filter { it.from in ids && it.to in ids }
        // The way on from each step is kept straight. Where several such ways meet, only the one
        // that comes along the main path is: the others are branches that join it.
        val first = inside.groupBy { it.from }.values.map { ways -> ways.minWith(successorOrder) }
            .groupBy { it.to }.values.map { meeting -> meeting.minBy { rank.getValue(it.from) }.edge.id }
            .toSet()
        val edges = edgesInOrder.filter { topOf.getValue(it.from) in ids && topOf.getValue(it.to) in ids }
        return placer.place(items, edges, Flow.RIGHT, first + placer.firstInside(edges.filter { topOf.getValue(it.from) != it.from }))
    }

    /**
     * A break after [before].
     *
     * @param edge the connector to the next row
     * @param across to how many steps other edges lead from this row to the rows below; edges to one step run together
     */
    private class Cut(val edge: SceneEdge, val before: List<SceneItem>, val after: List<SceneItem>, val placed: Placed, val across: Int)

    /**
     * The places where the row that starts with [items] can end: the widest few that fit the
     * limit, widest first, [choices] of them at most. If none fits, the narrowest there is, so
     * that the flow still wraps.
     *
     * @param placed a placement of [items], or of those of them that a row can hold; it tells
     *   how wide the row would be that ends at a step, and a step that is not in it lies too far along
     * @param anyAcross whether a row may also end where edges to more than [MAX_ACROSS_TARGETS] steps cross the break
     */
    private fun cuts(items: List<SceneItem>, placed: Placed, choices: Int, anyAcross: Boolean = false): List<Cut> {
        val ids = items.mapTo(HashSet()) { it.id }
        val inside = links.filter { it.from in ids && it.to in ids }
        val leaving = inside.groupBy { it.from }
        val next = inside.groupBy({ it.from }, { it.to })

        /**
         * An edge that may be the way on to the next row.
         *
         * @param estimate where the step before the break ends in the row as it stands, which tells how
         *   wide the row will be at least. A branch that stays in the row can make it wider, which shows
         *   when the row is placed.
         */
        class Open(val link: Link, val estimate: Double)

        class Candidate(val link: Link, val after: Set<String>, val across: Int)

        // Only the way on moves to the next row, never a side branch on its own.
        val open = inside.mapNotNull { link ->
            val ways = leaving.getValue(link.from)
            if (ways.minWith(successorOrder) !== link) return@mapNotNull null
            if (ways.any { it !== link && it.to != link.to && it.edge.kind == EdgeKind.FLOW && reach(it.to) >= reach(link.to) }) return@mapNotNull null
            placed.boxes[link.from]?.let { Open(link, it.right) }
        }.sortedByDescending { it.estimate }

        /** What a break at [link] leaves in the row and takes out of it; null if the row cannot end there. */
        fun candidate(link: Link): Candidate? {
            val after = following(link.to, next)
            if (link.from in after || !placer.keepsStacks { it in ids && it !in after }) return null
            val across = inside.filter { it.from !in after && it.to in after && it !== link }.map { it.to }.distinct().size
            return if (across > MAX_ACROSS_TARGETS && !anyAcross) null else Candidate(link, after, across)
        }

        // Breaks at edges that lead to the same step leave the same row, which is placed once.
        val rows = HashMap<Set<String>, Pair<List<SceneItem>, Placed>>()
        fun cutAt(candidate: Candidate): Cut {
            val (before, row) = rows.getOrPut(candidate.after) {
                val before = items.filter { it.id !in candidate.after }
                before to place(before)
            }
            return Cut(candidate.link.edge, before, items.filter { it.id in candidate.after }, row, candidate.across)
        }

        val fitting = ArrayList<Cut>()
        for (place in open) {
            if (place.estimate > wrapWidth * ESTIMATE_SLACK) continue
            val candidate = candidate(place.link) ?: continue
            // A break that takes little out of the row leaves nearly all of the flow in it, which is slow to place and never fits.
            if (leastWidth(items, inside, candidate.after) > wrapWidth + SURE) continue
            val cut = cutAt(candidate)
            if (cut.placed.width <= wrapWidth) fitting += cut
            if (fitting.size == choices) break
        }
        if (fitting.isNotEmpty()) return fitting
        // Nothing fits: the narrowest row there is, which the last place gives at which a row can end at all.
        val narrowest = open.asReversed().firstNotNullOfOrNull { candidate(it.link) } ?: return emptyList()
        return listOf(cutAt(narrowest))
    }

    /**
     * A width that the row is certain to reach which holds the steps of [items] that are not in
     * [after], found without placing it: the widths, added up, of the steps along the way
     * through the row that has most to add. Steps that follow each other lie side by side, each
     * in a column no narrower than itself, so the row is at least that wide. The two cards of a
     * stack share a column, and a region is taken to be as narrow as its header.
     *
     * That holds as long as the placement puts the target of every edge in a later column than
     * its source, which a layered layout does for a flow that never turns back on itself. Where
     * the flow does turn back, the layout turns some of its edges around, and which ones is not
     * known here: the answer is then zero, and the row is placed to find out.
     *
     * @param inside the edges between the steps of [items]
     */
    private fun leastWidth(items: List<SceneItem>, inside: List<Link>, after: Set<String>): Double {
        val widths = LinkedHashMap<String, Double>()
        for (item in items) {
            if (item.id in after) continue
            val width = when (item) {
                is SceneCard -> item.width
                is SceneRegion -> item.minWidth
            }
            widths.merge(placer.columnOf(item.id), width, ::maxOf)
        }
        val next = HashMap<String, MutableList<String>>()
        val waiting = HashMap<String, Int>()
        for (link in inside) {
            if (link.from in after || link.to in after) continue
            val from = placer.columnOf(link.from)
            val to = placer.columnOf(link.to)
            if (from == to) continue
            next.getOrPut(from) { ArrayList() } += to
            waiting.merge(to, 1, Int::plus)
        }
        // Each column once all the columns that lead to it are done, so that the widest way to it is known.
        val before = HashMap<String, Double>()
        val ready = ArrayDeque(widths.keys.filter { it !in waiting })
        var done = 0
        var widest = 0.0
        while (ready.isNotEmpty()) {
            val column = ready.removeFirst()
            done++
            val reached = (before[column] ?: 0.0) + widths.getValue(column)
            widest = maxOf(widest, reached)
            for (to in next[column].orEmpty()) {
                before.merge(to, reached, ::maxOf)
                if (waiting.merge(to, -1, Int::plus) == 0) ready.addLast(to)
            }
        }
        return if (done == widths.size) widest else 0.0
    }

    /** [from] and everything the flow can reach from it over the edges of [next], which holds the steps each step leads to. */
    private fun following(from: String, next: Map<String, List<String>>): Set<String> {
        val seen = hashSetOf(from)
        val pending = ArrayDeque(listOf(from))
        while (pending.isNotEmpty()) {
            for (id in next[pending.removeFirst()].orEmpty()) {
                if (seen.add(id)) pending.addLast(id)
            }
        }
        return seen
    }

    private fun reach(id: String, onPath: MutableSet<String> = HashSet()): Int {
        reach[id]?.let { return it }
        if (!onPath.add(id)) return 0
        val longest = leaving[id].orEmpty().maxOfOrNull { 1 + reach(it.to, onPath) } ?: 0
        onPath.remove(id)
        reach[id] = longest
        return longest
    }

    /** The items of the top level, depth first along the main path, so that it is placed first and branches after it. */
    private fun order(): List<SceneItem> {
        val byId = scene.main.associateBy { it.id }
        val visited = LinkedHashSet<String>()
        fun visit(id: String) {
            if (!visited.add(id)) return
            leaving[id].orEmpty().sortedWith(successorOrder).forEach { visit(it.to) }
        }
        scene.main.forEach { visit(it.id) }
        return visited.map(byId::getValue)
    }

    private companion object {
        /** A part of a row can come out a little narrower alone than it was in the whole row. */
        const val ESTIMATE_SLACK = 1.1

        /** How many ways of ending a row are compared. More find better breaks and take longer. */
        const val CHOICES = 8

        /** What a bundle of edges drawn across rows, besides the connector, counts for when breaks are compared, in units of height. */
        const val ACROSS_COST = 20.0

        /** What a unit of difference in width between the widest and the narrowest row counts for when plans are compared, in units of height. */
        const val UNEVEN_COST = 0.05

        /** A break is not made where edges to more steps than this would be drawn across it. */
        const val MAX_ACROSS_TARGETS = 3

        /** The least room a step takes along a row, with the gap to the next: no card and no region is narrower than a pill. */
        const val NARROWEST_COLUMN = Geometry.PILL_MIN_WIDTH + Geometry.ROW_SPACING

        /** How many steps beyond those a row can hold are placed with them, so that the last of them lie as they would with more to follow. */
        const val NEAR_STEPS = 4

        /** How much wider than the limit a row must be known to be before it is left unplaced, for what adding up widths can be off by. */
        const val SURE = 1.0
    }
}
