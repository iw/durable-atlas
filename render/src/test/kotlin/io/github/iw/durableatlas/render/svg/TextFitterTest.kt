package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.render.svg.text.FontMetricsMeasurer
import io.github.iw.durableatlas.render.svg.text.TextFitter
import io.github.iw.durableatlas.render.svg.text.TextStyles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextFitterTest {
    private val fitter = TextFitter(FontMetricsMeasurer)

    @Test
    fun `text is measured with the bundled typeface, a little generously`() {
        // Inter SemiBold at 14.5: about 8 units a letter.
        val name = fitter.width("ReserveStock", TextStyles.NAME)
        assertTrue(name in 90.0..110.0, "$name")
        // Bold, with tracking, is wider than the same letters set tight.
        val kind = fitter.width("ACTIVITY", TextStyles.KIND)
        assertTrue(kind > fitter.width("ACTIVITY", TextStyles.KIND.copy(tracking = 0.0)))
        assertEquals(0.0, fitter.width("", TextStyles.NAME))
        assertEquals(fitter.width("ab", TextStyles.NAME) * 2, fitter.width("abab", TextStyles.NAME), 0.5)
        // Characters the typeface lacks still take room, the same on every machine.
        assertTrue(fitter.width("日本語", TextStyles.NAME) > 3 * 14.5)
        assertEquals(name, fitter.width("ReserveStock", TextStyles.NAME))
    }

    @Test
    fun `long text is cut with an ellipsis`() {
        assertEquals("short", fitter.truncate("short", TextStyles.NAME, 100.0))
        val cut = fitter.truncate("a very long name that will never fit on a card", TextStyles.NAME, 100.0)
        assertTrue(cut.endsWith("…") && cut.length < 20, cut)
        assertTrue(fitter.width(cut, TextStyles.NAME) <= 100.0)
        assertTrue("a very long name that will never fit on a card".startsWith(cut.dropLast(1)))
    }

    @Test
    fun `a name breaks into two lines where a reader would break it`() {
        assertEquals(listOf("FraudCheck"), fitter.twoLines("FraudCheck", TextStyles.NAME, 196.0))
        assertEquals(listOf("continue-as-new", "suggested?"), fitter.twoLines("continue-as-new suggested?", TextStyles.NAME, 130.0))
        assertEquals(listOf("ReserveInventory", "ForBackorder"), fitter.twoLines("ReserveInventoryForBackorder", TextStyles.NAME, 150.0))
        assertEquals(listOf("com.example.", "orders.Shop"), fitter.twoLines("com.example.orders.Shop", TextStyles.NAME, 110.0))
        val lines = fitter.twoLines("one two three four five six seven eight nine ten eleven twelve", TextStyles.NAME, 120.0)
        assertEquals(2, lines.size)
        assertTrue(lines[1].endsWith("…"))
        lines.forEach { assertTrue(fitter.width(it, TextStyles.NAME) <= 120.0, it) }
        // A word without a place to break is broken where the line is full.
        val solid = fitter.twoLines("x".repeat(60), TextStyles.NAME, 100.0)
        assertEquals(2, solid.size)
        assertTrue(solid[0].all { it == 'x' } && solid[0].length > 5)
    }

    @Test
    fun `code breaks between its words, and inside one only if it does not fit otherwise`() {
        fun lines(text: String, width: Double = CARD) = fitter.twoLines(text, TextStyles.NAME, width)
        // At a space rather than at the dot or the capital further along the line.
        assertEquals(listOf("queue.isEmpty() or", "closed.get()"), lines("queue.isEmpty() or closed.get()", 150.0))
        assertEquals(listOf("pendingOrders.size >", "maxOpenOrders"), lines("pendingOrders.size > maxOpenOrders", 170.0))
        // After an operator where there are no spaces; never between an operator and what it belongs to.
        assertEquals(listOf("attempts>=", "maxAttempts"), lines("attempts>=maxAttempts", 110.0))
        assertEquals(listOf("order.total>limit&&", "!order.approved"), lines("order.total>limit&&!order.approved", 170.0))
        assertEquals(listOf("first,second,", "third"), lines("first,second,third", 110.0))
        // Two lines hold it only if a word is broken: then after its dot, not before its capital.
        assertEquals(listOf("not messageQueue.", "isEmpty() or exit"), lines("not messageQueue.isEmpty() or exit"))
        assertEquals(listOf("not pendingOrders.", "isEmpty() or done"), lines("not pendingOrders.isEmpty() or done"))
        // Without a dot, after other punctuation or before a capital.
        assertEquals(listOf("isReadyFor", "DispatchToday"), lines("isReadyForDispatchToday", 125.0))
        assertEquals(listOf("load_order_", "from_archive"), lines("load_order_from_archive", 110.0))
        // What takes more than two lines is not broken inside a word either, as long as a word fits the first line.
        val long = lines("orders.filter { it.isOpen() }.sumOf { it.total } > creditLimit.forCustomer(customerId)")
        assertEquals("orders.filter { it.isOpen()", long[0])
        assertTrue(long[1].startsWith("}.sumOf { it.total } >") && long[1].endsWith("…"), long[1])
        val longer = lines("approvals >= requiredApprovals or rejectionReason != null")
        assertEquals("approvals >=", longer[0])
        assertTrue(longer[1].startsWith("requiredApprovals or rej") && longer[1].endsWith("…"), longer[1])
        (long + longer).forEach { assertTrue(fitter.width(it, TextStyles.NAME) <= CARD, it) }
    }

    @Test
    fun `text goes on as many lines as it takes, up to a limit, each as full as its words allow`() {
        fun lines(text: String, count: Int, width: Double = 200.0) = fitter.lines(text, TextStyles.LABEL, width, count)
        assertEquals(listOf("paid"), lines("paid", 3))
        assertEquals(listOf("messageQueue.isEmpty() and exit"), lines("messageQueue.isEmpty() and exit", 3, 220.0))
        assertEquals(listOf("messageQueue.isEmpty() and", "exit"), lines("messageQueue.isEmpty() and exit", 3))
        assertEquals(
            listOf("pendingOrders.isEmpty() and", "request.options.flags.isEmpty()", "and not cancelled"),
            lines("pendingOrders.isEmpty() and request.options.flags.isEmpty() and not cancelled", 3),
        )
        // No more lines than allowed: the last is cut. One line allowed is a cut line.
        val two = lines("pendingOrders.isEmpty() and request.options.flags.isEmpty() and not cancelled", 2)
        assertEquals("pendingOrders.isEmpty() and", two[0])
        assertTrue(two.size == 2 && two[1].startsWith("request.options.flags.isE") && two[1].endsWith("…"), two.toString())
        val one = lines("pendingOrders.isEmpty() and request.options.flags.isEmpty()", 1)
        assertTrue(one.size == 1 && one[0].startsWith("pendingOrders.isEmpty() and") && one[0].endsWith("…"), one.toString())
        // A word longer than a line is broken after a dot, where the lines hold the text only that way.
        assertEquals(listOf("request.options.flags.", "isEmptyOrHoldsOnlyDefaults()"), lines("request.options.flags.isEmptyOrHoldsOnlyDefaults()", 3))
        (lines("pendingOrders.isEmpty() and request.options.flags.isEmpty() and not cancelled", 3) + two + one).forEach {
            assertTrue(fitter.width(it, TextStyles.LABEL) <= 200.0, it)
        }
        // Two lines are what a card has.
        assertEquals(fitter.lines("not messageQueue.isEmpty() or exit", TextStyles.NAME, CARD, 2), fitter.twoLines("not messageQueue.isEmpty() or exit", TextStyles.NAME, CARD))
    }

    @Test
    fun `a name that is too long for its line can lose its beginning`() {
        val name = "com.example.orders.fulfilment.Dispatch.DispatchWorkflowImpl"
        assertEquals(name, fitter.ending(name, TextStyles.HEADING_DETAIL, 600.0))
        // Whole parts go first.
        assertEquals("…fulfilment.Dispatch.DispatchWorkflowImpl", fitter.ending(name, TextStyles.HEADING_DETAIL, 300.0))
        assertEquals("…DispatchWorkflowImpl", fitter.ending(name, TextStyles.HEADING_DETAIL, 160.0))
        val cut = fitter.ending(name, TextStyles.HEADING_DETAIL, 100.0)!!
        assertTrue(cut.startsWith("…") && name.endsWith(cut.drop(1)) && cut.length in 8..16, cut)
        assertTrue(fitter.width(cut, TextStyles.HEADING_DETAIL) <= 100.0)
        assertEquals(null, fitter.ending(name, TextStyles.HEADING_DETAIL, 5.0))
    }

    private companion object {
        /** The room a card as wide as cards get has for a name. */
        const val CARD = (Geometry.CARD_MAX_WIDTH - Geometry.TILE_WIDTH - Geometry.CARD_TEXT_PADDING) * Geometry.TEXT_SLACK
    }
}
