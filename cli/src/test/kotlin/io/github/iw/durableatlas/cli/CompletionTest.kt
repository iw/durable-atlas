package io.github.iw.durableatlas.cli

import io.github.iw.durableatlas.ir.IrStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The exit status of a command that did all of its work, and the limit on unknown nodes behind it. */
class CompletionTest {
    @Test
    fun `a run without anything to flag is a success`() {
        assertEquals(ExitCode.SUCCESS, Completion.status(degradedFallback = false, overThreshold = false))
        assertEquals(0, ExitCode.SUCCESS)
    }

    @Test
    fun `a workflow over the limit is status 2`() {
        assertEquals(2, Completion.status(degradedFallback = false, overThreshold = true))
    }

    @Test
    fun `a model that had to be degraded is status 3, also when a workflow is over the limit`() {
        assertEquals(3, Completion.status(degradedFallback = true, overThreshold = false))
        assertEquals(3, Completion.status(degradedFallback = true, overThreshold = true))
    }

    @Test
    fun `a diagram that was given up when its time ran out is status 2, and a degraded model still comes first`() {
        assertEquals(2, Completion.status(degradedFallback = false, overThreshold = false, notDrawn = true))
        assertEquals(2, Completion.status(degradedFallback = false, overThreshold = true, notDrawn = true))
        assertEquals(3, Completion.status(degradedFallback = true, overThreshold = false, notDrawn = true))
        assertEquals(ExitCode.SUCCESS, Completion.status(degradedFallback = false, overThreshold = false, notDrawn = false))
        assertEquals(2, ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED)
    }

    @Test
    fun `the share of unknown nodes is taken of all nodes of the workflow`() {
        assertEquals(0.0, Unresolved.percentage(IrStats(nodes = 6, unknown = 0)))
        assertEquals(20.0, Unresolved.percentage(IrStats(nodes = 5, unknown = 1)))
        assertEquals(100.0, Unresolved.percentage(IrStats(nodes = 3, unknown = 3)))
        assertEquals(42.9, Unresolved.rounded(Unresolved.percentage(IrStats(nodes = 28, unknown = 12))))
        // A document without nodes cannot be written, but must not divide by zero either.
        assertEquals(0.0, Unresolved.percentage(IrStats(nodes = 0, unknown = 0)))
    }

    @Test
    fun `only more than the limit is too much`() {
        val fifth = IrStats(nodes = 5, unknown = 1)

        assertTrue(Unresolved.exceeds(fifth, 19.9))
        assertFalse(Unresolved.exceeds(fifth, 20.0))
        assertFalse(Unresolved.exceeds(fifth, 100.0))
        assertTrue(Unresolved.exceeds(fifth, 0.0))
        assertFalse(Unresolved.exceeds(IrStats(nodes = 5, unknown = 0), 0.0))
        assertFalse(Unresolved.exceeds(IrStats(nodes = 5, unknown = 5), 100.0))
    }

    @Test
    fun `without a limit nothing is too much`() {
        assertFalse(Unresolved.exceeds(IrStats(nodes = 3, unknown = 3), null))
    }

    @Test
    fun `a workflow over the limit is described with its counts and both percentages`() {
        assertEquals(
            "OrderWorkflow: 12 of 28 nodes are unknown (42.9 %), more than the 12.5 % allowed",
            Unresolved.describe("OrderWorkflow", IrStats(nodes = 28, unknown = 12), 12.5),
        )
    }

    @Test
    fun `a table has columns as wide as their widest cell and no trailing spaces`() {
        val table = Summaries.table(
            header = listOf("Workflow type", "Nodes", "Unknown", "File"),
            rows = listOf(
                listOf("A", "5", "1", "/out/A.md"),
                listOf("LongerWorkflowName", "120", "0", "/out/LongerWorkflowName.md"),
            ),
            rightAligned = setOf(1, 2),
        )

        assertEquals(
            """
            Workflow type       Nodes  Unknown  File
            A                       5        1  /out/A.md
            LongerWorkflowName    120        0  /out/LongerWorkflowName.md
            """.trimIndent(),
            table,
        )
        assertEquals("Workflow type  Nodes", Summaries.table(listOf("Workflow type", "Nodes"), emptyList()))
    }
}
